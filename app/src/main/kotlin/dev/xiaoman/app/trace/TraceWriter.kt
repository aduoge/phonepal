package dev.xiaoman.app.trace

import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AgentListener
import dev.xiaoman.api.AuditRecord
import dev.xiaoman.api.AuditSink
import java.io.File
import java.io.IOException

/**
 * Writes `<root>/<run_id>/trace.jsonl` (one [TraceFormat.line] per event), `audit.jsonl` (one line
 * per Sentinel decision) and `step-<n>.png` screenshots, and logs every event as
 * `<run_id> <EventKind> <json>` through [log] (logcat tag `Xiaoman` in the app).
 *
 * The run id is the event's task id. [secrets] (the configured API keys) are masked in every line
 * as a last line of defence; events never carry them. Thread-safe.
 */
class TraceWriter(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val secrets: () -> Collection<String> = { emptyList() },
    private val log: (String) -> Unit = {},
    private val warn: (String) -> Unit = {},
) : AgentListener, AuditSink {

    private val lock = Any()

    /** run -> (step, decision) of the latest audit record, joined onto StepExecuted lines. */
    private val lastDecision = HashMap<String, Pair<Int, String>>()

    private var currentRun: String? = null
    private var currentStep = 0

    override fun onEvent(event: AgentEvent) {
        val executedDecision = synchronized(lock) { track(event) }
        val line = mask(TraceFormat.line(event, clock(), executedDecision).toString())
        log("${event.taskId} ${TraceFormat.kind(event)} $line")
        append(event.taskId, TRACE_FILE, line)
    }

    override fun record(record: AuditRecord) {
        synchronized(lock) { lastDecision[record.taskId] = record.stepIndex to TraceFormat.decision(record.decision) }
        append(record.taskId, AUDIT_FILE, mask(TraceFormat.audit(record).toString()))
    }

    /** Saves [png] as `step-<n>.png` of the running phone task, n being the next step index. */
    fun saveScreenshot(png: ByteArray) {
        val (run, step) = synchronized(lock) { (currentRun ?: return) to currentStep }
        try {
            val dir = runDir(run).apply { mkdirs() }
            File(dir, "step-$step.png").writeBytes(png)
        } catch (e: IOException) {
            warn("trace: cannot save screenshot of $run: ${e.message}")
        }
    }

    fun runDir(run: String): File = File(root, safeName(run))

    /** Must hold [lock]. Returns the decision to put on a StepExecuted line. */
    private fun track(event: AgentEvent): String? = when (event) {
        is AgentEvent.PhoneTaskStarted -> {
            currentRun = event.taskId
            currentStep = 0
            null
        }
        is AgentEvent.StepExecuted -> {
            if (event.taskId == currentRun) currentStep = event.stepIndex + 1
            lastDecision[event.taskId]?.takeIf { it.first == event.stepIndex }?.second
        }
        is AgentEvent.PhoneTaskFinished -> {
            if (event.taskId == currentRun) currentRun = null
            lastDecision.remove(event.taskId)
            null
        }
        else -> null
    }

    private fun append(run: String, name: String, line: String) {
        try {
            synchronized(lock) {
                val dir = runDir(run).apply { mkdirs() }
                File(dir, name).appendText(line + "\n")
            }
        } catch (e: IOException) {
            warn("trace: cannot write $name of $run: ${e.message}")
        }
    }

    private fun mask(line: String): String {
        var out = line
        for (secret in secrets()) if (secret.length >= MIN_SECRET_LENGTH) out = out.replace(secret, "***")
        return out
    }

    companion object {
        const val TRACE_FILE = "trace.jsonl"
        const val AUDIT_FILE = "audit.jsonl"
        private const val MIN_SECRET_LENGTH = 4
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")

        /** Run ids become folder names; anything that could escape the traces folder is replaced. */
        fun safeName(run: String): String = run.replace(UNSAFE, "_").trim('.').ifEmpty { "_" }
    }
}
