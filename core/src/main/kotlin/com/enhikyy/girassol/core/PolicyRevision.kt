package com.enhikyy.girassol.core
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class ReplayMetrics(
    val innovation: Double,
    val resilience: Double,
    val harmony: Double,
    val energyEfficiency: Double,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val brierScore: Double = 0.0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val semanticEntropy: Double = 0.0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val evidenceVersion: Int = 0
) {
    val score get() = .3*innovation+.3*resilience+.2*harmony+.2*energyEfficiency

    fun valid() = listOf(
        innovation, resilience, harmony, energyEfficiency, brierScore, semanticEntropy
    ).all { it.isFinite() && it in 0.0..1.0 } && evidenceVersion in 0..1

    /** Promotion requires fitness gain without meaningful calibration or diversity regression. */
    fun dominates(other: ReplayMetrics): Boolean {
        if (!valid() || !other.valid() || score <= other.score + MINIMUM_J_GAIN) return false
        if (innovation + 1e-9 < other.innovation ||
            resilience + 1e-9 < other.resilience ||
            harmony + 1e-9 < other.harmony ||
            energyEfficiency + 1e-9 < other.energyEfficiency
        ) return false

        // Old journal events had no calibration/entropy fields. They remain replayable,
        // but a new promotion must compare complete v1 evidence on both windows.
        if (evidenceVersion != other.evidenceVersion) return false
        if (evidenceVersion == 0) return true
        return semanticEntropy >= MINIMUM_SEMANTIC_ENTROPY &&
            brierScore <= other.brierScore + BRIER_REGRESSION_TOLERANCE &&
            semanticEntropy + SEMANTIC_ENTROPY_REGRESSION_TOLERANCE >= other.semanticEntropy
    }

    companion object {
        const val MINIMUM_J_GAIN = 0.03
        const val MINIMUM_SEMANTIC_ENTROPY = 0.35
        const val BRIER_REGRESSION_TOLERANCE = 0.005
        const val SEMANTIC_ENTROPY_REGRESSION_TOLERANCE = 0.015
    }
}

/** Evidence is a controlled deterministic experiment, not a guarantee about the real future. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class PolicyRevision(val seed: Long,val sourceTick: Long,val sourceStateHash: String,val sourceEventHash: String,
    val baseWeights: List<Double>,val weights: List<Double>,val trainingBaseline: ReplayMetrics,val trainingCandidate: ReplayMetrics,
    val validationBaseline: ReplayMetrics,val validationCandidate: ReplayMetrics,val horizon: Int,val evaluations: Int,
    val algorithm: String = "CMA-ES/positive-rank-mu-v1",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val rulesVersion: Int = 5) {
    fun valid(seed: Long,tick: Long): Boolean = this.seed==seed && rulesVersion in 5..WorldActor.CURRENT_RULES_VERSION && sourceTick in 0..tick &&
        sourceStateHash.matches(Regex("[0-9a-f]{64}")) && sourceEventHash.matches(Regex("[0-9a-f]{64}")) &&
        baseWeights.size==8 && weights.size==8 && (baseWeights+weights).all { it.isFinite() && it in -2.0..2.0 } &&
        weights!=baseWeights && horizon>=100 && evaluations>=10 && algorithm=="CMA-ES/positive-rank-mu-v1" &&
        trainingBaseline.valid() && trainingCandidate.dominates(trainingBaseline) &&
        validationBaseline.valid() && validationCandidate.dominates(validationBaseline)
    fun applicable(world: WorldState) = valid(world.seed,world.tick) && baseWeights==(world.evolution?.weights ?: List(8) { 0.0 })
    fun snapshot() = copy(baseWeights=baseWeights.toList(),weights=weights.toList())
}
