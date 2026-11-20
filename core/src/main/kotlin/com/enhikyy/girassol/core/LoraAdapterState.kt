package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Complete immutable optimizer checkpoint. Null MindState.lora preserves historical encodings. */
@Serializable data class LoraAdapterState(val hex: String,val trainedTick: Long = -1,
    val sourceHash: String = "",val baseHash: String = "") {
    fun bytes(): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun steps(): Long = ByteBuffer.wrap(bytes()).order(ByteOrder.LITTLE_ENDIAN).getLong(16)
    fun valid(tick: Long): Boolean {
        if(hex.length!=BYTES*2 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return false
        val data=ByteBuffer.wrap(bytes()).order(ByteOrder.LITTLE_ENDIAN)
        if(data.int!=0x3141524c || data.short.toInt()!=1 || data.short.toInt()!=8 || data.short.toInt()!=8 || data.short.toInt()!=4 || data.int!=0) return false
        val steps=data.long;val anchors=data.long
        if(steps !in 0..MAX_STEPS || anchors!=steps/32) return false
        val values=List(320) { data.double }
        for(i in 0 until 64) {
            if(!values[i].isFinite() || abs(values[i])>8 || !values[256+i].isFinite() || abs(values[256+i])>8 ||
                !values[64+i].isFinite() || abs(values[64+i])>1e12 || !values[128+i].isFinite() || values[128+i] !in 0.0..1e24 ||
                !values[192+i].isFinite() || values[192+i] !in 0.0..1e24) return false
            if(steps==0L && (values[64+i]!=0.0 || values[128+i]!=0.0 || values[192+i]!=0.0 || values[i]!=values[256+i] || i>=32 && values[i]!=0.0)) return false
        }
        return if(steps==0L) trainedTick == -1L && sourceHash.isEmpty() && baseHash.isEmpty()
            else trainedTick in 1..tick && listOf(sourceHash,baseHash).all { s -> s.length==64 && s.all { it in '0'..'9' || it in 'a'..'f' } }
    }
    companion object {
        const val BYTES=2592
        const val MAX_STEPS=1_000_000_000_000L
        fun baseHash(mind: MindState)=Canonical.hash((mind.parameters.subList(184,248)+mind.hidden).joinToString(","))
        fun transition(world: WorldState,id: String,next: LoraAdapterState?,sourceHash: String): Boolean {
            val prior=world.minds[id];val old=prior?.lora
            if(next==null) return old==null
            if(!next.valid(world.tick)) return false
            if(old==null) return next.steps()==0L
            if(next==old) return true
            val receipt=world.agents.firstOrNull { it.id==id }?.memories?.lastOrNull { it.tick==world.tick }
            return old.steps()<MAX_STEPS && next.steps()==old.steps()+1 && next.trainedTick==world.tick &&
                next.trainedTick>old.trainedTick && next.sourceHash==sourceHash && next.baseHash==baseHash(prior) &&
                receipt!=null && receipt.eventId==world.tick && receipt.action==world.agents.first { it.id==id }.activity && receipt.reward.isFinite() && receipt.reward in -1.0..1.0
        }
    }
}
