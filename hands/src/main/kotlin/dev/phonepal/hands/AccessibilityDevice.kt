package dev.phonepal.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import dev.phonepal.api.Action
import dev.phonepal.api.ActionResult
import dev.phonepal.api.AppInfo
import dev.phonepal.api.Device
import dev.phonepal.api.Point
import dev.phonepal.api.ScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Thrown by [AccessibilityDevice.observe] while the accessibility service is not enabled. */
class ServiceNotConnectedException : IllegalStateException(AccessibilityDevice.NOT_CONNECTED)

/**
 * [Device] backed by [PhonePalAccessibilityService].
 *
 * @param serviceProvider returns the connected service or null; usually `{ PhonePalAccessibilityService.instance.value }`.
 * @param context any context (the application context is fine); used for package queries and as fallback for starting activities.
 * @param maxNodes cap of the compact node list.
 * @param settleDelayMs pause after every action so the UI can react before the next observation.
 * @param overlay when set, it is hidden during screenshots and dispatched touch gestures.
 */
class AccessibilityDevice(
    private val serviceProvider: () -> PhonePalAccessibilityService?,
    context: Context,
    private val maxNodes: Int = NodeCompactor.DEFAULT_MAX_NODES,
    private val settleDelayMs: Long = 600,
    var overlay: StatusOverlay? = null,
) : Device {

    private val appContext: Context = context.applicationContext ?: context
    private val compactor = NodeCompactor(maxNodes = maxNodes)
    private val mutex = Mutex()

    /** id -> node of the latest observation, for actions that name a nodeId. Guarded by [mutex]. */
    private var latestNodes: List<CompactNode<AccessibilityNodeInfo>> = emptyList()
    private var lastScreenshotAt = 0L

    // ---------------------------------------------------------------- observe

    override suspend fun observe(): ScreenState = mutex.withLock {
        val service = serviceProvider() ?: throw ServiceNotConnectedException()
        // Keep screenshots and node enumeration on the same unobstructed screen. Restoring the
        // pill between them can put its Stop button into the model's node list.
        overlay?.hiddenWhile { observeUnobstructed(service) } ?: observeUnobstructed(service)
    }

    private suspend fun observeUnobstructed(service: PhonePalAccessibilityService): ScreenState {
        val (w, h) = displaySize(service)
        val shot = captureScreenshot(service)
        val (pkg, nodes) = withContext(Dispatchers.Default) { readNodes(service, w, h) }
        val foreground = service.foreground.value
        return ScreenState(
            screenshotPng = shot.png,
            widthPx = w,
            heightPx = h,
            packageName = pkg ?: foreground?.packageName,
            activityName = foreground?.activityName?.takeIf { pkg == null || foreground.packageName == pkg },
            nodes = nodes,
            secure = shot.secure,
        )
    }

    private class Shot(val png: ByteArray?, val secure: Boolean)

    private suspend fun captureScreenshot(service: PhonePalAccessibilityService): Shot {
        repeat(SCREENSHOT_ATTEMPTS) {
            // Android allows one takeScreenshot per second per service.
            val wait = lastScreenshotAt + SCREENSHOT_INTERVAL_MS - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            val result = takeScreenshot(service)
            lastScreenshotAt = SystemClock.uptimeMillis()
            when (result) {
                is RawShot.Ok -> return withContext(Dispatchers.Default) { encode(result.bitmap) }
                is RawShot.Failed -> when (result.code) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> Unit // retry after the interval
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> return Shot(null, secure = true)
                    else -> {
                        Log.w(TAG, "takeScreenshot failed: ${result.code}")
                        return Shot(null, secure = false)
                    }
                }
            }
        }
        return Shot(null, secure = false)
    }

    private sealed class RawShot {
        class Ok(val bitmap: Bitmap) : RawShot()
        class Failed(val code: Int) : RawShot()
    }

    private suspend fun takeScreenshot(service: PhonePalAccessibilityService): RawShot =
        suspendCancellableCoroutine { cont ->
            val direct = Executor { it.run() }
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, direct, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hw ->
                                hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
                            }
                        } finally {
                            buffer.close()
                        }
                        if (cont.isActive) {
                            cont.resume(if (bitmap != null) RawShot.Ok(bitmap) else RawShot.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
                        } else {
                            bitmap?.recycle()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(RawShot.Failed(errorCode))
                    }
                })
            } catch (e: RuntimeException) {
                Log.w(TAG, "takeScreenshot threw", e)
                if (cont.isActive) cont.resume(RawShot.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
            }
        }

    private fun encode(full: Bitmap): Shot {
        try {
            val (tw, th) = ScreenshotScaling.targetSize(full.width, full.height)
            val scaled = if (tw == full.width && th == full.height) full else Bitmap.createScaledBitmap(full, tw, th, true)
            try {
                val pixels = IntArray(scaled.width * scaled.height)
                scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                // Android 11-13 return secure windows as black frames instead of failing.
                if (ScreenshotScaling.looksAllBlack(pixels, scaled.width, scaled.height)) return Shot(null, secure = true)
                val out = ByteArrayOutputStream(256 * 1024)
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                return Shot(out.toByteArray(), secure = false)
            } finally {
                if (scaled !== full) scaled.recycle()
            }
        } finally {
            full.recycle()
        }
    }

    /** Reads the compact node list and the active package; replaces [latestNodes]. Caller holds [mutex]. */
    private fun readNodes(service: PhonePalAccessibilityService, w: Int, h: Int): Pair<String?, List<dev.phonepal.api.UiNode>> {
        val roots = collectRoots(service)
        val pkg = roots.firstOrNull()?.packageName?.toString()
        val kept = compactor.compact(roots, AccessibilityNodeTree, w, h)
        // Children not kept were released by the compactor; release roots not kept (identity, not equals()).
        roots.forEach { root -> if (kept.none { it.source === root }) AccessibilityNodeTree.recycleCompat(root) }
        val previous = latestNodes
        latestNodes = kept
        previous.forEach { AccessibilityNodeTree.recycleCompat(it.source) }
        return pkg to kept.map { it.ui }
    }

    /** Active window first, then other application windows (dialogs, popups) top-down. Skips the IME and overlays. */
    private fun collectRoots(service: PhonePalAccessibilityService): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        val windows = runCatching { service.windows }.getOrDefault(emptyList())
        val activeRoot = service.rootInActiveWindow
        val activeWindow = activeRoot?.let { root -> windows.firstOrNull { it.id == root.windowId } }
        val active = if (activeWindow != null &&
            activeWindow.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
            activeWindow.type != AccessibilityWindowInfo.TYPE_SYSTEM
        ) {
            AccessibilityNodeTree.recycleCompat(activeRoot)
            null
        } else activeRoot
        if (active != null) roots += active
        for (window in windows.sortedByDescending { it.layer }) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION && window.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            if (active != null && window.id == active.windowId) continue
            // System windows: only permission/system dialogs, not the status or navigation bar.
            if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM && !window.isFocused) continue
            window.root?.let { roots += it }
        }
        return roots
    }

    @Suppress("DEPRECATION")
    private fun displaySize(service: Context): Pair<Int, Int> {
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    // ---------------------------------------------------------------- perform

    override suspend fun perform(action: Action): ActionResult {
        if (action is Action.AskUser || action is Action.Finish) return ActionResult(false, "not a device action")
        val service = serviceProvider() ?: return ActionResult(false, NOT_CONNECTED)
        val result = try {
            mutex.withLock { execute(service, action) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "action failed: $action", e)
            ActionResult(false, e.message ?: e.javaClass.simpleName)
        }
        if (action !is Action.Wait && settleDelayMs > 0) delay(settleDelayMs)
        return result
    }

    private suspend fun execute(service: PhonePalAccessibilityService, action: Action): ActionResult = when (action) {
        is Action.Tap -> press(service, action.nodeId, action.point, long = false, durationMs = TAP_MS)
        is Action.LongPress -> press(service, action.nodeId, action.point, long = true, durationMs = action.durationMs.coerceIn(400, 5000))
        is Action.Swipe -> {
            val (w, h) = displaySize(service)
            val (x1, y1) = Coordinates.denormalize(action.from, w, h)
            val (x2, y2) = Coordinates.denormalize(action.to, w, h)
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            gestureResult(service, path, action.durationMs.coerceIn(50, 5000), "swipe")
        }
        is Action.TypeText -> typeText(service, action)
        is Action.Back -> global(service, AccessibilityService.GLOBAL_ACTION_BACK, "back")
        is Action.Home -> global(service, AccessibilityService.GLOBAL_ACTION_HOME, "home")
        is Action.LaunchApp -> launch(service, action.packageName)
        is Action.OpenDeeplink -> deeplink(service, action)
        is Action.Wait -> {
            delay(action.millis.coerceIn(0, MAX_WAIT_MS))
            ActionResult(true)
        }
        is Action.AskUser, is Action.Finish -> ActionResult(false, "not a device action")
    }

    private suspend fun press(service: PhonePalAccessibilityService, nodeId: Int?, point: Point?, long: Boolean, durationMs: Long): ActionResult {
        val entry = nodeId?.let { latestNodes.getOrNull(it) }
        if (nodeId != null && entry == null && point == null) return ActionResult(false, "unknown nodeId $nodeId")
        if (entry != null) {
            val node = entry.source
            val fresh = runCatching { node.refresh() }.getOrDefault(false)
            val canAct = if (long) node.isLongClickable else node.isClickable
            if (fresh && canAct) {
                val id = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
                if (node.performAction(id)) return ActionResult(true, "${if (long) "long_click" else "click"} node $nodeId")
            }
            // Not clickable itself (text inside a clickable parent), stale, or refused: press its center.
            val rect = if (fresh) AccessibilityNodeTree.facts(node).boundsPx else entry.boundsPx
            return pressAt(service, rect.centerX, rect.centerY, durationMs, "node $nodeId")
        }
        val p = point ?: return ActionResult(false, "tap needs nodeId or point")
        val (w, h) = displaySize(service)
        val (x, y) = Coordinates.denormalize(p, w, h)
        return pressAt(service, x, y, durationMs, "(${p.x},${p.y})")
    }

    private suspend fun pressAt(service: PhonePalAccessibilityService, x: Float, y: Float, durationMs: Long, what: String): ActionResult {
        val path = Path().apply { moveTo(x, y) }
        return gestureResult(service, path, durationMs, "press $what")
    }

    private suspend fun gestureResult(service: PhonePalAccessibilityService, path: Path, durationMs: Long, what: String): ActionResult {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        // Screenshots omit the status pill. Keep the same touch surface while dispatching:
        // otherwise a top-of-screen tap can hit our Stop button instead of the app underneath.
        val completed = overlay?.hiddenWhile { dispatch(service, gesture) } ?: dispatch(service, gesture)
        return if (completed) ActionResult(true, what) else ActionResult(false, "$what: gesture cancelled")
    }

    private suspend fun dispatch(service: AccessibilityService, gesture: GestureDescription): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                if (!service.dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
            }
        }

    private suspend fun typeText(service: PhonePalAccessibilityService, action: Action.TypeText): ActionResult {
        var target: AccessibilityNodeInfo? = null
        val nodeId = action.nodeId
        if (nodeId != null) {
            val entry = latestNodes.getOrNull(nodeId) ?: return ActionResult(false, "unknown nodeId $nodeId")
            val node = entry.source
            if (runCatching { node.refresh() }.getOrDefault(false) && node.isEditable) {
                target = node
            } else {
                // Focus the field first (search bars often are a clickable placeholder that opens the real input).
                press(service, nodeId, null, long = false, durationMs = TAP_MS)
                delay(FOCUS_DELAY_MS)
            }
        }
        val focused = if (target == null) service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) else null
        val node = target ?: focused?.takeIf { it.isEditable }
            ?: return ActionResult(false, "no editable field focused").also { AccessibilityNodeTree.recycleCompat(focused) }
        try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
            }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return ActionResult(false, "set text refused")
            if (action.submit) {
                delay(SUBMIT_DELAY_MS)
                if (!node.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) return ActionResult(false, "text set, but IME enter refused")
            }
            return ActionResult(true, if (action.submit) "typed and submitted" else "typed")
        } finally {
            if (node === focused) AccessibilityNodeTree.recycleCompat(focused)
        }
    }

    private fun global(service: AccessibilityService, id: Int, what: String): ActionResult =
        if (service.performGlobalAction(id)) ActionResult(true, what) else ActionResult(false, "$what refused")

    private fun launch(service: PhonePalAccessibilityService, packageName: String): ActionResult {
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult(false, "未找到应用 $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(service, intent, "launched $packageName")
    }

    private fun deeplink(service: PhonePalAccessibilityService, action: Action.OpenDeeplink): ActionResult {
        val uri = runCatching { Uri.parse(action.uri) }.getOrNull()
        if (uri?.scheme.isNullOrEmpty()) return ActionResult(false, "invalid uri ${action.uri}")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        action.packageName?.let { intent.setPackage(it) }
        return start(service, intent, "opened ${action.uri}")
    }

    /** Starts from the service context: an app bound as an accessibility service may start activities from the background. */
    private fun start(service: Context, intent: Intent, ok: String): ActionResult = try {
        service.startActivity(intent)
        ActionResult(true, ok)
    } catch (e: ActivityNotFoundException) {
        ActionResult(false, "no app can open it: ${e.message}")
    } catch (e: SecurityException) {
        ActionResult(false, "not allowed: ${e.message}")
    }

    // ---------------------------------------------------------------- apps

    override suspend fun installedApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                AppInfo(pkg, info.loadLabel(pm).toString().ifBlank { pkg })
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }

    companion object {
        const val NOT_CONNECTED = "无障碍服务未开启"
        private const val TAG = "PhonePal"
        private const val SCREENSHOT_INTERVAL_MS = 1050L
        private const val SCREENSHOT_ATTEMPTS = 3
        private const val TAP_MS = 60L
        private const val FOCUS_DELAY_MS = 400L
        private const val SUBMIT_DELAY_MS = 150L
        private const val MAX_WAIT_MS = 30_000L
    }
}
