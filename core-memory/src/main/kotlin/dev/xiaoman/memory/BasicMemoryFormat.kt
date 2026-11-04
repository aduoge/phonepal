package dev.xiaoman.memory

import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.Relation
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.common.FlowStyle

/**
 * Reads and writes notes in the Basic Memory Markdown format (see [Note]).
 *
 * Observations and relations are parsed from list lines anywhere in the body outside fenced code;
 * other `[[wiki links]]` become `links_to` relations.
 */
object BasicMemoryFormat {
    /** Relation type given to wiki links outside relation lines. */
    const val LINKS_TO = "links_to"

    private val RESERVED = setOf("title", "type", "permalink", "tags")

    private val OBSERVATION = Regex("""^[-*+]\s+\[([^\[\]]+)](?:\s+(.*))?$""")
    private val RELATION = Regex("""^[-*+]\s+([^\[\]()#\s][^\[\]()#]*?)\s+\[\[([^\[\]]+)]]\s*(?:\((.*)\))?$""")
    private val WIKI_LINK = Regex("""\[\[([^\[\]]+)]]""")
    private val INLINE_CODE = Regex("`[^`]*`")
    private val TAG = Regex("""(?:^|(?<=\s))#([^\s#()\[\]]+)""")
    private val SPACES = Regex("""\s+""")
    private val SECTION_END = Regex("""^#{1,2}\s.*""")

    private val load = Load(LoadSettings.builder().build())
    private val blockDump = Dump(dumpSettings(FlowStyle.BLOCK))
    private val flowDump = Dump(dumpSettings(FlowStyle.FLOW))

    private fun dumpSettings(style: FlowStyle) =
        DumpSettings.builder().setDefaultFlowStyle(style).setWidth(4096).setSplitLines(false).build()

    /** Parses a note file; [permalink] is the file's path key and [mtime] becomes [Note.updatedAtMillis]. */
    fun parse(text: String, permalink: String, mtime: Long): Note {
        val (frontmatter, body) = splitFrontmatter(text)
        val (observations, relations) = parseBody(body)
        val title = frontmatter["title"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: firstHeading(body)
            ?: permalink.substringAfterLast('/')
        return Note(
            title = title,
            permalink = permalink,
            type = frontmatter["type"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "note",
            tags = parseTags(frontmatter["tags"]),
            extraFrontmatter = frontmatter.filterKeys { it !in RESERVED },
            body = body,
            observations = observations,
            relations = relations,
            updatedAtMillis = mtime,
        )
    }

    /**
     * Renders [note] as file text. A blank body is generated from the observations and relations;
     * a given body is kept, with observations/relations it lacks appended to their sections.
     */
    fun render(note: Note): String {
        val body = if (note.body.isBlank()) renderBody(note) else mergeBody(note.body, note.observations, note.relations)
        return buildString {
            append("---\n")
            append(renderFrontmatter(note))
            append("---\n")
            append(body)
            if (!body.endsWith("\n")) append('\n')
        }
    }

    /** Parses one `- [category] content #tag (context)` line, or returns null. */
    fun parseObservation(line: String): Observation? {
        val match = OBSERVATION.matchEntire(line.trim()) ?: return null
        val category = match.groupValues[1].trim()
        // Task-list checkboxes `- [ ]` / `- [x]` are not observations.
        if (category.isEmpty() || category.equals("x", ignoreCase = true)) return null
        var rest = match.groupValues[2].trim()
        var context: String? = null
        if (rest.endsWith(")")) {
            val open = matchingOpenParen(rest)
            if (open > 0 && rest[open - 1].isWhitespace()) {
                context = rest.substring(open + 1, rest.length - 1).trim().ifEmpty { null }
                rest = rest.substring(0, open).trim()
            }
        }
        val tags = TAG.findAll(rest).map { it.groupValues[1] }.toList()
        val content = TAG.replace(rest, "").replace(SPACES, " ").trim()
        return Observation(category, content, tags, context)
    }

    /** Parses one `- relation_type [[Target]] (context)` line, or returns null. */
    fun parseRelation(line: String): Relation? {
        val match = RELATION.matchEntire(line.trim()) ?: return null
        val target = linkTarget(match.groupValues[2]) ?: return null
        return Relation(match.groupValues[1].trim(), target, match.groupValues[3].trim().ifEmpty { null })
    }

    fun renderObservation(observation: Observation): String = buildString {
        append("- [").append(observation.category.oneLine().replace("[", "(").replace("]", ")")).append("] ")
        append(observation.content.oneLine())
        observation.tags.map { it.trim().removePrefix("#").replace(SPACES, "_") }.filter { it.isNotEmpty() }
            .forEach { append(" #").append(it) }
        observation.context?.oneLine()?.takeIf { it.isNotEmpty() }?.let { append(" (").append(it).append(')') }
    }.trimEnd()

    fun renderRelation(relation: Relation): String = buildString {
        append("- ").append(relation.type.oneLine().ifEmpty { LINKS_TO })
        append(" [[").append(relation.target.oneLine()).append("]]")
        relation.context?.oneLine()?.takeIf { it.isNotEmpty() }?.let { append(" (").append(it).append(')') }
    }

    /** Observations and relations (explicit plus `links_to`) found in [body]. */
    internal fun parseBody(body: String): Pair<List<Observation>, List<Relation>> {
        val observations = mutableListOf<Observation>()
        val relations = mutableListOf<Relation>()
        val links = mutableListOf<Relation>()
        forEachContentLine(body) { line ->
            val observation = parseObservation(line)
            if (observation != null) {
                observations += observation
                collectLinks(line, links)
                return@forEachContentLine
            }
            val relation = parseRelation(line)
            if (relation != null) relations += relation else collectLinks(line, links)
        }
        return observations to (relations + links).distinct()
    }

    private fun renderBody(note: Note): String = buildString {
        append("# ").append(note.title.oneLine()).append('\n')
        if (note.observations.isNotEmpty()) {
            append("\n## Observations\n")
            note.observations.forEach { append(renderObservation(it)).append('\n') }
        }
        if (note.relations.isNotEmpty()) {
            append("\n## Relations\n")
            note.relations.distinct().forEach { append(renderRelation(it)).append('\n') }
        }
    }

    private fun mergeBody(body: String, observations: List<Observation>, relations: List<Relation>): String {
        val (present, presentRelations) = parseBody(body)
        val missing = observations.map { parseObservation(renderObservation(it)) ?: it }.filter { it !in present }.distinct()
        val missingRelations =
            relations.map { parseRelation(renderRelation(it)) ?: it }.filter { it !in presentRelations }.distinct()
        if (missing.isEmpty() && missingRelations.isEmpty()) return body
        val lines = body.lines().toMutableList()
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        if (missing.isNotEmpty()) insertIntoSection(lines, "Observations", missing.map(::renderObservation), before = "Relations")
        if (missingRelations.isNotEmpty()) insertIntoSection(lines, "Relations", missingRelations.map(::renderRelation), before = null)
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun insertIntoSection(lines: MutableList<String>, section: String, newLines: List<String>, before: String?) {
        val heading = lines.indexOfFirst { isSectionHeading(it, section) }
        if (heading >= 0) {
            var end = heading + 1
            while (end < lines.size && !SECTION_END.matches(lines[end].trimStart())) end++
            var at = end
            while (at > heading + 1 && lines[at - 1].isBlank()) at--
            lines.addAll(at, newLines)
            return
        }
        val block = listOf("## $section") + newLines
        val next = before?.let { name -> lines.indexOfFirst { isSectionHeading(it, name) } } ?: -1
        if (next >= 0) {
            lines.addAll(next, block + "")
        } else {
            if (lines.isNotEmpty()) lines += ""
            lines += block
        }
    }

    private fun isSectionHeading(line: String, name: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("## ") && trimmed.removePrefix("##").trim().equals(name, ignoreCase = true)
    }

    private fun renderFrontmatter(note: Note): String = buildString {
        append(blockDump.dumpToString(mapOf("title" to note.title)))
        append(blockDump.dumpToString(mapOf("type" to note.type.ifBlank { "note" })))
        append(blockDump.dumpToString(mapOf("permalink" to note.permalink)))
        append("tags: ").append(flowDump.dumpToString(note.tags).trim()).append('\n')
        val extras = LinkedHashMap<String, Any?>()
        note.extraFrontmatter.forEach { (key, value) -> if (key !in RESERVED) extras[key] = yamlSafe(value) }
        if (extras.isNotEmpty()) append(blockDump.dumpToString(extras))
    }

    /** Converts values snakeyaml cannot represent to plain maps, lists and strings. */
    private fun yamlSafe(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Int, is Long, is Double, is Float, is Short, is Byte,
        is java.math.BigInteger, is java.math.BigDecimal -> value
        is Map<*, *> -> value.entries.associateTo(LinkedHashMap()) { it.key.toString() to yamlSafe(it.value) }
        is Iterable<*> -> value.map(::yamlSafe)
        is Array<*> -> value.map(::yamlSafe)
        is Enum<*> -> value.name
        else -> value.toString()
    }

    private fun splitFrontmatter(text: String): Pair<Map<String, Any?>, String> {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        val lines = normalized.split('\n')
        if (lines.first().trimEnd() != "---") return emptyMap<String, Any?>() to normalized
        val close = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" || lines[it].trimEnd() == "..." }
            ?: return emptyMap<String, Any?>() to normalized
        val yaml = lines.subList(1, close).joinToString("\n")
        val body = lines.subList(close + 1, lines.size).joinToString("\n")
        val loaded = try {
            if (yaml.isBlank()) null else load.loadFromString(yaml)
        } catch (e: Exception) {
            // Unreadable frontmatter: keep the whole text as body so nothing is lost on rewrite.
            return emptyMap<String, Any?>() to normalized
        }
        val map = (loaded as? Map<*, *>)?.entries?.associateTo(LinkedHashMap()) { it.key.toString() to it.value }
            ?: LinkedHashMap()
        return map to body
    }

    private fun parseTags(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is Iterable<*> -> value.mapNotNull { it?.toString() }
        else -> value.toString().split(',')
    }.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }

    private fun firstHeading(body: String): String? {
        var heading: String? = null
        forEachContentLine(body) { line ->
            if (heading == null && line.startsWith("# ")) heading = line.removePrefix("# ").trim().ifEmpty { null }
        }
        return heading
    }

    /** Calls [action] with each trimmed line outside fenced code blocks. */
    private inline fun forEachContentLine(body: String, action: (String) -> Unit) {
        var fence: String? = null
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("```") || line.startsWith("~~~")) {
                val marker = line.take(3)
                fence = if (fence == null) marker else if (fence == marker) null else fence
                continue
            }
            if (fence == null) action(line)
        }
    }

    private fun collectLinks(line: String, into: MutableList<Relation>) {
        WIKI_LINK.findAll(INLINE_CODE.replace(line, "")).forEach { match ->
            linkTarget(match.groupValues[1])?.let { into += Relation(LINKS_TO, it) }
        }
    }

    /** `Target|alias` and `Target#heading` both point at `Target`. */
    private fun linkTarget(raw: String): String? = raw.substringBefore('|').substringBefore('#').trim().ifEmpty { null }

    private fun matchingOpenParen(text: String): Int {
        var depth = 0
        for (i in text.indices.reversed()) {
            when (text[i]) {
                ')' -> depth++
                '(' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    private fun String.oneLine(): String = replace(SPACES, " ").trim()
}
