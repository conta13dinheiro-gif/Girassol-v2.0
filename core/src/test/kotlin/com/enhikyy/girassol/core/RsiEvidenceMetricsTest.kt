package com.enhikyy.girassol.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RsiEvidenceMetricsTest {
    private val baseline = ReplayMetrics(
        innovation = 0.50,
        resilience = 0.70,
        harmony = 0.70,
        energyEfficiency = 0.70,
        brierScore = 0.20,
        semanticEntropy = 0.75,
        evidenceVersion = 1
    )

    @Test
    fun promotionRequiresFitnessGainWithoutBrierRegression() {
        val improved = baseline.copy(
            innovation = 0.70,
            resilience = 0.72,
            harmony = 0.72,
            energyEfficiency = 0.72,
            brierScore = 0.202,
            semanticEntropy = 0.76
        )
        assertTrue(improved.dominates(baseline))

        val miscalibrated = improved.copy(brierScore = 0.21)
        assertFalse(miscalibrated.dominates(baseline))
    }

    @Test
    fun promotionRejectsMeaningfulSemanticEntropyRegression() {
        val improved = baseline.copy(
            innovation = 0.70,
            resilience = 0.72,
            harmony = 0.72,
            energyEfficiency = 0.72,
            brierScore = 0.19,
            semanticEntropy = 0.73
        )
        assertTrue(improved.dominates(baseline))

        val collapsed = improved.copy(semanticEntropy = 0.70)
        assertFalse(collapsed.dominates(baseline))
    }

    @Test
    fun promotionEnforcesAbsoluteSemanticEntropyFloor() {
        val improvedButCollapsed = baseline.copy(
            innovation = 0.70,
            resilience = 0.72,
            harmony = 0.72,
            energyEfficiency = 0.72,
            brierScore = 0.19,
            semanticEntropy = 0.34
        )
        assertFalse(improvedButCollapsed.dominates(baseline))
    }

    @Test
    fun metricEvidenceVersionsCannotBeMixedForPromotion() {
        val upgraded = baseline.copy(
            innovation = 0.70,
            resilience = 0.72,
            harmony = 0.72,
            energyEfficiency = 0.72,
            brierScore = 0.19,
            semanticEntropy = 0.76
        )
        assertFalse(upgraded.dominates(baseline.copy(evidenceVersion = 0)))
    }

    @Test
    fun extendedMetricsRemainBackwardCompatibleWithHistoricalPayloads() {
        val legacy = Json.decodeFromString<ReplayMetrics>(
            """{"innovation":0.5,"resilience":0.6,"harmony":0.7,"energyEfficiency":0.8}"""
        )
        assertEquals(0.0, legacy.brierScore, 0.0)
        assertEquals(0.0, legacy.semanticEntropy, 0.0)
        assertEquals(0, legacy.evidenceVersion)
        assertTrue(legacy.valid())
    }

    @Test
    fun invalidOrUnboundedCalibrationMetricsCannotBePromoted() {
        assertFalse(baseline.copy(brierScore = Double.NaN).valid())
        assertFalse(baseline.copy(brierScore = 1.1).valid())
        assertFalse(baseline.copy(semanticEntropy = -0.1).valid())
    }
}
