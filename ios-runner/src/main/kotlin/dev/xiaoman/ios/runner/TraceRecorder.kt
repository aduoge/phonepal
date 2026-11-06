package dev.xiaoman.ios.runner

import dev.xiaoman.api.Action
import dev.xiaoman.api.ActionResult
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AuditRecord
import dev.xiaoman.api.AuditSink
import dev.xiaoman.api.Device
import dev.xiaoman.api.ScreenState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * One run's trace under `<traces>/<yyyyMMdd-HHmmss>-<id>/`: `step-NNN.png` per observed screen and
 * `events.jsonl` with screens, actions, results, audit records and agent events. Known API keys are
 * replaced by `***`; text typed into password fields is never written.
 */
class TraceRecorder(
    tracesRoot: File,
    private val secrets: List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) : AuditSink {
    val dir: File = File(tracesRoot, SimpleDateFormat("yyyyMMdd-HHmmss").format(Date(clock())) + "-" + UUID.randomUUID().toString().take(6))
    private val events = File(dir, "events.jsonl")
    private var step = 0
    @Volatile private var lastScreen: ScreenState? = null

    /** Wraps [delegate] so every observe and perform lands in the trace. */
    fun device(delegate: Device): Device = object : Device {
        override suspend fun observe(): ScreenState {
            val screen = delegate.observe()
            lastScreen = screen
            val n = synchronized(this@TraceRecorder) { ++step }
            val png = screen.screenshotPng?.let { bytes ->
                val name = "step-%03d.png".format(n)
                runCatching { ensureDir(); File(dir, name).writeBytes(bytes) }.getOrNull()?.let { name }
            }
            write("observe") {
                put("step", n)
                put("package", screen.packageName)
                put("size", "${screen.widthPx}x${screen.heightPx}")
                put("nodes", screen.nodes.size)
                png?.let { put("screenshot", it) }
            }
            return screen
        }

        override suspend fun perform(action: Action): ActionResult {
            val result = delegate.perform(action)
            write("perform") {
                put("action", describe(action))
                put("ok", result.ok)
                result.message?.let { put("message", it) }
            }
            return result
        }

        override suspend fun installedApps() = delegate.installedApps()
    }

    override fun record(record: AuditRecord) = write("audit") {
        put("task", record.taskId)
        put("step", record.stepIndex)
        put("action", describe(record.action))
        put("decision", record.decision.toString())
        record.approved?.let { put("approved", it) }
    }

    fun event(event: AgentEvent) = write("event") {
        put("kind", event::class.simpleName)
        put("task", event.taskId)
        put("detail", when (event) {
            is AgentEvent.StepPlanned -> describe(event.action)
            is AgentEvent.StepExecuted -> describe(event.action) + " -> " + event.result
            is AgentEvent.StepBlocked -> describe(event.action) + " -> " + event.decision
            else -> event.toString()
        })
    }

    /** Action text for the trace, hiding what is typed into a password field. */
    fun describe(action: Action): String {
        if (action is Action.TypeText && typesIntoPassword(action)) return action.copy(text = "***").toString()
        return action.toString()
    }

    private fun typesIntoPassword(action: Action.TypeText): Boolean {
        val nodes = lastScreen?.nodes ?: return false
        val target = action.nodeId?.let { id -> nodes.firstOrNull { it.id == id } } ?: nodes.firstOrNull { it.focused && it.editable }
        return target?.password == true
    }

    private fun write(type: String, fields: JsonObjectBuilder.() -> Unit) {
        val line = buildJsonObject {
            put("time", clock())
            put("type", type)
            fields()
        }
        synchronized(this) {
            runCatching {
                ensureDir()
                events.appendText(redact(Json.encodeToString(JsonObject.serializer(), line)) + "\n")
            }
        }
    }

    private fun redact(text: String): String = secrets.fold(text) { acc, s -> if (s.isBlank()) acc else acc.replace(s, "***") }

    private fun ensureDir() {
        if (!dir.isDirectory) dir.mkdirs()
    }
}
