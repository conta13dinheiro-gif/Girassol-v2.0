package com.enhikyy.girassol.core

import kotlin.math.ln
import kotlin.math.pow

data class ActiveInferenceNeeds(
    val hydration: Double,
    val energy: Double,
    val glucose: Double,
    val social: Double,
    val curiosity: Double
) {
    init {
        require(listOf(hydration, energy, glucose, social, curiosity).all { it.isFinite() && it in 0.0..1.0 })
    }
    internal fun values() = listOf(hydration, energy, glucose, social, curiosity)
    internal fun after(action: Activity): ActiveInferenceNeeds {
        val relief = when (action) {
            Activity.WATER -> 0.30 to 0
            Activity.COFFEE -> 0.12 to 1
            Activity.EAT -> 0.30 to 2
            Activity.SLEEP -> 0.28 to 1
            Activity.TALK -> 0.25 to 3
            Activity.STUDY -> 0.25 to 4
            Activity.WRITE -> 0.18 to 4
            Activity.BUILD -> 0.10 to 4
        }
        val (delta, index) = relief
        val next = values().toMutableList()
        next[index] = (next[index] + delta).coerceAtMost(1.0)
        return ActiveInferenceNeeds(next[0], next[1], next[2], next[3], next[4])
    }
}

data class ActiveInferencePolicyEstimate(
    val activity: Activity,
    val expectedFreeEnergy: Double,
    val pragmaticRisk: Double,
    val epistemicValue: Double,
    val complexityCost: Double,
    val utility: Double
)

/** Stable identity preferences are intentionally small tie-breakers, never safety overrides. */
object CoreSelfAnchor {
    const val ID = "girassol-core-self-v1"
    const val CONTINUITY_WEIGHT = 0.018
    private val values = mapOf(
        Activity.WATER to 1.00, Activity.EAT to 1.00, Activity.SLEEP to 1.00,
        Activity.STUDY to 0.88, Activity.TALK to 0.82, Activity.WRITE to 0.74,
        Activity.BUILD to 0.62, Activity.COFFEE to 0.40
    )
    fun valueFor(activity: Activity): Double = values[activity] ?: 0.5
}

data class ActiveInferenceResult(
    val variationalFreeEnergy: Double,
    val expectedFreeEnergyByActivity: Map<Activity, Double>,
    val utilityByActivity: Map<Activity, Double>,
    val globalWorkspaceTopThree: List<ActiveInferencePolicyEstimate>,
    val coreSelfAnchorId: String
)

/**
 * Bounded PIANO-style policy evaluation. Lower expected free energy is preferred.
 * The engine is pure and deterministic; the world thread remains the state owner.
 */
object ActiveInferencePianoEngine {
    private const val PREFERENCE = 0.86
    private const val EPSILON = 1e-6

    fun evaluate(
        needs: ActiveInferenceNeeds,
        novelty: Double,
        predictionError: Double,
        openness: Double,
        dopamine: Double,
        previousReward: Double,
        thermalScale: Double,
        currentActivity: Activity,
        learnedValues: Map<Activity, Double> = emptyMap()
    ): ActiveInferenceResult {
        require(listOf(novelty, predictionError, openness, dopamine, thermalScale).all { it.isFinite() })
        require(novelty in 0.0..1.0 && predictionError in 0.0..1.0)
        require(openness in 0.0..1.0 && dopamine in 0.0..1.0)
        require(previousReward.isFinite() && previousReward in -1.0..1.0)
        require(thermalScale in 0.0..1.0)
        require(learnedValues.values.all { it.isFinite() && it in -1.0..1.0 })

        fun bernoulliKl(q: Double, p: Double): Double {
            val qSafe = q.coerceIn(EPSILON, 1.0 - EPSILON)
            val pSafe = p.coerceIn(EPSILON, 1.0 - EPSILON)
            return qSafe * ln(qSafe / pSafe) + (1.0 - qSafe) * ln((1.0 - qSafe) / (1.0 - pSafe))
        }
        val variational = needs.values().sumOf { bernoulliKl(it, PREFERENCE) } / 5.0 +
            predictionError * 0.20 + (1.0 - dopamine) * 0.015

        val estimates = Activity.entries.map { action ->
            val predicted = needs.after(action).values()
            val pragmaticRisk = predicted.sumOf { (PREFERENCE - it).pow(2) } / predicted.size
            val curiosityNeed = 1.0 - needs.curiosity
            val informationYield = when (action) {
                Activity.STUDY -> 1.00
                Activity.WRITE -> 0.78
                Activity.TALK -> 0.64
                Activity.BUILD -> 0.42
                Activity.WATER, Activity.COFFEE, Activity.EAT, Activity.SLEEP -> 0.18
            }
            val epistemicValue = (
                curiosityNeed * (0.35 + 0.65 * novelty) *
                    (0.45 + 0.55 * openness) * informationYield
                ).coerceIn(0.0, 1.0) * thermalScale
            val complexityCost = (0.012 + (1.0 - novelty) * 0.018 * informationYield) *
                (0.5 + thermalScale * 0.5)
            val learned = (learnedValues[action] ?: 0.0).coerceIn(-1.0, 1.0)
            val rewardPrior = if (action == currentActivity) previousReward * 0.025 else 0.0
            val expected = pragmaticRisk + complexityCost +
                predictionError * 0.025 * thermalScale -
                epistemicValue * 0.22 - learned * 0.06 - rewardPrior
            val selfBias = CoreSelfAnchor.valueFor(action) * CoreSelfAnchor.CONTINUITY_WEIGHT
            ActiveInferencePolicyEstimate(
                activity = action,
                expectedFreeEnergy = expected,
                pragmaticRisk = pragmaticRisk,
                epistemicValue = epistemicValue,
                complexityCost = complexityCost,
                utility = (-expected + selfBias).coerceIn(-1.0, 1.0)
            )
        }.sortedWith(compareBy<ActiveInferencePolicyEstimate> { it.expectedFreeEnergy }.thenBy { it.activity.ordinal })

        return ActiveInferenceResult(
            variationalFreeEnergy = variational.coerceAtLeast(0.0),
            expectedFreeEnergyByActivity = estimates.associate { it.activity to it.expectedFreeEnergy },
            utilityByActivity = estimates.associate { it.activity to it.utility },
            globalWorkspaceTopThree = estimates.take(3),
            coreSelfAnchorId = CoreSelfAnchor.ID
        )
    }
}
