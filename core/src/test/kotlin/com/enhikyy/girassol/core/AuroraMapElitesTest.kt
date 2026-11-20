package com.enhikyy.girassol.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AuroraMapElitesTest {
    private fun candidate(
        id: String,
        descriptor: AuroraDescriptor = AuroraDescriptor(0.2, 0.2, 0.2),
        score: Double = 0.5
    ) = AuroraCandidate(
        id = id,
        eventId = id.hashCode().toLong().let { if (it > 0L) it else -it + 1L },
        sourceEventHash = "a".repeat(64),
        descriptor = descriptor,
        genome = List(8) { index -> (index + 1) / 10.0 },
        metrics = ReplayMetrics(score, score, score, score),
        generation = 1L
    )

    @Test
    fun archiveKeepsOneElitePerNicheAndOnlyReplacesWithBetterCandidate() {
        val empty = AuroraMapElitesState(binsPerAxis = 4, genomeDimensions = 8)
        val first = candidate("first", score = 0.4)
        val inserted = AuroraMapElites.insert(empty, first)
        assertEquals(AuroraInsertDisposition.INSERTED, inserted.disposition)
        assertEquals(1, inserted.state.elites.size)

        val weaker = AuroraMapElites.insert(inserted.state, candidate("weaker", score = 0.3))
        assertEquals(AuroraInsertDisposition.RETAINED_EXISTING, weaker.disposition)
        assertEquals("first", weaker.state.elites.single().id)

        val stronger = AuroraMapElites.insert(weaker.state, candidate("stronger", score = 0.8))
        assertEquals(AuroraInsertDisposition.REPLACED, stronger.disposition)
        assertEquals("first", stronger.displacedCandidateId)
        assertEquals("stronger", stronger.state.elites.single().id)
        assertEquals(1, stronger.state.elites.size)
        assertTrue(stronger.state.valid())
    }

    @Test
    fun archiveUsesDifferentCellsAndStableIdForExactFitnessTies() {
        val initial = AuroraMapElitesState(binsPerAxis = 4, genomeDimensions = 8)
        val highId = candidate("zeta", descriptor = AuroraDescriptor(0.2, 0.2, 0.2), score = 0.7)
        val lowId = candidate("alpha", descriptor = AuroraDescriptor(0.21, 0.22, 0.23), score = 0.7)
        val first = AuroraMapElites.insert(initial, highId)
        val tie = AuroraMapElites.insert(first.state, lowId)
        assertEquals(AuroraInsertDisposition.REPLACED, tie.disposition)
        assertEquals("alpha", tie.state.elites.single().id)

        val otherNiche = AuroraMapElites.insert(
            tie.state,
            candidate("other", descriptor = AuroraDescriptor(0.8, 0.8, 0.8), score = 0.2)
        )
        assertEquals(AuroraInsertDisposition.INSERTED, otherNiche.disposition)
        assertEquals(2, otherNiche.state.elites.size)
        assertTrue(otherNiche.state.coverage > 0.0)
    }

    @Test
    fun repeatedCandidateIsIdempotentAndInvalidEvidenceIsRejected() {
        val state = AuroraMapElitesState(binsPerAxis = 4, genomeDimensions = 8)
        val item = candidate("stable")
        val first = AuroraMapElites.insert(state, item)
        val duplicate = AuroraMapElites.insert(first.state, item)
        assertEquals(AuroraInsertDisposition.DUPLICATE, duplicate.disposition)
        assertEquals(first.state, duplicate.state)

        var rejected = false
        try {
            AuroraMapElites.insert(state, item.copy(sourceEventHash = "not-a-hash"))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun seededOjaProjectorIsReproducibleAndKeepsThreeAxesOrthonormal() {
        val first = AuroraOjaProjectorState.create(inputDimensions = 12, learningRate = 0.03, seed = 44L)
        val same = AuroraOjaProjectorState.create(inputDimensions = 12, learningRate = 0.03, seed = 44L)
        assertEquals(first, same)

        var trained = first
        repeat(240) { sample ->
            val features = List(12) { dimension ->
                sin((sample + 1) * (dimension + 1) * 0.017) +
                    0.3 * sin((sample + 3) * (dimension + 2) * 0.031)
            }
            trained = trained.observe(features)
        }

        assertTrue(trained.valid())
        assertEquals(240L, trained.observations)
        for (i in trained.components.indices) {
            val norm = trained.components[i].sumOf { it * it }
            assertEquals(1.0, norm, 1e-5)
            for (j in 0 until i) {
                val dot = trained.components[i].indices.sumOf {
                    trained.components[i][it] * trained.components[j][it]
                }
                assertTrue(abs(dot) <= 1e-5)
            }
        }
    }

    @Test
    fun projectionIsBoundedAndArchiveStateSurvivesSerialization() {
        val projector = AuroraOjaProjectorState.create(inputDimensions = 6, seed = 9L)
        val descriptor = projector.project(List(6) { (it - 2).toDouble() })
        assertTrue(descriptor.valid())

        val archive = AuroraMapElites.insert(
            AuroraMapElitesState(binsPerAxis = 4, genomeDimensions = 8),
            candidate("serializable", descriptor = descriptor)
        ).state
        val encoded = Json.encodeToString(archive)
        val decoded = Json.decodeFromString<AuroraMapElitesState>(encoded)
        assertEquals(archive, decoded)
        assertTrue(decoded.valid())
    }
}
