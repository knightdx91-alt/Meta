package com.knightdx.glassestunes

/**
 * Decides whether a Vosk recognition result is someone calling "Jarvis".
 * Pure Kotlin so it can be unit tested.
 *
 * Vosk only knows a small vocabulary (see [GRAMMAR]). Besides "jarvis" it holds
 * sound-alike decoys ("jars", "service", "harvest", "nervous", ...) so those
 * words don't get forced onto "jarvis". Words that swallow or split a real
 * "jarvis" (like "with" and "novice") are left out on purpose. We then only accept an utterance that
 * is *just* the name, which ignores "Jarvis" said in the middle of a sentence.
 */
object JarvisDetector {

    private val decoys = """
        jar jars harvest service serve starve starving carve curve this is us nervous charles charlie davis travis
        marvin mavis garvey harvey jervis purvis elvis marvel marvelous jazz jasmine java javier jarred jarrod garage
        large larger charge charged far car star bar are our hour yes yeah no okay hey hi hello the a of and or to it
        its that what when where who why how you your yours me my i we they he she her him them there their here
        these those for from by on in at play stop pause next skip back music song songs please thanks thank now
        just so do does did done can could would should honey money funny sunny bunny surface furnace office officer
        price prize priced jobs job john joe joy jarrett jeremy jersey germs germ jury journey jerk
    """.trim().split(Regex("\\s+")).distinct()

    /** Vosk grammar: the wake word, decoys, and "[unk]" for everything else. */
    val GRAMMAR: String = (listOf("jarvis") + decoys + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }

    private val wakeUtterances = setOf("jarvis", "hey jarvis", "hi jarvis", "okay jarvis", "hello jarvis", "jervis", "hey jervis")
    private val textField = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"")
    // Braces and brackets are escaped everywhere: Android's regex engine (ICU) is stricter than the JVM's.
    private val wordEntry = Regex("\\{[^\\{\\}]*\"word\"\\s*:\\s*\"([^\"]*)\"[^\\{\\}]*\\}")
    private val confField = Regex("\"conf\"\\s*:\\s*([0-9.eE+-]+)")

    /** [json] is a Vosk final result with words enabled. */
    fun isWake(json: String, minConfidence: Double = 0.5): Boolean {
        val text = textField.find(json)?.groupValues?.get(1)?.trim() ?: return false
        if (text !in wakeUtterances) return false
        val conf = wordEntry.findAll(json)
            .filter { it.groupValues[1] == "jarvis" || it.groupValues[1] == "jervis" }
            .mapNotNull { confField.find(it.value)?.groupValues?.get(1)?.toDoubleOrNull() }
            .maxOrNull() ?: return false
        return conf >= minConfidence
    }
}
