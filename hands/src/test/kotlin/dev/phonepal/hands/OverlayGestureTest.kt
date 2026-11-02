package dev.phonepal.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.phonepal.api.Action
import dev.phonepal.api.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowWindowManagerImpl
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [OverlayGestureTest.GestureShadow::class])
class OverlayGestureTest {
    @Test fun coordinateGesturesDoNotHitTheStopOverlay() = checkGestures(cancel = false)
    @Test fun cancelledGesturesRestoreTheStopOverlay() = checkGestures(cancel = true)

    @Test fun nodeEnumerationAlsoHidesTheStopOverlay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(PhonePalAccessibilityService::class.java).create()
        val service = controller.get()
        val overlay = StatusOverlay(service)
        try {
            overlay.show()
            val view = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java)).views.single()
            var reads = 0
            GestureShadow.readRoot = {
                assertEquals(View.GONE, view.visibility)
                reads++
                null
            }
            // A secure screenshot avoids hardware bitmap dependencies; node enumeration still runs.
            Shadow.extract<GestureShadow>(service).setTakeScreenshotErrorCode(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
            AccessibilityDevice({ service }, service, settleDelayMs = 0, overlay = overlay).observe()
            assertEquals(1, reads)
            assertEquals(View.VISIBLE, view.visibility)
        } finally {
            GestureShadow.readRoot = { null }
            overlay.hide()
            controller.destroy()
            Dispatchers.resetMain()
        }
    }

    private fun checkGestures(cancel: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(PhonePalAccessibilityService::class.java).create()
        val service = controller.get()
        val overlay = StatusOverlay(service)
        try {
            overlay.show()
            val windows = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
            val view = windows.views.single()
            assertEquals(View.VISIBLE, view.visibility)
            val originalFlags = (view.layoutParams as WindowManager.LayoutParams).flags
            var dispatched = 0
            GestureShadow.checkAndCancel = {
                // The model sees a screenshot without the pill. Its gesture must reach that same UI.
                assertEquals(View.GONE, view.visibility)
                assertEquals(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    (view.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
                dispatched++
                cancel
            }
            val device = AccessibilityDevice({ service }, service, settleDelayMs = 0, overlay = overlay)
            for (action in listOf(
                Action.Tap(point = Point(620, 78)),
                Action.LongPress(point = Point(620, 78)),
                Action.Swipe(Point(620, 78), Point(620, 300)),
            )) {
                assertEquals(!cancel, device.perform(action).ok)
                assertEquals(View.VISIBLE, view.visibility)
                assertEquals(originalFlags, (view.layoutParams as WindowManager.LayoutParams).flags)
            }
            assertEquals(3, dispatched)
        } finally {
            GestureShadow.checkAndCancel = { false }
            overlay.hide()
            controller.destroy()
            Dispatchers.resetMain()
        }
    }

    @Implements(AccessibilityService::class)
    class GestureShadow : ShadowAccessibilityService() {
        @Implementation
        fun getRootInActiveWindow(): AccessibilityNodeInfo? = readRoot()

        @Implementation
        override fun dispatchGesture(
            gesture: GestureDescription,
            callback: AccessibilityService.GestureResultCallback?,
            handler: Handler?,
        ): Boolean {
            if (checkAndCancel()) callback?.onCancelled(gesture) else callback?.onCompleted(gesture)
            return true
        }

        companion object {
            var checkAndCancel: () -> Boolean = { false }
            var readRoot: () -> AccessibilityNodeInfo? = { null }
        }
    }
}
