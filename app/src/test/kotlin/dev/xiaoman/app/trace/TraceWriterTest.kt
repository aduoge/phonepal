package dev.xiaoman.app.trace

import dev.xiaoman.api.Action
import dev.xiaoman.api.ActionResult
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AuditRecord
import dev.xiaoman.api.Decision
import dev.xiaoman.api.Point
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val keys = listOf("time", "run", "kind", "step", "action", "text", "ok", "decision")

    private fun lines(file: File): List<JsonObject> = file.readLines().map { Json.parseToJsonElement(it).jsonObject }

    @Test fun `every line has exactly the documented keys in order`() {
        val logged = mutableListOf<String>()
        val writer = TraceWriter(tmp.root, clock = { 1761830000000 }, log = { logged += it })
        val tap = Action.Tap(nodeId = 12, target = "目的地输入框", reason = "先输入目的地")
        writer.onEvent(AgentEvent.PhoneTaskStarted("r1", "打车回家", null))
        writer.onEvent(AgentEvent.StepPlanned("r1", 0, tap))
        writer.record(AuditRecord(1, "r1", 0, tap, Decision.Allow("no rule matched"), null))
        writer.onEvent(AgentEvent.StepExecuted("r1", 0, tap, ActionResult(true)))
        writer.onEvent(AgentEvent.StepBlocked("r1", 1, Action.Tap(point = Point(500, 900), target = "确认呼叫"), Decision.Ask("确认呼叫？", "confirmation word '确认呼叫'")))
        writer.onEvent(AgentEvent.PhoneTaskFinished("r1", true, "已停在确认呼叫", 1))

        val trace = lines(File(tmp.root, "r1/trace.jsonl"))
        assertEquals(5, trace.size)
        trace.forEach { assertEquals(keys, it.keys.toList()) }

        val started = trace[0]
        assertEquals(1761830000000, started["time"]!!.jsonPrimitive.content.toLong())
        assertEquals("r1", started["run"]!!.jsonPrimitive.content)
        assertEquals("PhoneTaskStarted", started["kind"]!!.jsonPrimitive.content)
        assertEquals("打车回家", started["text"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, started["step"])
        assertEquals(JsonNull, started["action"])
        assertEquals(JsonNull, started["ok"])
        assertEquals(JsonNull, started["decision"])

        assertEquals(
            Json.parseToJsonElement("""{"type":"tap","nodeId":12,"target":"目的地输入框","reason":"先输入目的地"}"""),
            trace[1]["action"],
        )
        assertEquals("0", trace[1]["step"]!!.jsonPrimitive.content)

        val executed = trace[2]
        assertEquals("StepExecuted", executed["kind"]!!.jsonPrimitive.content)
        assertEquals("true", executed["ok"]!!.jsonPrimitive.content)
        assertEquals("allow: no rule matched", executed["decision"]!!.jsonPrimitive.content)

        val blocked = trace[3]
        assertEquals("ask: confirmation word '确认呼叫'", blocked["decision"]!!.jsonPrimitive.content)
        assertEquals("false", blocked["ok"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"x":500,"y":900}"""), blocked["action"]!!.jsonObject["point"])

        val finished = trace[4]
        assertEquals("1", finished["step"]!!.jsonPrimitive.content)
        assertEquals("true", finished["ok"]!!.jsonPrimitive.content)
        assertEquals("已停在确认呼叫", finished["text"]!!.jsonPrimitive.content)

        // logcat: "<run_id> <EventKind> <json>" with the same object as the trace line
        assertEquals(5, logged.size)
        val (run, kind, json) = logged[2].split(' ', limit = 3)
        assertEquals("r1", run)
        assertEquals("StepExecuted", kind)
        assertEquals(executed, Json.parseToJsonElement(json))

        val audit = lines(File(tmp.root, "r1/audit.jsonl")).single()
        assertEquals("allow: no rule matched", audit["decision"]!!.jsonPrimitive.content)
    }

    @Test fun `launch actions serialize packageName and failures carry the message`() {
        val writer = TraceWriter(tmp.root, clock = { 5 })
        writer.onEvent(AgentEvent.StepPlanned("r2", 0, Action.LaunchApp("com.android.settings")))
        writer.onEvent(AgentEvent.Failed("r2", "accessibility service not connected"))
        val trace = lines(File(tmp.root, "r2/trace.jsonl"))
        assertEquals(Json.parseToJsonElement("""{"type":"launch","packageName":"com.android.settings"}"""), trace[0]["action"])
        assertEquals("Failed", trace[1]["kind"]!!.jsonPrimitive.content)
        assertEquals("accessibility service not connected", trace[1]["text"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, trace[1]["ok"])
    }

    @Test fun `api keys are masked in traces and logs`() {
        val logged = mutableListOf<String>()
        val writer = TraceWriter(tmp.root, secrets = { listOf("sk-secret-123") }, log = { logged += it })
        writer.onEvent(AgentEvent.Reply("r3", "your key is sk-secret-123"))
        val text = File(tmp.root, "r3/trace.jsonl").readText()
        assertFalse("sk-secret-123" in text)
        assertTrue("***" in text)
        assertFalse(logged.any { "sk-secret-123" in it })
    }

    @Test fun `screenshots are saved per run with the next step index`() {
        val writer = TraceWriter(tmp.root)
        writer.saveScreenshot(byteArrayOf(1)) // no running task: ignored
        writer.onEvent(AgentEvent.PhoneTaskStarted("r4", "goal", null))
        writer.saveScreenshot(byteArrayOf(1))
        writer.onEvent(AgentEvent.StepExecuted("r4", 0, Action.Back(), ActionResult(true)))
        writer.saveScreenshot(byteArrayOf(2))
        writer.onEvent(AgentEvent.PhoneTaskFinished("r4", true, "ok", 1))
        writer.saveScreenshot(byteArrayOf(3))
        val dir = File(tmp.root, "r4")
        assertEquals(listOf("step-0.png", "step-1.png", "trace.jsonl"), dir.list()!!.sorted())
        assertEquals(2, File(dir, "step-1.png").readBytes().single().toInt())
    }

    @Test fun `run ids cannot escape the traces folder`() {
        assertEquals("_.._x", TraceWriter.safeName("/../x"))
        assertEquals("r1", TraceWriter.safeName("r1"))
    }
}
