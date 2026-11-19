package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class SleepConsolidationAndImmuneEngineTest {
    @Test fun betaBeliefsRemainUncertainUntilEvidenceArrives() {
        val prior = SubjectiveOpinion.fromBeta(1.0, 1.0)
        assertEquals(0.0, prior.belief, 1e-10)
        assertEquals(0.0, prior.disbelief, 1e-10)
        assertEquals(1.0, prior.uncertainty, 1e-10)
        val evidence = SubjectiveOpinion.fromBeta(3.0, 1.0)
        assertEquals(0.5, evidence.belief, 1e-10)
        assertEquals(0.75, evidence.projectedProbability(), 1e-10)
    }

    @Test fun trustDiscountMovesBeliefAndDisbeliefIntoUncertainty() {
        val certain = SubjectiveOpinion(.7, .2, .1, .6)
        val discounted = certain.discountByTrust(.25)
        assertEquals(.175, discounted.belief, 1e-10)
        assertEquals(.05, discounted.disbelief, 1e-10)
        assertEquals(.775, discounted.uncertainty, 1e-10)
        assertEquals(1.0, discounted.belief + discounted.disbelief + discounted.uncertainty, 1e-10)
    }

    @Test fun copiesOfOneSourceCannotManufactureIndependentConfidence() {
        val claim = "a".repeat(64)
        val source = "b".repeat(64)
        val opinion = SubjectiveOpinion.fromBeta(5.0, 1.0)
        val witness = EpistemicWitness(claim, source, "citizen-A", opinion)
        val fused = checkNotNull(EpistemicImmunityEngine.fuseIndependent(listOf(witness, witness.copy(sourceAgentId="citizen-B"))))
        assertEquals(opinion, fused.opinion)
        assertEquals(listOf(source), fused.acceptedSourceEventHashes)
        assertEquals(1, fused.ignoredDuplicateCount)
    }

    @Test fun independentSourcesCanFuseButDogmaticConflictReturnsToUncertainty() {
        val claim = "a".repeat(64)
        val first = EpistemicWitness(claim, "b".repeat(64), "citizen-A", SubjectiveOpinion.fromBeta(5.0, 1.0))
        val second = EpistemicWitness(claim, "c".repeat(64), "citizen-B", SubjectiveOpinion.fromBeta(5.0, 1.0))
        val combined = checkNotNull(EpistemicImmunityEngine.fuseIndependent(listOf(first, second)))
        assertEquals(2, combined.acceptedSourceEventHashes.size)
        assertTrue(combined.opinion.uncertainty < first.opinion.uncertainty)
        val conflict = SubjectiveOpinion.cumulativeFuseIndependent(
            SubjectiveOpinion(1.0, 0.0, 0.0),
            SubjectiveOpinion(0.0, 1.0, 0.0)
        )
        assertEquals(1.0, conflict.uncertainty, 1e-10)
    }

    @Test fun memoryConsolidationIsDeterministicAndRetainsCoverageAndPriority() {
        val world = WorldActor.genesis().copy(tick = 520)
        val citizen = world.agents.first()
        val episodes = (1L..520L).map { event ->
            val action = Activity.entries[((event - 1) % Activity.entries.size).toInt()]
            val reward = if (event % 17L == 0L) -1.0 else .15
            Episode(
                tick = event,
                eventId = event,
                action = action,
                reward = reward,
                x = citizen.x,
                z = citizen.z,
                emotion = reward.coerceIn(-1.0, 1.0),
                importance = if (reward < 0.0) 1.0 else .2,
                embedding = MemoryBank.embedding(citizen, action, reward)
            )
        }
        val bank = MemoryBank(episodes = episodes)
        assertTrue(bank.valid(520))
        val first = HippocampalSleepConsolidator.consolidate(bank, 520)
        val second = HippocampalSleepConsolidator.consolidate(bank, 520)
        assertEquals(512, first.bank.episodes.size)
        assertEquals(8, first.report.prunedEventIds.size)
        assertEquals(HippocampalSleepConsolidator.DEFAULT_REPLAY_BATCH_SIZE, first.report.selectedReplayEventIds.size)
        assertEquals(first, second)
        assertEquals(Activity.entries.toSet(), first.bank.episodes.map { it.action }.toSet())
        assertTrue(first.report.selectedReplayEventIds.any { it % 17L == 0L })
        assertTrue(first.bank.valid(520))
        assertEquals(520, bank.episodes.size)
    }

    @Test fun consolidationLeavesSmallBanksUnchanged() {
        val world = WorldActor.genesis().copy(tick = 1)
        val citizen = world.agents.first()
        val episode = Episode(1, 1, Activity.STUDY, .2, citizen.x, citizen.z, .4, .5, MemoryBank.embedding(citizen))
        val bank = MemoryBank(episodes = listOf(episode))
        val result = HippocampalSleepConsolidator.consolidate(bank, 1)
        assertEquals(bank, result.bank)
        assertTrue(result.report.prunedEventIds.isEmpty())
    }

    @Test fun allostasisIncreasesDeliberationCostUnderHeatAndScarcity() {
        val cool = AllostaticThermalCoupling.assess(ThermalTier.NONE_OR_LIGHT, false, .8)
        val hot = AllostaticThermalCoupling.assess(ThermalTier.SEVERE_OR_CRITICAL, false, .8)
        val scarce = AllostaticThermalCoupling.assess(ThermalTier.SEVERE_OR_CRITICAL, false, .2)
        assertTrue(cool.localLlmAllowed)
        assertFalse(hot.localLlmAllowed)
        assertTrue(hot.reflexFirst)
        assertTrue(hot.neuralCostMultiplier > cool.neuralCostMultiplier)
        assertEquals(hot.neuralCostMultiplier * 2.0, scarce.neuralCostMultiplier, 1e-10)
        assertEquals(ThermalTier.MODERATE, AllostaticThermalCoupling.fromPlatformThermalStatus(2))
        assertEquals(ThermalTier.SEVERE_OR_CRITICAL, AllostaticThermalCoupling.fromPlatformThermalStatus(4))
    }

    @Test fun thermalAndReflexGatesPreventUnnecessaryLocalLlmCalls() {
        assertFalse(AllostaticThermalCoupling.shouldInvokeLocalLlm(
            .9, .2, .1, .5, 0, false, ThermalTier.NONE_OR_LIGHT, false, .93
        ))
        assertFalse(AllostaticThermalCoupling.shouldInvokeLocalLlm(
            .9, .2, .1, .5, 0, false, ThermalTier.SEVERE_OR_CRITICAL, false, .2
        ))
        assertTrue(AllostaticThermalCoupling.shouldInvokeLocalLlm(
            .9, .2, .1, .5, 3, false, ThermalTier.SEVERE_OR_CRITICAL, false, .2
        ))
    }

    @Test fun semanticCollapseBonusTargetsRareActionsOnlyBelowThreshold() {
        val uniform = Activity.entries.associate { it.name to 10 }
        assertEquals(1.0, SemanticCollapseGuard.normalizedPopulationEntropy(uniform), 1e-10)
        assertEquals(0.0, SemanticCollapseGuard.noveltyEpistemicBonus(Activity.BUILD.name, uniform), 1e-10)

        val collapsed = Activity.entries.associate { it.name to if (it == Activity.WRITE) 100 else 0 }
        val rare = SemanticCollapseGuard.noveltyEpistemicBonus(Activity.BUILD.name, collapsed)
        val dominant = SemanticCollapseGuard.noveltyEpistemicBonus(Activity.WRITE.name, collapsed)
        assertTrue(rare > dominant)
        assertTrue(rare > 0.0)
        assertTrue(SemanticCollapseGuard.normalizedPopulationEntropy(collapsed) < .55)
    }
}
