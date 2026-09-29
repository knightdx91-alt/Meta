package com.knightdx.glassestunes

import java.text.Normalizer

/**
 * Matches song names by how they *sound*, for titles in other languages.
 *
 * English speech recognition writes an Italian or Norwegian title as English
 * words that sound similar ("Sjømannen" -> "sherman", "Andiamo" -> "and jamo",
 * "Byen sover" -> "be and silver"). We turn both sides into a rough sound
 * spelling: the heard words with English spelling rules, the title with
 * Italian/Norwegian rules ("gn" = "ny", "kj"/"sj" = "sh", "ci" = "chi",
 * "j" = "y", ...), then compare those. It's a best guess, so the app asks
 * "Did you mean …?" before playing it.
 */
object SoundMatcher {

    /** Tuned on English transcriptions of Italian and Norwegian titles (see SoundMatcherTest). */
    const val MIN_SCORE = 0.5
    const val MIN_LEAD = 0.08

    private fun plain(s: String): String {
        val t = s.lowercase().replace("ø", "o").replace("å", "o").replace("æ", "e")
        return Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    }

    // Italian + Norwegian spelling -> rough sounds. Capitals are sound markers: X = "sh", C = "ch", J = "j".
    private val foreignRules = listOf(
        Regex("skj|sj|kj|tj|sci(?=[aou])|sc(?=[ei])|sk(?=[iy])") to "X",
        Regex("c(?=[ei])") to "C",
        Regex("g(?=[ei])") to "J",
        Regex("ci(?=[aou])") to "C",
        Regex("gi(?=[aou])") to "J",
        Regex("ch") to "k",
        Regex("gh") to "g",
        Regex("gli") to "li",
        Regex("gn") to "ny",
        Regex("qu") to "kw",
        Regex("^(hj|gj|lj)") to "j",
        Regex("^hv") to "v",
        Regex("(?<=[a-z])hj|gj") to "j",
        Regex("j") to "y",
        Regex("ei") to "ay",
        Regex("z") to "ts",
    )

    // English spelling -> the same rough sounds.
    private val englishRules = listOf(
        Regex("tch|ch") to "C",
        Regex("sh|ti(?=on)") to "X",
        Regex("j|dge|g(?=[ei])") to "J",
        Regex("ph") to "f",
        Regex("ck") to "k",
        Regex("kn") to "n",
        Regex("wr") to "r",
        Regex("ee|ea") to "i",
        Regex("oo") to "u",
        Regex("ay|ai") to "ey",
        Regex("igh") to "ay",
        Regex("ow") to "au",
        Regex("th") to "t",
        Regex("wh") to "w",
    )

    private val classes: Map<Char, Char> = buildMap {
        "a".forEach { put(it, 'A') }
        "eiy".forEach { put(it, 'E') }
        "ou".forEach { put(it, 'O') }
        "bp".forEach { put(it, 'P') }
        "dt".forEach { put(it, 'T') }
        "gkcq".forEach { put(it, 'K') }
        "fvw".forEach { put(it, 'F') }
        "szx".forEach { put(it, 'S') }
        "XCJ".forEach { put(it, 'X') }
        put('m', 'M'); put('n', 'N'); put('l', 'L'); put('r', 'R')
    }

    /** Rough sound spelling of [text]; [foreign] picks Italian/Norwegian instead of English spelling rules. */
    fun key(text: String, foreign: Boolean): String {
        var t = plain(text).replace(Regex("[^a-z]"), "")
        for ((rule, sound) in if (foreign) foreignRules else englishRules) t = rule.replace(t, sound)
        val out = StringBuilder()
        for (ch in t) {
            val c = classes[ch] ?: continue
            if (out.isEmpty() || out.last() != c) out.append(c)
        }
        return out.toString()
    }

    private fun consonants(k: String) = k.filter { it != 'A' && it != 'E' && it != 'O' }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * 0..1: how alike [heard] and [name] sound. [heardInEnglish] is true when
     * the words came from English speech recognition (the usual case).
     */
    fun similarity(heard: String, name: String, heardInEnglish: Boolean = true): Double {
        val a = key(heard, foreign = !heardInEnglish)
        val b = key(name, foreign = true)
        val ca = consonants(a)
        val cb = consonants(b)
        if (cb.length < 2 || ca.isEmpty()) return 0.0
        val consonantScore = 1 - distance(ca, cb).toDouble() / maxOf(ca.length, cb.length)
        val fullScore = 1 - distance(a, b).toDouble() / maxOf(a.length, b.length)
        return 0.6 * consonantScore + 0.4 * fullScore
    }

    /** The name that sounds most like [heard], if it's a clear enough winner. */
    fun best(names: Collection<String>, heard: String, heardInEnglish: Boolean = true): String? {
        val ranked = names.distinct().map { it to similarity(heard, it, heardInEnglish) }.sortedByDescending { it.second }
        val top = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)?.second ?: 0.0
        return top.first.takeIf { top.second >= MIN_SCORE && top.second - runnerUp >= MIN_LEAD }
    }
}
