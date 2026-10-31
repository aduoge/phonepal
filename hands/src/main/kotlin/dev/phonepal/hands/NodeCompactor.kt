package dev.phonepal.hands

import dev.phonepal.api.UiNode

/** The facts about one accessibility node that the compactor needs; independent of Android. */
data class NodeFacts(
    val text: String? = null,
    val description: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val boundsPx: PxRect,
    val visible: Boolean = true,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val password: Boolean = false,
)

/** Adapts a tree of [T] (AccessibilityNodeInfo on the phone, plain objects in tests). */
interface NodeTree<T> {
    fun facts(node: T): NodeFacts
    fun childCount(node: T): Int

    /** May return null for children that vanished meanwhile. */
    fun child(node: T, index: Int): T?

    /** Called for every node obtained from [child] that was not kept in the result. */
    fun release(node: T) {}
}

/** A kept node: the compact [UiNode] plus the original node and its pixel bounds. */
data class CompactNode<T>(val ui: UiNode, val source: T, val boundsPx: PxRect)

/**
 * Turns accessibility trees into the compact node list for the operator model:
 * pre-order traversal, keep visible nodes that carry text or a description or can be clicked,
 * edited or scrolled, normalize bounds to 0..1000 and stop at [maxNodes]. Ids are list indexes.
 */
class NodeCompactor(
    private val maxNodes: Int = DEFAULT_MAX_NODES,
    private val maxTextLength: Int = DEFAULT_MAX_TEXT,
    private val maxDepth: Int = 64,
) {
    fun <T> compact(roots: List<T>, tree: NodeTree<T>, widthPx: Int, heightPx: Int): List<CompactNode<T>> {
        val out = ArrayList<CompactNode<T>>()
        for (root in roots) {
            if (out.size >= maxNodes) break
            visit(root, 0, tree, widthPx, heightPx, out)
        }
        return out
    }

    /** Returns true when [node] was kept (so the caller must not release it). */
    private fun <T> visit(node: T, depth: Int, tree: NodeTree<T>, w: Int, h: Int, out: MutableList<CompactNode<T>>): Boolean {
        val facts = tree.facts(node)
        var kept = false
        if (facts.visible && isInteresting(facts)) {
            val bounds = Coordinates.normalize(facts.boundsPx, w, h)
            if (bounds != null) {
                out += CompactNode(toUiNode(out.size, facts, bounds), node, facts.boundsPx)
                kept = true
            }
        }
        // Invisible containers can still hold visible children (e.g. clipped scroll content); keep walking.
        if (depth < maxDepth) {
            val n = tree.childCount(node)
            for (i in 0 until n) {
                if (out.size >= maxNodes) break
                val child = tree.child(node, i) ?: continue
                if (!visit(child, depth + 1, tree, w, h, out)) tree.release(child)
            }
        }
        return kept
    }

    private fun isInteresting(f: NodeFacts): Boolean =
        !f.text.isNullOrBlank() || !f.description.isNullOrBlank() ||
            f.clickable || f.longClickable || f.editable || f.scrollable

    private fun toUiNode(id: Int, f: NodeFacts, bounds: dev.phonepal.api.Bounds) = UiNode(
        id = id,
        // Never send the content of password fields to the model.
        text = if (f.password) null else f.text.clean(),
        description = f.description.clean(),
        resourceId = f.resourceId?.takeIf { it.isNotBlank() },
        className = f.className?.substringAfterLast('.')?.takeIf { it.isNotBlank() },
        bounds = bounds,
        clickable = f.clickable || f.longClickable,
        editable = f.editable,
        scrollable = f.scrollable,
        focused = f.focused,
        password = f.password,
    )

    private fun String?.clean(): String? {
        val s = this?.replace(WHITESPACE, " ")?.trim()
        if (s.isNullOrEmpty()) return null
        return if (s.length > maxTextLength) s.take(maxTextLength) + "…" else s
    }

    companion object {
        const val DEFAULT_MAX_NODES = 200
        const val DEFAULT_MAX_TEXT = 200
        private val WHITESPACE = Regex("\\s+")
    }
}
