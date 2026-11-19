package com.enhikyy.girassol.audio

import android.os.Handler
import android.os.HandlerThread
import com.enhikyy.girassol.render.NativeProjection
import java.util.concurrent.atomic.AtomicLong

internal object NativeAudio {
    val available get() = NativeProjection.available
    external fun start(seed: Long): Int
    external fun stop()
    private external fun nativeUpdate(volume: Float,left: Float,right: Float,mastery: Float,social: Float,weather: Float,tension: Float,milestone: Float,pitch: Float,ratios: FloatArray): Int
    fun update(volume: Float,left: Float,right: Float,mastery: Float,social: Float,weather: Float=0f,tension: Float=0f,milestone: Float=0f,pitch: Float=1f,ratios: FloatArray=floatArrayOf(1f,1.067f,1.125f,1.2f,1.333f,1.5f,1.6f,1.8f)): Int = nativeUpdate(volume,left,right,mastery,social,weather,tension,milestone,pitch,ratios)
}

/** One stream in the process; obsolete Activity operations cannot stop a newer owner's audio. */
internal object AudioOutput {
    private val worker=Handler(HandlerThread("GirassolAudioControl").apply { start() }.looper)
    private val generation=AtomicLong()
    fun acquire(): Long {
        val lease=generation.incrementAndGet()
        submit(lease) { if (NativeAudio.available) NativeAudio.stop() }
        return lease
    }
    fun submit(lease: Long, action: () -> Unit) {
        worker.post { if (generation.get()==lease) action() }
    }
    fun release(lease: Long) {
        if (generation.compareAndSet(lease,lease+1)) submit(lease+1) { if (NativeAudio.available) NativeAudio.stop() }
    }
}
