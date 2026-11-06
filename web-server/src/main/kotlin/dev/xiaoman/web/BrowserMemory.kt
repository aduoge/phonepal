package dev.xiaoman.web

import dev.xiaoman.api.Note
import dev.xiaoman.memory.BasicMemoryFormat
import dev.xiaoman.memory.Permalinks
import kotlinx.serialization.json.*

/**
 * Memory kept in the student's browser (localStorage), not on the server.
 *
 * Each chat request carries `memory: {notes: [{permalink, markdown, updatedAt}]}`; the markdown is the same
 * Basic Memory note format the Android app writes to files. The server loads it into the session's
 * [InMemoryMemory] for that turn and reports every change back as a `memory` event so the page can save it:
 * `{type: "memory", action: "saved", permalink, title, markdown, updatedAt}` or
 * `{type: "memory", action: "deleted", permalink}`. Nothing is written to disk.
 */
object BrowserMemory {
    const val MAX_NOTES = 200
    const val MAX_MARKDOWN_CHARS = 8_000
    const val MAX_PERMALINK_CHARS = 120

    class Loaded(val notes: List<Note>, val skipped: Int)

    /** Null when the request has no `memory` field (older pages): the session keeps its own memory then. */
    fun parse(body: JsonObject): Loaded? {
        val memory = body["memory"] as? JsonObject ?: return null
        val items = memory["notes"] as? JsonArray ?: JsonArray(emptyList())
        var skipped = (items.size - MAX_NOTES).coerceAtLeast(0)
        val notes = linkedMapOf<String, Note>()
        for (item in items.take(MAX_NOTES)) {
            val note = (item as? JsonObject)?.let(::note)
            if (note == null) skipped++ else notes[note.permalink] = note
        }
        return Loaded(notes.values.toList(), skipped)
    }

    private fun note(o: JsonObject): Note? {
        val raw = o.str("permalink")
        val markdown = o.str("markdown")
        if (raw.isBlank() || raw.length > MAX_PERMALINK_CHARS || markdown.isBlank() || markdown.length > MAX_MARKDOWN_CHARS) return null
        return try {
            val permalink = Permalinks.normalize(raw)
            val updated = (o["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L
            BasicMemoryFormat.parse(markdown, permalink, updated)
        } catch (_: Exception) { null }
    }

    fun saved(note: Note): JsonObject = event("memory") {
        put("action", "saved"); put("permalink", note.permalink); put("title", note.title)
        put("markdown", BasicMemoryFormat.render(note)); put("updatedAt", note.updatedAtMillis)
    }

    fun deleted(permalink: String): JsonObject = event("memory") { put("action", "deleted"); put("permalink", permalink) }
}
