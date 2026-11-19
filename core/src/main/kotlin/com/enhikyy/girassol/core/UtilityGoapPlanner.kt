package com.enhikyy.girassol.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/**
 * Deterministic goal-oriented planner for the existing biosphere action vocabulary.
 *
 * The planner searches short action sequences over a compact resource/need state.
 * It never mutates WorldState: WorldActor still validates every real action and
 * is the only place where balances, memories and rooms can change.
 */
internal object UtilityGoapPlanner {
    private const val HORIZON = 3
    private val actions = listOf(
        Activity.WATER, Activity.EAT, Activity.SLEEP, Activity.COFFEE,
        Activity.TALK, Activity.STUDY, Activity.WRITE, Activity.BUILD
    )

    private data class SimState(
        val energy: Int,
        val hydration: Int,
        val glucose: Int,
        val social: Int,
        val curiosity: Int,
        val water: Int,
        val food: Int,
        val coffee: Int,
        val paper: Int,
        val timber: Int,
        val coins: Int,
        val marketCoins: Int,
        val rooms: Int,
        val nearbyPartner: Boolean
    )

    fun choose(world: WorldState, citizen: Citizen, fallback: Activity): Activity {
        val needs = citizen.needs
        val initial = SimState(
            energy = bucket(needs.energy),
            hydration = bucket(needs.hydration),
            glucose = bucket(needs.glucose),
            social = bucket(needs.social),
            curiosity = bucket(needs.curiosity),
            water = stock(world, "market/water"),
            food = stock(world, "market/food"),
            coffee = stock(world, "market/coffee"),
            paper = stock(world, "market/paper"),
            timber = stock(world, "market/timber"),
            coins = stock(world, "${citizen.id}/coins"),
            marketCoins = stock(world, "market/coins"),
            rooms = world.rooms.size,
            nearbyPartner = world.agents.any {
                it.id != citizen.id && hypot(it.x - citizen.x, it.z - citizen.z) <= 2.5
            }
        )

        var bestAction = fallback
        var bestValue = 0.0

        fun search(state: SimState, first: Activity?, depth: Int) {
            if (depth == HORIZON) return
            for (action in actions) {
                val next = apply(state, action) ?: continue
                val rootAction = first ?: action
                val value = quality(next) - quality(initial) - (depth + 1) * 0.018 +
                    if (rootAction == fallback) 0.012 else 0.0
                if (value > bestValue + 1e-9 ||
                    (abs(value - bestValue) <= 1e-9 && rootAction == fallback && bestAction != fallback)) {
                    bestValue = value
                    bestAction = rootAction
                }
                search(next, rootAction, depth + 1)
            }
        }

        search(initial, null, 0)
        return if (bestValue > 0.015) bestAction else fallback
    }

    private fun apply(s: SimState, action: Activity): SimState? = when (action) {
        Activity.WATER -> if (s.hydration < 10 && s.water > 0) s.copy(hydration = 10, water = s.water - 1) else null
        Activity.EAT -> if (s.glucose < 10 && s.food > 0 && s.coins > 0)
            s.copy(glucose = 10, food = s.food - 1, coins = s.coins - 1, marketCoins = s.marketCoins + 1)
            else null
        Activity.COFFEE -> if (s.energy < 10 && s.coffee > 0)
            s.copy(energy = min(10, s.energy + 1), coffee = s.coffee - 1) else null
        Activity.SLEEP -> if (s.energy < 10) s.copy(energy = min(10, s.energy + 1)) else null
        Activity.TALK -> if (s.social < 10 && s.nearbyPartner) s.copy(social = min(10, s.social + 2)) else null
        Activity.STUDY -> if (s.curiosity < 10) s.copy(curiosity = min(10, s.curiosity + 1)) else null
        Activity.WRITE -> if (s.paper > 0 && (s.curiosity < 10 || (s.coins == 0 && s.marketCoins > 0))) {
            val earnsWage = s.marketCoins > 0 && s.coins < Int.MAX_VALUE
            s.copy(
                curiosity = min(10, s.curiosity + 2),
                paper = s.paper - 1,
                coins = if (earnsWage) s.coins + 1 else s.coins,
                marketCoins = if (earnsWage) s.marketCoins - 1 else s.marketCoins
            )
        } else null
        Activity.BUILD -> if (s.timber >= 4 && s.curiosity < 10) {
            val earnsWage = s.marketCoins > 0 && s.coins < Int.MAX_VALUE
            s.copy(
                curiosity = min(10, s.curiosity + 2),
                timber = s.timber - 4,
                rooms = s.rooms + 1,
                coins = if (earnsWage) s.coins + 1 else s.coins,
                marketCoins = if (earnsWage) s.marketCoins - 1 else s.marketCoins
            )
        } else null
    }

    private fun quality(s: SimState): Double =
        1.25 * s.energy / 10.0 +
            1.60 * s.hydration / 10.0 +
            1.40 * s.glucose / 10.0 +
            0.90 * s.social / 10.0 +
            0.70 * s.curiosity / 10.0 +
            0.10 * min(s.coins, 4)

    private fun bucket(value: Double): Int = floor(value.coerceIn(0.0, 1.0) * 10.0).toInt().coerceIn(0, 10)
    private fun stock(world: WorldState, account: String): Int = (world.balances[account] ?: 0).coerceAtLeast(0)
}
