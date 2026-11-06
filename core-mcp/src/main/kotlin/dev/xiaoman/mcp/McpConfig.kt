package dev.xiaoman.mcp

import java.io.File
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ConfirmPolicy { WRITES, ALL, NONE }

class McpServerConfig(
    val name: String,
    val title: String = name,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val confirm: ConfirmPolicy = ConfirmPolicy.WRITES,
    val tools: Set<String>? = null,
    val timeoutSeconds: Long = 30,
    internal val substitutedSecrets: Set<String> = emptySet(),
) {
    internal val redactor = SecretRedactor(listOf(this))

    override fun toString(): String = redactor.redact(
        "McpServerConfig(name=$name, title=$title, url=${url?.replace(Regex("([?&][^=&#]+)=([^&#]*)"), "$1=***")}, " +
            "headers=${headers.mapValues { "***" }}, command=$command, args=$args, " +
            "env=${env.mapValues { "***" }}, enabled=$enabled, confirm=$confirm, tools=$tools, timeoutSeconds=$timeoutSeconds)"
    )
}

class McpConfig(val servers: List<McpServerConfig> = emptyList(), val warnings: List<String> = emptyList()) {
    override fun toString() = "McpConfig(servers=$servers, warnings=$warnings)"

    companion object {
        fun load(file: File, env: Map<String, String> = System.getenv()): McpConfig =
            if (file.exists()) parse(file.readText(), env) else McpConfig()

        fun parse(json: String, env: Map<String, String> = System.getenv()): McpConfig {
            val root = try { Json.parseToJsonElement(json) as? JsonObject } catch (_: Exception) { null }
                ?: return McpConfig(warnings = listOf("MCP 配置无效：需要 JSON 对象"))
            val entries = root["mcpServers"] as? JsonObject
                ?: return McpConfig(warnings = listOf("MCP 配置无效：缺少 mcpServers 对象"))
            val servers = mutableListOf<McpServerConfig>()
            val warnings = mutableListOf<String>()
            entries.forEach { (name, value) ->
                if (name.startsWith("_")) return@forEach
                val raw = value as? JsonObject
                // Disabled entries need neither valid connection fields nor environment variables.
                if ((raw?.get("enabled") as? JsonPrimitive)?.booleanOrNull == false) return@forEach
                if (!name.matches(Regex("[a-zA-Z0-9_-]+"))) {
                    warnings += "MCP 服务器未启用：名称只能包含字母、数字、下划线或连字符"
                    return@forEach
                }
                if (raw == null) {
                    warnings += "服务器 $name 未启用：配置需要 JSON 对象"
                    return@forEach
                }
                val missing = linkedSetOf<String>()
                val secrets = mutableSetOf<String>()
                fun substitute(element: JsonElement): JsonElement = when (element) {
                    is JsonPrimitive -> if (!element.isString) element else JsonPrimitive(
                        Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}").replace(element.content) { match ->
                            val variable = match.groupValues[1]
                            env[variable]?.also { secrets += it } ?: run { missing += variable; "" }
                        }
                    )
                    is JsonArray -> JsonArray(element.map(::substitute))
                    is JsonObject -> JsonObject(element.mapValues { substitute(it.value) })
                }
                val fields = substitute(JsonObject(raw.filterKeys { !it.startsWith("_") })) as JsonObject
                if (missing.isNotEmpty()) {
                    missing.forEach { warnings += "服务器 $name 未启用：缺少环境变量 $it" }
                    return@forEach
                }
                try {
                    fun strings(key: String): Map<String, String> = (fields[key] as? JsonObject)?.mapValues {
                        (it.value as? JsonPrimitive)?.contentOrNull ?: error("Invalid map")
                    }.orEmpty()
                    val url = fields.string("url")
                    val command = fields.string("command")
                    require((url != null) xor (command != null))
                    require(url == null || url.toHttpUrlOrNull() != null)
                    require(command == null || command.isNotBlank())
                    val confirm = when (fields.string("confirm") ?: "writes") {
                        "writes" -> ConfirmPolicy.WRITES
                        "all" -> ConfirmPolicy.ALL
                        "none" -> ConfirmPolicy.NONE
                        else -> error("Invalid confirm")
                    }
                    val timeout = (fields["timeoutSeconds"] as? JsonPrimitive)?.longOrNull ?: 30
                    require(timeout > 0)
                    fun array(key: String) = (fields[key] as? JsonArray)?.map {
                        (it as? JsonPrimitive)?.contentOrNull ?: error("Invalid array")
                    }
                    servers += McpServerConfig(
                        name, fields.string("title") ?: name, url, strings("headers"), command,
                        array("args").orEmpty(), strings("env"),
                        (fields["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                        confirm, array("tools")?.toSet(), timeout, secrets,
                    )
                } catch (_: Exception) {
                    warnings += "服务器 $name 未启用：配置字段无效"
                }
            }
            return McpConfig(servers, SecretRedactor(servers).let { redactor -> warnings.map(redactor::redact) })
        }
    }
}

/** Redact values of at least six characters before truncating, including encoded variants. */
internal class SecretRedactor(servers: List<McpServerConfig>) {
    private val secrets: List<String> = buildSet {
        servers.forEach { server ->
            addAll(server.env.values)
            addAll(server.headers.values)
            server.headers.values.forEach { value ->
                if (value.startsWith("Bearer ", ignoreCase = true)) add(value.substring(7))
            }
            addAll(server.substitutedSecrets)
            server.url?.toHttpUrlOrNull()?.let { url ->
                url.queryParameterNames.forEach { name -> addAll(url.queryParameterValues(name).filterNotNull()) }
                url.encodedQuery?.split('&')?.forEachIndexed { index, parameter ->
                    if ((url.queryParameterValue(index)?.length ?: 0) >= 6) add(parameter.substringAfter('=', ""))
                }
                if (url.password.isNotEmpty()) add(url.password)
            }
        }
    }.filter { it.length >= 6 }.flatMap { listOf(it, JsonPrimitive(it).toString().removeSurrounding("\"")) }
        .distinct().sortedByDescending { it.length }

    fun redact(text: String): String = secrets.fold(text) { result, secret -> result.replace(secret, "***") }

    fun redactJson(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redact(element.content)) else element
        is JsonArray -> JsonArray(element.map(::redactJson))
        is JsonObject -> JsonObject(element.entries.associate { redact(it.key) to redactJson(it.value) })
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
