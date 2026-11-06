package dev.xiaoman.web

import dev.xiaoman.mcp.McpException
import dev.xiaoman.mcp.McpServerConfig
import dev.xiaoman.mcp.McpStdioTransport
import dev.xiaoman.mcp.McpTransport
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Catalog servers that run as a local process (stdio), e.g. 12306 via `npx -y 12306-mcp`.
 *
 * Only commands from the built-in catalog are ever started; hand-added servers stay HTTPS-only. Each web
 * session that ticks such a server gets its own child process, stopped when the session ends, and at most
 * [maxProcesses] run at once. Children see only [ENVIRONMENT] of the server's variables, so the access code
 * and anything else in the service environment is not passed on.
 */
class StdioMcp(val enabled: Boolean, private val maxProcesses: Int = 20,
    private val start: (McpServerConfig) -> McpTransport = { McpStdioTransport(it, ENVIRONMENT) }) {
    private val running = AtomicInteger()
    val runningCount: Int get() = running.get()

    fun transport(server: McpServerConfig): McpTransport {
        if (!enabled) throw McpException("服务器未开启本地 MCP（需要 Node.js）")
        if (running.incrementAndGet() > maxProcesses) {
            running.decrementAndGet()
            throw McpException("${server.title} 当前使用人数已满，请稍后再试")
        }
        val delegate = try { start(server) } catch (e: Exception) { running.decrementAndGet(); throw e }
        return object : McpTransport {
            private val released = AtomicBoolean(false)
            override suspend fun exchange(message: JsonObject) = delegate.exchange(message)
            override fun close() {
                try { delegate.close() } finally { if (released.compareAndSet(false, true)) running.decrementAndGet() }
            }
        }
    }

    companion object {
        /** Variables a Node.js MCP server needs to run and to reach the internet through a proxy. */
        val ENVIRONMENT = setOf(
            "PATH", "HOME", "LANG", "LC_ALL", "TZ", "TMPDIR", "USER",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
            "NODE_EXTRA_CA_CERTS", "npm_config_cache", "npm_config_registry", "NPM_CONFIG_CACHE", "NPM_CONFIG_REGISTRY",
        )

        /** Commands that catalog stdio servers use; `auto` enables stdio servers when all are on PATH. */
        val COMMANDS = listOf("node", "npx")

        fun commandsOnPath(path: String? = System.getenv("PATH"), commands: List<String> = COMMANDS): Boolean {
            val dirs = path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
            return commands.all { cmd -> dirs.any { File(it, cmd).let { f -> f.isFile && f.canExecute() } } }
        }
    }
}
