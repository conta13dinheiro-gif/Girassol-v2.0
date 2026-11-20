package com.enhikyy.girassol.cognition

import java.util.concurrent.ConcurrentHashMap

/**
 * Small deterministic reflex classifier. It is deliberately a bounded policy hint,
 * never a replacement for WorldActor validation or urgent-need rules.
 */
internal object TsetlinReflexEngine {
    private const val FEATURE_COUNT = 16
    private const val CLAUSE_COUNT = 32
    private const val STATES = 64
    private const val THRESHOLD = 8
    private val handles = ConcurrentHashMap<String, Long>()

    private fun handle(agentId: String): Long = handles.computeIfAbsent(agentId) {
        val seed = 0x47525354L xor agentId.hashCode().toLong()
        GeaNativeBridge.nativeCreateTsetlin(FEATURE_COUNT, CLAUSE_COUNT, STATES, 3.5f, THRESHOLD, seed)
            .also { check(it != 0L) { "Não foi possível criar o reflexo Tsetlin" } }
    }

    /** Inputs are quantized from the current citizen state in stable feature order. */
    fun evaluate(agentId: String, features: ByteArray, outcome: String?): Int {
        require(agentId.isNotBlank() && features.size == FEATURE_COUNT && features.all { it == 0.toByte() || it == 1.toByte() })
        val model = handle(agentId)
        val vote = GeaNativeBridge.nativeTsetlinPredictVote(model, features)
        when (outcome) {
            "OK" -> check(GeaNativeBridge.nativeTsetlinTrainStep(model, features, true))
            "PRECONDITION_FAILED" -> check(GeaNativeBridge.nativeTsetlinTrainStep(model, features, false))
        }
        return vote
    }

    fun snapshots(): Map<String, ByteArray> = handles.entries
        .sortedBy { it.key }
        .associate { (agent, handle) -> agent to checkNotNull(GeaNativeBridge.nativeTsetlinSerialize(handle)) }

    fun restore(agentId: String, snapshot: ByteArray) {
        require(agentId.isNotBlank() && snapshot.size in 32..(1024 * 1024))
        val replacement = GeaNativeBridge.nativeTsetlinRestore(snapshot)
        check(replacement != 0L) { "Snapshot Tsetlin inválido" }
        val previous = handles.put(agentId, replacement)
        if (previous != null) GeaNativeBridge.nativeDestroyTsetlin(previous)
    }
}
