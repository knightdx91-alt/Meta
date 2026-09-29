package com.knightdx.glassestunes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/**
 * The connector. Runs in the background while your glasses are in use:
 *  - watches for the tap-tap gesture on the glasses (or the notification /
 *    Quick Settings "Talk" button),
 *  - listens for a command through the glasses' mic,
 *  - plays it in Samsung Music, or from the phone's own music files.
 */
class GlassesService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var voice: GlassesVoice
    private lateinit var samsung: SamsungMusic
    private lateinit var local: LocalPlayer
    private lateinit var speaker: Speaker
    private lateinit var launcher: AppLauncher
    private lateinit var jarvis: JarvisWakeWord
    private var jarvisError: String? = null
    private var jarvisRetryAt = 0L
    private lateinit var sessionManager: MediaSessionManager

    @Volatile
    private var library: List<Track> = emptyList()
    private var busy = false
    private var suppressGesturesUntil = 0L
    private var lastUsedLocal = false
    /** False if Android refused mic access because we were started from the background. */
    private var micReady = false
    var status: String = "Ready"
        private set

    private val detectors = HashMap<String, TapTapDetector>()
    private val watched = HashMap<MediaController, MediaController.Callback>()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    /** Glasses connecting/disconnecting shows up as their Bluetooth mic appearing/disappearing. */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = scheduleJarvisUpdate(1500)
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = scheduleJarvisUpdate(300)
    }
    private val jarvisUpdate = Runnable { updateJarvis() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        voice = GlassesVoice(this)
        samsung = SamsungMusic(this)
        speaker = Speaker(this)
        launcher = AppLauncher(this)
        jarvis = JarvisWakeWord(this, onWake = ::onJarvis, onError = ::onJarvisError)
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCallback, main)
        local = LocalPlayer(this) { playing -> onPlayback(LOCAL_KEY, playing) }
        sessionManager = getSystemService(MediaSessionManager::class.java)
        goForeground()
        refresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LISTEN -> {
                // A notification tap lets us (re)claim the mic even from the background.
                if (!micReady) goForeground()
                startListening()
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "remove listener", e)
        }
        watched.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watched.clear()
        main.removeCallbacks(jarvisUpdate)
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCallback)
        jarvis.release()
        voice.cancel()
        voice.releaseGlassesMic()
        local.release()
        speaker.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    /** Re-read settings, the music library, and media access (call after the user grants permissions). */
    fun refresh() {
        io.execute {
            val songs = LocalLibrary.load(this)
            main.post { library = songs }
        }
        val component = ComponentName(this, MediaNotificationListener::class.java)
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            watch(sessionManager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.i(TAG, "notification access not granted yet; tap-tap gesture only works with the built-in player")
        }
        // Settings may have changed: restart Jarvis with them.
        jarvisRetryAt = 0
        jarvis.stop()
        scheduleJarvisUpdate(0)
    }

    // ---- "Jarvis" wake word -------------------------------------------------

    val jarvisStatus: String
        get() = when {
            !Prefs.jarvis(this) -> "Jarvis is off"
            jarvisError != null -> jarvisError!!
            jarvis.isRunning -> "Listening for \"Jarvis\""
            !voice.glassesMicConnected() -> "Jarvis waits for your glasses to connect"
            Prefs.jarvisOnlyWhenIdle(this) && anythingPlaying() -> "Jarvis is paused while music plays"
            !micReady -> "Tap Talk in the notification once to turn on the mic"
            else -> "Jarvis is starting…"
        }

    private fun scheduleJarvisUpdate(delayMs: Long) {
        main.removeCallbacks(jarvisUpdate)
        main.postDelayed(jarvisUpdate, delayMs)
    }

    private fun anythingPlaying(): Boolean = local.isPlaying ||
        samsung.activeControllers().any { it.packageName != packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING }

    /** Start or stop listening for "Jarvis" to match settings, glasses connection and playback. */
    private fun updateJarvis() {
        if (busy) return // resumes from finished()
        val want = Prefs.jarvis(this) && micReady && voice.glassesMicConnected() &&
            !(Prefs.jarvisOnlyWhenIdle(this) && anythingPlaying()) &&
            SystemClock.elapsedRealtime() >= jarvisRetryAt
        if (want && !jarvis.isRunning) {
            val mic = voice.holdGlassesMic() ?: return
            jarvisError = null
            if (!jarvis.start(mic)) voice.releaseGlassesMic()
        } else if (!want) {
            jarvis.stop()
            voice.releaseGlassesMic()
        }
        onStatus?.invoke(status)
    }

    private fun onJarvis() {
        Log.i(TAG, "wake word")
        startListening(fromWakeWord = true)
    }

    private fun onJarvisError(message: String) {
        jarvisError = message
        jarvisRetryAt = SystemClock.elapsedRealtime() + 60_000
        voice.releaseGlassesMic()
        setStatus(message)
        scheduleJarvisUpdate(60_500)
    }

    val librarySize get() = library.size

    // ---- tap-tap gesture ----------------------------------------------------

    private fun watch(controllers: List<MediaController>) {
        watched.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watched.clear()
        for (c in controllers) {
            if (c.packageName == packageName) continue
            val key = c.packageName
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    when (state?.state) {
                        PlaybackState.STATE_PLAYING -> onPlayback(key, true)
                        PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> onPlayback(key, false)
                    }
                }
            }
            c.registerCallback(cb, main)
            watched[c] = cb
            detectors.getOrPut(key) { TapTapDetector() }
                .reset(c.playbackState?.state == PlaybackState.STATE_PLAYING)
        }
    }

    private fun onPlayback(key: String, playing: Boolean) {
        val detector = detectors.getOrPut(key) { TapTapDetector() }
        val now = SystemClock.elapsedRealtime()
        if (busy || now < suppressGesturesUntil || !Prefs.tapGesture(this)) {
            detector.reset(playing)
            return
        }
        if (detector.onPlaybackChanged(playing, now)) {
            Log.i(TAG, "tap-tap on $key")
            startListening()
            return
        }
        if (Prefs.jarvisOnlyWhenIdle(this)) scheduleJarvisUpdate(800)
    }

    private fun quietGestures() {
        suppressGesturesUntil = SystemClock.elapsedRealtime() + 3000
    }

    // ---- listening ----------------------------------------------------------

    fun startListening(fromWakeWord: Boolean = false) {
        if (busy) return
        if (!micReady) {
            goForeground()
            if (micReady) scheduleJarvisUpdate(0)
        }
        if (!micReady) {
            say("Tap Talk in the Glasses Tunes notification once to turn the microphone on") {}
            return
        }
        busy = true
        // Free the mic for speech recognition (the glasses link stays open).
        jarvis.stop()
        val target = currentTarget()
        val wasPlaying = target.playing
        quietGestures()
        target.pause()
        setStatus("Listening…")
        voice.listen { result ->
            when (result) {
                // A false "Jarvis" shouldn't nag you, so wake-word misses stay quiet.
                is GlassesVoice.Result.Failed -> say(if (fromWakeWord) "" else result.reason) {
                    if (wasPlaying) target.resume()
                    finished("Ready")
                }
                is GlassesVoice.Result.Heard -> {
                    val command = result.phrases.map { CommandParser.parse(it) }
                        .firstOrNull { it !is Command.Unknown }
                        ?: Command.Unknown(result.phrases.first())
                    Log.i(TAG, "heard ${result.phrases} -> $command")
                    execute(command, target, wasPlaying)
                }
            }
        }
    }

    /** Run a typed command (used by the test box in the app). */
    fun runText(text: String) {
        if (busy) return
        busy = true
        val target = currentTarget()
        val wasPlaying = target.playing
        quietGestures()
        execute(CommandParser.parse(text), target, wasPlaying, pauseFirst = true)
    }

    private fun finished(newStatus: String) {
        quietGestures()
        busy = false
        setStatus(newStatus)
        // Let a started song settle before deciding whether Jarvis should listen.
        scheduleJarvisUpdate(1000)
    }

    private fun execute(command: Command, target: Target, wasPlaying: Boolean, pauseFirst: Boolean = false) {
        val resumeIfNeeded = { if (wasPlaying) target.resume() }
        when (command) {
            Command.Pause -> {
                target.pause()
                say("Paused") { finished("Paused") }
            }
            Command.Resume -> {
                when {
                    target !is Target.None -> {
                        target.resume()
                        finished("Playing")
                    }
                    // Nothing open yet: have Samsung Music pick up where it left off.
                    useSamsung() -> samsung.play { ok -> if (ok) finished("Samsung Music") else shuffleAll() }
                    else -> shuffleAll()
                }
            }
            Command.Next -> {
                target.next()
                target.resume()
                finished("Skipped")
            }
            Command.Previous -> {
                target.previous()
                target.resume()
                finished("Previous track")
            }
            Command.VolumeUp, Command.VolumeDown -> {
                val audio = getSystemService(AudioManager::class.java)
                val dir = if (command == Command.VolumeUp) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) }
                resumeIfNeeded()
                finished(if (command == Command.VolumeUp) "Volume up" else "Volume down")
            }
            Command.WhatsPlaying -> {
                val what = target.describe()
                say(if (what != null) "This is $what" else "Nothing is playing") {
                    resumeIfNeeded()
                    finished(what ?: "Ready")
                }
            }
            Command.ShuffleAll -> {
                if (pauseFirst) target.pause()
                shuffleAll()
            }
            is Command.Play -> {
                if (pauseFirst) target.pause()
                playRequest(command.request, resumeIfNeeded)
            }
            Command.Assistant -> say("Opening Gemini") {
                if (!launcher.openAssistant()) say("I couldn't open Gemini") { resumeIfNeeded() }
                // Leave the music paused so the assistant can hear you.
                finished("Opened Gemini")
            }
            is Command.OpenApp -> {
                val app = launcher.find(command.name)
                if (app == null) {
                    say("I couldn't find an app called ${command.name}") {
                        resumeIfNeeded()
                        finished("No app \"${command.name}\"")
                    }
                } else {
                    val hint = when {
                        !launcher.canOpenFromBackground() -> ". Tap the notification to open it"
                        launcher.isLocked() -> ". Unlock your phone to use it"
                        else -> ""
                    }
                    say("Opening ${app.label}$hint") {
                        launcher.open(app)
                        resumeIfNeeded()
                        finished("Opened ${app.label}")
                    }
                }
            }
            is Command.Unknown -> say("Sorry, I heard: ${command.heard}. Try saying play, then a song, artist, or album.") {
                resumeIfNeeded()
                finished("Didn't understand \"${command.heard}\"")
            }
        }
    }

    private fun useSamsung() = Prefs.preferSamsung(this) && samsung.isInstalled()

    private fun playRequest(request: PlayRequest, onNotFound: () -> Unit) {
        val spoken = request.query + (request.artist?.let { " by $it" } ?: "")
        val playLocal: (() -> Unit) -> Unit = { orElse ->
            val selection = LibraryMatcher.select(library, request)
            if (selection != null) {
                say("Playing ${selection.description}") {
                    playLocalSelection(selection)
                    finished("Playing ${selection.description}")
                }
            } else {
                orElse()
            }
        }
        val notFound = {
            say("I couldn't find $spoken") {
                onNotFound()
                finished("Couldn't find \"$spoken\"")
            }
        }
        if (useSamsung()) {
            say("Playing $spoken") {
                samsung.playFromSearch(request) { ok ->
                    if (ok) {
                        lastUsedLocal = false
                        finished("Samsung Music: $spoken")
                    } else {
                        playLocal(notFound)
                    }
                }
            }
        } else {
            playLocal {
                if (samsung.isInstalled()) {
                    samsung.playFromSearch(request) { ok ->
                        if (ok) {
                            lastUsedLocal = false
                            finished("Samsung Music: $spoken")
                        } else {
                            notFound()
                        }
                    }
                } else {
                    notFound()
                }
            }
        }
    }

    private fun shuffleAll() {
        val localShuffle = {
            val selection = LibraryMatcher.shuffleAll(library)
            if (selection != null) {
                say("Shuffling your music") {
                    playLocalSelection(selection)
                    finished("Shuffling all music")
                }
            } else {
                say("I couldn't find any music on your phone") { finished("No music found") }
            }
        }
        if (useSamsung()) {
            samsung.shuffleAll { ok ->
                if (ok) {
                    lastUsedLocal = false
                    finished("Samsung Music: shuffle all")
                } else {
                    localShuffle()
                }
            }
        } else {
            localShuffle()
        }
    }

    private fun playLocalSelection(selection: Selection) {
        lastUsedLocal = true
        local.play(selection.tracks)
    }

    private fun say(text: String, then: () -> Unit) {
        if (text.isEmpty()) {
            then()
            return
        }
        setStatus(text)
        if (Prefs.speakReplies(this)) speaker.say(text, then) else then()
    }

    // ---- what "pause / next / resume" talks to ------------------------------

    private sealed class Target {
        abstract val playing: Boolean
        open fun pause() {}
        open fun resume() {}
        open fun next() {}
        open fun previous() {}
        open fun describe(): String? = null

        class Local(private val p: LocalPlayer) : Target() {
            override val playing get() = p.isPlaying
            override fun pause() = p.pause()
            override fun resume() = p.resume()
            override fun next() = p.next()
            override fun previous() = p.previous()
            override fun describe() = p.current?.let { "${it.title} by ${it.artist}" }
        }

        class Remote(private val c: MediaController) : Target() {
            override val playing get() = c.playbackState?.state == PlaybackState.STATE_PLAYING
            override fun pause() = c.transportControls.pause()
            override fun resume() = c.transportControls.play()
            override fun next() = c.transportControls.skipToNext()
            override fun previous() = c.transportControls.skipToPrevious()
            override fun describe() = SamsungMusic.describe(c)
        }

        object None : Target() {
            override val playing = false
        }
    }

    private fun currentTarget(): Target {
        if (local.isPlaying) return Target.Local(local)
        val remote = samsung.bestController(exclude = packageName)
        if (remote != null && remote.playbackState?.state == PlaybackState.STATE_PLAYING) return Target.Remote(remote)
        if (local.hasQueue && lastUsedLocal) return Target.Local(local)
        if (remote != null) return Target.Remote(remote)
        if (local.hasQueue) return Target.Local(local)
        return Target.None
    }

    // ---- notification -------------------------------------------------------

    private fun setStatus(text: String) {
        status = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        onStatus?.invoke(text)
    }

    private fun goForeground() {
        createChannel(this)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT < 30) {
            startForeground(NOTIFICATION_ID, notification)
            micReady = true
            return
        }
        micReady = try {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            true
        } catch (e: SecurityException) {
            // Android 14+: started from the background, so no mic until the user taps something of ours.
            Log.w(TAG, "microphone not allowed yet", e)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            false
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val talk = PendingIntent.getService(this, 1, Intent(this, GlassesService::class.java).setAction(ACTION_LISTEN), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, GlassesService::class.java).setAction(ACTION_STOP), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_glasses)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.talk), talk).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "GlassesService"
        const val CHANNEL = "connector"
        private const val NOTIFICATION_ID = 1
        private const val LOCAL_KEY = "local"
        const val ACTION_LISTEN = "com.knightdx.glassestunes.LISTEN"
        const val ACTION_STOP = "com.knightdx.glassestunes.STOP"

        var instance: GlassesService? = null
            private set

        /** Lets the open app screen show live status. */
        var onStatus: ((String) -> Unit)? = null

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GlassesService::class.java))
        }
    }
}
