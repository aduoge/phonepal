package dev.xiaoman.app.settings

import android.content.Context
import org.robolectric.RuntimeEnvironment
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
class SettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val key = "ark-0123456789abcdef"

    private fun store() = SettingsStore(
        context.getSharedPreferences("plain-test", Context.MODE_PRIVATE),
        context.getSharedPreferences("secret-test", Context.MODE_PRIVATE),
    )

    private val sample = AppSettings(
        chat = ModelSettings(model = "doubao-chat", apiKey = key, temperature = 0.3),
        operator = ModelSettings(baseUrl = "https://example.com/v1", model = "doubao-vision", apiKey = key),
        maxSteps = 12,
        learnFromSuccess = false,
    )

    @Test fun `defaults point at Volcano Ark and are not configured`() {
        val s = store().current
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", s.chat.baseUrl)
        assertFalse(s.configured)
    }

    @Test fun `round trip keeps every field and keys stay out of the plain file`() {
        store().save(sample)
        val loaded = store().current
        assertEquals(sample, loaded)
        assertTrue(loaded.configured)
        val plain = context.getSharedPreferences("plain-test", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(key in plain)
        val secret = context.getSharedPreferences("secret-test", Context.MODE_PRIVATE).all.values.joinToString()
        assertTrue(key in secret)
    }

    @Test fun `toString never shows the api key`() {
        assertFalse(key in sample.toString())
        assertFalse(key in sample.chat.toString())
        assertFalse(key in sample.chat.toModelConfig(dev.xiaoman.api.ModelRole.CHAT).toString())
        assertFalse(key in SettingsForm.of(sample).toString())
        assertTrue("***" in sample.chat.toString())
    }

    @Test fun `form parses and validates numbers`() {
        val form = SettingsForm.of(sample)
        assertEquals(sample, form.toSettings())
        assertNull(form.copy(maxSteps = "0").toSettings())
        assertNull(form.copy(chat = form.chat.copy(temperature = "hot")).toSettings())
        assertNull(form.copy(chat = form.chat.copy(temperature = "")).toSettings()?.chat?.temperature)
    }
}
