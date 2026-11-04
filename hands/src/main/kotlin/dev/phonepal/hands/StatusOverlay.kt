package dev.phonepal.hands

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A small pill at the top of the screen showing the current step and a Stop button.
 *
 * [context] must be the connected [PhonePalAccessibilityService] (or a context derived from it):
 * the window is TYPE_ACCESSIBILITY_OVERLAY, which only an accessibility service may add and which
 * needs no SYSTEM_ALERT_WINDOW permission. The window wraps its content and is not focusable, so
 * touches outside the pill reach the app underneath.
 *
 * All methods may be called from any thread.
 */
class StatusOverlay(private val context: Context) {

    /** Invoked on the main thread when the user taps Stop. */
    var onStop: () -> Unit = {}
    var onOpen: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var textView: TextView? = null
    private var pendingText: CharSequence = ""

    val isShowing: Boolean get() = root?.isAttachedToWindow == true

    fun show() = onMain {
        if (root != null) return@onMain
        val view = buildView()
        try {
            windowManager.addView(view, layoutParams())
            root = view
        } catch (e: RuntimeException) {
            // BadTokenException / WindowManager errors when the service is not connected.
            android.util.Log.w(TAG, "cannot show overlay: ${e.message}")
            root = null
            textView = null
        }
    }

    fun update(text: CharSequence) = onMain {
        pendingText = text
        textView?.text = text
    }

    fun hide() = onMain {
        val view = root ?: return@onMain
        root = null
        textView = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    /**
     * Hides the overlay and disables its window's touch interception while [block] runs,
     * then restores both. View.GONE alone does not remove the WindowManager input surface.
     * Waits [settleMs] after hiding for the frame and input window update to be applied.
     */
    suspend fun <T> hiddenWhile(settleMs: Long = 80, block: suspend () -> T): T {
        val hidden = withContext(Dispatchers.Main.immediate) {
            val view = root
            if (view != null && view.visibility == View.VISIBLE) {
                val params = view.layoutParams as WindowManager.LayoutParams
                val flags = params.flags
                params.flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager.updateViewLayout(view, params)
                view.visibility = View.GONE
                view to flags
            } else null
        }
        if (hidden == null) return block()
        try {
            delay(settleMs)
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                val (view, flags) = hidden
                // hide() clears root before removing the window. Identity is sufficient here;
                // attachment may lag while WindowManager is applying the layout update.
                if (root === view) {
                    val params = view.layoutParams as WindowManager.LayoutParams
                    params.flags = flags
                    view.visibility = View.VISIBLE
                    windowManager.updateViewLayout(view, params)
                }
            }
        }
    }

    private fun buildView(): LinearLayout {
        val pad = dp(10)
        val label = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.52f).toInt()
            text = pendingText
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "查看 PhonePal 任务"
            setOnClickListener { onOpen() }
            // Read status changes to TalkBack users.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val stop = Button(context).apply {
            setText(R.string.phonepal_overlay_stop)
            contentDescription = context.getString(R.string.phonepal_overlay_stop_description)
            minimumHeight = dp(48)
            minHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { onStop() }
        }
        textView = label
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad / 2, pad / 2, pad / 2)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xF01E4635.toInt())
            }
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = pad })
            addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(36)
        title = "PhonePal status"
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val TAG = "PhonePal"
    }
}
