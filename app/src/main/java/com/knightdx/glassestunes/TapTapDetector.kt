package com.knightdx.glassestunes

/**
 * Detects the "tap-tap" gesture on the glasses: a single tap on the temple
 * pauses the music and a second tap soon after resumes it. For other apps we
 * only see their playback state, so pause → play of the *same song*, with the
 * pause lasting between [minPauseMs] and [windowMs], counts as "start
 * listening". Changing songs also flickers through paused, but it's much
 * shorter than a human double-tap and the song changes, so it's ignored.
 */
class TapTapDetector(private val windowMs: Long = 1500, private val minPauseMs: Long = 250) {
    private var lastPauseAt = Long.MIN_VALUE / 2
    private var pausedTrack: String? = null
    private var wasPlaying: Boolean? = null

    /** Returns true when the gesture completed. [track] identifies the current song, if known. */
    fun onPlaybackChanged(playing: Boolean, nowMs: Long, track: String? = null): Boolean {
        val previous = wasPlaying
        wasPlaying = playing
        if (previous == playing) return false
        if (!playing) {
            if (previous == true) {
                lastPauseAt = nowMs
                pausedTrack = track
            }
            return false
        }
        val pause = nowMs - lastPauseAt
        val sameSong = track == null || pausedTrack == null || track == pausedTrack
        val fired = pause in minPauseMs..windowMs && sameSong
        lastPauseAt = Long.MIN_VALUE / 2
        return fired
    }

    /** Forget history, e.g. after we paused/resumed the music ourselves. */
    fun reset(playing: Boolean?) {
        wasPlaying = playing
        lastPauseAt = Long.MIN_VALUE / 2
        pausedTrack = null
    }
}
