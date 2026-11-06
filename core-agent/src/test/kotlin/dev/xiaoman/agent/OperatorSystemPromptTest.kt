package dev.xiaoman.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperatorSystemPromptTest {
    @Test
    fun blankNotesKeepTheDefaultPrompt() {
        assertEquals(Prompts.OPERATOR_SYSTEM, Prompts.operatorSystem(""))
        assertEquals(Prompts.OPERATOR_SYSTEM, Prompts.operatorSystem("  \n"))
    }

    @Test
    fun notesAreAppendedAsAPlatformSection() {
        val prompt = Prompts.operatorSystem("  launch 的 packageName 填 bundleId \n")
        assertTrue(prompt.startsWith(Prompts.OPERATOR_SYSTEM))
        assertTrue(prompt.endsWith("## 平台说明\nlaunch 的 packageName 填 bundleId"))
    }
}
