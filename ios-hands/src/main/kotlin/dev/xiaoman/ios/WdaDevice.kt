package dev.xiaoman.ios

import dev.xiaoman.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** iPhone hands and eyes backed by a running WebDriverAgent. */
class WdaDevice(
    private val client: WdaClient,
    apps: List<AppInfo> = IosPlatform.DEFAULT_APPS,
    private val screenshotMaxSide: Int = 1280,
    private val settleMillis: Long = 600,
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : Device {
    init {
        require(screenshotMaxSide > 0)
        require(settleMillis >= 0)
    }

    private val apps = apps.toList()
    private val mutex = Mutex()
    private var width = 0
    private var height = 0
    private var latest = WdaSource.Snapshot(emptyList(), emptyList())

    override suspend fun observe(): ScreenState = mutex.withLock {
        val (w, h) = readSize()
        val screenshot = encodeScreenshot(client.screenshot())
        val snapshot = withContext(Dispatchers.Default) { WdaSource.parseDetails(client.source(), w, h) }
        val bundleId = (client.activeAppInfo()["bundleId"] as? JsonPrimitive)?.contentOrNull
        width = w
        height = h
        latest = snapshot
        ScreenState(screenshot, w, h, bundleId, nodes = snapshot.entries.map { it.ui }, secure = false)
    }

    override suspend fun installedApps(): List<AppInfo> = apps

    override suspend fun perform(action: Action): ActionResult {
        if (action is Action.AskUser || action is Action.Finish) return LOOP_ONLY
        return mutex.withLock {
            try {
                val result = execute(action)
                if (result.ok && action !is Action.Wait && settleMillis > 0) delay(settleMillis)
                result
            } catch (e: WdaException) {
                ActionResult(false, e.message)
            }
        }
    }

    private suspend fun execute(action: Action): ActionResult = when (action) {
        is Action.Tap -> press(action.nodeId, action.point, 60)
        is Action.LongPress -> press(action.nodeId, action.point, action.durationMs.coerceAtLeast(0))
        is Action.Swipe -> {
            ensureSize()
            gesture(coordinates(action.from), coordinates(action.to), action.durationMs.coerceAtLeast(0))
            ActionResult(true, "已滑动")
        }
        is Action.TypeText -> typeText(action)
        is Action.Back -> back()
        is Action.Home -> {
            client.home()
            ActionResult(true, "已回到主屏幕")
        }
        is Action.LaunchApp -> {
            client.launchApp(action.packageName)
            ActionResult(true, "已启动应用")
        }
        is Action.OpenDeeplink -> {
            client.openDeeplink(action.uri)
            ActionResult(true, "已打开链接")
        }
        is Action.Wait -> {
            delay(action.millis.coerceIn(0, 10_000))
            ActionResult(true, "已等待")
        }
        is Action.AskUser, is Action.Finish -> LOOP_ONLY
    }

    private suspend fun press(nodeId: Int?, point: Point?, duration: Long): ActionResult {
        val position = if (nodeId != null) {
            val entry = latest.entries.getOrNull(nodeId) ?: return unknownNode(nodeId)
            center(entry.ui.bounds)
        } else {
            if (point == null) return ActionResult(false, "点击需要 nodeId 或 point")
            ensureSize()
            coordinates(point)
        }
        gesture(position, null, duration)
        return ActionResult(true, "已点击")
    }

    private suspend fun typeText(action: Action.TypeText): ActionResult {
        val nodeId = action.nodeId
        val entry = if (nodeId != null) {
            latest.entries.getOrNull(nodeId) ?: return unknownNode(nodeId)
        } else latest.entries.firstOrNull { it.ui.editable && it.ui.focused }
        if (nodeId != null && entry != null) {
            gesture(center(entry.ui.bounds), null, 60)
            delay(minOf(settleMillis, 500))
        }
        val oldValue = entry?.value?.takeUnless { it == entry.placeholder }
        val elementId = client.focusedElement()
        if (elementId != null) {
            if (!oldValue.isNullOrEmpty()) client.clear(elementId)
            client.setValue(elementId, action.text)
            if (action.submit) client.keys(listOf("\n"))
            return ActionResult(true, if (action.submit) "已输入并提交" else "已输入")
        }
        val text = action.text + if (action.submit) "\n" else ""
        // Preserve supplementary Unicode characters as individual keys.
        client.keys(text.codePoints().toArray().map { String(Character.toChars(it)) })
        return ActionResult(true, "未能定位输入框，原有内容未清空；" + if (action.submit) "已输入并提交" else "已输入")
    }

    private suspend fun back(): ActionResult {
        ensureSize()
        val buttons = latest.entries.filter { it.ui.className == "Button" && it.ui.clickable }
        val button = buttons.firstOrNull { entry ->
            val b = entry.ui.bounds
            latest.navigationBars.any { bar ->
                b.left >= bar.left && b.top >= bar.top && b.right <= bar.right && b.bottom <= bar.bottom &&
                    (b.left + b.right) / 2 <= bar.left + (bar.right - bar.left) / 3
            }
        } ?: buttons.firstOrNull { entry ->
            val b = entry.ui.bounds
            b.top < height * 0.15 && (b.left + b.right) / 2 < width / 3.0
        }
        if (button != null) {
            gesture(center(button.ui.bounds), null, 60)
            return ActionResult(true, "已点击左上角导航返回按钮")
        }
        gesture(1 to height / 2, (width * 0.7).roundToInt() to height / 2, 300)
        return ActionResult(true, "已从左边缘向右滑动返回")
    }

    private fun unknownNode(id: Int) = ActionResult(false, "未知 nodeId $id")

    private suspend fun ensureSize() {
        if (width <= 0 || height <= 0) {
            val size = readSize()
            width = size.first
            height = size.second
        }
    }

    private suspend fun readSize(): Pair<Int, Int> {
        val size = client.windowSize()
        fun dimension(key: String): Int = (size[key] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf { it.isFinite() && it > 0 && it <= Int.MAX_VALUE }
            ?.roundToInt()?.takeIf { it > 0 }
            ?: throw WdaException("/window/size", 200, "invalid window size")
        return dimension("width") to dimension("height")
    }

    private fun coordinates(point: Point): Pair<Int, Int> =
        (point.x.toDouble() * width / Point.NORM).roundToInt().coerceIn(0, width - 1) to
            (point.y.toDouble() * height / Point.NORM).roundToInt().coerceIn(0, height - 1)

    private fun center(bounds: Bounds): Pair<Int, Int> =
        ((bounds.left.toLong() + bounds.right) / 2).toInt().coerceIn(0, width - 1) to
            ((bounds.top.toLong() + bounds.bottom) / 2).toInt().coerceIn(0, height - 1)

    private suspend fun gesture(from: Pair<Int, Int>, to: Pair<Int, Int>?, duration: Long) {
        fun move(point: Pair<Int, Int>, millis: Long) = buildJsonObject {
            put("type", "pointerMove")
            put("duration", millis)
            put("x", point.first)
            put("y", point.second)
        }
        val steps = buildJsonArray {
            add(move(from, 0))
            add(buildJsonObject { put("type", "pointerDown"); put("button", 0) })
            add(buildJsonObject { put("type", "pause"); put("duration", if (to == null) duration else 0) })
            if (to != null) add(move(to, duration))
            add(buildJsonObject { put("type", "pointerUp"); put("button", 0) })
        }
        client.actions(buildJsonObject {
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("type", "pointer")
                    put("id", "finger1")
                    put("parameters", buildJsonObject { put("pointerType", "touch") })
                    put("actions", steps)
                })
            })
        })
    }

    private suspend fun encodeScreenshot(base64: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val full = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(base64)))
                ?: return@withContext null
            val scale = minOf(1.0, screenshotMaxSide.toDouble() / maxOf(full.width, full.height))
            val w = (full.width * scale).roundToInt().coerceAtLeast(1)
            val h = (full.height * scale).roundToInt().coerceAtLeast(1)
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.drawImage(full, 0, 0, w, h, null)
            } finally {
                graphics.dispose()
                full.flush()
            }
            try {
                ByteArrayOutputStream().use { output ->
                    if (ImageIO.write(image, "png", output)) output.toByteArray() else null
                }
            } finally {
                image.flush()
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        val LOOP_ONLY = ActionResult(false, "此动作由操作循环处理")
    }
}
