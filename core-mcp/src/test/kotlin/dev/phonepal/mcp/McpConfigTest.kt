package dev.phonepal.mcp

import java.io.File
import kotlin.test.*

class McpConfigTest {
    @Test fun standardAndExtendedConfig() {
        val config = McpConfig.parse("""{"_comment":"ignored","mcpServers":{
            "maps":{"url":"https://example.test/mcp","title":"地图","headers":{"X-Client":"phonepal"},
              "confirm":"none","tools":["weather"],"timeoutSeconds":9,"_comment":"ignored"},
            "local":{"command":"python3","args":["server.py"],"env":{"MODE":"local"}},
            "disabled":{"url":"https://example.test","enabled":false,"confirm":"all"},
            "_comment":"ignored"}}""", emptyMap())
        assertEquals(2, config.servers.size)
        assertTrue(config.warnings.isEmpty())
        with(config.servers[0]) {
            assertEquals("地图", title)
            assertEquals(ConfirmPolicy.NONE, confirm)
            assertEquals(setOf("weather"), tools)
            assertEquals(9, timeoutSeconds)
            assertEquals("phonepal", headers["X-Client"])
        }
        with(config.servers[1]) {
            assertEquals("python3", command)
            assertEquals(listOf("server.py"), args)
            assertEquals("local", env["MODE"])
            assertEquals(ConfirmPolicy.WRITES, confirm)
            assertTrue(enabled)
            assertEquals(30, timeoutSeconds)
        }
        assertTrue(config.servers.none { it.name == "disabled" })
    }

    @Test fun substitutionAcrossStringsAndNestedValues() {
        val config = McpConfig.parse("""{"mcpServers":{
          "http":{"url":"https://example.test?key=${'$'}{TOKEN}","headers":{"Authorization":"Bearer ${'$'}{TOKEN}"}},
          "stdio":{"command":"${'$'}{CMD}","args":["${'$'}{ARG}"],"env":{"KEY":"${'$'}{TOKEN}"}}}}""",
            mapOf("TOKEN" to "secret-token", "CMD" to "python3", "ARG" to "server.py"))
        assertEquals("https://example.test?key=secret-token", config.servers[0].url)
        assertEquals("Bearer secret-token", config.servers[0].headers["Authorization"])
        assertEquals("server.py", config.servers[1].args.single())
        assertEquals("secret-token", config.servers[1].env["KEY"])
        assertFalse(config.toString().contains("secret-token"))
    }

    @Test fun missingVariableSkipsOnlyThatServerWithoutSecrets() {
        val config = McpConfig.parse("""{"mcpServers":{
          "bad":{"url":"https://example.test?key=${'$'}{KNOWN}&other=${'$'}{MISSING}","headers":{"X":"${'$'}{SECOND}"}},
          "ok":{"url":"https://example.test","_comment":"${'$'}{IGNORED}"}}}""", mapOf("KNOWN" to "secret-value"))
        assertEquals(listOf("ok"), config.servers.map { it.name })
        assertEquals(listOf("服务器 bad 未启用：缺少环境变量 MISSING", "服务器 bad 未启用：缺少环境变量 SECOND"), config.warnings)
        assertFalse(config.toString().contains("secret-value"))
    }

    @Test fun invalidServerDoesNotDisableOthers() {
        val config = McpConfig.parse("""{"mcpServers":{
          "bad name":{"url":"https://example.test"},
          "both":{"url":"https://example.test","command":"secret-command"},
          "timeout":{"url":"https://example.test","timeoutSeconds":0},
          "ok":{"url":"https://example.test"}}}""", emptyMap())
        assertEquals(listOf("ok"), config.servers.map { it.name })
        assertEquals(3, config.warnings.size)
        assertFalse(config.warnings.toString().contains("secret-command"))
    }

    @Test fun toStringMasksUrlHeadersEnvAndEncodedSecrets() {
        val server = McpServerConfig("safe", url = "https://example.test?key=url%2Bsecret&extra=another-secret",
            headers = mapOf("Authorization" to "Bearer header-secret"), env = mapOf("KEY" to "env-secret"),
            args = listOf("url+secret", "header-secret", "env-secret"))
        val text = server.toString()
        listOf("url%2Bsecret", "url+secret", "another-secret", "header-secret", "env-secret").forEach {
            assertFalse(text.contains(it), text)
        }
        assertTrue(text.contains("key=***"))
    }

    @Test fun missingFileAndInvalidJsonAreSafe() {
        val directory = kotlin.io.path.createTempDirectory("mcp-config-").toFile()
        try {
            assertTrue(McpConfig.load(File(directory, "missing.json"), emptyMap()).servers.isEmpty())
            assertTrue(McpConfig.parse("secret-broken-json", emptyMap()).warnings.isNotEmpty())
            assertFalse(McpConfig.parse("secret-broken-json", emptyMap()).toString().contains("secret-broken-json"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun disabledServersSkipSubstitutionAndValidationWithoutWarnings() {
        val config = McpConfig.parse("""{"mcpServers":{
          "disabled":{"enabled":false,"url":"https://example.test?key=${'$'}{MISSING}",
            "headers":{"Authorization":"Bearer ${'$'}{OTHER}"},"env":{"KEY":"${'$'}{ENV_MISSING}"}},
          "invalid name":{"enabled":false,"command":[],"timeoutSeconds":-1},
          "ok":{"url":"https://example.test"}}}""", emptyMap())
        assertEquals(listOf("ok"), config.servers.map { it.name })
        assertTrue(config.warnings.isEmpty())
    }

    @Test fun redactionPreservesShortValuesAcrossEverySecretSource() {
        val config = McpConfig.parse("""{"mcpServers":{"maps":{
          "url":"https://example.test?v=1&short=abcde&encoded=%61%62%63%64%65&key=abcdef",
          "headers":{"X-Short":"small","Authorization":"Bearer tiny","X-Key":"header-secret"},
          "env":{"NODE_ENV":"1","KEY":"env-secret"},
          "title":"${'$'}{SHORT} ${'$'}{LONG}"}}}""", mapOf("SHORT" to "five!", "LONG" to "substituted-secret"))
        val redactor = config.servers.single().redactor
        assertEquals("温度 18 small tiny abcde %61%62%63%64%65 five!", redactor.redact("温度 18 small tiny abcde %61%62%63%64%65 five!"))
        assertEquals("*** *** *** ***", redactor.redact("abcdef header-secret env-secret substituted-secret"))
        val bearer = McpServerConfig("bearer", headers = mapOf("Authorization" to "Bearer abcdef"))
        assertEquals("***", bearer.redactor.redact("abcdef"))
    }
}
