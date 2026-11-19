package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.pow

/**
 * Per-activity learning state.
 *
 * BKT estimates latent knowledge. The DSR curve schedules practice in simulation ticks.
 * A compact 3PL item-response estimate adapts theta online; item discrimination and guessing
 * are fixed because the current world has no calibrated item bank.
 *
 * This is deliberately not labelled as a full FSRS-6/FSRS-7 implementation.
 */
@Serializable
data class SkillLearningTrace(
    val action: Activity,
    val knowledgeProbability: Double = INITIAL_KNOWLEDGE,
    val difficulty: Double = 5.0,
    val stabilityTicks: Double = 1.0,
    val theta: Double = 0.0,
    val reviews: Int = 0,
    val lastReviewTick: Long = -1
) {
    fun valid(currentTick: Long): Boolean =
        currentTick >= 0 &&
            knowledgeProbability.isFinite() && knowledgeProbability in 0.0..1.0 &&
            difficulty.isFinite() && difficulty in 1.0..10.0 &&
            stabilityTicks.isFinite() && stabilityTicks in MIN_STABILITY..MAX_STABILITY &&
            theta.isFinite() && theta in -4.0..4.0 &&
            reviews >= 0 &&
            lastReviewTick in -1..currentTick &&
            ((reviews == 0 && lastReviewTick == -1L) || (reviews > 0 && lastReviewTick >= 0))

    /** Probability of recalling the skill after elapsedTicks, using the requested DSR curve. */
    fun retrievability(currentTick: Long): Double {
        require(currentTick >= 0)
        if (lastReviewTick < 0) return 1.0
        require(currentTick >= lastReviewTick) { "Review time cannot move backwards" }
        val elapsed = (currentTick - lastReviewTick).toDouble()
        return (1.0 + DSR_FACTOR * elapsed / stabilityTicks).pow(-0.5).coerceIn(0.0, 1.0)
    }

    /** Interval, in simulation ticks, that targets the requested probability of recall. */
    fun intervalTicks(targetRetrievability: Double = DEFAULT_TARGET_RETRIEVABILITY): Double {
        require(targetRetrievability.isFinite() && targetRetrievability in 0.01..1.0)
        return stabilityTicks * (targetRetrievability.pow(-2.0) - 1.0) / DSR_FACTOR
    }

    /** 3PL success probability for the current ability estimate and this skill's difficulty. */
    fun responseProbability(): Double {
        val b = (difficulty - 5.5) / 2.25
        val logistic = 1.0 / (1.0 + exp(-(theta - b).coerceIn(-12.0, 12.0)))
        return (IRT_GUESSING + (1.0 - IRT_GUESSING) * logistic).coerceIn(IRT_GUESSING, 1.0)
    }

    /**
     * Small bounded incentive for practising a less-mastered or due skill.
     * It is additive to the existing action score, never a hard-coded action override.
     */
    fun practiceBonus(currentTick: Long): Double {
        val due = 1.0 - retrievability(currentTick)
        return (0.045 * (1.0 - knowledgeProbability) + 0.025 * due).coerceIn(0.0, 0.07)
    }

    /** Apply one validated success/failure observation and update BKT, DSR and IRT state. */
    fun observe(correct: Boolean, tick: Long): SkillLearningTrace {
        require(tick >= 0 && (lastReviewTick < 0 || tick >= lastReviewTick))
        val prior = (knowledgeProbability * retrievability(tick)).coerceIn(EPSILON, 1.0 - EPSILON)

        val likelihoodKnown = if (correct) 1.0 - BKT_SLIP else BKT_SLIP
        val likelihoodUnknown = if (correct) BKT_GUESS else 1.0 - BKT_GUESS
        val denominator = prior * likelihoodKnown + (1.0 - prior) * likelihoodUnknown
        val posterior = (prior * likelihoodKnown / denominator).coerceIn(0.0, 1.0)
        val learned = (posterior + (1.0 - posterior) * BKT_TRANSITION).coerceIn(0.0, 1.0)

        // Online 3PL ability update: gradient of the Bernoulli log-likelihood.
        val p = responseProbability().coerceIn(EPSILON, 1.0 - EPSILON)
        val logisticPart = ((p - IRT_GUESSING) / (1.0 - IRT_GUESSING)).coerceIn(EPSILON, 1.0 - EPSILON)
        val derivative = (1.0 - IRT_GUESSING) * logisticPart * (1.0 - logisticPart)
        val outcome = if (correct) 1.0 else 0.0
        val gradient = derivative * (outcome - p) / (p * (1.0 - p))
        val nextTheta = (theta + IRT_LEARNING_RATE * gradient).coerceIn(-4.0, 4.0)

        val nextDifficulty = (difficulty + if (correct) -0.12 else 0.45).coerceIn(1.0, 10.0)
        val difficultyRelief = (10.0 - difficulty) / 9.0
        val nextStability = if (correct) {
            (stabilityTicks * (1.0 + 0.35 * difficultyRelief) + 0.1).coerceIn(MIN_STABILITY, MAX_STABILITY)
        } else {
            (stabilityTicks * 0.65).coerceIn(MIN_STABILITY, MAX_STABILITY)
        }

        return copy(
            knowledgeProbability = learned,
            difficulty = nextDifficulty,
            stabilityTicks = nextStability,
            theta = nextTheta,
            reviews = if (reviews == Int.MAX_VALUE) reviews else reviews + 1,
            lastReviewTick = tick
        )
    }

    companion object {
        const val INITIAL_KNOWLEDGE = 0.20
        const val BKT_TRANSITION = 0.08
        const val BKT_GUESS = 0.20
        const val BKT_SLIP = 0.10
        const val IRT_GUESSING = 0.20
        const val IRT_LEARNING_RATE = 0.15
        const val DEFAULT_TARGET_RETRIEVABILITY = 0.90
        const val DSR_FACTOR = 19.0 / 81.0
        const val MIN_STABILITY = 0.25
        const val MAX_STABILITY = 1.0e9
        private const val EPSILON = 1.0e-9
    }
}
