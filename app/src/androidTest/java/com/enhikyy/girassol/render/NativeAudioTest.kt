package com.enhikyy.girassol.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.enhikyy.girassol.audio.NativeAudio
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAudioTest {
    @Test fun aaudioSilentStreamCanStartCloseAndReopen() {
        assertTrue(NativeAudio.available)
        // This tests the real Android audio route at zero amplitude, without touching the database.
        try {
            repeat(3) {
                NativeAudio.stop()
                NativeAudio.update(0f,0f,0f,0f,0f)
                assertEquals("AAudio stream open/start failed",0,NativeAudio.start(42))
                Thread.sleep(100)
                assertEquals("AAudio stream disconnected",0,NativeAudio.update(0f,0f,0f,0f,0f))
                NativeAudio.stop(); NativeAudio.stop()
                assertTrue("Closed stream reported healthy",NativeAudio.update(0f,0f,0f,0f,0f)<0)
            }
        } finally { NativeAudio.stop() }
    }
}
