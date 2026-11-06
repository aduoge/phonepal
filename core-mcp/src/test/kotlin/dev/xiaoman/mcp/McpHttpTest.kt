package dev.xiaoman.mcp

import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class McpHttpTest {
    private fun rpc(id: JsonElement?, result: String) = """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    private fun serverTest(sse: Boolean) = runBlocking {
        val http = MockWebServer()
        http.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val message = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                if (message.string("method") == "notifications/initialized") return MockResponse().setResponseCode(202)
                val result = when (message.string("method")) {
                    "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"test","version":"1"}}"""
                    "tools/list" -> if (message["params"]?.jsonObject?.string("cursor") == null)
                        """{"tools":[{"name":"first","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}],"nextCursor":"page2"}"""
                    else """{"tools":[{"name":"second","inputSchema":null}]}"""
                    else -> """{"content":[{"type":"text","text":"done"}]}"""
                }
                val json = rpc(message["id"], result)
                val response = MockResponse().setHeader("Mcp-Session-Id", "session-test")
                return if (sse) response.setHeader("Content-Type", "text/event-stream")
                    .setBody(": heartbeat\n\nevent: message\ndata: {\"method\":\"notifications/progress\"}\n\n" +
                        "data: ${rpc(JsonPrimitive(999), "{}")}\n\n" +
                        "event: message\ndata: ${json.substringBefore(",\"result\"")},\n" +
                        "data: \"result\":$result}\n\n")
                else response.setHeader("Content-Type", "application/json").setBody(json)
            }
        }
        http.start()
        val client = McpClient(McpServerConfig("test", url = http.url("/mcp").toString(), headers = mapOf("X-Key" to "test-key")))
        try {
            val tools = client.listTools()
            assertEquals(listOf("first", "second"), tools.map { it.name })
            assertEquals(true, tools[0].readOnlyHint)
            assertEquals(McpClient.EMPTY_SCHEMA, tools[1].inputSchema)
            assertEquals("done", client.callTool("first", JsonObject(emptyMap())).text)
            val requests = (1..5).map { assertNotNull(http.takeRequest(2, TimeUnit.SECONDS)) }
            val initialization = Json.parseToJsonElement(requests[0].body.readUtf8()).jsonObject
            assertEquals("2025-06-18", initialization["params"]?.jsonObject?.string("protocolVersion"))
            assertEquals("xiaoman", initialization["params"]?.jsonObject?.get("clientInfo")?.jsonObject?.string("name"))
            assertNull(requests[0].getHeader("Mcp-Session-Id"))
            assertNull(requests[0].getHeader("MCP-Protocol-Version"))
            assertEquals("notifications/initialized", Json.parseToJsonElement(requests[1].body.readUtf8()).jsonObject.string("method"))
            requests.drop(1).forEach {
                assertEquals("session-test", it.getHeader("Mcp-Session-Id"))
                assertEquals("2025-06-18", it.getHeader("MCP-Protocol-Version"))
                assertEquals("application/json", it.getHeader("Content-Type")?.substringBefore(';'))
                assertEquals("application/json, text/event-stream", it.getHeader("Accept"))
                assertEquals("test-key", it.getHeader("X-Key"))
            }
        } finally { client.close(); http.shutdown() }
    }

    @Test fun jsonLifecycleSessionAndPagination() = serverTest(false)
    @Test fun sseLifecycleSessionAndPagination() = serverTest(true)

    @Test fun httpErrorsAreShortAndRedacted() = runBlocking {
        val http = MockWebServer()
        http.start()
        val url = http.url("/mcp?key=url-secret").toString()
        http.enqueue(MockResponse().setResponseCode(403).setBody("$url header-secret " + "x".repeat(400)))
        val client = McpClient(McpServerConfig("test", url = url, headers = mapOf("X-Key" to "header-secret")))
        try {
            val error = assertFailsWith<McpException> { client.initialize() }
            assertTrue(error.message.orEmpty().contains("403"))
            assertTrue(error.message.orEmpty().length < 260)
            assertFalse(error.toString().contains("url-secret"))
            assertFalse(error.toString().contains("header-secret"))
            assertFalse(error.toString().contains(http.url("/mcp").toString()))
            assertNull(error.cause)
        } finally { client.close(); http.shutdown() }
    }

    @Test fun rpcErrorAndWrongResponseIdFailSafely() = runBlocking {
        val http = MockWebServer()
        http.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-1,"message":"url-secret header-secret"}}"""))
        http.enqueue(MockResponse().setBody(rpc(JsonPrimitive(999), "{}")))
        http.start()
        val config = McpServerConfig("test", url = http.url("/?key=url-secret").toString(), headers = mapOf("X" to "header-secret"))
        try {
            McpClient(config).use { client ->
                val error = assertFailsWith<McpException> { client.initialize() }
                assertTrue(error.message.orEmpty().contains("-1"))
                assertFalse(error.toString().contains("url-secret"))
                assertFalse(error.toString().contains("header-secret"))
            }
            McpClient(config).use { client -> assertFailsWith<McpException> { client.initialize() }; Unit }
        } finally { http.shutdown() }
    }

    @Test fun initializedNotificationCanReturn200() = runBlocking {
        val http = MockWebServer()
        http.enqueue(MockResponse().setBody(rpc(JsonPrimitive(1), "{}")))
        http.enqueue(MockResponse().setResponseCode(200))
        http.start()
        try { McpClient(McpServerConfig("test", url = http.url("/").toString())).use { it.initialize() } }
        finally { http.shutdown() }
    }

    @Test fun httpTimeoutIsEnforced() = runBlocking {
        val http = MockWebServer()
        http.enqueue(MockResponse().setBody(rpc(JsonPrimitive(1), "{}")).setBodyDelay(2, TimeUnit.SECONDS))
        http.start()
        try {
            McpClient(McpServerConfig("test", url = http.url("/?key=url-secret").toString(), timeoutSeconds = 1)).use {
                val error = assertFailsWith<McpException> { it.initialize() }
                assertFalse(error.toString().contains("url-secret"))
            }
        } finally { http.shutdown() }
    }
}
