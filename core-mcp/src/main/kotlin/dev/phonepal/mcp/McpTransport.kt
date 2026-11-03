package dev.phonepal.mcp

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One JSON-RPC exchange; notifications have no id and return null. */
interface McpTransport : Closeable {
    suspend fun exchange(message: JsonObject): JsonObject?

    companion object {
        fun create(server: McpServerConfig): McpTransport =
            if (server.url != null) McpHttpTransport(server) else McpStdioTransport(server)
    }
}

/** Safe exception without a raw transport cause that could disclose credentials. */
class McpException(message: String) : IOException(message)

class McpHttpTransport(
    private val server: McpServerConfig,
    http: OkHttpClient = sharedHttp,
) : McpTransport {
    private val http = http.newBuilder()
        .callTimeout(server.timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(server.timeoutSeconds, TimeUnit.SECONDS)
        .connectTimeout(server.timeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val mutex = Mutex()
    private var sessionId: String? = null
    private var initialized = false
    @Volatile private var closed = false
    @Volatile private var activeCall: Call? = null

    override suspend fun exchange(message: JsonObject): JsonObject? = mutex.withLock {
        try {
            check(!closed) { "传输已关闭" }
            withContext(Dispatchers.IO) {
                val builder = Request.Builder().url(requireNotNull(server.url))
                server.headers.forEach { (key, value) -> builder.header(key, value) }
                builder.header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .post(message.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                if (initialized) builder.header("MCP-Protocol-Version", McpClient.PROTOCOL_VERSION)
                sessionId?.let { builder.header("Mcp-Session-Id", it) }
                val call = http.newCall(builder.build())
                activeCall = call
                if (closed) call.cancel()
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    try {
                        val result = call.execute().use { response ->
                            if (!response.isSuccessful) {
                                // Do not expose the URL, even if the server echoes it in the error body.
                                val detail = server.redactor.redact(response.body?.string().orEmpty())
                                    .replace(server.redactor.redact(server.url.orEmpty()), "[服务器地址]").take(200)
                                throw McpException("服务器 ${server.name} HTTP ${response.code}：$detail")
                            }
                            response.header("Mcp-Session-Id")?.let { sessionId = it }
                            val id = message["id"]
                            if (id == null) null else {
                                val body = response.body ?: throw McpException("服务器 ${server.name} 响应为空")
                                val result = if (response.header("Content-Type").orEmpty()
                                        .startsWith("text/event-stream", ignoreCase = true)) {
                                    val data = mutableListOf<String>()
                                    var found: JsonObject? = null
                                    fun event(): JsonObject? {
                                        val value = runCatching {
                                            Json.parseToJsonElement(data.joinToString("\n")) as? JsonObject
                                        }.getOrNull()
                                        data.clear()
                                        return value?.takeIf { it["id"] == id }
                                    }
                                    val source = body.source()
                                    while (found == null) {
                                        val line = source.readUtf8Line() ?: break
                                        when {
                                            line.isEmpty() -> found = event()
                                            line == "data" -> data += ""
                                            line.startsWith("data:") -> data += line.substring(5).removePrefix(" ")
                                        }
                                    }
                                    found ?: event() ?: throw McpException("服务器 ${server.name} 未返回匹配的响应")
                                } else {
                                    Json.parseToJsonElement(body.string()) as? JsonObject
                                        ?: throw McpException("服务器 ${server.name} 响应格式无效")
                                }
                                if (result["id"] != id) throw McpException("服务器 ${server.name} 响应 id 不匹配")
                                if (message.string("method") == "initialize" && result["error"] == null) initialized = true
                                result
                            }
                        }
                        continuation.resume(result)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    } finally {
                        activeCall = null
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw McpException(server.redactor.redact(
                if (e is McpException) e.message.orEmpty() else "服务器 ${server.name} 请求失败：${e.message ?: e.javaClass.simpleName}"
            ))
        }
    }

    override fun close() {
        closed = true
        activeCall?.cancel()
    }

    private companion object { val sharedHttp = OkHttpClient() }
}

/** Desktop-only transport. Stdout is drained independently so requests can time out. */
class McpStdioTransport(private val server: McpServerConfig) : McpTransport {
    private class Incoming(val message: JsonObject?)
    private val mutex = Mutex()
    private val incoming = LinkedBlockingQueue<Incoming>(256)
    @Volatile private var closed = false
    private val process = try {
        ProcessBuilder(listOf(requireNotNull(server.command)) + server.args).apply {
            environment().putAll(server.env)
        }.start()
    } catch (e: Exception) {
        throw McpException(server.redactor.redact("服务器 ${server.name} 启动失败：${e.message ?: e.javaClass.simpleName}"))
    }
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val stdout = Thread({
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (!closed) {
                    val line = reader.readLine() ?: break
                    val message = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                    if (message?.get("id") != null) incoming.offer(Incoming(message))
                }
            }
        } catch (_: Exception) {
            // EOF and reader errors are reported through the safe terminal marker.
        } finally {
            incoming.offer(Incoming(null))
        }
    }, "mcp-stdout").apply { isDaemon = true; start() }
    private val stderr = Thread({
        try { process.errorStream.use { stream ->
            val bytes = ByteArray(1024)
            while (stream.read(bytes) != -1) { /* Discard stderr, including credentials. */ }
        } } catch (_: Exception) { }
    }, "mcp-stderr").apply { isDaemon = true; start() }

    override suspend fun exchange(message: JsonObject): JsonObject? = mutex.withLock {
        try {
            runInterruptible(Dispatchers.IO) {
                check(!closed) { "传输已关闭" }
                writer.write(message.toString())
                writer.newLine()
                writer.flush()
                val id = message["id"] ?: return@runInterruptible null
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(server.timeoutSeconds)
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    val entry = if (remaining > 0) incoming.poll(remaining, TimeUnit.NANOSECONDS) else null
                    if (entry == null) throw McpException("服务器 ${server.name} 请求超时")
                    val response = entry.message ?: throw McpException("服务器 ${server.name} 进程已退出")
                    if (response["id"] == id) return@runInterruptible response
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } catch (e: CancellationException) {
            // A cancelled exchange cannot safely reuse an unread response.
            close()
            throw e
        } catch (e: Exception) {
            throw McpException(server.redactor.redact(
                if (e is McpException) e.message.orEmpty() else "服务器 ${server.name} 请求失败：${e.message ?: e.javaClass.simpleName}"
            ))
        }
    }

    override fun close() {
        closed = true
        process.destroy()
        incoming.clear()
        incoming.offer(Incoming(null))
        runCatching { writer.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        stdout.interrupt()
        stderr.interrupt()
    }
}
