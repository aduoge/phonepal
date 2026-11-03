package dev.phonepal.web

import dev.phonepal.api.*

/** Session-local notes; no content is persisted. */
class InMemoryMemory(private val clock: () -> Long = System::currentTimeMillis) : MemoryStore {
    private val notes = linkedMapOf<String, Note>()
    override suspend fun write(note: Note): Note = synchronized(notes) {
        note.copy(updatedAtMillis = clock()).also { notes[it.permalink] = it }
    }
    override suspend fun read(identifier: String): Note? = synchronized(notes) { find(identifier) }
    private fun find(identifier: String) = notes[identifier] ?: notes.values.firstOrNull { it.title == identifier }
    override suspend fun search(query: String, limit: Int): List<SearchHit> = synchronized(notes) {
        val q = query.trim().lowercase()
        if (q.isEmpty() || limit <= 0) return@synchronized emptyList()
        val chinese = q.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }.toSet()
        notes.values.mapNotNull { n ->
            val text = (listOf(n.title, n.body) + n.observations.map { it.content }).joinToString("\n").lowercase()
            val overlap = chinese.count { it in text }.toDouble() / chinese.size.coerceAtLeast(1)
            val score = (if (q in text) 2.0 else 0.0) + (if (q in n.title.lowercase()) 1.0 else 0.0) + overlap
            if (score == 0.0) null else SearchHit(n.permalink, n.title, text.take(240), score)
        }.sortedByDescending { it.score }.take(limit)
    }
    override suspend fun appendObservation(identifier: String, observation: Observation): Note = synchronized(notes) {
        val n = find(identifier) ?: Note(identifier, identifier)
        n.copy(observations = n.observations + observation,
            body = n.body + "\n- [${observation.category}] ${observation.content}", updatedAtMillis = clock())
            .also { notes[it.permalink] = it }
    }
    override suspend fun delete(identifier: String): Boolean = synchronized(notes) { find(identifier)?.let { notes.remove(it.permalink) != null } ?: false }
    override suspend fun list(folder: String?): List<Note> = synchronized(notes) {
        notes.values.filter { folder == null || it.permalink.startsWith(folder.trimEnd('/') + "/") }
    }
    fun clear() = synchronized(notes) { notes.clear() }
}
