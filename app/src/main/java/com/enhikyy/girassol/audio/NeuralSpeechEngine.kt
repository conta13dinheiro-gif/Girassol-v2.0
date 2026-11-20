package com.enhikyy.girassol.audio

import android.app.ActivityManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Process
import android.os.StatFs
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.asin
import com.enhikyy.girassol.core.WorldState

data class NeuralSpeechState(
    val installed: Boolean = false,
    val busy: Boolean = false,
    val playing: Boolean = false,
    val voiceId: Int = 42,
    val message: String = "Voz neural offline não instalada"
)

/**
 * Optional Kokoro-82M Brazilian Portuguese speech. The ONNX model is imported
 * into private app storage; it is never bundled with the APK. Synthesis is
 * serialized on a bounded worker; native HRTF processes reusable 2048-frame chunks.
 */
object NeuralSpeechEngine {
    private const val MODEL_LIMIT = 380L * 1024L * 1024L
    private const val TOTAL_LIMIT = 410L * 1024L * 1024L
    private const val ARCHIVE_LIMIT = 380L * 1024L * 1024L
    private const val ENTRY_LIMIT = 4096
    private const val MAX_TEXT_CHARS = 240
    private const val MAX_AUDIO_SECONDS = 90
    private val worker = ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),{ runnable ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); runnable.run() }, "Girassol-Kokoro-TTS")
    },ThreadPoolExecutor.DiscardOldestPolicy())
    private val stateMutable = MutableStateFlow(NeuralSpeechState())
    val state = stateMutable.asStateFlow()

    @Volatile private var application: Context? = null
    @Volatile private var synthesis: OfflineTts? = null
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var currentTask: Future<*>? = null
    private val engineLock = Any()
    @Volatile private var requestedVoice = 42

    private val root: File
        get() = File(checkNotNull(application).filesDir, "speech/kokoro-pt-br")
    private val staging: File
        get() = File(checkNotNull(application).filesDir, "speech/.kokoro-pt-br.tmp")

    fun initialize(context: Context) {
        if (application != null) return
        application = context.applicationContext
        val installed = isBundleValid()
        stateMutable.value = stateMutable.value.copy(
            installed = installed,
            message = if (installed) "Kokoro PT-BR pronto para uso offline" else "Importe um pacote Kokoro PT-BR para ativar a voz"
        )
    }

    fun setVoice(voiceId: Int) {
        require(voiceId in 42..44) { "Voz Kokoro PT-BR não reconhecida" }
        requestedVoice = voiceId
        stateMutable.value = stateMutable.value.copy(voiceId = voiceId)
    }

    /**
     * Accepts the official Kokoro-82M PT-BR bundle layout:
     * model.onnx, voices.bin, tokens.txt and espeak-ng-data/.
     * Paths are normalized and every expanded byte is counted to prevent ZIP bombs.
     */
    fun importKokoroBundle(context: Context, uri: Uri) {
        initialize(context)
        stop()
        val available = StatFs(context.applicationContext.filesDir.absolutePath).availableBytes
        require(available >= 800L * 1024L * 1024L) {
            "São necessários pelo menos 800 MiB livres para extrair o modelo FP32 com segurança"
        }
        stateMutable.value = stateMutable.value.copy(busy = true, message = "Validando pacote Kokoro PT-BR…")
        staging.deleteRecursively()
        require(staging.mkdirs()) { "Não foi possível criar a área temporária da voz" }
        try {
            val seen = HashSet<String>()
            val hashes = ArrayList<String>()
            var entryCount = 0
            var expanded = 0L
            var espeakEntries = 0
            var modelBytes = 0L
            var voiceBytes = 0L
            val rawInput = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Não foi possível abrir o pacote de voz")
            ZipInputStream(BufferedInputStream(BoundedInputStream(rawInput, ARCHIVE_LIMIT))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    require(entryCount <= ENTRY_LIMIT) { "Pacote de voz excede $ENTRY_LIMIT arquivos" }
                    val sourcePath = normalizeZipPath(entry.name)
                    val segments = sourcePath.split('/')
                    val espeakIndex = segments.indexOf("espeak-ng-data")
                    val basename = segments.last()
                    val relative = when {
                        basename == "model.onnx" -> "model.onnx"
                        basename == "voices.bin" -> "voices.bin"
                        basename == "tokens.txt" -> "tokens.txt"
                        espeakIndex >= 0 && segments.size > espeakIndex + 1 ->
                            "espeak-ng-data/" + segments.drop(espeakIndex + 1).joinToString("/")
                        basename.equals("LICENSE", ignoreCase = true) -> "LICENSE"
                        else -> null
                    }
                    val output = if (relative == null || entry.isDirectory) null else {
                        require(seen.add(relative)) { "Arquivo duplicado no pacote Kokoro: $relative" }
                        File(staging, relative).also { target ->
                            require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                            target.parentFile?.let { require(it.exists() || it.mkdirs()) }
                        }
                    }
                    val digest = if (output != null) MessageDigest.getInstance("SHA-256") else null
                    FileOutputStreamOrNull(output).use { destination ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            expanded = Math.addExact(expanded, read.toLong())
                            require(expanded <= TOTAL_LIMIT) { "Conteúdo descompactado excede 410 MiB" }
                            if (output != null) {
                                when (relative) {
                                    "model.onnx" -> {
                                        modelBytes = Math.addExact(modelBytes, read.toLong())
                                        require(modelBytes <= MODEL_LIMIT) { "Modelo ONNX excede 380 MiB" }
                                    }
                                    "voices.bin" -> {
                                        voiceBytes = Math.addExact(voiceBytes, read.toLong())
                                        require(voiceBytes <= 64L * 1024L * 1024L) { "voices.bin excede 64 MiB" }
                                    }
                                }
                                destination!!.write(buffer, 0, read)
                                digest!!.update(buffer, 0, read)
                            }
                        }
                    }
                    if (relative?.startsWith("espeak-ng-data/") == true && output != null) {
                        espeakEntries++
                    }
                    if (output != null) hashes += "${digest!!.digest().joinToString("") { "%02x".format(it) }}  $relative"
                    zip.closeEntry()
                }
            }
            require(modelBytes in (100L * 1024L * 1024L)..MODEL_LIMIT) {
                "model.onnx não corresponde ao pacote Kokoro-82M esperado"
            }
            require(voiceBytes >= 1024L * 1024L) { "voices.bin ausente ou incompleto" }
            require(File(staging, "tokens.txt").length() in 100L..(1024L * 1024L)) {
                "tokens.txt ausente ou inválido"
            }
            require(espeakEntries >= 20 && File(staging, "espeak-ng-data").isDirectory) {
                "Dados de fonemização espeak-ng-data ausentes"
            }
            File(staging, "bundle.sha256").writeText(hashes.sorted().joinToString("\n", postfix = "\n"))
            synchronized(engineLock) {
                synthesis?.free()
                synthesis = null
                val backup = File(staging.parentFile, ".kokoro-pt-br.old")
                backup.deleteRecursively()
                if (root.exists()) require(root.renameTo(backup)) { "Não foi possível substituir a voz anterior" }
                if (!staging.renameTo(root)) {
                    if (backup.exists()) backup.renameTo(root)
                    throw IllegalStateException("Não foi possível finalizar a instalação do modelo")
                }
                backup.deleteRecursively()
            }
            stateMutable.value = stateMutable.value.copy(
                installed = true, busy = false, playing = false,
                message = "Kokoro PT-BR importado e verificado por hash"
            )
        } catch (error: Exception) {
            staging.deleteRecursively()
            stateMutable.value = stateMutable.value.copy(
                installed = isBundleValid(), busy = false, playing = false,
                message = error.message ?: "Falha ao importar o modelo de voz"
            )
            throw error
        }
    }


    fun speak(text: String, voiceId: Int = requestedVoice, pan: Float = 0f, speed: Float = 1f,world: WorldState?=null,source: String?=null) {
        initialize(checkNotNull(application))
        require(text.isNotBlank() && text.length <= MAX_TEXT_CHARS && text.none { it.isISOControl() }) {
            "O texto falado deve conter até 240 caracteres imprimíveis"
        }
        require(voiceId in 42..44) { "Voz Kokoro PT-BR não reconhecida" }
        require(pan.isFinite() && pan in -1f..1f && speed.isFinite() && speed in .75f..1.25f)
        check(isBundleValid()) { "Importe o modelo Kokoro PT-BR antes de falar" }
        stop()
        stateMutable.value = stateMutable.value.copy(
            installed = true, busy = true, playing = false, voiceId = voiceId,
            message = "Gerando fala neural offline…"
        )
        currentTask = worker.submit {
            try {
                check(!Thread.currentThread().isInterrupted)
                val memory = ActivityManager.MemoryInfo().also {
                    checkNotNull(application).getSystemService(ActivityManager::class.java).getMemoryInfo(it)
                }
                check(!memory.lowMemory && memory.availMem >= 1_200L * 1024L * 1024L) {
                    "RAM livre insuficiente para carregar Kokoro-82M com segurança"
                }
                val audio = synchronized(engineLock) {
                    val engine = synthesis ?: createEngine().also { synthesis = it }
                    val nativeRate=engine.sampleRate()
                    require(nativeRate in 8000..96000)
                    val sampleLimit=nativeRate.toLong()*MAX_AUDIO_SECONDS
                    var emitted=0L
                    val generated=engine.generateWithCallback(text,sid=voiceId,speed=speed) { samples ->
                        emitted+=samples.size
                        if(Thread.currentThread().isInterrupted || emitted>sampleLimit) 0 else 1
                    }
                    check(emitted<=sampleLimit) { "A fala gerada excede o limite de 90 segundos" }
                    generated

                }
                check(!Thread.currentThread().isInterrupted)
                val sampleRate = audio.sampleRate
                require(sampleRate in 8000..96000 && audio.samples.isNotEmpty()) { "Saída de áudio Kokoro inválida" }
                require(audio.samples.size.toLong() <= sampleRate.toLong() * MAX_AUDIO_SECONDS) {
                    "A fala gerada excede o limite de 90 segundos"
                }
                val parameters=if(world!=null && source!=null) NativeSpatialAudio.trace(world,source)
                    else AcousticParameters(asin(pan.toDouble()),0.0,0.0)
                val spatializer=NativeSpatialAudio()
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
                )
                check(minBuffer > 0) { "Formato de saída não suportado pelo dispositivo" }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(max(minBuffer, 16 * 1024))
                    .build()
                currentTrack = track
                check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack não pôde ser inicializada" }
                if(NativeAudio.available) NativeAudio.duck(true)
                track.play()
                stateMutable.value = stateMutable.value.copy(busy = false, playing = true, message = "Fala Kokoro PT-BR em reprodução")
                val chunkFrames = 2048

                var offset = 0
                while (offset < audio.samples.size && !Thread.currentThread().isInterrupted) {
                    val frames = minOf(chunkFrames, audio.samples.size - offset)
                    val stereo=spatializer.process(audio.samples,offset,frames,sampleRate,parameters)
                    var writtenSamples=0
                    while(writtenSamples<frames*2 && !Thread.currentThread().isInterrupted) {
                        val written=track.write(stereo,writtenSamples,frames*2-writtenSamples,AudioTrack.WRITE_BLOCKING)
                        check(written>0 && written%2==0) { "A reprodução de fala foi interrompida" }
                        writtenSamples+=written
                    }
                    offset+=frames
                }
                if (!Thread.currentThread().isInterrupted) {
                    val deadline = android.os.SystemClock.elapsedRealtime() + MAX_AUDIO_SECONDS * 1000L + 5000L
                    while (track.playbackHeadPosition.toLong() < audio.samples.size &&
                        !Thread.currentThread().isInterrupted &&
                        android.os.SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(15)
                    }
                    check(track.playbackHeadPosition.toLong() >= audio.samples.size || Thread.currentThread().isInterrupted) {
                        "A saída de áudio não avançou até o fim"
                    }
                    if (!Thread.currentThread().isInterrupted) {
                        track.stop()
                        stateMutable.value = stateMutable.value.copy(playing = false, message = "Fala concluída")
                    }
                }
                track.release()
                currentTrack = null
            } catch (error: Exception) {
                runCatching { currentTrack?.pause(); currentTrack?.flush(); currentTrack?.release() }
                currentTrack = null
                stateMutable.value = stateMutable.value.copy(
                    busy = false, playing = false,
                    message = error.message ?: "Falha na síntese Kokoro"
                )
            } catch(error: OutOfMemoryError) {
                runCatching { currentTrack?.pause();currentTrack?.flush();currentTrack?.release() }
                currentTrack=null
                stateMutable.value=stateMutable.value.copy(busy=false,playing=false,message="Memória insuficiente para esta fala")
            } finally {
                if(NativeAudio.available) NativeAudio.duck(false)
            }
        }
    }

    fun stop() {
        if(NativeAudio.available) NativeAudio.duck(false)
        currentTask?.cancel(true)
        currentTask = null
        runCatching { currentTrack?.pause(); currentTrack?.flush(); currentTrack?.release() }
        currentTrack = null
        stateMutable.value = stateMutable.value.copy(busy = false, playing = false)
    }

    fun remove() {
        stop()
        synchronized(engineLock) {
            synthesis?.free()
            synthesis = null
            root.deleteRecursively()
        }
        stateMutable.value = stateMutable.value.copy(
            installed = false, busy = false, playing = false,
            message = "Modelo de voz removido do armazenamento privado"
        )
    }

    private fun createEngine(): OfflineTts {
        val memory = ActivityManager.MemoryInfo().also {
            checkNotNull(application).getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        check(!memory.lowMemory && memory.availMem >= 1_200L * 1024L * 1024L) {
            "RAM livre insuficiente para inicializar Kokoro-82M"
        }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(root, "model.onnx").absolutePath,
                    voices = File(root, "voices.bin").absolutePath,
                    tokens = File(root, "tokens.txt").absolutePath,
                    dataDir = File(root, "espeak-ng-data").absolutePath,
                    lang = "pt"
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1,
            silenceScale = .2f
        )
        return OfflineTts(config = config)
    }

    private fun isBundleValid(): Boolean {
        val model = File(root, "model.onnx")
        val voices = File(root, "voices.bin")
        val tokens = File(root, "tokens.txt")
        val espeak = File(root, "espeak-ng-data")
        val manifest = File(root, "bundle.sha256")
        if (!model.isFile || model.length() !in (100L * 1024L * 1024L)..MODEL_LIMIT) return false
        if (!voices.isFile || voices.length() < 1024L * 1024L) return false
        if (!tokens.isFile || !espeak.isDirectory || !manifest.isFile) return false
        val entries = runCatching { manifest.readLines().toSet() }.getOrNull() ?: return false
        return entries.any { it.endsWith("  model.onnx") } &&
            entries.any { it.endsWith("  voices.bin") } &&
            entries.any { it.endsWith("  tokens.txt") } &&
            entries.count { it.contains("  espeak-ng-data/") } >= 20
    }

    private fun normalizeZipPath(path: String): String {
        val clean = path.replace('\\', '/')
        require(clean.isNotBlank() && !clean.startsWith('/') && !clean.contains(':'))
        val parts = clean.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.isNotEmpty() && parts.none { it == ".." } && parts.size <= 12)
        return parts.joinToString("/")
    }

    private class BoundedInputStream(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
        private var consumed = 0L
        @Throws(IOException::class)
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count(1)
            return value
        }
        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val bytesRead = super.read(buffer, offset, length)
            if (bytesRead > 0) count(bytesRead)
            return bytesRead
        }
        private fun count(bytes: Int) {
            consumed = Math.addExact(consumed, bytes.toLong())
            if (consumed > maximum) throw IOException("Arquivo ZIP comprimido excede 380 MiB")
        }
    }

    private class FileOutputStreamOrNull(file: File?) : AutoCloseable {
        private val stream = file?.let { FileOutputStream(it) }
        fun write(bytes: ByteArray, offset: Int, length: Int) { stream?.write(bytes, offset, length) }
        override fun close() { stream?.close() }
    }
}
