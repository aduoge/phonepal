package dev.xiaoman.llm

import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ChatRole
import dev.xiaoman.api.ContentPart
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.api.ModelRole
import dev.xiaoman.api.ToolCall
import dev.xiaoman.api.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiCompatibleClientTest {
    private val apiKey = "sk-secret-123456"
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Long>()

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun config(
        temperature: Double? = null,
        maxTokens: Int? = null,
        timeoutSeconds: Long = 10,
    ) = ModelConfig(
        role = ModelRole.CHAT,
        baseUrl = server.url("/api/v3/").toString(),
        model = "test-model",
        apiKey = apiKey,
        temperature = temperature,
        maxTokens = maxTokens,
        timeoutSeconds = timeoutSeconds,
        extraHeaders = mapOf("X-Extra" to "yes"),
    )

    private fun client(config: ModelConfig = config(), maxRetries: Int = 2) =
        OpenAiCompatibleClient(config, maxRetries = maxRetries, baseDelayMillis = 100, sleep = { sleeps += it })

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private val simpleReply = """
        {"id":"x","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},
        "finish_reason":"stop","logprobs":null}],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4},"extra":1}
    """.trimIndent()

    private fun takeBody(): JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test
    fun `request body includes image, tools, json mode and sampling params`() = runTest {
        server.enqueue(ok(simpleReply))
        val png = byteArrayOf(1, 2, 3, 4)
        val params = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("city") { put("type", "string") } }
        }
        val request = ChatRequest(
            messages = listOf(
                ChatMessage.system("be brief"),
                ChatMessage(ChatRole.USER, listOf(ContentPart.Text("what is this?"), ContentPart.ImagePng(png))),
                ChatMessage(ChatRole.ASSISTANT, emptyList(), toolCalls = listOf(ToolCall("call_1", "weather", """{"city":"北京"}"""))),
                ChatMessage.tool("call_1", "sunny"),
            ),
            tools = listOf(ToolSpec("weather", "Get the weather", params)),
            jsonMode = true,
        )

        val response = client(config(temperature = 0.2, maxTokens = 256)).complete(request)
        assertEquals("hi", response.text)
        assertEquals("stop", response.finishReason)
        assertEquals(3, response.usage?.promptTokens)
        assertEquals(1, response.usage?.completionTokens)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v3/chat/completions", recorded.path)
        assertEquals("Bearer $apiKey", recorded.getHeader("Authorization"))
        assertEquals("yes", recorded.getHeader("X-Extra"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        assertEquals(0.2, body["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals(256, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("be brief", messages[0].jsonObject["content"]!!.jsonPrimitive.content)

        val userContent = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("text", userContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("what is this?", userContent[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", userContent[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/png;base64," + Base64.getEncoder().encodeToString(png),
            userContent[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )

        val assistant = messages[2].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, assistant["content"])
        val call = assistant["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("function", call["type"]!!.jsonPrimitive.content)
        assertEquals("weather", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("""{"city":"北京"}""", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)

        val tool = messages[3].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("sunny", tool["content"]!!.jsonPrimitive.content)

        val toolSpec = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", toolSpec["type"]!!.jsonPrimitive.content)
        assertEquals("weather", toolSpec["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Get the weather", toolSpec["function"]!!.jsonObject["description"]!!.jsonPrimitive.content)
        assertEquals(params, toolSpec["function"]!!.jsonObject["parameters"])
    }

    @Test
    fun `text-only request uses plain string content and omits optional fields`() = runTest {
        server.enqueue(ok(simpleReply))
        client().complete(ChatRequest(listOf(ChatMessage.user("hello"))))
        val body = takeBody()
        assertEquals("hello", body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse("tools" in body)
        assertFalse("response_format" in body)
        assertFalse("temperature" in body)
        assertFalse("max_tokens" in body)
    }

    @Test
    fun `parses tool calls and null content`() = runTest {
        server.enqueue(
            ok(
                """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_a","type":"function","function":{"name":"open_app","arguments":"{\"pkg\":\"com.x\"}"}},
                  {"id":"call_b","type":"function","function":{"name":"noop","arguments":{"k":1}}}
                ]},"finish_reason":"tool_calls"}]}
                """.trimIndent(),
            ),
        )
        val response = client().complete(ChatRequest(listOf(ChatMessage.user("go"))))
        assertEquals("", response.text)
        assertEquals("tool_calls", response.finishReason)
        assertNull(response.usage)
        assertEquals(
            listOf(ToolCall("call_a", "open_app", """{"pkg":"com.x"}"""), ToolCall("call_b", "noop", """{"k":1}""")),
            response.toolCalls,
        )
    }

    @Test
    fun `parses array content of text parts`() = runTest {
        server.enqueue(ok("""{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}"""))
        assertEquals("ab", client().complete(ChatRequest(listOf(ChatMessage.user("x")))).text)
    }

    @Test
    fun `retries on 429 and 500 with backoff and Retry-After`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3").setBody("""{"error":{"message":"slow down"}}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        server.enqueue(ok(simpleReply))
        val response = client().complete(ChatRequest(listOf(ChatMessage.user("x"))))
        assertEquals("hi", response.text)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(3000L, 200L), sleeps)
    }

    @Test
    fun `gives up after maxRetries and reports status`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"overloaded"}}""")) }
        val e = assertFailsWith<LlmException> { client().complete(ChatRequest(listOf(ChatMessage.user("x")))) }
        assertEquals(503, e.statusCode)
        assertTrue(e.retryable)
        assertTrue("overloaded" in e.message!!)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(100L, 200L), sleeps)
    }

    @Test
    fun `retries transport errors`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(ok(simpleReply))
        assertEquals("hi", client().complete(ChatRequest(listOf(ChatMessage.user("x")))).text)
        assertEquals(1, sleeps.size)
    }

    @Test
    fun `configured inference limit stops immediately without backoff`() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(429)
                .setBody("""{"error":{"code":"SetLimitExceeded","message":"inference limit reached"}}"""))
        }
        val e = assertFailsWith<LlmException> {
            client().complete(ChatRequest(listOf(ChatMessage.user("x"))))
        }
        assertEquals("SetLimitExceeded", e.providerCode)
        assertFalse(e.retryable)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `keeps structured quota error code without exposing credentials`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"QuotaExceeded.FreeTier","message":"quota exceeded for $apiKey"}}"""))
        val e = assertFailsWith<LlmException> {
            client(maxRetries = 0).complete(ChatRequest(listOf(ChatMessage.user("x"))))
        }
        assertEquals(429, e.statusCode)
        assertEquals("QuotaExceeded.FreeTier", e.providerCode)
        assertFalse(apiKey in e.message.orEmpty())
    }

    @Test
    fun `ignores a provider code containing the API key`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"$apiKey","message":"failed"}}"""))
        val e = assertFailsWith<LlmException> {
            client(maxRetries = 0).complete(ChatRequest(listOf(ChatMessage.user("x"))))
        }
        assertNull(e.providerCode)
    }

    @Test
    fun `does not retry on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid key"}}"""))
        val e = assertFailsWith<LlmException> { client().complete(ChatRequest(listOf(ChatMessage.user("x")))) }
        assertEquals(401, e.statusCode)
        assertFalse(e.retryable)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `falls back without response_format when endpoint rejects it`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"response_format is not supported by this model"}}"""),
        )
        server.enqueue(ok(simpleReply))
        server.enqueue(ok(simpleReply))
        val client = client()
        val request = ChatRequest(listOf(ChatMessage.user("x")), jsonMode = true)
        assertEquals("hi", client.complete(request).text)
        assertTrue("response_format" in takeBody())
        assertFalse("response_format" in takeBody())
        // Remembered for later calls on the same client.
        client.complete(request)
        assertFalse("response_format" in takeBody())
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `other 400 errors are not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"bad messages"}}"""))
        val e = assertFailsWith<LlmException> {
            client().complete(ChatRequest(listOf(ChatMessage.user("x")), jsonMode = true))
        }
        assertEquals(400, e.statusCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `api key echoed by server is redacted`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"key $apiKey is not allowed"}}"""))
        val e = assertFailsWith<LlmException> { client().complete(ChatRequest(listOf(ChatMessage.user("x")))) }
        assertFalse(apiKey in e.message!!)
        assertTrue("***" in e.message!!)
        assertFalse(apiKey in e.toString())
    }

    @Test
    fun `non-json error body is redacted`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Bearer $apiKey rejected"))
        val e = assertFailsWith<LlmException> { client().complete(ChatRequest(listOf(ChatMessage.user("x")))) }
        assertFalse(apiKey in e.message!!)
    }

    @Test
    fun `cancellation cancels the in-flight call`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OpenAiCompatibleClient(config(timeoutSeconds = 60), maxRetries = 0)
        val started = System.nanoTime()
        val job = async(Dispatchers.Default) { client.complete(ChatRequest(listOf(ChatMessage.user("x")))) }
        server.takeRequest(5, TimeUnit.SECONDS)
        delay(100)
        job.cancel()
        withTimeout(5_000) {
            assertFailsWith<CancellationException> { job.await() }
        }
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10)
    }

    @Test
    fun `factory creates clients for a config`() {
        val config = config()
        val client = LlmClients.factory().create(config)
        assertTrue(client is OpenAiCompatibleClient)
        assertEquals(config, client.config)
        assertTrue(LlmClients.default.create(config).config === config)
    }

    @Test
    fun `missing choices is an error`() = runTest {
        server.enqueue(ok("""{"object":"chat.completion","choices":[]}"""))
        val e = assertFailsWith<LlmException> { client().complete(ChatRequest(listOf(ChatMessage.user("x")))) }
        assertEquals(200, e.statusCode)
    }
}
