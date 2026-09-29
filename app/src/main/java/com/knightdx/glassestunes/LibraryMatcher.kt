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
    private val stopWords = setOf("the", "a", "an", "of", "and", "feat", "ft", "featuring")

    fun normalize(s: String): String = s.lowercase()
        .replace('’', '\'')
        // "God's Plan" is recognized as "gods plan".
        .replace("'", "")
        .replace("&", " and ")
        .replace(Regex("\\((feat|ft|with)[^)]*\\)"), " ")
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun tokens(s: String) = normalize(s).split(' ').filter { it.isNotEmpty() && it !in stopWords }.toSet()

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
        val overlap = qt.intersect(ft).size.toDouble()
        // Penalize a field that only matches a small fraction of what was said
        // and vice versa, so "love" doesn't match every song with "love" in it.
        val ratio = (overlap / qt.size) * 0.7 + (overlap / ft.size) * 0.3
        return (ratio * 80).toInt()
    }

    fun select(library: List<Track>, request: PlayRequest, random: kotlin.random.Random = kotlin.random.Random): Selection? {
        if (library.isEmpty()) return null
        val result = when (request.focus) {
            Focus.ARTIST -> byArtist(library, request.query)
            Focus.ALBUM -> byAlbum(library, request.query)
            Focus.SONG -> bySong(library, request.query, request.artist)
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
        )
        return candidates.maxByOrNull { it.score }?.takeIf { it.score >= THRESHOLD }?.selection
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
