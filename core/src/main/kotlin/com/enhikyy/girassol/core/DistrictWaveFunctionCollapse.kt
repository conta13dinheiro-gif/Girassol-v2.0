package com.enhikyy.girassol.core

import kotlin.math.ln

/**
 * Deterministic finite-grid Wave Function Collapse for city district adjacency.
 * The tile vocabulary describes real room archetypes already supported by the
 * native city renderer. Every cell is collapsed under four-neighbour constraints.
 */
internal object DistrictWaveFunctionCollapse {
    enum class Kind { RESIDENTIAL, AGRICULTURE, RECYCLING, ENERGY, RESEARCH, CIVIC }

    private const val WIDTH = 17
    private const val HEIGHT = 17
    private const val ALL = (1 shl 6) - 1
    private val weights = doubleArrayOf(1.25, 1.05, .90, .80, .95, 1.30)
    private val compatible = intArrayOf(
        bit(Kind.AGRICULTURE) or bit(Kind.RECYCLING) or bit(Kind.RESEARCH) or bit(Kind.CIVIC),
        bit(Kind.RESIDENTIAL) or bit(Kind.RECYCLING) or bit(Kind.CIVIC),
        bit(Kind.RESIDENTIAL) or bit(Kind.AGRICULTURE) or bit(Kind.ENERGY) or bit(Kind.CIVIC),
        bit(Kind.RECYCLING) or bit(Kind.RESEARCH) or bit(Kind.CIVIC),
        bit(Kind.RESIDENTIAL) or bit(Kind.ENERGY) or bit(Kind.CIVIC),
        ALL
    )

    private data class Cell(val x: Int, val z: Int)

    fun generate(seed: Long, chunkX: Int, chunkZ: Int): List<Kind> {
        val rng = Canonical.stream(seed, "district:$chunkX:$chunkZ", 0, "wfc-district-v1")
        val domains = IntArray(WIDTH * HEIGHT) { ALL }
        val anchors = listOf(
            Cell(0, 0) to Kind.RESIDENTIAL,
            Cell(WIDTH - 1, 0) to Kind.AGRICULTURE,
            Cell(0, HEIGHT - 1) to Kind.RECYCLING,
            Cell(WIDTH - 1, HEIGHT - 1) to Kind.ENERGY,
            Cell(WIDTH / 2, HEIGHT / 2) to Kind.RESEARCH,
            Cell(WIDTH / 2, 0) to Kind.CIVIC
        )
        val queue = ArrayDeque<Int>()
        domains.indices.forEach(queue::addLast)
        anchors.forEach { (cell, kind) -> domains[index(cell.x, cell.z)] = bit(kind) }
        check(propagate(domains, queue)) { "WFC: conjunto inicial de distritos inconsistente" }
        val solved = collapse(domains, rng) ?: error("WFC: não foi possível colapsar o mapa de distritos")
        return solved.map { mask -> Kind.entries.single { mask == bit(it) } }
    }

    private fun collapse(domains: IntArray, rng: DeterministicRng): IntArray? {
        var selected = -1
        var lowestEntropy = Double.POSITIVE_INFINITY
        val ties = ArrayList<Int>()
        for (cell in domains.indices) {
            val domain = domains[cell]
            val count = Integer.bitCount(domain)
            if (count <= 1) continue
            val entropy = entropy(domain)
            when {
                entropy < lowestEntropy - 1e-10 -> {
                    lowestEntropy = entropy
                    ties.clear()
                    ties += cell
                }
                kotlin.math.abs(entropy - lowestEntropy) <= 1e-10 -> ties += cell
            }
        }
        if (ties.isEmpty()) return domains
        selected = ties[rng.nextInt(ties.size)]
        for (kind in weightedOrder(domains[selected], rng)) {
            val branch = domains.copyOf()
            branch[selected] = bit(Kind.entries[kind])
            val queue = ArrayDeque<Int>()
            queue.addLast(selected)
            if (propagate(branch, queue)) {
                val result = collapse(branch, rng)
                if (result != null) return result
            }
        }
        return null
    }

    private fun propagate(domains: IntArray, queue: ArrayDeque<Int>): Boolean {
        while (queue.isNotEmpty()) {
            val source = queue.removeFirst()
            val sourceDomain = domains[source]
            if (sourceDomain == 0) return false
            val supported = supportMask(sourceDomain)
            val x = source % WIDTH
            val z = source / WIDTH
            val neighbours = intArrayOf(
                if (x > 0) source - 1 else -1,
                if (x + 1 < WIDTH) source + 1 else -1,
                if (z > 0) source - WIDTH else -1,
                if (z + 1 < HEIGHT) source + WIDTH else -1
            )
            for (neighbour in neighbours) {
                if (neighbour < 0) continue
                val old = domains[neighbour]
                val reduced = old and supported
                if (reduced == 0) return false
                if (reduced != old) {
                    domains[neighbour] = reduced
                    queue.addLast(neighbour)
                }
            }
        }
        return true
    }

    private fun supportMask(domain: Int): Int {
        var result = 0
        for (kind in Kind.entries) if (domain and bit(kind) != 0) result = result or compatible[kind.ordinal]
        return result
    }

    private fun entropy(domain: Int): Double {
        var total = 0.0
        for (kind in Kind.entries) if (domain and bit(kind) != 0) total += weights[kind.ordinal]
        var value = ln(total)
        for (kind in Kind.entries) if (domain and bit(kind) != 0) {
            val probability = weights[kind.ordinal] / total
            value -= probability * ln(probability)
        }
        return value
    }

    private fun weightedOrder(domain: Int, rng: DeterministicRng): List<Int> {
        val remaining = Kind.entries.filter { domain and bit(it) != 0 }.map { it.ordinal }.toMutableList()
        val ordered = ArrayList<Int>(remaining.size)
        while (remaining.isNotEmpty()) {
            val total = remaining.sumOf { weights[it] }
            var draw = rng.nextDouble() * total
            var chosen = remaining.last()
            for (candidate in remaining) {
                draw -= weights[candidate]
                if (draw < 0) {
                    chosen = candidate
                    break
                }
            }
            ordered += chosen
            remaining.remove(chosen)
        }
        return ordered
    }

    private fun index(x: Int, z: Int) = z * WIDTH + x
    private fun bit(kind: Kind) = 1 shl kind.ordinal
}
