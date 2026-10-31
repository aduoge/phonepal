package dev.phonepal.skills

import dev.phonepal.api.Flow
import dev.phonepal.api.FlowStep
import dev.phonepal.api.SkillProblem
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode

/**
 * Parses `flow.yaml` (see [Flow]). Pure: text in, flow and problems out. The flow is `null` whenever
 * any problem was found, so a half-understood flow never runs.
 */
object FlowParser {
    /** Longest accepted `wait`, in milliseconds. */
    const val MAX_WAIT_MILLIS: Long = 600_000

    val STEP_KINDS: List<String> = listOf("launch", "deeplink", "ai", "confirm", "tap", "type", "wait", "assert")

    /**
     * @param deeplinks the skill's named deep links; a `deeplink:` step must name one of them or be a literal URI.
     * @param apps the skill's `apps`; when non-empty, a `launch:` step must name one of them (Sentinel would deny it).
     */
    fun parse(
        text: String,
        source: String,
        deeplinks: Map<String, String> = emptyMap(),
        apps: List<String> = emptyList(),
    ): Pair<Flow?, List<SkillProblem>> {
        val doc = YamlDoc(source)
        val root = doc.compose(text)
        if (root == null) {
            if (doc.problems.isEmpty()) doc.problem("flow.yaml is empty; expected 'steps:'")
            return null to doc.problems
        }
        if (root !is MappingNode) {
            doc.problem("flow.yaml must be a mapping with 'steps:'", root)
            return null to doc.problems
        }
        val entries = doc.entries(root)
        for ((key, entry) in entries) if (key != "steps") doc.problem("unknown key '$key'; only 'steps' is allowed", entry.keyNode)
        val stepsEntry = entries["steps"]
        val stepsNode = stepsEntry?.value
        when {
            stepsEntry == null -> doc.problem("missing 'steps:' list", root)
            stepsNode !is SequenceNode -> doc.problem("'steps' must be a list", stepsEntry.keyNode)
            stepsNode.value.isEmpty() -> doc.problem("'steps' is empty", stepsEntry.keyNode)
        }
        val steps = (stepsNode as? SequenceNode)?.value.orEmpty().mapIndexedNotNull { index, node ->
            parseStep(doc, index + 1, node, deeplinks, apps)
        }
        return (if (doc.problems.isEmpty()) Flow(steps) else null) to doc.problems.toList()
    }

    private fun parseStep(doc: YamlDoc, number: Int, node: Node, deeplinks: Map<String, String>, apps: List<String>): FlowStep? {
        if (node !is MappingNode || node.value.size != 1) {
            doc.problem("step $number: expected one 'kind: value' pair, e.g. '- tap: 确认呼叫'", node)
            return null
        }
        val entry = doc.entries(node).values.singleOrNull() ?: return null
        val kind = entry.key
        fun bad(message: String): FlowStep? {
            doc.problem("step $number ($kind): $message", entry.keyNode)
            return null
        }
        fun text(): String? {
            val value = entry.value
            return if (value is ScalarNode && !value.isNull() && value.value.isNotBlank()) value.value.trim() else null
        }
        return when (kind) {
            "launch" -> {
                val pkg = text() ?: return bad("expected a package name")
                when {
                    !PACKAGE_NAME.matches(pkg) -> bad("'$pkg' is not a package name")
                    apps.isNotEmpty() && pkg !in apps -> bad("'$pkg' is not in the skill's apps")
                    else -> FlowStep.Launch(pkg)
                }
            }
            "deeplink" -> {
                val value = text() ?: return bad("expected a deeplinks key or a URI")
                if (value in deeplinks || DEEPLINK_FORM.matches(value)) FlowStep.Deeplink(value)
                else bad("'$value' is neither a key of the skill's deeplinks nor a scheme:// URI")
            }
            "ai" -> text()?.let(FlowStep::Ai) ?: bad("expected a sub-goal text")
            "confirm" -> text()?.let(FlowStep::Confirm) ?: bad("expected a question text")
            "tap" -> text()?.let(FlowStep::Tap) ?: bad("expected a target description")
            "assert" -> text()?.let(FlowStep::Assert) ?: bad("expected the text the screen must show")
            "wait" -> {
                val millis = (entry.value as? ScalarNode)?.takeUnless { it.isNull() }?.value?.trim()?.toLongOrNull()
                if (millis == null || millis < 0 || millis > MAX_WAIT_MILLIS) bad("expected milliseconds as an integer 0..$MAX_WAIT_MILLIS")
                else FlowStep.Wait(millis)
            }
            "type" -> parseType(doc, entry) ?: bad("expected { target: ..., text: ... }")
            else -> bad("unknown step kind; expected one of ${STEP_KINDS.joinToString()}")
        }
    }

    private fun parseType(doc: YamlDoc, entry: YamlDoc.Entry): FlowStep.Type? {
        val value = entry.value as? MappingNode ?: return null
        val fields = doc.entries(value)
        val unknown = fields.keys - setOf("target", "text")
        if (unknown.isNotEmpty()) return null
        val target = (fields["target"]?.value as? ScalarNode)?.takeUnless { it.isNull() }?.value?.trim()
        val text = (fields["text"]?.value as? ScalarNode)?.takeUnless { it.isNull() }?.value
        if (target.isNullOrEmpty() || text == null) return null
        return FlowStep.Type(target, text)
    }
}

/** Android application id: two or more dot-separated Java identifiers. */
internal val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
