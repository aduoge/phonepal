package dev.xiaoman.agent

import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.ChatRole
import dev.xiaoman.api.ToolCall
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReminderToolTest {
    /** 2026-11-02 07:50 in the test JVM's own timezone, so "10 分钟后" resolves deterministically. */
    private val nowMillis = LocalDateTime.of(2026, 11, 2, 7, 50)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun epochAt(hour: Int, minute: Int, day: Int = 2) =
        LocalDateTime.of(2026, 11, day, hour, minute)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun harness() = Harness(clock = { nowMillis })

    @Test
    fun reminderSetSchedulesNotification() = runTest {
        val h = harness()
        h.chat.tools(ToolCall("c1", AgentTools.REMINDER_SET, """{"text":"喝水","at":"2026-11-02T08:00"}"""))
            .reply("好的，8 点提醒你喝水。")

        h.agent.handle("10 分钟后提醒我喝水", h.events, h.stop)

        assertEquals(listOf("喝水" to epochAt(8, 0)), h.reminders.scheduled)
        val result = h.chat.requests[1].messages.last()
        assertEquals(ChatRole.TOOL, result.role)
        assertTrue("\"ok\":true" in result.text)
        assertTrue("\"at\":\"2026-11-02T08:00\"" in result.text)
        assertEquals("好的，8 点提醒你喝水。", h.events.of<AgentEvent.Reply>().single().text)
    }

    @Test
    fun pastTimeReturnsErrorAndSchedulesNothing() = runTest {
        val h = harness()
        h.chat.tools(ToolCall("c1", AgentTools.REMINDER_SET, """{"text":"带伞","at":"2026-11-02T07:00"}"""))
            .reply("你说的时间已经过了，想让我什么时候提醒？")

        h.agent.handle("提醒我带伞", h.events, h.stop)

        val result = h.chat.requests[1].messages.last().text
        assertTrue("\"error\"" in result)
        assertTrue("已经过去" in result)
        assertTrue(h.reminders.scheduled.isEmpty())
        assertTrue(h.events.of<AgentEvent.Reply>().isNotEmpty())
    }

    @Test
    fun malformedTimeReturnsError() = runTest {
        val h = harness()
        h.chat.tools(ToolCall("c1", AgentTools.REMINDER_SET, """{"text":"喝水","at":"明早 8 点"}"""))
            .reply("想让我明天几点提醒你？")

        h.agent.handle("明早提醒我喝水", h.events, h.stop)

        val result = h.chat.requests[1].messages.last().text
        assertTrue("\"error\"" in result)
        assertTrue("ISO-8601" in result)
        assertTrue(h.reminders.scheduled.isEmpty())
    }

    @Test
    fun schedulerFailureBecomesError() = runTest {
        val h = harness()
        h.reminders.failure = IllegalStateException("alarm unavailable")
        h.chat.tools(ToolCall("c1", AgentTools.REMINDER_SET, """{"text":"喝水","at":"2026-11-02T08:00"}"""))
            .reply("提醒没设上，稍后再试。")

        h.agent.handle("10 分钟后提醒我喝水", h.events, h.stop)

        val result = h.chat.requests[1].messages.last().text
        assertTrue("\"error\"" in result)
        assertTrue("alarm unavailable" in result)
    }

    @Test
    fun systemPromptCarriesCurrentTimeAndTool() = runTest {
        val h = harness()
        h.chat.reply("早")

        h.agent.handle("你好", h.events, h.stop)

        val request = h.chat.requests[0]
        val system = request.messages[0].text
        assertTrue(Prompts.NOW_HEADER in system)
        assertTrue(ReminderTimes.promptNow(nowMillis) in system)
        assertEquals(AgentTools.specs.map { it.name }, request.tools.map { it.name })
        assertTrue(AgentTools.REMINDER_SET in request.tools.map { it.name })
    }
}
