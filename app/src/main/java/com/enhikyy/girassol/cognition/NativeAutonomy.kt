package com.enhikyy.girassol.cognition

import com.enhikyy.girassol.core.*
import kotlin.math.hypot

/** Explicitly packed 56-float/citizen JNI bridge; native code never sees Kotlin object layouts. */
internal object NativeAutonomy {
    private const val STRIDE = 56
    private const val DT = 1.0 / 60.0
    init { com.enhikyy.girassol.cognition.GeaNativeBridge.requireAvailable() }
    private external fun advance(ids: LongArray, packed: FloatArray, couplingK: Float): Float

    data class Result(
        val psyches: Map<String, SoulModel>,
        val actualSensory: Map<String, List<Double>>,
        val orderParameter: Double
    )

    fun evaluate(world: WorldState, lastActions: List<ActionRecord>, minds: Map<String, MindState>): Result {
        require(minds.keys == world.agents.map { it.id }.toSet())
        require(world.agents.size in 1..4096)
        val ids = LongArray(world.agents.size)
        val packed = FloatArray(world.agents.size * STRIDE)
        val actualById = LinkedHashMap<String, List<Double>>(world.agents.size)
        var warmthSum = 0.0

        world.agents.forEachIndexed { index, citizen ->
            val offset = index * STRIDE
            val mind = minds.getValue(citizen.id)
            val psyche = mind.psyche
            val sensory = sense(world, citizen, mind, lastActions)
            actualById[citizen.id] = sensory
            val warmth = world.ties.asSequence().filter { it.from == citizen.id }
                .map { it.affinity.coerceIn(0.0, 1.0) }.average().let { if (it.isFinite()) it else .45 }
            warmthSum += warmth
            val outcome = lastActions.lastOrNull { it.agentId == citizen.id }?.outcome
            val surprise = when (outcome) {
                "PRECONDITION_FAILED" -> .9
                "RESERVED" -> .65
                "MOVE" -> .3
                "OK" -> .08
                else -> .12
            }
            val threat = maxOf(1.0 - citizen.needs.energy, 1.0 - citizen.needs.hydration, 1.0 - citizen.needs.glucose)
                .coerceIn(0.0, 1.0)
            val salience = maxOf(threat, 1.0 - citizen.needs.social, 1.0 - citizen.needs.curiosity).coerceIn(0.0, 1.0)
            val somaticBias = (psyche.pleasure - psyche.dominance).coerceIn(-1.0, 1.0)
            val expected = if (psyche.initialized) psyche.expectedSensory else sensory
            fun put(position: Int, value: Double) { packed[offset + position] = value.toFloat() }

            put(0, psyche.ageYears)
            put(1, psyche.biologicalHealth)
            put(2, psyche.attachment.ordinal.toDouble())
            psyche.bigFive.forEachIndexed { i, value -> put(3 + i, value) }
            put(8, psyche.cortisol); put(9, psyche.oxytocin); put(10, psyche.dopamine)
            put(11, psyche.pleasure); put(12, psyche.arousal); put(13, psyche.dominance)
            expected.forEachIndexed { i, value -> put(14 + i, value) }
            put(30, psyche.lastPredictionError); put(31, psyche.kuramotoPhase)
            put(32, psyche.kuramotoNaturalFrequency); put(33, psyche.ignitionIndex)
            put(34, threat); put(35, warmth); put(36, surprise)
            put(37, salience); put(38, somaticBias); put(39, DT)
            sensory.forEachIndexed { i, value -> put(40 + i, value) }
            ids[index] = Canonical.stream(world.seed, citizen.id, world.tick, "anokhin-citizen-id-v1").nextLong()
        }

        val coupling = (.55 + .65 * (warmthSum / world.agents.size)).coerceIn(0.0, 1.5).toFloat()
        val order = advance(ids, packed, coupling)
        check(order.isFinite() && order in 0f..1f) { "Resultado de sincronização Anokhin inválido" }

        val updated = LinkedHashMap<String, SoulModel>(world.agents.size)
        world.agents.forEachIndexed { index, citizen ->
            val o = index * STRIDE
            val old = minds.getValue(citizen.id).psyche
            fun value(position: Int) = packed[o + position].toDouble()
            val next = old.copy(
                ageYears = (old.ageYears + DT / (365.25 * 24.0 * 3600.0)).coerceAtMost(120.0),
                biologicalHealth = value(1).coerceIn(0.0, 1.0),
                attachment = AttachmentPattern.entries[value(2).toInt().coerceIn(0, 3)],
                bigFive = (3 until 8).map { value(it).coerceIn(0.0, 1.0) },
                cortisol = value(8).coerceIn(0.0, 1.0),
                oxytocin = value(9).coerceIn(0.0, 1.0),
                dopamine = value(10).coerceIn(0.0, 1.0),
                pleasure = value(11).coerceIn(-1.0, 1.0),
                arousal = value(12).coerceIn(-1.0, 1.0),
                dominance = value(13).coerceIn(-1.0, 1.0),
                lastPredictionError = value(30).coerceIn(0.0, 1.0),
                kuramotoPhase = value(31),
                kuramotoNaturalFrequency = value(32).coerceIn(.25, 4.0),
                ignitionIndex = value(33).coerceIn(0.0, 1.0),
                initialized = true
            )
            check(next.valid()) { "Estado somático inválido para ${citizen.id}" }
            updated[citizen.id] = next
        }
        return Result(updated, actualById, order.toDouble())
    }

    fun predictNext(world: WorldState, citizen: Citizen, action: Activity, sensory: List<Double>): List<Double> {
        require(sensory.size == 16 && sensory.all { it.isFinite() && it in 0.0..1.0 })
        val result = sensory.toMutableList()
        result[0] = (result[0] - .002).coerceAtLeast(0.0)
        result[1] = (result[1] - .003).coerceAtLeast(0.0)
        result[2] = (result[2] - .002).coerceAtLeast(0.0)
        result[3] = (result[3] - .002).coerceAtLeast(0.0)
        result[4] = (result[4] - .001).coerceAtLeast(0.0)
        val room = world.rooms.first { it.id == action.room }
        val distance = hypot(room.x - citizen.x, room.z - citizen.z)
        if (distance <= .35) {
            when (action) {
                Activity.WATER -> result[1] = .99
                Activity.COFFEE -> result[0] = (result[0] + .12).coerceAtMost(1.0)
                Activity.EAT -> result[2] = .99
                Activity.SLEEP -> result[0] = (result[0] + .1).coerceAtMost(1.0)
                Activity.TALK -> result[3] = (result[3] + .15).coerceAtMost(1.0)
                Activity.STUDY -> result[4] = (result[4] + .15).coerceAtMost(1.0)
                Activity.WRITE -> result[4] = (result[4] + .25).coerceAtMost(1.0)
                Activity.BUILD -> result[4] = (result[4] + .2).coerceAtMost(1.0)
            }
        }
        result[6] = (1.0 - minOf(result[0], result[1], result[2])).coerceIn(0.0, 1.0)
        result[10] = action.ordinal / 7.0
        result[11] = 1.0
        result[12] = (distance / 50.0).coerceIn(0.0, 1.0)
        return result
    }

    private fun sense(world: WorldState, citizen: Citizen, mind: MindState, lastActions: List<ActionRecord>): List<Double> {
        val outcome = lastActions.lastOrNull { it.agentId == citizen.id }?.outcome
        val destination = world.rooms.first { it.id == citizen.activity.room }
        val nearby = world.agents.count { it.id != citizen.id && hypot(it.x - citizen.x, it.z - citizen.z) <= 2.5 }
        val warmth = world.ties.asSequence().filter { it.from == citizen.id }
            .map { it.affinity }.average().let { if (it.isFinite()) it else .45 }
        val threat = maxOf(1.0 - citizen.needs.energy, 1.0 - citizen.needs.hydration, 1.0 - citizen.needs.glucose)
            .coerceIn(0.0, 1.0)
        return listOf(
            citizen.needs.energy, citizen.needs.hydration, citizen.needs.glucose,
            citizen.needs.social, citizen.needs.curiosity, mind.psyche.biologicalHealth, threat,
            warmth.coerceIn(0.0, 1.0), mind.novelty.coerceIn(0.0, 1.0),
            when (outcome) { "OK" -> 1.0; "MOVE" -> .65; "RESERVED" -> .35; "PRECONDITION_FAILED" -> 0.0; else -> .5 },
            citizen.activity.ordinal / 7.0, if (citizen.acting) 1.0 else 0.0,
            (hypot(destination.x - citizen.x, destination.z - citizen.z) / 50.0).coerceIn(0.0, 1.0),
            (nearby / 5.0).coerceIn(0.0, 1.0), mind.psyche.dopamine, mind.psyche.oxytocin
        ).map { it.coerceIn(0.0, 1.0) }
    }
}
