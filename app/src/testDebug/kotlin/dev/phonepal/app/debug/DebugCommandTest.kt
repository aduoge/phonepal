package dev.phonepal.app.debug

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DebugCommandTest {
    private fun parse(action: String?, vararg extras: Pair<String, String>) =
        DebugCommand.parse(action, mapOf(*extras)::get) { "generated" }

    @Test fun `run with all extras`() {
        assertEquals(
            DebugCommand.Run("打开设置", "open-settings-wifi", "r1"),
            parse("dev.phonepal.debug.RUN", "goal" to " 打开设置 ", "skill" to "open-settings-wifi", "run_id" to "r1"),
        )
    }

    @Test fun `run id and skill are optional`() {
        assertEquals(DebugCommand.Run("打开设置", null, "generated"), parse("dev.phonepal.debug.RUN", "goal" to "打开设置", "skill" to " "))
    }

    @Test fun `missing goal is an invalid run that keeps the run id`() {
        val cmd = assertIs<DebugCommand.InvalidRun>(parse("dev.phonepal.debug.RUN", "run_id" to "r9"))
        assertEquals("r9", cmd.runId)
    }

    @Test fun `unsafe run id is rejected`() {
        val cmd = assertIs<DebugCommand.InvalidRun>(parse("dev.phonepal.debug.RUN", "goal" to "x", "run_id" to "../etc"))
        assertEquals("generated", cmd.runId)
    }

    @Test fun `stop reload and unknown actions`() {
        assertEquals(DebugCommand.Stop, parse("dev.phonepal.debug.STOP"))
        assertEquals(DebugCommand.ReloadSkills, parse("dev.phonepal.debug.RELOAD_SKILLS"))
        assertNull(parse("dev.phonepal.debug.NOPE"))
        assertNull(parse(null))
    }
}
