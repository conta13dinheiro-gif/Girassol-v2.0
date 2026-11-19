package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlin.math.exp

@Serializable
data class WorkingMemorySlot(
    val eventId: Long,
    val activation: Double,
    val lastUpdatedTick: Long
) {
    fun valid(currentTick: Long, knownEventIds: Set<Long>): Boolean =
        eventId in knownEventIds &&
            activation.isFinite() && activation in MIN_ACTIVATION..1.0 &&
            lastUpdatedTick in 0..currentTick

    companion object {
        const val MIN_ACTIVATION = 0.015
    }
}

/**
 * Seven-slot active buffer (the central value of the 7±2 working-memory heuristic).
 * Time is simulation ticks, not wall-clock time. Each cognitive access refreshes activation;
 * the half-life decay is deterministic and bounded.
 */
@Serializable
data class WorkingMemoryState(
    val slots: List<WorkingMemorySlot> = emptyList()
) {
    fun valid(currentTick: Long, knownEventIds: Set<Long>): Boolean =
        slots.size <= CAPACITY &&
            slots.map { it.eventId }.distinct().size == slots.size &&
            slots.all { it.valid(currentTick, knownEventIds) }

    /** Apply elapsed-time decay and discard slots whose episodes were consolidated away. */
    fun decay(currentTick: Long, knownEventIds: Set<Long>): WorkingMemoryState {
        require(currentTick >= 0)
        val next = slots.mapNotNull { slot ->
            require(currentTick >= slot.lastUpdatedTick) { "Working-memory time cannot move backwards" }
            if (slot.eventId !in knownEventIds) return@mapNotNull null
            val elapsed = (currentTick - slot.lastUpdatedTick).toDouble()
            val activation = slot.activation * exp(-LN_2 * elapsed / HALF_LIFE_TICKS)
            if (activation < WorkingMemorySlot.MIN_ACTIVATION) null
            else slot.copy(activation = activation, lastUpdatedTick = currentTick)
        }
        return copy(slots = order(next).take(CAPACITY))
    }

    /** Attend to an event. Repeated recall strengthens activation without exceeding one. */
    fun activate(
        eventId: Long,
        tick: Long,
        boost: Double,
        knownEventIds: Set<Long>
    ): WorkingMemoryState {
        require(eventId in knownEventIds)
        require(boost.isFinite() && boost in 0.0..1.0)
        val decayed = decay(tick, knownEventIds)
        val old = decayed.slots.firstOrNull { it.eventId == eventId }
        val previous = old?.activation ?: 0.0
        val activation = (1.0 - (1.0 - previous) * (1.0 - boost)).coerceIn(0.0, 1.0)
        val updated = decayed.slots.filterNot { it.eventId == eventId } +
            WorkingMemorySlot(eventId, activation, tick)
        return copy(slots = order(updated).take(CAPACITY))
    }

    /** Decay and retain only slots whose episodes survived consolidation/forgetting. */
    fun retain(currentTick: Long, knownEventIds: Set<Long>): WorkingMemoryState =
        decay(currentTick, knownEventIds)

    fun activationFor(eventIds: Set<Long>, tick: Long, knownEventIds: Set<Long>): Double =
        decay(tick, knownEventIds).slots
            .filter { it.eventId in eventIds }
            .maxOfOrNull { it.activation } ?: 0.0

    private fun order(items: List<WorkingMemorySlot>) =
        items.sortedWith(
            compareByDescending<WorkingMemorySlot> { it.activation }
                .thenByDescending { it.lastUpdatedTick }
                .thenBy { it.eventId }
        )

    companion object {
        const val CAPACITY = 7
        const val HALF_LIFE_TICKS = 32.0
        private const val LN_2 = 0.6931471805599453
    }
}
