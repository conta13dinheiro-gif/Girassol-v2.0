package com.enhikyy.girassol.audio

import android.app.ActivityManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.sqrt

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
 * serialized on a background worker and output uses a bounded stereo pan.
 */
object NeuralSpeechEngine {
    private const val MODEL_LIMIT = 380L * 1024L * 1024L
    private const val TOTAL_LIMIT = 410L * 1024L * 1024L
    private const val ARCHIVE_LIMIT = 380L * 1024L * 1024L
    private const val ENTRY_LIMIT = 4096
    private const val MAX_TEXT_CHARS = 240
    private const val MAX_AUDIO_SECONDS = 90
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); runnable.run() }, "Girassol-Kokoro-TTS")
    }
    private val stateMutable = MutableStateFlow(NeuralSpeechState())
    val state = stateMutable.asStateFlow()

    @Volatile private var application: Context? = null
    @Volatile private var synthesis: OfflineTts? = null
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var currentTask: Future<*>? = null
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
        require(context.contentResolver.openInputStream(uri) != null) { "Não foi possível abrir o pacote de voz" }
        stop()
        stateMutable.value = stateMutable.value.copy(busy = true, message = "Validando pacote Kokoro PT-BR…")
        staging.deleteRecursively()
        require(staging.mkdirs()) { "Não foi possível criar a área temporária da voz" }
        try {
            val seen = HashSet<String>()
            var entryCount = 0
            var expanded = 0L
            var espeakEntries = 0
            var modelBytes = 0L
            ZipInputStream(BufferedInputStream(context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Arquivo de voz indisponível"))).use { zip ->
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
                        basename.equals("LICENSE", ignoreCase = true) ||
                            basename.equals("README.md", ignoreCase = true) -> basename.lowercase()
                        else -> null
                    }
                    val output = if (relative == null || entry.isDirectory) null else {
                        require(seen.add(relative)) { "Arquivo duplicado no pacote Kokoro: $relative" }
                        File(staging, relative).also { target ->
                            require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                            target.parentFile?.let { require(it.exists() || it.mkdirs()) }
                        }
                    }
                    val fileBuffer = if (output != null) ByteArrayOutputStream() else null
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        expanded = Math.addExact(expanded, read.toLong())
                        require(expanded <= TOTAL_LIMIT) { "Conteúdo descompactado excede 410 MiB" }
                        if (output != null) {
                            fileBuffer!!.write(buffer, 0, read)
                            if (relative == "model.onnx") {
                                modelBytes = Math.addExact(modelBytes, read.toLong())
                                require(modelBytes <= MODEL_LIMIT) { "Modelo ONNX excede 380 MiB" }
                            }
                        }
                    }
                    if (output != null) {
                        val bytes = fileBuffer!!.toByteArray()
                        output.writeBytes(bytes)
                        if (relative!!.startsWith("espeak-ng-data/")) espeakEntries++
                        writeHashLine(relative, bytes)
                    }
                    zip.closeEntry()
                }
            }
            require(File(staging, "model.onnx").length() in (100L * 1024L * 1024L)..MODEL_LIMIT) {
                "model.onnx não corresponde ao pacote Kokoro-82M esperado"
            }
            require(File(staging, "voices.bin").length() >= 1024L * 1024L) { "voices.bin ausente ou incompleto" }
            require(File(staging, "tokens.txt").length() in 100L..(1024L * 1024L)) { "tokens.txt ausente ou inválido" }
            require(espeakEntries >= 20 && File(staging, "espeak-ng-data").isDirectory) {
                "Dados de fonemização espeak-ng-data ausentes"
            }
            File(staging, "bundle.sha256").writeText(
                staging.walkTopDown().filter { it.isFile && it.name != "bundle.sha256" }
                    .sortedBy { it.relativeTo(staging).invariantSeparatorsPath }
                    .joinToString("\n") { file ->
                        "${sha256(file.readBytes())}  ${file.relativeTo(staging).invariantSeparatorsPath}"
                    } + "\n"
            )
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

    fun speak(text: String, voiceId: Int = requestedVoice, pan: Float = 0f, speed: Float = 1f) {
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
                val engine = synthesis ?: createEngine().also { synthesis = it }
                val audio = engine.generate(text, sid = voiceId, speed = speed)
                check(!Thread.currentThread().isInterrupted)
                val sampleRate = audio.sampleRate
                require(sampleRate in 8000..96000 && audio.samples.isNotEmpty()) { "Saída de áudio Kokoro inválida" }
                require(audio.samples.size.toLong() <= sampleRate.toLong() * MAX_AUDIO_SECONDS) {
                    "A fala gerada excede o limite de 90 segundos"
                }
                val leftGain = sqrt(((1f - pan) / 2f).toDouble()).toFloat()
                val rightGain = sqrt(((1f + pan) / 2f).toDouble()).toFloat()
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
                track.play()
                stateMutable.value = stateMutable.value.copy(busy = false, playing = true, message = "Fala Kokoro PT-BR em reprodução")
                val chunkFrames = 2048
                val stereo = FloatArray(chunkFrames * 2)
                var offset = 0
                while (offset < audio.samples.size && !Thread.currentThread().isInterrupted) {
                    val frames = minOf(chunkFrames, audio.samples.size - offset)
                    for (i in 0 until frames) {
                        val mono = audio.samples[offset + i].coerceIn(-1f, 1f)
                        stereo[i * 2] = mono * leftGain
                        stereo[i * 2 + 1] = mono * rightGain
                    }
                    val written = track.write(stereo, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
                    check(written > 0) { "A reprodução de fala foi interrompida" }
                    offset += written / 2
                }
                if (!Thread.currentThread().isInterrupted) {
                    track.stop()
                    stateMutable.value = stateMutable.value.copy(playing = false, message = "Fala concluída")
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
            }
        }
    }

    fun stop() {
        currentTask?.cancel(true)
        currentTask = null
        runCatching { currentTrack?.pause(); currentTrack?.flush(); currentTrack?.release() }
        currentTrack = null
        stateMutable.value = stateMutable.value.copy(busy = false, playing = false)
    }

    fun remove() {
        stop()
        synthesis?.free()
        synthesis = null
        root.deleteRecursively()
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

    private fun isBundleValid(): Boolean =
        File(root, "model.onnx").isFile && File(root, "voices.bin").isFile &&
            File(root, "tokens.txt").isFile && File(root, "espeak-ng-data").isDirectory

    private fun normalizeZipPath(path: String): String {
        val clean = path.replace('\\', '/')
        require(clean.isNotBlank() && !clean.startsWith('/') && !clean.contains(':'))
        val parts = clean.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.isNotEmpty() && parts.none { it == ".." } && parts.size <= 12)
        return parts.joinToString("/")
    }

    private fun writeHashLine(relative: String, bytes: ByteArray) {
        val hashFile = File(staging, ".hashes.tmp")
        hashFile.appendText("${sha256(bytes)}  $relative\n")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
