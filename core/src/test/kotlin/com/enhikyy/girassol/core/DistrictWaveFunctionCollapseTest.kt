package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class DistrictWaveFunctionCollapseTest {
    @Test fun chunksAreDeterministicCompleteAndRespectEveryAdjacencyConstraint() {
        val seed = 0x47524153534f4cL
        val width = 17
        val first = DistrictWaveFunctionCollapse.generate(seed, 0, 0)
        val repeat = DistrictWaveFunctionCollapse.generate(seed, 0, 0)
        assertEquals(width * width, first.size)
        assertEquals(first, repeat)
        assertTrue(first.toSet().containsAll(DistrictWaveFunctionCollapse.Kind.entries))
        for (z in 0 until width) for (x in 0 until width) {
            val current = first[z * width + x]
            if (x + 1 < width) {
                assertTrue(DistrictWaveFunctionCollapse.areCompatible(current, first[z * width + x + 1]))
            }
            if (z + 1 < width) {
                assertTrue(DistrictWaveFunctionCollapse.areCompatible(current, first[(z + 1) * width + x]))
            }
        }
    }

    @Test fun independentlyGeneratedChunksExposeCivicConnectorBorders() {
        val left = DistrictWaveFunctionCollapse.generate(99L, 0, 0)
        val right = DistrictWaveFunctionCollapse.generate(99L, 1, 0)
        val top = DistrictWaveFunctionCollapse.generate(99L, 0, -1)
        val civic = DistrictWaveFunctionCollapse.Kind.CIVIC
        for (z in 0 until 17) {
            assertEquals(civic, left[z * 17 + 16])
            assertEquals(civic, right[z * 17])
            assertTrue(DistrictWaveFunctionCollapse.areCompatible(left[z * 17 + 16], right[z * 17]))
        }
        for (x in 0 until 17) {
            assertEquals(civic, top[16 * 17 + x])
            assertEquals(civic, left[x])
            assertTrue(DistrictWaveFunctionCollapse.areCompatible(top[16 * 17 + x], left[x]))
        }
    }
}
