package dev.phonepal.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A key the user pastes when ticking a catalog server; [name] is the `${NAME}` placeholder it fills. */
data class CatalogKey(val name: String, val label: String, val help: String?)

/** One tickable server of the built-in catalog. */
data class CatalogServer(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val official: Boolean,
    /** The `mcpServers` entry this server becomes, still with `${NAME}` placeholders. */
    val entry: JsonObject,
    val keys: List<CatalogKey>,
    /** `desktop` for stdio servers; HTTP servers work everywhere. */
    val platforms: Set<String>,
) {
    val remote: Boolean get() = entry["url"] != null

    fun availableOn(platform: McpPlatform) = platform.name.lowercase() in platforms
}

enum class McpPlatform { DESKTOP, ANDROID, WEB }

/** A server the user adds by hand: any Streamable HTTP MCP endpoint. */
data class CustomMcpServer(
    val title: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val confirm: ConfirmPolicy = ConfirmPolicy.WRITES,
) {
    override fun toString() = "CustomMcpServer(title=$title, url=<hidden>, headers=${headers.keys}, confirm=$confirm)"
}

/** What the user ticked: catalog ids, the keys they pasted (by placeholder name) and their own servers. */
data class McpSelection(
    val enabled: Set<String> = emptySet(),
    val keys: Map<String, String> = emptyMap(),
    val custom: List<CustomMcpServer> = emptyList(),
) {
    override fun toString() = "McpSelection(enabled=$enabled, keys=${keys.keys}, custom=$custom)"
}

/**
 * The one-tap catalog (`catalog.json` in this module's resources) and the step from a [McpSelection] to a
 * [McpConfig]. Keys stay in memory: they are substituted into the config, never written back to the catalog.
 */
class McpCatalog(val servers: List<CatalogServer>) {
    fun server(id: String) = servers.firstOrNull { it.id == id }

    fun availableOn(platform: McpPlatform) = servers.filter { it.availableOn(platform) }

    /** Keys still missing for the ticked servers, as `id -> key names`. */
    fun missingKeys(selection: McpSelection): Map<String, List<String>> = selection.enabled.mapNotNull { id ->
        val server = server(id) ?: return@mapNotNull null
        val missing = server.keys.map { it.name }.filter { selection.keys[it].isNullOrBlank() }
        if (missing.isEmpty()) null else id to missing
    }.toMap()

    /**
     * Ticked catalog servers available on [platform] plus custom ones (named `custom-1`, `custom-2` ...).
     * A ticked server whose keys are missing is skipped with a warning, as [McpConfig.parse] does.
     */
    fun toConfig(selection: McpSelection, platform: McpPlatform): McpConfig {
        val warnings = mutableListOf<String>()
        val json = buildJsonObject {
            putJsonObject("mcpServers") {
                selection.enabled.sorted().forEach { id ->
                    val server = server(id)
                    when {
                        server == null -> warnings += "未知的 MCP 服务 $id，已忽略"
                        !server.availableOn(platform) -> warnings += "${server.title} 只能在电脑上使用，已跳过"
                        server.keys.any { selection.keys[it.name].isNullOrBlank() } -> warnings +=
                            "${server.title} 还没有填写 " + server.keys.filter { selection.keys[it.name].isNullOrBlank() }.joinToString("、") { it.label } + "，暂未启用"
                        else -> put(id, server.entry)
                    }
                }
                selection.custom.forEachIndexed { i, custom ->
                    val problem = validateCustomUrl(custom.url)
                        ?: "地址和请求头里不能包含 \${...}".takeIf { (listOf(custom.url) + custom.headers.values).any { "\${" in it } }
                    if (problem != null) {
                        warnings += "自定义服务「${custom.title}」未启用：$problem"
                        return@forEachIndexed
                    }
                    put("custom-${i + 1}", buildJsonObject {
                        put("title", custom.title.ifBlank { "自定义服务 ${i + 1}" })
                        put("url", custom.url.trim())
                        if (custom.headers.isNotEmpty()) putJsonObject("headers") { custom.headers.forEach { (k, v) -> put(k, v) } }
                        put("confirm", custom.confirm.name.lowercase())
                    })
                }
            }
        }
        val parsed = McpConfig.parse(json.toString(), selection.keys)
        return McpConfig(parsed.servers, warnings + parsed.warnings)
    }

    companion object {
        private const val RESOURCE = "/dev/phonepal/mcp/catalog.json"

        /** The catalog bundled with this module. */
        val builtIn: McpCatalog by lazy {
            val text = McpCatalog::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("missing $RESOURCE")
            parse(text)
        }

        fun parse(text: String): McpCatalog {
            val root = Json.parseToJsonElement(text) as JsonObject
            val servers = (root["servers"] as JsonArray).map { element ->
                val o = element as JsonObject
                fun s(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
                val id = requireNotNull(s("id")) { "catalog server without id" }
                val keys = (o["keys"] as? JsonArray).orEmpty().map { k ->
                    val ko = k as JsonObject
                    fun ks(key: String) = (ko[key] as? JsonPrimitive)?.contentOrNull
                    CatalogKey(requireNotNull(ks("name")), ks("label") ?: ks("name")!!, ks("help"))
                }
                val entry = JsonObject(o.filterKeys { it in ENTRY_FIELDS })
                val platforms = (o["platforms"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
                    ?: if (entry["url"] != null) ALL_PLATFORMS else setOf("desktop")
                CatalogServer(
                    id = id,
                    title = s("title") ?: id,
                    category = s("category") ?: "其他",
                    description = s("description").orEmpty(),
                    official = (o["official"] as? JsonPrimitive)?.contentOrNull == "true",
                    entry = buildJsonObject {
                        entry.forEach { (k, v) -> put(k, v) }
                        if (!entry.containsKey("title")) put("title", s("title") ?: id)
                    },
                    keys = keys,
                    platforms = platforms,
                )
            }
            require(servers.map { it.id }.toSet().size == servers.size) { "duplicate catalog ids" }
            return McpCatalog(servers)
        }

        /** Null when [url] is acceptable for a hand-added server: absolute HTTPS (HTTP only for localhost). */
        fun validateCustomUrl(url: String): String? {
            val parsed = url.trim().toHttpUrlOrNull() ?: return "地址无效"
            if (parsed.scheme != "https" && parsed.host !in setOf("localhost", "127.0.0.1")) return "只支持 https 地址"
            return null
        }

        private val ENTRY_FIELDS = setOf("title", "url", "headers", "command", "args", "env", "confirm", "tools", "timeoutSeconds")
        private val ALL_PLATFORMS = setOf("desktop", "android", "web")

        /** For callers that build JSON lists of catalog entries (UI, web API). */
        fun describe(server: CatalogServer): JsonObject = buildJsonObject {
            put("id", server.id)
            put("title", server.title)
            put("category", server.category)
            put("description", server.description)
            put("official", server.official)
            put("remote", server.remote)
            putJsonArray("platforms") { server.platforms.sorted().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("keys") {
                server.keys.forEach { k ->
                    add(buildJsonObject { put("name", k.name); put("label", k.label); k.help?.let { put("help", it) } })
                }
            }
        }
    }
}
