package dev.phonepal.hands

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app in front, as reported by window-state events. */
data class ForegroundApp(val packageName: String, val activityName: String?)

/**
 * PhonePal's hands and eyes. Enabled by the user in system Settings → Accessibility.
 * [AccessibilityDevice] does the actual work; this class only exposes the connected instance
 * and tracks the foreground activity.
 */
class PhonePalAccessibilityService : AccessibilityService() {

    private val _foreground = MutableStateFlow<ForegroundApp?>(null)

    /** Last foreground package/activity seen in a TYPE_WINDOW_STATE_CHANGED event. */
    val foreground: StateFlow<ForegroundApp?> = _foreground.asStateFlow()

    /** Activity-name lookups are cached; window-state events are frequent. */
    private val activityCache = HashMap<ComponentName, Boolean>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString()
        if (cls != null && isActivity(ComponentName(pkg, cls))) {
            _foreground.value = ForegroundApp(pkg, cls)
        } else if (_foreground.value?.packageName != pkg) {
            // A window of a new app whose activity we cannot resolve (package not visible, dialog,
            // custom window). Only switch when it really is the active window, not e.g. a toast or the IME.
            val root = rootInActiveWindow
            if (root?.packageName?.toString() == pkg) _foreground.value = ForegroundApp(pkg, null)
            AccessibilityNodeTree.recycleCompat(root)
        }
    }

    private fun isActivity(component: ComponentName): Boolean = activityCache.getOrPut(component) {
        try {
            packageManager.getActivityInfo(component, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        _instance.compareAndSet(this, null)
    }

    companion object {
        private val _instance = MutableStateFlow<PhonePalAccessibilityService?>(null)

        /** The connected service, or null while the user has not enabled it. */
        val instance: StateFlow<PhonePalAccessibilityService?> = _instance.asStateFlow()
    }
}
