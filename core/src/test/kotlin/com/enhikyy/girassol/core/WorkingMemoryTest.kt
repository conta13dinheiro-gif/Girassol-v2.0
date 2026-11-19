package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class WorkingMemoryTest {
    @Test fun bufferIsBoundedToSevenAndKeepsStrongRecentActivations() {
        val known = (1L..8L).toSet()
        var memory = WorkingMemoryState()
        (1L..8L).forEach { id ->
            memory = memory.activate(id, id, 0.5, known)
        }
        assertEquals(WorkingMemoryState.CAPACITY, memory.slots.size)
        assertTrue(memory.slots.any { it.eventId == 8L })
        assertFalse(memory.slots.any { it.eventId == 1L })
        assertTrue(memory.valid(8, known))
    }

    @Test fun activationHasDeterministicHalfLifeAndPrunesExpiredSlots() {
        val initial = WorkingMemoryState(
            slots = listOf(WorkingMemorySlot(eventId = 1, activation = 0.8, lastUpdatedTick = 10))
        )
        val decayed = initial.decay(42, setOf(1L))
        assertEquals(0.4, decayed.slots.single().activation, 1e-12)
        assertEquals(42L, decayed.slots.single().lastUpdatedTick)
        assertTrue(decayed.decay(1000, setOf(1L)).slots.isEmpty())
    }

    @Test fun consolidationRemovesSlotsWhoseEpisodesWerePruned() {
        val initial = WorkingMemoryState(
            slots = listOf(WorkingMemorySlot(1, 0.8, 10), WorkingMemorySlot(2, 0.6, 10))
        )
        val retained = initial.retain(12, setOf(2L))
        assertEquals(listOf(2L), retained.slots.map { it.eventId })
    }

    @Test fun committedEpisodesAndRecallsPopulateTheSamePersistedWorkingBuffer() {
        val world = WorldActor.genesis().copy(tick = 1)
        val citizen = world.agents.first().copy(
            activity = Activity.STUDY,
            memories = listOf(Memory(1, 1, Activity.STUDY, 0.15))
        )
        val bank = MemoryBank().observe(world, citizen, ActionRecord(citizen.id, Activity.STUDY, "OK"))
        assertTrue(bank.workingMemory.slots.any { it.eventId == 1L })

        val recalled = bank.recordRecall(listOf(1L), 1)
        assertTrue(recalled.workingMemory.slots.single().activation > bank.workingMemory.slots.single().activation)
        assertTrue(recalled.workingMemoryBonus(Activity.STUDY, 1) > 0.0)
        assertTrue(recalled.valid(1))
    }

    @Test fun timeCannotMoveBackwardsForActiveSlots() {
        val memory = WorkingMemoryState(slots = listOf(WorkingMemorySlot(1, 0.5, 10)))
        try {
            memory.decay(9, setOf(1L))
            fail("Expected backwards-time rejection")
        } catch (_: IllegalArgumentException) {
            // Expected deterministic boundary rejection.
        }
    }
}
