package dev.xiaoman.app.session

import dev.xiaoman.api.Agent
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AgentListener
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalGateway
import dev.xiaoman.api.ApprovalRequest
import dev.xiaoman.api.UserChannel
import dev.xiaoman.app.RunIds
import dev.xiaoman.hands.MutableStopSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Texts the session adds to the chat itself; resolved from resources by the app. */
data class SessionTexts(
    val notConfigured: String = "还没有配置模型。",
    val busy: String = "已有任务在运行。",
    val stopped: String = "已停止",
    val approvedOnce: (String) -> String = { "已允许：$it" },
    val approvedTask: (String) -> String = { "本次任务都允许：$it" },
    val denied: (String) -> String = { "已拒绝：$it" },
)

class PendingQuestion(val id: Long, val taskId: String, val question: String, val takeover: Boolean) {
    internal val answer = CompletableDeferred<String>()
}

class PendingApproval(val id: Long, val request: ApprovalRequest) {
    internal val answer = CompletableDeferred<ApprovalAnswer>()
}

/**
 * The one conversation with the agent: runs chat turns and phone tasks one at a time in [scope],
 * keeps the chat list, and is the agent's [UserChannel] (the next chat input answers a question)
 * and [ApprovalGateway] (the approval card / notification answers it).
 *
 * [AgentService][dev.xiaoman.app.AgentService] keeps the process in the foreground while [busy].
 *
 * Every phone task ends with a terminal event in the trace: when the agent cannot run (not
 * configured, busy, crashed, cancelled) the session emits `Failed` + `PhoneTaskFinished` itself.
 */
class AgentSession(
    private val scope: CoroutineScope,
    private val agent: () -> Agent?,
    private val runIds: RunIds,
    val stopSignal: MutableStopSignal,
    private val trace: AgentListener,
    private val onTaskEnd: (String) -> Unit = {},
    private val texts: SessionTexts = SessionTexts(),
    private val ready: suspend () -> Unit = {},
) : UserChannel, ApprovalGateway {

    private val ids = AtomicLong()
    private val running = AtomicBoolean(false)

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)

    /** Short text of the current step for the overlay and the notification. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _question = MutableStateFlow<PendingQuestion?>(null)
    val question: StateFlow<PendingQuestion?> = _question.asStateFlow()

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /** Phone tasks started but without PhoneTaskFinished yet. */
    private val openRuns = LinkedHashSet<String>()

    /** Trace first, then the UI. */
    val listener = AgentListener { event ->
        try {
            trace.onEvent(event)
        } catch (e: Exception) {
            // A full disk must not stop the task.
        }
        synchronized(openRuns) {
            when (event) {
                is AgentEvent.PhoneTaskStarted -> openRuns += event.taskId
                is AgentEvent.PhoneTaskFinished -> openRuns -= event.taskId
                else -> Unit
            }
        }
        if (event is AgentEvent.PhoneTaskFinished) onTaskEnd(event.taskId)
        StepText.status(event)?.let { _status.value = it }
        StepText.chatItem(event, ids.incrementAndGet())?.let(::add)
        _events.tryEmit(event)
    }

    sealed class Submit {
        data object Answered : Submit()
        data object Started : Submit()
        data object Busy : Submit()
        data object NotConfigured : Submit()
        data object Empty : Submit()
    }

    /** Chat input: answers a pending question, else starts a chat turn. */
    fun submit(text: String): Submit {
        val message = text.trim()
        if (message.isEmpty()) return Submit.Empty
        val pending = _question.value
        if (pending != null && pending.answer.complete(message)) {
            add(ChatItem.User(ids.incrementAndGet(), message))
            return Submit.Answered
        }
        val agent = agent()
        if (agent == null) {
            add(ChatItem.User(ids.incrementAndGet(), message))
            add(ChatItem.Error(ids.incrementAndGet(), texts.notConfigured))
            return Submit.NotConfigured
        }
        val started = launch(null) { agent.handle(message, listener, stopSignal) }
        if (!started) return Submit.Busy
        add(ChatItem.User(ids.incrementAndGet(), message))
        return Submit.Started
    }

    /**
     * Runs one phone task directly (debug bridge). [runId] becomes the task id and trace folder;
     * an invalid or missing one is replaced. Returns false when it could not start; the trace then
     * already holds Failed + PhoneTaskFinished for that run.
     */
    fun runPhoneTask(goal: String, skill: String?, runId: String?): Boolean {
        val id = runId?.takeIf(RunIds::isValid) ?: runIds.generate()
        val agent = agent()
        if (agent == null) {
            fail(id, texts.notConfigured)
            return false
        }
        val started = launch(id) {
            runIds.prime(id)
            agent.runPhoneTask(goal, skill, listener, stopSignal)
        }
        if (!started) fail(id, texts.busy)
        return started
    }

    /** Emits Failed + PhoneTaskFinished for a run that cannot (or could not) complete. */
    fun fail(runId: String, message: String) {
        listener.onEvent(AgentEvent.Failed(runId, message))
        listener.onEvent(AgentEvent.PhoneTaskFinished(runId, false, message, 0))
    }

    /** Overlay Stop / notification Stop / debug STOP: cooperative stop, pending prompts released. */
    fun stop() {
        if (!_busy.value) return
        stopSignal.stop()
        _approval.value?.answer?.complete(ApprovalAnswer(approved = false))
        _question.value?.answer?.complete(texts.stopped)
        _status.value = texts.stopped
    }

    fun answerApproval(answer: ApprovalAnswer, approvalId: Long? = null) {
        val pending = _approval.value ?: return
        if (approvalId != null && pending.id != approvalId) return
        pending.answer.complete(answer)
    }

    override suspend fun ask(taskId: String, question: String, takeover: Boolean): String {
        val pending = PendingQuestion(ids.incrementAndGet(), taskId, question, takeover)
        if (stopSignal.stopped) return texts.stopped
        _question.value = pending
        try {
            return pending.answer.await()
        } finally {
            _question.compareAndSet(pending, null)
        }
    }

    override suspend fun request(request: ApprovalRequest): ApprovalAnswer {
        if (stopSignal.stopped) return ApprovalAnswer(approved = false)
        val pending = PendingApproval(ids.incrementAndGet(), request)
        val before = _status.value
        _approval.value = pending
        _status.value = "等待确认：${request.prompt}"
        try {
            val answer = pending.answer.await()
            val line = when {
                !answer.approved -> texts.denied(request.prompt)
                answer.scope == dev.xiaoman.api.ApprovalScope.TASK -> texts.approvedTask(request.prompt)
                else -> texts.approvedOnce(request.prompt)
            }
            add(ChatItem.Progress(ids.incrementAndGet(), line, answer.approved))
            return answer
        } finally {
            _approval.compareAndSet(pending, null)
            if (!stopSignal.stopped) _status.value = before
        }
    }

    /** Starts [block] unless something is running. [runId] is pre-registered as open (debug runs). */
    private fun launch(runId: String?, block: suspend () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        stopSignal.reset()
        if (runId != null) synchronized(openRuns) { openRuns += runId }
        _status.value = null
        _busy.value = true
        scope.launch {
            var failure: String? = null
            try {
                ready()
                block()
            } catch (e: CancellationException) {
                failure = texts.stopped
                throw e
            } catch (e: Exception) {
                failure = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            } finally {
                withContext(NonCancellable) {
                    val open = synchronized(openRuns) { openRuns.toList().also { openRuns.clear() } }
                    open.forEach { fail(it, failure ?: texts.stopped) }
                    if (failure != null && open.isEmpty()) add(ChatItem.Error(ids.incrementAndGet(), failure))
                    _question.value?.answer?.cancel()
                    _approval.value?.answer?.cancel()
                    _question.value = null
                    _approval.value = null
                    _status.value = null
                    running.set(false)
                    _busy.value = false
                }
            }
        }
        return true
    }

    private fun add(item: ChatItem) {
        _items.update { (it + item).takeLast(MAX_ITEMS) }
    }

    companion object {
        const val MAX_ITEMS = 500
    }
}
