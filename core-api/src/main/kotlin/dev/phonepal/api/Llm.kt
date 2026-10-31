package dev.phonepal.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Which job a model is configured for. The chat model talks; the operator model reads screens. */
@Serializable
enum class ModelRole { CHAT, OPERATOR }

/**
 * An OpenAI-compatible endpoint, e.g. Volcano Ark `https://ark.cn-beijing.volces.com/api/v3`,
 * Alibaba Bailian, OpenRouter, or a local vLLM/Ollama server.
 */
@Serializable
data class ModelConfig(
    val role: ModelRole,
    val baseUrl: String,
    val model: String,
    /** Never logged or written to traces. */
    val apiKey: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val timeoutSeconds: Long = 120,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    override fun toString() = "ModelConfig(role=$role, baseUrl=$baseUrl, model=$model, apiKey=***)"
}

@Serializable
enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

sealed class ContentPart {
    data class Text(val text: String) : ContentPart()
    data class ImagePng(val bytes: ByteArray) : ContentPart()
}

data class ChatMessage(
    val role: ChatRole,
    val parts: List<ContentPart>,
    /** Set on ASSISTANT messages that requested tools. */
    val toolCalls: List<ToolCall> = emptyList(),
    /** Set on TOOL messages: the id of the call this message answers. */
    val toolCallId: String? = null,
) {
    val text: String get() = parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    companion object {
        fun system(text: String) = ChatMessage(ChatRole.SYSTEM, listOf(ContentPart.Text(text)))
        fun user(text: String) = ChatMessage(ChatRole.USER, listOf(ContentPart.Text(text)))
        fun assistant(text: String) = ChatMessage(ChatRole.ASSISTANT, listOf(ContentPart.Text(text)))
        fun tool(callId: String, text: String) = ChatMessage(ChatRole.TOOL, listOf(ContentPart.Text(text)), toolCallId = callId)
    }
}

/** A function the chat model may call. [parameters] is a JSON Schema object. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

data class ChatRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    /** Ask for a JSON object reply (`response_format: json_object`) when the endpoint supports it. */
    val jsonMode: Boolean = false,
)

data class Usage(val promptTokens: Int, val completionTokens: Int)

data class ChatResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val finishReason: String? = null,
    val usage: Usage? = null,
)

class LlmException(message: String, val statusCode: Int? = null, val retryable: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)

interface LlmClient {
    val config: ModelConfig

    /** @throws LlmException on transport or API errors, after the client's own retries. */
    suspend fun complete(request: ChatRequest): ChatResponse
}
