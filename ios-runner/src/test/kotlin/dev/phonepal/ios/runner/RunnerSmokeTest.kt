package dev.phonepal.ios.runner

import com.sun.net.httpserver.HttpServer
import dev.phonepal.api.ChatRequest
import dev.phonepal.api.ChatResponse
import dev.phonepal.api.ToolCall
import dev.phonepal.api.LlmClient
import dev.phonepal.api.ModelConfig
import dev.phonepal.api.ModelRole
import dev.phonepal.llm.LlmClientFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.StringReader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.*

class RunnerSmokeTest {
    private val home: File = Files.createTempDirectory("runner-smoke").toFile()
    private val wda = FakeWda()

    @AfterTest fun cleanup() {
        wda.close()
        home.deleteRecursively()
    }

    private class FakeWda : AutoCloseable {
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val mcpCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var xml = SETTINGS
        @Volatile var bundle = "com.apple.springboard"
        private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(390, 844, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { ex ->
                val request = Json.parseToJsonElement(ex.requestBody.readBytes().toString(Charsets.UTF_8)) as JsonObject
                val id = request["id"]
                val method = (request["method"] as JsonPrimitive).content
                if (id == null) {
                    ex.sendResponseHeaders(202, -1)
                    ex.close()
                    return@createContext
                }
                val result: JsonElement = when (method) {
                    "initialize" -> buildJsonObject { put("protocolVersion", "2025-06-18"); put("capabilities", buildJsonObject {}) }
                    "tools/list" -> buildJsonObject {
                        put("tools", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "weather"); put("description", "查天气")
                                put("inputSchema", buildJsonObject { put("type", "object") })
                                put("annotations", buildJsonObject { put("readOnlyHint", true) })
                            })
                            add(buildJsonObject {
                                put("name", "order"); put("description", "下单")
                                put("inputSchema", buildJsonObject { put("type", "object") })
                            })
                        })
                    }
                    else -> {
                        val name = ((request["params"] as JsonObject)["name"] as JsonPrimitive).content
                        mcpCalls += name
                        buildJsonObject { put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "北京 晴 18°C") }) }) }
                    }
                }
                val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            createContext("/") { ex ->
                ex.requestBody.readBytes()
                val path = ex.requestURI.toString()
                paths += "${ex.requestMethod} $path"
                val value: JsonElement = when {
                    path == "/session" -> buildJsonObject { put("sessionId", "s1") }
                    path == "/status" -> buildJsonObject { put("ready", true); put("os", buildJsonObject { put("version", "26.1") }) }
                    path.endsWith("/window/size") -> buildJsonObject { put("width", 390); put("height", 844) }
                    path == "/screenshot" -> JsonPrimitive(Base64.getEncoder().encodeToString(png))
                    path.startsWith("/source") -> JsonPrimitive(xml)
                    path == "/wda/activeAppInfo" -> buildJsonObject { put("bundleId", bundle) }
                    path.endsWith("/wda/apps/launch") -> { bundle = "com.apple.Preferences"; JsonPrimitive(null as String?) }
                    path.endsWith("/elements") -> buildJsonArray {}
                    else -> JsonPrimitive(null as String?)
                }
                val body = buildJsonObject { put("value", value); put("sessionId", "s1") }.toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"
        fun count(suffix: String) = paths.count { it.endsWith(suffix) }
        override fun close() = server.stop(0)
    }

    private class Scripted(override val config: ModelConfig, replies: List<String>, chat: List<ChatResponse> = emptyList()) : LlmClient {
        private val queue = ArrayDeque(replies)
        private val chatQueue = ArrayDeque(chat)
        val requests = mutableListOf<ChatRequest>()
        override suspend fun complete(request: ChatRequest): ChatResponse {
            requests += request
            if (config.role == ModelRole.CHAT) return chatQueue.removeFirstOrNull() ?: ChatResponse("好的")
            return ChatResponse(queue.removeFirstOrNull() ?: FINISH_FAIL)
        }
    }

    private var chatLlm: Scripted? = null

    private fun runner(
        operatorReplies: List<String>,
        out: ByteArrayOutputStream,
        err: ByteArrayOutputStream = ByteArrayOutputStream(),
        chat: List<ChatResponse> = emptyList(),
    ) = Runner(
        env = mapOf("PHONEPAL_HOME" to home.path, "PHONEPAL_WDA_URL" to wda.url, "ARK_API_KEY" to KEY,
            "PHONEPAL_CHAT_MODEL" to "chat-m", "PHONEPAL_OPERATOR_MODEL" to "op-m"),
        input = BufferedReader(StringReader("")),
        out = PrintStream(out, true, Charsets.UTF_8),
        err = PrintStream(err, true, Charsets.UTF_8),
        hasConsole = false,
        llm = LlmClientFactory { c ->
            Scripted(c, if (c.role == ModelRole.OPERATOR) operatorReplies else emptyList(), chat).also { if (c.role == ModelRole.CHAT) chatLlm = it }
        },
        userHome = home.path,
    )

    @Test fun checkReportsScreen() {
        val out = ByteArrayOutputStream()
        assertEquals(0, runner(emptyList(), out).run(listOf("check")))
        val text = out.toString(Charsets.UTF_8)
        assertTrue("390x844 点" in text, text)
        assertTrue("com.apple.springboard" in text, text)
        assertTrue("iOS 26.1" in text, text)
        assertTrue(Regex("节点：\\d+ 个").containsMatchIn(text), text)
    }

    @Test fun checkWithoutWdaGivesHint() {
        wda.close()
        val err = ByteArrayOutputStream()
        assertEquals(1, runner(emptyList(), ByteArrayOutputStream(), err).run(listOf("check")))
        assertTrue("wda.sh start" in err.toString(Charsets.UTF_8))
    }

    @Test fun usageExitCodes() {
        val out = ByteArrayOutputStream()
        assertEquals(0, runner(emptyList(), out).run(listOf("--help")))
        assertTrue("ios-runner check" in out.toString(Charsets.UTF_8))
        assertEquals(2, runner(emptyList(), ByteArrayOutputStream()).run(emptyList()))
    }

    @Test fun taskLaunchesTapsAndFinishesWithTrace() {
        val out = ByteArrayOutputStream()
        val code = runner(listOf(
            """{"thought":"先打开设置","action":{"type":"launch","packageName":"com.apple.Preferences"}}""",
            """{"thought":"点无线局域网","action":{"type":"tap","nodeId":0,"target":"无线局域网"}}""",
            """{"thought":"完成","action":{"type":"finish","success":true,"summary":"无线局域网已打开，连接 Home"}}""",
        ), out).run(listOf("task", "打开设置里的无线局域网页面", "--non-interactive"))
        val text = out.toString(Charsets.UTF_8)
        assertEquals(0, code, text)
        assertEquals(1, wda.count("/wda/apps/launch"), wda.paths.toString())
        assertEquals(1, wda.count("/actions"), wda.paths.toString())
        assertTrue("任务完成" in text, text)
        val traceDir = File(home, "traces").listFiles()!!.single()
        val events = File(traceDir, "events.jsonl").readText()
        assertTrue(traceDir.listFiles()!!.any { it.name.startsWith("step-") && it.name.endsWith(".png") })
        assertTrue("\"perform\"" in events && "LaunchApp" in events)
        assertFalse(KEY in events)
        events.lineSequence().filter { it.isNotBlank() }.forEach { Json.parseToJsonElement(it) as JsonObject }
    }

    @Test fun paymentTapIsBlockedWhenNobodyIsThere() {
        wda.xml = PAY
        wda.bundle = "com.taobao.taobao4iphone"
        val out = ByteArrayOutputStream()
        val code = runner(listOf(
            """{"thought":"付款","action":{"type":"tap","nodeId":1,"target":"立即支付 按钮"}}""",
            """{"thought":"无法付款","action":{"type":"finish","success":false,"summary":"停在支付前"}}""",
        ), out).run(listOf("task", "帮我付款", "--non-interactive"))
        val text = out.toString(Charsets.UTF_8)
        assertEquals(1, code, text)
        assertEquals(0, wda.count("/actions"), wda.paths.toString())
        assertTrue("已自动拒绝" in text, text)
        assertTrue("被安全模块拦下" in text, text)
    }

    @Test fun askUsesMcpToolsAndDeniesWritesWithoutAUser() {
        File(home, "mcp.json").writeText("""{"mcpServers": {"fake": {"title": "测试服务", "url": "${wda.url}/mcp"}}}""")
        val out = ByteArrayOutputStream()
        val code = runner(emptyList(), out, chat = listOf(
            ChatResponse("", listOf(ToolCall("c1", "fake__weather", "{\"city\":\"北京\"}"), ToolCall("c2", "fake__order", "{}"))),
            ChatResponse("北京晴，18 度；下单需要你确认。"),
        )).run(listOf("ask", "北京天气怎么样，顺便帮我下单", "--non-interactive"))
        val text = out.toString(Charsets.UTF_8)
        assertEquals(0, code, text)
        assertEquals(listOf("weather"), wda.mcpCalls.toList(), text)
        assertTrue("调用工具：测试服务·weather 成功" in text, text)
        assertTrue("调用工具：测试服务·order 失败" in text, text)
        assertTrue("已自动拒绝" in text, text)
        val offered = chatLlm!!.requests.first().tools.map { it.name }
        assertTrue("fake__weather" in offered && "fake__order" in offered, offered.toString())
        assertTrue("北京 晴 18°C" in chatLlm!!.requests[1].messages.last { it.role == dev.phonepal.api.ChatRole.TOOL && it.toolCallId == "c1" }.text)
    }

    @Test fun toolsListsMcpServers() {
        File(home, "mcp.json").writeText("""{"mcpServers": {"fake": {"title": "测试服务", "url": "${wda.url}/mcp"}}}""")
        val out = ByteArrayOutputStream()
        assertEquals(0, runner(emptyList(), out).run(listOf("tools")))
        val text = out.toString(Charsets.UTF_8)
        assertTrue("测试服务（fake）：已连接，2 个工具" in text, text)
        assertTrue("fake__order" in text, text)
    }

    @Test fun taskWithoutModelsExitsTwo() {
        val err = ByteArrayOutputStream()
        val r = Runner(env = mapOf("PHONEPAL_HOME" to home.path), input = BufferedReader(StringReader("")),
            out = PrintStream(ByteArrayOutputStream()), err = PrintStream(err, true, Charsets.UTF_8), hasConsole = false,
            llm = LlmClientFactory { error("must not be called") }, userHome = home.path)
        assertEquals(2, r.run(listOf("task", "随便")))
        assertTrue("模型还没配置好" in err.toString(Charsets.UTF_8))
    }

    companion object {
        const val KEY = "ark-test-key-9f8e7d"
        const val FINISH_FAIL = """{"thought":"脚本结束","action":{"type":"finish","success":false,"summary":"脚本用完"}}"""
        val SETTINGS = """
            <AppiumAUT><XCUIElementTypeApplication type="XCUIElementTypeApplication" visible="true" x="0" y="0" width="390" height="844">
              <XCUIElementTypeCell type="XCUIElementTypeCell" visible="true" enabled="true" label="无线局域网" x="0" y="200" width="390" height="44"/>
              <XCUIElementTypeStaticText type="XCUIElementTypeStaticText" visible="true" label="设置" x="16" y="100" width="100" height="40"/>
            </XCUIElementTypeApplication></AppiumAUT>
        """.trimIndent()
        val PAY = """
            <AppiumAUT><XCUIElementTypeApplication type="XCUIElementTypeApplication" visible="true" x="0" y="0" width="390" height="844">
              <XCUIElementTypeStaticText type="XCUIElementTypeStaticText" visible="true" label="合计 ¥2.40" x="16" y="700" width="200" height="40"/>
              <XCUIElementTypeButton type="XCUIElementTypeButton" visible="true" enabled="true" label="立即支付" x="250" y="780" width="120" height="44"/>
            </XCUIElementTypeApplication></AppiumAUT>
        """.trimIndent()
    }
}
