package dev.xiaoman.web

import com.sun.net.httpserver.HttpServer
import dev.xiaoman.api.*
import dev.xiaoman.llm.LlmClientFactory
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.time.*
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class WebServerTest {
    private data class Response(val code: Int, val text: String, val headers: Map<String?, List<String>>) {
        val json get() = Json.parseToJsonElement(text).jsonObject
    }
    private fun connection(server: WebServer, path: String, session: String? = null, code: String? = null,
        body: String? = null): HttpURLConnection {
        val c = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 5000
        session?.let { c.setRequestProperty("X-Xiaoman-Session", it) }
        code?.let { c.setRequestProperty("X-Xiaoman-Access", it) }
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return c
    }
    private fun request(s: WebServer, path: String, sid: String? = null, code: String? = null, body: String? = null): Response {
        val c = connection(s, path, sid, code, body)
        try {
            val status = c.responseCode
            val text = (if (status >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(status, text, c.headerFields)
        } finally { c.disconnect() }
    }
    private fun create(s: WebServer, code: String? = null) = request(s, "/api/session", code = code, body = "{}").json.str("sessionId")
    private fun events(text: String) = text.lineSequence().filter { it.startsWith("data: ") }
        .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }.toList()
    private fun chatBody(message: String = "read", url: String? = null): String = buildJsonObject {
        put("message", message)
        put("model", buildJsonObject { put("baseUrl", "https://llm.invalid"); put("model", "fake"); put("apiKey", "llm-secret-123") })
        put("mcp", buildJsonObject {
            put("keys", buildJsonObject { put("AMAP_MAPS_API_KEY", "mcp-secret-456") })
            put("custom", JsonArray(if (url == null) emptyList() else listOf(buildJsonObject {
                put("title", "测试服务"); put("url", url); put("confirm", "writes")
                put("headers", buildJsonObject { put("Authorization", "Bearer mcp-secret-456") })
            })))
        })
    }.toString()
    private val scripted = LlmClientFactory { config -> object : LlmClient {
        override val config = config
        override suspend fun complete(request: ChatRequest): ChatResponse {
            if (request.messages.last().role == ChatRole.TOOL) return ChatResponse("完成 llm-secret-123 mcp-secret-456")
            val message = request.messages.last { it.role == ChatRole.USER }.text
            val call = when (message) {
                "reminder" -> ToolCall("1", "reminder_set", """{"text":"喝水,带伞;\\\n出门","at":"2099-11-03T18:30"}""")
                "memory" -> ToolCall("1", "memory_write", """{"title":"家","observations":[{"category":"地址","content":"北京"}]}""")
                "forget" -> ToolCall("1", "memory_forget", """{"identifier":"家"}""")
                else -> request.tools.firstOrNull { it.name.endsWith("__" + message) }?.let {
                    ToolCall("1", it.name, """{"value":"llm-secret-123 mcp-secret-456"}""")
                }
            }
            return if (call == null) ChatResponse("你好") else ChatResponse("开始", listOf(call))
        }
    } }
    private class FakeMcp(private val failure: String? = null) : Closeable {
        val calls = CopyOnWriteArrayList<String>()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = this@FakeMcp.executor
            createContext("/mcp") { x ->
                val body = Json.parseToJsonElement(x.requestBody.bufferedReader().readText()).jsonObject
                val method = body.str("method")
                if (failure != null) {
                    val bytes = failure.toByteArray(Charsets.UTF_8)
                    x.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                    x.sendResponseHeaders(500, bytes.size.toLong()); x.responseBody.write(bytes); x.close()
                    return@createContext
                }
                val result = when (method) {
                    "initialize" -> buildJsonObject { put("protocolVersion", "2024-11-05"); put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) }); put("serverInfo", buildJsonObject { put("name", "fake"); put("version", "1") }) }
                    "tools/list" -> buildJsonObject { put("tools", JsonArray(listOf("read", "write").map { name -> buildJsonObject {
                        put("name", name); put("description", name); put("inputSchema", buildJsonObject { put("type", "object") })
                        put("annotations", buildJsonObject { put("readOnlyHint", name == "read") })
                    } })) }
                    "tools/call" -> {
                        calls += body.obj("params").str("name")
                        buildJsonObject { put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "成功 mcp-secret-456") }))) }
                    }
                    else -> buildJsonObject {}
                }
                if (body["id"] == null) { x.sendResponseHeaders(202, -1) } else {
                    val reply = buildJsonObject { put("jsonrpc", "2.0"); put("id", body.getValue("id")); put("result", result) }.toString().toByteArray()
                    x.responseHeaders.set("Content-Type", "application/json"); x.sendResponseHeaders(200, reply.size.toLong()); x.responseBody.write(reply)
                }
                x.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}/mcp"
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
    private fun options() = WebServer.Options(port = 0, allowPrivateMcp = true, heartbeatMillis = 50)

    @Test fun catalogSessionAndInfoFollowContract() {
        WebServer(options(), scripted, logger = {}).start().use { s ->
            val catalog = request(s, "/api/catalog").json.arr("servers")
            val web = dev.xiaoman.mcp.McpCatalog.builtIn.availableOn(dev.xiaoman.mcp.McpPlatform.WEB).map { it.id }.toSet()
            assertEquals(web + "12306", catalog.map { it.jsonObject.str("id") }.toSet())
            val train = catalog.single { it.jsonObject.str("id") == "12306" }.jsonObject
            assertFalse(train["available"]!!.jsonPrimitive.boolean); assertTrue("Node.js" in train.str("note"))
            assertTrue(catalog.filter { it.jsonObject.str("id") in web }.all { it.jsonObject["available"]!!.jsonPrimitive.boolean })
            assertTrue(catalog.all { "url" !in it.jsonObject && "entry" !in it.jsonObject })
            val sid = create(s); assertEquals(22, sid.length); assertTrue(sid.matches(Regex("[A-Za-z0-9_-]+")))
            assertEquals("Xiaoman", request(s, "/api/info").json.str("name"))
            assertEquals(404, request(s, "/api/chat", "missing", body = chatBody()).code)
        }
    }
    @Test fun accessCodeProtectsEveryApiExceptInfo() {
        WebServer(options().copy(accessCode = "access"), scripted, logger = {}).start().use { s ->
            assertTrue(request(s, "/api/info").json["accessCodeRequired"]!!.jsonPrimitive.boolean)
            listOf("/api/catalog", "/api/session", "/api/chat", "/api/answer", "/api/mcp/test").forEach { path ->
                val response = request(s, path, body = if (path == "/api/catalog") null else "{}")
                assertEquals(401, response.code); assertEquals("需要访问码", response.json.str("error"))
            }
            assertEquals(22, create(s, "access").length)
        }
    }
    @Test fun readOnlyToolExecutesWithoutApprovalAndSecretsNeverReachEventsOrLogs() {
        FakeMcp().use { mcp ->
            val logs = CopyOnWriteArrayList<String>()
            WebServer(options(), scripted, logger = logs::add).start().use { s ->
                val response = request(s, "/api/chat", create(s), body = chatBody(url = mcp.url))
                val e = events(response.text)
                assertEquals(listOf("reply", "tool", "reply", "done"), e.map { it.str("type") })
                assertEquals(listOf("read"), mcp.calls.toList())
                assertTrue(e.first { it.str("type") == "tool" }["ok"]!!.jsonPrimitive.boolean)
                listOf("llm-secret-123", "mcp-secret-456").forEach { secret ->
                    assertFalse(secret in response.text); assertFalse(logs.any { secret in it })
                }
            }
        }
    }
    private fun approvalTurn(approved: Boolean) {
        FakeMcp().use { mcp -> WebServer(options(), scripted, logger = {}).start().use { s ->
            val sid = create(s)
            val c = connection(s, "/api/chat", sid, body = chatBody("write", mcp.url))
            try {
                assertEquals(200, c.responseCode)
                val collected = mutableListOf<JsonObject>()
                c.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data: ")) continue
                        val e = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                        collected += e
                        if (e.str("type") == "approval") {
                            assertTrue(mcp.calls.isEmpty())
                            assertFalse("secret" in e.str("arguments"))
                            val busy = request(s, "/api/chat", sid, body = chatBody())
                            assertEquals(409, busy.code)
                            val answer = buildJsonObject { put("id", e.str("id")); put("approved", approved) }.toString()
                            assertEquals(200, request(s, "/api/answer", sid, body = answer).code)
                            assertEquals(404, request(s, "/api/answer", create(s), body = answer).code)
                        }
                    }
                }
                assertEquals(listOf("reply", "approval", "tool", "reply", "done"), collected.map { it.str("type") })
                assertEquals(approved, collected.first { it.str("type") == "tool" }["ok"]!!.jsonPrimitive.boolean)
                assertEquals(if (approved) listOf("write") else emptyList(), mcp.calls.toList())
            } finally { c.disconnect() }
        } }
    }
    @Test fun approvedWriteContinuesAndExecutes() = approvalTurn(true)
    @Test fun declinedWriteContinuesWithoutExecution() = approvalTurn(false)

    @Test fun reminderToolProducesValidEscapedCalendarAndMemoryToolEmitsMemory() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            WebServer(options(), scripted, logger = {}).start().use { s ->
                val sid = create(s)
                val e = events(request(s, "/api/chat", sid, body = chatBody("reminder")).text)
                val reminder = e.single { it.str("type") == "reminder" }
                val ics = reminder.str("ics")
                assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"))
                assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
                assertTrue("DTSTART:20991103T103000Z\r\n" in ics)
                assertTrue("SUMMARY:喝水\\,带伞\\;\\\\\\n出门\r\n" in ics)
                assertTrue("BEGIN:VALARM\r\nTRIGGER:PT0M" in ics)
                assertEquals("2099-11-03T18:30", reminder.str("at"))
                assertEquals("done", e.last().str("type"))
                assertTrue(events(request(s, "/api/chat", sid, body = chatBody("memory")).text).any { it.str("type") == "memory" })
            }
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun calendarFoldsUtf8LinesAndEscapesSpecialCharacters() {
        val text = "汉".repeat(100) + ",;\\\n下一行"
        val ics = IcsReminders.calendar(text, 0, 0)
        assertTrue(ics.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertTrue("DTSTART:19700101T000000Z" in ics)
        assertTrue("\\,\\;\\\\\\n" in ics.replace("\r\n ", ""))
    }
    @Test fun privateCustomMcpIsSkippedUnlessExplicitlyAllowed() {
        WebServer(options().copy(allowPrivateMcp = false), scripted, logger = {}).start().use { s ->
            listOf("http://127.0.0.1/mcp", "https://10.1.2.3/mcp", "https://169.254.1.1/mcp", "http://localhost/mcp").forEach { url ->
                val config = s.mcpConfig(Json.parseToJsonElement(chatBody(url = url)).jsonObject.obj("mcp"))
                assertTrue(config.servers.isEmpty()); assertTrue(config.warnings.any { "不允许访问内网" in it })
            }
        }
    }
    @Test fun mcpTestConnectsAndClosesAndSanitizesStatus() {
        FakeMcp().use { mcp -> WebServer(options(), scripted, logger = {}).start().use { s ->
            val r = request(s, "/api/mcp/test", create(s), body = chatBody(url = mcp.url))
            assertEquals(200, r.code)
            val status = r.json.arr("servers").single().jsonObject
            assertTrue(status["connected"]!!.jsonPrimitive.boolean); assertEquals(2, status["toolCount"]!!.jsonPrimitive.int)
            assertFalse("mcp-secret-456" in r.text)
        } }
    }
    @Test fun expiresSessionsReleasesAgentAndMemoryAndEvictsLeastRecentlyUsed() {
        val time = AtomicLong(100)
        WebServer(options().copy(sessionMillis = 100, maxSessions = 2), scripted, time::get, {}).start().use { s ->
            val a = create(s); val held = s.session(a)!!
            request(s, "/api/chat", a, body = chatBody("memory"))
            assertNotNull(held.agent)
            time.set(110); val b = create(s)
            time.set(120); create(s)
            assertTrue(held.closed); assertNull(held.agent); assertEquals(0, runBlocking { held.memory.list().size })
            assertEquals(404, request(s, "/api/chat", a, body = chatBody()).code)
            time.set(220); s.expireSessions(); assertEquals(0, s.sessionCount)
            assertEquals(404, request(s, "/api/chat", b, body = chatBody()).code)
        }
    }
    @Test fun staticRootServesIndexHeadersAndRejectsTraversal() {
        val root = Files.createTempDirectory("xiaoman-web-test")
        try {
            Files.writeString(root.resolve("index.html"), "<html>Xiaoman</html>")
            WebServer(options().copy(staticRoot = root), scripted, logger = {}).start().use { s ->
                val r = request(s, "/")
                assertEquals(200, r.code); assertEquals("<html>Xiaoman</html>", r.text)
                assertEquals(listOf("nosniff"), r.headers.entries.first { it.key.equals("X-content-type-options", true) }.value)
                assertTrue(r.headers.entries.first { it.key.equals("Content-security-policy", true) }.value.single().contains("connect-src 'self'"))
                assertEquals(400, request(s, "/%2e%2e/index.html").code)
                assertEquals(400, request(s, "/../index.html").code)
                assertEquals(404, request(s, "/missing.js").code)
            }
        } finally { Files.deleteIfExists(root.resolve("index.html")); Files.deleteIfExists(root) }
    }
    @Test fun missingModelMalformedJsonAndBodyLimitHaveSafeErrors() {
        WebServer(options(), scripted, logger = {}).start().use { s ->
            val sid = create(s)
            assertEquals(listOf("error", "done"), events(request(s, "/api/chat", sid, body = "{}").text).map { it.str("type") })
            assertEquals(400, request(s, "/api/chat", sid, body = "{bad").code)
            assertEquals(413, request(s, "/api/chat", sid, body = "x".repeat(256 * 1024 + 1)).code)
        }
    }
    @Test fun confirmationTimeoutRejectsAndQuestionAnswersAreSessionScoped() = runBlocking {
        val channels = WebChannels(40)
        var approval: JsonObject? = null
        channels.attach { approval = it }
        val deferred = async { channels.approve(ToolApprovalRequest("task", "server", "write", null, "{}", "修改")) }
        assertFalse(deferred.await()); assertEquals(0, channels.pendingCount)
        assertFalse(channels.answer(buildJsonObject { put("id", approval!!.str("id")); put("approved", true) }))
        var question: JsonObject? = null
        channels.attach { question = it }
        val asked = async(start = CoroutineStart.UNDISPATCHED) { channels.ask("task", "去哪里？", false) }
        assertTrue(channels.answer(buildJsonObject { put("id", question!!.str("id")); put("text", "回家") }))
        assertEquals("回家", asked.await())
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { channels.ask("task", "还有吗？", false) }
        channels.detach(); assertEquals("用户没有回答", cancelled.await())
    }
    @Test fun chatTimeoutEndsWithDoneAndAllowsNextTurn() {
        val slow = LlmClientFactory { config -> object : LlmClient {
            override val config = config
            override suspend fun complete(request: ChatRequest): ChatResponse { delay(10_000); return ChatResponse("迟到") }
        } }
        WebServer(options().copy(chatTimeoutMillis = 80), slow, logger = {}).start().use { s ->
            val sid = create(s)
            val e = events(request(s, "/api/chat", sid, body = chatBody()).text)
            assertEquals(listOf("error", "done"), e.map { it.str("type") })
            assertFalse(s.session(sid)!!.busy)
        }
    }
    @Test fun disconnectRejectsPendingApprovalAndCancelsTurn() {
        FakeMcp().use { mcp -> WebServer(options(), scripted, logger = {}).start().use { s ->
            val sid = create(s)
            val c = connection(s, "/api/chat", sid, body = chatBody("write", mcp.url))
            val reader = c.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: fail("Missing approval")
                if (line.startsWith("data: ") && Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject.str("type") == "approval") break
            }
            c.disconnect(); reader.close()
            val deadline = System.nanoTime() + 3_000_000_000
            while (s.session(sid)!!.busy && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(s.session(sid)!!.busy)
            assertEquals(0, s.session(sid)!!.channels.pendingCount); assertTrue(mcp.calls.isEmpty())
        } }
    }
    @Test fun changingConfigurationRebuildsAgentAndRetainsSessionMemory() {
        WebServer(options(), scripted, logger = {}).start().use { s ->
            val sid = create(s)
            request(s, "/api/chat", sid, body = chatBody("memory"))
            val session = s.session(sid)!!
            val original = session.agent
            request(s, "/api/chat", sid, body = chatBody())
            assertSame(original, session.agent)
            val changed = Json.parseToJsonElement(chatBody()).jsonObject
            val body = JsonObject(changed + ("model" to JsonObject(changed.obj("model") + ("model" to JsonPrimitive("another")))))
            request(s, "/api/chat", sid, body = body.toString())
            assertNotSame(original, session.agent)
            assertEquals("家", runBlocking { session.memory.read("家") }!!.title)
        }
    }
    @Test fun modelExceptionsAndEncodedSecretsAreNotDisclosed() {
        val broken = LlmClientFactory { config -> object : LlmClient {
            override val config = config
            override suspend fun complete(request: ChatRequest): ChatResponse =
                throw LlmException("错误 ${config.apiKey} mcp-secret-456", statusCode = 401,
                    providerCode = "诊断 ${config.apiKey} mcp-secret-456")
        } }
        WebServer(options(), broken, logger = {}).start().use { s ->
            val response = request(s, "/api/chat", create(s), body = chatBody())
            assertFalse("llm-secret-123" in response.text); assertFalse("mcp-secret-456" in response.text)
            val e = events(response.text)
            assertEquals(listOf("error", "done"), e.map { it.str("type") })
            assertTrue("诊断 *** ***" in e.first().str("message"))
            assertTrue("错误码 401" in e.first().str("message"))
        }
        val session = WebSession("test", 0) { 0 }
        val key = "quoted\"secret"
        session.rememberSecrets(buildJsonObject { put("model", buildJsonObject { put("apiKey", key) }) })
        val arguments = buildJsonObject { put("key", key) }.toString()
        val redacted = session.redact(event("approval") { put("arguments", arguments) }).jsonObject.str("arguments")
        assertFalse(key in redacted)
        assertFalse(JsonPrimitive(key).toString().removeSurrounding("\"" ) in redacted)
        session.close()
    }

    @Test fun privateModelAddressesAreRejectedBeforeFactoryIsCalled() {
        var factoryCalls = 0
        val factory = LlmClientFactory { factoryCalls++; error("Factory must not be called") }
        WebServer(options().copy(allowPrivateMcp = false), factory, logger = {}).start().use { s ->
            val sid = create(s)
            listOf("http://127.0.0.1:12345", "http://localhost:12345", "http://10.1.2.3",
                "http://169.254.169.254", "http://[::1]:12345").forEach { url ->
                val original = Json.parseToJsonElement(chatBody()).jsonObject
                val body = JsonObject(original + ("model" to JsonObject(original.obj("model") + ("baseUrl" to JsonPrimitive(url)))))
                val e = events(request(s, "/api/chat", sid, body = body.toString()).text)
                assertEquals(listOf("error", "done"), e.map { it.str("type") })
                assertEquals("模型地址不允许指向内网", e.first().str("message"))
            }
            assertEquals(0, factoryCalls)
        }
    }
    @Test fun shortApiKeyDoesNotReplaceOrdinaryReplyCharacters() {
        val reply = "a cat can read a map"
        val factory = LlmClientFactory { config -> object : LlmClient {
            override val config = config
            override suspend fun complete(request: ChatRequest) = ChatResponse(reply)
        } }
        WebServer(options(), factory, logger = {}).start().use { s ->
            val original = Json.parseToJsonElement(chatBody()).jsonObject
            listOf("a", "short").forEach { key ->
                val body = JsonObject(original + ("model" to JsonObject(original.obj("model") + ("apiKey" to JsonPrimitive(key)))))
                val e = events(request(s, "/api/chat", create(s), body = body.toString()).text)
                assertEquals(listOf("reply", "done"), e.map { it.str("type") })
                assertEquals(reply, e.first().str("text"))
            }
        }
    }
    @Test fun mcpTestPreservesDiagnosticsAndRedactsBeforeTruncation() {
        FakeMcp("服务拒绝 llm-secret-123 mcp-secret-456 " + "详情".repeat(150)).use { mcp ->
            WebServer(options(), scripted, logger = {}).start().use { s ->
                val r = request(s, "/api/mcp/test", create(s), body = chatBody(url = mcp.url))
                assertEquals(200, r.code)
                val status = r.json.arr("servers").single().jsonObject
                assertFalse(status["connected"]!!.jsonPrimitive.boolean)
                val error = status.str("error")
                assertTrue("HTTP 500" in error); assertTrue("服务拒绝 *** ***" in error)
                assertTrue(error.length <= 200)
                assertFalse("llm-secret-123" in r.text); assertFalse("mcp-secret-456" in r.text)
            }
        }
    }

    private fun withMemory(body: String, vararg notes: JsonObject): String =
        JsonObject(Json.parseToJsonElement(body).jsonObject + ("memory" to buildJsonObject { put("notes", JsonArray(notes.toList())) })).toString()
    private fun browserNote(permalink: String, markdown: String, updatedAt: Long = 1) =
        buildJsonObject { put("permalink", permalink); put("markdown", markdown); put("updatedAt", updatedAt) }
    private val coffee = browserNote("偏好/咖啡", "---\ntitle: 咖啡\ntype: preference\npermalink: 偏好/咖啡\n---\n# 咖啡\n\n## Observations\n- [偏好] 喜欢燕麦拿铁，少糖\n")

    @Test fun browserMemoryIsLoadedEachTurnAndChangesComeBackAsEvents() {
        val prompts = CopyOnWriteArrayList<String>()
        val recording = LlmClientFactory { config -> val inner = scripted.create(config); object : LlmClient {
            override val config = config
            override suspend fun complete(request: ChatRequest): ChatResponse {
                prompts += request.messages.first { it.role == ChatRole.SYSTEM }.text; return inner.complete(request)
            }
        } }
        WebServer(options(), recording, logger = {}).start().use { s ->
            val sid = create(s)
            // The model sees the browser's note in its prompt; memory_write comes back as a full note.
            val e = events(request(s, "/api/chat", sid, body = withMemory(chatBody("memory"), coffee)).text)
            assertTrue(prompts.isNotEmpty())
            val saved = e.single { it.str("type") == "memory" }
            assertEquals("saved", saved.str("action")); assertEquals("家", saved.str("permalink")); assertEquals("家", saved.str("title"))
            assertTrue("- [地址] 北京" in saved.str("markdown")); assertTrue(saved["updatedAt"]!!.jsonPrimitive.long > 0)
            val session = s.session(sid)!!
            assertEquals(setOf("偏好/咖啡", "家"), runBlocking { session.memory.list() }.map { it.permalink }.toSet())
            // Next turn: the browser is the source of truth, so a note it no longer sends is gone.
            request(s, "/api/chat", sid, body = withMemory(chatBody("咖啡"), coffee))
            assertEquals(listOf("偏好/咖啡"), runBlocking { session.memory.list() }.map { it.permalink })
            assertTrue(prompts.last().contains("燕麦拿铁"))
        }
        // A restarted server has no sessions, yet the browser brings the notes back; forgetting is reported.
        WebServer(options(), scripted, logger = {}).start().use { s ->
            val sid = create(s)
            val home = browserNote("家", "---\ntitle: 家\npermalink: 家\n---\n# 家\n\n## Observations\n- [地址] 北京\n")
            val e = events(request(s, "/api/chat", sid, body = withMemory(chatBody("forget"), coffee, home)).text)
            val deleted = e.single { it.str("type") == "memory" }
            assertEquals("deleted", deleted.str("action")); assertEquals("家", deleted.str("permalink"))
            assertEquals(listOf("偏好/咖啡"), runBlocking { s.session(sid)!!.memory.list() }.map { it.permalink })
        }
    }
    @Test fun invalidOrExcessBrowserNotesAreSkippedWithAWarning() {
        WebServer(options(), scripted, logger = {}).start().use { s ->
            val sid = create(s)
            val bad = arrayOf(coffee, browserNote("../etc/passwd", "x"), browserNote("", "x"), browserNote("长", "x".repeat(BrowserMemory.MAX_MARKDOWN_CHARS + 1)),
                buildJsonObject { put("permalink", 3) })
            val e = events(request(s, "/api/chat", sid, body = withMemory(chatBody(), *bad)).text)
            assertTrue(e.single { it.str("type") == "warning" }.str("message").contains("4 条"))
            assertEquals(listOf("偏好/咖啡"), runBlocking { s.session(sid)!!.memory.list() }.map { it.permalink })
            val many = (1..BrowserMemory.MAX_NOTES + 3).map { browserNote("n/$it", "# 第 $it 条") }.toTypedArray()
            val loaded = BrowserMemory.parse(Json.parseToJsonElement(withMemory("{}", *many)).jsonObject)!!
            assertEquals(BrowserMemory.MAX_NOTES, loaded.notes.size); assertEquals(3, loaded.skipped)
        }
    }
    @Test fun browserMemoryRoundTripsThroughTheMarkdownFormat() {
        val note = Note("家", "places/家", observations = listOf(Observation("地址", "北京市海淀区")), updatedAtMillis = 42)
        val saved = BrowserMemory.saved(note)
        val parsed = BrowserMemory.parse(buildJsonObject { put("memory", buildJsonObject {
            put("notes", JsonArray(listOf(browserNote(saved.str("permalink"), saved.str("markdown"), 42))))
        }) })!!.notes.single()
        assertEquals("家", parsed.title); assertEquals("places/家", parsed.permalink); assertEquals(42, parsed.updatedAtMillis)
        assertEquals("北京市海淀区", parsed.observations.single().content)
        assertNull(BrowserMemory.parse(buildJsonObject { put("message", "hi") }))
        assertTrue(BrowserMemory.parse(buildJsonObject { put("memory", buildJsonObject {}) })!!.notes.isEmpty())
    }
    @Test fun stdioCatalogServersNeedTheFlagAndCustomServersNeverStartProcesses() {
        val mcp = buildJsonObject { put("enabled", JsonArray(listOf(JsonPrimitive("12306"), JsonPrimitive("aihot")))) }
        WebServer(options(), scripted, logger = {}).start().use { s ->
            val config = s.mcpConfig(mcp)
            assertEquals(listOf("aihot"), config.servers.map { it.name })
            assertTrue(config.warnings.any { "Node.js" in it })
        }
        WebServer(options().copy(stdioMcp = true), scripted, logger = {}).start().use { s ->
            val config = s.mcpConfig(mcp)
            assertEquals(setOf("12306", "aihot"), config.servers.map { it.name }.toSet())
            assertEquals("npx", config.servers.single { it.name == "12306" }.command)
            assertTrue(request(s, "/api/info").json["stdioMcp"]!!.jsonPrimitive.boolean)
            val train = request(s, "/api/catalog").json.arr("servers").single { it.jsonObject.str("id") == "12306" }.jsonObject
            assertTrue(train["available"]!!.jsonPrimitive.boolean)
            val custom = buildJsonObject { put("custom", JsonArray(listOf(buildJsonObject { put("title", "x"); put("url", "npx"); put("command", "sh") }))) }
            assertTrue(s.mcpConfig(custom).servers.all { it.command == null })
        }
    }
    @Test fun stdioProcessesAreCappedAndReleasedOnClose() {
        val closed = AtomicLong()
        val fake = { _: dev.xiaoman.mcp.McpServerConfig -> object : dev.xiaoman.mcp.McpTransport {
            override suspend fun exchange(message: JsonObject): JsonObject? = null
            override fun close() { closed.incrementAndGet() }
        } }
        val stdio = StdioMcp(true, maxProcesses = 2, start = fake)
        val server = dev.xiaoman.mcp.McpServerConfig("12306", "火车票查询", command = "npx")
        val a = stdio.transport(server); stdio.transport(server)
        assertTrue(assertFailsWith<dev.xiaoman.mcp.McpException> { stdio.transport(server) }.message!!.contains("人数已满"))
        a.close(); a.close()
        assertEquals(1, stdio.runningCount); assertEquals(2, closed.get())
        stdio.transport(server)
        assertFailsWith<dev.xiaoman.mcp.McpException> { StdioMcp(false).transport(server) }
        val dir = Files.createTempDirectory("xiaoman-path")
        try {
            listOf("node", "npx").forEach { Files.createFile(dir.resolve(it)).toFile().setExecutable(true) }
            assertTrue(StdioMcp.commandsOnPath(dir.toString()))
            assertFalse(StdioMcp.commandsOnPath(dir.toString(), listOf("node", "missing")))
            assertFalse(StdioMcp.commandsOnPath(""))
            assertFalse(StdioMcp.commandsOnPath(dir.toString(), windows = true, pathExt = ".EXE;.CMD"))
            listOf("node.exe", "npx.cmd").forEach { Files.createFile(dir.resolve(it)).toFile().setExecutable(true) }
            Files.delete(dir.resolve("npx"))
            assertTrue(StdioMcp.commandsOnPath(dir.toString(), windows = true, pathExt = ".EXE;.CMD"))
        } finally { dir.toFile().deleteRecursively() }
    }
}
