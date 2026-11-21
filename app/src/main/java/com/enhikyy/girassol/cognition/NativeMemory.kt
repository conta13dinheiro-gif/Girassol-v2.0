package com.enhikyy.girassol.cognition

import com.enhikyy.girassol.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeMemory {
    init { System.loadLibrary("girassol_projection") }
    private external fun rank(input: ByteBuffer,count: Int,limit: Int,output: ByteBuffer): Int
    /** score is a non-negative aggregation weight; similarity preserves the raw cosine value for ranking. */
    data class Hit(val episode: Episode,val score: Float,val similarity: Float = score)
    fun recall(bank: MemoryBank,citizen: Citizen,tick: Long,limit: Int=8): List<Hit> {
        require(limit>=0)
        if(bank.episodes.isEmpty() || limit==0) return emptyList()
        val count=bank.episodes.size; val size=minOf(limit,count)
        val input=ByteBuffer.allocateDirect(Math.addExact(64,Math.multiplyExact(count,72))).order(ByteOrder.nativeOrder())
        MemoryBank.embedding(citizen).forEach(input::putFloat)
        bank.episodes.forEach { e-> e.embedding.forEach(input::putFloat); input.putFloat((tick-e.tick).coerceAtLeast(0).toFloat()); input.putFloat(e.importance.toFloat()) }
        val output=ByteBuffer.allocateDirect(size*8).order(ByteOrder.nativeOrder())
        val found=rank(input,count,size,output); check(found in 0..size) { "Falha na recuperação de memórias" }
        // HNSW inner-product similarity spans [-1, 1]. Keep it for MemRL ranking,
        // but map it to [0, 1] before using it as a weighted reward average.
        return List(found) { i ->
            val episode=bank.episodes[output.getInt(i*8)]
            val similarity=output.getFloat(i*8+4).coerceIn(-1f,1f)
            Hit(episode,MemoryBank.cosineWeight(similarity.toDouble()).toFloat(),similarity)
        }.sortedWith(
            compareByDescending<Hit> { hit -> bank.utilityAdjustedScore(hit.episode.eventId, hit.similarity.toDouble()) }
                .thenByDescending { it.episode.importance }
                .thenBy { it.episode.eventId }
        )
    }
}
