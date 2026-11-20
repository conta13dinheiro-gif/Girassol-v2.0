package com.enhikyy.girassol.core

import java.util.Random
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.tanh

/** Three bounded behavior descriptors used to place a candidate in a MAP-Elites niche. */
@Serializable
data class AuroraDescriptor(
    val first: Double,
    val second: Double,
    val third: Double
) {
    fun valid(): Boolean = listOf(first, second, third).all { it.isFinite() && it in 0.0..1.0 }
    fun values(): List<Double> = listOf(first, second, third)
}

/** A candidate is admitted only when it is tied to a committed event and has valid replay metrics. */
@Serializable
data class AuroraCandidate(
    val id: String,
    val eventId: Long,
    val sourceEventHash: String,
    val descriptor: AuroraDescriptor,
    val genome: List<Double>,
    val metrics: ReplayMetrics,
    val generation: Long
) {
    fun valid(genomeDimensions: Int): Boolean =
        id.matches(Regex("[A-Za-z0-9._:-]{1,128}")) &&
            eventId > 0L &&
            sourceEventHash.matches(Regex("[a-f0-9]{64}")) &&
            descriptor.valid() &&
            genome.size == genomeDimensions &&
            genome.all { it.isFinite() } &&
            metrics.valid() &&
            generation >= 0L
}

@Serializable
data class AuroraNicheCell(val first: Int, val second: Int, val third: Int)

@Serializable
data class AuroraMapElitesState(
    val binsPerAxis: Int = 8,
    val genomeDimensions: Int = 8,
    val elites: List<AuroraCandidate> = emptyList()
) {
    val capacity: Int
        get() = binsPerAxis * binsPerAxis * binsPerAxis

    val coverage: Double
        get() = if (capacity <= 0) 0.0 else elites.size.toDouble() / capacity

    val meanQuality: Double
        get() = if (elites.isEmpty()) 0.0 else elites.map { it.metrics.score }.average()

    fun valid(): Boolean {
        if (binsPerAxis !in 2..32 || genomeDimensions !in 1..4096) return false
        if (elites.size > capacity || elites.any { !it.valid(genomeDimensions) }) return false
        if (elites.map { it.id }.distinct().size != elites.size) return false
        val cells = elites.map { AuroraMapElites.cellOf(it.descriptor, binsPerAxis) }
        return cells.distinct().size == cells.size
    }

    fun snapshot(): AuroraMapElitesState = copy(
        elites = elites.map { it.copy(genome = it.genome.toList()) }
    )
}

@Serializable
enum class AuroraInsertDisposition {
    INSERTED,
    REPLACED,
    RETAINED_EXISTING,
    DUPLICATE
}

@Serializable
data class AuroraArchiveUpdate(
    val state: AuroraMapElitesState,
    val disposition: AuroraInsertDisposition,
    val cell: AuroraNicheCell,
    val displacedCandidateId: String? = null
)

/**
 * Deterministic 3D MAP-Elites archive.
 *
 * It stores one best-scoring candidate per descriptor niche. A tie is resolved by
 * candidate ID, so replaying the same input produces the same archive independent
 * of insertion order. This class is an archive primitive, not a complete optimizer.
 */
object AuroraMapElites {
    fun cellOf(descriptor: AuroraDescriptor, binsPerAxis: Int): AuroraNicheCell {
        require(descriptor.valid())
        require(binsPerAxis in 2..32)
        fun bin(value: Double): Int = floor(value * binsPerAxis).toInt().coerceIn(0, binsPerAxis - 1)
        return AuroraNicheCell(
            bin(descriptor.first),
            bin(descriptor.second),
            bin(descriptor.third)
        )
    }

    fun insert(state: AuroraMapElitesState, candidate: AuroraCandidate): AuroraArchiveUpdate {
        require(state.valid()) { "MAP-Elites archive is invalid" }
        require(candidate.valid(state.genomeDimensions)) { "Candidate is invalid or has the wrong genome size" }

        val sameId = state.elites.firstOrNull { it.id == candidate.id }
        if (sameId != null) {
            require(sameId == candidate) { "A candidate ID cannot be reused for different evidence" }
            return AuroraArchiveUpdate(
                state,
                AuroraInsertDisposition.DUPLICATE,
                cellOf(candidate.descriptor, state.binsPerAxis)
            )
        }

        val cell = cellOf(candidate.descriptor, state.binsPerAxis)
        val incumbent = state.elites.firstOrNull {
            cellOf(it.descriptor, state.binsPerAxis) == cell
        }
        if (incumbent != null && !isBetter(candidate, incumbent)) {
            return AuroraArchiveUpdate(state, AuroraInsertDisposition.RETAINED_EXISTING, cell)
        }

        val nextElites = state.elites
            .filterNot { cellOf(it.descriptor, state.binsPerAxis) == cell }
            .plus(candidate)
            .sortedWith(
                compareBy<AuroraCandidate> { cellOf(it.descriptor, state.binsPerAxis).first }
                    .thenBy { cellOf(it.descriptor, state.binsPerAxis).second }
                    .thenBy { cellOf(it.descriptor, state.binsPerAxis).third }
            )
        val next = state.copy(elites = nextElites)
        check(next.valid()) { "MAP-Elites insertion broke archive invariants" }
        return AuroraArchiveUpdate(
            state = next,
            disposition = if (incumbent == null) AuroraInsertDisposition.INSERTED else AuroraInsertDisposition.REPLACED,
            cell = cell,
            displacedCandidateId = incumbent?.id
        )
    }

    private fun isBetter(candidate: AuroraCandidate, incumbent: AuroraCandidate): Boolean {
        val difference = candidate.metrics.score - incumbent.metrics.score
        return difference > 1e-12 ||
            (abs(difference) <= 1e-12 && candidate.id < incumbent.id)
    }
}

/** Online Oja projection state with three explicitly orthonormal components. */
@Serializable
data class AuroraOjaProjectorState(
    val inputDimensions: Int,
    val learningRate: Double,
    val observations: Long,
    val mean: List<Double>,
    val components: List<List<Double>>
) {
    fun valid(): Boolean {
        if (inputDimensions !in 3..4096) return false
        if (!learningRate.isFinite() || learningRate !in 1e-5..0.25) return false
        if (observations < 0L || mean.size != inputDimensions || components.size != 3) return false
        if (mean.any { !it.isFinite() } || components.any { axis ->
                axis.size != inputDimensions || axis.any { !it.isFinite() }
            }) return false
        for (i in components.indices) {
            val norm = dot(components[i], components[i])
            if (abs(norm - 1.0) > 1e-5) return false
            for (j in 0 until i) {
                if (abs(dot(components[i], components[j])) > 1e-5) return false
            }
        }
        return true
    }

    /** Projects using the current model. The tanh map bounds each descriptor to [0, 1]. */
    fun project(features: List<Double>): AuroraDescriptor {
        require(valid()) { "Oja projector state is invalid" }
        require(features.size == inputDimensions && features.all { it.isFinite() })
        val centered = features.indices.map { features[it] - mean[it] }
        fun value(axis: List<Double>) = 0.5 + 0.5 * tanh(dot(centered, axis))
        return AuroraDescriptor(value(components[0]), value(components[1]), value(components[2]))
    }

    /**
     * Applies one online Oja update. Modified components are Gram-Schmidt
     * orthogonalized after each update to prevent duplicate latent directions.
     */
    fun observe(features: List<Double>): AuroraOjaProjectorState {
        require(valid()) { "Oja projector state is invalid" }
        require(features.size == inputDimensions && features.all { it.isFinite() })
        require(observations < Long.MAX_VALUE) { "Oja observation counter exhausted" }

        val count = observations + 1L
        val nextMean = mean.indices.map { index ->
            mean[index] + (features[index] - mean[index]) / count.toDouble()
        }
        val centered = features.indices.map { features[it] - nextMean[it] }
        val rate = learningRate / sqrt(count.toDouble().coerceAtLeast(1.0))
        val nextComponents = mutableListOf<List<Double>>()
        var residual = centered.toMutableList()

        components.forEachIndexed { index, oldAxis ->
            val activation = dot(residual, oldAxis)
            val updated = oldAxis.indices.map { coordinate ->
                oldAxis[coordinate] + rate * activation *
                    (residual[coordinate] - activation * oldAxis[coordinate])
            }
            var orthogonal = orthogonalize(updated, nextComponents)
            if (orthogonal == null) orthogonal = orthogonalize(oldAxis, nextComponents)
            if (orthogonal == null) orthogonal = firstAvailableBasis(inputDimensions, nextComponents)
            checkNotNull(orthogonal) { "Unable to restore an orthonormal Oja basis" }
            nextComponents += orthogonal
            val removed = dot(residual, orthogonal)
            residual = residual.indices.map { coordinate ->
                residual[coordinate] - removed * orthogonal[coordinate]
            }.toMutableList()
        }

        return copy(observations = count, mean = nextMean, components = nextComponents).also {
            check(it.valid()) { "Oja update broke orthonormality" }
        }
    }

    companion object {
        fun create(
            inputDimensions: Int,
            learningRate: Double = 0.02,
            seed: Long = 1L
        ): AuroraOjaProjectorState {
            require(inputDimensions in 3..4096)
            require(learningRate.isFinite() && learningRate in 1e-5..0.25)
            val random = Random(seed)
            val components = mutableListOf<List<Double>>()
            repeat(3) {
                val candidate = List(inputDimensions) { random.nextDouble() * 2.0 - 1.0 }
                val orthogonal = orthogonalize(candidate, components)
                    ?: firstAvailableBasis(inputDimensions, components)
                    ?: error("Unable to initialize an orthonormal Oja basis")
                components += orthogonal
            }
            return AuroraOjaProjectorState(
                inputDimensions = inputDimensions,
                learningRate = learningRate,
                observations = 0L,
                mean = List(inputDimensions) { 0.0 },
                components = components
            ).also { check(it.valid()) }
        }

        private fun dot(first: List<Double>, second: List<Double>): Double =
            first.indices.sumOf { first[it] * second[it] }

        private fun orthogonalize(
            candidate: List<Double>,
            against: List<List<Double>>
        ): List<Double>? {
            val residual = candidate.toMutableList()
            against.forEach { axis ->
                val projection = dot(residual, axis)
                residual.indices.forEach { index ->
                    residual[index] -= projection * axis[index]
                }
            }
            val normSquared = dot(residual, residual)
            if (!normSquared.isFinite() || normSquared <= 1e-20) return null
            val norm = sqrt(normSquared)
            return residual.map { it / norm }
        }

        private fun firstAvailableBasis(
            dimensions: Int,
            against: List<List<Double>>
        ): List<Double>? {
            for (coordinate in 0 until dimensions) {
                val basis = MutableList(dimensions) { 0.0 }
                basis[coordinate] = 1.0
                val orthogonal = orthogonalize(basis, against)
                if (orthogonal != null) return orthogonal
            }
            return null
        }
    }
}
