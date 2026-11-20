package com.enhikyy.girassol.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Subjective logic opinion: belief + disbelief + uncertainty = 1. */
data class SubjectiveOpinion(
    val belief: Double,
    val disbelief: Double,
    val uncertainty: Double,
    val baseRate: Double = 0.5
) {
    init {
        require(listOf(belief, disbelief, uncertainty, baseRate).all { it.isFinite() })
        require(belief in 0.0..1.0 && disbelief in 0.0..1.0 && uncertainty in 0.0..1.0)
        require(baseRate in 0.0..1.0)
        require(abs(belief + disbelief + uncertainty - 1.0) <= 1e-8)
    }

    fun projectedProbability(): Double = (belief + baseRate * uncertainty).coerceIn(0.0, 1.0)

    fun discountByTrust(trustWeight: Double): SubjectiveOpinion {
        require(trustWeight.isFinite() && trustWeight in 0.0..1.0)
        val b = trustWeight * belief
        val d = trustWeight * disbelief
        return SubjectiveOpinion(b, d, (1.0 - b - d).coerceIn(0.0, 1.0), baseRate)
    }

    companion object {
        fun fromBeta(alpha: Double, beta: Double, baseRate: Double = 0.5, priorWeight: Double = 2.0): SubjectiveOpinion {
            require(alpha.isFinite() && beta.isFinite() && alpha > 0.0 && beta > 0.0)
            require(baseRate.isFinite() && baseRate in 0.0..1.0)
            require(priorWeight.isFinite() && priorWeight > 0.0)
            val positive = (alpha - 1.0).coerceAtLeast(0.0)
            val negative = (beta - 1.0).coerceAtLeast(0.0)
            val denominator = positive + negative + priorWeight
            return SubjectiveOpinion(
                positive / denominator,
                negative / denominator,
                priorWeight / denominator,
                baseRate
            )
        }

        /** Cumulative fusion is only called by EpistemicImmunityEngine after source de-duplication. */
        internal fun cumulativeFuseIndependent(first: SubjectiveOpinion, second: SubjectiveOpinion): SubjectiveOpinion {
            val kappa = first.uncertainty + second.uncertainty - first.uncertainty * second.uncertainty
            val baseRate = (first.baseRate + second.baseRate) / 2.0
            // Two dogmatic reports provide no independent uncertainty to resolve conflict.
            // Preserve uncertainty instead of manufacturing certainty from contradictory claims.
            if (kappa <= 1e-12) {
                if (first == second) return first
                return SubjectiveOpinion(0.0, 0.0, 1.0, baseRate)
            }

            val belief = (first.belief * second.uncertainty + second.belief * first.uncertainty) / kappa
            val disbelief = (first.disbelief * second.uncertainty + second.disbelief * first.uncertainty) / kappa
            val uncertainty = (first.uncertainty * second.uncertainty) / kappa
            val norm = belief + disbelief + uncertainty
            require(norm.isFinite() && norm > 0.0)
            return SubjectiveOpinion(belief / norm, disbelief / norm, uncertainty / norm, baseRate)
        }

        /** Averaging fusion for correlated reports/echoes; repeated transmission is not independent evidence. */
        internal fun averagingFuseCorrelated(first: SubjectiveOpinion, second: SubjectiveOpinion): SubjectiveOpinion {
            val kappa = first.uncertainty + second.uncertainty -
                2.0 * first.uncertainty * second.uncertainty
            val baseRate = (first.baseRate + second.baseRate) / 2.0
            if (kappa <= 1e-12) {
                if (first.uncertainty >= 1.0 - 1e-12 && second.uncertainty >= 1.0 - 1e-12) {
                    return SubjectiveOpinion(0.0, 0.0, 1.0, baseRate)
                }
                val belief = (first.belief + second.belief) / 2.0
                val disbelief = (first.disbelief + second.disbelief) / 2.0
                return SubjectiveOpinion(belief, disbelief, (1.0 - belief - disbelief).coerceIn(0.0, 1.0), baseRate)
            }
            val belief = (first.belief * second.uncertainty + second.belief * first.uncertainty) / kappa
            val disbelief = (first.disbelief * second.uncertainty + second.disbelief * first.uncertainty) / kappa
            val uncertainty = (2.0 * first.uncertainty * second.uncertainty) / kappa
            val norm = belief + disbelief + uncertainty
            require(norm.isFinite() && norm > 0.0)
            return SubjectiveOpinion(belief / norm, disbelief / norm, uncertainty / norm, baseRate)
        }
    }
}

/** An evidence item tied to one committed event; text itself stays in the acquisition journal. */
data class EpistemicWitness(
    val claimHash: String,
    val sourceEventHash: String,
    val sourceAgentId: String,
    val opinion: SubjectiveOpinion
) {
    init {
        require(claimHash.matches(Regex("[a-f0-9]{64}")))
        require(sourceEventHash.matches(Regex("[a-f0-9]{64}")))
        require(sourceAgentId.isNotBlank())
    }
}

data class EpistemicFusionResult(
    val claimHash: String,
    val opinion: SubjectiveOpinion,
    val acceptedSourceEventHashes: List<String>,
    val ignoredDuplicateCount: Int
)

/**
 * Discounts testimony by the recipient's Beta-derived trust and never treats
 * multiple copies of the same source event as independent corroboration.
 */
object EpistemicImmunityEngine {
    fun discount(witness: EpistemicWitness, recipientTrust: Double): EpistemicWitness =
        witness.copy(opinion = witness.opinion.discountByTrust(recipientTrust))

    fun fuseIndependent(witnesses: List<EpistemicWitness>): EpistemicFusionResult? {
        if (witnesses.isEmpty()) return null
        val claimHashes = witnesses.map { it.claimHash }.distinct()
        require(claimHashes.size == 1) { "A fusion batch must refer to one claim" }

        val ordered = witnesses.sortedWith(
            compareBy<EpistemicWitness> { it.sourceEventHash }
                .thenBy { it.sourceAgentId }
                .thenBy { it.opinion.projectedProbability() }
        )
        val independent = ordered.distinctBy { it.sourceEventHash }
        var fused = independent.first().opinion
        independent.drop(1).forEach { witness ->
            fused = SubjectiveOpinion.cumulativeFuseIndependent(fused, witness.opinion)
        }
        return EpistemicFusionResult(
            claimHash = claimHashes.single(),
            opinion = fused,
            acceptedSourceEventHashes = independent.map { it.sourceEventHash },
            ignoredDuplicateCount = ordered.size - independent.size
        )
    }

    /** Fold correlated reports with averaging fusion so social echoes do not inflate certainty. */
    fun fuseCorrelated(witnesses: List<EpistemicWitness>): EpistemicFusionResult? {
        if (witnesses.isEmpty()) return null
        val claimHashes = witnesses.map { it.claimHash }.distinct()
        require(claimHashes.size == 1) { "A fusion batch must refer to one claim" }
        val ordered = witnesses.sortedWith(
            compareBy<EpistemicWitness> { it.sourceEventHash }
                .thenBy { it.sourceAgentId }
                .thenBy { it.opinion.projectedProbability() }
        )
        // Same agent repeating the same source event is a duplicate. Different agents
        // reviewing the same acquisition remain correlated and are combined by averaging.
        val unique = ordered.distinctBy { it.sourceEventHash to it.sourceAgentId }
        var fused = unique.first().opinion
        unique.drop(1).forEach { witness ->
            fused = SubjectiveOpinion.averagingFuseCorrelated(fused, witness.opinion)
        }
        return EpistemicFusionResult(
            claimHash = claimHashes.single(),
            opinion = fused,
            acceptedSourceEventHashes = unique.map { it.sourceEventHash }.distinct(),
            ignoredDuplicateCount = ordered.size - unique.size
        )
    }
}

data class MemoryConsolidationReport(
    val selectedReplayEventIds: List<Long>,
    val retainedEventIds: List<Long>,
    val prunedEventIds: List<Long>
)

data class MemoryConsolidationResult(
    val bank: MemoryBank,
    val report: MemoryConsolidationReport
)

/**
 * Bounded episodic consolidation over WorldActor-validated events.
 *
 * Replay priority uses prediction error |reward - Q(action)| and learned action
 * utility. Retention also includes importance and an ACT-R-style age decay.
 * Existing per-action reflections and validated procedures remain untouched.
 */
object HippocampalSleepConsolidator {
    const val MAX_NODES_PER_AGENT = 512
    const val DEFAULT_REPLAY_BATCH_SIZE = 12

    fun consolidate(
        bank: MemoryBank,
        currentTick: Long,
        maxNodes: Int = MAX_NODES_PER_AGENT,
        replayBatchSize: Int = DEFAULT_REPLAY_BATCH_SIZE,
        activationDecay: Double = 0.5
    ): MemoryConsolidationResult {
        require(bank.valid(currentTick)) { "Memory bank is not valid at the requested tick" }
        require(maxNodes >= Activity.entries.size)
        require(replayBatchSize > 0)
        require(activationDecay.isFinite() && activationDecay in 0.0..1.0)

        val expectedByAction = bank.reflections.associate { it.action to it.expectedReward }
        fun expected(episode: Episode) = expectedByAction[episode.action] ?: 0.0
        fun predictionError(episode: Episode) = episode.reward - expected(episode)
        fun replayPriority(episode: Episode) =
            0.65 * abs(predictionError(episode)) + 0.35 * abs(expected(episode))
        fun retentionScore(episode: Episode): Double {
            val age = (currentTick - episode.tick).coerceAtLeast(0L).toDouble()
            val activation = ln(episode.importance.coerceAtLeast(1e-6)) -
                activationDecay * ln(1.0 + age)
            return activation + 1.5 * abs(expected(episode)) + 0.65 * abs(predictionError(episode))
        }

        val selectedReplay = bank.episodes
            .sortedWith(
                compareByDescending<Episode> { replayPriority(it) }
                    .thenByDescending { it.importance }
                    .thenByDescending { it.tick }
                    .thenByDescending { it.eventId }
            )
            .take(replayBatchSize)
            .map { it.eventId }
            .distinct()

        if (bank.episodes.size <= maxNodes) {
            val ids = bank.episodes.map { it.eventId }.distinct().sorted()
            return MemoryConsolidationResult(
                bank,
                MemoryConsolidationReport(selectedReplay, ids, emptyList())
            )
        }

        // Keep at least the most recent episode for each action before filling by value.
        val mandatory = bank.episodes
            .groupBy { it.action }
            .values
            .map { group -> group.maxWithOrNull(compareBy<Episode> { it.tick }.thenBy { it.eventId })!! }
            .sortedBy { it.action.ordinal }
        val keepKeys = LinkedHashSet<String>()
        fun key(episode: Episode) = episode.tick.toString() + ":" + episode.eventId + ":" + episode.action.name
        mandatory.forEach { keepKeys += key(it) }

        val additional = bank.episodes.asSequence()
            .filter { key(it) !in keepKeys }
            .sortedWith(
                compareByDescending<Episode> { retentionScore(it) }
                    .thenByDescending { it.tick }
                    .thenByDescending { it.eventId }
                    .thenBy { it.action.ordinal }
            )
            .take((maxNodes - keepKeys.size).coerceAtLeast(0))
            .toList()
        additional.forEach { keepKeys += key(it) }

        val retained = bank.episodes.filter { key(it) in keepKeys }
            .sortedWith(compareBy<Episode> { it.tick }.thenBy { it.eventId }.thenBy { it.action.ordinal })
        val retainedIds = retained.map { it.eventId }.distinct().sorted()
        val prunedIds = bank.episodes.filter { key(it) !in keepKeys }
            .map { it.eventId }.distinct().sorted()
        val retainedEventIds = retained.map { it.eventId }.toSet()
        val next = bank.copy(
            episodes = retained,
            utilities = bank.utilities.filter { it.eventId in retainedEventIds },
            pendingRecallEventIds = bank.pendingRecallEventIds.filter { it in retainedEventIds },
            workingMemory = bank.workingMemory.retain(currentTick,retainedEventIds)
        )
        check(next.valid(currentTick)) { "Consolidation produced an invalid memory bank" }
        return MemoryConsolidationResult(
            next,
            MemoryConsolidationReport(selectedReplay, retainedIds, prunedIds)
        )
    }
}

enum class ThermalTier(val metabolicMultiplier: Double) {
    NONE_OR_LIGHT(1.0),
    MODERATE(1.85),
    SEVERE_OR_CRITICAL(4.50)
}

data class AllostaticBudget(
    val tier: ThermalTier,
    val neuralCostMultiplier: Double,
    val localLlmAllowed: Boolean,
    val reflexFirst: Boolean
)

/** Pure deterministic thermal/RAM-to-deliberation policy; Android supplies only telemetry. */
object AllostaticThermalCoupling {
    fun fromPlatformThermalStatus(status: Int): ThermalTier = when {
        status <= 1 -> ThermalTier.NONE_OR_LIGHT
        status == 2 -> ThermalTier.MODERATE
        else -> ThermalTier.SEVERE_OR_CRITICAL
    }

    fun computeNeuralDeliberationCost(
        tier: ThermalTier,
        thermalSensitivity: Double,
        agentEnergyRatio: Double
    ): Double {
        require(thermalSensitivity.isFinite() && thermalSensitivity in 0.5..3.0)
        require(agentEnergyRatio.isFinite() && agentEnergyRatio in 0.0..1.0)
        val scarcityFactor = if (agentEnergyRatio < 0.30) 2.0 else 1.0
        return 0.12 * tier.metabolicMultiplier * thermalSensitivity * scarcityFactor
    }

    fun assess(
        tier: ThermalTier,
        lowMemory: Boolean,
        agentEnergyRatio: Double,
        thermalSensitivity: Double = 1.0
    ): AllostaticBudget {
        val cost = computeNeuralDeliberationCost(tier, thermalSensitivity, agentEnergyRatio)
        val allowed = !lowMemory && tier == ThermalTier.NONE_OR_LIGHT
        return AllostaticBudget(
            tier = tier,
            neuralCostMultiplier = cost,
            localLlmAllowed = allowed,
            reflexFirst = !allowed || cost >= 0.40
        )
    }

    fun shouldInvokeLocalLlm(
        topActionUtility: Double,
        secondActionUtility: Double,
        tieThreshold: Double,
        minimumUrgency: Double,
        consecutiveHtnFailures: Int,
        socialNegotiationActive: Boolean,
        tier: ThermalTier,
        lowMemory: Boolean,
        tsetlinReflexConfidence: Double
    ): Boolean {
        require(listOf(topActionUtility, secondActionUtility, tieThreshold, minimumUrgency, tsetlinReflexConfidence).all { it.isFinite() })
        require(tieThreshold >= 0.0 && minimumUrgency >= 0.0)
        require(consecutiveHtnFailures >= 0 && tsetlinReflexConfidence in 0.0..1.0)
        if (lowMemory) return false
        if (tsetlinReflexConfidence >= 0.88 && consecutiveHtnFailures == 0 && !socialNegotiationActive) return false
        if (tier == ThermalTier.SEVERE_OR_CRITICAL && consecutiveHtnFailures < 3 && !socialNegotiationActive) return false
        val closeTie = topActionUtility >= minimumUrgency &&
            (topActionUtility - secondActionUtility) < tieThreshold
        return closeTie || consecutiveHtnFailures >= 2 || socialNegotiationActive
    }
}

/** Frequency-sensitive bonus used only by the current deterministic cognitive scorer. */
object SemanticCollapseGuard {
    fun normalizedPopulationEntropy(actionCounts: Map<String, Int>): Double {
        val counts = Activity.entries.map { (actionCounts[it.name] ?: 0).coerceAtLeast(0) }
        val total = counts.sum().toDouble()
        if (total <= 0.0) return 0.0
        val entropy = counts.filter { it > 0 }.sumOf { count ->
            val probability = count / total
            -probability * ln(probability)
        }
        return (entropy / ln(Activity.entries.size.toDouble())).coerceIn(0.0, 1.0)
    }

    fun noveltyEpistemicBonus(
        actionType: String,
        actionCounts: Map<String, Int>,
        collapseThreshold: Double = 0.55
    ): Double {
        require(collapseThreshold.isFinite() && collapseThreshold in 0.01..1.0)
        val action = Activity.entries.firstOrNull { it.name == actionType } ?: return 0.0
        val entropy = normalizedPopulationEntropy(actionCounts)
        if (entropy >= collapseThreshold) return 0.0
        val total = Activity.entries.sumOf { (actionCounts[it.name] ?: 0).coerceAtLeast(0) }
        if (total == 0) return 0.0
        val frequency = ((actionCounts[action.name] ?: 0).coerceAtLeast(0)).toDouble() / total
        val urgency = ((collapseThreshold - entropy) / collapseThreshold).coerceIn(0.0, 1.0)
        return 0.30 * urgency * (1.0 - frequency).coerceIn(0.0, 1.0)
    }
}
