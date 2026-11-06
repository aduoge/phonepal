package dev.xiaoman.ios

import com.sun.net.httpserver.HttpServer
import dev.xiaoman.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.*

class WdaDeviceTest {
    private data class Request(
        val method: String,
        val path: String,
        val body: JsonObject?,
        val protocol: String,
        val upgrade: String?,
    )
    private class FakeWda : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        var xml = sourceXml
        var screenshot = png(780, 1688)
        var sessions = 0
        var nestedSession = false
        var invalidActions = 0
        var actionError: String? = null
        var focusedElements = buildJsonArray {}
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val bodyText = exchange.requestBody.bufferedReader().use { it.readText() }
                val body = bodyText.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject }
                val path = exchange.requestURI.toString()
                requests += Request(exchange.requestMethod, path, body, exchange.protocol,
                    exchange.requestHeaders.getFirst("Upgrade"))
                var status = 200
                val response: JsonObject = when {
                    path == "/session" -> {
                        sessions++
                        if (nestedSession) buildJsonObject {
                            put("value", buildJsonObject { put("sessionId", "s$sessions") })
                        } else buildJsonObject { put("sessionId", "s$sessions"); put("value", buildJsonObject {}) }
                    }
                    path.endsWith("/actions") && invalidActions > 0 -> {
                        invalidActions--
                        status = 404
                        wrapped(buildJsonObject { put("error", "invalid session id") })
                    }
                    path.endsWith("/actions") && actionError != null -> {
                        status = 500
                        wrapped(buildJsonObject { put("error", actionError) })
                    }
                    path.endsWith("/elements") -> wrapped(focusedElements)
                    path.endsWith("/window/size") -> wrapped(buildJsonObject { put("width", 390); put("height", 844) })
                    path == "/screenshot" -> wrapped(JsonPrimitive(screenshot))
                    path == "/source?format=xml" -> wrapped(JsonPrimitive(xml))
                    path == "/wda/activeAppInfo" -> wrapped(buildJsonObject { put("bundleId", "com.example.test") })
                    else -> wrapped(JsonNull)
                }
                val bytes = response.toString().toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val client = WdaClient("http://127.0.0.1:" + server.address.port, 3000)
        fun actions() = requests.filter { it.path.endsWith("/actions") }
        fun keys() = requests.filter { it.path.endsWith("/wda/keys") }
        override fun close() { server.stop(0) }
    }

    @Test fun observeScalesPngAndUsesWindowPointsAndBundleId() = runBlocking {
        FakeWda().use { fake ->
            val device = WdaDevice(fake.client, screenshotMaxSide = 400, settleMillis = 0)
            assertEquals(0, fake.sessions)
            val screen = device.observe()
            assertEquals(1, fake.sessions)
            assertEquals("com.example.test", screen.packageName)
            assertEquals(390, screen.widthPx)
            assertEquals(844, screen.heightPx)
            assertNull(screen.activityName)
            assertFalse(screen.secure)
            val bytes = assertNotNull(screen.screenshotPng)
            assertContentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), bytes.take(8).toByteArray())
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            assertEquals(400, image.height)
            assertEquals(185, image.width)
            assertFalse(image.colorModel.hasAlpha())
            assertEquals("返回", screen.nodes.first().text)
            assertEquals(Bounds(10, 50, 70, 90), screen.nodes.first().bounds)
            fake.screenshot = "not base64"
            assertNull(device.observe().screenshotPng)
            fake.screenshot = Base64.getEncoder().encodeToString("not an image".toByteArray())
            assertNull(device.observe().screenshotPng)
            fake.screenshot = png(20, 40)
            val small = ImageIO.read(ByteArrayInputStream(assertNotNull(device.observe().screenshotPng)))
            assertEquals(20, small.width)
            assertEquals(40, small.height)
            assertEquals(1, fake.sessions)
        }
    }

    @Test fun tapsLongPressAndSwipeProduceW3cPointActionsAndSettle() = runBlocking {
        FakeWda().use { fake ->
            val waits = mutableListOf<Long>()
            val device = WdaDevice(fake.client, settleMillis = 600, delay = { waits += it })
            device.observe()
            assertTrue(device.perform(Action.Tap(nodeId = 0)).ok)
            assertMove(steps(fake.actions().last())[0], 40, 70, 0)
            assertTrue(device.perform(Action.Tap(point = Point(500, 250))).ok)
            assertMove(steps(fake.actions().last())[0], 195, 211, 0)
            assertTrue(device.perform(Action.Tap(point = Point(1000, 1000))).ok)
            assertMove(steps(fake.actions().last())[0], 389, 843, 0)
            assertTrue(device.perform(Action.LongPress(nodeId = 0, durationMs = 1250)).ok)
            assertEquals(1250L, steps(fake.actions().last())[2].jsonObject["duration"]!!.jsonPrimitive.long)
            assertTrue(device.perform(Action.Swipe(Point(100, 200), Point(900, 800), 720)).ok)
            val request = fake.actions().last()
            val pointer = request.body!!["actions"]!!.jsonArray.single().jsonObject
            assertEquals("pointer", pointer["type"]!!.jsonPrimitive.content)
            assertEquals("finger1", pointer["id"]!!.jsonPrimitive.content)
            assertEquals("touch", pointer["parameters"]!!.jsonObject["pointerType"]!!.jsonPrimitive.content)
            val actions = steps(request)
            assertEquals(listOf("pointerMove", "pointerDown", "pause", "pointerMove", "pointerUp"),
                actions.map { it.jsonObject["type"]!!.jsonPrimitive.content })
            assertMove(actions[0], 39, 169, 0)
            assertMove(actions[3], 351, 675, 720)
            assertEquals(0, actions[1].jsonObject["button"]!!.jsonPrimitive.int)
            assertEquals(0, actions[4].jsonObject["button"]!!.jsonPrimitive.int)
            assertEquals(listOf(600L, 600L, 600L, 600L, 600L), waits)
        }
    }

    @Test fun typingFocusesWaitsClearsSetsValueAndSubmits() = runBlocking {
        FakeWda().use { fake ->
            fake.focusedElements = buildJsonArray { add(buildJsonObject { put("ELEMENT", "field1") }) }
            val waits = mutableListOf<Long>()
            val device = WdaDevice(fake.client, settleMillis = 600, delay = {
                waits += it
                if (waits.size == 1) assertTrue(fake.requests.last().path.endsWith("/actions"))
            })
            device.observe()
            val before = fake.requests.size
            assertTrue(device.perform(Action.TypeText("你好😀", nodeId = 1, submit = true)).ok)
            val sent = fake.requests.drop(before)
            assertEquals(listOf("/session/s1/actions", "/session/s1/elements",
                "/session/s1/element/field1/clear", "/session/s1/element/field1/value",
                "/session/s1/wda/keys"), sent.map { it.path })
            assertTrue(sent.all { it.method == "POST" })
            assertMove(steps(sent[0])[0], 120, 170, 0)
            assertEquals(buildJsonObject {
                put("using", "predicate string"); put("value", "hasKeyboardFocus == 1")
            }, sent[1].body)
            assertEquals(listOf("你", "好", "😀"), keyValues(sent[3]))
            assertEquals(listOf("\n"), keyValues(sent[4]))
            assertEquals(listOf(500L, 600L), waits)
        }
    }

    @Test fun typingPlaceholderUsesW3cElementWithoutClearing() = runBlocking {
        FakeWda().use { fake ->
            fake.focusedElements = buildJsonArray {
                add(buildJsonObject { put("element-6066-11e4-a52e-4f735466cecf", "field2") })
            }
            val waits = mutableListOf<Long>()
            val device = WdaDevice(fake.client, settleMillis = 100, delay = { waits += it })
            device.observe()
            val before = fake.requests.size
            assertTrue(device.perform(Action.TypeText("新值", nodeId = 2)).ok)
            val sent = fake.requests.drop(before)
            assertEquals(listOf("/session/s1/actions", "/session/s1/elements",
                "/session/s1/element/field2/value"), sent.map { it.path })
            assertEquals(listOf("新", "值"), keyValues(sent.last()))
            assertEquals(listOf(100L, 100L), waits)
            val next = fake.requests.size
            assertTrue(device.perform(Action.TypeText("焦点")).ok)
            assertEquals(listOf("/session/s1/elements", "/session/s1/element/field2/value"),
                fake.requests.drop(next).map { it.path })
            assertEquals(listOf(100L, 100L, 100L), waits)
        }
    }

    @Test fun typingWithoutFocusedElementUsesKeysAndReportsUnclearedContent() = runBlocking {
        FakeWda().use { fake ->
            val waits = mutableListOf<Long>()
            val device = WdaDevice(fake.client, settleMillis = 120, delay = { waits += it })
            device.observe()
            val before = fake.requests.size
            val result = device.perform(Action.TypeText("你好😀", nodeId = 1, submit = true))
            assertTrue(result.ok)
            assertTrue(result.message!!.contains("未能定位输入框，原有内容未清空"))
            val sent = fake.requests.drop(before)
            assertEquals(listOf("/session/s1/actions", "/session/s1/elements",
                "/session/s1/wda/keys"), sent.map { it.path })
            assertEquals(listOf("你", "好", "😀", "\n"), keyValues(sent.last()))
            assertEquals(listOf(120L, 120L), waits)
            val next = fake.requests.size
            val focusedResult = device.perform(Action.TypeText("焦点"))
            assertTrue(focusedResult.ok)
            assertTrue(focusedResult.message!!.contains("未清空"))
            assertEquals(listOf("/session/s1/elements", "/session/s1/wda/keys"),
                fake.requests.drop(next).map { it.path })
            assertEquals(listOf("焦", "点"), keyValues(fake.keys().last()))
        }
    }

    @Test fun focusedElementAcceptsBothIdKeysAndOnlyUsesFirstMatch() = runBlocking {
        FakeWda().use { fake ->
            assertNull(fake.client.focusedElement())
            for (key in listOf("ELEMENT", "element-6066-11e4-a52e-4f735466cecf")) {
                fake.focusedElements = buildJsonArray {
                    add(buildJsonObject { put(key, "first") })
                    add(buildJsonObject { put(key, "second") })
                }
                assertEquals("first", fake.client.focusedElement())
            }
            fake.focusedElements = buildJsonArray {
                add(buildJsonObject {})
                add(buildJsonObject { put("ELEMENT", "second") })
            }
            assertNull(fake.client.focusedElement())
            assertEquals(1, fake.sessions)
        }
    }

    @Test fun backUsesNavigationButtonThenFallsBackToEdgeSwipe() = runBlocking {
        FakeWda().use { fake ->
            val device = WdaDevice(fake.client, settleMillis = 0)
            device.observe()
            assertTrue(device.perform(Action.Back()).message!!.contains("导航"))
            assertMove(steps(fake.actions().last())[0], 40, 70, 0)
            fake.xml = sourceXml.replace("<root>", """
                <root><XCUIElementTypeButton visible="true" enabled="true" label="其他顶部按钮" x="0" y="0" width="20" height="20"/>
            """.trimIndent())
            device.observe()
            assertTrue(device.perform(Action.Back()).message!!.contains("导航"))
            assertMove(steps(fake.actions().last())[0], 40, 70, 0)
            fake.xml = """<root><XCUIElementTypeButton visible="true" enabled="true" label="返回" x="10" y="20" width="40" height="30"/></root>"""
            device.observe()
            assertTrue(device.perform(Action.Back()).message!!.contains("按钮"))
            assertMove(steps(fake.actions().last())[0], 30, 35, 0)
            fake.xml = "<root/>"
            device.observe()
            assertTrue(device.perform(Action.Back()).message!!.contains("左边缘"))
            val actions = steps(fake.actions().last())
            assertMove(actions[0], 1, 422, 0)
            assertMove(actions[3], 273, 422, 300)
        }
    }

    @Test fun homeLaunchDeeplinkWaitAndLoopActionsUseExpectedEndpoints() = runBlocking {
        FakeWda().use { fake ->
            val waits = mutableListOf<Long>()
            val apps = listOf(AppInfo("com.custom", "自定义"))
            val device = WdaDevice(fake.client, apps, settleMillis = 12, delay = { waits += it })
            assertEquals(apps, device.installedApps())
            assertTrue(device.perform(Action.Home()).ok)
            assertEquals("/wda/homescreen", fake.requests.last().path)
            assertTrue(device.perform(Action.LaunchApp("com.apple.Preferences")).ok)
            assertEquals("/session/s1/wda/apps/launch", fake.requests.last().path)
            assertEquals("com.apple.Preferences", fake.requests.last().body!!["bundleId"]!!.jsonPrimitive.content)
            assertTrue(device.perform(Action.OpenDeeplink("https://example.test/你好")).ok)
            assertEquals("/session/s1/url", fake.requests.last().path)
            assertEquals("https://example.test/你好", fake.requests.last().body!!["url"]!!.jsonPrimitive.content)
            val count = fake.requests.size
            assertTrue(device.perform(Action.Wait(20_000)).ok)
            assertTrue(device.perform(Action.Wait(-10)).ok)
            for (action in listOf(Action.AskUser("需要登录"), Action.Finish(true, "完成"))) {
                val result = device.perform(action)
                assertFalse(result.ok)
                assertTrue(result.message!!.contains("循环"))
            }
            assertFalse(device.perform(Action.Tap(nodeId = 999, point = Point(1, 1))).ok)
            assertFalse(device.perform(Action.LongPress(nodeId = 999)).ok)
            assertFalse(device.perform(Action.TypeText("秘密", nodeId = 999)).ok)
            assertEquals(count, fake.requests.size)
            assertEquals(listOf(12L, 12L, 12L, 10_000L, 0L), waits)
            assertTrue(fake.requests.filter { it.path != "/session" }.all { it.method == "POST" })
        }
    }

    @Test fun invalidSessionRebuildsOnceAndOtherFailuresBecomeResults() = runBlocking {
        FakeWda().use { fake ->
            fake.nestedSession = true
            val device = WdaDevice(fake.client, settleMillis = 0)
            device.observe()
            fake.invalidActions = 1
            assertTrue(device.perform(Action.Tap(nodeId = 0)).ok)
            assertEquals(2, fake.sessions)
            assertEquals(listOf("/session/s1/actions", "/session/s2/actions"), fake.actions().map { it.path })
            assertEquals(fake.actions()[0].body, fake.actions()[1].body)
            val sessionBody = fake.requests.first { it.path == "/session" }.body!!
            assertEquals(buildJsonObject {}, sessionBody["capabilities"]!!.jsonObject["alwaysMatch"])
            fake.invalidActions = 2
            val failed = device.perform(Action.Tap(nodeId = 0))
            assertFalse(failed.ok)
            assertTrue(failed.message!!.contains("404"))
            assertEquals(3, fake.sessions)
            assertEquals(4, fake.actions().size)
            fake.actionError = "unknown error"
            val otherFailure = device.perform(Action.Tap(nodeId = 0))
            assertFalse(otherFailure.ok)
            assertTrue(otherFailure.message!!.contains("/session/s3/actions"))
            assertTrue(otherFailure.message!!.contains("500"))
            assertEquals(3, fake.sessions)
        }
    }

    @Test fun pointActionBeforeObservationFetchesSizeAndClientStatusWorks() = runBlocking {
        FakeWda().use { fake ->
            fake.client.status()
            assertEquals("/status", fake.requests.single().path)
            assertEquals(0, fake.sessions)
            assertEquals("HTTP/1.1", fake.requests.single().protocol)
            assertNull(fake.requests.single().upgrade)
            val device = WdaDevice(fake.client, settleMillis = 0)
            assertTrue(device.perform(Action.Tap(point = Point(500, 500))).ok)
            assertMove(steps(fake.actions().single())[0], 195, 422, 0)
            assertEquals(1, fake.sessions)
        }
    }

    private fun steps(request: Request): JsonArray =
        request.body!!["actions"]!!.jsonArray.single().jsonObject["actions"]!!.jsonArray

    private fun assertMove(step: JsonElement, x: Int, y: Int, duration: Long) {
        val move = step.jsonObject
        assertEquals("pointerMove", move["type"]!!.jsonPrimitive.content)
        assertEquals(x, move["x"]!!.jsonPrimitive.int)
        assertEquals(y, move["y"]!!.jsonPrimitive.int)
        assertEquals(duration, move["duration"]!!.jsonPrimitive.long)
    }

    private fun keyValues(request: Request): List<String> =
        request.body!!["value"]!!.jsonArray.map { it.jsonPrimitive.content }

    companion object {
        private fun wrapped(value: JsonElement) = buildJsonObject { put("value", value) }
        private fun png(w: Int, h: Int): String = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", output)
            Base64.getEncoder().encodeToString(output.toByteArray())
        }
        private val sourceXml = """
            <root>
              <XCUIElementTypeNavigationBar visible="true" x="0" y="40" width="390" height="60">
                <XCUIElementTypeButton visible="true" enabled="true" label="返回" x="10" y="50" width="60" height="40"/>
              </XCUIElementTypeNavigationBar>
              <XCUIElementTypeTextField visible="true" enabled="true" label="姓名" value="旧内容" x="20" y="150" width="200" height="40"/>
              <XCUIElementTypeTextField visible="true" enabled="true" label="搜索" value="搜索" placeholderValue="搜索" x="20" y="200" width="200" height="40"/>
            </root>
        """.trimIndent()
    }
}
