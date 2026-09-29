package com.knightdx.glassestunes

import android.content.ContentUris
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log

/** Reads the songs stored on the phone from Android's media index. */
object LocalLibrary {
    fun load(context: Context): List<Track> {
        val tracks = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TRACK,
        )
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    tracks += Track(
                        id = c.getLong(0),
                        title = c.getString(1) ?: "",
                        artist = c.getString(2) ?: "",
                        album = c.getString(3) ?: "",
                        // MediaStore encodes disc*1000 + track.
                        trackNo = c.getInt(4),
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w("LocalLibrary", "no permission to read music", e)
        }
        return tracks
    }
}

/**
 * A minimal built-in player for the music files on the phone, used when
 * Samsung Music can't take a voice search. It publishes a media session, so
 * tapping the glasses' temple still pauses/plays/skips it.
 */
class LocalPlayer(private val context: Context, private val onStateChanged: (playing: Boolean) -> Unit) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            }
        }
        .build()

    private var queue: List<Track> = emptyList()
    private var index = 0
    private var player: MediaPlayer? = null
    private var prepared = false

    val session = MediaSession(context, "GlassesTunes").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onStop() = pause()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = previous()
        })
    }

    val hasQueue get() = queue.isNotEmpty()
    val isPlaying get() = player?.isPlaying == true
    val current: Track? get() = queue.getOrNull(index)

    fun play(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        queue = tracks
        index = 0
        start()
    }

    fun resume() {
        val p = player
        if (p == null || !prepared) {
            if (hasQueue) start()
            return
        }
        if (!requestFocus()) return
        p.start()
        publish()
    }

    fun pause() {
        player?.takeIf { prepared && it.isPlaying }?.pause()
        publish()
    }

    fun next() {
        if (!hasQueue) return
        index = (index + 1) % queue.size
        start()
    }

    fun previous() {
        if (!hasQueue) return
        val p = player
        if (p != null && prepared && p.currentPosition > 3000) {
            p.seekTo(0)
            return
        }
        index = if (index == 0) queue.size - 1 else index - 1
        start()
    }

    fun release() {
        player?.release()
        player = null
        audio.abandonAudioFocusRequest(focusRequest)
        session.isActive = false
        session.release()
    }

    private fun requestFocus() = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun start() {
        val track = current ?: return
        if (!requestFocus()) return
        player?.release()
        prepared = false
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id)
        player = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            setOnPreparedListener {
                prepared = true
                it.start()
                publish()
            }
            setOnCompletionListener { next() }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "playback error $what/$extra on ${track.title}")
                if (queue.size > 1) next()
                true
            }
            try {
                setDataSource(context, uri)
                prepareAsync()
            } catch (e: Exception) {
                Log.w(TAG, "cannot open ${track.title}", e)
            }
        }
        session.isActive = true
        publish()
    }

    private fun publish() {
        val track = current
        if (track != null) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                    .build()
            )
        }
        val playing = isPlaying
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    if (prepared) player?.currentPosition?.toLong() ?: 0L else 0L,
                    1f,
                )
                .build()
        )
        onStateChanged(playing)
    }

    companion object {
        private const val TAG = "LocalPlayer"
    }
}
