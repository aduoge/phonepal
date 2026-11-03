package dev.phonepal.app.settings

import android.content.Context
import dev.phonepal.mcp.ConfirmPolicy
import dev.phonepal.mcp.CustomMcpServer
import dev.phonepal.mcp.McpSelection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpSettingsStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val plain = context.getSharedPreferences("mcp-plain-test", Context.MODE_PRIVATE)
    private val secret = context.getSharedPreferences("mcp-secret-test", Context.MODE_PRIVATE)

    @Test fun `round trip keeps ticks keys and custom servers, secrets only in the secret file`() {
        val selection = McpSelection(
            enabled = setOf("amap", "aihot"),
            keys = mapOf("AMAP_MAPS_API_KEY" to "amap-key-0123456789", "EMPTY" to " "),
            custom = listOf(CustomMcpServer("我的", "https://mcp.example.com/mcp", mapOf("Authorization" to "Bearer tok-abcdef123"), ConfirmPolicy.ALL)),
        )
        McpSettingsStore(plain, secret).save(selection)
        val loaded = McpSettingsStore(plain, secret).current
        assertEquals(selection.enabled, loaded.enabled)
        assertEquals(mapOf("AMAP_MAPS_API_KEY" to "amap-key-0123456789"), loaded.keys)
        assertEquals(selection.custom, loaded.custom)
        val plainDump = plain.all.values.joinToString()
        assertFalse("amap-key-0123456789" in plainDump || "tok-abcdef123" in plainDump, plainDump)
        assertTrue("https://mcp.example.com/mcp" in plainDump)
    }

    @Test fun `empty store loads an empty selection`() {
        assertEquals(McpSelection(), McpSettingsStore(
            context.getSharedPreferences("mcp-empty-a", Context.MODE_PRIVATE),
            context.getSharedPreferences("mcp-empty-b", Context.MODE_PRIVATE),
        ).current)
    }
}
