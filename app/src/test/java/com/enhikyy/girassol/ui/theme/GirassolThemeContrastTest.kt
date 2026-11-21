package com.enhikyy.girassol.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GirassolThemeContrastTest {
    @Test fun everySemanticTextColorMeetsWcagAaOnBothNativeSurfaces() {
        val ratios = nativeThemeContrastRatios()
        assertEquals(GirassolVisualTheme.values().size, ratios.size)
        ratios.forEach { (theme, values) ->
            assertEquals("$theme must test five semantic foregrounds on two surfaces", 10, values.size)
            values.forEachIndexed { index, ratio ->
                assertTrue("$theme foreground/surface $index contrast $ratio is below WCAG AA 4.5:1", ratio >= 4.5)
            }
        }
    }
}
