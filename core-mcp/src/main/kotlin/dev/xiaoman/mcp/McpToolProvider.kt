package dev.xiaoman.mcp

import dev.xiaoman.api.*
import java.io.Closeable
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class ServerStatus(val name: String, val title: String, val connected: Boolean, val toolCount: Int, val error: String?)

/** Lazily discovers independent servers and mediates every tool call. */
class McpToolProvider(
    private val config: McpConfig,
    private val approvals: ToolApprovalGateway,
    private val transportFactory: (McpServerConfig) -> McpTransport = McpTransport::create,
    private val maxResultChars: Int = 6000,
    private val retryAfterMillis: Long = 5 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : ToolProvider, Closeable {
    private class State(val server: McpServerConfig) {
        var client: McpClient? = null
        var pending: McpClient? = null
        var tools: List<McpTool> = emptyList()
        var error: String? = null
        var lastFailureAtMillis: Long? = null
    }
    private class Binding(val state: State, val tool: McpTool, val spec: ToolSpec)
    private val states = config.servers.filter { it.enabled }.map(::State)
    private val redactor = SecretRedactor(config.servers)
    private val mutex = Mutex()
    private val lifecycle = Any()
    private var bindings: Map<String, Binding> = emptyMap()
    @Volatile private var closed = false

    init {
        require(maxResultChars > 0)
        require(retryAfterMillis >= 0)
    }

    override suspend fun specs(): List<ToolSpec> = discover(refresh = false)

    private suspend fun discover(refresh: Boolean): List<ToolSpec> = mutex.withLock {
        if (closed) return@withLock emptyList()
        coroutineScope {
            val now = clock()
            states.filter { state ->
                state.client == null && (refresh || state.lastFailureAtMillis?.let {
                    now - it >= retryAfterMillis
                } != false)
            }.map { state -> async(Dispatchers.IO) {
                var client: McpClient? = null
                try {
                    client = McpClient(state.server, transportFactory(state.server))
                    synchronized(lifecycle) {
                        if (closed) client.close() else state.pending = client
                    }
                    val tools = client.listTools().filter { state.server.tools == null || it.name in state.server.tools }
                    synchronized(lifecycle) {
                        state.pending = null
                        if (closed) client.close() else {
                            state.client = client
                            state.tools = tools
                            state.error = null
                            state.lastFailureAtMillis = null
                        }
                    }
                } catch (e: CancellationException) {
                    client?.let { runCatching { it.close() } }
                    synchronized(lifecycle) { state.pending = null }
                    throw e
                } catch (e: Exception) {
                    client?.let { runCatching { it.close() } }
                    synchronized(lifecycle) { state.pending = null }
                    state.error = redactor.redact(e.message ?: e.javaClass.simpleName).take(600)
                    state.lastFailureAtMillis = clock()
                }
            } }.awaitAll()
        }
        if (closed) return@withLock emptyList()
        // Keep existing names stable when an unavailable server later recovers.
        val result = bindings.toMutableMap()
        states.filter { it.client != null }.forEach { state ->
            state.tools.forEach toolLoop@ { tool ->
                if (result.values.any { it.state === state && it.tool === tool }) return@toolLoop
                val base = cleanName(redactor.redact("${state.server.name}__${tool.name}"))
                var name = base
                var suffix = 2
                while (name in result) {
                    val ending = "_${suffix++}"
                    name = base.take(64 - ending.length) + ending
                }
                val description = redactor.redact("[服务器 ${state.server.title}] " +
                    listOfNotNull(tool.title, tool.description).joinToString("：")).take(600)
                val schema = redactor.redactJson(tool.inputSchema) as JsonObject
                result[name] = Binding(state, tool, ToolSpec(name, description, schema))
            }
        }
        bindings = result
        result.values.map { it.spec }
    }

    /** Retry failed servers immediately when [refresh] is true; keep connected servers cached. */
    suspend fun status(refresh: Boolean = false): List<ServerStatus> {
        discover(refresh)
        return mutex.withLock {
            config.servers.map { server ->
                val state = states.firstOrNull { it.server === server }
                ServerStatus(redactor.redact(server.name), redactor.redact(server.title),
                    state?.client != null && !closed, state?.tools?.size ?: 0, state?.error)
            }
        }
    }

    override suspend fun call(taskId: String, name: String, argumentsJson: String): ToolResult? {
        val binding = mutex.withLock { bindings[name] } ?: return null
        val server = binding.state.server
        val tool = binding.tool
        val label = redactor.redact("${server.title}·${tool.name}")
        fun result(ok: Boolean, text: String) = ToolResult(ok, truncate(redactor.redact(text)),
            redactor.redact("$label ${if (ok) "成功" else "失败"}").take(600))
        try {
            check(!closed) { "MCP 已关闭" }
            val arguments = if (argumentsJson.isBlank()) JsonObject(emptyMap()) else
                Json.parseToJsonElement(argumentsJson) as? JsonObject ?: error("工具参数必须是 JSON 对象")
            val confirm = when (server.confirm) {
                ConfirmPolicy.NONE -> false
                ConfirmPolicy.ALL -> true
                ConfirmPolicy.WRITES -> tool.readOnlyHint != true
            }
            if (confirm && !approvals.approve(ToolApprovalRequest(
                    taskId, redactor.redact(server.title), redactor.redact(tool.name),
                    tool.title?.let(redactor::redact), redactor.redact(arguments.toString()),
                    if (server.confirm == ConfirmPolicy.ALL) "该服务器的所有工具调用都需要确认" else "该工具会修改外部数据或产生费用",
                ))) {
                return result(false, buildJsonObject { put("error", "用户没有确认，未执行 ${tool.name}") }.toString())
            }
            val response = requireNotNull(binding.state.client).callTool(tool.name, arguments)
            return result(response.ok, response.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Redact before JSON escaping so quoted secrets cannot leak through an error result.
            return result(false, buildJsonObject { put("error", redactor.redact(e.message ?: e.javaClass.simpleName)) }.toString())
        }
    }

    private fun truncate(text: String): String {
        if (text.length <= maxResultChars) return text
        val marker = "\n[结果已截断]"
        return if (maxResultChars >= marker.length) text.take(maxResultChars - marker.length) + marker
            else "[已截断]".take(maxResultChars)
    }

    override fun close() = synchronized(lifecycle) {
        closed = true
        states.forEach { state ->
            state.pending?.let { runCatching { it.close() } }
            state.client?.let { runCatching { it.close() } }
        }
    }

    private fun cleanName(raw: String): String {
        val clean = raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifEmpty { "mcp_tool" }
        if (clean.length <= 64) return clean
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it.toInt() and 255) }
        return clean.take(55) + "_" + hash
    }
}
