package com.enhikyy.girassol.cognition

/** Single version-checked loader for the simulation JNI library. JVM callers can inspect availability. */
object GeaNativeBridge {
    const val CONTRACT_VERSION = 1
    private val failure: LinkageError? = try {
        System.loadLibrary("girassol_core")
        val actual = contractVersion()
        if (actual != CONTRACT_VERSION) UnsatisfiedLinkError("Girassol JNI contract: expected $CONTRACT_VERSION, got $actual") else null
    } catch (error: LinkageError) { error }

    val available: Boolean get() = failure == null
    fun requireAvailable() { failure?.let { throw it } }
    private external fun contractVersion(): Int
    external fun nativeCreateTsetlin(features: Int, clauses: Int, states: Int, specificity: Float, threshold: Int, seed: Long): Long
    external fun nativeDestroyTsetlin(handle: Long)
    external fun nativeTsetlinTrainStep(handle: Long, features: ByteArray, targetPositive: Boolean): Boolean
    external fun nativeTsetlinPredictVote(handle: Long, features: ByteArray): Int
    external fun nativeTsetlinSerialize(handle: Long): ByteArray?
    external fun nativeTsetlinRestore(blob: ByteArray): Long
    external fun nativeHardwareProbe(): String
}
