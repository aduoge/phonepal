package dev.phonepal.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.phonepal.api.*
import dev.phonepal.llm.*
import dev.phonepal.mcp.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** HTTP and SSE adapter; credentials and conversation state stay in memory. */
class WebServer(
    private val options: Options = Options(),
    llm: LlmClientFactory? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = ::println,
) : Closeable {
    data class Options(
        val host: String = "127.0.0.1", val port: Int = 8787, val staticRoot: Path? = null,
        val accessCode: String? = null,
        /** Allows private addresses for both model and MCP endpoints (local tests only). */
        val allowPrivateMcp: Boolean = false,
        val timezone: ZoneId = ZoneId.of("Asia/Shanghai"), val sessionMillis: Long = 7_200_000,
        val maxSessions: Int = 500, val chatTimeoutMillis: Long = 300_000,
        val heartbeatMillis: Long = 10_000,
    ) {
        init { require(sessionMillis > 0 && maxSessions > 0 && chatTimeoutMillis > 0 && heartbeatMillis > 0) }
    }
    private val sessions = linkedMapOf<String, WebSession>()
    private val executor = Executors.newCachedThreadPool()
    private val reaper = Executors.newSingleThreadScheduledExecutor()
    private val http = OkHttpClient.Builder().dns(if (options.allowPrivateMcp) Dns.SYSTEM else SafeDns()).build()
    private val llm = llm ?: LlmClients.factory(http = http)
    // DNS filtering applies to direct connections. An HTTP proxy may resolve destinations itself.
    private val server = HttpServer.create(InetSocketAddress(options.host, options.port), 0).apply {
        executor = this@WebServer.executor
        createContext("/") { exchange -> handle(exchange) }
    }
    val port: Int get() = server.address.port
    val sessionCount: Int get() = synchronized(sessions) { sessions.size }
    fun start(): WebServer {
        server.start()
        reaper.scheduleAtFixedRate({ expireSessions() }, 1, 1, TimeUnit.MINUTES)
        return this
    }
    fun expireSessions() = synchronized(sessions) {
        val now = clock()
        val expired = sessions.values.filter { now - it.lastUsed >= options.sessionMillis }
        expired.forEach { sessions.remove(it.id); it.close() }
    }
    internal fun session(id: String): WebSession? = synchronized(sessions) {
        expireSessions()
        sessions[id]?.also { it.lastUsed = clock() }
    }
    private fun newSession(): WebSession = synchronized(sessions) {
        expireSessions()
        while (sessions.size >= options.maxSessions) {
            sessions.values.minBy { it.lastUsed }.let { sessions.remove(it.id); it.close() }
        }
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        WebSession(id, clock(), clock).also { sessions[id] = it }
    }
    private class Problem(val status: Int, override val message: String) : Exception(message)
    private fun handle(x: HttpExchange) {
        val started = System.nanoTime()
        val path = x.requestURI.path
        x.responseHeaders.set("X-Content-Type-Options", "nosniff")
        x.responseHeaders.set("Referrer-Policy", "no-referrer")
        try {
            if (path.startsWith("/api/")) {
                if (path != "/api/info" && !authorized(x)) throw Problem(401, "需要访问码")
                api(x, path)
            } else static(x, path)
        } catch (p: Problem) { if (x.responseCode == -1) json(x, p.status, buildJsonObject { put("error", p.message) })
        } catch (_: Exception) { if (x.responseCode == -1) runCatching { json(x, 500, buildJsonObject { put("error", "请求处理失败") }) }
        } finally {
            // Only fixed API route names are logged; paths and queries may contain credentials.
            val route = path.takeIf { it in API_PATHS } ?: if (path.startsWith("/api/")) "/api/其他" else "/静态文件"
            logger("${x.requestMethod} $route ${x.responseCode} ${(System.nanoTime() - started) / 1_000_000}ms")
            x.close()
        }
    }
    private fun authorized(x: HttpExchange): Boolean {
        val code = options.accessCode?.takeIf { it.isNotEmpty() } ?: return true
        return MessageDigest.isEqual(code.toByteArray(), x.requestHeaders.getFirst("X-PhonePal-Access").orEmpty().toByteArray())
    }
    private fun api(x: HttpExchange, path: String) {
        val method = if (path in setOf("/api/info", "/api/catalog")) "GET" else "POST"
        if (path !in API_PATHS) throw Problem(404, "接口不存在")
        if (x.requestMethod != method) throw Problem(405, "请求方法不支持")
        when (path) {
            "/api/info" -> json(x, 200, buildJsonObject {
                put("name", "PhonePal"); put("version", "1.0"); put("accessCodeRequired", !options.accessCode.isNullOrEmpty())
            })
            "/api/catalog" -> json(x, 200, buildJsonObject {
                put("servers", JsonArray(McpCatalog.builtIn.availableOn(McpPlatform.WEB).map(McpCatalog::describe)))
            })
            "/api/session" -> { readLimited(x); json(x, 200, buildJsonObject { put("sessionId", newSession().id) }) }
            else -> {
                val s = session(x.requestHeaders.getFirst("X-PhonePal-Session").orEmpty()) ?: throw Problem(404, "会话已过期")
                val body = body(x)
                when (path) {
                    "/api/answer" -> {
                        if (!s.channels.answer(body)) throw Problem(404, "确认或问题已过期")
                        json(x, 200, buildJsonObject { put("ok", true) })
                    }
                    "/api/chat" -> chat(x, s, body)
                    "/api/mcp/test" -> {
                        s.rememberSecrets(body)
                        val config = mcpConfig(body.obj("mcp"))
                        val result = runBlocking {
                            withTimeout(options.chatTimeoutMillis) {
                                McpToolProvider(config, { false }, { McpHttpTransport(it, http) }, clock = clock).use { p ->
                                    val status = p.status(refresh = true)
                                    buildJsonObject {
                                        put("servers", JsonArray(status.map { item -> buildJsonObject {
                                            put("name", item.name); put("title", item.title); put("connected", item.connected)
                                            put("toolCount", item.toolCount); put("error", if (item.error == null) JsonNull else JsonPrimitive(s.redact(JsonPrimitive(item.error)).jsonPrimitive.content.take(200)))
                                        } }))
                                        put("warnings", JsonArray(config.warnings.map(::JsonPrimitive)))
                                    }
                                }
                            }
                        }
                        json(x, 200, s.redact(result))
                    }
                }
            }
        }
    }
    private fun chat(x: HttpExchange, s: WebSession, body: JsonObject) {
        synchronized(sessions) {
            if (s.closed) throw Problem(404, "会话已过期")
            if (s.busy) throw Problem(409, "上一条消息还在处理")
            s.busy = true
        }
        try {
            s.rememberSecrets(body)
            x.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
            x.responseHeaders.set("Cache-Control", "no-cache")
            x.sendResponseHeaders(200, 0)
            val outputLock = Any()
            fun emit(raw: JsonObject) = synchronized(outputLock) {
                var value = s.redact(raw) as JsonObject
                if (value.str("type") == "approval") value = JsonObject(value + ("arguments" to JsonPrimitive(value.str("arguments").take(500))))
                x.responseBody.write(("data: " + value + "\n\n").toByteArray(Charsets.UTF_8))
                x.responseBody.flush()
            }
            runBlocking {
                val turnJob = coroutineContext.job
                s.job = turnJob
                if (s.closed) turnJob.cancel()
                s.channels.attach(::emit)
                val heartbeat = launch(Dispatchers.IO) {
                    while (isActive) {
                        delay(options.heartbeatMillis)
                        try { synchronized(outputLock) {
                            x.responseBody.write(": keepalive\n\n".toByteArray()); x.responseBody.flush()
                        } } catch (_: IOException) { turnJob.cancel(); break }
                    }
                }
                try {
                    withTimeout(options.chatTimeoutMillis) {
                        val m = body.obj("model")
                        if (listOf("baseUrl", "model", "apiKey").any { m.str(it).isBlank() }) {
                            emit(event("error") { put("message", "请先配置模型地址、模型名称和 API Key") })
                        } else if (m.str("baseUrl").toHttpUrlOrNull() == null) {
                            emit(event("error") { put("message", "模型地址无效") })
                        } else if (!options.allowPrivateMcp && !publicAddress(m.str("baseUrl"))) {
                            emit(event("error") { put("message", "模型地址不允许指向内网") })
                        } else {
                            val config = mcpConfig(body.obj("mcp"))
                            config.warnings.forEach { warning -> emit(event("warning") { put("message", warning) }) }
                            val agent = s.assemble(body, config, llm, http, options.timezone, clock)
                            agent.handle(body.str("message"), AgentListener { e ->
                                when (e) {
                                    is AgentEvent.Reply -> emit(event("reply") { put("text", e.text) })
                                    is AgentEvent.ToolCalled -> emit(event("tool") { put("tool", e.tool); put("ok", e.ok); put("summary", e.summary) })
                                    is AgentEvent.MemoryUpdated -> emit(event("memory") { put("permalink", e.permalink) })
                                    is AgentEvent.Failed -> emit(event("error") { put("message", e.message) })
                                    else -> Unit
                                }
                            }, object : StopSignal { override val stopped get() = !turnJob.isActive })
                        }
                    }
                    emit(event("done"))
                } catch (_: TimeoutCancellationException) {
                    runCatching { emit(event("error") { put("message", "本轮处理超时，请重试") }); emit(event("done")) }
                } catch (e: CancellationException) { throw e
                } catch (_: IOException) { turnJob.cancel()
                } catch (_: Exception) {
                    runCatching { emit(event("error") { put("message", "处理失败，请检查配置后重试") }); emit(event("done")) }
                } finally { heartbeat.cancel(); s.channels.detach(); s.job = null }
            }
        } finally {
            synchronized(sessions) { s.busy = false; if (!s.closed) s.lastUsed = clock() }
        }
    }
    internal fun mcpConfig(body: JsonObject): McpConfig {
        val warnings = mutableListOf<String>()
        val custom = body.arr("custom").mapIndexedNotNull { i, item ->
            val o = item as? JsonObject ?: throw Problem(400, "MCP 配置无效")
            val url = o.str("url")
            val parsed = url.toHttpUrlOrNull()
            if (!options.allowPrivateMcp && parsed != null) {
                if (!publicAddress(url)) {
                    warnings += "自定义服务 ${i + 1} 未启用：不允许访问内网地址或地址无法解析"
                    return@mapIndexedNotNull null
                }
            }
            val policy = when (o.str("confirm").ifBlank { "writes" }) {
                "writes" -> ConfirmPolicy.WRITES; "all" -> ConfirmPolicy.ALL; "none" -> ConfirmPolicy.NONE
                else -> throw Problem(400, "MCP 确认策略无效")
            }
            CustomMcpServer(o.str("title"), url, o.obj("headers").mapValues { it.value.jsonPrimitive.content }, policy)
        }
        val selection = McpSelection(body.arr("enabled").map { it.jsonPrimitive.content }.toSet(),
            body.obj("keys").mapValues { it.value.jsonPrimitive.content }, custom)
        val config = McpCatalog.builtIn.toConfig(selection, McpPlatform.WEB)
        return McpConfig(config.servers.filter { it.url != null }, warnings + config.warnings)
    }
    private fun publicAddress(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return try { SafeDns().lookup(host); true } catch (_: Exception) { false }
    }
    private fun readLimited(x: HttpExchange): ByteArray {
        val bytes = x.requestBody.readNBytes(256 * 1024 + 1)
        if (bytes.size > 256 * 1024) throw Problem(413, "请求内容超过 256KB")
        return bytes
    }
    private fun body(x: HttpExchange): JsonObject {
        val bytes = readLimited(x)
        return try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: throw IllegalArgumentException()
        } catch (_: Exception) { throw Problem(400, "请求内容需要是合法 JSON 对象") }
    }
    private fun json(x: HttpExchange, code: Int, value: JsonElement) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        x.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        x.sendResponseHeaders(code, bytes.size.toLong()); x.responseBody.write(bytes)
    }
    private fun static(x: HttpExchange, path: String) {
        if (x.requestMethod != "GET") throw Problem(405, "请求方法不支持")
        if (".." in path || '\\' in path || '\u0000' in path) throw Problem(400, "不允许访问此路径")
        val name = if (path == "/") "index.html" else path.removePrefix("/")
        val bytes = if (options.staticRoot != null) {
            val root = options.staticRoot.toRealPath()
            val file = root.resolve(name).normalize()
            if (!file.startsWith(root)) throw Problem(400, "不允许访问此路径")
            if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(root)) throw Problem(404, "文件不存在")
            Files.readAllBytes(file)
        } else WebServer::class.java.getResourceAsStream("/web/$name")?.use { it.readBytes() } ?: throw Problem(404, "文件不存在")
        val type = when (name.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html; charset=utf-8"; "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"; "json" -> "application/json; charset=utf-8"
            "webmanifest" -> "application/manifest+json; charset=utf-8"; "svg" -> "image/svg+xml"; "png" -> "image/png"
            else -> "application/octet-stream"
        }
        x.responseHeaders.set("Content-Type", type)
        if (name.endsWith(".html", true)) x.responseHeaders.set("Content-Security-Policy",
            "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; connect-src 'self'")
        x.sendResponseHeaders(200, bytes.size.toLong()); x.responseBody.write(bytes)
    }
    override fun close() {
        reaper.shutdownNow()
        synchronized(sessions) { sessions.values.forEach { it.close() }; sessions.clear() }
        server.stop(0); executor.shutdownNow()
        http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
    }
    companion object { private val API_PATHS = setOf("/api/info", "/api/catalog", "/api/session", "/api/chat", "/api/answer", "/api/mcp/test") }
}
