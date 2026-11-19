package com.enhikyy.girassol.core

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.sqrt

/** Deterministic spiral parcels backed by WFC district constraints. */
object ProceduralDistricts {
    private const val CHUNK_SIZE = 17
    private data class ChunkKey(val seed: Long, val x: Int, val z: Int)
    private val plans = ConcurrentHashMap<ChunkKey, List<DistrictWaveFunctionCollapse.Kind>>()

    fun nextAnnex(rooms: List<Room>, seed: Long): Room {
        val index = rooms.count { it.id.startsWith("annex_") }
        val (gx, gz) = spiral(index)
        val chunkX = Math.floorDiv(gx, CHUNK_SIZE)
        val chunkZ = Math.floorDiv(gz, CHUNK_SIZE)
        val localX = Math.floorMod(gx, CHUNK_SIZE)
        val localZ = Math.floorMod(gz, CHUNK_SIZE)
        val layout = plans.computeIfAbsent(ChunkKey(seed, chunkX, chunkZ)) {
            DistrictWaveFunctionCollapse.generate(seed, chunkX, chunkZ)
        }
        val kind = layout[localZ * CHUNK_SIZE + localX]
        val (code, title) = when (kind) {
            DistrictWaveFunctionCollapse.Kind.RESIDENTIAL -> "residential" to "Moradia modular"
            DistrictWaveFunctionCollapse.Kind.AGRICULTURE -> "agriculture" to "Estufa agrícola"
            DistrictWaveFunctionCollapse.Kind.RECYCLING -> "recycling" to "Reciclagem e reúso"
            DistrictWaveFunctionCollapse.Kind.ENERGY -> "energy" to "Oficina de energia"
            DistrictWaveFunctionCollapse.Kind.RESEARCH -> "research" to "Biblioteca de pesquisa"
            DistrictWaveFunctionCollapse.Kind.CIVIC -> "civic" to "Ágora cívica"
        }
        val x = 14.0 + gx * 2.0
        val z = 9.0 + gz * 2.0
        return Room("annex_${index}_$code", "$title ${index + 1}", x, z, 1.7, 1.5, 2)
    }

    internal fun spiral(index: Int): Pair<Int, Int> {
        require(index >= 0)
        if (index == 0) return 0 to 0
        val ring = ceil((sqrt(index.toDouble() + 1.0) - 1.0) / 2.0).toInt()
        val maximum = (2 * ring + 1) * (2 * ring + 1) - 1
        val side = 2 * ring
        val offset = maximum - index
        return when {
            offset < side -> (ring - offset) to -ring
            offset < 2 * side -> -ring to (-ring + offset - side)
            offset < 3 * side -> (-ring + offset - 2 * side) to ring
            else -> ring to (ring - offset + 3 * side)
        }
    }
}
