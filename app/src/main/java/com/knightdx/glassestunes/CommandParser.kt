package com.knightdx.glassestunes

/** What the user asked to play, when they asked for something specific. */
enum class Focus { ANY, ARTIST, ALBUM, SONG, PLAYLIST, GENRE }

data class PlayRequest(
    val query: String,
    val focus: Focus = Focus.ANY,
    /** For "play <song> by <artist>". */
    val artist: String? = null,
    val shuffle: Boolean = false,
)

sealed class Command {
    data class Play(val request: PlayRequest) : Command()
    /**
     * "play an Italian song": the title is asked for and heard in that language,
     * so speech recognition spells it right. [title] is set if one was already said.
     */
    data class PlayInLanguage(val languageTag: String, val languageName: String, val title: String? = null) : Command()
    object ShuffleAll : Command()
    object Resume : Command()
    object Pause : Command()
    object Next : Command()
    object Previous : Command()
    object VolumeUp : Command()
    object VolumeDown : Command()
    object WhatsPlaying : Command()
    /** Open the phone's assistant (Gemini, if it's set as default). */
    object Assistant : Command()
    data class OpenApp(val name: String) : Command()

    // ---- phone & messages ----
    /** "call mom", "call john on mobile", "call 555 1234". */
    data class Call(val who: String, val numberKind: String? = null) : Command()
    object AnswerCall : Command()
    object DeclineCall : Command()
    /**
     * "text mom I'm on my way". The name/message boundary depends on your
     * contacts, so [recipientAndMessage] is split later; [message] is set when
     * the phrase made it explicit ("text mom saying ...").
     */
    data class SendMessage(val app: MessageApp, val recipientAndMessage: String, val message: String? = null) : Command()
    /** "reply sounds good" (message only), or "reply to john sounds good" (recipient first). */
    data class Reply(val recipientAndMessage: String? = null, val message: String? = null) : Command()
    data class ReadMessages(val from: String? = null) : Command()

    // ---- controlling the screen (needs the accessibility service) ----
    object UiBack : Command()
    object UiHome : Command()
    object UiNotifications : Command()
    object UiRecents : Command()
    data class UiScroll(val down: Boolean) : Command()
    data class UiTap(val label: String) : Command()
    data class UiType(val text: String) : Command()

    /** A question for Gemini ("google who won the game", or anything that isn't a command). */
    data class Ask(val question: String) : Command()

    data class Unknown(val heard: String) : Command()
}

enum class MessageApp { SMS, WHATSAPP }

/**
 * Turns a spoken phrase ("play the album thriller", "skip", "shuffle some queen")
 * into a [Command]. Pure Kotlin so it can be unit tested on the JVM.
 */
object CommandParser {

    private val leadingFiller = Regex("^(hey|ok|okay|yo|um|uh|please|can you|could you|would you|i want to|i wanna|let's|lets)\\s+")
    private val trailingFiller = Regex(
        "\\s+(please|for me|now|on samsung music|in samsung music|with samsung music|on samsung|" +
            "from my phone|on my phone|from my library|on the glasses|on my glasses)$"
    )

    private val pauseWords = setOf("pause", "stop", "pause music", "stop music", "pause the music", "stop the music", "hold on", "quiet", "shut up")
    private val resumeWords = setOf("play", "resume", "continue", "unpause", "play music", "resume music", "keep playing", "go", "start", "start music", "play it", "press play", "hit play")
    private val nextWords = setOf("next", "skip", "next song", "next track", "skip song", "skip this", "skip it", "skip track", "skip this song", "next one")
    private val prevWords = setOf("previous", "back", "go back", "last song", "previous song", "previous track", "replay", "play that again", "restart song", "back one")
    private val volUpWords = setOf("volume up", "louder", "turn it up", "turn up", "turn the volume up", "increase volume", "raise volume", "turn up the volume")
    private val volDownWords = setOf("volume down", "quieter", "softer", "turn it down", "turn down", "turn the volume down", "decrease volume", "lower volume", "turn down the volume")
    private val whatWords = setOf("what's playing", "whats playing", "what is playing", "what song is this", "what's this song", "whats this song", "what is this song", "what is this", "who is this", "who's this", "who sings this", "what song is playing")
    private val shuffleAllWords = setOf(
        "shuffle", "shuffle all", "shuffle everything", "shuffle my music", "shuffle my songs", "shuffle my library",
        "play everything", "play all", "play my music", "play my songs", "play something", "play some music",
        "play anything", "play all my music", "play my library", "shuffle music", "shuffle songs", "shuffle all songs",
    )

    // "hey"/"ok" are stripped by normalize(), so "hey google" arrives as "google".
    private val assistantWords = setOf(
        "gemini", "gemini live", "talk to gemini", "open gemini", "open gemini live", "start gemini live", "go live",
        "ask gemini", "google", "talk to google", "ask google", "assistant", "google assistant", "open assistant",
        "open google assistant", "open the assistant",
    )

    private val answerWords = setOf("answer", "answer it", "answer the call", "answer the phone", "answer call", "pick up", "pick it up", "accept", "accept the call", "accept call")
    private val declineWords = setOf("decline", "decline it", "decline the call", "decline call", "reject", "reject the call", "reject call", "ignore", "ignore it", "ignore the call", "ignore call", "send to voicemail")
    private val homeWords = setOf("go home", "home screen", "go to the home screen", "go to home screen", "show home screen", "press home", "home button")
    private val backWords = setOf("press back", "back button", "go back a screen", "previous screen", "hit back", "tap back")
    private val notificationWords = setOf("notifications", "show notifications", "open notifications", "show my notifications", "open the notifications", "pull down notifications", "notification shade")
    private val recentsWords = setOf("recent apps", "recents", "show recent apps", "open recent apps", "show recents", "app switcher")
    private val readWords = setOf(
        "read my messages", "read messages", "read my texts", "read texts", "read my new messages", "read new messages",
        "any messages", "any new messages", "do i have any messages", "do i have any new messages", "do i have messages",
        "read the message", "read the last message", "read my last message", "read last message", "what did they say",
        "read it", "check my messages", "check messages",
    )
    private val yesWords = setOf(
        "yes", "yeah", "yep", "yup", "sure", "send", "send it", "ok", "okay", "do it", "go ahead", "correct", "confirm",
        "yes please", "yes send it", "yeah send it", "that's right", "thats right", "right", "affirmative", "call", "call them",
    )

    /** For confirmations ("Send it?"). */
    fun isYes(raw: String): Boolean = normalizeLight(raw).removeSuffix(" please").trim() in yesWords

    /** Song languages you can ask for by name, with their speech-recognition language tags. */
    val songLanguages = mapOf(
        "italian" to "it-IT", "norwegian" to "nb-NO", "spanish" to "es-ES", "french" to "fr-FR", "german" to "de-DE",
        "portuguese" to "pt-BR", "swedish" to "sv-SE", "danish" to "da-DK", "dutch" to "nl-NL", "finnish" to "fi-FI",
        "polish" to "pl-PL", "greek" to "el-GR", "turkish" to "tr-TR",
    )

    private fun parseLanguagePlay(text: String): Command? {
        val names = songLanguages.keys.joinToString("|")
        fun cmd(name: String, title: String?) =
            Command.PlayInLanguage(songLanguages.getValue(name), name.replaceFirstChar { it.uppercase() }, title?.trim()?.ifBlank { null })
        // "italian", "italian song", "play an italian song", "play something in norwegian", "play some italian music"
        Regex("^(?:(?:play|put on)\\s+)?(?:an?\\s+|some\\s+|my\\s+|something\\s+in\\s+|in\\s+)?($names)(?:\\s+(?:song|songs|music|track|tune))?$")
            .find(text)?.let { return cmd(it.groupValues[1], null) }
        // "play the italian song vivo per lei", "play italian song vivo per lei"
        Regex("^(?:play|put on)\\s+(?:the\\s+|an?\\s+)?($names)\\s+(?:song|track)\\s+(.+)$")
            .find(text)?.let { return cmd(it.groupValues[1], it.groupValues[2]) }
        // "play vivo per lei in italian"
        Regex("^(?:play|put on)\\s+(.+?)\\s+in\\s+($names)$")
            .find(text)?.let { return cmd(it.groupValues[2], it.groupValues[1]) }
        return null
    }

    fun parse(raw: String): Command {
        val text = normalize(raw)
        if (text.isEmpty()) return Command.Unknown(raw)
        // Messages keep their trailing words ("... I'll be there now").
        val light = normalizeLight(raw)

        when (text) {
            in pauseWords -> return Command.Pause
            in resumeWords -> return Command.Resume
            in nextWords -> return Command.Next
            in prevWords -> return Command.Previous
            in volUpWords -> return Command.VolumeUp
            in volDownWords -> return Command.VolumeDown
            in whatWords -> return Command.WhatsPlaying
            in shuffleAllWords -> return Command.ShuffleAll
            in assistantWords -> return Command.Assistant
            in answerWords -> return Command.AnswerCall
            in declineWords -> return Command.DeclineCall
            in homeWords -> return Command.UiHome
            in backWords -> return Command.UiBack
            in notificationWords -> return Command.UiNotifications
            in recentsWords -> return Command.UiRecents
            in readWords -> return Command.ReadMessages()
            "scroll down", "page down", "scroll" -> return Command.UiScroll(down = true)
            "scroll up", "page up" -> return Command.UiScroll(down = false)
            "reply", "reply to it", "reply to that", "respond" -> return Command.Reply()
        }

        Regex("^(?:google|search for|search|look up|ask google|ask jarvis|ask)\\s+(.+)$").find(light)?.let {
            return Command.Ask(it.groupValues[1])
        }

        parseMessaging(light)?.let { return it }
        parseCall(text)?.let { return it }

        parseLanguagePlay(text)?.let { return it }

        val shuffleMatch = Regex("^(shuffle|play shuffled|shuffle play)\\s+(.+)$").find(text)
        if (shuffleMatch != null) {
            return Command.Play(parseTarget(stripSome(shuffleMatch.groupValues[2])).copy(shuffle = true))
        }

        val playMatch = Regex("^(play|put on|listen to|start playing|throw on)\\s+(.+)$").find(text)
        if (playMatch != null) {
            val target = playMatch.groupValues[2]
            val shuffled = Regex("^(.+?)\\s+(on shuffle|shuffled)$").find(target)
            return if (shuffled != null) {
                Command.Play(parseTarget(stripSome(shuffled.groupValues[1])).copy(shuffle = true))
            } else {
                Command.Play(parseTarget(stripSome(target)))
            }
        }

        val openMatch = Regex("^(open|launch|start|run|go to|switch to|pull up|bring up)\\s+(the\\s+)?(.+?)(\\s+app)?$").find(text)
        if (openMatch != null) return Command.OpenApp(openMatch.groupValues[3])

        return Command.Unknown(raw)
    }

    private fun parseCall(text: String): Command? {
        val kinds = "mobile|cell|cellphone|cell phone|work|office|home|house"
        Regex("^(?:call|phone|dial|ring|make a call to|place a call to|facetime)\\s+(.+?)(?:\\s+(?:on|at)\\s+(?:(?:his|her|their|the|my)\\s+)?($kinds)(?:\\s+(?:number|phone))?)?$")
            .find(text)?.let { return Command.Call(it.groupValues[1].trim(), kindOf(it.groupValues[2])) }
        Regex("^give\\s+(.+?)\\s+a\\s+(?:call|ring)$").find(text)?.let { return Command.Call(it.groupValues[1].trim()) }
        return null
    }

    private fun kindOf(spoken: String): String? = when (spoken) {
        "" -> null
        "cell", "cellphone", "cell phone" -> "mobile"
        "office" -> "work"
        "house" -> "home"
        else -> spoken
    }

    private fun parseMessaging(light: String): Command? {
        // Screen control with free text.
        Regex("^(?:tap|click|press|select|touch|hit)\\s+(?:on\\s+)?(?:the\\s+)?(.+?)(?:\\s+button)?$").find(light)?.let {
            return Command.UiTap(it.groupValues[1])
        }
        Regex("^(?:type|write|enter)\\s+(.+)$").find(light)?.let { return Command.UiType(it.groupValues[1]) }

        // Reading.
        Regex("^(?:read|what did|what's|whats)\\s+(?:my\\s+)?(?:messages?|texts?)\\s+from\\s+(.+)$").find(light)?.let {
            return Command.ReadMessages(it.groupValues[1])
        }
        Regex("^what did\\s+(.+?)\\s+(?:say|send|text|write)(?:\\s+me)?$").find(light)?.let { return Command.ReadMessages(it.groupValues[1]) }
        Regex("^read\\s+(.+?)'s\\s+(?:messages?|texts?)$").find(light)?.let { return Command.ReadMessages(it.groupValues[1]) }

        // Replying to the last message.
        Regex("^(?:reply|respond|answer)\\s+(to\\s+)?(.+)$").find(light)?.let {
            return if (it.groupValues[1].isNotEmpty()) {
                val (recipientAndMessage, message) = splitExplicit(it.groupValues[2])
                Command.Reply(recipientAndMessage = recipientAndMessage, message = message)
            } else {
                Command.Reply(message = stripSaying(it.groupValues[2]))
            }
        }

        // New messages.
        var app = MessageApp.SMS
        var t = light
        // "... on WhatsApp" right after the name, or at the very end.
        Regex("\\s+(?:on|via|over|through|using|in)\\s+whatsapp(?=$|\\s+(?:saying|that|to say|and say|with)\\s)").find(t)?.let {
            app = MessageApp.WHATSAPP
            t = t.removeRange(it.range).trim()
        }
        val verbs = "text|message|sms|whatsapp|send a text to|send a message to|send a text message to|send an sms to|" +
            "send a whatsapp to|send a whatsapp message to|send whatsapp to|send message to|send text to|tell|let"
        val m = Regex("^($verbs)\\s+(.+)$").find(t)
            ?: Regex("^send\\s+(.+?)\\s+an?\\s+(text|message|whatsapp|whatsapp message|text message)(?:\\s+(.+))?$").find(t)?.let { sm ->
                val kind = sm.groupValues[2]
                if (kind.startsWith("whatsapp")) app = MessageApp.WHATSAPP
                val (who, msg) = sm.groupValues[1] to sm.groupValues[3].ifBlank { null }?.let { stripSaying(it) }
                return Command.SendMessage(app, who, msg)
            }
            ?: return null
        val verb = m.groupValues[1]
        if (verb.contains("whatsapp")) app = MessageApp.WHATSAPP
        var rest = m.groupValues[2]
        if (verb == "let") {
            // "let mom know I'm on my way"
            val know = Regex("^(.+?)\\s+know\\s+(?:that\\s+)?(.+)$").find(rest) ?: return null
            return Command.SendMessage(app, know.groupValues[1], know.groupValues[2])
        }
        val (recipientAndMessage, message) = splitExplicit(rest)
        rest = recipientAndMessage
        return Command.SendMessage(app, rest, message)
    }

    /** "mom saying hi" -> ("mom", "hi"); "mom hi" -> ("mom hi", null) to be split using contacts. */
    private fun splitExplicit(s: String): Pair<String, String?> {
        val sep = Regex("\\s+(?:saying|that says|that|to say|and say|and tell (?:him|her|them)|with the message|with)\\s+").find(s)
            ?: return s to null
        return s.substring(0, sep.range.first).trim() to s.substring(sep.range.last + 1).trim()
    }

    private fun stripSaying(s: String) = s.replace(Regex("^(?:saying|that|with)\\s+"), "").trim()

    private fun stripSome(s: String) = s.replace(Regex("^(some|a little|a bit of|a few songs by|a few|more)\\s+"), "").trim()

    private fun parseTarget(target: String): PlayRequest {
        var t = target.trim()

        // "the album X" / "album X" / "X album" / "the X album"
        Regex("^(the )?album\\s+(.+)$").find(t)?.let { return PlayRequest(clean(it.groupValues[2]), Focus.ALBUM) }
        Regex("^(the )?(.+?)\\s+album$").find(t)?.let { return PlayRequest(clean(it.groupValues[2]), Focus.ALBUM) }

        // "my X playlist" / "the playlist X" / "playlist X"
        Regex("^(my |the )?playlist\\s+(.+)$").find(t)?.let { return PlayRequest(clean(it.groupValues[2]), Focus.PLAYLIST) }
        Regex("^(my |the )?(.+?)\\s+playlist$").find(t)?.let { return PlayRequest(clean(it.groupValues[2]), Focus.PLAYLIST) }

        // "the artist X" / "artist X" / "songs by X" / "music by X" / "anything by X"
        Regex("^(the )?(artist|band|singer)\\s+(.+)$").find(t)?.let { return PlayRequest(clean(it.groupValues[3]), Focus.ARTIST) }
        Regex("^(songs|music|stuff|anything|something|tracks|hits|the hits)\\s+(by|from)\\s+(.+)$").find(t)?.let {
            return PlayRequest(clean(it.groupValues[3]), Focus.ARTIST)
        }

        // "genre X" / "X music" (e.g. "jazz music")
        Regex("^(the )?genre\\s+(.+)$").find(t)?.let { return PlayRequest(clean(it.groupValues[2]), Focus.GENRE) }

        // "the song X by Y" / "X by Y"
        val song = Regex("^(the )?(song|track)\\s+(.+)$").find(t)
        val isSong = song != null
        if (song != null) t = song.groupValues[3]
        Regex("^(.+?)\\s+(?:by|from)\\s+(.+)$").find(t)?.let {
            return PlayRequest(clean(it.groupValues[1]), Focus.SONG, artist = clean(it.groupValues[2]))
        }
        return PlayRequest(clean(t), if (isSong) Focus.SONG else Focus.ANY)
    }

    private fun clean(s: String) = s.trim().removeSuffix(" songs").removeSuffix(" music").trim()

    /** Lowercase, drop punctuation and leading filler, but keep every word of a message. */
    fun normalizeLight(raw: String): String {
        var t = raw.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^\\p{L}0-9' &+]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        repeat(3) { t = t.replace(leadingFiller, "").trim() }
        return t
    }

    fun normalize(raw: String): String {
        var t = raw.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^\\p{L}0-9' &]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Filler can be stacked ("hey can you ..."), so strip repeatedly.
        repeat(3) {
            t = t.replace(leadingFiller, "").replace(trailingFiller, "").trim()
        }
        return t
    }
}
