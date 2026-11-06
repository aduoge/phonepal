package dev.xiaoman.web

import com.sun.net.httpserver.HttpServer
import dev.xiaoman.api.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ClassGatewayTest {
    private val upstreamKey = "ark-instructor-secret-9f8e7d"

    /** A local OpenAI-compatible upstream that records what the gateway sends it. */
    private class FakeUpstream(private val key: String) : Closeable {
        data class Seen(val auth: String?, val body: JsonObject)
        val seen = CopyOnWriteArrayList<Seen>()
        @Volatile var usage = true
        @Volatile var gate: CountDownLatch? = null
        private val executor = Executors.newCachedThreadPool()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = this@FakeUpstream.executor
            createContext("/api/v3/chat/completions") { x ->
                val body = Json.parseToJsonElement(x.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
                val auth = x.requestHeaders.getFirst("Authorization")
                seen += Seen(auth, body)
                gate?.await(5, TimeUnit.SECONDS)
                val model = body.str("model")
                fun send(code: Int, type: String, text: String) {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    x.responseHeaders.set("Content-Type", type); x.sendResponseHeaders(code, bytes.size.toLong()); x.responseBody.write(bytes); x.close()
                }
                when {
                    auth != "Bearer $key" -> send(401, "application/json", """{"error":{"code":"AuthenticationError","message":"bad key"}}""")
                    model == "broken-model" -> send(400, "application/json",
                        """{"error":{"code":"InvalidParameter","message":"image too large; header was $auth"}}""")
                    model == "deny-model" -> send(401, "application/json", """{"error":{"code":"AuthenticationError","message":"key $key invalid"}}""")
                    (body["stream"] as? JsonPrimitive)?.booleanOrNull == true -> {
                        x.responseHeaders.set("Content-Type", "text/event-stream"); x.sendResponseHeaders(200, 0)
                        val out = x.responseBody
                        fun chunk(o: String) { out.write("data: $o\n\n".toByteArray()); out.flush() }
                        chunk("""{"id":"1","object":"chat.completion.chunk","model":"$model","choices":[{"index":0,"delta":{"role":"assistant","content":"你"}}]}""")
                        chunk("""{"id":"1","object":"chat.completion.chunk","model":"$model","choices":[{"index":0,"delta":{"content":"好"},"finish_reason":"stop"}]}""")
                        val wants = (body["stream_options"] as? JsonObject)?.get("include_usage")?.jsonPrimitive?.booleanOrNull == true
                        if (wants && usage) chunk("""{"id":"1","object":"chat.completion.chunk","model":"$model","choices":[],"usage":{"prompt_tokens":30,"completion_tokens":7,"total_tokens":37}}""")
                        chunk("[DONE]"); x.close()
                    }
                    else -> send(200, "application/json", buildJsonObject {
                        put("id", "c1"); put("object", "chat.completion"); put("model", model)
                        put("choices", JsonArray(listOf(buildJsonObject {
                            put("index", 0); put("finish_reason", "stop")
                            put("message", buildJsonObject { put("role", "assistant"); put("content", "你好，我是小满") })
                        })))
                        if (usage) put("usage", buildJsonObject { put("prompt_tokens", 100); put("completion_tokens", 20); put("total_tokens", 120) })
                    }.toString())
                }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}/api/v3"
        override fun close() { server.stop(0); executor.shutdownNow() }
    }

    private class Setup(val dir: Path, val file: Path, val tokens: List<String>)
    private fun setup(n: Int = 3, vararg extra: String): Setup {
        val dir = Files.createTempDirectory("class-gw")
        val file = dir.resolve("class-tokens.json")
        val out = ByteArrayOutputStream()
        assertEquals(0, ClassTokenAdmin.run(listOf("generate", n.toString(), "--file", file.toString()) + extra,
            PrintStream(out, true, "UTF-8"), PrintStream(ByteArrayOutputStream()), env = emptyMap()))
        return Setup(dir, file, out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { it.substringAfter('\t') })
    }
    private fun admin(file: Path, vararg args: String) =
        assertEquals(0, ClassTokenAdmin.run(args.toList() + listOf("--file", file.toString()), PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream()), env = emptyMap()))

    private fun config(up: FakeUpstream, file: Path, rpm: Int = 30, perToken: Int = 2, global: Int = 32, bodyBytes: Int = 8 * 1024 * 1024) = ClassConfig(
        upstreamKey, linkedMapOf("xiaoman-class" to "doubao-real-id", "broken" to "broken-model", "deny" to "deny-model"), up.url, file,
        perTokenConcurrency = perToken, perTokenPerMinute = rpm, maxConcurrent = global, maxBodyBytes = bodyBytes, flushMillis = 60_000, reloadMillis = 0)
    private fun server(cfg: ClassConfig?, logs: MutableList<String>) =
        WebServer(WebServer.Options(port = 0, allowPrivateMcp = true, heartbeatMillis = 50, classConfig = cfg), logger = logs::add).start()

    private data class Reply(val code: Int, val text: String, private val raw: Map<String?, List<String>>) {
        val headers = raw.filterKeys { it != null }.mapKeys { it.key!!.lowercase() }
        val json get() = Json.parseToJsonElement(text).jsonObject
        val errorCode get() = json.obj("error").str("code")
    }
    private fun call(s: WebServer, path: String, token: String?, body: String? = null, method: String = if (body == null) "GET" else "POST"): Reply {
        val c = URL("http://127.0.0.1:${s.port}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 10_000; c.requestMethod = method
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        try {
            val code = c.responseCode
            return Reply(code, (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty(), c.headerFields)
        } finally { c.disconnect() }
    }
    private fun chatBody(model: String = "xiaoman-class", stream: Boolean = false, image: Boolean = false, includeUsage: Boolean = false) = buildJsonObject {
        put("model", model)
        if (stream) put("stream", true)
        if (includeUsage) put("stream_options", buildJsonObject { put("include_usage", true) })
        put("response_format", buildJsonObject { put("type", "json_object") })
        put("messages", JsonArray(listOf(buildJsonObject {
            put("role", "user")
            if (image) put("content", JsonArray(listOf(
                buildJsonObject { put("type", "text"); put("text", "看截图") },
                buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject {
                    put("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(ByteArray(300_000) { it.toByte() })) }) },
            ))) else put("content", "你好")
        })))
    }.toString()
    private fun usage(file: Path, token: String) = ClassTokenStore(file, ZoneId.of("Asia/Shanghai"), reloadMillis = 0).find(token)!!

    @Test fun nonStreamingForwardsWithInstructorKeyAndMetersUsage() {
        FakeUpstream(upstreamKey).use { up ->
            val st = setup(); val logs = CopyOnWriteArrayList<String>()
            server(config(up, st.file), logs).use { s ->
                val r = call(s, "/v1/chat/completions", st.tokens[0], chatBody())
                assertEquals(200, r.code, r.text)
                assertEquals("你好，我是小满", r.json.arr("choices")[0].jsonObject.obj("message").str("content"))
                val sent = up.seen.single()
                assertEquals("Bearer $upstreamKey", sent.auth)
                assertEquals("doubao-real-id", sent.body.str("model"), "alias is replaced by the real model ID")
                assertEquals("json_object", sent.body.obj("response_format").str("type"), "other fields pass through")
                assertNull(sent.body["stream_options"])
                assertTrue(info(s))
            }
            // Closing the server flushes usage to the file.
            val t = usage(st.file, st.tokens[0])
            assertEquals(120, t.usedTotal); assertEquals(1, t.requests)
            val line = logs.single { it.startsWith("POST /v1/chat/completions") }
            assertTrue("学员 01(${st.tokens[0].take(6)})" in line && "tokens=120" in line, line)
            assertNoLeak(logs, st.tokens)
        }
    }

    @Test fun streamingRequestsUsageButOnlyForwardsItWhenAsked() {
        FakeUpstream(upstreamKey).use { up ->
            val st = setup(); val logs = CopyOnWriteArrayList<String>()
            server(config(up, st.file), logs).use { s ->
                val plain = call(s, "/v1/chat/completions", st.tokens[0], chatBody(stream = true))
                assertEquals(200, plain.code)
                assertTrue(plain.headers["content-type"]!!.single().startsWith("text/event-stream"))
                val data = plain.text.lines().filter { it.startsWith("data: ") }
                assertEquals(3, data.size, plain.text); assertEquals("data: [DONE]", data.last())
                assertFalse("usage" in plain.text, "client did not ask for the usage chunk")
                assertEquals(true, up.seen.last().body.obj("stream_options")["include_usage"]!!.jsonPrimitive.boolean)

                val asked = call(s, "/v1/chat/completions", st.tokens[1], chatBody(stream = true, includeUsage = true))
                assertTrue("\"total_tokens\":37" in asked.text)

                up.usage = false // upstream sends no usage: estimate
                val est = call(s, "/v1/chat/completions", st.tokens[2], chatBody(stream = true))
                assertEquals(200, est.code)
            }
            assertEquals(37, usage(st.file, st.tokens[0]).usedTotal)
            assertEquals(37, usage(st.file, st.tokens[1]).usedTotal)
            assertTrue(usage(st.file, st.tokens[2]).usedTotal in 1..20, "estimated from text length")
            assertTrue(logs.any { "(估算)" in it })
            assertNoLeak(logs, st.tokens)
        }
    }

    @Test fun imagesPassThroughAndBodySizeIsCapped() {
        FakeUpstream(upstreamKey).use { up ->
            val st = setup(1)
            server(config(up, st.file, bodyBytes = 1024 * 1024), mutableListOf()).use { s ->
                val r = call(s, "/v1/chat/completions", st.tokens[0], chatBody(image = true))
                assertEquals(200, r.code, r.text)
                val parts = up.seen.single().body.arr("messages")[0].jsonObject.arr("content")
                assertEquals("image_url", parts[1].jsonObject.str("type"))
                assertTrue(parts[1].jsonObject.obj("image_url").str("url").startsWith("data:image/png;base64,"))
                val huge = """{"model":"xiaoman-class","messages":[{"role":"user","content":"${"a".repeat(1024 * 1024 + 10)}"}]}"""
                val big = call(s, "/v1/chat/completions", st.tokens[0], huge)
                assertEquals(413, big.code); assertEquals("request_too_large", big.errorCode)
                assertEquals(1, up.seen.size)
            }
        }
    }

    @Test fun invalidRevokedExpiredAndOverQuotaTokensGetOpenAiErrors() {
        FakeUpstream(upstreamKey).use { up ->
            val st = setup(4, "--daily", "100"); val logs = CopyOnWriteArrayList<String>()
            server(config(up, st.file), logs).use { s ->
                assertEquals("missing_token", call(s, "/v1/chat/completions", null, chatBody()).also { assertEquals(401, it.code) }.errorCode)
                val bad = call(s, "/v1/chat/completions", "xm-" + "z".repeat(20), chatBody())
                assertEquals(401, bad.code); assertEquals("invalid_token", bad.errorCode); assertEquals("authentication_error", bad.json.obj("error").str("type"))

                admin(st.file, "revoke", "学员 01")
                assertEquals("token_revoked", call(s, "/v1/chat/completions", st.tokens[0], chatBody()).errorCode, "revocation applies while running")
                assertEquals("token_revoked", call(s, "/v1/models", st.tokens[0]).errorCode)

                admin(st.file, "limit", "学员 02", "--expires", "2000-01-01 00:00")
                assertEquals("token_expired", call(s, "/v1/chat/completions", st.tokens[1], chatBody()).errorCode)

                assertEquals(200, call(s, "/v1/chat/completions", st.tokens[2], chatBody()).code) // 120 tokens > daily 100
                val over = call(s, "/v1/chat/completions", st.tokens[2], chatBody())
                assertEquals(429, over.code); assertEquals("daily_quota_exceeded", over.errorCode)
                assertEquals("insufficient_quota", over.json.obj("error").str("type"))

                admin(st.file, "revoke-all")
                assertEquals("token_revoked", call(s, "/v1/chat/completions", st.tokens[3], chatBody()).errorCode)
                assertEquals(1, up.seen.size, "only the admitted request reached the upstream")
            }
            assertNoLeak(logs, st.tokens)
        }
    }

    @Test fun whitelistAliasesAndModelsList() {
        FakeUpstream(upstreamKey).use { up ->
            val st = setup(1)
            server(config(up, st.file), mutableListOf()).use { s ->
                val models = call(s, "/v1/models", st.tokens[0])
                assertEquals(200, models.code)
                assertEquals(listOf("xiaoman-class", "broken", "deny"), models.json.arr("data").map { it.jsonObject.str("id") })
                assertFalse("doubao-real-id" in models.text, "real IDs stay on the server")
                val denied = call(s, "/v1/chat/completions", st.tokens[0], chatBody(model = "doubao-real-id"))
                assertEquals(400, denied.code); assertEquals("model_not_allowed", denied.errorCode)
                assertTrue(up.seen.isEmpty())
                assertEquals(401, call(s, "/v1/models", null).code)
                assertEquals(405, call(s, "/v1/models", st.tokens[0], method = "DELETE").code)
            }
        }
    }

    @Test fun upstreamErrorsNeverRevealTheInstructorKey() {
        FakeUpstream(upstreamKey).use { up ->
            val st = setup(1); val logs = CopyOnWriteArrayList<String>()
            server(config(up, st.file), logs).use { s ->
                val bad = call(s, "/v1/chat/completions", st.tokens[0], chatBody(model = "broken"))
                assertEquals(400, bad.code); assertEquals("InvalidParameter", bad.errorCode)
                assertTrue("image too large" in bad.text)
                assertFalse(upstreamKey in bad.text, bad.text)
                val auth = call(s, "/v1/chat/completions", st.tokens[0], chatBody(model = "deny"))
                assertEquals(502, auth.code); assertEquals("upstream_auth_failed", auth.errorCode)
                assertFalse(upstreamKey in auth.text)
                val stream = call(s, "/v1/chat/completions", st.tokens[0], chatBody(model = "broken", stream = true))
                assertEquals(400, stream.code); assertFalse(upstreamKey in stream.text)
            }
            assertTrue(logs.none { upstreamKey in it })
            assertNoLeak(logs, st.tokens)
        }
    }

    @Test fun perTokenRateAndConcurrencyAndGlobalLimits() {
        FakeUpstream(upstreamKey).use { up ->
            val st = setup(3)
            server(config(up, st.file, rpm = 2), mutableListOf()).use { s ->
                assertEquals(200, call(s, "/v1/chat/completions", st.tokens[0], chatBody()).code)
                assertEquals(200, call(s, "/v1/chat/completions", st.tokens[0], chatBody()).code)
                val limited = call(s, "/v1/chat/completions", st.tokens[0], chatBody())
                assertEquals(429, limited.code); assertEquals("rate_limit_exceeded", limited.errorCode)
                assertTrue(limited.headers["retry-after"]!!.single().toLong() in 1..60)
                assertEquals(200, call(s, "/v1/chat/completions", st.tokens[1], chatBody()).code, "other tokens are not affected")
            }
            server(config(up, st.file, perToken = 1, global = 2), mutableListOf()).use { s ->
                val gate = CountDownLatch(1); up.gate = gate
                val base = up.seen.size
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val first = pool.submit<Reply> { call(s, "/v1/chat/completions", st.tokens[0], chatBody()) }
                    val second = pool.submit<Reply> { call(s, "/v1/chat/completions", st.tokens[1], chatBody()) }
                    waitFor { up.seen.size >= base + 2 }
                    assertEquals("too_many_concurrent_requests", call(s, "/v1/chat/completions", st.tokens[0], chatBody()).errorCode)
                    assertEquals("server_busy", call(s, "/v1/chat/completions", st.tokens[2], chatBody()).errorCode)
                    gate.countDown()
                    assertEquals(200, first.get().code); assertEquals(200, second.get().code)
                    assertEquals(200, call(s, "/v1/chat/completions", st.tokens[2], chatBody()).code, "slots are released")
                } finally { up.gate = null; pool.shutdownNow() }
            }
        }
    }

    @Test fun concurrencyLimiterRejectsBeyondLimits() {
        val limiter = ClassLimiter(perToken = 1, perMinute = 100, global = 2)
        val (a, _) = limiter.acquire("a")
        assertNotNull(a)
        assertEquals("too_many_concurrent_requests", limiter.acquire("a").second?.code)
        val (b, _) = limiter.acquire("b")
        assertNotNull(b)
        assertEquals("server_busy", limiter.acquire("c").second?.code)
        a(); a() // releasing twice is harmless
        assertNotNull(limiter.acquire("c").first)
        b()
        assertNotNull(limiter.acquire("a").first)
    }

    @Test fun whitelistParsingAndEnvConfig() {
        assertEquals(mapOf("xiaoman-class" to "ep-123", "doubao-seed-1-6-250615" to "doubao-seed-1-6-250615"),
            ClassConfig.parseModels("xiaoman-class=ep-123, doubao-seed-1-6-250615"))
        assertFailsWith<IllegalArgumentException> { ClassConfig.parseModels("a=b\"c") }
        assertNull(ClassConfig.fromEnv(emptyMap()).first, "no key: off")
        assertNull(ClassConfig.fromEnv(mapOf("XIAOMAN_CLASS_UPSTREAM_KEY" to "k-123456")).first, "no whitelist: off")
        val keyFile = Files.createTempFile("key", ".txt").also { Files.writeString(it, "file-key-123\n") }
        val cfg = ClassConfig.fromEnv(mapOf("XIAOMAN_CLASS_UPSTREAM_KEY_FILE" to keyFile.toString(), "XIAOMAN_CLASS_MODELS" to "xiaoman-class=ep-1",
            "XIAOMAN_CLASS_TOKEN_RPM" to "12")).first!!
        assertEquals("file-key-123", cfg.upstreamKey); assertEquals(12, cfg.perTokenPerMinute)
        assertEquals(ClassConfig.DEFAULT_UPSTREAM, cfg.upstreamUrl)
        assertFalse("file-key-123" in cfg.toString())
    }

    @Test fun featureIsOffWithoutConfigOrTokenFile() {
        FakeUpstream(upstreamKey).use { up ->
            server(null, mutableListOf()).use { s ->
                assertFalse(info(s))
                assertEquals(404, call(s, "/v1/models", "xm-x").code)
            }
            val dir = Files.createTempDirectory("class-gw")
            val file = dir.resolve("class-tokens.json")
            server(config(up, file), mutableListOf()).use { s ->
                assertFalse(info(s), "configured but no token file yet")
                assertEquals(405, call(s, "/v1/chat/completions", "xm-x", chatBody()).code, "falls through to static files as before")
                val out = ByteArrayOutputStream()
                ClassTokenAdmin.run(listOf("generate", "1", "--file", file.toString()), PrintStream(out, true, "UTF-8"), PrintStream(ByteArrayOutputStream()), env = emptyMap())
                assertTrue(info(s), "token file created while running turns the feature on")
                assertEquals(200, call(s, "/v1/chat/completions", out.toString(Charsets.UTF_8).trim().substringAfter('\t'), chatBody()).code)
            }
        }
    }

    @Test fun webPageClassModeUsesTheSameMeteringAndHidesTheKey() {
        FakeUpstream(upstreamKey).use { up ->
            val st = setup(2, "--daily", "200"); val logs = CopyOnWriteArrayList<String>()
            server(config(up, st.file), logs).use { s ->
                fun turn(token: String): List<JsonObject> {
                    val sid = Json.parseToJsonElement(webPost(s, "/api/session", null, "{}")).jsonObject.str("sessionId")
                    val body = buildJsonObject {
                        put("message", "你好"); put("model", buildJsonObject { put("classToken", token) })
                        put("mcp", buildJsonObject {})
                    }.toString()
                    return webPost(s, "/api/chat", sid, body).lineSequence().filter { it.startsWith("data: ") }
                        .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }.toList()
                }
                val ok = turn(st.tokens[0])
                assertEquals(listOf("reply", "done"), ok.map { it.str("type") }, ok.toString())
                assertEquals("你好，我是小满", ok[0].str("text"))
                val sent = up.seen.single()
                assertEquals("Bearer $upstreamKey", sent.auth); assertEquals("doubao-real-id", sent.body.str("model"))
                // Usage is shared with /v1: 120 from the web turn + 120 here exceeds the daily 200.
                assertEquals(200, call(s, "/v1/chat/completions", st.tokens[0], chatBody()).code)
                val over = turn(st.tokens[0])
                assertEquals("error", over[0].str("type")); assertTrue("额度" in over[0].str("message"))
                admin(st.file, "revoke", "学员 02")
                assertTrue("作废" in turn(st.tokens[1])[0].str("message"))
                assertTrue("无效" in turn("xm-" + "q".repeat(20))[0].str("message"))
                assertEquals(2, up.seen.size)
            }
            assertEquals(240, usage(st.file, st.tokens[0]).usedTotal)
            assertTrue(logs.any { "WEB 课堂模式" in it && "学员 01" in it })
            assertNoLeak(logs, st.tokens)
        }
    }

    @Test fun deepThinkingIsOffByDefaultUnlessTheClientOrConfigSaysOtherwise() {
        FakeUpstream(upstreamKey).use { up ->
            val st = setup(1); val logs = CopyOnWriteArrayList<String>()
            server(config(up, st.file), logs).use { s ->
                assertEquals(200, call(s, "/v1/chat/completions", st.tokens[0], chatBody()).code)
                assertEquals("disabled", up.seen.last().body.obj("thinking").str("type"))
                assertEquals(200, call(s, "/v1/chat/completions", st.tokens[0], chatBody(stream = true)).code)
                assertEquals("disabled", up.seen.last().body.obj("thinking").str("type"), "streaming too")
                val asked = Json.parseToJsonElement(chatBody()).jsonObject.let { JsonObject(it + ("thinking" to buildJsonObject { put("type", "enabled") })) }
                assertEquals(200, call(s, "/v1/chat/completions", st.tokens[0], asked.toString()).code)
                assertEquals("enabled", up.seen.last().body.obj("thinking").str("type"), "an explicit client choice wins")
                // The web page's class mode goes through the same upstream client.
                val sid = Json.parseToJsonElement(webPost(s, "/api/session", null, "{}")).jsonObject.str("sessionId")
                webPost(s, "/api/chat", sid, buildJsonObject {
                    put("message", "你好"); put("model", buildJsonObject { put("classToken", st.tokens[0]) }); put("mcp", buildJsonObject {})
                }.toString())
                assertEquals("disabled", up.seen.last().body.obj("thinking").str("type"))
            }
            server(config(up, st.file).copy(thinking = null), logs).use { s ->
                assertEquals(200, call(s, "/v1/chat/completions", st.tokens[0], chatBody()).code)
                assertNull(up.seen.last().body["thinking"], "keep mode forwards the request unchanged")
            }
        }
        val env = mapOf("XIAOMAN_CLASS_UPSTREAM_KEY" to "k", "XIAOMAN_CLASS_MODELS" to "a=b")
        assertEquals("disabled", ClassConfig.fromEnv(env).first!!.thinking)
        assertNull(ClassConfig.fromEnv(env + ("XIAOMAN_CLASS_THINKING" to "keep")).first!!.thinking)
        assertEquals("auto", ClassConfig.fromEnv(env + ("XIAOMAN_CLASS_THINKING" to "auto")).first!!.thinking)
        assertFailsWith<IllegalArgumentException> { ClassConfig.fromEnv(env + ("XIAOMAN_CLASS_THINKING" to "off")) }
    }

    @Test fun androidAppClientWorksWithBaseUrlV1() {
        FakeUpstream(upstreamKey).use { up ->
            val st = setup(1)
            server(config(up, st.file), mutableListOf()).use { s ->
                // Exactly what the app builds from Settings: Base URL https://<server>/v1, Key = token, model = whitelist name.
                val app = dev.xiaoman.llm.OpenAiCompatibleClient(ModelConfig(ModelRole.OPERATOR, "http://127.0.0.1:${s.port}/v1/", "xiaoman-class", st.tokens[0]),
                    maxRetries = 0)
                val reply = kotlinx.coroutines.runBlocking {
                    app.complete(ChatRequest(listOf(ChatMessage(ChatRole.USER, listOf(ContentPart.Text("点哪里"), ContentPart.ImagePng(ByteArray(2048) { 7 })))),
                        jsonMode = true))
                }
                assertEquals("你好，我是小满", reply.text); assertEquals(Usage(100, 20), reply.usage)
                val sent = up.seen.single().body
                assertEquals("doubao-real-id", sent.str("model")); assertEquals("json_object", sent.obj("response_format").str("type"))
                assertEquals("image_url", sent.arr("messages")[0].jsonObject.arr("content")[1].jsonObject.str("type"))
                admin(st.file, "revoke", "学员 01")
                val e = assertFailsWith<LlmException> { kotlinx.coroutines.runBlocking { app.complete(ChatRequest(listOf(ChatMessage.user("hi")))) } }
                assertEquals(401, e.statusCode); assertEquals("token_revoked", e.providerCode)
            }
        }
    }

    private fun webPost(s: WebServer, path: String, sid: String?, body: String): String {
        val c = URL("http://127.0.0.1:${s.port}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 10_000; c.requestMethod = "POST"; c.doOutput = true
        sid?.let { c.setRequestProperty("X-Xiaoman-Session", it) }
        c.outputStream.use { it.write(body.toByteArray()) }
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
    private fun info(s: WebServer) = Json.parseToJsonElement(webGet(s, "/api/info")).jsonObject["classTokens"]!!.jsonPrimitive.boolean
    private fun webGet(s: WebServer, path: String) = (URL("http://127.0.0.1:${s.port}$path").openConnection() as HttpURLConnection).inputStream.bufferedReader().use { it.readText() }
    private fun assertNoLeak(logs: List<String>, tokens: List<String>) {
        assertTrue(logs.none { upstreamKey in it }, "upstream key in logs")
        tokens.forEach { t -> assertTrue(logs.none { t in it }, "full token in logs") }
    }
    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition()) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(10) }
    }
}
