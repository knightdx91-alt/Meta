package com.knightdx.glassestunes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.service.media.MediaBrowserService
import android.util.Log

/**
 * Talks to Samsung Music (and, for play/pause/skip, whatever app is playing)
 * through Android's standard media session APIs — the same ones Android Auto
 * and Bluetooth car stereos use.
 */
class SamsungMusic(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, MediaNotificationListener::class.java)

    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Needs "notification access" so we can see other apps' media sessions. */
    fun activeControllers(): List<MediaController> = try {
        sessions.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
        emptyList()
    }

    fun samsungController(): MediaController? = activeControllers().firstOrNull { it.packageName == PACKAGE }

    /** The session to send play/pause/skip to: whatever is playing, else Samsung Music, else the most recent. */
    fun bestController(exclude: String? = null): MediaController? {
        val all = activeControllers().filter { it.packageName != exclude }
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull { it.packageName == PACKAGE }
            ?: all.firstOrNull()
    }

    /**
     * Ask Samsung Music to search and play. Reports whether it started
     * playing within a few seconds.
     */
    fun playFromSearch(request: PlayRequest, done: (Boolean) -> Unit) {
        val query = buildString {
            append(request.query)
            if (request.artist != null) append(" ").append(request.artist)
        }
        val extras = searchExtras(request)
        val existing = samsungController()
        if (existing != null && supports(existing, PlaybackState.ACTION_PLAY_FROM_SEARCH)) {
            existing.transportControls.playFromSearch(query, extras)
            verifyPlaying(existing, done)
            return
        }
        withBrowser { controller, disconnect ->
            if (controller == null) {
                done(false)
                return@withBrowser
            }
            controller.transportControls.playFromSearch(query, extras)
            verifyPlaying(controller) { ok ->
                disconnect()
                done(ok)
            }
        }
    }

    /**
     * Ask Samsung Music to play one song we already found in the phone's
     * library, by its file (if Samsung accepts that) or by its exact title and
     * artist from the tags. Only reports success if that song is what's playing.
     */
    fun playTrack(track: Track, done: (Boolean) -> Unit) {
        val uri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id)
        val request = PlayRequest(track.title, Focus.SONG, artist = track.artist)
        val ask = { c: MediaController ->
            if (supports(c, PlaybackState.ACTION_PLAY_FROM_URI)) {
                c.transportControls.playFromUri(uri, Bundle())
            } else {
                c.transportControls.playFromSearch("${track.title} ${track.artist}", searchExtras(request))
            }
        }
        val existing = samsungController()
        if (existing != null) {
            ask(existing)
            verifySong(existing, track, done)
            return
        }
        withBrowser { controller, disconnect ->
            if (controller == null) {
                done(false)
                return@withBrowser
            }
            ask(controller)
            verifySong(controller, track) { ok ->
                disconnect()
                done(ok)
            }
        }
    }

    private fun verifySong(controller: MediaController, track: Track, done: (Boolean) -> Unit) {
        fun right(): Boolean {
            val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return false
            return controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                LibraryMatcher.score(title, track.title) >= 85
        }
        var answered = false
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = check()
            override fun onMetadataChanged(metadata: MediaMetadata?) = check()
            fun check() {
                if (!answered && right()) {
                    answered = true
                    controller.unregisterCallback(this)
                    done(true)
                }
            }
        }
        controller.registerCallback(callback, main)
        main.postDelayed({
            if (!answered) {
                answered = true
                controller.unregisterCallback(callback)
                val ok = right()
                if (!ok) Log.i(TAG, "Samsung Music didn't take \"${track.title}\"; using the built-in player")
                done(ok)
            }
        }, 3000)
    }

    /** Start Samsung Music playing even if it isn't running yet ("play music"). */
    fun play(done: (Boolean) -> Unit) {
        val existing = samsungController()
        if (existing != null) {
            existing.transportControls.play()
            verifyPlaying(existing, done)
            return
        }
        withBrowser { controller, disconnect ->
            if (controller == null) {
                done(false)
                return@withBrowser
            }
            controller.transportControls.play()
            verifyPlaying(controller) { ok ->
                disconnect()
                done(ok)
            }
        }
    }

    /** Samsung Music shuffle-all, via an empty search which by convention means "play anything". */
    fun shuffleAll(done: (Boolean) -> Unit) {
        withBrowser { controller, disconnect ->
            val c = controller ?: samsungController()
            if (c == null) {
                disconnect()
                done(false)
                return@withBrowser
            }
            c.transportControls.playFromSearch("", Bundle())
            verifyPlaying(c) { ok ->
                disconnect()
                done(ok)
            }
        }
    }

    private fun supports(c: MediaController, action: Long) =
        ((c.playbackState?.actions ?: 0L) and action) != 0L

    private fun searchExtras(request: PlayRequest) = Bundle().apply {
        when (request.focus) {
            Focus.ARTIST -> {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_ARTIST, request.query)
            }
            Focus.ALBUM -> {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_ALBUM, request.query)
            }
            Focus.SONG -> {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                putString(MediaStore.EXTRA_MEDIA_TITLE, request.query)
                request.artist?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
            }
            Focus.PLAYLIST -> {
                @Suppress("DEPRECATION")
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
                putString(EXTRA_MEDIA_PLAYLIST, request.query)
            }
            Focus.GENRE -> {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE)
                putString(EXTRA_MEDIA_GENRE, request.query)
            }
            Focus.ANY -> putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        putString(android.app.SearchManager.QUERY, request.query)
    }

    private fun verifyPlaying(controller: MediaController, done: (Boolean) -> Unit) {
        var answered = false
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (!answered && state?.state == PlaybackState.STATE_PLAYING) {
                    answered = true
                    controller.unregisterCallback(this)
                    done(true)
                }
            }
        }
        controller.registerCallback(callback, main)
        main.postDelayed({
            if (!answered) {
                answered = true
                controller.unregisterCallback(callback)
                done(controller.playbackState?.state == PlaybackState.STATE_PLAYING)
            }
        }, 4000)
    }

    /** Connects to Samsung Music's MediaBrowserService (the Android Auto entry point). */
    private fun withBrowser(block: (MediaController?, () -> Unit) -> Unit) {
        val service = context.packageManager
            .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(PACKAGE), 0)
            .firstOrNull()
        if (service == null) {
            block(null) {}
            return
        }
        var browser: MediaBrowser? = null
        var called = false
        val disconnect = { browser?.disconnect(); Unit }
        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                if (called) return
                called = true
                val b = browser ?: return
                block(MediaController(context, b.sessionToken), disconnect)
            }

            override fun onConnectionFailed() {
                if (called) return
                called = true
                Log.w(TAG, "Samsung Music refused the media browser connection")
                block(null, disconnect)
            }

            override fun onConnectionSuspended() {}
        }
        browser = MediaBrowser(context, ComponentName(PACKAGE, service.serviceInfo.name), callback, null)
        browser.connect()
        main.postDelayed({
            if (!called) {
                called = true
                disconnect()
                block(null) {}
            }
        }, 5000)
    }

    companion object {
        const val PACKAGE = "com.sec.android.app.music"
        private const val TAG = "SamsungMusic"
        private const val EXTRA_MEDIA_PLAYLIST = "android.intent.extra.playlist"
        private const val EXTRA_MEDIA_GENRE = "android.intent.extra.genre"

        fun describe(controller: MediaController?): String? {
            val md = controller?.metadata ?: return null
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            return if (artist.isNullOrBlank()) title else "$title by $artist"
        }
    }
}
