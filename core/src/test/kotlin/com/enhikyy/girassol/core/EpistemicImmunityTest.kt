package com.enhikyy.girassol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpistemicImmunityTest {
    private val claim = "a".repeat(64)

    private fun witness(event: Char, agent: String, opinion: SubjectiveOpinion) =
        EpistemicWitness(claim, event.toString().repeat(64), agent, opinion)

    @Test
    fun averagingFusionPreservesMoreUncertaintyThanIndependentFusion() {
        val first = SubjectiveOpinion(0.6, 0.1, 0.3, 0.4)
        val second = SubjectiveOpinion(0.2, 0.3, 0.5, 0.8)
        val averaged = SubjectiveOpinion.averagingFuseCorrelated(first, second)
        val cumulative = SubjectiveOpinion.cumulativeFuseIndependent(first, second)

        assertTrue(averaged.uncertainty > cumulative.uncertainty)
        assertEquals(1.0, averaged.belief + averaged.disbelief + averaged.uncertainty, 1e-10)
        assertEquals(0.6, averaged.baseRate, 1e-10)
    }

    @Test
    fun averagingFusionHandlesDogmaticAndVacuousBoundaryCases() {
        val positive = SubjectiveOpinion(1.0, 0.0, 0.0)
        val negative = SubjectiveOpinion(0.0, 1.0, 0.0)
        val conflict = SubjectiveOpinion.averagingFuseCorrelated(positive, negative)
        assertEquals(0.5, conflict.belief, 1e-10)
        assertEquals(0.5, conflict.disbelief, 1e-10)
        assertEquals(0.0, conflict.uncertainty, 1e-10)

        val vacuous = SubjectiveOpinion(0.0, 0.0, 1.0)
        assertEquals(vacuous, SubjectiveOpinion.averagingFuseCorrelated(vacuous, vacuous))
    }

    @Test
    fun correlatedFusionDeduplicatesRepeatedSourceEvents() {
        val first = SubjectiveOpinion(0.6, 0.1, 0.3, 0.4)
        val second = SubjectiveOpinion(0.2, 0.3, 0.5, 0.8)
        val result = EpistemicImmunityEngine.fuseCorrelated(
            listOf(
                witness('b', "agent-a", first),
                witness('b', "agent-echo", second),
                witness('b', "agent-a", first),
                witness('c', "agent-c", second)
            )
        )!!

        assertEquals(2, result.acceptedSourceEventHashes.size)
        assertEquals(1, result.ignoredDuplicateCount)
        assertEquals(claim, result.claimHash)
        assertEquals(1.0, result.opinion.belief + result.opinion.disbelief + result.opinion.uncertainty, 1e-10)
    }
}
