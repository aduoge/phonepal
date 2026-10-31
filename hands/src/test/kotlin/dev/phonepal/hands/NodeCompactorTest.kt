package dev.phonepal.hands

import dev.phonepal.api.Bounds
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NodeCompactorTest {
    private class N(val facts: NodeFacts, vararg val children: N)

    private class Tree : NodeTree<N> {
        val released = mutableListOf<N>()
        override fun facts(node: N) = node.facts
        override fun childCount(node: N) = node.children.size
        override fun child(node: N, index: Int) = node.children[index]
        override fun release(node: N) { released += node }
    }

    private fun n(text: String? = null, rect: PxRect = PxRect(0, 0, 100, 100), visible: Boolean = true,
                  clickable: Boolean = false, editable: Boolean = false, scrollable: Boolean = false,
                  password: Boolean = false, desc: String? = null, cls: String? = null, vararg children: N) =
        N(NodeFacts(text = text, description = desc, className = cls, boundsPx = rect, visible = visible, clickable = clickable,
            editable = editable, scrollable = scrollable, password = password), *children)

    @Test fun preOrderKeepsOnlyInterestingVisibleNodes() {
        val title = n("打车")
        val hidden = n("hidden", visible = false)
        val button = n(clickable = true, rect = PxRect(0, 1200, 1080, 1320), cls = "android.widget.Button")
        val plain = n()
        val list = n(scrollable = true, rect = PxRect(0, 200, 1080, 2400), children = arrayOf(button, plain))
        val root = n(rect = PxRect(0, 0, 1080, 2400), children = arrayOf(title, hidden, list))
        val tree = Tree()

        val out = NodeCompactor().compact(listOf(root), tree, 1080, 2400)

        assertEquals(listOf(title, list, button), out.map { it.source })
        assertEquals(listOf(0, 1, 2), out.map { it.ui.id })
        assertEquals("打车", out[0].ui.text)
        assertTrue(out[1].ui.scrollable)
        assertEquals(Bounds(0, 500, 1000, 550), out[2].ui.bounds)
        assertEquals("Button", out[2].ui.className)
        assertTrue(out[2].ui.clickable)
        // Children not kept are released; the root is the caller's business.
        assertEquals(setOf(hidden, plain), tree.released.toSet())
    }

    @Test fun invisibleContainerChildrenAreStillVisited() {
        val inner = n("visible child")
        val root = n(visible = false, children = arrayOf(inner))
        val out = NodeCompactor().compact(listOf(root), Tree(), 1080, 2400)
        assertEquals(listOf(inner), out.map { it.source })
    }

    @Test fun capAtMaxNodesAcrossRoots() {
        val a = n(children = Array(5) { n("a$it") })
        val b = n(children = Array(5) { n("b$it") })
        val out = NodeCompactor(maxNodes = 7).compact(listOf(a, b), Tree(), 1080, 2400)
        assertEquals(listOf("a0", "a1", "a2", "a3", "a4", "b0", "b1"), out.map { it.ui.text })
        assertEquals((0 until 7).toList(), out.map { it.ui.id })
    }

    @Test fun offscreenNodesDropped() {
        val out = NodeCompactor().compact(listOf(n("below", rect = PxRect(0, 2500, 100, 2600))), Tree(), 1080, 2400)
        assertTrue(out.isEmpty())
    }

    @Test fun passwordTextHiddenAndWhitespaceCollapsed() {
        val pwd = n("secret", editable = true, password = true)
        val messy = n("  hello\n\n  world  ", desc = "   ")
        val long = n("x".repeat(500))
        val out = NodeCompactor(maxTextLength = 10).compact(listOf(pwd, messy, long), Tree(), 1080, 2400)
        assertNull(out[0].ui.text)
        assertTrue(out[0].ui.password && out[0].ui.editable)
        assertEquals("hello worl…", out[1].ui.text)
        assertNull(out[1].ui.description)
        assertEquals(11, out[2].ui.text!!.length)
        assertFalse(out[2].ui.clickable)
    }
}
