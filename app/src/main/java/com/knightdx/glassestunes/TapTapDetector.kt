package com.knightdx.glassestunes

/**
 * Detects the "tap-tap" gesture on the glasses: a single tap on the temple
 * pauses the music and a second tap soon after resumes it. We watch the
 * playback state of whatever is playing and treat pause → play within
 * [windowMs] as "start listening for a voice command".
 */
class TapTapDetector(private val windowMs: Long = 1500) {
    private var lastPauseAt = Long.MIN_VALUE / 2
    private var wasPlaying: Boolean? = null

    /** Returns true when the gesture completed. */
    fun onPlaybackChanged(playing: Boolean, nowMs: Long): Boolean {
        val previous = wasPlaying
        wasPlaying = playing
        if (previous == playing) return false
        if (!playing) {
            if (previous == true) lastPauseAt = nowMs
            return false
        }
        val fired = nowMs - lastPauseAt in 0..windowMs
        if (fired) lastPauseAt = Long.MIN_VALUE / 2
        return fired
    }

    /** Forget history, e.g. after we paused/resumed the music ourselves. */
    fun reset(playing: Boolean?) {
        wasPlaying = playing
        lastPauseAt = Long.MIN_VALUE / 2
    }
}
