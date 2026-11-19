package com.enhikyy.girassol.audio

import android.os.Handler
import android.os.HandlerThread
import com.enhikyy.girassol.render.NativeProjection
import java.util.concurrent.atomic.AtomicLong

internal object NativeAudio {
    val available get() = NativeProjection.available
    external fun start(seed: Long): Int
    external fun stop()
    private external fun nativeUpdate(volume: Float,left: Float,right: Float,mastery: Float,social: Float,weather: Float,tension: Float,milestone: Float): Int
    fun update(volume: Float,left: Float,right: Float,mastery: Float,social: Float,weather: Float=0f,tension: Float=0f,milestone: Float=0f): Int = nativeUpdate(volume,left,right,mastery,social,weather,tension,milestone)
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
