package dev.xiaoman.skills

import dev.xiaoman.api.SkillProblem
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.nodes.Tag

/**
 * Node-level YAML access that keeps line numbers. [lineOffset] is the number of file lines before
 * the YAML text (the opening `---` of a frontmatter block), so reported lines are 1-based file lines.
 */
internal class YamlDoc(val source: String, private val lineOffset: Int = 0) {
    val problems = mutableListOf<SkillProblem>()

    fun problem(message: String, node: Node? = null) {
        problems += SkillProblem(source, message, node?.let(::lineOf))
    }

    fun problemAt(message: String, line: Int?) {
        problems += SkillProblem(source, message, line)
    }

    fun lineOf(node: Node): Int? = node.startMark.map { it.line + 1 + lineOffset }.orElse(null)

    /** Composes [text] into a node tree; `null` with a problem on a syntax error, `null` alone when empty. */
    fun compose(text: String): Node? = try {
        Compose(SETTINGS).composeString(text).orElse(null)
    } catch (e: MarkedYamlEngineException) {
        val line = e.problemMark.map { it.line + 1 + lineOffset }.orElse(null)
        problemAt("invalid YAML: ${e.problem ?: e.message}", line)
        null
    } catch (e: YamlEngineException) {
        problemAt("invalid YAML: ${e.message}", null)
        null
    }

    /** Mapping entries by key; non-scalar or repeated keys are reported. */
    fun entries(mapping: MappingNode): Map<String, Entry> {
        val result = LinkedHashMap<String, Entry>()
        for (tuple in mapping.value) {
            val keyNode = tuple.keyNode
            val key = (keyNode as? ScalarNode)?.value
            if (key == null) {
                problem("mapping keys must be plain strings", keyNode)
                continue
            }
            if (key in result) {
                problem("duplicate key '$key'", keyNode)
                continue
            }
            result[key] = Entry(key, keyNode, tuple.valueNode)
        }
        return result
    }

    /** A non-null scalar as a string, or `null` with a problem. */
    fun string(entry: Entry): String? {
        val node = entry.value
        if (node is ScalarNode && !node.isNull()) return node.value
        problem("'${entry.key}' must be a string", entry.keyNode)
        return null
    }

    /** A list of strings; a single string is accepted as a one-element list, `null` as empty. */
    fun stringList(entry: Entry): List<String>? {
        val node = entry.value
        return when {
            node is ScalarNode && node.isNull() -> emptyList()
            node is ScalarNode -> listOf(node.value)
            node is SequenceNode -> {
                val items = node.value.map { item ->
                    if (item is ScalarNode && !item.isNull()) item.value.trim() else {
                        problem("'${entry.key}' items must be strings", item)
                        return null
                    }
                }
                if (items.any { it.isEmpty() }) {
                    problem("'${entry.key}' items must not be empty", entry.keyNode)
                    return null
                }
                items
            }
            else -> {
                problem("'${entry.key}' must be a list of strings", entry.keyNode)
                null
            }
        }
    }

    class Entry(val key: String, val keyNode: Node, val value: Node)

    private companion object {
        val SETTINGS: LoadSettings = LoadSettings.builder().setLabel("skill").build()
    }
}

internal fun ScalarNode.isNull(): Boolean = tag == Tag.NULL

/** `scheme://rest` with a URI-style scheme; `{placeholders}` are allowed in the rest. */
internal val DEEPLINK_FORM = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://\\S+$")
