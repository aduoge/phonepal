package dev.phonepal.agent

import dev.phonepal.api.Action
import dev.phonepal.api.AgentConfig
import dev.phonepal.api.AgentEvent
import dev.phonepal.api.ChatResponse
import dev.phonepal.api.ChatRole
import dev.phonepal.api.ContentPart
import dev.phonepal.api.Decision
import dev.phonepal.api.Flow
import dev.phonepal.api.FlowStep
import dev.phonepal.api.Note
import dev.phonepal.api.Observation
import dev.phonepal.api.ToolCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhoneTaskTest {
    private val home = screen("com.android.launcher", "设置", "相机")
    private val settings = screen("com.android.settings", "WLAN", "蓝牙")
    private val wlan = screen("com.android.settings", "WLAN 开关", "已连接 Home")

    @Test
    fun phoneTaskFromChatSucceedsAndLearns() = runTest {
        val h = Harness(device = FakeDevice(home, settings, wlan))
        h.chat.tools(ToolCall("p1", AgentTools.PHONE_TASK, """{"goal": "打开设置里的 WLAN"}""")).reply("已经打开 WLAN 页面。")
        h.operator
            .reply(op("""{"type": "launch", "packageName": "com.android.settings"}"""))
            .reply(op("""{"type": "tap", "nodeId": 0, "reason": "进入 WLAN"}"""))
            .reply(op("""{"type": "finish", "success": true, "summary": "WLAN 页面已打开"}"""))

        h.agent.handle("帮我打开 WLAN 设置", h.events, h.stop)

        assertEquals(listOf(Action.LaunchApp("com.android.settings"), Action.Tap(nodeId = 0, reason = "进入 WLAN")), h.device.performed)
        val finished = h.events.of<AgentEvent.PhoneTaskFinished>().single()
        assertTrue(finished.success)
        assertEquals(2, finished.steps)
        val toolResult = h.chat.requests[1].messages.last { it.role == ChatRole.TOOL }.text
        assertTrue("\"success\":true" in toolResult && "WLAN 页面已打开" in toolResult)
        assertEquals("已经打开 WLAN 页面。", h.events.of<AgentEvent.Reply>().single().text)

        // Operator prompt: goal, node lines, screenshot, jsonMode, history of the earlier step.
        val req = h.operator.requests[1]
        assertTrue(req.jsonMode)
        val user = req.messages.last()
        assertTrue(user.parts.any { it is ContentPart.ImagePng })
        assertTrue("打开设置里的 WLAN" in user.text)
        assertTrue("[0] TextView \"WLAN\" (0,0,1000,90) clickable" in user.text)
        assertTrue("第 1 步：打开 com.android.settings → 成功" in user.text)

        val note = assertNotNull(h.memory.notes["howto/com.android.settings"])
        assertEquals("howto", note.type)
        assertEquals(listOf(Observation("path", "打开 com.android.settings → WLAN", listOf("com.android.settings"))), note.observations)
        assertTrue("- [path] 打开 com.android.settings → WLAN #com.android.settings" in note.body)
        assertEquals(listOf("howto/com.android.settings"), h.events.of<AgentEvent.MemoryUpdated>().map { it.permalink })

        val kinds = h.events.all.map { it::class.simpleName }
        assertEquals(listOf("PhoneTaskStarted", "StepPlanned", "StepExecuted", "StepPlanned", "StepExecuted", "StepPlanned", "MemoryUpdated", "PhoneTaskFinished", "Reply"), kinds)
    }

    @Test
    fun learningDedupesAndHowtoFeedsTheNextPrompt() = runTest {
        val h = Harness(device = FakeDevice(settings), skills = FakeSkills(skill("wifi", "打开 WLAN")))
        repeat(2) {
            h.operator.reply(op("""{"type": "tap", "nodeId": 0, "target": "WLAN"}""")).reply(op("""{"type": "finish", "success": true, "summary": "ok"}"""))
            assertTrue(h.agent.runPhoneTask("打开 WLAN", "wifi", h.events, h.stop).success)
        }
        val note = h.memory.notes["howto/wifi"]!!
        assertEquals(1, note.observations.size)
        assertEquals("WLAN #wifi".let { "- [path] $it" }, NoteText.observationLine(note.observations.single()))
        assertEquals(1, h.events.of<AgentEvent.MemoryUpdated>().size)
        assertEquals("wifi", h.events.of<AgentEvent.SkillChosen>().first().skillName)
        val secondTaskPrompt = h.operator.requests[2].messages.last().text
        assertTrue(Prompts.HOWTO_HEADER in secondTaskPrompt && "- [path] WLAN #wifi" in secondTaskPrompt)
        assertTrue("按步骤操作 wifi" in secondTaskPrompt)
    }

    @Test
    fun skillMemoryRefsAreLoaded() = runTest {
        val h = Harness(device = FakeDevice(settings), skills = FakeSkills(skill("taxi", memoryRefs = listOf("地址/家"))))
        h.memory.write(Note(title = "家", permalink = "地址/家", observations = listOf(Observation("address", "海淀区某某路"))))
        h.operator.reply(op("""{"type": "finish", "success": false, "summary": "不做"}"""))
        h.agent.runPhoneTask("打车回家", "taxi", h.events, h.stop)
        assertTrue("海淀区某某路" in h.operator.requests[0].messages.last().text)
    }

    @Test
    fun unknownSkillFails() = runTest {
        val h = Harness()
        val r = h.agent.runPhoneTask("x", "nope", h.events, h.stop)
        assertFalse(r.success)
        assertTrue(h.operator.requests.isEmpty())
    }

    @Test
    fun blockedStepIsReportedAndToldToTheModel() = runTest {
        val deny = Decision.Deny("支付需要用户亲自操作")
        val h = Harness(
            device = FakeDevice(screen("com.example.shop", "立即支付")),
            sentinel = FakeSentinel { a -> if (a.target?.contains("支付") == true) deny else Decision.Allow() },
        )
        h.operator
            .reply(op("""{"type": "tap", "nodeId": 0, "target": "立即支付"}"""))
            .reply(op("""{"type": "finish", "success": false, "summary": "需要用户支付"}"""))

        val r = h.agent.runPhoneTask("买一杯咖啡", null, h.events, h.stop)

        assertFalse(r.success)
        assertTrue(h.device.performed.isEmpty())
        val blocked = h.events.of<AgentEvent.StepBlocked>().single()
        assertEquals(deny, blocked.decision)
        assertTrue("支付需要用户亲自操作" in h.operator.requests[1].messages.last().text)
        assertTrue(h.memory.notes.isEmpty())
    }

    @Test
    fun stuckScreenWarnsOnceThenFails() = runTest {
        val h = Harness(device = FakeDevice(settings), config = AgentConfig(stuckRepeats = 3))
        h.operator.fallback = { ChatResponse(op("""{"type": "tap", "nodeId": 1}""")) }

        val r = h.agent.runPhoneTask("打开蓝牙", null, h.events, h.stop)

        assertFalse(r.success)
        assertEquals(Prompts.STUCK_FAILED, r.summary)
        assertEquals(4, h.device.performed.size)
        val warned = h.operator.requests.map { it.messages.last().text }.count { Prompts.STUCK_WARNING in it }
        assertEquals(1, warned)
    }

    @Test
    fun secureScreenAsksForTakeover() = runTest {
        val h = Harness(device = FakeDevice(screen("com.bank", secure = true), settings), users = FakeUsers("好了"))
        h.operator.reply(op("""{"type": "finish", "success": true, "summary": "完成"}"""))

        val r = h.agent.runPhoneTask("登录银行", null, h.events, h.stop)

        assertTrue(r.success)
        assertEquals(listOf(Prompts.SECURE_SCREEN to true), h.users.asked)
        assertTrue(h.events.of<AgentEvent.NeedsUser>().single().takeover)
        assertEquals(1, h.operator.requests.size)
        assertTrue("好了" in h.operator.requests[0].messages.last().text)
    }

    @Test
    fun askUserAnswerGoesIntoHistory() = runTest {
        val h = Harness(device = FakeDevice(settings), users = FakeUsers("选快车"))
        h.operator
            .reply(op("""{"type": "ask_user", "question": "要快车还是专车？"}"""))
            .reply(op("""{"type": "finish", "success": true, "summary": "ok"}"""))
        h.agent.runPhoneTask("打车", null, h.events, h.stop)
        assertEquals(listOf("要快车还是专车？" to false), h.users.asked)
        assertTrue("选快车" in h.operator.requests[1].messages.last().text)
    }

    @Test
    fun parseFailuresAreFedBackThenFail() = runTest {
        val h = Harness(device = FakeDevice(settings))
        h.operator.reply("点 WLAN 吧").reply(op("""{"type": "finish", "success": true, "summary": "ok"}"""))
        assertTrue(h.agent.runPhoneTask("看看", null, h.events, h.stop).success)
        assertTrue("无法解析" in h.operator.requests[1].messages.last().text)

        val h2 = Harness(device = FakeDevice(settings))
        h2.operator.fallback = { ChatResponse("不知道") }
        val r = h2.agent.runPhoneTask("看看", null, h2.events, h2.stop)
        assertFalse(r.success)
        assertEquals(Prompts.PARSE_FAILED, r.summary)
        assertEquals(OperatorLoop.PARSE_FAILURE_LIMIT, h2.operator.requests.size)
    }

    @Test
    fun unknownNodeIdIsRejected() = runTest {
        val h = Harness(device = FakeDevice(settings))
        h.operator.reply(op("""{"type": "tap", "nodeId": 99}""")).reply(op("""{"type": "finish", "success": true, "summary": "ok"}"""))
        h.agent.runPhoneTask("看看", null, h.events, h.stop)
        assertTrue(h.device.performed.isEmpty())
        assertTrue(Prompts.unknownNode(99) in h.operator.requests[1].messages.last().text)
    }

    @Test
    fun maxStepsExhausted() = runTest {
        // A different screen after every action, so it never counts as stuck.
        val h = Harness(device = FakeDevice(screen("a", "1"), screen("a", "2"), screen("a", "3"), screen("a", "4")), config = AgentConfig(maxSteps = 3))
        h.operator.fallback = { ChatResponse(op("""{"type": "swipe", "from": [500, 800], "to": [500, 200]}""")) }

        val r = h.agent.runPhoneTask("一直滑", null, h.events, h.stop)
        assertFalse(r.success)
        assertEquals(Prompts.MAX_STEPS, r.summary)
        assertEquals(3, r.steps)
        assertEquals(3, h.operator.requests.size)
    }

    @Test
    fun stopSignalEndsTheLoop() = runTest {
        val h = Harness(device = FakeDevice(home, settings))
        h.device.onPerform = { h.stop.value = true }
        h.operator.fallback = { ChatResponse(op("""{"type": "tap", "nodeId": 0}""")) }
        val r = h.agent.runPhoneTask("打开设置", null, h.events, h.stop)
        assertFalse(r.success)
        assertEquals(Prompts.STOPPED, r.summary)
        assertEquals(1, h.device.performed.size)
        assertEquals(1, h.operator.requests.size)
    }

    @Test
    fun operatorLlmErrorRetriesOnceThenFails() = runTest {
        val h = Harness(device = FakeDevice(settings))
        h.operator.fail().reply(op("""{"type": "finish", "success": true, "summary": "ok"}"""))
        assertTrue(h.agent.runPhoneTask("看看", null, h.events, h.stop).success)

        val h2 = Harness(device = FakeDevice(settings))
        h2.operator.fail().fail()
        val r = h2.agent.runPhoneTask("看看", null, h2.events, h2.stop)
        assertFalse(r.success)
        assertEquals("${Prompts.OPERATOR_LLM_FAILED}（错误码 503）", h2.events.of<AgentEvent.Failed>().single().message)
    }

    @Test
    fun deviceObserveErrorEndsTheTaskCleanly() = runTest {
        val h = Harness()
        h.device.observeError = IllegalStateException("无障碍服务未开启")
        h.chat.tools(ToolCall("p", AgentTools.PHONE_TASK, """{"goal": "打开设置"}""")).reply("请先开启无障碍服务。")

        h.agent.handle("打开设置", h.events, h.stop)

        assertEquals("无障碍服务未开启", h.events.of<AgentEvent.Failed>().single().message)
        val finished = h.events.of<AgentEvent.PhoneTaskFinished>().single()
        assertFalse(finished.success)
        assertEquals("无障碍服务未开启", finished.summary)
        assertTrue("\"success\":false" in h.chat.requests[1].messages.last().text)
        assertEquals("请先开启无障碍服务。", h.events.of<AgentEvent.Reply>().single().text)
    }

    @Test
    fun flowConfirmDeclinedStopsBeforeTapping() = runTest {
        val flow = Flow(listOf(FlowStep.Launch("com.example.app"), FlowStep.Confirm("确认呼叫快车？"), FlowStep.Tap("确认呼叫")))
        val h = Harness(device = FakeDevice(home), skills = FakeSkills(skill("taxi-home", flow = flow)), users = FakeUsers("不要了"))

        val r = h.agent.runPhoneTask("打车回家", "taxi-home", h.events, h.stop)

        assertFalse(r.success)
        assertEquals(Prompts.CONFIRM_DECLINED, r.summary)
        assertEquals(listOf<Action>(Action.LaunchApp("com.example.app", target = "打开 com.example.app")), h.device.performed)
        assertEquals(listOf("确认呼叫快车？" to false), h.users.asked)
        assertTrue(h.operator.requests.isEmpty())
        assertTrue(h.memory.notes.isEmpty())
    }

    @Test
    fun flowWithDeeplinkPlaceholdersConfirmTapAndAssert() = runTest {
        val flow = Flow(
            listOf(
                FlowStep.Deeplink("search"),
                FlowStep.Wait(500),
                FlowStep.Confirm("确认呼叫？"),
                FlowStep.Tap("确认呼叫"),
                FlowStep.Assert("正在呼叫"),
            ),
        )
        val taxi = skill("taxi-home", flow = flow, deeplinks = mapOf("search" to "amapuri://route/plan/?dname={destination}"))
        val h = Harness(device = FakeDevice(screen("com.example.app", "快车", "确认呼叫")), skills = FakeSkills(taxi), users = FakeUsers("好的"))
        h.operator
            .reply("""{"values": {"destination": "家 北京"}}""")
            .reply(op("""{"type": "click", "nodeId": 1, "target": "确认呼叫 按钮"}"""))
            .reply("""```json
{"ok": true, "reason": "显示正在呼叫"}
```""")

        val r = h.agent.runPhoneTask("打车回家", "taxi-home", h.events, h.stop)

        assertTrue(r.success, r.summary)
        assertEquals(3, r.steps)
        val deeplink = assertIs<Action.OpenDeeplink>(h.device.performed[0])
        assertEquals("amapuri://route/plan/?dname=%E5%AE%B6%20%E5%8C%97%E4%BA%AC", deeplink.uri)
        assertEquals(Action.Wait(500), h.device.performed[1])
        assertEquals(Action.Tap(nodeId = 1, target = "确认呼叫 按钮"), h.device.performed[2])
        assertEquals(h.device.performed, h.sentinel.authorized)
        assertTrue("「确认呼叫」" in h.operator.requests[1].messages.last().text)
        assertEquals("search → 确认呼叫 按钮", h.memory.notes["howto/taxi-home"]!!.observations.single().content)
    }

    @Test
    fun flowAssertFailureFailsTheTask() = runTest {
        val flow = Flow(listOf(FlowStep.Type("目的地", "{destination}"), FlowStep.Assert("行程已开始")))
        val h = Harness(device = FakeDevice(screen("com.example.app", "目的地")), skills = FakeSkills(skill("t", flow = flow)), users = FakeUsers("西单"))
        h.operator
            .reply("""{"values": {}}""")
            .reply(op("""{"type": "input", "text": "别的", "nodeId": 0}"""))
            .reply("""{"ok": false, "reason": "还在首页"}""")

        val r = h.agent.runPhoneTask("去西单", "t", h.events, h.stop)

        assertFalse(r.success)
        assertTrue("还在首页" in r.summary)
        assertEquals(listOf<Action>(Action.TypeText("西单", nodeId = 0)), h.device.performed)
        assertEquals(listOf(Prompts.askValue("destination") to false), h.users.asked)
    }
}
