package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable enum class ReviewGrade(val score: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }
@Serializable data class FsrsReview(val difficulty: Double,val stability: Double,val tick: Long,val due: Long,
    val repetitions: Int,val lapses: Int,val grade: ReviewGrade,val modelVersion: Int=45) {
    fun valid(now: Long)=modelVersion==45 && difficulty.isFinite() && difficulty in 1.0..10.0 && stability.isFinite() && stability in .1..1e9 &&
        tick in 0..now && due>tick && repetitions>0 && lapses in 0..repetitions
}

/** FSRS 4.5, pinned ts-fsrs v3.5.7 parameters. Time unit is one deterministic simulation tick.
 * Uses Double throughout; no calendar fuzz or intermediate decimal rounding. Review interval >=1.
 * https://github.com/open-spaced-repetition/ts-fsrs/tree/0fdaa11f74fc236546cdcb8d48cb678b053b397b/src/fsrs
 */
object Fsrs45 {
    private val w=doubleArrayOf(.5701,1.4436,4.1386,10.9355,5.1443,1.2006,.8627,.0362,1.629,.1342,1.0166,2.1174,.0839,.3204,1.4676,.219,2.8237)
    const val FACTOR=19.0/81.0
    fun recall(elapsed: Double,stability: Double): Double {
        require(elapsed.isFinite() && elapsed>=0 && stability.isFinite() && stability>0)
        return (1+FACTOR*elapsed/stability).pow(-.5)
    }
    fun interval(stability: Double,retention: Double=.9): Double {
        require(stability.isFinite() && stability>0 && retention.isFinite() && retention in .01..1.0)
        return stability*(retention.pow(-2)-1)/FACTOR
    }
    fun review(previous: FsrsReview?,grade: ReviewGrade,tick: Long,retention: Double=.9): FsrsReview {
        require(tick in 0 until Long.MAX_VALUE && (previous==null || previous.valid(tick) && tick>previous.tick))
        val g=grade.score
        val difficulty: Double
        val stability: Double
        if(previous==null) {
            difficulty=(w[4]-(g-3)*w[5]).coerceIn(1.0,10.0)
            stability=w[g-1]
        } else {
            val d=previous.difficulty;val s=previous.stability
            val r=recall((tick-previous.tick).toDouble(),s)
            difficulty=(w[7]*w[4]+(1-w[7])*(d-w[6]*(g-3))).coerceIn(1.0,10.0)
            stability=if(grade==ReviewGrade.AGAIN)
                w[11]*d.pow(-w[12])*((s+1).pow(w[13])-1)*exp((1-r)*w[14])
            else s*(1+exp(w[8])*(11-d)*s.pow(-w[9])*expm1((1-r)*w[10])*
                (if(grade==ReviewGrade.HARD) w[15] else 1.0)*(if(grade==ReviewGrade.EASY) w[16] else 1.0))
        }
        val bounded=stability.coerceIn(.1,1e9)
        val delay=interval(bounded,retention).roundToLong().coerceIn(1,1_000_000_000)
        return FsrsReview(difficulty,bounded,tick,if(Long.MAX_VALUE-tick<delay) Long.MAX_VALUE else tick+delay,
            ((previous?.repetitions ?: 0).toLong()+1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ((previous?.lapses ?: 0).toLong()+if(grade==ReviewGrade.AGAIN) 1 else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),grade)
    }
}

@Serializable enum class IrtModel { TWO_PL, THREE_PL }
@Serializable data class IrtItem(val model: IrtModel,val discrimination: Double=1.4,val difficulty: Double=0.0,
    val guessing: Double=0.0,val observations: Int=0) {
    fun valid()=discrimination.isFinite() && discrimination in .25..4.0 && difficulty.isFinite() && difficulty in -3.0..3.0 &&
        guessing.isFinite() && guessing in 0.0.. .35 && (model!=IrtModel.TWO_PL || guessing==0.0) && observations>=0
    fun probability(ability: Double): Double {
        require(valid() && ability.isFinite() && ability in -4.0..4.0)
        return guessing+(1-guessing)/(1+exp(-discrimination*(ability-difficulty)))
    }
    /** Exact Bernoulli likelihood derivatives in order theta, a, b, c; no sampling. */
    fun derivatives(ability: Double): DoubleArray {
        val p=probability(ability);val s=(p-guessing)/(1-guessing)
        val slope=(1-guessing)*s*(1-s)
        return doubleArrayOf(slope*discrimination,slope*(ability-difficulty),-slope*discrimination,
            if(model==IrtModel.THREE_PL) 1-s else 0.0)
    }
    fun information(ability: Double): Double {
        val p=probability(ability);val d=derivatives(ability)[0]
        return d*d/(p*(1-p)).coerceAtLeast(1e-12)
    }
    /** Bounded online MAP calibration with fixed priors anchoring the otherwise unidentified scale. */
    fun observe(ability: Double,success: Boolean,prior: IrtItem): Pair<Double,IrtItem> {
        require(prior.valid() && prior.model==model)
        val p=probability(ability);val gradient=((if(success) 1.0 else 0.0)-p)/(p*(1-p)).coerceAtLeast(1e-12)
        val d=derivatives(ability);val rate=.08/sqrt(1+observations/32.0)
        val theta=(ability+rate*(gradient*d[0]-.02*ability)).coerceIn(-4.0,4.0)
        val a=(discrimination+rate*(gradient*d[1]-.05*(discrimination-prior.discrimination))).coerceIn(.25,4.0)
        val b=(difficulty+rate*(gradient*d[2]-.10*(difficulty-prior.difficulty))).coerceIn(-3.0,3.0)
        val c=if(model==IrtModel.TWO_PL) 0.0 else (guessing+rate*(gradient*d[3]-.5*(guessing-prior.guessing))).coerceIn(0.0,.35)
        return theta to copy(discrimination=a,difficulty=b,guessing=c,observations=if(observations==Int.MAX_VALUE) observations else observations+1)
    }
    companion object {
        fun prior(action: Activity): IrtItem {
            val difficulty=when(action) { Activity.BUILD -> 1.0;Activity.WRITE -> .6;Activity.STUDY -> .3;Activity.TALK -> .1;else -> -.5 }
            val model=if(action in setOf(Activity.STUDY,Activity.TALK)) IrtModel.THREE_PL else IrtModel.TWO_PL
            return IrtItem(model,difficulty=difficulty,guessing=if(model==IrtModel.THREE_PL) .15 else 0.0)
        }
    }
}
