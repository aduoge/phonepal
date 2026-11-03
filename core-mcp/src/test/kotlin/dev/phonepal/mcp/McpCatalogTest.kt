package dev.phonepal.mcp

import kotlin.test.*

class McpCatalogTest {
    private val catalog = McpCatalog.builtIn

    @Test fun builtInCatalogLoads() {
        val ids = catalog.servers.map { it.id }
        assertTrue(listOf("amap", "didi", "aihot", "12306").all { it in ids }, ids.toString())
        val amap = catalog.server("amap")!!
        assertEquals("AMAP_MAPS_API_KEY", amap.keys.single().name)
        assertTrue(amap.availableOn(McpPlatform.WEB) && amap.availableOn(McpPlatform.ANDROID))
        val train = catalog.server("12306")!!
        assertFalse(train.availableOn(McpPlatform.WEB))
        assertTrue(train.availableOn(McpPlatform.DESKTOP))
        assertFalse(catalog.availableOn(McpPlatform.WEB).any { !it.remote })
    }

    @Test fun selectionBecomesConfigWithKeys() {
        val selection = McpSelection(
            enabled = setOf("amap", "aihot", "didi", "12306", "nope"),
            keys = mapOf("AMAP_MAPS_API_KEY" to "amap-secret-123456"),
            custom = listOf(CustomMcpServer("我的服务", "https://mcp.example.com/mcp", mapOf("Authorization" to "Bearer custom-token-9876"))),
        )
        assertEquals(mapOf("didi" to listOf("DIDI_MCP_KEY")), catalog.missingKeys(selection))
        val config = catalog.toConfig(selection, McpPlatform.WEB)
        assertEquals(listOf("aihot", "amap", "custom-1"), config.servers.map { it.name })
        assertEquals("https://mcp.amap.com/mcp?key=amap-secret-123456", config.servers[1].url)
        assertEquals("我的服务", config.servers[2].title)
        assertEquals(ConfirmPolicy.WRITES, config.servers[2].confirm)
        val warnings = config.warnings.joinToString("\n")
        assertTrue("火车票查询 只能在电脑上使用" in warnings, warnings)
        assertTrue("未知的 MCP 服务 nope" in warnings, warnings)
        assertTrue("滴滴出行 还没有填写 滴滴 MCP Key，暂未启用" in warnings, warnings)
        assertFalse("amap-secret-123456" in warnings || "amap-secret-123456" in config.toString())
        assertFalse("custom-token-9876" in config.toString() || "custom-token-9876" in selection.toString())
    }

    @Test fun customServersAreChecked() {
        val config = catalog.toConfig(McpSelection(custom = listOf(
            CustomMcpServer("明文", "http://mcp.example.com/mcp"),
            CustomMcpServer("占位", "https://mcp.example.com/mcp?k=\${HOME}"),
            CustomMcpServer("坏地址", "not a url"),
            CustomMcpServer("本机", "http://localhost:9000/mcp"),
        )), McpPlatform.DESKTOP)
        assertEquals(listOf("custom-4"), config.servers.map { it.name })
        assertEquals(3, config.warnings.size, config.warnings.toString())
    }

    @Test fun describeOmitsEntrySecrets() {
        val json = McpCatalog.describe(catalog.server("amap")!!).toString()
        assertTrue("高德地图" in json && "AMAP_MAPS_API_KEY" in json)
        assertFalse("mcp.amap.com" in json)
    }
}
