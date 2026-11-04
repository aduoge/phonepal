package dev.xiaoman.hands

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/** [NodeTree] over live [AccessibilityNodeInfo] objects. */
internal object AccessibilityNodeTree : NodeTree<AccessibilityNodeInfo> {
    override fun facts(node: AccessibilityNodeInfo): NodeFacts {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return NodeFacts(
            text = node.text?.toString(),
            description = node.contentDescription?.toString() ?: node.hintTextCompat(),
            resourceId = node.viewIdResourceName,
            className = node.className?.toString(),
            boundsPx = PxRect(rect.left, rect.top, rect.right, rect.bottom),
            visible = node.isVisibleToUser,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            focused = node.isFocused,
            password = node.isPassword,
        )
    }

    override fun childCount(node: AccessibilityNodeInfo): Int = node.childCount

    override fun child(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? = node.getChild(index)

    override fun release(node: AccessibilityNodeInfo) = recycleCompat(node)

    /** Empty editable fields usually expose their placeholder only as hint text. */
    private fun AccessibilityNodeInfo.hintTextCompat(): String? =
        if (isEditable && text.isNullOrEmpty()) hintText?.toString() else null

    /** recycle() is a no-op (and deprecated) from API 33; before that it returns the object to the pool. */
    @Suppress("DEPRECATION")
    fun recycleCompat(node: AccessibilityNodeInfo?) {
        if (node != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching { node.recycle() }
        }
    }
}
