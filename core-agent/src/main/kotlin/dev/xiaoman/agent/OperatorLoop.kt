package dev.xiaoman.agent

import dev.xiaoman.api.Action
import dev.xiaoman.api.ActionResult
import dev.xiaoman.api.AgentConfig
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ChatResponse
import dev.xiaoman.api.ChatRole
import dev.xiaoman.api.ContentPart
import dev.xiaoman.api.Decision
import dev.xiaoman.api.Device
import dev.xiaoman.api.GuardContext
import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.ScreenState
import dev.xiaoman.api.Sentinel
import dev.xiaoman.api.UserChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The operator loop of docs/ARCHITECTURE.md: observe, prompt the operator model, parse, gate
 * through Sentinel, perform, detect stuck screens. Also hosts single-step and screen checks used by flows.
 */
internal class OperatorLoop(
    private val operator: LlmClient,
    private val device: Device,
    private val sentinel: Sentinel,
    private val users: UserChannel,
    private val config: AgentConfig,
) {
    /**
     * Runs until finish, failure or [maxSteps] iterations. [subGoal] replaces the task goal as the
     * immediate objective (flow `ai` steps); [constraint] makes it a single constrained step.
     */
    suspend fun run(
        task: TaskRun,
        subGoal: String? = null,
        constraint: StepConstraint? = null,
        maxSteps: Int = config.maxSteps,
    ): LoopOutcome {
        val history = mutableListOf<String>()
        val feedback = mutableListOf<String>()
        var parseFailures = 0
        var actedOn: String? = null // fingerprint of the screen the last action ran on
        var acted = false
        var sameAfterActing = 0
        var warnedStuck = false

        for (iteration in 0 until maxSteps) {
            if (task.stop.stopped) return LoopOutcome(false, Prompts.STOPPED)
            val screen = device.observe()
            screen.packageName?.let { task.lastPackage = it }

            if (screen.secure) {
                task.emit(AgentEvent.NeedsUser(task.taskId, Prompts.SECURE_SCREEN, takeover = true))
                val answer = users.ask(task.taskId, Prompts.SECURE_SCREEN, true)
                history += Prompts.takeoverDone(answer)
                acted = false
                continue
            }

            val fp = screen.fingerprint()
            if (acted) {
                sameAfterActing = if (fp == actedOn) sameAfterActing + 1 else 0
                acted = false
            }
            if (sameAfterActing > 0 && sameAfterActing + 1 >= config.stuckRepeats) {
                if (warnedStuck) return LoopOutcome(false, Prompts.STUCK_FAILED)
                warnedStuck = true
                sameAfterActing = 0
                feedback += Prompts.STUCK_WARNING
            }

            val request = operatorRequest(task, subGoal, constraint, screen, history, feedback)
            val reply = try {
                completeWithRetry(request)
            } catch (e: LlmException) {
                val message = Prompts.operatorLlmFailed(e)
                task.emit(AgentEvent.Failed(task.taskId, message))
                return LoopOutcome(false, message)
            }
            feedback.clear()

            var action = when (val parsed = OperatorReplyParser.parse(reply.text)) {
                is ParsedReply.Error -> {
                    if (++parseFailures >= PARSE_FAILURE_LIMIT) return LoopOutcome(false, Prompts.PARSE_FAILED)
                    feedback += Prompts.parseError(parsed.message)
                    continue
                }
                is ParsedReply.Ok -> parsed.action
            }

            if (constraint != null && action !is Action.Finish) {
                val matches = when (constraint.kind) {
                    "tap" -> action is Action.Tap
                    "type" -> action is Action.TypeText
                    else -> true
                }
                if (!matches) {
                    feedback += Prompts.wrongSingleStepKind(constraint.kind)
                    continue
                }
                if (action is Action.TypeText && constraint.text != null) action = action.copy(text = constraint.text)
            }

            val nodeId = when (action) {
                is Action.Tap -> action.nodeId
                is Action.LongPress -> action.nodeId
                is Action.TypeText -> action.nodeId
                else -> null
            }
            if (nodeId != null && screen.nodes.none { it.id == nodeId }) {
                feedback += Prompts.unknownNode(nodeId)
                continue
            }

            task.emit(AgentEvent.StepPlanned(task.taskId, task.steps, action))
            when (action) {
                is Action.Finish -> return LoopOutcome(action.success, action.summary)
                is Action.AskUser -> {
                    task.emit(AgentEvent.NeedsUser(task.taskId, action.question, action.takeover))
                    val answer = users.ask(task.taskId, action.question, action.takeover)
                    history += Prompts.askedUser(action.question, answer)
                    continue
                }
                else -> Unit
            }

            val index = task.steps
            when (val executed = execute(task, action, screen)) {
                is Executed.Blocked -> {
                    feedback += Prompts.blocked(executed.decision)
                    history += Prompts.stepLine(index, ScreenText.describe(action), "被拦截：${executed.decision.reason}")
                }
                is Executed.Done -> {
                    val r = executed.result
                    history += Prompts.stepLine(index, ScreenText.describe(action), if (r.ok) "成功" else "失败：${r.message ?: "未知原因"}")
                    if (!r.ok) feedback += "上一步执行失败：${r.message ?: "未知原因"}"
                    acted = true
                    actedOn = fp
                    if (constraint != null) {
                        return LoopOutcome(r.ok, if (r.ok) ScreenText.describe(action) else (r.message ?: "执行失败"))
                    }
                }
            }
        }
        return LoopOutcome(false, if (constraint != null) "没能完成：${constraint.target}" else Prompts.MAX_STEPS)
    }

    /** Gates [action] through Sentinel and performs it; emits StepBlocked or StepExecuted. */
    suspend fun execute(task: TaskRun, action: Action, screen: ScreenState?): Executed {
        val context = GuardContext(task.taskId, task.goal, task.skill, screen, task.steps)
        val needsConfirmation = sentinel.guard(context, action) is Decision.Ask
        if (!sentinel.authorize(context, action)) {
            val decision = sentinel.guard(context, action).let { if (it is Decision.Allow) Decision.Deny("未获授权") else it }
            task.emit(AgentEvent.StepBlocked(task.taskId, task.steps, action, decision))
            return Executed.Blocked(decision)
        }
        if (task.stop.stopped) {
            val decision = Decision.Deny(Prompts.STOPPED)
            task.emit(AgentEvent.StepBlocked(task.taskId, task.steps, action, decision))
            return Executed.Blocked(decision)
        }
        if (needsConfirmation && !awaitApprovalScreen(task, screen)) {
            val decision = Decision.Deny("确认期间画面发生变化，未执行原动作；请返回目标应用，重新观察和定位后再确认。")
            task.emit(AgentEvent.StepBlocked(task.taskId, task.steps, action, decision))
            return Executed.Blocked(decision)
        }
        if (task.stop.stopped) {
            val decision = Decision.Deny(Prompts.STOPPED)
            task.emit(AgentEvent.StepBlocked(task.taskId, task.steps, action, decision))
            return Executed.Blocked(decision)
        }
        val result = try {
            device.perform(action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult(false, e.message ?: e::class.simpleName)
        }
        task.emit(AgentEvent.StepExecuted(task.taskId, task.steps, action, result))
        task.steps++
        if (result.ok) {
            if (action is Action.LaunchApp && task.launchedPackage == null) task.launchedPackage = action.packageName
            task.addPath(ScreenText.pathLabel(action, screen))
        }
        return Executed.Done(result)
    }

    /** App-switch animations can outlast the approval card. Never tap until the original UI returns. */
    private suspend fun awaitApprovalScreen(task: TaskRun, before: ScreenState?): Boolean {
        repeat(5) { attempt ->
            if (task.stop.stopped) return false
            val after = device.observe()
            if (after.secure) return false
            if (ScreenText.matchesApprovalScreen(before, after)) return true
            if (attempt < 4) delay(300)
        }
        return false
    }

    /** Flow `assert`: asks the operator model whether the screen shows [expectation]. */
    suspend fun check(task: TaskRun, expectation: String): LoopOutcome {
        repeat(PARSE_FAILURE_LIMIT) {
            if (task.stop.stopped) return LoopOutcome(false, Prompts.STOPPED)
            val screen = device.observe()
            if (screen.secure) {
                task.emit(AgentEvent.NeedsUser(task.taskId, Prompts.SECURE_SCREEN, takeover = true))
                users.ask(task.taskId, Prompts.SECURE_SCREEN, true)
                return@repeat
            }
            val text = Prompts.assertRequest(expectation) + "\n\n" + ScreenText.section(screen, config.maxNodes)
            val request = ChatRequest(listOf(ChatMessage.system(Prompts.ASSERT_SYSTEM), userMessage(text, screen)), jsonMode = true)
            val reply = try {
                completeWithRetry(request)
            } catch (e: LlmException) {
                val message = Prompts.operatorLlmFailed(e)
                task.emit(AgentEvent.Failed(task.taskId, message))
                return LoopOutcome(false, message)
            }
            val obj = JsonText.firstObject(reply.text) ?: return@repeat
            val ok = JsonText.bool(obj, "ok") ?: return@repeat
            val reason = JsonText.string(obj, "reason")
            return LoopOutcome(ok, if (ok) (reason ?: expectation) else Prompts.assertFailed(expectation, reason))
        }
        return LoopOutcome(false, Prompts.assertFailed(expectation, null))
    }

    /** Retry a transient model failure once; account/configuration failures need intervention. */
    suspend fun completeWithRetry(request: ChatRequest): ChatResponse = try {
        operator.complete(request)
    } catch (e: LlmException) {
        if (!e.retryable) throw e
        operator.complete(request)
    }

    private fun operatorRequest(
        task: TaskRun,
        subGoal: String?,
        constraint: StepConstraint?,
        screen: ScreenState,
        history: List<String>,
        feedback: List<String>,
    ): ChatRequest {
        val sections = buildList {
            add(if (subGoal != null) Prompts.subGoalSection(subGoal, task.goal) else Prompts.goalSection(task.goal))
            task.skill?.let { s -> s.instructions.takeIf { it.isNotBlank() }?.let { add(Prompts.skillSection(s.name, it)) } }
            if (task.references.isNotBlank()) add(Prompts.MEMORY_REFS_HEADER + "\n" + task.references)
            if (task.howto.isNotBlank()) add(Prompts.HOWTO_HEADER + "\n" + task.howto)
            if (history.isNotEmpty()) add(Prompts.HISTORY_HEADER + "\n" + history.takeLast(config.historyWindow).joinToString("\n"))
            if (feedback.isNotEmpty()) add(Prompts.FEEDBACK_HEADER + "\n" + feedback.joinToString("\n"))
            constraint?.let { add(Prompts.singleStep(it.kind, it.target, it.text)) }
            add(ScreenText.section(screen, config.maxNodes))
            add(Prompts.NEXT_STEP)
        }
        return ChatRequest(
            messages = listOf(ChatMessage.system(Prompts.operatorSystem(config.operatorNotes)), userMessage(sections.joinToString("\n\n"), screen)),
            jsonMode = true,
        )
    }

    private fun userMessage(text: String, screen: ScreenState) = ChatMessage(
        ChatRole.USER,
        listOfNotNull(ContentPart.Text(text), screen.screenshotPng?.let { ContentPart.ImagePng(it) }),
    )

    companion object {
        const val PARSE_FAILURE_LIMIT = 3
    }
}
