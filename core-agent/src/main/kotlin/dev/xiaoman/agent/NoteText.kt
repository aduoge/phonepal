package dev.xiaoman.agent

import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.Relation

/** Basic Memory Markdown rendering shared by the memory tools and the learning notes. */
internal object NoteText {
    fun observationLine(o: Observation) = buildString {
        append("- [").append(o.category).append("] ").append(o.content)
        o.tags.forEach { append(" #").append(it) }
        o.context?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
    }

    fun relationLine(r: Relation) = buildString {
        append("- ").append(r.type).append(" [[").append(r.target).append("]]")
        r.context?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
    }

    /**
     * Body with `# title`, any free text of [preamble], then the Observations and Relations sections.
     * [preamble] is the part of an existing body before its first section heading.
     */
    fun body(title: String, observations: List<Observation>, relations: List<Relation>, preamble: String = ""): String = buildString {
        val intro = preamble.lines().dropWhile { it.isBlank() || it.trim() == "# $title" }.joinToString("\n").trim()
        append("# ").append(title).append("\n")
        if (intro.isNotEmpty()) append("\n").append(intro).append("\n")
        if (observations.isNotEmpty()) {
            append("\n## Observations\n")
            observations.forEach { append(observationLine(it)).append("\n") }
        }
        if (relations.isNotEmpty()) {
            append("\n## Relations\n")
            relations.forEach { append(relationLine(it)).append("\n") }
        }
    }

    /** Free text of a body before `## Observations` / `## Relations`. */
    fun preamble(body: String): String {
        val idx = Regex("(?m)^## (Observations|Relations)\\s*$").find(body)?.range?.first ?: return body
        return body.substring(0, idx)
    }

    /** Compact text of a note for prompts, cut to [maxChars]. */
    fun forPrompt(note: Note, maxChars: Int = 1500): String {
        val text = buildString {
            append("### ").append(note.title).append(" (").append(note.permalink).append(")\n")
            if (note.observations.isNotEmpty() || note.relations.isNotEmpty()) {
                note.observations.forEach { append(observationLine(it)).append("\n") }
                note.relations.forEach { append(relationLine(it)).append("\n") }
                val intro = preamble(note.body).lines().filterNot { it.startsWith("# ") }.joinToString("\n").trim()
                if (intro.isNotEmpty()) append(intro).append("\n")
            } else {
                append(note.body.trim()).append("\n")
            }
        }.trim()
        return if (text.length <= maxChars) text else text.take(maxChars) + "…"
    }
}
