package com.enhikyy.girassol.core

import kotlin.math.ceil
import kotlin.math.sqrt

/** Deterministic outward square-spiral parcel allocator for modular city growth. */
object ProceduralDistricts {
    fun nextAnnex(rooms: List<Room>): Room {
        val index = rooms.count { it.id.startsWith("annex_") }
        val (gx, gz) = spiral(index)
        val x = 14.0 + gx * 2.0
        val z = 9.0 + gz * 2.0
        val (code,title) = when(index % 6) {
            0 -> "residential" to "Moradia modular"
            1 -> "agriculture" to "Estufa agrícola"
            2 -> "recycling" to "Reciclagem e reúso"
            3 -> "energy" to "Oficina de energia"
            4 -> "research" to "Biblioteca de pesquisa"
            else -> "civic" to "Ágora cívica"
        }
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
