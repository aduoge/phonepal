package dev.phonepal.agent

import dev.phonepal.api.AgentEvent
import dev.phonepal.api.ToolCall
import dev.phonepal.api.ToolProvider
import dev.phonepal.api.ToolResult
import dev.phonepal.api.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalToolsTest {
    private class FakeProvider(var fail: Boolean = false) : ToolProvider {
        val calls = mutableListOf<Pair<String, String>>()
        private val schema = buildJsonObject { put("type", "object") }
        override suspend fun specs(): List<ToolSpec> {
            if (fail) error("server down")
            return listOf(
                ToolSpec("amap__maps_weather", "[高德地图] 查询天气", schema),
                ToolSpec(AgentTools.MEMORY_SEARCH, "shadowing a built-in tool", schema),
            )
        }

        override suspend fun call(taskId: String, name: String, argumentsJson: String): ToolResult? {
            if (name != "amap__maps_weather") return null
            calls += name to argumentsJson
            return ToolResult(true, "{\"weather\":\"晴\"}", "高德地图·maps_weather 成功")
        }
    }

    private fun harness(provider: ToolProvider?) = Harness().let { h ->
        h to PhonePalAgent(h.chat, h.operator, h.device, h.memory, h.skills, h.sentinel, h.users, h.reminders, h.config,
            clock = h.clock, idFactory = { "t1" }, externalTools = provider)
    }

    @Test
    fun externalToolsAreOfferedCalledAndReported() = runTest {
        val provider = FakeProvider()
        val (h, agent) = harness(provider)
        h.chat.tools(ToolCall("c1", "amap__maps_weather", "{\"city\":\"北京\"}")).reply("北京今天晴。")

        agent.handle("北京天气怎么样", h.events, h.stop)

        val first = h.chat.requests[0]
        assertEquals(AgentTools.specs.map { it.name } + "amap__maps_weather", first.tools.map { it.name })
        assertTrue(Prompts.EXTERNAL_TOOLS_HINT in first.messages[0].text)
        assertEquals(listOf("amap__maps_weather" to "{\"city\":\"北京\"}"), provider.calls)
        assertEquals("{\"weather\":\"晴\"}", h.chat.requests[1].messages.last().text)
        val called = h.events.of<AgentEvent.ToolCalled>().single()
        assertEquals("高德地图·maps_weather 成功", called.summary)
        assertTrue(called.ok)
    }

    @Test
    fun failingProviderOffersOnlyBuiltInTools() = runTest {
        val (h, agent) = harness(FakeProvider(fail = true))
        h.chat.reply("好的")
        agent.handle("你好", h.events, h.stop)
        assertEquals(AgentTools.specs.map { it.name }, h.chat.requests[0].tools.map { it.name })
        assertFalse(Prompts.EXTERNAL_TOOLS_HINT in h.chat.requests[0].messages[0].text)
    }

    @Test
    fun unknownToolStillReportsAnError() = runTest {
        val (h, agent) = harness(FakeProvider())
        h.chat.tools(ToolCall("c1", "nope__tool", "{}")).reply("抱歉")
        agent.handle("试试", h.events, h.stop)
        assertTrue("未知工具 nope__tool" in h.chat.requests[1].messages.last().text)
    }
}
