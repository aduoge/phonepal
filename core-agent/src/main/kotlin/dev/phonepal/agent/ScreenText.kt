package dev.phonepal.agent

import dev.phonepal.api.Action
import dev.phonepal.api.ScreenState
import dev.phonepal.api.UiNode

/** Text views of screens and actions for prompts, history and learning notes. */
internal object ScreenText {
    fun section(screen: ScreenState, maxNodes: Int): String {
        val shown = screen.nodes.take(maxNodes)
        return Prompts.screenSection(
            screen.packageName,
            screen.activityName,
            shown.joinToString("\n") { nodeLine(it) },
            screen.nodes.size - shown.size,
        )
    }

    /** `[id] class "text" desc=... (l,t,r,b) clickable editable ...` */
    fun nodeLine(n: UiNode): String = buildString {
        append('[').append(n.id).append("] ").append(n.className?.substringAfterLast('.') ?: "View")
        clean(n.text)?.let { append(" \"").append(it).append('"') }
        clean(n.description)?.let { append(" desc=").append(it) }
        n.resourceId?.substringAfter(":id/")?.takeIf { it.isNotBlank() }?.let { append(" res=").append(it) }
        val b = n.bounds
        append(" (").append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom).append(')')
        if (n.clickable) append(" clickable")
        if (n.editable) append(" editable")
        if (n.scrollable) append(" scrollable")
        if (n.focused) append(" focused")
        if (n.password) append(" password")
    }

    private fun clean(s: String?): String? =
        s?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > 60) it.take(60) + "…" else it }

    fun describe(action: Action): String = when (action) {
        is Action.Tap -> "点击 " + (action.target ?: action.nodeId?.let { "节点 $it" } ?: action.point.toString())
        is Action.LongPress -> "长按 " + (action.target ?: action.nodeId?.let { "节点 $it" } ?: action.point.toString())
        is Action.Swipe -> "滑动 (${action.from.x},${action.from.y})→(${action.to.x},${action.to.y})"
        is Action.TypeText -> "输入「${action.text}」" + (action.target?.let { " 到 $it" } ?: "")
        is Action.Back -> "返回"
        is Action.Home -> "回到桌面"
        is Action.LaunchApp -> "打开 ${action.packageName}"
        is Action.OpenDeeplink -> "打开链接 ${action.uri}"
        is Action.Wait -> "等待 ${action.millis}ms"
        is Action.AskUser -> "询问用户：${action.question}"
        is Action.Finish -> "结束：${action.summary}"
    }

    /** Short label of a successful action for the learned path, or null for actions not worth recording. */
    fun pathLabel(action: Action, screen: ScreenState?): String? {
        fun nodeText(id: Int?) = id?.let { i -> screen?.nodes?.firstOrNull { it.id == i } }?.let { clean(it.text) ?: clean(it.description) }
        val target = clean(action.target)
        return when (action) {
            is Action.Tap -> target ?: nodeText(action.nodeId)
            is Action.LongPress -> "长按 " + (target ?: nodeText(action.nodeId) ?: "")
            is Action.TypeText -> "输入" + (target ?: nodeText(action.nodeId) ?: "")
            is Action.LaunchApp -> target ?: "打开 ${action.packageName}"
            is Action.OpenDeeplink -> target ?: "打开链接"
            is Action.Swipe -> target ?: "滑动"
            is Action.Back -> "返回"
            is Action.Home, is Action.Wait, is Action.AskUser, is Action.Finish -> null
        }
    }
}
