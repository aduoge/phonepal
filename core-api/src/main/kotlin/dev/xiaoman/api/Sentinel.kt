package dev.xiaoman.api

/** Everything Sentinel may look at when judging one action. */
data class GuardContext(
    val taskId: String,
    val goal: String,
    val skill: Skill?,
    /** The screen the action was planned against. */
    val screen: ScreenState?,
    /** Steps already executed in this task, oldest first. */
    val stepIndex: Int,
)

sealed class Decision {
    abstract val reason: String

    data class Allow(override val reason: String = "allowed") : Decision()

    /** Needs the user's yes before running. [prompt] is shown on the approval card. */
    data class Ask(val prompt: String, override val reason: String) : Decision()

    data class Deny(override val reason: String) : Decision()
}

enum class ApprovalScope { ONCE, TASK }

data class ApprovalRequest(val taskId: String, val action: Action, val prompt: String, val reason: String)

data class ApprovalAnswer(val approved: Boolean, val scope: ApprovalScope = ApprovalScope.ONCE)

/** UI side of approvals: the app shows a card (chat and overlay) and suspends until the user answers. */
fun interface ApprovalGateway {
    suspend fun request(request: ApprovalRequest): ApprovalAnswer
}

data class AuditRecord(
    val timeMillis: Long,
    val taskId: String,
    val stepIndex: Int,
    val action: Action,
    val decision: Decision,
    val approved: Boolean?,
)

fun interface AuditSink {
    fun record(record: AuditRecord)
}

/**
 * The gate every action passes before it reaches [Device.perform]. Policy is evaluated outside
 * the model: a skill cannot switch it off, only add confirmation words.
 */
interface Sentinel {
    suspend fun guard(context: GuardContext, action: Action): Decision

    /**
     * Full gate: [guard], then the approval round-trip for [Decision.Ask], honoring TASK-scoped
     * approvals already given, and an audit record. Returns true when the action may run.
     */
    suspend fun authorize(context: GuardContext, action: Action): Boolean
}
