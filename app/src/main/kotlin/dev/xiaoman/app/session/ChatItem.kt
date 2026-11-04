package dev.xiaoman.app.session

import dev.xiaoman.api.Action
import dev.xiaoman.api.AgentEvent

/** One row of the chat list. */
sealed class ChatItem {
    abstract val id: Long

    data class User(override val id: Long, val text: String) : ChatItem()

    data class Assistant(override val id: Long, val text: String) : ChatItem()

    /** Compact progress line: steps, skill chosen, memory updated, task result. */
    data class Progress(override val id: Long, val text: String, val ok: Boolean? = null) : ChatItem()

    data class Question(override val id: Long, val text: String, val takeover: Boolean) : ChatItem()

    data class Error(override val id: Long, val text: String) : ChatItem()
}

/** Human-readable (Chinese) text for agent events, used by the chat list and the status overlay. */
object StepText {
    fun action(action: Action): String = when (action) {
        is Action.Tap -> "点击" + where(action.target, action.nodeId)
        is Action.LongPress -> "长按" + where(action.target, action.nodeId)
        is Action.Swipe -> "滑动" + where(action.target, null)
        is Action.TypeText -> "输入「${action.text}」" + where(action.target, action.nodeId)
        is Action.Back -> "返回"
        is Action.Home -> "回到桌面"
        is Action.LaunchApp -> "打开 ${action.packageName}"
        is Action.OpenDeeplink -> "打开链接 ${action.uri.substringBefore('?')}"
        is Action.Wait -> "等待 ${action.millis} 毫秒"
        is Action.AskUser -> "询问：${action.question}"
        is Action.Finish -> "结束：${action.summary}"
    }

    private fun where(target: String?, nodeId: Int?): String = when {
        !target.isNullOrBlank() -> " $target"
        nodeId != null -> " #$nodeId"
        else -> ""
    }

    /** Overlay / notification status for [event], or null to keep the previous one. */
    fun status(event: AgentEvent): String? = when (event) {
        is AgentEvent.PhoneTaskStarted -> "开始：${event.goal}"
        is AgentEvent.StepPlanned -> "第 ${event.stepIndex + 1} 步：${action(event.action)}"
        is AgentEvent.StepBlocked -> "第 ${event.stepIndex + 1} 步已拦截"
        is AgentEvent.NeedsUser -> if (event.takeover) "请接管手机，完成后回到小满回复" else "等待你回答：${event.question}"
        is AgentEvent.PhoneTaskFinished -> if (event.success) "已完成" else "未完成"
        else -> null
    }

    /** The chat row for [event], or null when it only updates the status. */
    fun chatItem(event: AgentEvent, id: Long): ChatItem? = when (event) {
        is AgentEvent.Reply -> ChatItem.Assistant(id, event.text)
        is AgentEvent.SkillChosen -> ChatItem.Progress(id, "使用技能 ${event.skillName}")
        is AgentEvent.PhoneTaskStarted -> ChatItem.Progress(id, "开始手机任务：${event.goal}")
        is AgentEvent.StepPlanned -> null
        is AgentEvent.StepExecuted -> {
            val failure = event.result.message?.takeIf { !event.result.ok }?.let { "（$it）" }.orEmpty()
            ChatItem.Progress(id, "${event.stepIndex + 1}. ${action(event.action)}$failure", event.result.ok)
        }
        is AgentEvent.StepBlocked -> ChatItem.Progress(id, "${event.stepIndex + 1}. ${action(event.action)}：已拦截（${event.decision.reason}）", false)
        is AgentEvent.NeedsUser -> ChatItem.Question(id, event.question, event.takeover)
        is AgentEvent.MemoryUpdated -> ChatItem.Progress(id, "已更新记忆 ${event.permalink}")
        is AgentEvent.PhoneTaskFinished -> ChatItem.Progress(
            id,
            (if (event.success) "任务完成" else "任务未完成") + "（${event.steps} 步）：${event.summary}",
            event.success,
        )
        is AgentEvent.Failed -> ChatItem.Error(id, event.message)
    }
}
