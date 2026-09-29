package com.knightdx.glassestunes

import org.junit.Assert.assertEquals
import org.junit.Test

/** "play <song> by <artist>" the way speech recognition actually writes it, against real-world tags. */
class SpokenSongTest {
    private val lib = listOf(
        Track(1, "Bohemian Rhapsody", "Queen", "A Night at the Opera", 11),
        Track(2, "Don't Stop Me Now", "Queen", "Jazz", 12),
        Track(3, "God's Plan", "Drake", "Scorpion", 5),
        Track(4, "HUMBLE.", "Kendrick Lamar", "DAMN.", 8),
        Track(5, "Halo", "Beyoncé", "I Am... Sasha Fierce", 2),
        Track(6, "Blinding Lights", "The Weeknd", "After Hours", 9),
        Track(7, "Lose Yourself", "Eminem", "8 Mile", 1),
        Track(8, "Sweet Child O' Mine", "Guns N' Roses", "Appetite for Destruction", 9),
        Track(9, "Old Town Road (feat. Billy Ray Cyrus) - Remix", "Lil Nas X", "7 EP", 2),
        Track(10, "Mr. Brightside", "The Killers", "Hot Fuss", 2),
        Track(11, "Track 01", "<unknown>", "Downloads", 0),
    )

    private fun firstId(spoken: String): Long? {
        val cmd = CommandParser.parse(spoken) as? Command.Play ?: return -1
        return LibraryMatcher.select(lib, cmd.request)?.tracks?.first()?.id
    }

    @Test fun exactNames() {
        assertEquals(1L, firstId("play Bohemian Rhapsody by Queen"))
        assertEquals(2L, firstId("play Don't Stop Me Now by Queen"))
        assertEquals(3L, firstId("play God's Plan by Drake"))
        assertEquals(7L, firstId("play Lose Yourself by Eminem"))
        assertEquals(10L, firstId("play Mr. Brightside by The Killers"))
    }

    @Test fun howSpeechRecognitionWritesThem() {
        assertEquals(4L, firstId("play humble by Kendrick Lamar"))
        assertEquals(5L, firstId("play Halo by Beyonce"))
        assertEquals(6L, firstId("play Blinding Lights by the weekend"))
        assertEquals(8L, firstId("play sweet child of mine by guns and roses"))
        assertEquals(9L, firstId("play Old Town Road by Lil Nas X"))
        assertEquals(10L, firstId("play mister brightside by the killers"))
        assertEquals(1L, firstId("play bohemian rhapsody from queen"))
        assertEquals(1L, firstId("play the song bohemian rhapsody by queen"))
    }

    @Test fun misheardSlightly() {
        assertEquals(1L, firstId("play bohemian rapsody by queen"))
        assertEquals(4L, firstId("play humble by kendrick llamar"))
    }
}
