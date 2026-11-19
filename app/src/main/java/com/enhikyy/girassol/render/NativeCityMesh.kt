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
        require(rooms.size <= 64)
        val input = direct(rooms.size * 24)
        rooms.forEach { r ->
            input.putFloat(r.x.toFloat()).putFloat(r.z.toFloat()).putFloat(r.width.toFloat()).putFloat(r.depth.toFloat())
            input.putInt(when (r.id) { "salon" -> 0; "bedroom" -> 1; "cafe" -> 2; "market" -> 3; "library" -> 4; "workshop" -> 5; else -> 6 })
            input.putInt(r.id.hashCode())
        }
        input.rewind()
        return build(input, rooms.size, seed, mode, 0)
    }

    fun citizen(argb: Long, role: Int = 0): Data {
        val rgba = ((argb shr 16) and 255) or (argb and 0xff00) or ((argb and 255) shl 16) or 0xff000000L
        return build(null, role.coerceIn(0,3), 0, 1, rgba.toInt())
    }

    private fun build(input: ByteBuffer?, count: Int, seed: Long, mode: Int, color: Int): Data {
        check(NativeProjection.available) { "Biblioteca nativa indisponível" }
        val sizes = nativeBuild(input, count, seed, mode, color, null, null)
        val vc = (sizes ushr 32).toInt()
        val ic = sizes.toInt()
        check(vc in 1..250_000 && ic in 1..500_000) { "Geometria nativa inválida" }
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
