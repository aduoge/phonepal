package dev.xiaoman.agent

import dev.xiaoman.api.Action
import dev.xiaoman.api.Point
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong

/** Result of [OperatorReplyParser.parse]. */
sealed class ParsedReply {
    data class Ok(val action: Action, val thought: String?) : ParsedReply()
    data class Error(val message: String) : ParsedReply()
}

/**
 * Tolerant parser for operator model replies: strips code fences, takes the first balanced `{...}`,
 * accepts `{"action": {...}}` or a bare action, common type synonyms, field aliases, and points as
 * `[x, y]`, `{x, y}` or `"x,y"` (clamped to 0..1000).
 */
object OperatorReplyParser {
    private val json = Json { isLenient = true }

    private val typeSynonyms: Map<String, String> = buildMap {
        listOf("tap", "click", "press", "touch", "tap_node", "click_node").forEach { put(it, "tap") }
        listOf("long_press", "longpress", "long_click", "longclick", "hold").forEach { put(it, "long_press") }
        listOf("swipe", "scroll", "drag", "fling").forEach { put(it, "swipe") }
        listOf("type", "input", "input_text", "type_text", "text", "enter_text", "set_text").forEach { put(it, "type") }
        listOf("back", "go_back", "press_back", "navigate_back").forEach { put(it, "back") }
        listOf("home", "go_home", "press_home").forEach { put(it, "home") }
        listOf("launch", "launch_app", "open_app", "start_app", "open").forEach { put(it, "launch") }
        listOf("deeplink", "deep_link", "open_deeplink", "open_url", "open_uri", "url").forEach { put(it, "deeplink") }
        listOf("wait", "sleep", "pause").forEach { put(it, "wait") }
        listOf("ask_user", "ask", "ask_human", "question", "takeover", "take_over", "handover").forEach { put(it, "ask_user") }
        listOf("finish", "done", "complete", "completed", "stop", "end", "fail", "failed").forEach { put(it, "finish") }
    }

    fun parse(reply: String): ParsedReply {
        val raw = extractFirstObject(stripFences(reply)) ?: return ParsedReply.Error("回复中没有 JSON 对象")
        val root = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return ParsedReply.Error("JSON 格式错误")
        val thought = root.str("thought", "thinking", "reasoning")
        val nested = root["action"]
        val actionObj: JsonObject = when {
            nested is JsonObject -> nested
            // {"action": "tap", "nodeId": 3}
            nested is JsonPrimitive && nested.isString -> JsonObject(root + ("type" to nested))
            else -> root
        }
        return try {
            ParsedReply.Ok(toAction(actionObj), thought)
        } catch (e: IllegalArgumentException) {
            ParsedReply.Error(e.message ?: "无法识别的操作")
        }
    }

    internal fun stripFences(text: String): String =
        text.replace(Regex("```[a-zA-Z]*"), "").trim()

    /** The first balanced JSON object in [text], honoring strings and escapes. */
    internal fun extractFirstObject(text: String): String? {
        var start = text.indexOf('{')
        while (start >= 0) {
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until text.length) {
                val c = text[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return text.substring(start, i + 1)
                }
            }
            start = text.indexOf('{', start + 1)
        }
        return null
    }

    private fun toAction(o: JsonObject): Action {
        val rawType = o.str("type", "action_type", "actionType", "name", "op")
            ?: throw IllegalArgumentException("缺少 action.type")
        val norm = rawType.trim().lowercase().replace('-', '_').replace(' ', '_')
        val type = typeSynonyms[norm] ?: throw IllegalArgumentException("未知的操作类型 \"$rawType\"")
        val target = o.str("target", "element", "label")
        val reason = o.str("reason", "why")
        return when (type) {
            "tap" -> {
                val nodeId = o.nodeId()
                val point = o.point()
                require(nodeId != null || point != null) { "tap 需要 nodeId 或 point" }
                Action.Tap(point = point, nodeId = nodeId, target = target, reason = reason)
            }
            "long_press" -> {
                val nodeId = o.nodeId()
                val point = o.point()
                require(nodeId != null || point != null) { "long_press 需要 nodeId 或 point" }
                Action.LongPress(point, nodeId, o.long("durationMs", "duration_ms", "duration") ?: 800, target, reason)
            }
            "swipe" -> swipe(o, target, reason)
            "type" -> {
                val text = o.str("text", "content", "value", "input") ?: throw IllegalArgumentException("type 需要 text")
                Action.TypeText(text, o.nodeId(), o.bool("submit", "enter") ?: false, target, reason)
            }
            "back" -> Action.Back(target, reason)
            "home" -> Action.Home(target, reason)
            "launch" -> {
                val pkg = o.str("packageName", "package_name", "package", "app", "appPackage")
                    ?: throw IllegalArgumentException("launch 需要 packageName")
                Action.LaunchApp(pkg, target, reason)
            }
            "deeplink" -> {
                val uri = o.str("uri", "url", "link", "deeplink") ?: throw IllegalArgumentException("deeplink 需要 uri")
                Action.OpenDeeplink(uri, o.str("packageName", "package_name", "package"), target, reason)
            }
            "wait" -> {
                val millis = o.long("millis", "ms", "durationMs", "duration_ms", "duration")
                    ?: o.double("seconds", "secs")?.let { (it * 1000).roundToLong() }
                    ?: 1000
                Action.Wait(millis.coerceIn(0, 60_000), target, reason)
            }
            "ask_user" -> {
                val question = o.str("question", "text", "message", "prompt") ?: throw IllegalArgumentException("ask_user 需要 question")
                Action.AskUser(question, o.bool("takeover", "take_over") ?: norm.startsWith("take") || norm == "handover", target, reason)
            }
            "finish" -> {
                val status = o.str("status")?.lowercase()
                val success = o.bool("success", "ok") ?: when {
                    status != null -> status in setOf("success", "succeeded", "ok", "done", "completed")
                    norm.startsWith("fail") -> false
                    else -> true
                }
                val summary = o.str("summary", "message", "result", "text", "answer") ?: if (success) "完成" else "未完成"
                Action.Finish(success, summary, target, reason)
            }
            else -> throw IllegalArgumentException("未知的操作类型 \"$rawType\"")
        }
    }

    private fun swipe(o: JsonObject, target: String?, reason: String?): Action.Swipe {
        val duration = o.long("durationMs", "duration_ms", "duration") ?: 300
        val from = o.pointAt("from", "start", "from_point")
        val to = o.pointAt("to", "end", "to_point")
        if (from != null && to != null) return Action.Swipe(from, to, duration, target, reason)
        // {"type": "scroll", "direction": "up"}: finger direction.
        val (f, t) = when (o.str("direction", "dir")?.lowercase()) {
            "up" -> Point(500, 750) to Point(500, 250)
            "down" -> Point(500, 250) to Point(500, 750)
            "left" -> Point(800, 500) to Point(200, 500)
            "right" -> Point(200, 500) to Point(800, 500)
            else -> throw IllegalArgumentException("swipe 需要 from/to 或 direction")
        }
        return Action.Swipe(f, t, duration, target, reason)
    }

    // --- field helpers ---

    private fun JsonObject.first(vararg keys: String): JsonElement? =
        keys.firstNotNullOfOrNull { k -> this[k]?.takeIf { it !is JsonNull } }

    private fun JsonObject.str(vararg keys: String): String? =
        (first(*keys) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(vararg keys: String): Boolean? {
        val p = first(*keys) as? JsonPrimitive ?: return null
        return p.booleanOrNull ?: when (p.content.lowercase()) {
            "yes", "1" -> true
            "no", "0" -> false
            else -> null
        }
    }

    private fun JsonObject.double(vararg keys: String): Double? {
        val p = first(*keys) as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.content.trim().toDoubleOrNull()
    }

    private fun JsonObject.long(vararg keys: String): Long? {
        val p = first(*keys) as? JsonPrimitive ?: return null
        return p.longOrNull ?: double(*keys)?.roundToLong()
    }

    private fun JsonObject.nodeId(): Int? {
        val p = first("nodeId", "node_id", "nodeID", "id", "node", "element_id", "elementId", "index") as? JsonPrimitive ?: return null
        val s = p.content.trim().removePrefix("[").removeSuffix("]")
        return s.toIntOrNull() ?: s.toDoubleOrNull()?.toInt()
    }

    /** The tap point: `point` (and aliases), or `x`/`y` directly on the action. */
    private fun JsonObject.point(): Point? {
        pointAt("point", "coordinate", "coordinates", "position", "pos", "xy")?.let { return it }
        val x = double("x")
        val y = double("y")
        return if (x != null && y != null) clampPoint(x, y) else null
    }

    private fun JsonObject.pointAt(vararg keys: String): Point? = first(*keys)?.let { toPoint(it) }

    private fun toPoint(e: JsonElement): Point? = when (e) {
        is JsonArray -> if (e.size >= 2) {
            val x = (e[0] as? JsonPrimitive)?.content?.toDoubleOrNull()
            val y = (e[1] as? JsonPrimitive)?.content?.toDoubleOrNull()
            if (x != null && y != null) clampPoint(x, y) else null
        } else null
        is JsonObject -> {
            val x = e.double("x")
            val y = e.double("y")
            if (x != null && y != null) clampPoint(x, y) else null
        }
        is JsonPrimitive -> {
            val nums = Regex("-?\\d+(\\.\\d+)?").findAll(e.content).map { it.value.toDouble() }.toList()
            if (nums.size >= 2) clampPoint(nums[0], nums[1]) else null
        }
    }

    private fun clampPoint(x: Double, y: Double) =
        Point(x.roundToLong().coerceIn(0, Point.NORM.toLong()).toInt(), y.roundToLong().coerceIn(0, Point.NORM.toLong()).toInt())
}
