package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class NeuralProposalTest {
    private fun ready(): WorldState {
        val world = WorldActor.genesis()
        return world.copy(agents = world.agents.map { it.copy(needs = Needs(.95, .95, .95, .7, .7)) })
    }
    private fun proposal(world: WorldState, action: Activity = Activity.STUDY) = NeuralProposal(
        world.seed, world.tick, world.agents.first().id, action, Canonical.hash(Canonical.state(world)), "a".repeat(64))
    @Test fun recordedProposalReplaysWithoutModelAndOldEnvelopesKeepTheirShape() {
        val initial = ready()
        val actor = WorldActor(Checkpoint(initial, 0, WorldActor.GENESIS_HASH))
        val neural = proposal(initial)
        val event = actor.step(neural).event
        assertEquals(3, event.version)
        assertEquals(neural, event.neural)
        assertTrue(event.valid())
        assertEquals(Activity.STUDY, event.actions.first { it.agentId == neural.agentId }.activity)
        val replay = WorldActor(Checkpoint(initial, 0, WorldActor.GENESIS_HASH))
        replay.replay(Canonical.event(Canonical.event(event)))
        assertEquals(actor.checkpoint(), replay.checkpoint())
        replay.replay(event)
        assertEquals(actor.checkpoint(), replay.checkpoint())
        val old = WorldActor().step().event
        assertEquals(2, old.version)
        assertFalse(Canonical.event(old).contains("neural"))
    }
    @Test fun urgentNeedsStaleFutureUnknownAndMalformedProposalsAreIgnored() {
        val initial = ready()
        val valid = proposal(initial)
        val urgent = initial.copy(agents = initial.agents.map { it.copy(needs = it.needs.copy(hydration = .2)) })
        assertNull(WorldActor(Checkpoint(urgent, 0, WorldActor.GENESIS_HASH)).step(valid).event.neural)
        val stale = initial.copy(tick = 600)
        assertFalse(valid.applicable(stale))
        listOf(valid.copy(seed = -1), valid.copy(sourceTick = 100), valid.copy(agentId = "absent"),
            valid.copy(modelHash = "not-a-hash"), valid.copy(sourceStateHash = "")).forEach {
            assertFalse(it.applicable(initial))
        }
    }
    @Test fun neuralActionCannotConsumeAtDistanceOrCreateResources() {
        val world = ready()
        val actor = WorldActor(Checkpoint(world, 0, WorldActor.GENESIS_HASH))
        val target = world.agents.first()
        val event = actor.step(proposal(world, Activity.EAT)).event
        assertEquals("MOVE", event.actions.first { it.agentId == target.id }.outcome)
        assertTrue(WorldActor.violations(actor.state).isEmpty())
        assertEquals(target.needs.glucose - .002, actor.state.agents.first().needs.glucose, 1e-9)
    }
    @Test fun alteredRecordedDecisionDivergesAndLeavesReplayUnchanged() {
        val world = ready()
        val source = WorldActor(Checkpoint(world, 0, WorldActor.GENESIS_HASH))
        val event = source.step(proposal(world)).event
        val modified = event.copy(neural = event.neural!!.copy(activity = Activity.SLEEP), hash = "")
            .let { it.copy(hash = it.computedHash()) }
        val replay = WorldActor(Checkpoint(world, 0, WorldActor.GENESIS_HASH))
        try { replay.replay(modified); fail("Expected divergence") } catch (_: IllegalArgumentException) {}
        assertEquals(world, replay.state)
    }
    @Test fun mixedNeuralAndUtilityHistoryResumesAndReplaysAcrossCheckpoints() {
        val actor = WorldActor()
        val events = (0 until 400).map { tick ->
            val world = actor.state
            val candidate = world.agents.firstOrNull { it.acceptsNeuralActivity() }
            val input = if (tick % 17 == 0 && candidate != null)
                proposal(world).copy(agentId = candidate.id) else null
            actor.step(input).event
        }
        assertTrue(events.any { it.version == 3 })
        assertTrue(events.any { it.version == 2 })
        val replay = WorldActor()
        events.take(200).forEach(replay::replay)
        val resumed = WorldActor(replay.checkpoint())
        events.drop(200).forEach(resumed::replay)
        assertEquals(actor.checkpoint(), resumed.checkpoint())
        assertTrue(WorldActor.violations(resumed.state).isEmpty())
    }
    @Test fun parserRejectsCodeUnknownFieldsInvalidEnumsAndOversizedOutput() {
        assertEquals(Activity.STUDY, NeuralProtocol.parse("{\"activity\":\"STUDY\"}"))
        listOf("{\"activity\":\"EXEC\"}", "{\"activity\":\"STUDY\",\"code\":\"run\"}",
            "STUDY", "{}", "x".repeat(257)).forEach {
            try { NeuralProtocol.parse(it); fail("Invalid output accepted") } catch (_: IllegalArgumentException) {}
        }
    }
}
