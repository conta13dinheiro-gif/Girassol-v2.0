package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.ln

/** The only mutable RSI phenotype. Physical rules, JNI dimensions and population limits are absent. */
@Serializable data class CognitivePolicyGenome(
    val version: Int = 1,
    val epistemicExplorationWeight: Double = .65,
    val cognitiveGateTieThreshold: Double = .05,
    val cognitiveGateMinUrgency: Double = .60,
    val intentionSwitchHysteresis: Double = .12,
    val memRlLearningRate: Double = .20,
    val actRDecayExponent: Double = .50,
    val thermalThrottleSensitivity: Double = 1.0,
    val activeVpoRules: List<String> = emptyList()
) {
    fun parameters() = listOf(epistemicExplorationWeight,cognitiveGateTieThreshold,cognitiveGateMinUrgency,
        intentionSwitchHysteresis,memRlLearningRate,actRDecayExponent,thermalThrottleSensitivity)
    fun valid() = version in 1..1_000_000 && parameters().zip(BOUNDS).all { (v,b) -> v.isFinite() && v in b } &&
        activeVpoRules.size<=8 && activeVpoRules.distinct().size==activeVpoRules.size && activeVpoRules.all { VpoRule.parse(it)!=null }
    fun signature(): String { require(valid()); return Canonical.hash(Json.encodeToString(this)) }
    fun snapshot() = copy(activeVpoRules=activeVpoRules.toList())
    fun differsFrom(other: CognitivePolicyGenome) = copy(version=other.version)!=other
    /** CMA-ES stays eight dimensional; dimension eight selects one grammar production. */
    fun vector() = parameters().zip(BOUNDS).map { (v,b) -> 4*(v-b.start)/(b.endInclusive-b.start)-2 }+listOf(-2.0)
    fun mutate(vector: List<Double>): CognitivePolicyGenome {
        require(valid() && version<1_000_000 && vector.size==8 && vector.all { it.isFinite() && it in -2.0..2.0 })
        val p=vector.take(7).zip(BOUNDS).map { (v,b) -> b.start+(v+2)/4*(b.endInclusive-b.start) }
        val choice=((vector[7]+2)*VpoRule.PRODUCTIONS.size/4).toInt().coerceAtMost(VpoRule.PRODUCTIONS.lastIndex)
        val rule=VpoRule.PRODUCTIONS[choice]
        return CognitivePolicyGenome(version+1,p[0],p[1],p[2],p[3],p[4],p[5],p[6],
            if(rule.isEmpty()) activeVpoRules.toList() else (activeVpoRules+rule).distinct().takeLast(8)).also { require(it.valid()) }
    }
    companion object {
        private val BOUNDS=listOf(.10..1.50,.01.. .20,.40.. .90,.02.. .35,.05.. .50,.20.. .85,.50..3.0)
    }
}

/** Finite GBNF language: no code execution, arbitrary identifiers or invariant writes. */
data class VpoRule(val need: String,val threshold: Double,val action: Activity) {
    fun bonus(citizen: Citizen): Double {
        val value=when(need) { "hydration"->citizen.needs.hydration; "glucose"->citizen.needs.glucose; else->citizen.needs.energy }
        return if(value<threshold) .25 else 0.0
    }
    companion object {
        const val GBNF="""root ::= "IF " need " < " threshold " THEN " action
need ::= "hydration" | "glucose" | "energy"
threshold ::= "0.15" | "0.20" | "0.30"
action ::= "WATER" | "EAT" | "SLEEP""""
        private val language=FiniteGbnf.compile(GBNF)
        val PRODUCTIONS=listOf("")+language.sorted()
        private val syntax=Regex("IF (hydration|glucose|energy) < (0\\.15|0\\.20|0\\.30) THEN (WATER|EAT|SLEEP)")
        fun parse(text: String): VpoRule? {
            if(text.length>140 || text !in language) return null
            val match=syntax.matchEntire(text) ?: return null
            val (need,threshold,action)=match.destructured
            return VpoRule(need,threshold.toDouble(),Activity.valueOf(action))
        }
    }
}

@Serializable data class RsiMetrics(val meanFreeEnergy: Double,val survivalEfficiency: Double,val brierScore: Double,
    val semanticEntropy: Double,val llmCallsPer100Ticks: Double,val invariantViolations: Int,val observations: Int) {
    val objective get() = survivalEfficiency-.35*meanFreeEnergy-.004*llmCallsPer100Ticks
    fun valid() = meanFreeEnergy.isFinite() && meanFreeEnergy in 0.0..100.0 &&
        listOf(survivalEfficiency,brierScore,semanticEntropy).all { it.isFinite() && it in 0.0..1.0 } &&
        llmCallsPer100Ticks.isFinite() && llmCallsPer100Ticks in 0.0..15000.0 && invariantViolations>=0 && observations>0
}
@Serializable data class RsiWindow(val legacy: ReplayMetrics,val safety: RsiMetrics) {
    fun valid() = legacy.valid() && safety.valid()
}
@Serializable data class VerifiedGenomeChange(val baseline: CognitivePolicyGenome,val candidate: CognitivePolicyGenome,
    val trainingBaseline: RsiWindow,val trainingCandidate: RsiWindow,val validationBaseline: RsiWindow,val validationCandidate: RsiWindow) {
    fun valid() = baseline.valid() && candidate.valid() && candidate.version==baseline.version+1 && candidate.differsFrom(baseline) &&
        BoundedRsiEngine.rejection(trainingBaseline,trainingCandidate)==null && BoundedRsiEngine.rejection(validationBaseline,validationCandidate)==null
    fun snapshot() = copy(baseline=baseline.snapshot(),candidate=candidate.snapshot())
}
/** PROPOSED is durably written before evaluation; terminal records are append-only. */
@Serializable data class RsiTrial(val id: String,val seed: Long,val sourceTick: Long,val sourceStateHash: String,val sourceEventHash: String,
    val rulesVersion: Int,val ordinal: Int,val baseline: CognitivePolicyGenome,val candidate: CognitivePolicyGenome,
    val stage: String = "PROPOSED",val reason: String = "PROPOSED",val trainingBaseline: RsiWindow? = null,
    val trainingCandidate: RsiWindow? = null,val validationBaseline: RsiWindow? = null,val validationCandidate: RsiWindow? = null) {
    fun valid() = id.matches(Regex("[0-9a-f]{64}")) && sourceTick>=0 &&
        listOf(sourceStateHash,sourceEventHash).all { it.matches(Regex("[0-9a-f]{64}")) } && rulesVersion==WorldActor.CURRENT_RULES_VERSION &&
        ordinal in 0..19 && baseline.valid() && candidate.valid() && candidate.version==baseline.version+1 && candidate.differsFrom(baseline) &&
        stage in setOf("PROPOSED","TRAINED","REJECTED","VERIFIED","CANCELLED","ERROR") &&
        reason in setOf("PROPOSED","TRAINED","INVARIANT","H_SEM","BRIER","DELTA_J","LEGACY_PARETO","INVALID_EVIDENCE","NOT_SELECTED","VERIFIED","CANCELLED","EVALUATION_ERROR","RECOVERED_INTERRUPTION") &&
        listOfNotNull(trainingBaseline,trainingCandidate,validationBaseline,validationCandidate).all { it.valid() } &&
        id==Canonical.hash("$sourceEventHash:$ordinal:${candidate.signature()}") &&
        (stage!="PROPOSED" || (reason=="PROPOSED" && trainingBaseline==null && trainingCandidate==null && validationBaseline==null && validationCandidate==null)) &&
        (stage!="CANCELLED" || reason in setOf("CANCELLED","RECOVERED_INTERRUPTION")) &&
        (stage!="ERROR" || reason=="EVALUATION_ERROR") &&
        (stage!="REJECTED" || (trainingBaseline!=null && trainingCandidate!=null &&
            (if(reason=="NOT_SELECTED") BoundedRsiEngine.rejection(trainingBaseline,trainingCandidate)==null
             else reason==BoundedRsiEngine.rejection(trainingBaseline,trainingCandidate) ||
                 (validationBaseline!=null && validationCandidate!=null && reason==BoundedRsiEngine.rejection(validationBaseline,validationCandidate))))) &&
        (stage!="TRAINED" || (reason=="TRAINED" && trainingBaseline!=null && trainingCandidate!=null && BoundedRsiEngine.rejection(trainingBaseline,trainingCandidate)==null)) &&
        (stage!="VERIFIED" || (reason=="VERIFIED" && trainingBaseline!=null && trainingCandidate!=null && validationBaseline!=null && validationCandidate!=null &&
            BoundedRsiEngine.rejection(trainingBaseline,trainingCandidate)==null && BoundedRsiEngine.rejection(validationBaseline,validationCandidate)==null))
}
object BoundedRsiEngine {
    /** Additional gates; the pre-existing >.03 Pareto contract remains mandatory. */
    fun rejection(base: RsiWindow,candidate: RsiWindow): String? = when {
        !base.valid() || !candidate.valid() -> "INVALID_EVIDENCE"
        base.safety.invariantViolations!=0 || candidate.safety.invariantViolations!=0 -> "INVARIANT"
        candidate.safety.semanticEntropy<.35 -> "H_SEM"
        candidate.safety.brierScore>base.safety.brierScore+.010 -> "BRIER"
        candidate.safety.objective-base.safety.objective<.025 -> "DELTA_J"
        !candidate.legacy.dominates(base.legacy) -> "LEGACY_PARETO"
        else -> null
    }
    fun semanticEntropy(counts: LongArray): Double {
        require(counts.size==8 && counts.all { it>=0 })
        val total=counts.sumOf { it.toDouble() }
        return if(total==0.0) 0.0 else (-counts.filter { it>0 }.sumOf { val p=it/total; p*ln(p) }/ln(8.0)).coerceIn(0.0,1.0)
    }
}
