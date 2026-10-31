package dev.phonepal.hands

import dev.phonepal.api.Bounds
import dev.phonepal.api.Point
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeometryTest {
    @Test fun normalizeAxis() {
        assertEquals(0, Coordinates.normalize(0, 1080))
        assertEquals(500, Coordinates.normalize(540, 1080))
        assertEquals(1000, Coordinates.normalize(1080, 1080))
        assertEquals(1000, Coordinates.normalize(5000, 1080))
        assertEquals(0, Coordinates.normalize(-20, 1080))
        assertEquals(0, Coordinates.normalize(10, 0))
    }

    @Test fun normalizeRectClampsToScreen() {
        assertEquals(Bounds(0, 0, 500, 250), Coordinates.normalize(PxRect(-100, -50, 540, 600), 1080, 2400))
        assertEquals(Bounds(500, 500, 1000, 1000), Coordinates.normalize(PxRect(540, 1200, 2000, 3000), 1080, 2400))
    }

    @Test fun normalizeRectOffscreenOrEmptyIsNull() {
        assertNull(Coordinates.normalize(PxRect(0, 2500, 100, 2600), 1080, 2400))
        assertNull(Coordinates.normalize(PxRect(10, 10, 10, 50), 1080, 2400))
        assertNull(Coordinates.normalize(PxRect(0, 0, 10, 10), 0, 0))
    }

    @Test fun denormalize() {
        assertEquals(540f to 1200f, Coordinates.denormalize(Point(500, 500), 1080, 2400))
        assertEquals(0f to 0f, Coordinates.denormalize(Point(0, 0), 1080, 2400))
        // The far edge stays on screen.
        assertEquals(1079f to 2399f, Coordinates.denormalize(Point(1000, 1000), 1080, 2400))
    }

    @Test fun roundTripIsClose() {
        val b = Coordinates.normalize(PxRect(100, 300, 400, 500), 1080, 2400)!!
        val (x, y) = Coordinates.denormalize(b.center, 1080, 2400)
        assertTrue(kotlin.math.abs(x - 250f) < 3f && kotlin.math.abs(y - 400f) < 3f, "($x, $y)")
    }

    @Test fun targetSizeDownscalesLongEdge() {
        assertEquals(576 to 1280, ScreenshotScaling.targetSize(1080, 2400))
        assertEquals(1280 to 576, ScreenshotScaling.targetSize(2400, 1080))
        assertEquals(960 to 1280, ScreenshotScaling.targetSize(1440, 1920))
        assertEquals(720 to 1280, ScreenshotScaling.targetSize(720, 1280))
        assertEquals(400 to 800, ScreenshotScaling.targetSize(400, 800))
        assertEquals(1 to 1280, ScreenshotScaling.targetSize(1, 100_000))
    }

    @Test fun allBlackDetection() {
        assertTrue(ScreenshotScaling.looksAllBlack(IntArray(100 * 200) { 0xFF000000.toInt() }, 100, 200))
        val px = IntArray(100 * 200) { 0xFF000000.toInt() }
        px.fill(0xFFFFFFFF.toInt(), 0, 100 * 20)
        assertFalse(ScreenshotScaling.looksAllBlack(px, 100, 200))
        assertFalse(ScreenshotScaling.looksAllBlack(IntArray(0), 0, 0))
    }
}
