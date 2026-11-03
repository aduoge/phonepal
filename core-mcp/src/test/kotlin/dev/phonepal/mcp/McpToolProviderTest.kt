package dev.phonepal.mcp

import dev.phonepal.api.*
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class McpToolProviderTest {
    private class FakeTransport(
        val tools: JsonArray,
        var result: JsonObject = buildJsonObject { putJsonArray("content") { addJsonObject { put("type", "text"); put("text", "done") } } },
    ) : McpTransport {
        val messages = mutableListOf<JsonObject>()
        var failure: String? = null
        var closed = false
        override suspend fun exchange(message: JsonObject): JsonObject? {
            messages += message
            if (message.string("method") == "tools/call") failure?.let { throw IOException(it) }
            val id = message["id"] ?: return null
            val response = when (message.string("method")) {
                "tools/list" -> buildJsonObject { put("tools", tools) }
                "tools/call" -> result
                else -> buildJsonObject { }
            }
            return buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", response) }
        }
        val callCount get() = messages.count { it.string("method") == "tools/call" }
        override fun close() { closed = true }
    }

    private fun tool(name: String = "weather", readOnly: Boolean? = null, title: String? = null, description: String? = null) = buildJsonObject {
        put("name", name)
        title?.let { put("title", it) }
        description?.let { put("description", it) }
        put("inputSchema", McpClient.EMPTY_SCHEMA)
        readOnly?.let { putJsonObject("annotations") { put("readOnlyHint", it) } }
    }

    @Test fun namesAreSanitizedBoundedUniqueAndStable() = runBlocking {
        val fake = FakeTransport(JsonArray(listOf(tool("normal"), tool("a.b"), tool("a/b"), tool("x".repeat(100)), tool("重复", title = "查询", description = "天气"))))
        val provider = McpToolProvider(McpConfig(listOf(McpServerConfig("maps", title = "地图"))), ToolApprovalGateway { true }, { fake })
        try {
            assertTrue(fake.messages.isEmpty())
            val specs = provider.specs()
            assertEquals("maps__normal", specs[0].name)
            assertEquals("maps__a_b", specs[1].name)
            assertEquals("maps__a_b_2", specs[2].name)
            assertEquals(64, specs[3].name.length)
            assertEquals(5, specs.map { it.name }.toSet().size)
            assertTrue(specs.all { it.name.matches(Regex("[a-zA-Z0-9_-]{1,64}")) })
            assertTrue(specs.last().description.startsWith("[服务器 地图] 查询：天气"))
            assertEquals(specs, provider.specs())
            assertEquals(1, fake.messages.count { it.string("method") == "initialize" })
            assertNull(provider.call("task", "unknown", "{}"))
        } finally { provider.close() }
        assertTrue(fake.closed)
    }

    @Test fun writesApprovesOnlyNonReadOnlyTools() = runBlocking {
        val fake = FakeTransport(JsonArray(listOf(tool("read", true), tool("write", false), tool("unspecified"))))
        val approvals = mutableListOf<ToolApprovalRequest>()
        val provider = McpToolProvider(McpConfig(listOf(McpServerConfig("maps"))), ToolApprovalGateway { approvals += it; true }, { fake })
        try {
            provider.specs()
            assertTrue(assertNotNull(provider.call("task", "maps__read", "")).ok)
            assertTrue(assertNotNull(provider.call("task", "maps__write", "{}" )).ok)
            assertTrue(assertNotNull(provider.call("task", "maps__unspecified", "{}" )).ok)
            assertEquals(listOf("write", "unspecified"), approvals.map { it.tool })
            assertEquals("task", approvals.first().taskId)
            assertTrue(approvals.first().reason.contains("修改外部数据"))
            assertEquals(3, fake.callCount)
            assertEquals(JsonObject(emptyMap()), fake.messages.last()["params"]?.jsonObject?.get("arguments"))
        } finally { provider.close() }
    }

    @Test fun allConfirmsEvenReadOnlyAndNoneNeverConfirms() = runBlocking {
        listOf(ConfirmPolicy.ALL to 1, ConfirmPolicy.NONE to 0).forEach { (policy, expected) ->
            var approvals = 0
            val fake = FakeTransport(JsonArray(listOf(tool(readOnly = true))))
            McpToolProvider(McpConfig(listOf(McpServerConfig("maps", confirm = policy))), ToolApprovalGateway { approvals++; true }, { fake }).use {
                it.specs()
                assertTrue(assertNotNull(it.call("task", "maps__weather", "{}")).ok)
                assertEquals(expected, approvals)
                assertEquals(1, fake.callCount)
            }
        }
    }

    @Test fun rejectionDoesNotCallServer() = runBlocking {
        val fake = FakeTransport(JsonArray(listOf(tool())))
        McpToolProvider(McpConfig(listOf(McpServerConfig("maps"))), ToolApprovalGateway { false }, { fake }).use {
            it.specs()
            val result = assertNotNull(it.call("task", "maps__weather", "{}"))
            assertFalse(result.ok)
            assertTrue(result.text.contains("用户没有确认"))
            assertEquals(0, fake.callCount)
        }
    }

    @Test fun errorResultsAndTransportExceptionsReturnFalse() = runBlocking {
        val fake = FakeTransport(JsonArray(listOf(tool())), buildJsonObject {
            put("isError", true)
            putJsonArray("content") { addJsonObject { put("type", "text"); put("text", "server error") } }
        })
        McpToolProvider(McpConfig(listOf(McpServerConfig("maps", confirm = ConfirmPolicy.NONE))), ToolApprovalGateway { true }, { fake }).use {
            it.specs()
            assertFalse(assertNotNull(it.call("task", "maps__weather", "{}")).ok)
            fake.failure = "connection failed"
            val result = assertNotNull(it.call("task", "maps__weather", "{}"))
            assertFalse(result.ok)
            assertTrue(result.text.contains("connection failed"))
        }
    }

    @Test fun badArgumentsAndApprovalFailureReturnErrorsWithoutCall() = runBlocking {
        val fake = FakeTransport(JsonArray(listOf(tool())))
        McpToolProvider(McpConfig(listOf(McpServerConfig("maps"))), ToolApprovalGateway { throw IOException("approval unavailable") }, { fake }).use {
            it.specs()
            listOf("[]", "not-json", "{}").forEach { arguments ->
                assertFalse(assertNotNull(it.call("task", "maps__weather", arguments)).ok)
            }
            assertEquals(0, fake.callCount)
        }
    }

    @Test fun longResultsAreTruncatedAfterRedaction() = runBlocking {
        val fake = FakeTransport(JsonArray(listOf(tool())), buildJsonObject {
            putJsonArray("content") { addJsonObject { put("type", "text"); put("text", "url-secret" + "x".repeat(9000)) } }
        })
        McpToolProvider(McpConfig(listOf(McpServerConfig("maps", url = "https://example.test?key=url-secret", confirm = ConfirmPolicy.NONE))),
            ToolApprovalGateway { true }, { fake }, maxResultChars = 100).use {
            it.specs()
            val result = assertNotNull(it.call("task", "maps__weather", "{}"))
            assertEquals(100, result.text.length)
            assertTrue(result.text.endsWith("[结果已截断]"))
            assertFalse(result.text.contains("url-secret"))
        }
    }

    @Test fun everyModelFieldApprovalAndStatusRedactsAllSecrets() = runBlocking {
        val secretText = "url+secret url%2Bsecret header-secret token-secret env-secret"
        val config = McpServerConfig("maps", title = secretText, url = "https://example.test?key=url%2Bsecret",
            headers = mapOf("X-Key" to "header-secret", "Authorization" to "Bearer token-secret"), env = mapOf("KEY" to "env-secret"))
        val rawTool = tool(title = secretText, description = secretText).toMutableMap().apply {
            put("inputSchema", buildJsonObject { put("type", "object"); put("description", secretText) })
        }
        val fake = FakeTransport(JsonArray(listOf(JsonObject(rawTool))), buildJsonObject {
            putJsonArray("content") { addJsonObject { put("type", "text"); put("text", secretText) } }
        })
        val requests = mutableListOf<ToolApprovalRequest>()
        McpToolProvider(McpConfig(listOf(config)), ToolApprovalGateway { requests += it; true }, { fake }).use {
            val specs = it.specs()
            val success = assertNotNull(it.call("task", specs.single().name, buildJsonObject { put("value", secretText) }.toString()))
            fake.failure = secretText
            val failure = assertNotNull(it.call("task", specs.single().name, "{}"))
            val visible = listOf(specs, success, failure, requests, it.status(), config).joinToString()
            secretText.split(' ').forEach { secret -> assertFalse(visible.contains(secret), visible) }
            assertTrue(success.ok)
            assertFalse(failure.ok)
        }
    }

    @Test fun serverFailuresAreIsolatedAndRetriedWithStableNames() = runBlocking {
        val good = FakeTransport(JsonArray(listOf(tool("a/b"))))
        val bad = FakeTransport(JsonArray(listOf(tool("a.b"))))
        var attempts = 0
        val provider = McpToolProvider(McpConfig(listOf(
            McpServerConfig("good", confirm = ConfirmPolicy.NONE),
            McpServerConfig("bad", url = "https://example.test?key=secret-key", headers = mapOf("X" to "header-secret")),
            McpServerConfig("disabled", enabled = false),
        )), ToolApprovalGateway { true }, { server ->
            if (server.name == "good") good else {
                attempts++
                if (attempts <= 2) throw IOException("offline secret-key header-secret")
                bad
            }
        }, retryAfterMillis = 0)
        try {
            val initial = provider.specs().single()
            assertTrue(assertNotNull(provider.call("task", initial.name, "{}")).ok)
            val statuses = provider.status()
            assertTrue(statuses[0].connected)
            assertEquals(1, statuses[0].toolCount)
            assertFalse(statuses[1].connected)
            assertTrue(statuses[1].error.orEmpty().contains("offline"))
            assertFalse(statuses.toString().contains("secret-key"))
            assertFalse(statuses.toString().contains("header-secret"))
            assertFalse(statuses[2].connected)
            val recovered = provider.specs()
            assertEquals(2, recovered.size)
            assertEquals(initial, recovered.first())
            assertEquals(3, attempts)
            assertEquals(1, good.messages.count { it.string("method") == "initialize" })
        } finally { provider.close() }
    }

    @Test fun whitelistAndMissingSchemaAreHandled() = runBlocking {
        val fake = FakeTransport(JsonArray(listOf(buildJsonObject { put("name", "keep"); put("inputSchema", "invalid") }, tool("skip"))))
        McpToolProvider(McpConfig(listOf(McpServerConfig("maps", tools = setOf("keep")))), ToolApprovalGateway { true }, { fake }).use {
            val spec = it.specs().single()
            assertEquals("maps__keep", spec.name)
            assertEquals(McpClient.EMPTY_SCHEMA, spec.parameters)
        }
    }

    @Test fun structuredAndNonTextResultsAreRepresented() = runBlocking {
        val fake = FakeTransport(JsonArray(listOf(tool())), buildJsonObject {
            putJsonArray("content") { addJsonObject { put("type", "image"); put("data", "binary") }; addJsonObject { put("type", "resource") } }
            putJsonObject("structuredContent") { put("count", 3) }
        })
        McpToolProvider(McpConfig(listOf(McpServerConfig("maps", confirm = ConfirmPolicy.NONE))), ToolApprovalGateway { true }, { fake }).use {
            it.specs()
            val result = assertNotNull(it.call("task", "maps__weather", "{}"))
            assertTrue(result.text.contains("[图片内容]"))
            assertTrue(result.text.contains("[资源内容]"))
            assertTrue(result.text.contains("\"count\":3"))
            assertFalse(result.text.contains("binary"))
        }
    }

    @Test fun repeatedPaginationCursorFailsAndClosesTransport() = runBlocking {
        var closed = false
        val transport = object : McpTransport {
            override suspend fun exchange(message: JsonObject): JsonObject? {
                val id = message["id"] ?: return null
                return buildJsonObject { put("id", id); putJsonObject("result") {
                    if (message.string("method") == "tools/list") { put("tools", JsonArray(emptyList())); put("nextCursor", "loop") }
                } }
            }
            override fun close() { closed = true }
        }
        McpToolProvider(McpConfig(listOf(McpServerConfig("maps"))), ToolApprovalGateway { true }, { transport }).use {
            assertTrue(it.specs().isEmpty())
            assertTrue(closed)
        }
    }

    @Test fun userChannelAcceptsOnlyExplicitAffirmatives() = runBlocking {
        val request = ToolApprovalRequest("task", "maps", "weather", "天气", "x".repeat(2000), "需要确认")
        listOf("确认", " 是 ", "好", "可以", "Y", " YES ", "no", "确认一下", "").forEach { reply ->
            val gateway = UserChannelApprovals(UserChannel { task, question, takeover ->
                assertEquals("task", task)
                assertFalse(takeover)
                assertTrue(question.contains("「天气」（maps）"))
                assertTrue(question.contains("[参数已截断]"))
                assertTrue(question.length < 1200)
                reply
            })
            assertEquals(reply.trim().lowercase() in setOf("确认", "是", "好", "可以", "y", "yes"), gateway.approve(request))
        }
    }

    @Test fun shortConfigValuesDoNotCorruptModelResults() = runBlocking {
        val fake = FakeTransport(JsonArray(listOf(tool("weather1", description = "温度 18"))), buildJsonObject {
            putJsonArray("content") { addJsonObject { put("type", "text"); put("text", "温度 18，key=long-secret") } }
        })
        val config = McpConfig.parse("""{"mcpServers":{"maps":{
          "url":"https://example.test?v=1&key=long-secret","env":{"NODE_ENV":"1"},"confirm":"none"}}}""", emptyMap())
        McpToolProvider(config, ToolApprovalGateway { true }, { fake }).use {
            val spec = it.specs().single()
            assertEquals("maps__weather1", spec.name)
            assertTrue(spec.description.contains("温度 18"))
            val result = assertNotNull(it.call("task", spec.name, "{}"))
            assertEquals("温度 18，key=***", result.text)
            assertTrue(result.summary.contains("weather1"))
        }
    }

    @Test fun failedServerWaitsFiveMinutesByDefaultAndResetsDelayOnFailure() = runBlocking {
        var now = 0L
        var attempts = 0
        val good = FakeTransport(JsonArray(listOf(tool())))
        McpToolProvider(McpConfig(listOf(McpServerConfig("good"), McpServerConfig("bad"))), ToolApprovalGateway { true }, { server ->
            if (server.name == "good") good else {
                attempts++
                // Simulate time spent in a failing request: the interval starts at failure completion.
                now += 30_000
                throw IOException("offline")
            }
        }, clock = { now }).use { provider ->
            assertEquals(1, provider.specs().size)
            assertEquals(1, attempts)
            repeat(3) { assertEquals(1, provider.specs().size) }
            assertFalse(provider.status().last().connected)
            assertEquals(1, attempts)
            now += 299_999
            provider.specs()
            assertEquals(1, attempts)
            now++
            provider.specs()
            assertEquals(2, attempts)
            now += 299_999
            provider.specs()
            assertEquals(2, attempts)
            now++
            provider.specs()
            assertEquals(3, attempts)
            assertEquals(1, good.messages.count { it.string("method") == "initialize" })
        }
    }

    @Test fun statusRefreshBypassesRetryDelayAndRecoveryStaysCached() = runBlocking {
        var now = 10L
        var attempts = 0
        val fake = FakeTransport(JsonArray(listOf(tool())))
        McpToolProvider(McpConfig(listOf(McpServerConfig("maps"))), ToolApprovalGateway { true }, {
            attempts++
            if (attempts < 3) throw IOException("offline")
            fake
        }, retryAfterMillis = 1000, clock = { now }).use { provider ->
            assertTrue(provider.specs().isEmpty())
            assertEquals(1, attempts)
            assertFalse(provider.status().single().connected)
            assertEquals(1, attempts)
            assertFalse(provider.status(refresh = true).single().connected)
            assertEquals(2, attempts)
            now += 999
            provider.specs()
            assertEquals(2, attempts)
            val recovered = provider.status(refresh = true).single()
            assertTrue(recovered.connected)
            assertEquals(1, recovered.toolCount)
            assertNull(recovered.error)
            assertEquals(3, attempts)
            now += 1000
            assertEquals(1, provider.specs().size)
            assertTrue(provider.status(refresh = true).single().connected)
            assertEquals(3, attempts)
        }
    }
}
