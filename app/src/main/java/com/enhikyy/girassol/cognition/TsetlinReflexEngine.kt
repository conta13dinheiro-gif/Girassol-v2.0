package com.enhikyy.girassol.cognition

/**
 * One bounded, deterministic reflex model shared by the world thread.
 * It is a small policy hint, never a replacement for WorldActor validation.
 */
internal object TsetlinReflexEngine {
    private const val FEATURE_COUNT = 16
    private const val CLAUSE_COUNT = 32
    private const val STATES = 64
    private const val THRESHOLD = 8
    private const val SNAPSHOT_KEY = "__global__"
    private var handle: Long? = null

    @Synchronized
    private fun model(): Long = handle ?: GeaNativeBridge.nativeCreateTsetlin(
        FEATURE_COUNT, CLAUSE_COUNT, STATES, 3.5f, THRESHOLD, 0x47525354L
    ).also {
        check(it != 0L) { "Não foi possível criar o reflexo Tsetlin" }
        handle = it
    }

    /** Inputs are quantized from current citizen state in stable feature order. */
    @Synchronized
    fun evaluate(agentId: String, features: ByteArray, outcome: String?): Int {
        require(agentId.isNotBlank() && features.size == FEATURE_COUNT &&
            features.all { it == 0.toByte() || it == 1.toByte() })
        val current = model()
        val vote = GeaNativeBridge.nativeTsetlinPredictVote(current, features)
        when (outcome) {
            "OK" -> check(GeaNativeBridge.nativeTsetlinTrainStep(current, features, true))
            "PRECONDITION_FAILED" -> check(GeaNativeBridge.nativeTsetlinTrainStep(current, features, false))
        }
        return vote
    }

    @Synchronized
    fun snapshot(): ByteArray = checkNotNull(GeaNativeBridge.nativeTsetlinSerialize(model()))

    @Synchronized
    fun restore(snapshot: ByteArray) {
        require(snapshot.size in 32..(1024 * 1024))
        val replacement = GeaNativeBridge.nativeTsetlinRestore(snapshot)
        check(replacement != 0L) { "Snapshot Tsetlin inválido" }
        handle?.let(GeaNativeBridge::nativeDestroyTsetlin)
        handle = replacement
    }

    const val persistenceKey: String = SNAPSHOT_KEY
}
