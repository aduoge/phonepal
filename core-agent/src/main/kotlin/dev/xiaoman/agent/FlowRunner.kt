package dev.xiaoman.agent

import dev.xiaoman.api.Action
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.Flow
import dev.xiaoman.api.FlowStep
import dev.xiaoman.api.UserChannel
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/** Runs a skill's `flow.yaml` steps; device actions go through [OperatorLoop.execute] and so through Sentinel. */
internal class FlowRunner(
    private val loop: OperatorLoop,
    private val users: UserChannel,
) {
    suspend fun run(task: TaskRun, flow: Flow): LoopOutcome {
        var summary: String? = null
        for (step in flow.steps) {
            if (task.stop.stopped) return LoopOutcome(false, Prompts.STOPPED)
            val outcome = when (step) {
                is FlowStep.Launch -> act(task, Action.LaunchApp(step.packageName, target = "打开 ${step.packageName}"))
                is FlowStep.Deeplink -> {
                    val template = task.skill?.deeplinks?.get(step.keyOrUri) ?: step.keyOrUri
                    val uri = fill(task, template, urlEncode = true)
                    val label = if (template != step.keyOrUri) step.keyOrUri else null
                    act(task, Action.OpenDeeplink(uri, task.skill?.apps?.firstOrNull(), target = label))
                }
                is FlowStep.Ai -> loop.run(task, subGoal = fill(task, step.goal, urlEncode = false))
                is FlowStep.Confirm -> confirm(task, step.question)
                is FlowStep.Tap -> loop.run(task, constraint = StepConstraint("tap", step.target), maxSteps = SINGLE_STEP_TRIES)
                is FlowStep.Type -> {
                    val text = fill(task, step.text, urlEncode = false)
                    loop.run(task, constraint = StepConstraint("type", step.target, text), maxSteps = SINGLE_STEP_TRIES)
                }
                is FlowStep.Wait -> act(task, Action.Wait(step.millis))
                is FlowStep.Assert -> loop.check(task, step.expectation)
            }
            if (!outcome.success) return outcome
            if (step is FlowStep.Ai) summary = outcome.summary
        }
        return LoopOutcome(true, summary ?: Prompts.FLOW_DONE)
    }

    private suspend fun act(task: TaskRun, action: Action): LoopOutcome {
        task.emit(AgentEvent.StepPlanned(task.taskId, task.steps, action))
        return when (val r = loop.execute(task, action, screen = null)) {
            is Executed.Blocked -> LoopOutcome(false, "操作被拦截：${r.decision.reason}")
            is Executed.Done -> LoopOutcome(r.result.ok, if (r.result.ok) ScreenText.describe(action) else (r.result.message ?: "执行失败"))
        }
    }

    private suspend fun confirm(task: TaskRun, question: String): LoopOutcome {
        task.emit(AgentEvent.NeedsUser(task.taskId, question, takeover = false))
        val answer = users.ask(task.taskId, question, false)
        return if (isYes(answer)) LoopOutcome(true, answer) else LoopOutcome(false, Prompts.CONFIRM_DECLINED)
    }

    /**
     * Replaces `{name}` placeholders: values already known in the task, else asked from the operator
     * model as `{"values": {...}}` given goal and skill memory, else asked from the user.
     */
    suspend fun fill(task: TaskRun, template: String, urlEncode: Boolean): String {
        val names = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.distinct().toList()
        if (names.isEmpty()) return template
        val missing = names.filter { it !in task.values }
        if (missing.isNotEmpty()) {
            val request = ChatRequest(
                listOf(ChatMessage.system(Prompts.VALUES_SYSTEM), ChatMessage.user(Prompts.valuesRequest(task.goal, missing, task.references))),
                jsonMode = true,
            )
            val values = JsonText.firstObject(loop.completeWithRetry(request).text)?.let { it["values"] as? JsonObject ?: it }
            for (name in missing) {
                val v = values?.let { JsonText.string(it, name) }
                    ?: run {
                        val q = Prompts.askValue(name)
                        task.emit(AgentEvent.NeedsUser(task.taskId, q, takeover = false))
                        users.ask(task.taskId, q, false).trim()
                    }
                task.values[name] = v
            }
        }
        return PLACEHOLDER.replace(template) { m ->
            val v = task.values[m.groupValues[1]].orEmpty()
            if (urlEncode) URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20") else v
        }
    }

    companion object {
        private val PLACEHOLDER = Regex("\\{([A-Za-z0-9_\\-\\p{IsHan}]+)\\}")
        private val YES_ZH = listOf("是", "好", "确认", "确定", "可以", "行", "对", "嗯")
        private val YES_EN = listOf("yes", "ok", "okay", "y", "sure", "yeah")
        private const val SINGLE_STEP_TRIES = 3

        /** 是/好/确认/yes/ok and close variants (好的, 确认呼叫, OK!) count as yes; 不…/no/别… do not. */
        fun isYes(answer: String): Boolean {
            val a = answer.trim().lowercase()
            if (a.isEmpty() || a.startsWith("不") || a.startsWith("别") || a.startsWith("no")) return false
            if (YES_ZH.any { a.startsWith(it) }) return true
            return YES_EN.any { a.startsWith(it) && (a.length == it.length || !a[it.length].isLetter()) }
        }
    }
}
