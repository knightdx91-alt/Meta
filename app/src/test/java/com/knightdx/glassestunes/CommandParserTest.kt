package com.knightdx.glassestunes

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {
    private fun play(q: String, focus: Focus = Focus.ANY, artist: String? = null, shuffle: Boolean = false) =
        Command.Play(PlayRequest(q, focus, artist, shuffle))

    @Test fun transport() {
        assertEquals(Command.Pause, CommandParser.parse("Pause"))
        assertEquals(Command.Pause, CommandParser.parse("stop the music please"))
        assertEquals(Command.Resume, CommandParser.parse("play"))
        assertEquals(Command.Resume, CommandParser.parse("Resume."))
        assertEquals(Command.Next, CommandParser.parse("skip this song"))
        assertEquals(Command.Previous, CommandParser.parse("go back"))
        assertEquals(Command.VolumeUp, CommandParser.parse("turn it up"))
        assertEquals(Command.VolumeDown, CommandParser.parse("Volume down"))
        assertEquals(Command.WhatsPlaying, CommandParser.parse("What's playing?"))
    }

    @Test fun shuffleAll() {
        assertEquals(Command.ShuffleAll, CommandParser.parse("shuffle everything"))
        assertEquals(Command.ShuffleAll, CommandParser.parse("play my music"))
        assertEquals(Command.ShuffleAll, CommandParser.parse("hey play some music"))
    }

    @Test fun playTargets() {
        assertEquals(play("queen"), CommandParser.parse("Play Queen"))
        assertEquals(play("bohemian rhapsody", Focus.SONG, "queen"), CommandParser.parse("play Bohemian Rhapsody by Queen"))
        assertEquals(play("thriller", Focus.ALBUM), CommandParser.parse("play the album Thriller"))
        assertEquals(play("thriller", Focus.ALBUM), CommandParser.parse("play the Thriller album"))
        assertEquals(play("workout", Focus.PLAYLIST), CommandParser.parse("play my workout playlist"))
        assertEquals(play("drake", Focus.ARTIST), CommandParser.parse("play songs by Drake"))
        assertEquals(play("drake", Focus.ARTIST), CommandParser.parse("play the artist drake"))
        assertEquals(play("hotel california", Focus.SONG), CommandParser.parse("play the song hotel california"))
        assertEquals(play("jazz", Focus.GENRE), CommandParser.parse("play genre jazz"))
        assertEquals(play("beyonce"), CommandParser.parse("can you play some Beyonce on Samsung Music"))
    }

    @Test fun shuffleTargets() {
        assertEquals(play("drake", shuffle = true), CommandParser.parse("shuffle Drake"))
        assertEquals(play("abbey road", Focus.ALBUM, shuffle = true), CommandParser.parse("play the album abbey road on shuffle"))
    }

    @Test fun unknown() {
        assertEquals(Command.Unknown("what's the weather"), CommandParser.parse("what's the weather"))
    }
}

class AppCommandsTest {
    @Test fun openApps() {
        assertEquals(Command.OpenApp("maps"), CommandParser.parse("open Maps"))
        assertEquals(Command.OpenApp("samsung notes"), CommandParser.parse("launch the Samsung Notes app"))
        assertEquals(Command.OpenApp("camera"), CommandParser.parse("hey start camera"))
        // "start playing X" is still music.
        assertEquals(Command.Play(PlayRequest("drake")), CommandParser.parse("start playing Drake"))
    }

    @Test fun assistant() {
        assertEquals(Command.Assistant, CommandParser.parse("talk to Gemini"))
        assertEquals(Command.Assistant, CommandParser.parse("Hey Google"))
        assertEquals(Command.Assistant, CommandParser.parse("open Gemini Live"))
    }

    @Test fun matchesAppNames() {
        val apps = listOf(
            AppEntry("Camera", "com.sec.android.app.camera"),
            AppEntry("Camera Assistant", "com.samsung.cameraassistant"),
            AppEntry("Maps", "com.google.android.apps.maps"),
            AppEntry("Samsung Notes", "com.samsung.android.app.notes"),
            AppEntry("WhatsApp", "com.whatsapp"),
        )
        assertEquals("com.sec.android.app.camera", AppMatcher.find(apps, "camera")?.packageName)
        assertEquals("com.google.android.apps.maps", AppMatcher.find(apps, "google maps")?.packageName)
        assertEquals("com.samsung.android.app.notes", AppMatcher.find(apps, "notes")?.packageName)
        assertEquals("com.whatsapp", AppMatcher.find(apps, "whatsapp")?.packageName)
        assertEquals(null, AppMatcher.find(apps, "tiktok"))
    }

    @Test fun recognizesGlasses() {
        org.junit.Assert.assertTrue(GlassesNames.isGlasses("Ray-Ban Meta 04B2"))
        org.junit.Assert.assertTrue(GlassesNames.isGlasses("RayBan Stories"))
        org.junit.Assert.assertTrue(GlassesNames.isGlasses("Oakley Meta HSTN"))
        org.junit.Assert.assertFalse(GlassesNames.isGlasses("Galaxy Buds3 Pro"))
        org.junit.Assert.assertFalse(GlassesNames.isGlasses("Metallica Speaker"))
        org.junit.Assert.assertFalse(GlassesNames.isGlasses(null))
    }
}
