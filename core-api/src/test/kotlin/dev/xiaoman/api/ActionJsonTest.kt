package dev.xiaoman.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ActionJsonTest {
    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

    @Test
    fun tapRoundTrips() {
        val tap: Action = Action.Tap(point = Point(500, 900), target = "确认呼叫", reason = "下单")
        val text = json.encodeToString(tap)
        assertEquals(tap, json.decodeFromString<Action>(text))
    }

    @Test
    fun decodesModelStyleJson() {
        val action = json.decodeFromString<Action>("""{"type":"launch","packageName":"com.autonavi.minimap","extra":1}""")
        assertEquals(Action.LaunchApp("com.autonavi.minimap"), action)
    }

    @Test
    fun rejectsOutOfRangePoint() {
        assertFailsWith<IllegalArgumentException> { Point(1001, 0) }
    }
}
