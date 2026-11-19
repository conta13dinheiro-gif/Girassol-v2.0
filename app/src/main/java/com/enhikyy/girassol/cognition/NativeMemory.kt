package com.enhikyy.girassol.cognition

import com.enhikyy.girassol.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeMemory {
    init { System.loadLibrary("girassol_projection") }
    private external fun rank(input: ByteBuffer,count: Int,limit: Int,output: ByteBuffer): Int
    data class Hit(val episode: Episode,val score: Float)
    fun recall(bank: MemoryBank,citizen: Citizen,tick: Long,limit: Int=8): List<Hit> {
        require(limit>=0)
        if(bank.episodes.isEmpty() || limit==0) return emptyList()
        val count=bank.episodes.size; val size=minOf(limit,count)
        val input=ByteBuffer.allocateDirect(Math.addExact(64,Math.multiplyExact(count,72))).order(ByteOrder.nativeOrder())
        MemoryBank.embedding(citizen).forEach(input::putFloat)
        bank.episodes.forEach { e-> e.embedding.forEach(input::putFloat); input.putFloat((tick-e.tick).coerceAtLeast(0).toFloat()); input.putFloat(e.importance.toFloat()) }
        val output=ByteBuffer.allocateDirect(size*8).order(ByteOrder.nativeOrder())
        val found=rank(input,count,size,output); check(found in 0..size) { "Falha na recuperação de memórias" }
        return List(found) { i-> Hit(bank.episodes[output.getInt(i*8)],output.getFloat(i*8+4)) }
    }
}
