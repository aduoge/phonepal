package dev.xiaoman.api

/**
 * Tools from outside the phone, for example MCP servers, offered to the chat model next to the built-in
 * memory / reminder / phone_task tools. A provider owns its tool names and must never throw for a
 * failing server: it lists fewer tools or returns an error result instead.
 */
interface ToolProvider {
    /** The tools to offer now. Names follow the OpenAI function-name rules: `[a-zA-Z0-9_-]{1,64}`. */
    suspend fun specs(): List<ToolSpec>

    /** Runs [name] when it belongs to this provider and returns the result text for the model; null when unknown. */
    suspend fun call(taskId: String, name: String, argumentsJson: String): ToolResult?
}

/** [text] goes back to the chat model; [summary] is a short line for the chat UI and traces. */
data class ToolResult(val ok: Boolean, val text: String, val summary: String)

/** Confirmation before an external tool with side effects runs (booking a ride, placing an order ...). */
data class ToolApprovalRequest(
    val taskId: String,
    /** Display name of the server, e.g. `滴滴出行`. */
    val server: String,
    /** Tool name on that server, e.g. `create_order`. */
    val tool: String,
    /** Human-readable title of the tool when the server provides one. */
    val title: String?,
    val argumentsJson: String,
    /** Why confirmation is needed, shown to the user. */
    val reason: String,
)

fun interface ToolApprovalGateway {
    suspend fun approve(request: ToolApprovalRequest): Boolean
}
