package dev.xiaoman.web

import com.sun.net.httpserver.HttpExchange
import dev.xiaoman.api.*
import dev.xiaoman.llm.LlmClientFactory
import dev.xiaoman.llm.OpenAiCompatibleClient
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Class-token mode settings. The feature is on only when an upstream key and a model whitelist are configured
 * and [tokensFile] exists. [models] maps the name students type (an alias or the real ID) to the upstream model ID.
 */
data class ClassConfig(
    val upstreamKey: String,
    val models: Map<String, String>,
    val upstreamUrl: String = DEFAULT_UPSTREAM,
    val tokensFile: Path = Path.of(ClassTokenAdmin.DEFAULT_FILE),
    /** Whitelist name used by the web page's class-token mode; defaults to the first one. */
    val webModel: String? = null,
    val perTokenConcurrency: Int = 2,
    val perTokenPerMinute: Int = 30,
    val maxConcurrent: Int = 32,
    val maxBodyBytes: Int = 8 * 1024 * 1024,
    val timeoutSeconds: Long = 180,
    val flushMillis: Long = 5_000,
    val reloadMillis: Long = 1_000,
    /** Ark `thinking.type` added to class requests that don't set one (deep thinking bills as output); null leaves requests as sent. */
    val thinking: String? = "disabled",
) {
    init {
        require(upstreamKey.isNotBlank() && models.isNotEmpty())
        require(perTokenConcurrency > 0 && perTokenPerMinute > 0 && maxConcurrent > 0 && maxBodyBytes > 0 && timeoutSeconds > 0)
        require(webModel == null || webModel in models)
        require(thinking == null || thinking in THINKING_MODES) { "thinking 只能是 ${THINKING_MODES.joinToString("/")}" }
    }
    val webModelName: String get() = webModel ?: models.keys.first()
    override fun toString() = "ClassConfig(upstreamUrl=$upstreamUrl, models=${models.keys}, tokensFile=$tokensFile, upstreamKey=***)"

    companion object {
        const val DEFAULT_UPSTREAM = "https://ark.cn-beijing.volces.com/api/v3"
        val THINKING_MODES = setOf("disabled", "enabled", "auto")

        /** `alias=real-id` or `real-id`, separated by commas, semicolons or whitespace. */
        fun parseModels(text: String): Map<String, String> = text.split(',', ';', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
            .associate { entry ->
                val name = entry.substringBefore('=').trim()
                val real = entry.substringAfter('=', name).trim()
                require(name.isNotEmpty() && real.isNotEmpty() && name.matches(SAFE) && real.matches(SAFE)) { "模型白名单格式无效" }
                name to real
            }
        private val SAFE = Regex("[A-Za-z0-9._:/-]{1,128}")

        /** Reads XIAOMAN_CLASS_* variables. Returns null (feature off) with the reason when something required is missing. */
        fun fromEnv(env: Map<String, String>, tokensFile: Path? = null): Pair<ClassConfig?, String> {
            fun v(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun int(name: String, default: Int) = v(name)?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: error("$name 需要正整数") } ?: default
            val key = v("XIAOMAN_CLASS_UPSTREAM_KEY") ?: v("XIAOMAN_CLASS_UPSTREAM_KEY_FILE")?.let { file ->
                runCatching { Files.readString(Path.of(file)).trim() }.getOrElse { return null to "读不到上游 Key 文件 $file" }
            }
            if (key.isNullOrBlank()) return null to "未配置上游 Key（XIAOMAN_CLASS_UPSTREAM_KEY_FILE）"
            val models = parseModels(v("XIAOMAN_CLASS_MODELS") ?: return null to "未配置模型白名单（XIAOMAN_CLASS_MODELS）")
            if (models.isEmpty()) return null to "模型白名单为空"
            val url = v("XIAOMAN_CLASS_UPSTREAM_URL") ?: DEFAULT_UPSTREAM
            require(url.startsWith("https://") || url.startsWith("http://")) { "XIAOMAN_CLASS_UPSTREAM_URL 无效" }
            val web = v("XIAOMAN_CLASS_WEB_MODEL")
            require(web == null || web in models) { "XIAOMAN_CLASS_WEB_MODEL 不在白名单里" }
            // keep = forward whatever the client sent; default turns deep thinking off to save output tokens.
            val thinking = when (val t = v("XIAOMAN_CLASS_THINKING") ?: "disabled") {
                "keep" -> null
                in THINKING_MODES -> t
                else -> throw IllegalArgumentException("XIAOMAN_CLASS_THINKING 只能是 disabled/enabled/auto/keep")
            }
            return ClassConfig(key, models, url.trimEnd('/'),
                tokensFile ?: v("XIAOMAN_CLASS_TOKENS")?.let { Path.of(it) } ?: Path.of(ClassTokenAdmin.DEFAULT_FILE), web,
                int("XIAOMAN_CLASS_TOKEN_CONCURRENCY", 2), int("XIAOMAN_CLASS_TOKEN_RPM", 30), int("XIAOMAN_CLASS_MAX_CONCURRENT", 32),
                int("XIAOMAN_CLASS_MAX_BODY_MB", 8) * 1024 * 1024, int("XIAOMAN_CLASS_TIMEOUT_SECONDS", 180).toLong(),
                thinking = thinking) to "已开启"
        }
    }
}

/** Per-token concurrency and requests-per-minute, plus a global concurrency cap. */
class ClassLimiter(private val perToken: Int, private val perMinute: Int, private val global: Int,
    private val clock: () -> Long = System::currentTimeMillis) {
    private val active = HashMap<String, Int>()
    private val windows = HashMap<String, ArrayDeque<Long>>()
    private var total = 0

    /** Returns a release function, or why the request must wait. */
    @Synchronized fun acquire(key: String): Pair<(() -> Unit)?, ClassDenied?> {
        val now = clock()
        val window = windows.getOrPut(key) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() >= 60_000) window.removeFirst()
        if ((active[key] ?: 0) >= perToken) return null to ClassDenied(429, "too_many_concurrent_requests", "这个课堂令牌同时进行的请求太多，请等上一个完成", 1)
        if (total >= global) return null to ClassDenied(429, "server_busy", "课堂服务正忙，请稍后重试", 2)
        if (window.size >= perMinute) {
            val wait = ((window.first() + 60_000 - now + 999) / 1000).coerceAtLeast(1)
            return null to ClassDenied(429, "rate_limit_exceeded", "请求太频繁：每个令牌每分钟最多 $perMinute 次", wait)
        }
        window.addLast(now); total++; active[key] = (active[key] ?: 0) + 1
        var released = false
        return {
            synchronized(this) {
                if (!released) {
                    released = true; total--
                    val left = (active[key] ?: 1) - 1
                    if (left <= 0) active.remove(key) else active[key] = left
                }
            }
        } to null
    }
}

/**
 * Serves `/v1/chat/completions` and `/v1/models` for class tokens, forwarding to the instructor's upstream with
 * the instructor's key, and builds the same metered client for the web page's class-token mode.
 * Request and reply content is never logged; logs carry the token label and its first 6 characters only.
 */
class ClassGateway(
    val config: ClassConfig, zone: ZoneId,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = ::println,
    http: OkHttpClient? = null,
) : Closeable {
    val store = ClassTokenStore(config.tokensFile, zone, clock, config.reloadMillis)
    private val limiter = ClassLimiter(config.perTokenConcurrency, config.perTokenPerMinute, config.maxConcurrent, clock)
    private val chatUrl = config.upstreamUrl.trimEnd('/') + "/chat/completions"
    val http: OkHttpClient = (http ?: OkHttpClient()).newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Both /v1 and the web page's class mode reach the upstream through this client, so the thinking default is set once here.
        .addInterceptor { chain -> chain.proceed(withThinking(chain.request())) }
        .build()
    private val flusher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "class-token-flush").apply { isDaemon = true } }

    init {
        flusher.scheduleWithFixedDelay({ flushQuietly() }, config.flushMillis, config.flushMillis, TimeUnit.MILLISECONDS)
    }

    /** True once the token file exists; until then `/v1` and the web page's class mode stay off. */
    val active: Boolean get() = store.exists()

    /** An admitted request: the token and a release for the limiter slot. */
    class Admitted(val token: ClassToken, private val release: () -> Unit) : Closeable { override fun close() = release() }

    fun admit(plain: String?): Pair<Admitted?, ClassDenied?> {
        if (plain.isNullOrBlank()) return null to ClassDenied(401, "missing_token", "缺少课堂令牌（Authorization: Bearer <令牌>）")
        val token = store.find(plain) ?: return null to ClassDenied(401, "invalid_token", "课堂令牌无效")
        store.check(token)?.let { return null to it }
        val (release, denied) = limiter.acquire(token.hash)
        return if (release != null) Admitted(token, release) to null else null to denied
    }

    fun record(token: ClassToken, tokens: Long) = store.record(token.hash, tokens)

    fun flushQuietly() = try { store.flush() } catch (e: Exception) { logger("课堂令牌用量写入失败（${e.javaClass.simpleName}），稍后重试") }

    // ---- OpenAI-compatible endpoints ----

    fun handle(x: HttpExchange) {
        val started = System.nanoTime()
        val route = x.requestURI.path.takeIf { it in ROUTES } ?: "/v1/其他"
        var tag = "-"; var note = ""
        try {
            when (route) {
                "/v1/models" -> {
                    if (x.requestMethod != "GET") return error(x, ClassDenied(405, "method_not_allowed", "请求方法不支持"))
                    val (admitted, denied) = admit(bearer(x))
                    if (admitted == null) { note = denied!!.code; return error(x, denied) }
                    admitted.use {
                        tag = it.token.tag
                        json(x, 200, buildJsonObject {
                            put("object", "list")
                            put("data", JsonArray(config.models.keys.map { name -> buildJsonObject {
                                put("id", name); put("object", "model"); put("created", 0); put("owned_by", "xiaoman-class")
                            } }))
                        })
                    }
                }
                "/v1/chat/completions" -> {
                    if (x.requestMethod != "POST") return error(x, ClassDenied(405, "method_not_allowed", "请求方法不支持"))
                    val (admitted, denied) = admit(bearer(x))
                    if (admitted == null) {
                        note = denied!!.code
                        drain(x, config.maxBodyBytes.toLong())
                        return error(x, denied)
                    }
                    admitted.use { tag = it.token.tag; note = chat(x, it.token) }
                }
                else -> error(x, ClassDenied(404, "not_found", "接口不存在"))
            }
        } catch (_: Exception) {
            if (x.responseCode == -1) runCatching { error(x, ClassDenied(500, "server_error", "请求处理失败")) }
        } finally {
            logger("${x.requestMethod} $route ${x.responseCode} ${(System.nanoTime() - started) / 1_000_000}ms $tag $note".trimEnd())
            x.close()
        }
    }

    /** Forwards one chat completion; returns a short log note (usage, never content). */
    private fun chat(x: HttpExchange, token: ClassToken): String {
        val declared = x.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > config.maxBodyBytes) { drain(x, MAX_DRAIN); error(x, tooLarge()); return "request_too_large" }
        val bytes = x.requestBody.readNBytes(config.maxBodyBytes + 1)
        if (bytes.size > config.maxBodyBytes) { drain(x, MAX_DRAIN); error(x, tooLarge()); return "request_too_large" }
        val root = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject }.getOrNull()
            ?: run { error(x, ClassDenied(400, "invalid_request_error", "请求内容需要是合法 JSON 对象")); return "bad_json" }
        val name = root.str("model")
        val real = config.models[name]
            ?: run { error(x, ClassDenied(400, "model_not_allowed", "模型「${name.take(64)}」不可用，可用：${config.models.keys.joinToString("、")}")); return "model_not_allowed" }
        val stream = (root["stream"] as? JsonPrimitive)?.booleanOrNull == true
        val clientUsage = stream && ((root["stream_options"] as? JsonObject)?.get("include_usage") as? JsonPrimitive)?.booleanOrNull == true
        val outgoing = JsonObject(root + ("model" to JsonPrimitive(real)) + (if (stream) mapOf("stream_options" to
            JsonObject(((root["stream_options"] as? JsonObject) ?: JsonObject(emptyMap())) + ("include_usage" to JsonPrimitive(true)))) else emptyMap()))
        val request = Request.Builder().url(chatUrl)
            .header("Authorization", "Bearer ${config.upstreamKey}")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(outgoing.toString().toRequestBody(JSON))
            .build()
        val call = http.newCall(request)
        if (!stream) call.timeout().timeout(config.timeoutSeconds, TimeUnit.SECONDS)
        val promptEstimate = estimatePrompt(root)
        val response = try { call.execute() } catch (e: IOException) {
            store.record(token.hash, 0)
            error(x, if (e is InterruptedIOException) ClassDenied(504, "upstream_timeout", "模型服务响应超时，请重试")
                else ClassDenied(502, "upstream_unavailable", "连接模型服务失败，请稍后重试"))
            return "upstream_unavailable"
        }
        response.use { r ->
            if (!r.isSuccessful) {
                store.record(token.hash, 0)
                val denied = upstreamError(r.code, r.body?.string().orEmpty())
                error(x, denied)
                return "upstream_${r.code}"
            }
            if (!stream) {
                val text = r.body?.string().orEmpty()
                val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                val used = parsed?.let(::usageOf)
                val counted = used ?: (promptEstimate + estimateCompletion(parsed))
                store.record(token.hash, counted)
                val out = redact(text).toByteArray(Charsets.UTF_8)
                x.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                x.sendResponseHeaders(200, out.size.toLong()); x.responseBody.write(out)
                return "tokens=$counted" + if (used == null) "(估算)" else ""
            }
            x.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
            x.responseHeaders.set("Cache-Control", "no-cache")
            x.sendResponseHeaders(200, 0)
            var used: Long? = null
            var produced = 0L
            var skipBlank = false
            var gone = false
            val source = r.body!!.source()
            val out = x.responseBody
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty() && skipBlank) { skipBlank = false; continue }
                    skipBlank = false
                    var forward = true
                    if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        val chunk = if (data == "[DONE]") null else runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
                        if (chunk != null) {
                            usageOf(chunk)?.let { used = it }
                            produced += estimateCompletion(chunk)
                            // The usage-only final chunk (empty choices) goes only to clients that asked for it.
                            if (!clientUsage && chunk["usage"] is JsonObject && chunk.arr("choices").isEmpty()) { forward = false; skipBlank = true }
                        }
                    }
                    if (forward) {
                        out.write((redact(line) + "\n").toByteArray(Charsets.UTF_8))
                        if (line.isEmpty()) out.flush()
                    }
                }
                out.flush()
            } catch (_: IOException) { gone = true; call.cancel() }
            val counted = used ?: (promptEstimate + produced)
            store.record(token.hash, counted)
            return "stream tokens=$counted" + (if (used == null) "(估算)" else "") + if (gone) " 中断" else ""
        }
    }

    private fun upstreamError(status: Int, body: String): ClassDenied {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val code = (error?.str("code")?.ifBlank { null } ?: root?.str("code")?.ifBlank { null })
            ?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")) && config.upstreamKey !in it }
        val message = redact(error?.str("message") ?: root?.str("message") ?: "").take(300)
        return when {
            code == "SetLimitExceeded" -> ClassDenied(429, "upstream_quota_exceeded", "讲师账号的模型限额已用完，请联系讲师")
            status == 401 || status == 403 -> ClassDenied(502, "upstream_auth_failed", "讲师的模型账号认证失败，请联系讲师")
            status == 404 -> ClassDenied(502, "upstream_model_unavailable", "模型服务上找不到这个模型，请联系讲师")
            status == 429 -> ClassDenied(429, code ?: "upstream_rate_limited", "模型服务限流，请稍后重试" + if (message.isNotEmpty()) "：$message" else "", 2)
            status in 400..499 -> ClassDenied(400, code ?: "invalid_request_error", "模型服务拒绝了请求" + if (message.isNotEmpty()) "：$message" else "")
            else -> ClassDenied(502, "upstream_error", "模型服务暂时出错（$status），请稍后重试")
        }
    }

    // ---- web page class-token mode ----

    /** A client factory for one web session: every call is admitted, limited and metered like `/v1`. */
    fun webClients(plain: String): LlmClientFactory = LlmClientFactory { requested -> WebClassClient(requested, plain) }

    private inner class WebClassClient(override val config: ModelConfig, private val plain: String) : LlmClient {
        private val inner = OpenAiCompatibleClient(ModelConfig(config.role, this@ClassGateway.config.upstreamUrl,
            this@ClassGateway.config.models.getValue(this@ClassGateway.config.webModelName), this@ClassGateway.config.upstreamKey,
            temperature = config.temperature, maxTokens = config.maxTokens, timeoutSeconds = this@ClassGateway.config.timeoutSeconds), http, maxRetries = 1)

        override suspend fun complete(request: ChatRequest): ChatResponse {
            val started = System.nanoTime()
            val (admitted, denied) = admit(plain)
            if (admitted == null) {
                logger("WEB 课堂模式 ${denied!!.status} ${denied.code}")
                throw LlmException(denied.message, denied.status, retryable = false, providerCode = denied.code)
            }
            admitted.use {
                var note = ""
                try {
                    val response = inner.complete(request)
                    val counted = response.usage?.let { u -> (u.promptTokens + u.completionTokens).toLong() }
                        ?: (estimateMessages(request.messages) + response.text.length + response.toolCalls.sumOf { c -> c.argumentsJson.length })
                    store.record(it.token.hash, counted)
                    note = "tokens=$counted" + if (response.usage == null) "(估算)" else ""
                    return response
                } catch (e: LlmException) {
                    store.record(it.token.hash, 0)
                    note = "upstream_${e.statusCode ?: "error"}"
                    // Upstream messages are dropped: they could describe the instructor's account.
                    throw LlmException("模型服务调用失败", e.statusCode, e.retryable, providerCode = e.providerCode)
                } finally {
                    logger("WEB 课堂模式 ${(System.nanoTime() - started) / 1_000_000}ms ${it.token.tag} $note".trimEnd())
                }
            }
        }
    }

    // ---- helpers ----

    private fun withThinking(request: Request): Request {
        val mode = config.thinking ?: return request
        val body = request.body ?: return request
        if (request.method != "POST" || request.url.toString() != chatUrl) return request
        val buffer = okio.Buffer().also { body.writeTo(it) }
        val root = runCatching { Json.parseToJsonElement(buffer.readUtf8()) as JsonObject }.getOrNull() ?: return request
        if ("thinking" in root) return request
        val patched = JsonObject(root + ("thinking" to buildJsonObject { put("type", mode) }))
        return request.newBuilder().post(patched.toString().toRequestBody(body.contentType() ?: JSON)).build()
    }

    private fun bearer(x: HttpExchange): String? = x.requestHeaders.getFirst("Authorization")?.trim()
        ?.takeIf { it.length > 7 && it.substring(0, 7).equals("Bearer ", true) }?.substring(7)?.trim()
    private fun tooLarge() = ClassDenied(413, "request_too_large", "请求内容超过 ${config.maxBodyBytes / 1024 / 1024}MB（截图太大或对话太长）")
    /** Reads and drops the rest of a rejected body, so the client sees the error instead of a reset connection. */
    private fun drain(x: HttpExchange, limit: Long) { runCatching { var left = limit; val buf = ByteArray(65536)
        while (left > 0) { val n = x.requestBody.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; left -= n } } }
    private fun redact(text: String) = text.replace(config.upstreamKey, "***")

    private fun error(x: HttpExchange, d: ClassDenied) {
        d.retryAfterSeconds?.let { x.responseHeaders.set("Retry-After", it.toString()) }
        json(x, d.status, buildJsonObject {
            put("error", buildJsonObject {
                put("message", d.message)
                put("type", when (d.status) { 401 -> "authentication_error"; 429 -> if ("quota" in d.code) "insufficient_quota" else "rate_limit_error"
                    in 400..499 -> "invalid_request_error"; else -> "api_error" })
                put("code", d.code)
            })
        })
    }
    private fun json(x: HttpExchange, code: Int, value: JsonElement) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        x.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        x.sendResponseHeaders(code, bytes.size.toLong()); x.responseBody.write(bytes)
    }

    override fun close() {
        flusher.shutdownNow()
        flushQuietly()
        http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
    }

    companion object {
        private val ROUTES = setOf("/v1/chat/completions", "/v1/models")
        private val JSON = "application/json; charset=utf-8".toMediaType()
        /** Rough cost of one image when the upstream reports no usage. */
        private const val IMAGE_TOKENS = 1_000L
        private const val MAX_DRAIN = 32L * 1024 * 1024

        internal fun usageOf(o: JsonObject): Long? {
            val u = o["usage"] as? JsonObject ?: return null
            fun n(k: String) = (u[k] as? JsonPrimitive)?.longOrNull
            return n("total_tokens") ?: listOfNotNull(n("prompt_tokens"), n("completion_tokens")).takeIf { it.isNotEmpty() }?.sum()
        }
        /** About one token per 3 UTF-8 bytes of text (roughly one per Chinese character) plus a fixed cost per image. */
        internal fun estimatePrompt(root: JsonObject): Long = root.arr("messages").sumOf { m ->
            when (val c = (m as? JsonObject)?.get("content")) {
                is JsonPrimitive -> c.contentOrNull.orEmpty().toByteArray().size / 3L + 4
                is JsonArray -> c.sumOf { part ->
                    val p = part as? JsonObject
                    if (p?.str("type") == "image_url") IMAGE_TOKENS else p?.str("text").orEmpty().toByteArray().size / 3L
                } + 4
                else -> 4L
            }
        }
        private fun estimateMessages(messages: List<ChatMessage>): Long = messages.sumOf { m ->
            m.parts.sumOf { when (it) { is ContentPart.ImagePng -> IMAGE_TOKENS; is ContentPart.Text -> it.text.toByteArray().size / 3L } } + 4
        }
        /** Output characters in a completion or stream chunk. */
        internal fun estimateCompletion(o: JsonObject?): Long = o?.arr("choices")?.sumOf { c ->
            val choice = c as? JsonObject ?: return@sumOf 0L
            val m = (choice["message"] as? JsonObject) ?: (choice["delta"] as? JsonObject) ?: return@sumOf 0L
            (m.str("content").length + m.str("reasoning_content").length +
                m.arr("tool_calls").sumOf { t -> ((t as? JsonObject)?.obj("function")?.str("arguments")?.length ?: 0) }).toLong()
        } ?: 0L
    }
}
