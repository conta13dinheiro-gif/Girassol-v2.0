package com.enhikyy.girassol.core

/**
 * Fixed-grid stigmergic field.
 *
 * A no-flux discrete Laplacian diffuses mass between orthogonal neighbors.
 * The 12x12 world grid makes a full update a fixed bounded operation; each
 * cell update itself examines at most four neighbors. Evaporation is applied
 * after diffusion and deposits saturate rather than overflow the field.
 */
object StigmergicField {
    const val DEFAULT_WIDTH = 12
    const val DEFAULT_HEIGHT = 12
    const val DEFAULT_DIFFUSION_RATE = 0.08
    const val DEFAULT_EVAPORATION_RATE = 0.03

    fun diffuseAndEvaporate(
        values: List<Double>,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        diffusionRate: Double = DEFAULT_DIFFUSION_RATE,
        evaporationRate: Double = DEFAULT_EVAPORATION_RATE
    ): List<Double> {
        require(width > 0 && height > 0)
        require(values.size == width * height)
        require(values.all { it.isFinite() && it in 0.0..1.0 })
        require(diffusionRate.isFinite() && diffusionRate in 0.0..0.25)
        require(evaporationRate.isFinite() && evaporationRate in 0.0..1.0)

        val retain = 1.0 - evaporationRate
        return List(values.size) { index ->
            val x = index % width
            val y = index / width
            val center = values[index]
            var laplacian = 0.0

            if (x > 0) laplacian += values[index - 1] - center
            if (x + 1 < width) laplacian += values[index + 1] - center
            if (y > 0) laplacian += values[index - width] - center
            if (y + 1 < height) laplacian += values[index + width] - center

            (center + diffusionRate * laplacian).coerceIn(0.0, 1.0) * retain
        }
    }

    fun deposit(values: MutableList<Double>, cell: Int, amount: Double) {
        require(values.isNotEmpty() && cell in values.indices)
        require(amount.isFinite() && amount >= 0.0)
        require(values.all { it.isFinite() && it in 0.0..1.0 })
        values[cell] = (values[cell] + amount).coerceAtMost(1.0)
    }

    fun sample(values: List<Double>, cell: Int): Double {
        require(values.isNotEmpty() && cell in values.indices)
        return values[cell]
    }
}
