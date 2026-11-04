package dev.xiaoman.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Screen coordinates are normalized: x and y both run 0..1000 from the top-left corner,
 * independent of the device resolution. Only [Device] implementations convert to pixels.
 */
@Serializable
data class Point(val x: Int, val y: Int) {
    init {
        require(x in 0..NORM && y in 0..NORM) { "Point out of range 0..$NORM: ($x, $y)" }
    }

    companion object {
        const val NORM = 1000
    }
}

/** A rectangle in normalized 0..1000 coordinates. */
@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val center: Point get() = Point((left + right) / 2, (top + bottom) / 2)
}

/** One element of the compact accessibility tree sent to the operator model. */
@Serializable
data class UiNode(
    /** Index within [ScreenState.nodes]; the model may refer to a node by this id. */
    val id: Int,
    val text: String? = null,
    val description: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val password: Boolean = false,
)

/** What the agent can see of the phone at one moment. */
class ScreenState(
    /**
     * PNG bytes, already downscaled by the [Device] (long edge about 1280 px), or null when the
     * screen could not be captured (see [secure]).
     */
    val screenshotPng: ByteArray?,
    val widthPx: Int,
    val heightPx: Int,
    val packageName: String?,
    val activityName: String? = null,
    val nodes: List<UiNode> = emptyList(),
    /** True when the foreground window forbids capture (FLAG_SECURE: payment, password pages). */
    val secure: Boolean = false,
    val capturedAtMillis: Long = System.currentTimeMillis(),
) {
    /** Stable fingerprint of what is on screen (package + node texts), used for stuck detection. */
    fun fingerprint(): String = buildString {
        append(packageName).append('|').append(activityName)
        nodes.forEach { append('|').append(it.text).append('/').append(it.description).append('@').append(it.bounds.center.x / 20).append(',').append(it.bounds.center.y / 20) }
    }.hashCode().toString(16)
}

/** One step the operator decides to take. Serialized as `{"type": "...", ...}` in model output and traces. */
@Serializable
sealed class Action {
    /** Free-form words describing what the action is aimed at, e.g. "确认呼叫 按钮". Used by Sentinel. */
    abstract val target: String?

    /** Why the model chose this step; shown to the user and kept in the trace. */
    abstract val reason: String?

    @Serializable @SerialName("tap")
    data class Tap(val point: Point? = null, val nodeId: Int? = null, override val target: String? = null, override val reason: String? = null) : Action()

    @Serializable @SerialName("long_press")
    data class LongPress(val point: Point? = null, val nodeId: Int? = null, val durationMs: Long = 800, override val target: String? = null, override val reason: String? = null) : Action()

    @Serializable @SerialName("swipe")
    data class Swipe(val from: Point, val to: Point, val durationMs: Long = 300, override val target: String? = null, override val reason: String? = null) : Action()

    /** Types into the focused editable field, or into [nodeId] when given. */
    @Serializable @SerialName("type")
    data class TypeText(val text: String, val nodeId: Int? = null, val submit: Boolean = false, override val target: String? = null, override val reason: String? = null) : Action()

    @Serializable @SerialName("back")
    data class Back(override val target: String? = null, override val reason: String? = null) : Action()

    @Serializable @SerialName("home")
    data class Home(override val target: String? = null, override val reason: String? = null) : Action()

    @Serializable @SerialName("launch")
    data class LaunchApp(val packageName: String, override val target: String? = null, override val reason: String? = null) : Action()

    @Serializable @SerialName("deeplink")
    data class OpenDeeplink(val uri: String, val packageName: String? = null, override val target: String? = null, override val reason: String? = null) : Action()

    @Serializable @SerialName("wait")
    data class Wait(val millis: Long = 1000, override val target: String? = null, override val reason: String? = null) : Action()

    /** Hand control to the user (login, captcha, secure page) or ask a question; the loop pauses for the answer. */
    @Serializable @SerialName("ask_user")
    data class AskUser(val question: String, val takeover: Boolean = false, override val target: String? = null, override val reason: String? = null) : Action()

    @Serializable @SerialName("finish")
    data class Finish(val success: Boolean, val summary: String, override val target: String? = null, override val reason: String? = null) : Action()
}

@Serializable
data class ActionResult(val ok: Boolean, val message: String? = null)

@Serializable
data class AppInfo(val packageName: String, val label: String)

/** The phone's hands and eyes. Implemented on Android by the accessibility service; faked in tests. */
interface Device {
    suspend fun observe(): ScreenState

    /** Executes one non-terminal action (not [Action.AskUser] or [Action.Finish]). */
    suspend fun perform(action: Action): ActionResult

    suspend fun installedApps(): List<AppInfo>
}
