package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class WorldActorTest {
    @Test fun identicalSeedAndInputsProduceIdenticalEvents() {
        val a = WorldActor(); val b = WorldActor()
        repeat(300) { assertEquals(a.step(), b.step()) }
        assertNotEquals(Canonical.state(a.state), Canonical.state(WorldActor.genesis(10)))
    }
    @Test fun streamsDoNotDependOnOtherAgentsDraws() {
        val expected = Canonical.stream(42, "A", 30, "decision").nextLong()
        repeat(50) { Canonical.stream(42, "B", it.toLong(), "birth").nextLong() }
        assertEquals(expected, Canonical.stream(42, "A", 30, "decision").nextLong())
        assertNotEquals(expected, Canonical.stream(42, "A", 30, "genome").nextLong())
    }
    @Test fun serializationAndCheckpointResumePreserveFuture() {
        val actor = WorldActor()
        repeat(111) { actor.step() }
        val cp = actor.checkpoint()
        val restored = WorldActor(cp.copy(state = Canonical.state(Canonical.state(cp.state))))
        repeat(150) { assertEquals(actor.step(), restored.step()) }
    }
    @Test fun replayIsIdempotentAndRejectsTamperingWithoutMutation() {
        val a = WorldActor(); val b = WorldActor()
        repeat(100) { val t = a.step(); b.replay(t.event); b.replay(t.event); assertEquals(a.checkpoint(), b.checkpoint()) }
        val before = b.checkpoint()
        val next = a.step().event
        assertThrows(IllegalArgumentException::class.java) { b.replay(next.copy(stateHash = "tampered")) }
        assertEquals(before, b.checkpoint())
        val falseEvent = next.copy(stateHash = "different").let { it.copy(hash = it.computedHash()) }
        assertThrows(IllegalArgumentException::class.java) { b.replay(falseEvent) }
        assertEquals(before, b.checkpoint())
    }
    @Test fun closedEconomyRejectsInvalidAndOverflowingTransfersAtomically() {
        val b = mutableMapOf("a/coins" to 10, "b/coins" to 5)
        val before = b.toMap()
        for (amount in listOf(-1, 0, 11, Int.MAX_VALUE)) assertFalse(WorldActor.transfer(b, "a", "b", "coins", amount))
        assertFalse(WorldActor.transfer(b, "a", "a", "coins", 1))
        assertEquals(before, b)
        assertTrue(WorldActor.transfer(b, "a", "b", "coins", 7))
        assertEquals(15, b.values.sum())
        val overflow = mutableMapOf("a/coins" to 10, "b/coins" to Int.MAX_VALUE)
        assertFalse(WorldActor.transfer(overflow, "a", "b", "coins", 1))
        assertEquals(10, overflow["a/coins"])
    }
    @Test fun longRunPreservesConservationMemoryBoundsAndRealActivity() {
        val a = WorldActor()
        val counts = mutableMapOf<Activity, Int>()
        repeat(2000) {
            val t = a.step()
            assertTrue(WorldActor.violations(t.state).isEmpty())
            assertTrue(t.event.valid())
            t.event.actions.filter { it.outcome == "OK" }.forEach { counts[it.activity] = (counts[it.activity] ?: 0) + 1 }
        }
        assertTrue(a.state.agents.all { it.memories.size <= 32 })
        assertTrue(a.state.dialogues.isNotEmpty())
        assertTrue(a.state.artifacts.isNotEmpty())
        assertTrue(a.state.rooms.size > 6)
        assertEquals(Activity.entries.toSet(), counts.keys)
    }
    @Test fun movementDoesNotApplyDistantResourceEffects() {
        val initial = WorldActor.genesis()
        val agents = initial.agents.map { it.copy(x = 11.0, z = 11.0, needs = it.needs.copy(hydration = .1), activity = Activity.WATER) }
        val a = WorldActor(Checkpoint(initial.copy(agents = agents), 0, WorldActor.GENESIS_HASH))
        val t = a.step()
        assertTrue(t.event.actions.all { it.outcome == "MOVE" })
        assertEquals(initial.balances, t.state.balances)
        assertTrue(t.state.agents.all { it.needs.hydration < .1 })
    }
    @Test fun exclusiveLibraryAllowsOnlyOneAgentEachTick() {
        val initial = WorldActor.genesis()
        val room = initial.rooms.first { it.id == "library" }
        val agents = initial.agents.map { it.copy(x = room.x, z = room.z, activity = Activity.WRITE, needs = Needs()) }
        val a = WorldActor(Checkpoint(initial.copy(agents = agents), 0, WorldActor.GENESIS_HASH))
        val t = a.step()
        assertEquals(1, t.event.actions.count { it.outcome == "OK" && it.activity == Activity.WRITE })
        assertTrue(t.event.actions.any { it.outcome == "RESERVED" })
        assertEquals(1, t.state.artifacts.size)
    }
    @Test fun eventHashCoversActionsTickStateAndPreviousHash() {
        val e = WorldActor().step().event
        assertTrue(e.valid())
        assertFalse(e.copy(tick = 9).valid())
        assertFalse(e.copy(previousHash = "x").valid())
        assertFalse(e.copy(actions = emptyList()).valid())
        assertEquals(e, Canonical.event(Canonical.event(e)))
    }
    @Test fun snapshotsAlreadyPublishedRemainUnchanged() {
        val a = WorldActor(); val old = a.state; val payload = Canonical.state(old)
        repeat(20) { a.step() }
        assertEquals(payload, Canonical.state(old))
        assertEquals(0L, old.tick)
    }
    @Test fun skillMustHaveThreeValidatedPracticesBeforeSharing() {
        var skill = Skill()
        repeat(2) { skill = skill.practice(it.toLong()); assertFalse(skill.shareable) }
        skill = skill.practice(3)
        assertTrue(skill.shareable)
        assertTrue(skill.mastery > .2 && skill.mastery <= 1)
    }
    @Test fun dominanceDoesNotUseLearnedMastery() {
        val locus = Locus(.2, .8, .25, .75)
        assertEquals(.65, locus.express(), .000001)
        val genome = WorldActor.genesis().agents.first().genome
        val a = WorldActor(); repeat(100) { a.step() }
        assertEquals(genome, a.state.agents.first().genome)
    }
    @Test fun goapPlansToEarnCoinsBeforePurchasingFood() {
        val base = WorldActor.genesis()
        val hungry = base.agents.first().copy(
            needs = Needs(energy = 1.0, hydration = 1.0, glucose = .1, social = 1.0, curiosity = 1.0)
        )
        val world = base.copy(
            agents = base.agents.map { if (it.id == hungry.id) hungry else it },
            balances = base.balances + ("${hungry.id}/coins" to 0)
        )
        val before = Canonical.state(world)
        assertEquals(Activity.WRITE, UtilityGoapPlanner.choose(world, hungry, Activity.STUDY))
        assertEquals(before, Canonical.state(world))
    }

    @Test fun goapAvoidsAnUnavailableResourceAndUsesAnotherValidGoal() {
        val base = WorldActor.genesis()
        val citizen = base.agents.first().copy(
            needs = Needs(energy = 1.0, hydration = .1, glucose = 1.0, social = .1, curiosity = 1.0)
        )
        val world = base.copy(
            agents = base.agents.map { if (it.id == citizen.id) citizen else it },
            balances = base.balances + mapOf(
                "market/water" to 0,
                "market/food" to 0,
                "${citizen.id}/coins" to 0
            )
        )
        assertEquals(Activity.TALK, UtilityGoapPlanner.choose(world, citizen, Activity.WATER))
    }

    @Test fun versionSixGoapEventsReplayWithoutChangingHistoricalRules() {
        val origin = WorldActor.genesis()
        val a = WorldActor(Checkpoint(origin, 0, WorldActor.GENESIS_HASH), 6)
        val b = WorldActor(Checkpoint(origin, 0, WorldActor.GENESIS_HASH), 6)
        val intents = a.intentions()
        val poses = origin.agents.associate { agent ->
            val point = SpatialPoint(agent.x, 0.0, agent.z)
            agent.id to SpatialPose(point, 0.0, 0.0, 0.0, 0.0, false, listOf(point))
        }
        val frame = SpatialFrame(
            sourceTick = origin.tick,
            sourceHash = Canonical.hash(Canonical.state(origin)),
            seed = origin.seed,
            intentions = intents,
            poses = poses
        )
        val transition = a.step(spatial = frame)
        assertEquals(6, transition.event.version)
        assertTrue(transition.event.valid())
        b.replay(transition.event)
        assertEquals(a.checkpoint(), b.checkpoint())
    }

}
