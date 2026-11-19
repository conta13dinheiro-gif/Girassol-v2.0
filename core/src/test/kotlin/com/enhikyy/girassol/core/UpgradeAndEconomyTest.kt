package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class UpgradeAndEconomyTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResource("/$name")).readText()

    @Test fun originalEventsReplayAndOldCheckpointContinuesWithVersionTwo() {
        val oldEvents = resource("legacy-v1-events.jsonl").lineSequence().filter { it.isNotBlank() }.map(Canonical::event).toList()
        val restored = WorldActor()
        oldEvents.forEach { e -> assertEquals(1, e.version); restored.replay(e); restored.replay(e) }
        assertEquals(resource("legacy-v1-state.json"), Canonical.state(restored.state))
        val before = restored.checkpoint()
        val next = restored.step()
        assertEquals(2, next.event.version)
        assertEquals(before.eventHash, next.event.previousHash)
        assertEquals(before.eventId + 1, next.event.eventId)
        val replayed = WorldActor(before)
        replayed.replay(next.event)
        assertEquals(restored.checkpoint(), replayed.checkpoint())
    }

    @Test fun recyclingTransfersOnlyAvailableStocksAndPreservesTotals() {
        val initial = WorldActor.genesis()
        val b = initial.balances.toMutableMap()
        assertTrue(WorldActor.transfer(b, "market", "environment", "water", 3))
        assertTrue(WorldActor.transfer(b, "market", "environment", "food", 2))
        assertTrue(WorldActor.transfer(b, "market", "environment", "coffee", 1))
        assertTrue(WorldActor.transfer(b, "market", "archive", "paper", 1))
        val agents = initial.agents.map { it.copy(x = 11.0, z = 11.0, activity = Activity.SLEEP, acting = false, needs = Needs()) }
        val state = initial.copy(tick = 5, balances = b.toMap(), agents = agents)
        val actor = WorldActor(Checkpoint(state, 5, WorldActor.GENESIS_HASH))
        val next = actor.step().state
        assertEquals(2, next.balances["environment/water"])
        assertEquals(1, next.balances["environment/food"])
        assertEquals(1, next.balances["environment/coffee"])
        assertEquals(0, next.balances["archive/paper"])
        assertTrue(WorldActor.violations(next).isEmpty())
        repeat(12) { assertTrue(WorldActor.violations(actor.step().state).isEmpty()) }
        assertEquals(0, actor.state.balances["environment/water"])
        assertEquals(0, actor.state.balances["environment/food"])
        assertEquals(0, actor.state.balances["environment/coffee"])
        assertEquals(initial.initialTotals, actor.state.initialTotals)
    }

    @Test fun hungryCitizenWithoutMoneyCanEarnAndThenBuyFood() {
        val initial = WorldActor.genesis()
        val b = initial.balances.toMutableMap()
        assertTrue(WorldActor.transfer(b, "agent0", "market", "coins", 24))
        val library = initial.rooms.first { it.id == "library" }
        val agents = initial.agents.mapIndexed { i, a ->
            if (i == 0) a.copy(x = library.x, z = library.z, needs = Needs(glucose = .1), activity = Activity.EAT)
            else a.copy(x = 11.0, z = 11.0, activity = Activity.SLEEP, needs = Needs())
        }
        val actor = WorldActor(Checkpoint(initial.copy(balances = b, agents = agents), 0, WorldActor.GENESIS_HASH))
        val worked = actor.step()
        assertTrue(worked.event.actions.any { it.agentId == "agent0" && it.activity == Activity.WRITE && it.outcome == "OK" })
        assertEquals(1, worked.state.balances["agent0/coins"])
        assertEquals(1, worked.state.artifacts.size)
        val eventTypes = mutableListOf<ActionRecord>()
        repeat(40) { eventTypes.addAll(actor.step().event.actions) }
        assertTrue(eventTypes.any { it.agentId == "agent0" && it.activity == Activity.EAT && it.outcome == "OK" })
        assertTrue(WorldActor.violations(actor.state).isEmpty())
    }

    @Test fun noWageWhenWritingCannotBePerformed() {
        val initial = WorldActor.genesis()
        val b = initial.balances.toMutableMap()
        assertTrue(WorldActor.transfer(b, "market", "archive", "paper", b.getValue("market/paper")))
        val library = initial.rooms.first { it.id == "library" }
        val agents = initial.agents.mapIndexed { i, a -> if (i == 0) a.copy(x = library.x, z = library.z, activity = Activity.WRITE, needs = Needs()) else a.copy(x = 11.0, z = 11.0, activity = Activity.SLEEP, needs = Needs()) }
        val actor = WorldActor(Checkpoint(initial.copy(balances = b, agents = agents), 0, WorldActor.GENESIS_HASH))
        val t = actor.step()
        assertTrue(t.event.actions.any { it.agentId == "agent0" && it.activity == Activity.WRITE && it.outcome == "PRECONDITION_FAILED" })
        assertEquals(initial.balances["agent0/coins"], t.state.balances["agent0/coins"])
    }

    @Test fun dialogueTopicsReflectMemoriesAndAreDeterministic() {
        val a = WorldActor(); val b = WorldActor()
        repeat(1500) { assertEquals(a.step(), b.step()) }
        assertTrue(a.state.dialogues.map { it.portuguese }.distinct().size > 1)
        assertTrue(a.state.words.filter { it.concept != Activity.TALK }.any { it.uses > 0 })
    }
}
