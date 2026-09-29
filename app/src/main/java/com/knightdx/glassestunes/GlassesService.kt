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
    private lateinit var phone: PhoneActions
    private lateinit var assistant: AskAssistant
    /** Recent questions and answers, so follow-ups like "who's he married to?" work. */
    private val conversation = ArrayList<AskAssistant.Turn>()
    private var lastQuestionAt = 0L
    /** The message last read aloud, so "reply" answers it. */
    private var lastHeardMessage: InboxEntry? = null
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
    private val jarvisUpdate = Runnable { guard("Jarvis update") { updateJarvis() } }
    /** Android 12+ only; the listener type doesn't exist on older versions, so it's created lazily. */
    private var modeListener: Any? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        voice = GlassesVoice(this)
        samsung = SamsungMusic(this)
        speaker = Speaker(this)
        launcher = AppLauncher(this)
        phone = PhoneActions(this)
        assistant = AskAssistant(this)
        jarvis = JarvisWakeWord(this, onWake = ::onJarvis, onError = ::onJarvisError)
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCallback, main)
        if (Build.VERSION.SDK_INT >= 31) {
            // Step aside for phone and WhatsApp calls, which need the glasses' mic themselves.
            val listener = AudioManager.OnModeChangedListener { scheduleJarvisUpdate(300) }
            modeListener = listener
            getSystemService(AudioManager::class.java).addOnModeChangedListener(mainExecutor, listener)
        }
        local = LocalPlayer(
            this,
            onStateChanged = { if (Prefs.jarvisOnlyWhenIdle(this)) scheduleJarvisUpdate(800) },
            // Our own player tells us about real taps directly, so loading a song can't look like a tap.
            onUserToggle = { playing -> onPlayback(LOCAL_KEY, playing, "local") },
        )
        sessionManager = getSystemService(MediaSessionManager::class.java)
        if (!goForeground()) {
            AutoStart.promptRestart(this)
            stopSelf()
            return
        }
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
        if (Build.VERSION.SDK_INT >= 31) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let {
                getSystemService(AudioManager::class.java).removeOnModeChangedListener(it)
            }
        }
        jarvis.release()
        voice.cancel()
        voice.releaseGlassesMic()
        local.release()
        speaker.shutdown()
        assistant.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    /** Re-read settings, the music library, and media access (call after the user grants permissions). */
    fun refresh() {
        io.execute {
            val songs = try {
                LocalLibrary.load(this)
            } catch (e: RuntimeException) {
                CrashReport.recordProblem(this, "loading music library", e)
                emptyList()
            }
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
        jarvis.stop(wait = false) // don't block the screen that called us
        scheduleJarvisUpdate(300)
    }

    // ---- "Jarvis" wake word -------------------------------------------------

    val jarvisStatus: String
        get() = when {
            !Prefs.jarvis(this) -> "Jarvis is off"
            jarvisError != null -> jarvisError!!
            jarvis.isRunning -> "Listening for \"Jarvis\""
            !voice.glassesMicConnected() -> "Jarvis waits for your glasses to connect"
            inCall() -> "Jarvis is paused during calls"
            Prefs.jarvisOnlyWhenIdle(this) && anythingPlaying() -> "Jarvis is paused while music plays"
            !micReady -> "Tap Talk in the notification once to turn on the mic"
            else -> "Jarvis is starting…"
        }

    private fun scheduleJarvisUpdate(delayMs: Long) {
        main.removeCallbacks(jarvisUpdate)
        main.postDelayed(jarvisUpdate, delayMs)
    }

    /** A phone or internet call is using the audio (ringing is fine, so "Jarvis, answer" works). */
    private fun inCall(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false // older Androids: our own voice link uses this mode
        val mode = getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_CALL_SCREENING
    }

    private fun anythingPlaying(): Boolean = local.isPlaying ||
        samsung.activeControllers().any { it.packageName != packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING }

    /** Start or stop listening for "Jarvis" to match settings, glasses connection and playback. */
    private fun updateJarvis() {
        if (busy) return // resumes from finished()
        val want = Prefs.jarvis(this) && micReady && voice.glassesMicConnected() &&
            !(Prefs.jarvisOnlyWhenIdle(this) && anythingPlaying()) && !inCall() &&
            SystemClock.elapsedRealtime() >= jarvisRetryAt
        if (want && !jarvis.isRunning) {
            val mic = voice.holdGlassesMic() ?: return
            jarvisError = null
            if (!jarvis.start(mic)) voice.releaseGlassesMic()
        } else if (!want) {
            jarvis.stop(wait = false)
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
                        PlaybackState.STATE_PLAYING -> onPlayback(key, true, songOf(c))
                        PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> onPlayback(key, false, songOf(c))
                    }
                }
            }
            c.registerCallback(cb, main)
            watched[c] = cb
            detectors.getOrPut(key) { TapTapDetector() }
                .reset(c.playbackState?.state == PlaybackState.STATE_PLAYING)
        }
    }

    private fun onPlayback(key: String, playing: Boolean, song: String?) =
        guard("watching playback") { playbackChanged(key, playing, song) }

    private fun songOf(c: MediaController): String? = c.metadata?.let {
        "${it.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)}|${it.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)}"
    }

    private fun playbackChanged(key: String, playing: Boolean, song: String?) {
        val detector = detectors.getOrPut(key) { TapTapDetector() }
        val now = SystemClock.elapsedRealtime()
        if (busy || now < suppressGesturesUntil || !Prefs.tapGesture(this)) {
            detector.reset(playing)
            return
        }
        if (detector.onPlaybackChanged(playing, now, song)) {
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

    fun startListening(fromWakeWord: Boolean = false) = guard("starting to listen") { listenNow(fromWakeWord) }

    private fun listenNow(fromWakeWord: Boolean) {
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
        voice.listen { result -> guard("handling what was heard") {
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
        } }
    }

    /** Run a typed command (used by the test box in the app). */
    fun runText(text: String) = guard("running a typed command") {
        if (busy) return@guard
        busy = true
        val target = currentTarget()
        val wasPlaying = target.playing
        quietGestures()
        execute(CommandParser.parse(text), target, wasPlaying, pauseFirst = true)
    }

    /**
     * Runs a step of a command. Callbacks (after speech, searches, contacts…)
     * run later on their own, so each goes through here: an unexpected error
     * is recorded for the crash report and the app keeps running.
     */
    private fun guard(where: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.e(TAG, "error in $where", e)
            CrashReport.recordProblem(this, where, e)
            val wasBusy = busy
            busy = false
            try {
                setStatus("Something went wrong ($where)")
                if (wasBusy) speaker.say("Sorry, something went wrong") {}
            } catch (ignored: Throwable) {
            }
            scheduleJarvisUpdate(2000)
        }
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
                    useSamsung() -> samsung.play { ok ->
                        guard("Samsung Music play") { if (ok) finished("Samsung Music") else shuffleAll() }
                    }
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
            is Command.Call -> placeCall(command, resumeIfNeeded)
            Command.AnswerCall -> {
                if (!phone.answer()) say("Allow phone permissions in the Glasses Tunes app so I can answer calls") {}
                finished("Answered")
            }
            Command.DeclineCall -> {
                if (!phone.decline()) say("Allow phone permissions in the Glasses Tunes app so I can decline calls") {}
                finished("Declined")
            }
            is Command.SendMessage -> sendMessage(command, resumeIfNeeded)
            is Command.Reply -> reply(command, resumeIfNeeded)
            is Command.ReadMessages -> readMessages(command, resumeIfNeeded)
            Command.UiBack, Command.UiHome, Command.UiNotifications, Command.UiRecents,
            is Command.UiScroll, is Command.UiTap, is Command.UiType -> controlScreen(command, resumeIfNeeded)
            is Command.Ask -> askQuestion(command.question, resumeIfNeeded)
            // Anything that isn't a command is treated as a question, once Gemini is set up.
            is Command.Unknown -> if (Prefs.geminiKey(this).isNotBlank()) {
                askQuestion(command.heard, resumeIfNeeded)
            } else {
                say("Sorry, I heard: ${command.heard}. To ask questions, add a Gemini key in the Glasses Tunes app.") {
                    resumeIfNeeded()
                    finished("Didn't understand \"${command.heard}\"")
                }
            }
        }
    }

    // ---- questions ------------------------------------------------------------

    private fun askQuestion(question: String, done: () -> Unit) {
        // Forget the conversation after a few quiet minutes.
        if (SystemClock.elapsedRealtime() - lastQuestionAt > 5 * 60_000) conversation.clear()
        setStatus("Thinking about \"$question\"…")
        assistant.ask(question, conversation.toList()) { answer ->
            guard("answering a question") {
                lastQuestionAt = SystemClock.elapsedRealtime()
                when (answer) {
                    is AskAssistant.Answer.Spoken -> {
                        conversation += AskAssistant.Turn(question, answer.text)
                        while (conversation.size > 3) conversation.removeAt(0)
                        end(answer.text, done)
                    }
                    is AskAssistant.Answer.Failed -> end(answer.reason, done)
                }
            }
        }
    }

    // ---- conversations ------------------------------------------------------

    /** Ask a question and listen for the answer (null if nothing was heard). */
    private fun ask(question: String, onAnswer: (String?) -> Unit) {
        say(question) {
            setStatus("Listening…")
            voice.listen { result ->
                guard("handling an answer") { onAnswer((result as? GlassesVoice.Result.Heard)?.phrases?.firstOrNull()) }
            }
        }
    }

    /** Anything that sends on your behalf is read back first and needs a "yes". */
    private fun confirm(question: String, done: () -> Unit, onYes: () -> Unit) {
        ask(question) { answer ->
            if (answer != null && CommandParser.isYes(answer)) onYes() else say("Cancelled") { done(); finished("Cancelled") }
        }
    }

    private fun end(message: String, done: () -> Unit) = say(message) {
        done()
        finished(message)
    }

    private fun withContacts(block: (List<Contact>) -> Unit) {
        if (!phone.canReadContacts) {
            end("Allow contacts in the Glasses Tunes app first") {}
            return
        }
        io.execute {
            val list = try {
                phone.contacts()
            } catch (e: RuntimeException) {
                CrashReport.recordProblem(this, "reading contacts", e)
                emptyList()
            }
            main.post { guard("using contacts") { block(list) } }
        }
    }

    // ---- calls ----------------------------------------------------------------

    private fun placeCall(command: Command.Call, done: () -> Unit) {
        if (!phone.canCall) return end("Allow phone calls in the Glasses Tunes app first", done)
        ContactMatcher.asPhoneNumber(command.who)?.let { number ->
            say("Calling ${number.toList().joinToString(" ")}") {
                phone.call(number)
                finished("Calling $number")
            }
            return
        }
        withContacts { contacts ->
            val contact = ContactMatcher.find(contacts, command.who)
                ?: return@withContacts end("I couldn't find ${command.who} in your contacts", done)
            val number = ContactMatcher.pickNumber(contact, command.numberKind)
                ?: return@withContacts end("${contact.name} doesn't have a phone number", done)
            val which = if (contact.phones.size > 1) " on ${number.kind}" else ""
            val call = {
                say("Calling ${contact.name}$which") {
                    if (phone.call(number.number)) finished("Calling ${contact.name}")
                    else end("I couldn't place the call", done)
                }
            }
            // Ask first only when the name was a loose match.
            if (ContactMatcher.confidence(contact, command.who) >= 85) call()
            else confirm("Call ${contact.name}$which?", done) { call() }
        }
    }

    // ---- sending messages ---------------------------------------------------

    private fun sendMessage(command: Command.SendMessage, done: () -> Unit) {
        if (command.app == MessageApp.WHATSAPP && !phone.hasWhatsApp()) return end("WhatsApp isn't installed", done)
        withContacts { contacts ->
            val (contact, message) = when {
                command.message != null -> ContactMatcher.find(contacts, command.recipientAndMessage) to command.message
                else -> ContactMatcher.split(contacts, command.recipientAndMessage)
                    ?: (ContactMatcher.find(contacts, command.recipientAndMessage) to null)
            }
            if (contact == null) return@withContacts end("I couldn't find that person in your contacts", done)
            if (message.isNullOrBlank()) {
                ask("What's the message for ${contact.name}?") { answer ->
                    if (answer.isNullOrBlank()) end("Cancelled", done) else confirmAndSend(command.app, contact, answer, done)
                }
            } else {
                confirmAndSend(command.app, contact, message, done)
            }
        }
    }

    private fun confirmAndSend(app: MessageApp, contact: Contact, raw: String, done: () -> Unit) {
        val message = ContactMatcher.sentence(raw)
        when (app) {
            MessageApp.SMS -> {
                // If Google Messages (or Samsung Messages) has this conversation open in a
                // notification, answer there: that keeps RCS chat instead of falling back to SMS.
                val conversation = MessageInbox.latest(contact.name, needsReply = true)
                    ?.takeIf { it.packageName in TEXTING_APPS }
                if (conversation != null) {
                    confirm("Text ${contact.name}: $message. Send it?", done) {
                        end(if (MessageInbox.reply(this, conversation, message)) "Sent" else "The text didn't send", done)
                    }
                    return
                }
                if (!phone.canText) return end("Allow text messages in the Glasses Tunes app first", done)
                val number = ContactMatcher.pickNumber(contact, "mobile")
                    ?: return end("${contact.name} doesn't have a phone number", done)
                confirm("Text ${contact.name}: $message. Send it?", done) {
                    end(if (phone.sendSms(number.number, message)) "Sent" else "The text didn't send", done)
                }
            }
            MessageApp.WHATSAPP -> {
                val jid = contact.whatsappJid
                    ?: return end("I can't find ${contact.name} on WhatsApp", done)
                confirm("WhatsApp ${contact.name}: $message. Send it?", done) { sendWhatsApp(jid, message, done) }
            }
        }
    }

    /** WhatsApp has no sending API: open the chat with the text filled in and press Send. */
    private fun sendWhatsApp(jid: String, message: String, done: () -> Unit) {
        if (launcher.isLocked()) {
            return end("Unlock your phone to send a new WhatsApp message. Replies work while it's locked.", done)
        }
        phone.openWhatsAppChat(jid, message, launcher)
        val screen = ScreenControlService.instance
            ?: return end("I opened the chat. Turn on screen control in the app so I can press send for you.", done)
        var tries = 0
        val press = object : Runnable {
            override fun run() = guard("pressing WhatsApp send") {
                when {
                    screen.pressWhatsAppSend() -> end("Sent", done)
                    ++tries < 12 -> main.postDelayed(this, 500)
                    else -> end("I opened the chat but couldn't press send", done)
                }
            }
        }
        main.postDelayed(press, 1200)
    }

    // ---- incoming messages --------------------------------------------------

    private fun reply(command: Command.Reply, done: () -> Unit) {
        var message = command.message
        val entry = when {
            command.recipientAndMessage == null ->
                lastHeardMessage?.takeIf { it.canReply && MessageInbox.all().any { e -> e.key == it.key } }
                    ?: MessageInbox.latest(needsReply = true)
            message != null -> MessageInbox.latest(command.recipientAndMessage, needsReply = true)
            else -> {
                val split = ContactMatcher.splitNames(MessageInbox.senders(), command.recipientAndMessage)
                if (split != null) {
                    message = split.second
                    MessageInbox.latest(split.first, needsReply = true)
                } else {
                    MessageInbox.latest(command.recipientAndMessage, needsReply = true)
                }
            }
        } ?: return end("I don't see a message I can reply to", done)
        val send = { text: String ->
            val reply = ContactMatcher.sentence(text)
            confirm("Reply to ${entry.sender} on ${entry.appLabel}: $reply. Send it?", done) {
                end(if (MessageInbox.reply(this, entry, reply)) "Sent" else "That message can't be replied to anymore", done)
            }
        }
        if (message.isNullOrBlank()) {
            ask("What's your reply to ${entry.sender}?") { answer ->
                if (answer.isNullOrBlank()) end("Cancelled", done) else send(answer)
            }
        } else {
            send(message)
        }
    }

    private fun readMessages(command: Command.ReadMessages, done: () -> Unit) {
        val messages = if (command.from != null) {
            MessageInbox.all().filter { LibraryMatcher.score(it.sender, command.from) >= 70 }.takeLast(3)
        } else {
            MessageInbox.unread().takeLast(5)
        }
        if (messages.isEmpty()) {
            return end(if (command.from != null) "No recent messages from ${command.from}" else "No new messages", done)
        }
        messages.forEach { MessageInbox.markRead(it) }
        lastHeardMessage = messages.last()
        val text = messages.joinToString(". ") { "${it.sender} on ${it.appLabel}: ${it.text}" }
        end(text, done)
    }

    /** Called for each new message notification. */
    fun onNewMessage(entry: InboxEntry) = guard("announcing a message") {
        if (!Prefs.announceMessages(this) || busy) return@guard
        MessageInbox.markRead(entry)
        lastHeardMessage = entry
        say("${entry.sender} on ${entry.appLabel}: ${entry.text}") { setStatus("Ready") }
    }

    // ---- controlling the screen ---------------------------------------------

    private fun controlScreen(command: Command, done: () -> Unit) {
        val screen = ScreenControlService.instance
            ?: return end("Turn on screen control for Glasses Tunes in the app first", done)
        if (launcher.isLocked()) return end("Unlock your phone first", done)
        when (command) {
            Command.UiBack -> screen.back()
            Command.UiHome -> screen.home()
            Command.UiNotifications -> screen.notifications()
            Command.UiRecents -> screen.recents()
            is Command.UiScroll -> if (!screen.scroll(command.down)) return end("There's nothing to scroll", done)
            is Command.UiTap -> {
                val tapped = screen.tap(command.label)
                    ?: return end("I couldn't find ${command.label} on the screen", done)
                done()
                finished("Tapped $tapped")
                return
            }
            is Command.UiType -> if (!screen.type(ContactMatcher.sentence(command.text))) {
                return end("There's no text box to type in", done)
            }
            else -> Unit
        }
        done()
        finished("Done")
    }

    private fun useSamsung() = Prefs.preferSamsung(this) && samsung.isInstalled()

    /**
     * Finds the song in the phone's own music library first: that's the same
     * library Samsung Music plays, and our matching forgives mishearings.
     * Samsung Music is then handed that exact song; if it won't take it (it
     * often refuses requests from other apps), the built-in player plays it.
     */
    private fun playRequest(request: PlayRequest, onNotFound: () -> Unit) {
        val spoken = request.query + (request.artist?.let { " by $it" } ?: "")
        withLibrary { songs ->
            val selection = LibraryMatcher.select(songs, request)
            if (selection == null) {
                notInLibrary(request, spoken, songs.isEmpty(), onNotFound)
                return@withLibrary
            }
            say("Playing ${selection.description}") {
                if (!useSamsung()) {
                    playLocalSelection(selection)
                    finished("Playing ${selection.description}")
                    return@say
                }
                samsung.playTrack(selection.tracks.first()) { ok ->
                    guard("Samsung Music") {
                        if (ok) {
                            lastUsedLocal = false
                            finished("Samsung Music: ${selection.description}")
                        } else {
                            playLocalSelection(selection)
                            finished("Playing ${selection.description}")
                        }
                    }
                }
            }
        }
    }

    /** Not among the phone's songs. Samsung Music may still know it; otherwise say why it failed. */
    private fun notInLibrary(request: PlayRequest, spoken: String, libraryEmpty: Boolean, onNotFound: () -> Unit) {
        val giveUp = {
            say(if (libraryEmpty) libraryProblem() else "I couldn't find $spoken in your music") {
                onNotFound()
                finished("Couldn't find \"$spoken\"")
            }
        }
        if (!useSamsung()) return giveUp()
        samsung.playFromSearch(request) { ok ->
            guard("Samsung Music search") {
                if (ok) {
                    lastUsedLocal = false
                    finished("Samsung Music: $spoken")
                } else {
                    giveUp()
                }
            }
        }
    }

    /** Why the music library is empty, as something to say out loud. */
    private fun libraryProblem(): String {
        val permission = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "I can't see your music. Open Glasses Tunes and allow access to music and audio."
        } else {
            "I didn't find any music files on your phone"
        }
    }

    /** The phone's songs, reloading first if the list is empty (e.g. music access was just granted). */
    private fun withLibrary(block: (List<Track>) -> Unit) {
        if (library.isNotEmpty()) return block(library)
        io.execute {
            val songs = try {
                LocalLibrary.load(this)
            } catch (e: RuntimeException) {
                CrashReport.recordProblem(this, "loading music library", e)
                emptyList()
            }
            main.post {
                library = songs
                guard("using the music library") { block(songs) }
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
                say(libraryProblem()) { finished("No music found") }
            }
        }
        if (useSamsung()) {
            samsung.shuffleAll { ok ->
                guard("Samsung Music shuffle") {
                    if (ok) {
                        lastUsedLocal = false
                        finished("Samsung Music: shuffle all")
                    } else {
                        localShuffle()
                    }
                }
            }
        } else {
            localShuffle()
        }
    }

    private fun playLocalSelection(selection: Selection) {
        lastUsedLocal = true
        local.play(selection.tracks)
        detectors.getOrPut(LOCAL_KEY) { TapTapDetector() }.reset(true)
    }

    private fun say(text: String, then: () -> Unit) {
        if (text.isEmpty()) {
            then()
            return
        }
        setStatus(text)
        val next = { guard("after saying \"${text.take(30)}\"", then) }
        if (Prefs.speakReplies(this)) speaker.say(text, next) else next()
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

    /**
     * Returns false if Android won't let us run in the foreground at all. That
     * happens when Android restarts the service by itself in the background
     * (e.g. after Samsung's battery saver stopped it): Android 12+ refuses, and
     * we must stop instead of crashing.
     */
    private fun goForeground(): Boolean {
        createChannel(this)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT < 30) {
            return try {
                startForeground(NOTIFICATION_ID, notification)
                micReady = true
                true
            } catch (e: RuntimeException) {
                false
            }
        }
        try {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            micReady = true
            return true
        } catch (e: SecurityException) {
            // Android 14+: started from the background, so no mic until the user taps something of ours.
            Log.w(TAG, "microphone not allowed yet", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "not allowed to run in the foreground", e)
            return micReady // already in the foreground from an earlier call
        }
        micReady = false
        return try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "not allowed to run in the foreground", e)
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
        private val TEXTING_APPS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging")
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
            try {
                context.startForegroundService(Intent(context, GlassesService::class.java))
            } catch (e: RuntimeException) {
                // e.g. ForegroundServiceStartNotAllowedException if Android thinks we're in the background.
                Log.w(TAG, "couldn't start the connector", e)
                CrashReport.recordProblem(context, "starting the connector", e)
                AutoStart.promptRestart(context)
            }
        }
    }
}
