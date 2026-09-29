package com.knightdx.glassestunes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundMatcherTest {
    /**
     * Italian and Norwegian titles spoken in those languages and transcribed by an
     * English recognizer (synthetic voice + Vosk). Harsher than Google on a real
     * voice, but it's the same kind of mistake: foreign words heard as English ones.
     */
    private val heardInEnglish = listOf(
        "Volare" to "play malawi",
        "Nel blu dipinto di blu" to "play the the",
        "Con te partirò" to "please complete the call",
        "Azzurro" to "play on google",
        "Bella ciao" to "play man towel",
        "Ti amo" to "play diablo",
        "Vivo per lei" to "play be more people are you",
        "La solitudine" to "play lots of little the",
        "Laura non c'è" to "play the can",
        "Sarà perché ti amo" to "play the",
        "Gli anni" to "play the",
        "L'italiano" to "play the piano",
        "Zitti e buoni" to "play the the body",
        "Soldi" to "play solo the",
        "Il mondo" to "play your mobile",
        "Mille" to "play merely",
        "Brividi" to "play the dvd",
        "Andiamo" to "play and jamo",
        "Senza una donna" to "play then",
        "Io che amo solo te" to "play the",
        "Quando quando quando" to "play one the one the one boy",
        "Tu vuò fà l'americano" to "play to offer may be cargo",
        "Caruso" to "play can move on",
        "Figli delle stelle" to "play he didn't sailing",
        "Ciao ragazzo" to "play coward thoughtful",
        "Fjellet" to "play the",
        "Solen skinner" to "play slogan kinda",
        "Kjærlighet" to "play called the",
        "Sjømannen" to "play sherman",
        "Mitt hjerte" to "play the doctor",
        "Nordlys" to "play not least",
        "Stjernesludd" to "play the countless looted",
        "Når vi er sammen" to "play note beyond some and",
        "Hjem til deg" to "play bmc league",
        "Kjære deg" to "play a big",
        "Skjærgården" to "play shot london",
        "Jeg vil ha deg" to "play the ivy league",
        "Bare du" to "play modern the",
        "Sommerfugl" to "play some food",
        "Vinterland" to "play when of london",
        "Håp" to "play pool",
        "Lykke" to "play luka",
        "Hjertet mitt" to "play the meat",
        "Tusen takk" to "play the talk",
        "Vår" to "play vote",
        "Sjelevenn" to "play kaylee men",
        "Kjenner du lukta" to "play can the top",
        "Fly høyt" to "play fleet are you",
        "Dansen på Løkka" to "play thompson polar com",
        "Byen sover" to "play be and silver",
    )
    private val english = listOf("Bohemian Rhapsody", "Halo", "Blinding Lights", "Lose Yourself", "Sad", "HUMBLE.", "Mr. Brightside")

    @Test fun guessesForeignTitlesOftenAndRarelyWrong() {
        val names = heardInEnglish.map { it.first } + english
        var right = 0
        var wrong = 0
        for ((title, heard) in heardInEnglish) {
            val guess = SoundMatcher.best(names, heard.removePrefix("play ").removePrefix("please ")) ?: continue
            if (guess == title) right++ else wrong++
        }
        println("sound matching: right=$right wrong=$wrong of ${heardInEnglish.size}")
        assertTrue("right=$right", right >= 11)
        assertTrue("wrong=$wrong", wrong <= 5)
    }

    @Test fun examples() {
        val names = heardInEnglish.map { it.first }
        assertEquals("Andiamo", SoundMatcher.best(names, "and jamo"))
        assertEquals("Nordlys", SoundMatcher.best(names, "not least"))
        assertEquals("Byen sover", SoundMatcher.best(names, "be and silver"))
        assertEquals("Soldi", SoundMatcher.best(names, "solo the"))
    }

    @Test fun heardInTheRightLanguageMatchesExactly() {
        val names = heardInEnglish.map { it.first }
        // In language mode Google writes the real words (maybe without accents).
        assertEquals("Con te partirò", SoundMatcher.best(names, "con te partiro", heardInEnglish = false))
        assertEquals("Kjærlighet", SoundMatcher.best(names, "kjærlighet", heardInEnglish = false))
    }

    @Test fun libraryAsksBeforePlayingASoundGuess() {
        val lib = listOf(Track(1, "Andiamo", "Fabio Rovazzi", "Andiamo", 1), Track(2, "Halo", "Beyoncé", "I Am", 2))
        val s = LibraryMatcher.select(lib, PlayRequest("and jamo"))!!
        assertEquals(1L, s.tracks.first().id)
        assertTrue(s.unsure)
        assertFalse(LibraryMatcher.select(lib, PlayRequest("halo"))!!.unsure)
    }
}

class PlaySadTest {
    private val lib = listOf(
        Track(1, "SAD!", "XXXTENTACION", "?", 4),
        Track(2, "Smooth Operator", "Sade", "Diamond Life", 1),
        Track(3, "Sad Girl", "Lana Del Rey", "Ultraviolence", 7),
        Track(4, "Tears", "Various", "Sad Songs Collection", 3),
    )

    @Test fun oneWordTitle() {
        val cmd = CommandParser.parse("play Sad") as Command.Play
        val s = LibraryMatcher.select(lib, cmd.request)!!
        assertEquals(1L, s.tracks.first().id)
        assertFalse(s.unsure)
    }
}

class LanguageModeTest {
    @Test fun phrases() {
        assertEquals(Command.PlayInLanguage("it-IT", "Italian"), CommandParser.parse("play an Italian song"))
        assertEquals(Command.PlayInLanguage("nb-NO", "Norwegian"), CommandParser.parse("Norwegian"))
        assertEquals(Command.PlayInLanguage("nb-NO", "Norwegian"), CommandParser.parse("play something in Norwegian"))
        assertEquals(Command.PlayInLanguage("it-IT", "Italian", "vivo per lei"), CommandParser.parse("play the Italian song vivo per lei"))
        assertEquals(Command.PlayInLanguage("it-IT", "Italian", "vivo per lei"), CommandParser.parse("play vivo per lei in Italian"))
        // A real song title that starts with a language stays a normal request.
        assertEquals(Command.Play(PlayRequest("norwegian wood")), CommandParser.parse("play Norwegian Wood"))
    }

    @Test fun accentsAreKept() {
        assertEquals(Command.Play(PlayRequest("con te partirò")), CommandParser.parse("play Con te partirò"))
        assertEquals(Command.Play(PlayRequest("kjærlighet")), CommandParser.parse("play Kjærlighet"))
    }
}
