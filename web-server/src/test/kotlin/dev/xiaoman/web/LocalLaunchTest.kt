package dev.xiaoman.web

import kotlinx.serialization.json.*
import java.net.BindException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.test.*

class LocalLaunchTest {
    private fun get(port: Int, path: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            val code = c.responseCode
            return code to ((if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally { c.disconnect() }
    }
    /** Raw request so the Host header can be chosen (HttpURLConnection does not allow it). */
    private fun statusWithHost(port: Int, path: String, host: String): Int = Socket("127.0.0.1", port).use { s ->
        s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
        s.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
    }

    @Test fun accessCodesAreShortAndEasyToType() {
        repeat(50) { assertTrue(Regex("[2-9a-hjkmnp-z]{6}").matches(LocalLaunch.accessCode())) }
    }

    @Test fun connectPageOnlyInLocalModeAndOnlyFromThisComputer() {
        WebServer(WebServer.Options(port = 0, accessCode = "abc234", localConnect = true), logger = {}).start().use { s ->
            val (code, page) = get(s.port, "/connect")
            assertEquals(200, code); assertTrue("qrcode.js" in page)
            assertEquals(200, get(s.port, "/connect/qrcode.js").first)
            val info = Json.parseToJsonElement(get(s.port, "/connect/info").second).jsonObject
            assertEquals("abc234", info["accessCode"]!!.jsonPrimitive.content)
            assertEquals(s.port, info["port"]!!.jsonPrimitive.int)
            info["addresses"]!!.jsonArray.forEach { a ->
                val url = a.jsonObject["url"]!!.jsonPrimitive.content
                assertTrue(Regex("http://(10|172|192)\\.[0-9.]+:${s.port}/").matches(url), url)
            }
            assertEquals(404, get(s.port, "/connect/missing.js").first)
            assertEquals(200, statusWithHost(s.port, "/connect/info", "localhost:${s.port}"))
            // DNS rebinding: a page on another host name that resolves to 127.0.0.1 must not read the code.
            assertEquals(403, statusWithHost(s.port, "/connect/info", "evil.example:${s.port}"))
            assertEquals(200, get(s.port, "/").first)
        }
        WebServer(WebServer.Options(port = 0), logger = {}).start().use { s -> assertEquals(404, get(s.port, "/connect/info").first) }
    }

    @Test fun startMovesToTheNextFreePort() {
        ServerSocket(0).use { taken ->
            val tried = mutableListOf<Int>()
            val server = LocalLaunch.start(WebServer.Options(port = taken.localPort), tries = 5) { o ->
                tried += o.port
                if (o.port == taken.localPort) throw BindException("busy") else WebServer(o.copy(port = 0), logger = {})
            }
            server.use { assertEquals(listOf(taken.localPort, taken.localPort + 1), tried) }
            assertFailsWith<BindException> { LocalLaunch.start(WebServer.Options(port = taken.localPort), tries = 1) }
        }
    }

    @Test fun wifiAndWiredInterfacesComeBeforeVirtualOnes() {
        assertEquals("wifi", LocalLaunch.kind("en0", "en0"))
        assertEquals("wifi", LocalLaunch.kind("wireless_32768", "Intel(R) Wi-Fi 6 AX201 160MHz"))
        assertEquals("wired", LocalLaunch.kind("eth0", "Realtek PCIe GbE Family Controller"))
        assertEquals("virtual", LocalLaunch.kind("eth5", "vEthernet (WSL)"))
        assertEquals("virtual", LocalLaunch.kind("docker0", "docker0"))
        assertEquals("other", LocalLaunch.kind("bridge100", "bridge100"))
    }
}
