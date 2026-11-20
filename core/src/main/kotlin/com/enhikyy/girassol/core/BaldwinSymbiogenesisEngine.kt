package com.enhikyy.girassol.core

import kotlin.math.abs

/**
 * Deterministic Baldwinian selection and a bounded symbiosis-locus merge.
 *
 * Learning never writes acquired phenotype values directly into the genome.
 * Instead, parental learning mastery biases transmission toward the parent's
 * better-performing inherited allele. Rare bounded mutations preserve variation.
 */
object BaldwinSymbiogenesisEngine {
    private const val MUTATION_RATE = 0.015
    private const val MUTATION_RADIUS = 0.06
    private const val MAX_ADAPTIVE_BIAS = 0.20
    private const val SYMBIOSIS_MASTERY_GATE = 0.65
    private const val COMPLEMENTARITY_GATE = 0.25
    private const val SYMBIOTIC_BLEND = 0.35

    private data class Allele(val value: Double, val dominance: Double)

    fun inheritGenome(
        first: Genome,
        second: Genome,
        firstLearningMastery: Double,
        secondLearningMastery: Double,
        generation: Int,
        random: DeterministicRng
    ): Genome {
        require(firstLearningMastery.isFinite() && firstLearningMastery in 0.0..1.0)
        require(secondLearningMastery.isFinite() && secondLearningMastery in 0.0..1.0)
        require(generation == maxOf(first.generation, second.generation) + 1)

        val curiosity = inheritLocus(
            first.curiosity, second.curiosity,
            firstLearningMastery, secondLearningMastery, random
        )
        val sociability = inheritLocus(
            first.sociability, second.sociability,
            firstLearningMastery, secondLearningMastery, random
        )
        val symbiosis = inheritSymbiosis(
            first.symbiosis, second.symbiosis,
            firstLearningMastery, secondLearningMastery, random
        )
        return Genome(
            curiosity = curiosity,
            sociability = sociability,
            generation = generation,
            symbiosis = symbiosis
        )
    }

    fun inheritLocus(
        first: Locus,
        second: Locus,
        firstLearningMastery: Double,
        secondLearningMastery: Double,
        random: DeterministicRng
    ): Locus {
        require(firstLearningMastery.isFinite() && firstLearningMastery in 0.0..1.0)
        require(secondLearningMastery.isFinite() && secondLearningMastery in 0.0..1.0)
        val one = transmit(first, firstLearningMastery, random)
        val two = transmit(second, secondLearningMastery, random)
        return Locus(one.value, two.value, one.dominance, two.dominance)
    }

    /**
     * Complementary, well-learned parental symbiosis phenotypes can produce a
     * blended inherited locus. The blend is still bounded and mutation remains rare.
     */
    private fun inheritSymbiosis(
        first: Locus,
        second: Locus,
        firstLearningMastery: Double,
        secondLearningMastery: Double,
        random: DeterministicRng
    ): Locus {
        val inherited = inheritLocus(
            first, second, firstLearningMastery, secondLearningMastery, random
        )
        val complementary = abs(first.express() - second.express()) >= COMPLEMENTARITY_GATE
        val bothAdapted = minOf(firstLearningMastery, secondLearningMastery) >= SYMBIOSIS_MASTERY_GATE
        if (!complementary || !bothAdapted) return inherited

        val bridge = (first.express() + second.express()) / 2.0
        fun merge(value: Double): Double =
            (value * (1.0 - SYMBIOTIC_BLEND) + bridge * SYMBIOTIC_BLEND)
                .coerceIn(0.0, 1.0)
        return Locus(
            maternal = mutate(merge(inherited.maternal), random),
            paternal = mutate(merge(inherited.paternal), random),
            dominanceA = inherited.dominanceA,
            dominanceB = inherited.dominanceB
        )
    }

    private fun transmit(locus: Locus, mastery: Double, random: DeterministicRng): Allele {
        val maternalPreferred = locus.maternal >= locus.paternal
        val adaptiveBias = MAX_ADAPTIVE_BIAS * mastery
        val selectPreferred = random.nextDouble() < (0.5 + adaptiveBias)
        val chooseMaternal = if (selectPreferred) maternalPreferred else !maternalPreferred
        val value = if (chooseMaternal) locus.maternal else locus.paternal
        val dominance = if (chooseMaternal) locus.dominanceA else locus.dominanceB
        return Allele(mutate(value, random), mutate(dominance, random))
    }

    private fun mutate(value: Double, random: DeterministicRng): Double {
        if (random.nextDouble() >= MUTATION_RATE) return value
        val delta = (random.nextDouble() * 2.0 - 1.0) * MUTATION_RADIUS
        return (value + delta).coerceIn(0.0, 1.0)
    }
}
