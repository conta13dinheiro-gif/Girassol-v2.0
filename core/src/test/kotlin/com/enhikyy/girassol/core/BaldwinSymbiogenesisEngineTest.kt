package com.enhikyy.girassol.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaldwinSymbiogenesisEngineTest {
    @Test
    fun inheritedGenomeIsDeterministicForTheSameCanonicalSeed() {
        val first = Genome(
            curiosity = Locus(.9, .2, .8, .2),
            sociability = Locus(.7, .3, .7, .3),
            generation = 2
        )
        val second = Genome(
            curiosity = Locus(.4, .8, .4, .8),
            sociability = Locus(.3, .9, .3, .9),
            generation = 3
        )
        val one = BaldwinSymbiogenesisEngine.inheritGenome(
            first, second, .82, .74, 4,
            Canonical.stream(33L, "child", 12L, "genetics-v9")
        )
        val two = BaldwinSymbiogenesisEngine.inheritGenome(
            first, second, .82, .74, 4,
            Canonical.stream(33L, "child", 12L, "genetics-v9")
        )
        assertEquals(one, two)
        assertEquals(4, one.generation)
        assertTrue(
            listOf(one.curiosity, one.sociability, one.symbiosis).all { locus ->
                listOf(locus.maternal, locus.paternal, locus.dominanceA, locus.dominanceB)
                    .all { it in 0.0..1.0 }
            }
        )
    }

    @Test
    fun acquiredLearningBiasesTransmissionWithoutCopyingTheLearnedScore() {
        val highAlleleLocus = Locus(.95, .05, 1.0, 1.0)
        var highMasteryTotal = 0.0
        var lowMasteryTotal = 0.0
        repeat(600) { sample ->
            highMasteryTotal += BaldwinSymbiogenesisEngine.inheritLocus(
                highAlleleLocus, highAlleleLocus, 1.0, 1.0,
                Canonical.stream(91L, "high-$sample", 1L, "baldwin")
            ).express()
            lowMasteryTotal += BaldwinSymbiogenesisEngine.inheritLocus(
                highAlleleLocus, highAlleleLocus, 0.0, 0.0,
                Canonical.stream(91L, "low-$sample", 1L, "baldwin")
            ).express()
        }
        assertTrue(highMasteryTotal / 600.0 > lowMasteryTotal / 600.0 + 0.08)
    }

    @Test
    fun complementaryLearnedParentsProduceBoundedSymbiosisBlend() {
        val first = Genome(
            curiosity = Locus(.8, .4, .5, .5),
            sociability = Locus(.7, .3, .5, .5),
            symbiosis = Locus(.1, .1, 1.0, 1.0)
        )
        val second = Genome(
            curiosity = Locus(.4, .8, .5, .5),
            sociability = Locus(.3, .7, .5, .5),
            symbiosis = Locus(.9, .9, 1.0, 1.0)
        )
        val child = BaldwinSymbiogenesisEngine.inheritGenome(
            first, second, .9, .9, 2,
            Canonical.stream(7L, "symbiotic-child", 4L, "genetics-v9")
        )
        assertTrue(child.symbiosis.express() in 0.0..1.0)
        assertTrue(child.symbiosis.express() > first.symbiosis.express())
        assertTrue(child.symbiosis.express() < second.symbiosis.express())
    }

    @Test
    fun legacyGenomePayloadDefaultsSymbiosisWithoutChangingItsEncodedShape() {
        val serializer = Json { encodeDefaults = true }
        val legacy = """{"curiosity":{"maternal":0.3,"paternal":0.7,"dominanceA":0.5,"dominanceB":0.5},"sociability":{"maternal":0.4,"paternal":0.6,"dominanceA":0.5,"dominanceB":0.5},"generation":1}"""
        val decoded = serializer.decodeFromString<Genome>(legacy)
        assertEquals(Locus(.5, .5, .5, .5), decoded.symbiosis)
        assertFalse(serializer.encodeToString(decoded).contains("symbiosis"))
    }
}
