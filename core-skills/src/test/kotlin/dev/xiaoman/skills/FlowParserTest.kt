package dev.xiaoman.skills

import dev.xiaoman.api.Flow
import dev.xiaoman.api.FlowStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowParserTest {
    private val deeplinks = mapOf("search" to "amapuri://route/plan/?dname={destination}")

    private fun parse(text: String, apps: List<String> = emptyList()) = FlowParser.parse(text, "flow.yaml", deeplinks, apps)

    private fun singleProblem(text: String, fragment: String, apps: List<String> = emptyList()): Int? {
        val (flow, problems) = parse(text, apps)
        assertNull(flow)
        assertEquals(1, problems.size, problems.toString())
        assertTrue(fragment in problems.single().message, problems.single().message)
        return problems.single().line
    }

    @Test
    fun parsesAllStepKinds() {
        val text = """
            steps:
              - launch: com.autonavi.minimap
              - deeplink: search
              - deeplink: "amapuri://poi?name=家"
              - ai: 选择快车并读出预估价
              - confirm: 确认呼叫快车？
              - tap: 确认呼叫
              - type: { target: 目的地, text: "{destination}" }
              - wait: 1500
              - assert: 行程已开始
        """.trimIndent()
        val (flow, problems) = parse(text, apps = listOf("com.autonavi.minimap"))
        assertEquals(emptyList(), problems)
        assertEquals(
            Flow(
                listOf(
                    FlowStep.Launch("com.autonavi.minimap"),
                    FlowStep.Deeplink("search"),
                    FlowStep.Deeplink("amapuri://poi?name=家"),
                    FlowStep.Ai("选择快车并读出预估价"),
                    FlowStep.Confirm("确认呼叫快车？"),
                    FlowStep.Tap("确认呼叫"),
                    FlowStep.Type("目的地", "{destination}"),
                    FlowStep.Wait(1500),
                    FlowStep.Assert("行程已开始"),
                ),
            ),
            flow,
        )
    }

    @Test
    fun unknownStepKindReportsIndexAndLine() {
        val line = singleProblem("steps:\n  - tap: a\n  - swipe: up\n", "step 2 (swipe): unknown step kind")
        assertEquals(3, line)
    }

    @Test
    fun wrongShapes() {
        singleProblem("steps:\n  - wait: soon\n", "step 1 (wait)")
        singleProblem("steps:\n  - wait: -5\n", "step 1 (wait)")
        singleProblem("steps:\n  - type: 目的地\n", "step 1 (type)")
        singleProblem("steps:\n  - type: { target: 目的地 }\n", "step 1 (type)")
        singleProblem("steps:\n  - type: { target: a, text: b, extra: c }\n", "step 1 (type)")
        singleProblem("steps:\n  - tap:\n", "step 1 (tap)")
        singleProblem("steps:\n  - tap: a\n    wait: 5\n", "step 1: expected one")
        singleProblem("steps:\n  - just text\n", "step 1: expected one")
        singleProblem("steps:\n  - launch: 高德\n", "not a package name")
    }

    @Test
    fun unknownDeeplinkKey() {
        val line = singleProblem("steps:\n  - deeplink: home\n", "'home' is neither a key")
        assertEquals(2, line)
    }

    @Test
    fun launchOutsideApps() {
        singleProblem("steps:\n  - launch: com.other.app\n", "not in the skill's apps", apps = listOf("com.autonavi.minimap"))
    }

    @Test
    fun badTopLevel() {
        singleProblem("", "empty")
        singleProblem("- tap: a\n", "must be a mapping")
        val (flow, problems) = parse("step:\n  - tap: a\n")
        assertNull(flow)
        assertEquals(listOf("unknown key 'step'", "missing 'steps:'"), problems.map { it.message.substringBefore(';').substringBefore(" list") })
        singleProblem("steps: []\n", "'steps' is empty")
        singleProblem("steps: tap\n", "'steps' must be a list")
    }

    @Test
    fun reportsEveryBadStep() {
        val (flow, problems) = parse("steps:\n  - fly: a\n  - tap: ok\n  - wait: x\n")
        assertNull(flow)
        assertEquals(listOf(2, 4), problems.map { it.line })
    }
}
