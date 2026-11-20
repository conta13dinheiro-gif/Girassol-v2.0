package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable

/** Bounded evidence retained in the same canonical event as the chosen action. */
@Serializable data class DecisionTrace(val topThree: List<Activity> = emptyList(),
    val freeEnergy: List<Double> = emptyList(), val expectedFreeEnergy: List<Double> = emptyList(),
    val successProbability: List<Double> = emptyList(), val allowLlm: Boolean = true) {
    fun valid(): Boolean = if(topThree.isEmpty()) freeEnergy.isEmpty() && expectedFreeEnergy.isEmpty() && successProbability.isEmpty()
        else topThree.size==3 && topThree.distinct().size==3 && freeEnergy.size==8 && expectedFreeEnergy.size==8 &&
            successProbability.size==8 && freeEnergy.all { it.isFinite() && it>=0 } &&
            expectedFreeEnergy.all { it.isFinite() } && successProbability.all { it.isFinite() && it in 0.0..1.0 }
}
