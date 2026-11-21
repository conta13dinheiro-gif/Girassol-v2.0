package com.enhikyy.girassol.ui.typography

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TypographyStudioScreenshotTest {
    @Test fun captureTypographyStudioScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch(TypographyStudioPreviewActivity::class.java).use {
            instrumentation.waitForIdleSync()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            val output = File(directory, "typography-studio.png")
            output.outputStream().use { stream -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream) }
            bitmap.recycle()
            assertTrue("Typography Studio screenshot was not created", output.exists() && output.length() > 0)
        }
    }
}