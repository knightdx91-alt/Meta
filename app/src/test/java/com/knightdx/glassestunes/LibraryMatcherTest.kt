package com.knightdx.glassestunes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMatcherTest {
    private val lib = listOf(
        Track(1, "Bohemian Rhapsody", "Queen", "A Night at the Opera", 11),
        Track(2, "Love of My Life", "Queen", "A Night at the Opera", 9),
        Track(3, "Under Pressure", "Queen & David Bowie", "Hot Space", 11),
        Track(4, "Billie Jean", "Michael Jackson", "Thriller", 6),
        Track(5, "Thriller", "Michael Jackson", "Thriller", 4),
        Track(6, "Beat It", "Michael Jackson", "Thriller", 5),
        Track(7, "God's Plan", "Drake", "Scorpion", 5),
        Track(8, "Hotline Bling", "Drake", "Views", 20),
    )

    private fun select(q: String, focus: Focus = Focus.ANY, artist: String? = null) =
        LibraryMatcher.select(lib, PlayRequest(q, focus, artist))

    @Test fun artistBeatsSongsOnTie() {
        val s = select("queen")!!
        assertEquals("Queen", s.description)
        assertEquals(setOf(1L, 2L, 3L), s.tracks.map { it.id }.toSet())
    }

    @Test fun albumPlaysInTrackOrder() {
        val s = select("thriller", Focus.ALBUM)!!
        assertEquals(listOf(5L, 6L, 4L), s.tracks.map { it.id })
        assertEquals("Thriller by Michael Jackson", s.description)
    }

    @Test fun songByArtist() {
        val s = select("bohemian rhapsody", Focus.SONG, "queen")!!
        assertEquals(1L, s.tracks.first().id)
    }

    @Test fun speechWithoutPunctuationStillMatches() {
        val s = select("gods plan")!!
        assertEquals(7L, s.tracks.first().id)
    }

    @Test fun anyFindsSongTitle() {
        val s = select("hotline bling")!!
        assertEquals(8L, s.tracks.first().id)
        assertTrue(s.tracks.all { it.artist == "Drake" })
    }

    @Test fun nothingMatches() {
        assertNull(select("taylor swift"))
    }

    @Test fun shuffleAllUsesEverything() {
        assertEquals(lib.size, LibraryMatcher.shuffleAll(lib)!!.tracks.size)
        assertNull(LibraryMatcher.shuffleAll(emptyList()))
    }
}
