package com.enhikyy.girassol.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiosphereUiRuntimeTest {
    @Test
    fun wakeLockDefaultsToReleased() {
        assertFalse(BiosphereUi().wakeLockHeld)
    }

    @Test
    fun wakeLockSnapshotTracksRunningAndStoppedStates() {
        val active = BiosphereUi(running = true, wakeLockHeld = true)
        assertTrue(active.running)
        assertTrue(active.wakeLockHeld)

        val stopped = active.copy(running = false, wakeLockHeld = false)
        assertFalse(stopped.running)
        assertFalse(stopped.wakeLockHeld)
    }
}
