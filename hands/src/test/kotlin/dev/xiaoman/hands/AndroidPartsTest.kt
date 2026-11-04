package dev.xiaoman.hands

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.xiaoman.api.Action
import dev.xiaoman.api.Bounds
import dev.xiaoman.api.Point
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidPartsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Suppress("DEPRECATION")
    private fun node(text: String?, rect: Rect, clickable: Boolean = false, editable: Boolean = false) =
        AccessibilityNodeInfo.obtain().apply {
            this.text = text
            setBoundsInScreen(rect)
            isVisibleToUser = true
            isClickable = clickable
            isEditable = editable
            className = if (editable) "android.widget.EditText" else "android.widget.TextView"
            viewIdResourceName = "com.example:id/n"
        }

    @Test fun accessibilityNodesToUiNodes() {
        val root = node(null, Rect(0, 0, 1080, 2400))
        val search = node(null, Rect(40, 240, 1040, 360), editable = true).apply { hintText = "你要去哪儿" }
        val go = node("快车", Rect(0, 2160, 540, 2400), clickable = true)
        shadowOf(root).addChild(search)
        shadowOf(root).addChild(go)

        val out = NodeCompactor().compact(listOf(root), AccessibilityNodeTree, 1080, 2400)

        assertEquals(2, out.size)
        assertEquals("你要去哪儿", out[0].ui.description)
        assertTrue(out[0].ui.editable)
        assertEquals("EditText", out[0].ui.className)
        assertEquals("com.example:id/n", out[0].ui.resourceId)
        assertEquals(Bounds(37, 100, 962, 150), out[0].ui.bounds)
        assertEquals("快车", out[1].ui.text)
        assertTrue(out[1].ui.clickable)
        assertEquals(Bounds(0, 900, 500, 1000), out[1].ui.bounds)
    }

    @Test fun stopSignal() {
        val s = MutableStopSignal()
        assertFalse(s.stopped)
        s.stop()
        assertTrue(s.stopped)
        s.reset()
        assertFalse(s.stopped)
    }

    @Test fun deviceWithoutServiceFailsGracefully() = runTest {
        val device = AccessibilityDevice({ null }, app, settleDelayMs = 0)
        assertEquals(AccessibilityDevice.NOT_CONNECTED, device.perform(Action.Tap(point = Point(1, 1))).message)
        assertFalse(device.perform(Action.Back()).ok)
        assertEquals("not a device action", device.perform(Action.Finish(true, "done")).message)
        assertEquals("not a device action", device.perform(Action.AskUser("?")).message)
        assertFailsWith<ServiceNotConnectedException> { device.observe() }
    }

    @Test fun installedAppsListsLauncherActivities() = runTest {
        val pm = shadowOf(app.packageManager)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        listOf("com.autonavi.minimap" to "高德地图", "com.android.settings" to "Settings", "com.autonavi.minimap" to "高德地图").forEach { (pkg, label) ->
            pm.addResolveInfoForIntent(launcher, ResolveInfo().apply {
                nonLocalizedLabel = label
                activityInfo = ActivityInfo().apply {
                    packageName = pkg
                    name = "$pkg.Main"
                    applicationInfo = ApplicationInfo().apply { packageName = pkg }
                }
            })
        }
        val apps = AccessibilityDevice({ null }, app).installedApps()
        assertEquals(listOf("Settings", "高德地图"), apps.map { it.label })
        assertTrue(apps.any { it.packageName == "com.autonavi.minimap" })
    }
}
