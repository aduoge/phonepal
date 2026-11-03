package dev.phonepal.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.phonepal.mcp.ConfirmPolicy
import dev.phonepal.mcp.CustomMcpServer
import dev.phonepal.mcp.McpSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The ticked MCP services ([McpSelection]). Ids, titles, URLs and confirm policies go to [plain]; keys
 * and custom request headers only to [secret] (the encrypted preferences shared with [SettingsStore]).
 */
class McpSettingsStore(private val plain: SharedPreferences, private val secret: SharedPreferences) {
    private val _selection = MutableStateFlow(load())
    val selection: StateFlow<McpSelection> = _selection.asStateFlow()

    val current: McpSelection get() = _selection.value

    @Synchronized
    fun save(selection: McpSelection) {
        plain.edit(commit = true) {
            putStringSet(ENABLED, selection.enabled)
            putString(CUSTOM, buildJsonArray {
                selection.custom.forEach { c ->
                    add(buildJsonObject { put("title", c.title); put("url", c.url); put("confirm", c.confirm.name) })
                }
            }.toString())
        }
        secret.edit(commit = true) {
            putString(KEYS, JsonObject(selection.keys.filterValues { it.isNotBlank() }.mapValues { JsonPrimitive(it.value) }).toString())
            putString(HEADERS, buildJsonArray {
                selection.custom.forEach { c -> add(JsonObject(c.headers.mapValues { JsonPrimitive(it.value) })) }
            }.toString())
        }
        _selection.value = selection
    }

    private fun load(): McpSelection {
        val custom = parseArray(plain.getString(CUSTOM, null))
        val headers = parseArray(secret.getString(HEADERS, null))
        return McpSelection(
            enabled = plain.getStringSet(ENABLED, emptySet()).orEmpty().toSet(),
            keys = parseObject(secret.getString(KEYS, null)),
            custom = custom.mapIndexedNotNull { i, element ->
                val o = element as? JsonObject ?: return@mapIndexedNotNull null
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                CustomMcpServer(
                    title = s("title").orEmpty(),
                    url = s("url") ?: return@mapIndexedNotNull null,
                    headers = (headers.getOrNull(i) as? JsonObject)?.let { h -> h.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() } }.orEmpty(),
                    confirm = s("confirm")?.let { runCatching { ConfirmPolicy.valueOf(it) }.getOrNull() } ?: ConfirmPolicy.WRITES,
                )
            },
        )
    }

    private fun parseArray(text: String?): List<Any?> =
        text?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }.orEmpty()

    private fun parseObject(text: String?): Map<String, String> =
        text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()

    companion object {
        private const val PLAIN_FILE = "mcp"
        private const val ENABLED = "mcp.enabled"
        private const val CUSTOM = "mcp.custom"
        private const val KEYS = "mcp.keys"
        private const val HEADERS = "mcp.customHeaders"

        fun create(context: Context, secret: SharedPreferences): McpSettingsStore =
            McpSettingsStore(context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE), secret)
    }
}
