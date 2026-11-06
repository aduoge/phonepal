package dev.xiaoman.mcp

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class McpTool(
    val name: String,
    val title: String?,
    val description: String?,
    val inputSchema: JsonObject,
    val readOnlyHint: Boolean?,
)

class McpCallResult(val ok: Boolean, val text: String)

/** MCP lifecycle, pagination and tool result decoding shared by both transports. */
class McpClient(
    private val server: McpServerConfig,
    private val transport: McpTransport = McpTransport.create(server),
    private val version: String = "1.0",
) : Closeable {
    private val ids = AtomicLong()
    private val initialization = Mutex()
    private var initialized = false

    suspend fun initialize() = initialization.withLock {
        if (!initialized) {
            request("initialize", buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                putJsonObject("clientInfo") { put("name", "xiaoman"); put("version", version) }
            })
            safeExchange(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") })
            initialized = true
        }
    }

    suspend fun listTools(): List<McpTool> {
        initialize()
        val tools = mutableListOf<McpTool>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val result = request("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            (result["tools"] as? JsonArray).orEmpty().forEach { item ->
                val tool = item as? JsonObject ?: return@forEach
                val name = tool.string("name") ?: return@forEach
                val annotations = tool["annotations"] as? JsonObject
                tools += McpTool(
                    name, tool.string("title") ?: annotations?.string("title"), tool.string("description"),
                    tool["inputSchema"] as? JsonObject ?: EMPTY_SCHEMA,
                    (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull,
                )
            }
            cursor = result.string("nextCursor")?.takeIf { it.isNotEmpty() }
            if (cursor != null && !cursors.add(cursor)) throw McpException("服务器 ${server.name} 分页游标重复")
        } while (cursor != null)
        return tools
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        initialize()
        val result = request("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
        val content = (result["content"] as? JsonArray).orEmpty()
        val hasText = content.any { (it as? JsonObject)?.string("type") == "text" }
        val text = content.joinToString("\n") { item ->
            val part = item as? JsonObject
            when (part?.string("type")) {
                "text" -> part.string("text").orEmpty()
                "image" -> "[图片内容]"
                "resource", "resource_link" -> "[资源内容]"
                "audio" -> "[音频内容]"
                else -> "[非文本内容]"
            }
        }
        val structured = result["structuredContent"]?.takeUnless { it is JsonNull }?.toString()
        val decoded = if (!hasText && structured != null) listOf(text, structured).filter { it.isNotEmpty() }.joinToString("\n") else text
        return McpCallResult((result["isError"] as? JsonPrimitive)?.booleanOrNull != true, server.redactor.redact(decoded))
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = JsonPrimitive(ids.incrementAndGet())
        val response = safeExchange(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }) ?: throw McpException("服务器 ${server.name} 响应为空")
        if (response["id"] != id) throw McpException("服务器 ${server.name} 响应 id 不匹配")
        (response["error"] as? JsonObject)?.let { error ->
            throw McpException(server.redactor.redact("服务器 ${server.name} RPC ${error["code"]}：${error.string("message").orEmpty()}"))
        }
        return response["result"] as? JsonObject ?: throw McpException("服务器 ${server.name} 缺少 result 对象")
    }

    private suspend fun safeExchange(message: JsonObject): JsonObject? = try {
        transport.exchange(message)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw McpException(server.redactor.redact("服务器 ${server.name}：${e.message ?: e.javaClass.simpleName}"))
    }

    override fun close() = transport.close()

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        internal val EMPTY_SCHEMA = buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) }
    }
}
