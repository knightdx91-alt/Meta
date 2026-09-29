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
    object ShuffleAll : Command()
    object Resume : Command()
    object Pause : Command()
    object Next : Command()
    object Previous : Command()
    object VolumeUp : Command()
    object VolumeDown : Command()
    object WhatsPlaying : Command()
    data class Unknown(val heard: String) : Command()
}

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
    private val resumeWords = setOf("play", "resume", "continue", "unpause", "play music", "resume music", "keep playing", "go", "start", "start music", "play it")
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

    fun parse(raw: String): Command {
        val text = normalize(raw)
        if (text.isEmpty()) return Command.Unknown(raw)

        when (text) {
            in pauseWords -> return Command.Pause
            in resumeWords -> return Command.Resume
            in nextWords -> return Command.Next
            in prevWords -> return Command.Previous
            in volUpWords -> return Command.VolumeUp
            in volDownWords -> return Command.VolumeDown
            in whatWords -> return Command.WhatsPlaying
            in shuffleAllWords -> return Command.ShuffleAll
        }

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

        return Command.Unknown(raw)
    }

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
        Regex("^(.+?)\\s+by\\s+(.+)$").find(t)?.let {
            return PlayRequest(clean(it.groupValues[1]), Focus.SONG, artist = clean(it.groupValues[2]))
        }
        return PlayRequest(clean(t), if (isSong) Focus.SONG else Focus.ANY)
    }

    private fun clean(s: String) = s.trim().removeSuffix(" songs").removeSuffix(" music").trim()

    fun normalize(raw: String): String {
        var t = raw.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^a-z0-9' &]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Filler can be stacked ("hey can you ..."), so strip repeatedly.
        repeat(3) {
            t = t.replace(leadingFiller, "").replace(trailingFiller, "").trim()
        }
        return t
    }
}
