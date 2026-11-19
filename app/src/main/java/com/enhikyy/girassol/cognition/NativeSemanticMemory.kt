package com.enhikyy.girassol.cognition

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeSemanticMemory {
    init { System.loadLibrary("girassol_projection") }
    private external fun rank(input: ByteBuffer,dimensions: Int,count: Int,limit: Int,output: ByteBuffer): Int
    fun search(query: List<Float>,rows: List<Pair<String,List<Float>>>,limit: Int=8): List<Pair<String,Float>> {
        if(rows.isEmpty()) return emptyList()
        require(query.isNotEmpty() && rows.all { it.second.size==query.size })
        val size=minOf(limit,rows.size)
        val input=ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(rows.size+1,query.size),4)).order(ByteOrder.nativeOrder())
        query.forEach(input::putFloat);rows.forEach { row->row.second.forEach(input::putFloat) }
        val output=ByteBuffer.allocateDirect(size*8).order(ByteOrder.nativeOrder())
        val found=rank(input,query.size,rows.size,size,output);check(found in 0..size)
        return List(found) { rows[output.getInt(it*8)].first to output.getFloat(it*8+4) }
    }
}
