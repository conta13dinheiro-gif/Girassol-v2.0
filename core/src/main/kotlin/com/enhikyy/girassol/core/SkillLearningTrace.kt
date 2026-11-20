package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.math.exp
import kotlin.math.pow

/**
 * Per-activity learning state.
 *
 * BKT estimates latent knowledge. The DSR curve schedules practice in simulation ticks.
 * Historical records use a compact fixed-item 3PL and a DSR stability heuristic.
 * Explicit fsrs/item fields select the pinned FSRS4.5 scheduler and bounded online 2/3PL
 * calibration. Neither path claims empirical psychometric calibration or FSRS6/7.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SkillLearningTrace(
    val action: Activity,
    val knowledgeProbability: Double = INITIAL_KNOWLEDGE,
    val difficulty: Double = 5.0,
    val stabilityTicks: Double = 1.0,
    val theta: Double = 0.0,
    val reviews: Int = 0,
    val lastReviewTick: Long = -1,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fsrs: FsrsReview? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val item: IrtItem? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val verifiedExecutions: Int = 0
) {
    fun valid(currentTick: Long): Boolean =
        currentTick >= 0 &&
            knowledgeProbability.isFinite() && knowledgeProbability in 0.0..1.0 &&
            difficulty.isFinite() && difficulty in 1.0..10.0 &&
            stabilityTicks.isFinite() && stabilityTicks in (if(fsrs==null) MIN_STABILITY else .1)..MAX_STABILITY &&
            theta.isFinite() && theta in -4.0..4.0 &&
            reviews >= 0 && verifiedExecutions in 0..reviews &&
            (item==null)==(fsrs==null) && (item==null || item.valid() && item.observations<=reviews) &&
            (fsrs==null || fsrs.valid(currentTick) && fsrs.difficulty==difficulty && fsrs.stability==stabilityTicks && fsrs.tick==lastReviewTick && fsrs.repetitions==reviews) &&
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
        item?.let { return it.probability(theta) }
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
        return if(item==null) (0.045 * (1.0 - knowledgeProbability) + 0.025 * due).coerceIn(0.0, 0.07)
        else (.035*(1-knowledgeProbability)+.035*due+.01*item.information(theta)).coerceIn(0.0,.09)
    }

    /** Apply one validated success/failure observation and update BKT, DSR and IRT state. */
    fun observe(correct: Boolean, tick: Long): SkillLearningTrace {
        if(fsrs!=null) return observeFsrs(if(correct) ReviewGrade.GOOD else ReviewGrade.AGAIN,tick,.5,false)
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

    /** New version: full FSRS4.5 D/S scheduler, genetic BKT and regularized IRT calibration.
     * Verified executions count only completed real physical jobs, independently of review/teaching.
     */
    fun observeFsrs(grade: ReviewGrade,tick: Long,aptitude: Double,verified: Boolean): SkillLearningTrace {
        require(valid(tick) && tick>lastReviewTick && aptitude.isFinite() && aptitude in 0.0..1.0)
        val success=grade!=ReviewGrade.AGAIN
        require(!verified || success)
        val prior=knowledgeProbability.coerceIn(EPSILON,1-EPSILON)
        val known=if(success) .92 else .08;val unknown=if(success) .15 else .85
        val posterior=prior*known/(prior*known+(1-prior)*unknown)
        val transition=.10+.18*aptitude
        val knowledge=(posterior+(1-posterior)*transition).coerceIn(0.0,1.0)
        val migrated=fsrs ?: if(reviews==0) null else FsrsReview(difficulty,stabilityTicks,lastReviewTick,
            if(lastReviewTick==Long.MAX_VALUE) Long.MAX_VALUE else lastReviewTick+1,reviews,0,ReviewGrade.GOOD)
        val scheduled=Fsrs45.review(migrated,grade,tick)
        val priorItem=IrtItem.prior(action)
        val (ability,calibrated)=(item ?: priorItem).observe(theta,success,priorItem)
        return copy(knowledgeProbability=knowledge,difficulty=scheduled.difficulty,stabilityTicks=scheduled.stability,
            theta=ability,reviews=scheduled.repetitions,lastReviewTick=tick,fsrs=scheduled,item=calibrated,
            verifiedExecutions=(verifiedExecutions.toLong()+if(verified) 1 else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
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
