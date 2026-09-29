package com.knightdx.glassestunes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisDetectorTest {
    private fun result(text: String, vararg words: Pair<String, Double>) = buildString {
        append("{\n  \"result\" : [")
        append(words.joinToString(", ") { (w, c) -> "{\n \"conf\" : $c,\n \"end\" : 1.2,\n \"start\" : 0.6,\n \"word\" : \"$w\"\n }" })
        append("],\n  \"text\" : \"$text\"\n}")
    }

    @Test fun nameAloneWakes() {
        assertTrue(JarvisDetector.isWake(result("jarvis", "jarvis" to 1.0)))
        assertTrue(JarvisDetector.isWake(result("hey jarvis", "hey" to 1.0, "jarvis" to 0.93)))
        assertTrue(JarvisDetector.isWake(result("jervis", "jervis" to 0.9)))
    }

    @Test fun lowConfidenceIgnored() {
        assertFalse(JarvisDetector.isWake(result("jarvis", "jarvis" to 0.4)))
    }

    @Test fun nameInsideASentenceIgnored() {
        assertFalse(JarvisDetector.isWake(result("jarvis here", "jarvis" to 1.0, "here" to 1.0)))
        assertFalse(JarvisDetector.isWake(result("i told jarvis that", "i" to 1.0, "told" to 1.0, "jarvis" to 1.0, "that" to 1.0)))
    }

    @Test fun otherWordsIgnored() {
        assertFalse(JarvisDetector.isWake(result("service", "service" to 1.0)))
        assertFalse(JarvisDetector.isWake(result("[unk]", "[unk]" to 1.0)))
        assertFalse(JarvisDetector.isWake("{\n  \"text\" : \"\"\n}"))
    }

    @Test fun grammarIsValidJsonList() {
        assertTrue(JarvisDetector.GRAMMAR.startsWith("[\"jarvis\","))
        assertTrue(JarvisDetector.GRAMMAR.endsWith("\"[unk]\"]"))
    }
}
