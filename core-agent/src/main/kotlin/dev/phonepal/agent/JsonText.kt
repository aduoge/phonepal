package dev.phonepal.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Small helpers for model-produced JSON. */
internal object JsonText {
    val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** The first `{...}` object in [text] (code fences and surrounding prose allowed), or null. */
    fun firstObject(text: String): JsonObject? {
        val raw = OperatorReplyParser.extractFirstObject(OperatorReplyParser.stripFences(text)) ?: return null
        return try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    fun string(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    fun bool(o: JsonObject, key: String): Boolean? {
        val p = o[key] as? JsonPrimitive ?: return null
        return p.booleanOrNull ?: when (p.content.trim().lowercase()) {
            "yes", "1", "是" -> true
            "no", "0", "否" -> false
            else -> null
        }
    }

    fun stringList(o: JsonObject, key: String): List<String> = when (val e = o[key]) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
        is JsonPrimitive -> if (e is JsonNull) emptyList() else e.content.split(',', '，', ' ').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    /** JSON string literal of [s]. */
    fun quote(s: String): String = JsonPrimitive(s).toString()
}
