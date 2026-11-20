package com.enhikyy.girassol.cognition

import java.nio.ByteBuffer

/** Single version-checked loader for the simulation JNI library. JVM callers can inspect availability. */
object GeaNativeBridge {
    const val CONTRACT_VERSION = 3
    private val failure: LinkageError? = try {
        System.loadLibrary("girassol_core")
        val actual = contractVersion()
        if (actual != CONTRACT_VERSION) UnsatisfiedLinkError("Girassol JNI contract: expected $CONTRACT_VERSION, got $actual") else null
    } catch (error: LinkageError) { error }

    val available: Boolean get() = failure == null

    fun requireAvailable() {
        failure?.let { throw it }
    }

    external fun tsetlinTrain(state: ByteArray, features: Int, label: Int, seed: Long): ByteArray
    external fun tsetlinVote(state: ByteArray, features: Int, early: Boolean): Int

    external fun qdUpdate(state: ByteBuffer, batch: ByteBuffer, count: Int, tick: Long, reset: Boolean): Boolean

    private external fun contractVersion(): Int
}
