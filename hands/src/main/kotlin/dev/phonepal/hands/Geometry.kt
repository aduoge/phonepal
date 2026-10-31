package dev.phonepal.hands

import dev.phonepal.api.Bounds
import dev.phonepal.api.Point
import kotlin.math.max
import kotlin.math.roundToInt

/** A pixel rectangle in screen coordinates (same convention as android.graphics.Rect). */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** Converts between screen pixels and the 0..1000 normalized space of [Point] and [Bounds]. */
object Coordinates {
    private const val NORM = Point.NORM

    /** Pixel offset [px] along an axis of [extentPx] pixels to 0..1000, clamped. */
    fun normalize(px: Int, extentPx: Int): Int {
        if (extentPx <= 0) return 0
        return (px.toLong() * NORM / extentPx).toInt().coerceIn(0, NORM)
    }

    /**
     * Pixel rectangle to normalized bounds, clamped to the screen. Returns null when nothing of
     * the rectangle is on screen or it has no area after clamping.
     */
    fun normalize(rect: PxRect, widthPx: Int, heightPx: Int): Bounds? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val l = rect.left.coerceIn(0, widthPx)
        val t = rect.top.coerceIn(0, heightPx)
        val r = rect.right.coerceIn(0, widthPx)
        val b = rect.bottom.coerceIn(0, heightPx)
        if (r <= l || b <= t) return null
        return Bounds(normalize(l, widthPx), normalize(t, heightPx), normalize(r, widthPx), normalize(b, heightPx))
    }

    /** Normalized point to pixel coordinates, kept strictly inside the screen. */
    fun denormalize(point: Point, widthPx: Int, heightPx: Int): Pair<Float, Float> {
        val x = (point.x.toFloat() * widthPx / NORM).coerceIn(0f, max(0, widthPx - 1).toFloat())
        val y = (point.y.toFloat() * heightPx / NORM).coerceIn(0f, max(0, heightPx - 1).toFloat())
        return x to y
    }
}

/** Size calculation for the downscaled screenshot sent to the operator model. */
object ScreenshotScaling {
    const val DEFAULT_LONG_EDGE = 1280

    /** Target size keeping the aspect ratio, long edge at most [maxLongEdge]; never upscales. */
    fun targetSize(widthPx: Int, heightPx: Int, maxLongEdge: Int = DEFAULT_LONG_EDGE): Pair<Int, Int> {
        require(widthPx > 0 && heightPx > 0) { "empty image: ${widthPx}x$heightPx" }
        val long = max(widthPx, heightPx)
        if (long <= maxLongEdge) return widthPx to heightPx
        val scale = maxLongEdge.toDouble() / long
        return max(1, (widthPx * scale).roundToInt()) to max(1, (heightPx * scale).roundToInt())
    }

    /**
     * True when every sampled pixel is opaque pure black. On Android 11–13 a FLAG_SECURE window
     * is captured as black instead of failing with ERROR_TAKE_SCREENSHOT_SECURE_WINDOW.
     * [pixels] are ARGB ints, row-major, [width] per row.
     */
    fun looksAllBlack(pixels: IntArray, width: Int, height: Int, samplesPerAxis: Int = 16): Boolean {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return false
        for (sy in 0 until samplesPerAxis) {
            val y = ((sy + 0.5) * height / samplesPerAxis).toInt().coerceIn(0, height - 1)
            for (sx in 0 until samplesPerAxis) {
                val x = ((sx + 0.5) * width / samplesPerAxis).toInt().coerceIn(0, width - 1)
                if (pixels[y * width + x] and 0xFFFFFF != 0) return false
            }
        }
        return true
    }
}
