package com.enhikyy.girassol.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SkillLearningTest {
    @Test fun bktSuccessRaisesAndFailureLowersKnowledgeEstimate() {
        val initial = SkillLearningTrace(Activity.STUDY)
        val success = initial.observe(correct = true, tick = 1)
        val failure = initial.observe(correct = false, tick = 1)

        assertTrue(success.knowledgeProbability > initial.knowledgeProbability)
        assertTrue(failure.knowledgeProbability < initial.knowledgeProbability)
        assertTrue(success.valid(1))
        assertTrue(failure.valid(1))
    }

    @Test fun dsrCurveHasNinetyPercentRetrievabilityAtOneStabilityInterval() {
        val trace = SkillLearningTrace(
            action = Activity.STUDY,
            stabilityTicks = 5.0,
            reviews = 1,
            lastReviewTick = 10
        )

        assertEquals(1.0, trace.retrievability(10), 1e-12)
        assertEquals(0.9, trace.retrievability(15), 1e-12)
        assertEquals(5.0, trace.intervalTicks(0.9), 1e-12)
    }

    @Test fun intervalGrowsWithStabilityAndHigherRetentionTargetShortensIt() {
        val base = SkillLearningTrace(Activity.WRITE, stabilityTicks = 4.0)
        val stable = base.copy(stabilityTicks = 8.0)

        assertEquals(2.0 * base.intervalTicks(), stable.intervalTicks(), 1e-12)
        assertTrue(base.intervalTicks(0.95) < base.intervalTicks(0.90))
    }

    @Test fun threeParameterItemResponseIsBoundedAndMonotonicInAbility() {
        val base = SkillLearningTrace(Activity.BUILD)
        val low = base.copy(theta = -4.0).responseProbability()
        val middle = base.responseProbability()
        val high = base.copy(theta = 4.0).responseProbability()

        assertTrue(low >= SkillLearningTrace.IRT_GUESSING)
        assertTrue(high <= 1.0)
        assertTrue(low < middle)
        assertTrue(middle < high)
    }

    @Test fun onlineItemResponseUpdateMovesAbilityTowardObservedOutcome() {
        val base = SkillLearningTrace(Activity.STUDY, theta = 0.0)
        val success = base.observe(correct = true, tick = 1)
        val failure = base.observe(correct = false, tick = 1)

        assertTrue(success.theta > base.theta)
        assertTrue(failure.theta < base.theta)
        assertTrue(success.responseProbability() > base.responseProbability())
        assertTrue(failure.responseProbability() < base.responseProbability())
    }

    @Test fun memoryBankUpdatesOneSkillTraceOnceForACommittedOutcome() {
        val world = WorldActor.genesis().copy(tick = 1)
        val citizen = world.agents.first().copy(
            activity = Activity.STUDY,
            memories = listOf(Memory(1, 1, Activity.STUDY, 0.15))
        )
        val action = ActionRecord(citizen.id, Activity.STUDY, "OK")
        val bank = MemoryBank().observe(world, citizen, action)

        assertEquals(1, bank.learning.size)
        assertEquals(Activity.STUDY, bank.learning.single().action)
        assertEquals(1, bank.learning.single().reviews)
        assertTrue(bank.learning.single().knowledgeProbability > SkillLearningTrace.INITIAL_KNOWLEDGE)
        assertEquals(bank, bank.observe(world, citizen, action))
        assertTrue(bank.valid(1))
    }

    @Test fun learningStatePersistsAndLegacyMemoryBanksDecodeWithoutIt() {
        val legacy = """{"episodes":[],"reflections":[],"procedures":[],"reflectedTick":-1}"""
        val decoded = Json.decodeFromString<MemoryBank>(legacy)
        assertTrue(decoded.learning.isEmpty())
        assertTrue(decoded.valid(0))

        val world = WorldActor.genesis().copy(tick = 2)
        val citizen = world.agents.first()
        val trace = SkillLearningTrace(Activity.STUDY).observe(correct = true, tick = 2)
        val bank = MemoryBank(learning = listOf(trace))
        val mind = MindState.initial(world, citizen).copy(memory = bank)
        val persisted = world.copy(minds = mapOf(citizen.id to mind))

        assertEquals(persisted, Canonical.state(Canonical.state(persisted)))
        assertTrue(Canonical.state(persisted).contains("\"learning\""))
    }

    @Test fun invalidLearningStateIsRejectedByMemoryBankValidation() {
        val invalid = SkillLearningTrace(Activity.STUDY, knowledgeProbability = Double.NaN)
        assertFalse(MemoryBank(learning = listOf(invalid)).valid(0))
    }
}
