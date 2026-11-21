package com.enhikyy.girassol.ui.typography

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TypographyPreferencesInstrumentedTest {
    @Test fun sixAiPresetConfigurationsPersistAcrossDataStoreReads() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val presets = GirassolFontRegistry.presets
        presets.forEach { preset ->
            TypographyPreferences.update(context) { current ->
                current.copy(
                    interfaceFont = preset.interfaceFont,
                    chatFont = preset.interfaceFont,
                    editorialFont = preset.editorialFont,
                    telemetryFont = preset.codeFont,
                    presetId = preset.id
                )
            }
            val persisted = withTimeout(5_000) {
                TypographyPreferences.observe(context).first { it.presetId == preset.id }
            }
            assertEquals(preset.interfaceFont, persisted.interfaceFont)
            assertEquals(preset.editorialFont, persisted.editorialFont)
            assertEquals(preset.codeFont, persisted.telemetryFont)
        }
    }
}