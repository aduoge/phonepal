package dev.xiaoman.web

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.*
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * "Run on my own computer" mode (`--local`), used by the classroom launcher scripts: students without an
 * Android phone run the server on their laptop and open it from the phone over the same Wi-Fi.
 *
 * Adds a port fallback, a generated access code, a printout of the LAN addresses, and a `/connect` page with
 * a QR code of the LAN address. The page shows the access code, so it only answers requests made on this
 * computer (loopback peer and a localhost Host header, which also blocks DNS rebinding).
 */
object LocalLaunch {
    /** No 0/o/1/l/i, so the code is easy to read off a screen and type on a phone. */
    private const val CODE_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"
    private val random = SecureRandom()

    fun accessCode(length: Int = 6): String = buildString { repeat(length) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) } }

    /** Starts on [WebServer.Options.port] or, when it is taken, one of the next [tries] - 1 ports. */
    fun start(options: WebServer.Options, tries: Int = 20, create: (WebServer.Options) -> WebServer = ::WebServer): WebServer {
        var last: BindException? = null
        for (port in options.port until minOf(options.port + tries, 65536)) {
            try { return create(options.copy(port = port)).start() } catch (e: BindException) { last = e }
        }
        throw last ?: BindException("没有可用端口")
    }

    data class LanAddress(val address: String, val label: String, val interfaceName: String)

    /** Private IPv4 addresses of this computer, Wi-Fi first, then wired, then everything else. */
    fun lanAddresses(): List<LanAddress> {
        val found = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic to it } }
        } catch (_: Exception) { emptyList() }
        return found.filter { (_, ip) -> ip.isSiteLocalAddress }
            .map { (nic, ip) -> Triple(nic.name.orEmpty(), nic.displayName.orEmpty(), ip.hostAddress) }
            .sortedWith(compareBy({ rank(it.first, it.second, it.third) }, { it.third }))
            .map { (name, display, ip) -> LanAddress(ip, label(name, display), display.ifBlank { name }) }
            .distinctBy { it.address }
    }

    private val VIRTUAL = listOf("docker", "veth", "br-", "virbr", "vmnet", "vbox", "virtualbox", "vmware", "hyper-v", "vethernet",
        "utun", "tun", "tap", "wsl", "tailscale", "zerotier", "awdl", "llw", "anpi", "loopback", "bluetooth", "vpn")
    private val WIFI = listOf("wi-fi", "wifi", "wlan", "wireless", "802.11", "airport", "无线")
    private val WIRED = listOf("eth", "ethernet", "以太网", "enp", "eno", "ens")

    internal fun kind(name: String, display: String): String {
        val text = "$name $display".lowercase()
        return when {
            VIRTUAL.any { it in text } -> "virtual"
            WIFI.any { it in text } || name.startsWith("wl") || name == "en0" -> "wifi"
            WIRED.any { it in text } || Regex("en\\d+").matches(name) -> "wired"
            else -> "other"
        }
    }
    private fun rank(name: String, display: String, ip: String): Int =
        when (kind(name, display)) { "wifi" -> 0; "wired" -> 1; "other" -> 2; else -> 4 } + if (ip.startsWith("192.168.") || ip.startsWith("172.20.10.")) 0 else 1
    private fun label(name: String, display: String) =
        when (kind(name, display)) { "wifi" -> "Wi-Fi/热点"; "wired" -> "有线网络"; "virtual" -> "虚拟网卡（手机一般连不上）"; else -> "其他网络" }

    /** Prints what the student needs in the terminal: LAN links, the access code and the connect page. */
    fun announce(port: Int, accessCode: String?, out: (String) -> Unit = ::println) {
        val addresses = lanAddresses()
        out("")
        out("================ 小满网页版已在这台电脑上启动 ================")
        out("电脑浏览器打开：http://127.0.0.1:$port/connect （扫码页，会自动打开）")
        if (addresses.isEmpty()) out("没找到局域网地址：请先连上 Wi-Fi，或让电脑连手机热点，然后在扫码页点“刷新地址”。")
        else {
            out("手机（和电脑连同一个 Wi-Fi）在浏览器里打开：")
            addresses.forEachIndexed { i, a -> out("  ${if (i == 0) "→" else " "} http://${a.address}:$port/   （${a.label}：${a.interfaceName}）") }
        }
        if (!accessCode.isNullOrEmpty()) out("访问码：$accessCode")
        out("关掉这个窗口（或按 Ctrl+C）就会停止小满。")
        out("==============================================================")
        out("")
    }

    /** Opens the connect page in the default browser; failures are ignored, the address is printed anyway. */
    fun openBrowser(url: String) {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            os.startsWith("mac") -> listOf("open", url)
            os.startsWith("windows") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> listOf("xdg-open", url)
        }
        runCatching { ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }
    }

    internal fun isConnectPath(path: String) = path == "/connect" || path.startsWith("/connect/")

    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

    internal fun fromThisComputer(x: HttpExchange): Boolean {
        val host = x.requestHeaders.getFirst("Host").orEmpty().lowercase().let { h ->
            if (h.startsWith("[")) h.substringBefore("]") + "]" else h.substringBefore(":")
        }
        return x.remoteAddress?.address?.isLoopbackAddress == true && host in LOCAL_HOSTS
    }

    /** Serves `/connect` (page, script, styles, QR library) and `/connect/info` (addresses and code). */
    internal fun serve(x: HttpExchange, path: String, port: Int, accessCode: String?, stdioMcp: Boolean) {
        fun send(code: Int, type: String, bytes: ByteArray) {
            x.responseHeaders.set("Content-Type", type)
            x.responseHeaders.set("Cache-Control", "no-store")
            x.sendResponseHeaders(code, bytes.size.toLong()); x.responseBody.write(bytes)
        }
        if (!fromThisComputer(x)) return send(403, "text/plain; charset=utf-8",
            "扫码页只能在运行小满的这台电脑上打开（地址用 127.0.0.1 或 localhost）。".toByteArray())
        if (x.requestMethod != "GET") return send(405, "text/plain; charset=utf-8", "请求方法不支持".toByteArray())
        if (path == "/connect/info") return send(200, "application/json; charset=utf-8", buildJsonObject {
            put("port", port)
            put("accessCode", accessCode.orEmpty())
            put("stdioMcp", stdioMcp)
            put("addresses", JsonArray(lanAddresses().map { a -> buildJsonObject {
                put("address", a.address); put("label", a.label); put("interface", a.interfaceName)
                put("url", "http://${a.address}:$port/")
            } }))
        }.toString().toByteArray())
        val name = when (path) { "/connect", "/connect/" -> "index.html"; else -> path.removePrefix("/connect/") }
        val type = mapOf("index.html" to "text/html; charset=utf-8", "connect.js" to "text/javascript; charset=utf-8",
            "qrcode.js" to "text/javascript; charset=utf-8", "connect.css" to "text/css; charset=utf-8")[name]
        val bytes = type?.let { LocalLaunch::class.java.getResourceAsStream("/connect/$name")?.use { it.readBytes() } }
            ?: return send(404, "text/plain; charset=utf-8", "文件不存在".toByteArray())
        if (name == "index.html") x.responseHeaders.set("Content-Security-Policy",
            "default-src 'self'; img-src 'self' data:; style-src 'self'; connect-src 'self'; frame-ancestors 'none'")
        send(200, type, bytes)
    }
}
