package com.enhikyy.girassol.cognition

import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CognitionTest {
    @Test fun importIsContentAddressedAndInvalidReplacementPreservesCurrent() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "cognition-import-test").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir(): File = root }
        try {
            val files = ModelFiles(context)
            assertNull(files.current())
            val validHeader = File(root, "source.bin").apply { writeBytes("GGUF".toByteArray() + ByteArray(20)) }
            val installed = files.install(Uri.fromFile(validHeader))
            assertEquals(24L, installed.bytes)
            assertEquals(installed, files.current())
            assertEquals(installed, files.install(Uri.fromFile(validHeader)))
            val invalid = File(root, "invalid.bin").apply { writeBytes(ByteArray(24)) }
            try { files.install(Uri.fromFile(invalid)); fail("Non-GGUF import accepted") }
            catch (_: IllegalArgumentException) {}
            assertEquals(installed, files.current())
            assertFalse(File(root, "models/import.part").exists())
            files.remove()
            assertNull(files.current())
        } finally { root.deleteRecursively() }
    }
    @Test fun nativeCancellationAndInvalidPathReturnExceptions() {
        NativeCognition.cancel(1)
        try { NativeCognition.infer("/missing.gguf", "x".toByteArray(), 1); fail("Cancelled request accepted") }
        catch (_: IllegalStateException) {}
        try { NativeCognition.infer("/missing.gguf", "x".toByteArray(), 2); fail("Missing GGUF accepted") }
        catch (_: IllegalStateException) {}
    }
}
