package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable

/** Immutable wire state; empty default preserves historical event encodings. */
@Serializable data class TsetlinState(val hex: String = "", val features: Int = 0,
    val action: Int = 0, val decisionTick: Long = -1) {
    fun valid(tick: Long): Boolean = features in 0..65535 && action in 0..7 && decisionTick in -1..tick &&
        (hex.isEmpty() || (hex.length==1032 && hex.startsWith("47540110") &&
            hex.all { it in '0'..'9' || it in 'a'..'f' } &&
            hex.drop(8).chunked(2).all { it.toInt(16) in 1..128 }))
    fun bytes(): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    companion object {
        fun encode(bytes: ByteArray) = buildString(bytes.size*2) { for(byte in bytes) { val v=byte.toInt() and 255; append("0123456789abcdef"[v ushr 4]); append("0123456789abcdef"[v and 15]) } }
        fun features(citizen: Citizen, action: Activity): Int {
            val n=citizen.needs
            var bits=1 shl action.ordinal
            listOf(n.energy,n.hydration,n.glucose,n.social,n.curiosity,citizen.skill.mastery).forEachIndexed { i,v ->
                if(v>=.5) bits=bits or (1 shl (8+i))
            }
            if(citizen.acting) bits=bits or (1 shl 14)
            if(n.energy<.2 || n.hydration<.2 || n.glucose<.2) bits=bits or (1 shl 15)
            return bits
        }
    }
}
