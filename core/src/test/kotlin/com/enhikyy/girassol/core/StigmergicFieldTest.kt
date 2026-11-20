package com.enhikyy.girassol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StigmergicFieldTest {
    @Test
    fun diffusionMovesPheromoneToNeighborsWithoutCreatingMass() {
        val values = MutableList(9) { 0.0 }
        values[4] = 0.8
        val diffused = StigmergicField.diffuseAndEvaporate(
            values, width = 3, height = 3,
            diffusionRate = 0.1, evaporationRate = 0.0
        )
        assertTrue(diffused[4] < values[4])
        assertTrue(diffused[1] > 0.0)
        assertTrue(diffused[3] > 0.0)
        assertTrue(diffused[5] > 0.0)
        assertTrue(diffused[7] > 0.0)
        assertEquals(values.sum(), diffused.sum(), 1e-12)
    }

    @Test
    fun evaporationIsAppliedAfterConservativeDiffusion() {
        val values = List(144) { index -> (index % 10) / 20.0 }
        val evaporated = StigmergicField.diffuseAndEvaporate(
            values, diffusionRate = 0.08, evaporationRate = 0.1
        )
        assertEquals(values.sum() * 0.9, evaporated.sum(), 1e-9)
        assertTrue(evaporated.all { it in 0.0..1.0 })
    }

    @Test
    fun depositsAreBoundedAndCellSamplingIsConstantTime() {
        val values = MutableList(144) { 0.0 }
        StigmergicField.deposit(values, 17, 0.3)
        assertEquals(0.3, StigmergicField.sample(values, 17), 1e-12)
        StigmergicField.deposit(values, 17, 2.0)
        assertEquals(1.0, StigmergicField.sample(values, 17), 1e-12)
        assertEquals(0.0, StigmergicField.sample(values, 18), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun diffusionRejectsUnstableRates() {
        StigmergicField.diffuseAndEvaporate(
            List(4) { 0.0 }, width = 2, height = 2, diffusionRate = 0.26
        )
    }
}
