package com.enhikyy.girassol.ui.typography

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun allSixAiPresetsAreAvailable() {
        assertEquals(setOf("chatgpt", "claude", "perplexity", "gemini", "deepseek", "kimi"), GirassolFontRegistry.presets.map { it.id }.toSet())
    }
}