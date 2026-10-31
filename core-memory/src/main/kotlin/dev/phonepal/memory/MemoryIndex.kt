package dev.phonepal.memory

import dev.phonepal.api.Note
import dev.phonepal.api.SearchHit
import java.io.File
import java.util.Locale
import kotlin.math.ln

/**
 * In-memory inverted index over the note files under a root folder. Not thread-safe; the owning
 * store serialises access. [sync] picks up files added, changed or removed by other tools.
 */
internal class MemoryIndex(private val root: File) {
    private class Doc(val note: Note, val mtime: Long, val size: Long, val terms: Map<String, Double>, val length: Int)

    private val docs = HashMap<String, Doc>()
    private var documentFrequency: Map<String, Int>? = null
    private var averageLength = 1.0

    val notes: Collection<Note> get() = docs.values.map { it.note }

    /** Rescans [root], re-parsing files whose mtime or size changed and dropping vanished ones. */
    fun sync() {
        val seen = HashSet<String>()
        if (root.isDirectory) {
            root.walkTopDown()
                .onEnter { it == root || !it.name.startsWith(".") }
                .filter { it.isFile && it.name.endsWith(".md") && !it.name.startsWith(".") }
                .forEach { file ->
                    val permalink = file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".md")
                    seen += permalink
                    load(permalink, file)
                }
        }
        if (docs.keys.retainAll(seen)) documentFrequency = null
    }

    /** Returns the note in [file], re-reading it only when it changed since it was indexed. */
    fun load(permalink: String, file: File): Note? {
        val mtime = file.lastModified()
        val size = file.length()
        docs[permalink]?.let { if (it.mtime == mtime && it.size == size && file.isFile) return it.note }
        if (!file.isFile) {
            remove(permalink)
            return null
        }
        val note = BasicMemoryFormat.parse(file.readText(Charsets.UTF_8), permalink, mtime)
        put(note, mtime, size)
        return note
    }

    fun put(note: Note, mtime: Long, size: Long) {
        val terms = HashMap<String, Double>()
        fun add(text: String, weight: Double) = Tokenizer.tokens(text).forEach { terms.merge(it, weight, Double::plus) }
        add(note.body, 1.0)
        add(note.title, TITLE_BOOST)
        note.tags.forEach { add(it, TAG_BOOST) }
        val length = Tokenizer.tokens(note.body).size + Tokenizer.tokens(note.title).size
        docs[note.permalink] = Doc(note, mtime, size, terms, length)
        documentFrequency = null
    }

    fun remove(permalink: String) {
        if (docs.remove(permalink) != null) documentFrequency = null
    }

    /** BM25 search; a blank [query] returns the most recently updated notes. */
    fun search(query: String, limit: Int): List<SearchHit> {
        if (limit <= 0) return emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return docs.values
                .sortedWith(compareByDescending<Doc> { it.note.updatedAtMillis }.thenBy { it.note.permalink })
                .take(limit)
                .map { SearchHit(it.note.permalink, it.note.title, Snippets.excerpt(it.note), 0.0) }
        }
        val queryTerms = Tokenizer.queryTerms(trimmed)
        if (queryTerms.isEmpty()) return emptyList()
        val df = frequencies()
        val n = docs.size.toDouble()
        val phrase = trimmed.lowercase(Locale.ROOT)

        class Scored(val doc: Doc, val score: Double, val primary: Boolean)

        val scored = docs.values.mapNotNull { doc ->
            var score = 0.0
            var primary = false
            for ((term, weight) in queryTerms) {
                val tf = doc.terms[term] ?: continue
                val docs = df[term] ?: continue
                val idf = ln(1 + (n - docs + 0.5) / (docs + 0.5))
                score += weight * idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * doc.length / averageLength))
                if (weight >= 1.0) primary = true
            }
            if (score <= 0) return@mapNotNull null
            if (doc.note.title.lowercase(Locale.ROOT).contains(phrase)) score *= TITLE_PHRASE_FACTOR
            else if (doc.note.body.lowercase(Locale.ROOT).contains(phrase)) score *= BODY_PHRASE_FACTOR
            Scored(doc, score, primary)
        }
        // Weak CJK unigram matches only rank notes that also match a primary term; on their own they
        // are noise (e.g. "你好" would pull in every note containing 好 into the chat prompt).
        return scored.filter { it.primary }
            .sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.doc.note.updatedAtMillis })
            .take(limit)
            .map { SearchHit(it.doc.note.permalink, it.doc.note.title, Snippets.best(it.doc.note, queryTerms), it.score) }
    }

    private fun frequencies(): Map<String, Int> {
        documentFrequency?.let { return it }
        val df = HashMap<String, Int>()
        var total = 0L
        for (doc in docs.values) {
            doc.terms.keys.forEach { df.merge(it, 1, Int::plus) }
            total += doc.length
        }
        averageLength = if (docs.isEmpty()) 1.0 else maxOf(1.0, total.toDouble() / docs.size)
        documentFrequency = df
        return df
    }

    private companion object {
        const val K1 = 1.2
        const val B = 0.75
        const val TITLE_BOOST = 3.0
        const val TAG_BOOST = 2.0
        const val TITLE_PHRASE_FACTOR = 1.5
        const val BODY_PHRASE_FACTOR = 1.2
    }
}

/** Picks the line of a note that best explains a hit. */
internal object Snippets {
    const val MAX_LENGTH = 120
    private val LIST_MARKER = Regex("""^[-*+]\s+""")

    /** The body line (observations preferred on ties) matching the most query weight, clipped around the match. */
    fun best(note: Note, queryTerms: Map<String, Double>): String {
        val lines = candidates(note)
        var best: String? = null
        var bestScore = 0.0
        for (line in lines) {
            val tokens = Tokenizer.tokens(line).toSet()
            val score = queryTerms.entries.sumOf { (term, weight) -> if (term in tokens) weight else 0.0 } +
                if (line.startsWith("[")) 0.01 else 0.0
            if (score > bestScore + 1e-9 && score > 0.01) {
                best = line
                bestScore = score
            }
        }
        return clip(best ?: lines.firstOrNull() ?: note.title, queryTerms.keys)
    }

    /** First meaningful body line, for listings without a query. */
    fun excerpt(note: Note): String = clip(candidates(note).firstOrNull() ?: note.title, emptySet())

    private fun candidates(note: Note): List<String> = note.body.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it != "---" && !it.startsWith("```") }
        .map { LIST_MARKER.replace(it, "") }
        .filter { it.isNotEmpty() }
        .toList()

    private fun clip(line: String, terms: Collection<String>): String {
        if (line.length <= MAX_LENGTH) return line
        val lower = line.lowercase(Locale.ROOT)
        val hit = terms.mapNotNull { term -> lower.indexOf(term).takeIf { it >= 0 } }.minOrNull() ?: 0
        var start = (hit - 30).coerceIn(0, line.length - MAX_LENGTH)
        var end = start + MAX_LENGTH
        if (start > 0 && line[start].isLowSurrogate()) start++
        if (end < line.length && line[end - 1].isHighSurrogate()) end--
        return (if (start > 0) "…" else "") + line.substring(start, end) + (if (end < line.length) "…" else "")
    }
}
