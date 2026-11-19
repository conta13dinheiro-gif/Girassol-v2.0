package com.enhikyy.girassol.render

import com.enhikyy.girassol.core.Room
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Visual data derived solely from published rooms, never a second simulation. */
internal object NativeCityMesh {
    const val STRIDE = 32
    data class Data(val vertices: ByteBuffer, val indices: ByteBuffer, val vertexCount: Int, val indexCount: Int, val opaqueCount: Int)
    private external fun nativeBuild(rooms: ByteBuffer?, count: Int, seed: Long, mode: Int, color: Int,
                                    vertices: ByteBuffer?, indices: ByteBuffer?): Long

    fun city(rooms: List<Room>, seed: Long, mode: Int = 0): Data {
        val input=roomBuffer(rooms)
        return build(input, rooms.size, seed, mode, 0)
    }

    fun roomBuffer(rooms: List<Room>): ByteBuffer {
        val input = direct(Math.multiplyExact(rooms.size, 24))
        rooms.forEach { r ->
            input.putFloat(r.x.toFloat()).putFloat(r.z.toFloat()).putFloat(r.width.toFloat()).putFloat(r.depth.toFloat())
            input.putInt(when { r.id == "salon" -> 0; r.id == "bedroom" -> 1; r.id == "cafe" -> 2; r.id == "market" -> 3; r.id == "library" -> 4; r.id == "workshop" -> 5; r.name.startsWith("Moradia modular") -> 0; r.name.startsWith("Estufa agrícola") -> 1; r.name.startsWith("Oficina de energia") -> 5; r.name.startsWith("Biblioteca de pesquisa") -> 4; r.name.startsWith("Ágora cívica") -> 3; else -> 6 })
            input.putInt(r.id.hashCode())
        }
        input.rewind()
        return input
    }

    fun citizen(argb: Long, role: Int = 0): Data {
        val rgba = ((argb shr 16) and 255) or (argb and 0xff00) or ((argb and 255) shl 16) or 0xff000000L
        return build(null, role.coerceIn(0,3), 0, 1, rgba.toInt())
    }

    private fun build(input: ByteBuffer?, count: Int, seed: Long, mode: Int, color: Int): Data {
        check(NativeProjection.available) { "Biblioteca nativa indisponível" }
        val sizes = nativeBuild(input, count, seed, mode, color, null, null)
        val vcLong = sizes ushr 32
        val icLong = sizes and 0xffffffffL
        check(vcLong in 1..(Int.MAX_VALUE / STRIDE).toLong() && icLong in 1..(Int.MAX_VALUE / 4).toLong()) { "Geometria nativa excede o espaço endereçável do processo" }
        val vc = vcLong.toInt()
        val ic = icLong.toInt()
        val vertices = direct(vc * STRIDE)
        val indices = direct(ic * 4)
        check(nativeBuild(input, count, seed, mode, color, vertices, indices) == sizes)
        // Keep both passes in a single zero-copy vertex/index allocation.
        val sorted=direct(ic*4)
        var opaque=0
        for (transparent in listOf(false,true)) {
            var i=0
            while(i<ic) {
                val v=indices.getInt(i*4)
                val glass=(vertices.getInt(v*STRIDE+28) ushr 24)==5
                if(glass==transparent) repeat(3) { sorted.putInt(indices.getInt((i+it)*4)) }
                i+=3
            }
            if(!transparent) opaque=sorted.position()/4
        }
        sorted.flip()
        return Data(vertices, sorted, vc, ic, opaque)
    }

    private fun direct(bytes: Int) = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
}
