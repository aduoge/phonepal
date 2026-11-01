package dev.phonepal.agent

import dev.phonepal.api.ActionResult
import dev.phonepal.api.AgentEvent
import dev.phonepal.api.AgentListener
import dev.phonepal.api.Decision
import dev.phonepal.api.Skill
import dev.phonepal.api.StopSignal

/** State of one phone task, shared by its flow steps and every operator loop they run. */
internal class TaskRun(
    val taskId: String,
    val goal: String,
    val skill: Skill?,
    val listener: AgentListener,
    val stop: StopSignal,
) {
    /** Device actions executed so far; also the step index of the next one. */
    var steps = 0
    /** Labels of the successful actions, for the learning note. */
    val path = mutableListOf<String>()
    /** Skill memory refs, rendered for prompts. */
    var references: String = ""
    /** Earlier successful paths (howto notes), rendered for prompts. */
    var howto: String = ""
    /** Placeholder values already resolved in this task. */
    val values = mutableMapOf<String, String>()
    var launchedPackage: String? = null
    var lastPackage: String? = null

    fun emit(event: AgentEvent) = listener.onEvent(event)

    fun addPath(label: String?) {
        val l = label?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (path.lastOrNull() != l) path += l
    }
}

internal data class LoopOutcome(val success: Boolean, val summary: String)

/** Restricts a loop to one action of [kind] (`tap` or `type`) aimed at [target]; [text] overrides the typed text. */
internal data class StepConstraint(val kind: String, val target: String, val text: String? = null)

/** Outcome of [OperatorLoop.execute]. */
internal sealed class Executed {
    data class Done(val result: ActionResult) : Executed()
    data class Blocked(val decision: Decision) : Executed()
}
