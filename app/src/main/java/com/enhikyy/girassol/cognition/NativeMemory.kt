package com.enhikyy.girassol.cognition

import com.enhikyy.girassol.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

internal object NativeMemory {
    init { com.enhikyy.girassol.cognition.GeaNativeBridge.requireAvailable() }
    private external fun rank(input: ByteBuffer,count: Int,limit: Int,output: ByteBuffer): Int
    private class Buffers {
        val input=ByteBuffer.allocateDirect(64+2048*72).order(ByteOrder.nativeOrder())
        val output=ByteBuffer.allocateDirect(2048*8).order(ByteOrder.nativeOrder())
    }
    private val buffers=ThreadLocal.withInitial { Buffers() }
    /** score is a non-negative reward weight; similarity preserves the native contextual ranking signal. */
    data class Hit(val episode: Episode,val score: Float,val similarity: Float = score)
    fun recall(bank: MemoryBank,citizen: Citizen,tick: Long,limit: Int=8): List<Hit> {
        require(limit>=0 && bank.episodes.size<=2048)
        if(bank.episodes.isEmpty() || limit==0) return emptyList()
        val count=bank.episodes.size; val size=minOf(limit,count)
        val storage=checkNotNull(buffers.get());val input=storage.input.apply { clear() }
        val query=MemoryBank.embedding(citizen)
        query.forEach(input::putFloat)
        bank.episodes.forEach { e-> e.embedding.forEach(input::putFloat); input.putFloat((tick-e.tick).coerceAtLeast(0).toFloat()); input.putFloat(e.importance.toFloat()) }
        val output=storage.output.apply { clear() }
        // Exact-sized views preserve the existing JNI capacity contract while sharing fixed storage.
        input.flip()
        val inputView=input.slice().order(ByteOrder.nativeOrder())
        output.limit(size*8)
        val outputView=output.slice().order(ByteOrder.nativeOrder())
        val found=rank(inputView,count,size,outputView); check(found in 0..size) { "Falha na recuperação de memórias" }
        // Keep the existing native cosine/importance/recency signal for MemRL ranking.
        // Associated candidates use the same formula and join before the final bounded selection.
        val active=bank.workingMemory.decay(tick,bank.episodes.map { it.eventId }.toSet()).slots.map { it.eventId }.toSet()
        val association=bank.associations.filter { it.targetEvent in active }.groupBy { it.sourceEvent }
            .mapValues { (_,links) -> links.maxOf { it.similarity } }
        val nearest=List(found) { i ->
            val episode=bank.episodes[output.getInt(i*8)]
            val similarity=output.getFloat(i*8+4).coerceIn(-1f,1f)
            Hit(episode,MemoryBank.cosineWeight(similarity.toDouble()).toFloat(),similarity)
        }
        val known=nearest.map { it.episode.eventId }.toSet()
        val associated=bank.episodes.filter { it.eventId in association && it.eventId !in known }.map { episode ->
            val cosine=query.zip(episode.embedding).sumOf { (a,b) -> a.toDouble()*b }.coerceIn(0.0,1.0)
            val similarity=.45*cosine+.3*episode.importance+.25*exp(-(tick-episode.tick).coerceAtLeast(0).toDouble()/720)
            Hit(episode,MemoryBank.cosineWeight(similarity).toFloat(),similarity.toFloat())
        }
        return (nearest+associated).sortedWith(
            compareByDescending<Hit> { hit -> bank.utilityAdjustedScore(hit.episode.eventId, hit.similarity.toDouble())+.15*(association[hit.episode.eventId] ?: 0.0) }
                .thenByDescending { it.episode.importance }
                .thenBy { it.episode.eventId }
        ).take(size)
    }
}
