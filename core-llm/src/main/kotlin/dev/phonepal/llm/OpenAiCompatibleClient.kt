package dev.phonepal.llm

import dev.phonepal.api.ChatMessage
import dev.phonepal.api.ChatRequest
import dev.phonepal.api.ChatResponse
import dev.phonepal.api.ChatRole
import dev.phonepal.api.ContentPart
import dev.phonepal.api.LlmClient
import dev.phonepal.api.LlmException
import dev.phonepal.api.ModelConfig
import dev.phonepal.api.ToolCall
import dev.phonepal.api.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LlmClient] for any OpenAI-compatible `/chat/completions` endpoint.
 *
 * Retries 408/409/429/5xx and transport errors up to [maxRetries] times with exponential backoff
 * starting at [baseDelayMillis] (or the server's `Retry-After` seconds). If the endpoint rejects
 * `response_format`, the request is retried once without it and JSON mode stays off for this client.
 * Error messages never contain the API key.
 */
class OpenAiCompatibleClient(
    override val config: ModelConfig,
    http: OkHttpClient = LlmClients.sharedHttp,
    private val maxRetries: Int = 2,
    private val baseDelayMillis: Long = 500,
    /** Backoff sleep; replace in tests to avoid real waiting. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : LlmClient {
    private val http = http.newBuilder()
        .callTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .build()
    private val url = config.baseUrl.trimEnd('/') + "/chat/completions"

    @Volatile
    private var jsonModeUnsupported = false

    override suspend fun complete(request: ChatRequest): ChatResponse {
        var attempt = 0
        var jsonMode = request.jsonMode && !jsonModeUnsupported
        while (true) {
            val result = try {
                send(buildBody(request, jsonMode))
            } catch (e: IOException) {
                if (attempt < maxRetries) {
                    sleep(backoff(attempt++, null))
                    continue
                }
                throw LlmException(redact("Request to $url failed: ${e.message ?: e.javaClass.simpleName}"), retryable = true, cause = e)
            }
            if (result.code in 200..299) return parseResponse(result.body)
            if (result.code == 400 && jsonMode && result.body.contains("response_format")) {
                jsonModeUnsupported = true
                jsonMode = false
                continue
            }
            val providerCode = providerCode(result.body)
            // A configured spending/inference limit needs an account change, not backoff.
            val retryable = isRetryable(result.code) && providerCode != "SetLimitExceeded"
            if (retryable && attempt < maxRetries) {
                sleep(backoff(attempt++, result.retryAfter))
                continue
            }
            throw LlmException(redact("HTTP ${result.code} from $url: ${errorDetail(result.body)}"), result.code, retryable,
                providerCode = providerCode)
        }
    }

    internal fun buildBody(request: ChatRequest, jsonMode: Boolean): JsonObject = buildJsonObject {
        put("model", config.model)
        putJsonArray("messages") { request.messages.forEach { add(encodeMessage(it)) } }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        }
                    }
                }
            }
        }
        if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
        config.temperature?.let { put("temperature", it) }
        config.maxTokens?.let { put("max_tokens", it) }
    }

    private fun encodeMessage(message: ChatMessage): JsonObject = buildJsonObject {
        put("role", message.role.name.lowercase())
        val hasImage = message.parts.any { it is ContentPart.ImagePng }
        when {
            hasImage -> putJsonArray("content") {
                message.parts.forEach { part ->
                    addJsonObject {
                        when (part) {
                            is ContentPart.Text -> {
                                put("type", "text")
                                put("text", part.text)
                            }
                            is ContentPart.ImagePng -> {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(part.bytes))
                                }
                            }
                        }
                    }
                }
            }
            message.toolCalls.isNotEmpty() && message.text.isEmpty() -> put("content", JsonNull)
            else -> put("content", message.text)
        }
        if (message.role == ChatRole.ASSISTANT && message.toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                message.toolCalls.forEach { call ->
                    addJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", call.name)
                            put("arguments", call.argumentsJson)
                        }
                    }
                }
            }
        }
        message.toolCallId?.let { put("tool_call_id", it) }
    }

    private fun parseResponse(body: String): ChatResponse {
        val root = try {
            json.parseToJsonElement(body) as JsonObject
        } catch (e: Exception) {
            throw LlmException(redact("Malformed response from $url: ${body.take(200)}"), 200, cause = e)
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw LlmException(redact("Response from $url has no choices: ${errorDetail(body)}"), 200)
        val message = choice["message"] as? JsonObject ?: JsonObject(emptyMap())
        val toolCalls = (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull { element ->
            val call = element as? JsonObject ?: return@mapNotNull null
            val function = call["function"] as? JsonObject ?: return@mapNotNull null
            val args = when (val a = function["arguments"]) {
                null, is JsonNull -> "{}"
                is JsonPrimitive -> a.content
                else -> a.toString()
            }
            ToolCall(call.string("id").orEmpty(), function.string("name").orEmpty(), args)
        }
        val usage = (root["usage"] as? JsonObject)?.let {
            Usage(it.int("prompt_tokens") ?: 0, it.int("completion_tokens") ?: 0)
        }
        return ChatResponse(contentText(message["content"]), toolCalls, choice.string("finish_reason"), usage)
    }

    private fun contentText(content: JsonElement?): String = when (content) {
        null, is JsonNull -> ""
        is JsonPrimitive -> content.content
        is JsonArray -> content.joinToString("") { part ->
            when (part) {
                is JsonPrimitive -> part.content
                is JsonObject -> if (part.string("type") in setOf(null, "text")) part.string("text").orEmpty() else ""
                else -> ""
            }
        }
        is JsonObject -> content.string("text").orEmpty()
    }

    /** Runs the HTTP call on [Dispatchers.IO]; cancelling the coroutine cancels the OkHttp call. */
    private suspend fun send(body: JsonObject): HttpResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
        config.extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        val call = http.newCall(builder.build())
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            try {
                val result = call.execute().use { response ->
                    HttpResult(response.code, response.body?.string().orEmpty(), response.header("Retry-After"))
                }
                cont.resume(result)
            } catch (e: IOException) {
                cont.resumeWithException(e)
            }
        }
    }

    private fun backoff(attempt: Int, retryAfter: String?): Long =
        retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000 }
            ?: (baseDelayMillis shl attempt.coerceAtMost(20))

    private fun errorDetail(body: String): String {
        val message = runCatching {
            val root = json.parseToJsonElement(body) as JsonObject
            when (val error = root["error"]) {
                is JsonObject -> error.string("message")
                is JsonPrimitive -> error.contentOrNull
                else -> root.string("message")
            }
        }.getOrNull()
        return (message ?: body).take(500).ifBlank { "<empty body>" }
    }

    /** Only carry a short provider identifier into user-facing events, never a raw error body. */
    private fun providerCode(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body) as JsonObject
        val error = root["error"] as? JsonObject
        (error?.string("code") ?: root.string("code"))?.takeIf {
            it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}")) &&
                (config.apiKey.isBlank() || !it.contains(config.apiKey))
        }
    }.getOrNull()

    private fun redact(text: String): String =
        if (config.apiKey.isBlank()) text else text.replace(config.apiKey, "***")

    private class HttpResult(val code: Int, val body: String, val retryAfter: String?)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        fun isRetryable(code: Int) = code == 408 || code == 409 || code == 429 || code in 500..599
        fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
