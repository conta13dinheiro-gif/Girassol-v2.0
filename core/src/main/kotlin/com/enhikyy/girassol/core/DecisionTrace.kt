package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/** Bounded evidence retained in the same canonical event as the chosen action. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class DecisionTrace(val topThree: List<Activity> = emptyList(),
    val freeEnergy: List<Double> = emptyList(), val expectedFreeEnergy: List<Double> = emptyList(),
    val successProbability: List<Double> = emptyList(), val allowLlm: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nativeActionValues: List<Double> = emptyList()) {
    fun valid(): Boolean = if(topThree.isEmpty()) freeEnergy.isEmpty() && expectedFreeEnergy.isEmpty() && successProbability.isEmpty() && nativeActionValues.isEmpty()
        else (nativeActionValues.isEmpty() || nativeActionValues.size==8 && nativeActionValues.all { it.isFinite() && it in -8.0..8.0 }) && topThree.size==3 && topThree.distinct().size==3 && freeEnergy.size==8 && expectedFreeEnergy.size==8 &&
            successProbability.size==8 && freeEnergy.all { it.isFinite() && it>=0 } &&
            expectedFreeEnergy.all { it.isFinite() } && successProbability.all { it.isFinite() && it in 0.0..1.0 }
}
