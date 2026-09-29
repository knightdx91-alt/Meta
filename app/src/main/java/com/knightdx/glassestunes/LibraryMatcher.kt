package com.knightdx.glassestunes

/** A song on the phone. Pure data so matching can be unit tested. */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val trackNo: Int = 0,
    val genre: String = "",
)

data class Selection(val tracks: List<Track>, val description: String)

/**
 * Fuzzy-matches a spoken request against the songs on the phone.
 * Speech recognition gets words right but not always spelling/punctuation,
 * so everything is compared on normalized word tokens.
 */
object LibraryMatcher {

    private const val THRESHOLD = 50
    private val stopWords = setOf("the", "a", "an", "of", "and", "feat", "ft", "featuring", "by", "from")

    /** Spoken and written forms of the same word, mapped to one spelling. */
    private val sameWord = mapOf(
        "mr" to "mister", "mrs" to "missus", "dr" to "doctor", "st" to "saint", "vs" to "versus",
        "n" to "and", "o" to "of", "u" to "you", "ur" to "your", "r" to "are", "pt" to "part",
        "1" to "one", "2" to "two", "3" to "three", "4" to "four", "5" to "five",
        "6" to "six", "7" to "seven", "8" to "eight", "9" to "nine", "10" to "ten",
    )

    fun normalize(s: String): String {
        val plain = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "") // "Beyoncé" -> "beyonce"
            .replace('’', '\'')
            // "God's Plan" is recognized as "gods plan".
            .replace("'", "")
            .replace("&", " and ")
            .replace(Regex("\\((feat|ft|with)[^)]*\\)"), " ")
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("\\s-\\s.*(remix|remaster|version|edit|live|mix).*$"), " ") // "Song - 2011 Remaster"
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return plain.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { sameWord[it] ?: it }
    }

    private fun tokens(s: String) = normalize(s).split(' ').filter { it.isNotEmpty() && it !in stopWords }.toSet()

    /** Levenshtein distance, stopping early once it exceeds [max]. */
    private fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > max) return max + 1
            prev = cur
        }
        return prev[b.length]
    }

    /** Same word, allowing for a small mishearing ("rapsody" ~ "rhapsody", "llamar" ~ "lamar"). */
    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        val shorter = minOf(a.length, b.length)
        val allowed = when {
            shorter >= 7 -> 2
            shorter >= 4 -> 1
            else -> 0
        }
        return allowed > 0 && distance(a, b, allowed) <= allowed
    }

    /** 0..100 — how well [field] (a title/artist/album) matches the spoken [query]. */
    fun score(field: String, query: String): Int {
        val f = normalize(field)
        val q = normalize(query)
        if (f.isEmpty() || q.isEmpty() || f == "unknown" || f == "unknown artist") return 0
        if (f == q) return 100
        if (q.length >= 3 && (" $f ").contains(" $q ")) return 85
        if (f.length >= 4 && (" $q ").contains(" $f ")) return 65
        val qt = tokens(q)
        val ft = tokens(f)
        if (qt.isEmpty() || ft.isEmpty()) return 0
        // Words said that appear in the field, forgiving small mishearings.
        val matchedSaid = qt.count { w -> ft.any { similar(w, it) } }.toDouble()
        val matchedField = ft.count { w -> qt.any { similar(w, it) } }.toDouble()
        if (matchedSaid == qt.size.toDouble() && matchedField == ft.size.toDouble()) return 90 // same words, fuzzily
        // Penalize a field that only matches a small fraction of what was said
        // and vice versa, so "love" doesn't match every song with "love" in it.
        val ratio = (matchedSaid / qt.size) * 0.7 + (matchedField / ft.size) * 0.3
        return (ratio * 80).toInt()
    }

    fun select(library: List<Track>, request: PlayRequest, random: kotlin.random.Random = kotlin.random.Random): Selection? {
        if (library.isEmpty()) return null
        val result = when (request.focus) {
            Focus.ARTIST -> byArtist(library, request.query)
            Focus.ALBUM -> byAlbum(library, request.query)
            // "X by Y" that isn't a song by an artist (e.g. "Stand by Me"): search the whole phrase.
            Focus.SONG -> bySong(library, request.query, request.artist)
                ?: request.artist?.let { best(library, "${request.query} by $it") }
            Focus.GENRE -> byGenre(library, request.query) ?: best(library, request.query)
            // Samsung Music keeps its playlists to itself, so treat as a general search.
            Focus.PLAYLIST, Focus.ANY -> best(library, request.query)
        } ?: return null
        return if (request.shuffle) result.copy(tracks = result.tracks.shuffled(random)) else result
    }

    fun shuffleAll(library: List<Track>, random: kotlin.random.Random = kotlin.random.Random): Selection? =
        if (library.isEmpty()) null else Selection(library.shuffled(random), "all your music on shuffle")

    private data class Candidate(val score: Int, val selection: Selection)

    private fun best(library: List<Track>, query: String): Selection? {
        // On ties prefer artist, then album, then song ("play queen" = the band).
        val candidates = listOfNotNull(
            artistCandidate(library, query),
            albumCandidate(library, query),
            songCandidate(library, query, null),
            titleAndArtistCandidate(library, query),
        )
        return candidates.maxByOrNull { it.score }?.takeIf { it.score >= THRESHOLD }?.selection
    }

    /** "bohemian rhapsody queen": the phrase names both the song and its artist. */
    private fun titleAndArtistCandidate(library: List<Track>, q: String): Candidate? {
        val song = library.maxByOrNull { maxOf(score("${it.title} ${it.artist}", q), score("${it.artist} ${it.title}", q)) }
            ?: return null
        val s = maxOf(score("${song.title} ${song.artist}", q), score("${song.artist} ${song.title}", q))
        val rest = library.filter { it.id != song.id && it.artist == song.artist }.shuffled()
        return Candidate(s, Selection(listOf(song) + rest, "${song.title} by ${song.artist}"))
    }

    private fun byArtist(library: List<Track>, q: String) = artistCandidate(library, q)?.takeIf { it.score >= THRESHOLD }?.selection
    private fun byAlbum(library: List<Track>, q: String) = albumCandidate(library, q)?.takeIf { it.score >= THRESHOLD }?.selection
    private fun bySong(library: List<Track>, q: String, artist: String?) =
        songCandidate(library, q, artist)?.takeIf { it.score >= THRESHOLD }?.selection

    private fun byGenre(library: List<Track>, q: String): Selection? {
        val genre = library.map { it.genre }.filter { it.isNotBlank() }.distinct()
            .maxByOrNull { score(it, q) }?.takeIf { score(it, q) >= THRESHOLD } ?: return null
        return Selection(library.filter { it.genre == genre }.shuffled(), genre)
    }

    private fun artistCandidate(library: List<Track>, q: String): Candidate? {
        val artist = library.map { it.artist }.distinct().maxByOrNull { score(it, q) } ?: return null
        val s = score(artist, q)
        // Include collaborations ("Queen & David Bowie") when the main name matched.
        val tracks = library.filter { it.artist == artist || score(it.artist, artist) >= 85 }
        return Candidate(s, Selection(tracks.shuffled(), artist))
    }

    private fun albumCandidate(library: List<Track>, q: String): Candidate? {
        val album = library.map { it.album }.distinct().maxByOrNull { score(it, q) } ?: return null
        val tracks = library.filter { it.album == album }.sortedWith(compareBy({ it.trackNo }, { it.title }))
        val by = tracks.map { it.artist }.distinct().singleOrNull()
        return Candidate(score(album, q) - 1, Selection(tracks, if (by != null) "$album by $by" else album))
    }

    private fun songCandidate(library: List<Track>, q: String, artist: String?): Candidate? {
        var pool = library
        if (artist != null) {
            val byArtist = library.filter { score(it.artist, artist) >= THRESHOLD }
            if (byArtist.isNotEmpty()) pool = byArtist
        }
        val song = pool.maxByOrNull { score(it.title, q) } ?: return null
        // Play the song, then keep going with more from the same artist.
        val rest = library.filter { it.id != song.id && it.artist == song.artist }.shuffled()
        return Candidate(score(song.title, q) - 2, Selection(listOf(song) + rest, "${song.title} by ${song.artist}"))
    }
}
