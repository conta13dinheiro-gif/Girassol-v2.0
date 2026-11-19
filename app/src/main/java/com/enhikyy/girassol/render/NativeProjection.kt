package com.enhikyy.girassol.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read-only rendering calculation; simulation authority remains in WorldActor. */
object NativeProjection {
    val available = try { System.loadLibrary("girassol_projection"); true } catch (_: LinkageError) { false }
    private external fun nativeProject(input: ByteBuffer, output: ByteBuffer, count: Int): Boolean
    private class Buffers(val capacity: Int) {
        val input: ByteBuffer = ByteBuffer.allocateDirect(capacity * 8).order(ByteOrder.nativeOrder())
        val output: ByteBuffer = ByteBuffer.allocateDirect(capacity * 8).order(ByteOrder.nativeOrder())
    }
    private val buffers = ThreadLocal<Buffers>()

    fun project(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()
        if (!available || points.size > 65536) return fallback(points)
        val cached = buffers.get()?.takeIf { it.capacity >= points.size } ?: Buffers(points.size).also { buffers.set(it) }
        val input = cached.input
        val output = cached.output
        points.forEachIndexed { i, (x, z) -> input.putFloat(i * 8, x); input.putFloat(i * 8 + 4, z) }
        if (!nativeProject(input, output, points.size)) return fallback(points)
        return points.indices.map { i -> output.getFloat(i * 8) to output.getFloat(i * 8 + 4) }
    }

    private fun fallback(points: List<Pair<Float, Float>>) = points.map { (x, z) -> (x - z) to ((x + z) * .52f) }
}
