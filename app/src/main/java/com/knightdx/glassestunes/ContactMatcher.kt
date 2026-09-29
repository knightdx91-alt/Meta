package com.knightdx.glassestunes

data class Phone(val number: String, val kind: String, val primary: Boolean = false)

data class Contact(
    val id: Long,
    val name: String,
    val phones: List<Phone>,
    /** WhatsApp's id for this person, e.g. "15551234567@s.whatsapp.net", if they use WhatsApp. */
    val whatsappJid: String? = null,
)

/** Matches spoken names against your contacts. Pure, for unit tests. */
object ContactMatcher {

    private const val NAME_MATCH = 70
    private const val SPLIT_MATCH = 85

    fun find(contacts: List<Contact>, spoken: String): Contact? =
        contacts.filter { it.phones.isNotEmpty() || it.whatsappJid != null }
            .sortedBy { it.name.length } // "Mom" before "Mom Work"
            .maxByOrNull { LibraryMatcher.score(it.name, spoken) }
            ?.takeIf { LibraryMatcher.score(it.name, spoken) >= NAME_MATCH }

    /** How sure we are that [spoken] means [contact] (0..100). */
    fun confidence(contact: Contact, spoken: String) = LibraryMatcher.score(contact.name, spoken)

    /**
     * "mom i'm on my way" -> (Mom, "i'm on my way"). Tries the first 1–4 words as
     * the name and keeps the longest, best-matching one.
     */
    fun split(contacts: List<Contact>, text: String): Pair<Contact, String>? =
        splitNames(contacts.map { it.name }, text)?.let { (name, message) ->
            contacts.first { it.name == name } to message
        }

    /** Same as [split] for plain names (e.g. who sent the messages you received). */
    fun splitNames(names: List<String>, text: String): Pair<String, String>? {
        val words = text.trim().split(Regex("\\s+"))
        var best: Triple<String, String, Int>? = null
        for (n in minOf(4, words.size - 1) downTo 1) {
            val spokenName = words.take(n).joinToString(" ")
            val name = names.sortedBy { it.length }.maxByOrNull { LibraryMatcher.score(it, spokenName) } ?: continue
            val score = LibraryMatcher.score(name, spokenName)
            if (score >= SPLIT_MATCH && (best == null || score > best.third)) {
                best = Triple(name, words.drop(n).joinToString(" "), score)
            }
        }
        return best?.let { it.first to it.second }
    }

    fun pickNumber(contact: Contact, kind: String?): Phone? {
        if (kind != null) contact.phones.firstOrNull { it.kind == kind }?.let { return it }
        return contact.phones.firstOrNull { it.primary }
            ?: contact.phones.firstOrNull { it.kind == "mobile" }
            ?: contact.phones.firstOrNull()
    }

    /** "555 123 4567" / "+1 555-1234" -> dialable digits, or null if it's a name. */
    fun asPhoneNumber(spoken: String): String? {
        val digits = spoken.filter { it.isDigit() || it == '+' }
        val letters = spoken.count { it.isLetter() }
        return if (digits.count { it.isDigit() } >= 3 && letters == 0) digits else null
    }

    /** Capitalize the first letter, since speech recognition hands us lowercase. */
    fun sentence(message: String): String = message.trim().replaceFirstChar { it.uppercase() }
}
