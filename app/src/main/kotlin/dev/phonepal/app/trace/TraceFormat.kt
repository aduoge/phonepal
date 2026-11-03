package dev.phonepal.app.trace

import dev.phonepal.api.Action
import dev.phonepal.api.AgentEvent
import dev.phonepal.api.AuditRecord
import dev.phonepal.api.Decision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The trace line format of docs/ARCHITECTURE.md (Traces). Every line has exactly the keys
 * `time, run, kind, step, action, text, ok, decision`, absent values as JSON null. The logcat
 * line `<run_id> <EventKind> <json>` carries the same object.
 */
object TraceFormat {
    val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        explicitNulls = false
    }

    fun kind(event: AgentEvent): String = when (event) {
        is AgentEvent.Reply -> "Reply"
        is AgentEvent.SkillChosen -> "SkillChosen"
        is AgentEvent.PhoneTaskStarted -> "PhoneTaskStarted"
        is AgentEvent.StepPlanned -> "StepPlanned"
        is AgentEvent.StepExecuted -> "StepExecuted"
        is AgentEvent.StepBlocked -> "StepBlocked"
        is AgentEvent.NeedsUser -> "NeedsUser"
        is AgentEvent.MemoryUpdated -> "MemoryUpdated"
        is AgentEvent.ToolCalled -> "ToolCalled"
        is AgentEvent.PhoneTaskFinished -> "PhoneTaskFinished"
        is AgentEvent.Failed -> "Failed"
    }

    /** `allow|ask|deny: <reason>`. */
    fun decision(decision: Decision): String {
        val verdict = when (decision) {
            is Decision.Allow -> "allow"
            is Decision.Ask -> "ask"
            is Decision.Deny -> "deny"
        }
        return "$verdict: ${decision.reason}"
    }

    fun action(action: Action) = json.encodeToJsonElement(Action.serializer(), action)

    /**
     * @param executedDecision the Sentinel decision recorded for a [AgentEvent.StepExecuted] step,
     *   which the event itself does not carry.
     */
    fun line(event: AgentEvent, timeMillis: Long, executedDecision: String? = null): JsonObject {
        var step: Int? = null
        var action: Action? = null
        var text: String? = null
        var ok: Boolean? = null
        var decision: String? = null
        when (event) {
            is AgentEvent.Reply -> text = event.text
            is AgentEvent.SkillChosen -> text = event.skillName
            is AgentEvent.PhoneTaskStarted -> text = event.goal
            is AgentEvent.StepPlanned -> {
                step = event.stepIndex
                action = event.action
            }
            is AgentEvent.StepExecuted -> {
                step = event.stepIndex
                action = event.action
                ok = event.result.ok
                text = event.result.message
                decision = executedDecision
            }
            is AgentEvent.StepBlocked -> {
                step = event.stepIndex
                action = event.action
                ok = false
                decision = decision(event.decision)
            }
            is AgentEvent.NeedsUser -> text = event.question
            is AgentEvent.MemoryUpdated -> text = event.permalink
            is AgentEvent.ToolCalled -> {
                text = event.tool + ": " + event.summary
                ok = event.ok
            }
            is AgentEvent.PhoneTaskFinished -> {
                step = event.steps
                text = event.summary
                ok = event.success
            }
            is AgentEvent.Failed -> text = event.message
        }
        return buildJsonObject {
            put("time", timeMillis)
            put("run", event.taskId)
            put("kind", kind(event))
            put("step", step)
            put("action", action?.let { TraceFormat.action(it) } ?: JsonNull)
            put("text", text)
            put("ok", ok)
            put("decision", decision)
        }
    }

    fun audit(record: AuditRecord): JsonObject = buildJsonObject {
        put("time", record.timeMillis)
        put("run", record.taskId)
        put("step", record.stepIndex)
        put("action", action(record.action))
        put("decision", decision(record.decision))
        put("approved", record.approved)
    }
}
