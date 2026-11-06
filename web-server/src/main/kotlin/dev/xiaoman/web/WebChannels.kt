package dev.xiaoman.web

import dev.xiaoman.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal fun event(type: String, block: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("type", type); block() }

/** Pending answers belong to one session and one chat stream. */
class WebChannels(private val timeoutMillis: Long = 180_000) : UserChannel, ToolApprovalGateway {
    private data class Pending(val approval: Boolean, val result: CompletableDeferred<JsonObject>)
    private val pending = ConcurrentHashMap<String, Pending>()
    @Volatile private var output: ((JsonObject) -> Unit)? = null
    fun attach(emit: (JsonObject) -> Unit) { output = emit }
    fun emit(value: JsonObject) { output?.invoke(value) }
    private suspend fun waitFor(approval: Boolean, make: (String) -> JsonObject): JsonObject? {
        val id = UUID.randomUUID().toString()
        val p = Pending(approval, CompletableDeferred())
        pending[id] = p
        try {
            val sink = output ?: return null
            sink(make(id))
            return withTimeoutOrNull(timeoutMillis) { p.result.await() }
        } finally { pending.remove(id) }
    }
    override suspend fun ask(taskId: String, question: String, takeover: Boolean): String =
        waitFor(false) { id -> event("question") { put("id", id); put("question", question) } }
            ?.get("text")?.jsonPrimitive?.contentOrNull ?: "用户没有回答"
    override suspend fun approve(request: ToolApprovalRequest): Boolean =
        waitFor(true) { id -> event("approval") {
            put("id", id); put("title", request.title ?: request.tool); put("server", request.server)
            put("tool", request.tool); put("arguments", request.argumentsJson); put("reason", request.reason)
        } }?.get("approved")?.jsonPrimitive?.booleanOrNull == true
    fun answer(body: JsonObject): Boolean {
        val id = (body["id"] as? JsonPrimitive)?.contentOrNull ?: return false
        val p = pending[id] ?: return false
        if (p.approval && (body["approved"] as? JsonPrimitive)?.booleanOrNull == null) return false
        if (!p.approval && ((body["text"] as? JsonPrimitive)?.isString != true)) return false
        return p.result.complete(body)
    }
    fun detach() {
        output = null
        pending.values.forEach { it.result.complete(buildJsonObject { put("approved", false) }) }
        pending.clear()
    }
    val pendingCount: Int get() = pending.size
}
