package com.enhikyy.girassol.cognition

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class LocalModel(val file: File, val hash: String) {
    val bytes: Long get() = file.length()
}

/** Called under CognitionWork.lock on IO. A failed/cancelled import preserves the installed model. */
internal class ModelFiles(context: Context,slot: String="models") {
    private val resolver = context.contentResolver
    private val directory = File(context.noBackupFilesDir,slot.also { require(it=="models" || it=="review-models" || it=="adapters) }).apply { mkdirs() }
    private val index = AtomicFile(File(directory, "current"))
    fun current(): LocalModel? {
        val hash = try { index.readFully().toString(Charsets.US_ASCII) } catch (_: java.io.FileNotFoundException) { return null }
        check(hash.matches(Regex("[a-f0-9]{64}"))) { "Índice do modelo inválido. Importe o GGUF novamente." }
        val file = File(directory, "$hash.gguf")
        check(file.isFile && file.length()>=24) { "Modelo local ausente ou incompleto. Importe novamente." }
        return LocalModel(file, hash)
    }
    suspend fun install(uri: Uri): LocalModel {
        val temp = File(directory, "import.part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Não foi possível abrir o documento" }
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(directory.usableSpace > 128L * 1024 * 1024 + count) { "Armazenamento insuficiente" }
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                    }
                    require(total >= 24) { "GGUF incompleto" }
                    output.fd.sync()
                }
            }
            temp.inputStream().use { input ->
                require(input.readNBytes(4).contentEquals(byteArrayOf(71, 71, 85, 70))) { "O arquivo selecionado não é GGUF" }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(directory, "$hash.gguf")
            currentCoroutineContext().ensureActive()
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            val stream = index.startWrite()
            try { stream.write(hash.toByteArray(Charsets.US_ASCII)); index.finishWrite(stream) }
            catch (error: Exception) { index.failWrite(stream); throw error }
            directory.listFiles()?.filter { it.extension == "gguf" && it != target }?.forEach { it.delete() }
            return LocalModel(target, hash)
        } finally { temp.delete() }
    }
    fun remove() {
        index.delete()
        directory.listFiles()?.forEach { it.delete() }
    }
}
