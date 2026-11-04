package dev.xiaoman.skills

import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillChannel
import dev.xiaoman.api.SkillProblem
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.ScalarNode

/**
 * Parses and validates skill folders (see [Skill]). Pure: strings in, so the same code loads skills
 * from directories, Android assets or tests.
 *
 * A skill is returned only when it has no problems, with one exception: a frontmatter `name` that
 * differs from the folder name is reported but the skill still loads under its frontmatter name.
 * Problem sources are `<source>/SKILL.md` or `<source>/flow.yaml`; lines are 1-based file lines.
 */
object SkillParser {
    val NAME: Regex = Regex("^[a-z0-9-]+$")

    val KEYS: List<String> = listOf("name", "description", "apps", "channel", "confirm_before", "memory", "deeplinks")

    /**
     * Parses one skill folder.
     * @param name the folder name; the frontmatter `name` should equal it.
     * @param flowYaml contents of `flow.yaml`, or `null` when the folder has none.
     * @param source folder path or URI, stored in [Skill.source].
     */
    fun parseFolder(name: String, skillMd: String, flowYaml: String?, source: String): Pair<Skill?, List<SkillProblem>> {
        val (skill, problems) = parse(skillMd, source)
        val all = problems.toMutableList()
        if (skill != null && skill.name != name) {
            all += SkillProblem("$source/SKILL.md", "name '${skill.name}' does not match folder name '$name'", nameLine(skillMd))
        }
        // A flow is checked against the skill's deeplinks and apps, so it is only parsed once SKILL.md is valid.
        if (skill == null || flowYaml == null) return skill to all
        val (flow, flowProblems) = FlowParser.parse(flowYaml, "$source/flow.yaml", skill.deeplinks, skill.apps)
        all += flowProblems
        return flow?.let { skill.copy(flow = it) } to all
    }

    /** Parses `SKILL.md` alone (no folder-name check, no flow). */
    fun parse(skillMd: String, source: String): Pair<Skill?, List<SkillProblem>> {
        val doc = YamlDoc("$source/SKILL.md", lineOffset = 1)
        val split = splitFrontmatter(skillMd)
        if (split == null) {
            doc.problemAt("missing frontmatter: SKILL.md must start with a '---' line and close it with another '---'", 1)
            return null to doc.problems
        }
        val (yaml, body) = split
        val root = doc.compose(yaml)
        if (root != null && root !is MappingNode) {
            doc.problem("frontmatter must be a mapping of keys", root)
            return null to doc.problems
        }
        if (doc.problems.isNotEmpty()) return null to doc.problems
        val entries = (root as? MappingNode)?.let(doc::entries).orEmpty()
        for ((key, entry) in entries) if (key !in KEYS) doc.problem("unknown key '$key'; expected one of ${KEYS.joinToString()}", entry.keyNode)

        val name = entries["name"]?.let(doc::string)?.trim()
        when {
            entries["name"] == null -> doc.problemAt("missing required key 'name'", 1)
            name == null -> Unit
            !NAME.matches(name) -> doc.problem("name '$name' must match [a-z0-9-]+", entries.getValue("name").keyNode)
        }

        val descriptionEntry = entries["description"]
        val description = descriptionEntry?.let(doc::string)?.trim()
        when {
            descriptionEntry == null -> doc.problemAt("missing required key 'description'", 1)
            description == null -> Unit
            description.isEmpty() -> doc.problem("'description' must not be empty", descriptionEntry.keyNode)
            '\n' in description -> doc.problem("'description' must be a single line", descriptionEntry.keyNode)
        }

        val apps = entries["apps"]?.let { entry ->
            doc.stringList(entry)?.also { list ->
                list.filterNot(PACKAGE_NAME::matches).forEach { doc.problem("apps: '$it' is not a package name", entry.keyNode) }
            }
        }.orEmpty()

        val channel = entries["channel"]?.let { entry ->
            doc.string(entry)?.let { wire ->
                SkillChannel.fromWire(wire) ?: run {
                    doc.problem("unknown channel '$wire'; expected one of ${SkillChannel.entries.joinToString { it.wire }}", entry.keyNode)
                    null
                }
            }
        } ?: SkillChannel.GUI

        val confirmBefore = entries["confirm_before"]?.let(doc::stringList).orEmpty()
        val memory = entries["memory"]?.let(doc::stringList).orEmpty()
        val deeplinks = entries["deeplinks"]?.let { parseDeeplinks(doc, it) }.orEmpty()

        if (doc.problems.isNotEmpty() || name == null || description == null) return null to doc.problems
        return Skill(
            name = name,
            description = description,
            apps = apps,
            channel = channel,
            confirmBefore = confirmBefore,
            memoryRefs = memory,
            deeplinks = deeplinks,
            instructions = body,
            source = source,
        ) to emptyList()
    }

    private fun parseDeeplinks(doc: YamlDoc, entry: YamlDoc.Entry): Map<String, String> {
        val node = entry.value
        if (node is ScalarNode && node.isNull()) return emptyMap()
        if (node !is MappingNode) {
            doc.problem("'deeplinks' must be a mapping of name: \"scheme://...\"", entry.keyNode)
            return emptyMap()
        }
        val result = LinkedHashMap<String, String>()
        for ((key, link) in doc.entries(node)) {
            val value = (link.value as? ScalarNode)?.takeUnless { it.isNull() }?.value?.trim()
            when {
                key.isBlank() -> doc.problem("deeplinks: names must not be empty", link.keyNode)
                value == null -> doc.problem("deeplinks: '$key' must be a string", link.keyNode)
                !DEEPLINK_FORM.matches(value) -> doc.problem("deeplinks: '$key' = '$value' is not a scheme:// URI", link.keyNode)
                else -> result[key] = value
            }
        }
        return result
    }

    /** Splits `---\n<yaml>\n---\n<body>`; `null` when there is no closed frontmatter block. */
    internal fun splitFrontmatter(text: String): Pair<String, String>? {
        val lines = text.removePrefix("\uFEFF").lines()
        if (lines.firstOrNull()?.trimEnd() != "---") return null
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" || lines[it].trimEnd() == "..." } ?: return null
        val yaml = lines.subList(1, end).joinToString("\n")
        val body = lines.subList(end + 1, lines.size).joinToString("\n").trim()
        return yaml to body
    }

    private fun nameLine(skillMd: String): Int? =
        skillMd.removePrefix("\uFEFF").lines().indexOfFirst { it.startsWith("name:") }.takeIf { it >= 0 }?.plus(1)
}
