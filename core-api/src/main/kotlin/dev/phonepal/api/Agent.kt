package dev.phonepal.api

/** Events streamed from the agent to the UI and the trace. */
sealed class AgentEvent {
    abstract val taskId: String

    /** Assistant text for the chat. */
    data class Reply(override val taskId: String, val text: String) : AgentEvent()

    data class SkillChosen(override val taskId: String, val skillName: String) : AgentEvent()

    data class PhoneTaskStarted(override val taskId: String, val goal: String, val skillName: String?) : AgentEvent()

    data class StepPlanned(override val taskId: String, val stepIndex: Int, val action: Action) : AgentEvent()

    data class StepExecuted(override val taskId: String, val stepIndex: Int, val action: Action, val result: ActionResult) : AgentEvent()

    data class StepBlocked(override val taskId: String, val stepIndex: Int, val action: Action, val decision: Decision) : AgentEvent()

    /** The loop paused for the user: a question or a takeover (login, captcha, secure page). */
    data class NeedsUser(override val taskId: String, val question: String, val takeover: Boolean) : AgentEvent()

    data class MemoryUpdated(override val taskId: String, val permalink: String) : AgentEvent()

    data class PhoneTaskFinished(override val taskId: String, val success: Boolean, val summary: String, val steps: Int) : AgentEvent()

    data class Failed(override val taskId: String, val message: String) : AgentEvent()
}

fun interface AgentListener {
    fun onEvent(event: AgentEvent)
}

/** Answers [Action.AskUser] questions; the app routes this to the chat input. */
fun interface UserChannel {
    suspend fun ask(taskId: String, question: String, takeover: Boolean): String
}

data class AgentConfig(
    val maxSteps: Int = 30,
    /** The same screen this many times in a row after acting counts as stuck. */
    val stuckRepeats: Int = 3,
    /** How many earlier steps are replayed to the operator model as text. */
    val historyWindow: Int = 8,
    /** Max nodes of the accessibility tree sent per step. */
    val maxNodes: Int = 120,
    /** Write a "how to" note into memory after a successful phone task. */
    val learnFromSuccess: Boolean = true,
    /** Platform notes appended to the operator system prompt, e.g. how back and launch work on iOS. */
    val operatorNotes: String = "",
)

/** Cooperative cancellation from the overlay's Stop button. */
interface StopSignal {
    val stopped: Boolean
}

interface Agent {
    /** Handles one chat turn: replies, memory tools, or a phone task. Suspends until the turn is done. */
    suspend fun handle(userMessage: String, listener: AgentListener, stop: StopSignal)

    /** Runs one phone task directly (used by the developer CLI and skill tests). */
    suspend fun runPhoneTask(goal: String, skillName: String?, listener: AgentListener, stop: StopSignal): AgentEvent.PhoneTaskFinished
}
