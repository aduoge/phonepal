package dev.xiaoman.mcp

import java.io.File
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class McpStdioTest {
    private fun scriptTest(script: String, block: suspend (File) -> Unit) = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("mcp-stdio-").toFile()
        try {
            val file = File(directory, "server.py")
            file.writeText(script)
            block(file)
        } finally { assertTrue(directory.deleteRecursively()) }
    }

    @Test fun pythonServerLifecycleAndCall() = scriptTest("""
        import json, os, sys
        initialized = False
        print("startup noise", flush=True)
        for line in sys.stdin:
            m = json.loads(line)
            method = m['method']
            if method == 'notifications/initialized':
                initialized = True
                continue
            if method == 'initialize':
                assert m['params']['protocolVersion'] == '2025-06-18'
                result = {'capabilities': {}}
            elif method == 'tools/list':
                assert initialized
                result = {'tools': [{'name': 'echo', 'inputSchema': {'type': 'object'}}]}
            else:
                assert initialized
                assert m['params']['name'] == 'echo'
                result = {'content': [{'type': 'text', 'text': os.environ['MCP_TEST_VALUE'] + m['params']['arguments']['message']}]}
            print('not json', flush=True)
            print(json.dumps({'jsonrpc':'2.0','method':'notifications/progress'}), flush=True)
            print(json.dumps({'jsonrpc':'2.0','id':999,'result':{}}), flush=True)
            print(json.dumps({'jsonrpc':'2.0','id':m['id'],'result':result}), flush=True)
    """.trimIndent()) { file ->
        val server = McpServerConfig("local", command = "python3", args = listOf("-u", file.absolutePath),
            env = mapOf("MCP_TEST_VALUE" to "env-secret"))
        McpClient(server).use { client ->
            assertEquals("echo", client.listTools().single().name)
            val result = client.callTool("echo", buildJsonObject { put("message", " hello") })
            assertTrue(result.ok)
            assertEquals("*** hello", result.text)
        }
    }

    @Test fun stdioExitDoesNotLeakStderr() = scriptTest("""
        import sys
        print('stderr-secret', file=sys.stderr, flush=True)
        sys.exit(2)
    """.trimIndent()) { file ->
        McpClient(McpServerConfig("local", command = "python3", args = listOf(file.absolutePath))).use {
            val error = assertFailsWith<McpException> { it.initialize() }
            assertFalse(error.toString().contains("stderr-secret"))
            assertTrue(error.message.orEmpty().contains("退出"))
        }
    }

    @Test fun stdioTimeoutAndClose() = scriptTest("""
        import time
        time.sleep(30)
    """.trimIndent()) { file ->
        val transport = McpStdioTransport(McpServerConfig("local", command = "python3", args = listOf(file.absolutePath), timeoutSeconds = 1))
        val client = McpClient(McpServerConfig("local"), transport)
        try {
            assertTrue(assertFailsWith<McpException> { client.initialize() }.message.orEmpty().contains("超时"))
            transport.close()
            assertTrue(assertFailsWith<McpException> { client.initialize() }.message.orEmpty().contains("关闭"))
        } finally { client.close() }
    }

    @Test fun environmentAllowlistHidesOtherVariables() = scriptTest("""
        import json, os, sys
        for line in sys.stdin:
            m = json.loads(line)
            if 'id' not in m: continue
            if m['method'] == 'tools/call':
                seen = ','.join(sorted(k for k in ('PATH', 'HOME', 'MCP_TEST_VALUE') if k in os.environ))
                result = {'content': [{'type': 'text', 'text': seen}]}
            elif m['method'] == 'tools/list':
                result = {'tools': [{'name': 'env', 'inputSchema': {'type': 'object'}}]}
            else:
                result = {'capabilities': {}}
            print(json.dumps({'jsonrpc':'2.0','id':m['id'],'result':result}), flush=True)
    """.trimIndent()) { file ->
        // HOME is set in the parent but not allowed; PATH is allowed; server.env is always passed.
        val server = McpServerConfig("local", command = "python3", args = listOf("-u", file.absolutePath),
            env = mapOf("MCP_TEST_VALUE" to "configured"))
        McpClient(server, McpStdioTransport(server, inheritedEnvironment = setOf("PATH"))).use { client ->
            assertEquals("MCP_TEST_VALUE,PATH", client.callTool("env", buildJsonObject {}).text)
        }
    }
}
