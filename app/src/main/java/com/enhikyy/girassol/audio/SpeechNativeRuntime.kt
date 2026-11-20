package com.enhikyy.girassol.audio

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Installs the optional Sherpa-ONNX ARM64 native runtime into app-private storage.
 *
 * The AAR remains a compile-time dependency for its small Java/Kotlin API, while
 * its large native libraries are downloaded on first neural-speech use. The
 * archive is pinned by SHA-256 and extraction accepts only the four expected
 * ARM64 shared objects. No executable code is loaded before verification.
 */
internal object SpeechNativeRuntime {
    private const val ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"
    private const val ARCHIVE_SHA256 =
        "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
    private const val MAX_ARCHIVE_BYTES = 48L * 1024L * 1024L
    private const val MAX_NATIVE_BYTES = 48L * 1024L * 1024L
    private val requiredLibraries = listOf(
        "libonnxruntime.so",
        "libsherpa-onnx-c-api.so",
        "libsherpa-onnx-cxx-api.so",
        "libsherpa-onnx-jni.so"
    )

    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        check(android.os.Process.is64Bit()) { "A voz neural requer Android 64-bit" }
        val root = File(context.applicationContext.filesDir, "speech/native-runtime/arm64-v8a")
        val marker = File(root, "archive.sha256")
        val validInstall = marker.isFile &&
            runCatching { marker.readText().trim() == ARCHIVE_SHA256 }.getOrDefault(false) &&
            requiredLibraries.all { name -> File(root, name).isFile && File(root, name).length() > 4096L }

        if (!validInstall) install(root)

        // Load dependencies before the Sherpa JNI bridge. System.load uses the
        // verified absolute app-private path; the app never searches writable
        // external/shared storage for native code.
        requiredLibraries.forEach { name ->
            val library = File(root, name)
            check(library.isFile && library.length() > 4096L) { "Runtime neural incompleto: $name" }
            System.load(library.absolutePath)
        }
        loaded = true
    }

    private fun install(root: File) {
        val parent = checkNotNull(root.parentFile)
        check(parent.exists() || parent.mkdirs()) { "Não foi possível criar diretório do runtime neural" }
        val archive = File(parent, "sherpa-onnx-2.0.1.aar.part")
        val staging = File(parent, "arm64-v8a.installing")
        archive.delete()
        staging.deleteRecursively()
        try {
            val connection = (URL(ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "Girassol-Android")
            }
            try {
                check(connection.responseCode in 200..299) { "Download do runtime neural retornou HTTP ${connection.responseCode}" }
                val declared = connection.contentLengthLong
                check(declared in 1..MAX_ARCHIVE_BYTES) { "Tamanho do runtime neural fora do limite" }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total = Math.addExact(total, count.toLong())
                            check(total <= MAX_ARCHIVE_BYTES) { "Arquivo do runtime neural excede o limite" }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == ARCHIVE_SHA256) { "SHA-256 do runtime neural não confere" }
            } finally {
                connection.disconnect()
            }

            check(staging.mkdirs()) { "Não foi possível criar área temporária do runtime neural" }
            var extracted = 0L
            val found = HashSet<String>()
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val prefix = "jni/arm64-v8a/"
                    if (!entry.name.startsWith(prefix)) continue
                    val name = entry.name.removePrefix(prefix)
                    if (name !in requiredLibraries) continue
                    check(found.add(name)) { "Biblioteca duplicada no pacote Sherpa: $name" }
                    check(entry.size in 1..MAX_NATIVE_BYTES) { "Biblioteca nativa excede o limite: $name" }
                    val outputFile = File(staging, name)
                    var written = 0L
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outputFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                written = Math.addExact(written, count.toLong())
                                extracted = Math.addExact(extracted, count.toLong())
                                check(extracted <= MAX_NATIVE_BYTES) { "Runtime nativo expandido excede o limite" }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    check(written == entry.size && written > 4096L) { "Biblioteca nativa incompleta: $name" }
                    check(outputFile.setReadable(true, true)) { "Não foi possível definir permissões para $name" }
                }
            }
            check(found == requiredLibraries.toSet()) { "O pacote Sherpa não contém todas as bibliotecas ARM64 esperadas" }
            File(staging, "archive.sha256").writeText("$ARCHIVE_SHA256\n")
            root.deleteRecursively()
            check(staging.renameTo(root)) { "Não foi possível ativar o runtime neural instalado" }
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }
}
