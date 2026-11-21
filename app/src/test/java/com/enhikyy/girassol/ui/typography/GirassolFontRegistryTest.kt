package com.enhikyy.girassol.ui.typography

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.Color
import com.enhikyy.girassol.ui.theme.GirassolThemePalette
import kotlin.math.pow

class GirassolFontRegistryTest {
    @Test fun catalogContainsAtLeast320UniqueFamilies() {
        assertTrue("Expected 320+ unique families, got ${GirassolFontRegistry.catalog.size}", GirassolFontRegistry.catalog.size >= 320)
        assertEquals(GirassolFontRegistry.catalog.size, GirassolFontRegistry.catalog.map { it.name }.distinct().size)
    }

    @Test fun curatedCategoriesMeetTheirMinimums() {
        assertTrue(GirassolFontRegistry.names(FontCategory.NEO).size >= 65)
        assertTrue(GirassolFontRegistry.names(FontCategory.EDITORIAL).size >= 55)
        assertTrue(GirassolFontRegistry.names(FontCategory.MONO).size >= 45)
        assertTrue(GirassolFontRegistry.names(FontCategory.GEOMETRIC).size >= 55)
        assertTrue(GirassolFontRegistry.names(FontCategory.HUMANIST).size >= 40)
        assertTrue(GirassolFontRegistry.names(FontCategory.DISPLAY).size >= 35)
        assertTrue(GirassolFontRegistry.names(FontCategory.ACCESSIBLE).size >= 25)
    }

    @Test fun thirtyTwoCoreFamiliesAreEmbeddedForOfflineBoot() {
        assertEquals(32, GirassolFontRegistry.embeddedFontCount)
        assertTrue(GirassolFontRegistry.isEmbedded("Inter"))
        assertTrue(GirassolFontRegistry.isEmbedded("JetBrains Mono"))
        assertTrue(GirassolFontRegistry.isEmbedded("Atkinson Hyperlegible"))
    }

    @Test fun allFifteenOledPalettesMeetTextContrastTargets() {
        GirassolThemePalette.all.forEach { palette ->
            assertTrue("${palette.name}: ink contrast below AAA", contrast(palette.ink, palette.background) >= 7.0)
            assertTrue("${palette.name}: muted contrast below AA", contrast(palette.muted, palette.background) >= 4.5)
            assertTrue("${palette.name}: amber contrast below AA", contrast(palette.amber, palette.background) >= 4.5)
            assertTrue("${palette.name}: telemetry contrast below AA", contrast(palette.telemetry, palette.background) >= 4.5)
        }
    }

    private fun contrast(foreground: Color, background: Color): Double {
        fun channel(value: Float): Double = if (value <= 0.04045f) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        fun luminance(color: Color) = 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
        val a = luminance(foreground); val b = luminance(background)
        return (maxOf(a,b) + 0.05) / (minOf(a,b) + 0.05)
    }

    @Test fun allSixAiPresetsAreAvailable() {
        assertEquals(setOf("chatgpt", "claude", "perplexity", "gemini", "deepseek", "kimi"), GirassolFontRegistry.presets.map { it.id }.toSet())
    }
}