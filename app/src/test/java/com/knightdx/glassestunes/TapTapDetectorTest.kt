package com.knightdx.glassestunes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapTapDetectorTest {
    @Test fun quickPauseThenPlayFires() {
        val d = TapTapDetector(1500)
        assertFalse(d.onPlaybackChanged(true, 0))
        assertFalse(d.onPlaybackChanged(false, 10_000))
        assertTrue(d.onPlaybackChanged(true, 10_800))
    }

    @Test fun slowResumeDoesNotFire() {
        val d = TapTapDetector(1500)
        d.onPlaybackChanged(true, 0)
        d.onPlaybackChanged(false, 10_000)
        assertFalse(d.onPlaybackChanged(true, 13_000))
    }

    @Test fun startingPlaybackFromStoppedDoesNotFire() {
        val d = TapTapDetector(1500)
        assertFalse(d.onPlaybackChanged(false, 0))
        assertFalse(d.onPlaybackChanged(true, 200))
    }

    @Test fun duplicateStatesIgnoredAndResetClears() {
        val d = TapTapDetector(1500)
        d.onPlaybackChanged(true, 0)
        d.onPlaybackChanged(false, 1000)
        d.reset(false)
        assertFalse(d.onPlaybackChanged(true, 1500))
    }
}
