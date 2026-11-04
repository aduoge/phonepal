package dev.xiaoman.sentinel

import dev.xiaoman.api.Action
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalGateway
import dev.xiaoman.api.ApprovalRequest
import dev.xiaoman.api.ApprovalScope
import dev.xiaoman.api.AuditRecord
import dev.xiaoman.api.Bounds
import dev.xiaoman.api.Decision
import dev.xiaoman.api.GuardContext
import dev.xiaoman.api.Point
import dev.xiaoman.api.ScreenState
import dev.xiaoman.api.Skill
import dev.xiaoman.api.UiNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicySentinelTest {
    private class FakeGateway(var answer: (ApprovalRequest) -> ApprovalAnswer = { ApprovalAnswer(true) }) : ApprovalGateway {
        val requests = mutableListOf<ApprovalRequest>()
        override suspend fun request(request: ApprovalRequest): ApprovalAnswer {
            requests += request
            return answer(request)
        }
    }

    private val gateway = FakeGateway()
    private val records = mutableListOf<AuditRecord>()
    private val sentinel = PolicySentinel(gateway, { records += it }, clock = { 42L })

    private val didi = Skill(
        name = "taxi-home",
        description = "打车回家",
        apps = listOf("com.sdu.didi.psnger", "com.autonavi.minimap"),
        confirmBefore = listOf("确认叫车"),
        deeplinks = mapOf("search" to "amapuri://route/plan/?dname={destination}"),
        instructions = "",
        source = "test",
    )

    private fun screen(pkg: String? = "com.sdu.didi.psnger", nodes: List<UiNode> = emptyList(), secure: Boolean = false) =
        ScreenState(null, 1080, 2400, pkg, nodes = nodes, secure = secure)

    private fun ctx(skill: Skill? = didi, screen: ScreenState? = screen(), taskId: String = "t1", step: Int = 0) =
        GuardContext(taskId, "goal", skill, screen, step)

    private fun node(id: Int, text: String? = null, bounds: Bounds = Bounds(0, 0, 1000, 1000), desc: String? = null, editable: Boolean = false, focused: Boolean = false, password: Boolean = false) =
        UiNode(id = id, text = text, description = desc, bounds = bounds, editable = editable, focused = focused, password = password)

    // ---- deny ----

    @Test
    fun deniesTypingIntoPasswordNodeById() = runTest {
        val s = screen(nodes = listOf(node(0, "账号", editable = true), node(1, "密码", editable = true, password = true)))
        assertIs<Decision.Deny>(sentinel.guard(ctx(screen = s), Action.TypeText("secret", nodeId = 1)))
        assertIs<Decision.Allow>(sentinel.guard(ctx(screen = s), Action.TypeText("me", nodeId = 0)))
    }

    @Test
    fun deniesTypingIntoFocusedPasswordNode() = runTest {
        val s = screen(nodes = listOf(node(0, editable = true), node(1, editable = true, focused = true, password = true)))
        val d = sentinel.guard(ctx(screen = s), Action.TypeText("secret"))
        assertIs<Decision.Deny>(d)
        assertTrue("password" in d.reason)
    }

    @Test
    fun secureScreenAllowsOnlyNavigation() = runTest {
        val c = ctx(screen = screen(secure = true))
        assertIs<Decision.Deny>(sentinel.guard(c, Action.Tap(Point(10, 10))))
        assertIs<Decision.Deny>(sentinel.guard(c, Action.Swipe(Point(10, 10), Point(10, 500))))
        assertIs<Decision.Deny>(sentinel.guard(c, Action.LaunchApp("com.sdu.didi.psnger")))
        val d = sentinel.guard(c, Action.TypeText("x"))
        assertIs<Decision.Deny>(d)
        assertTrue("take over" in d.reason)
        assertIs<Decision.Allow>(sentinel.guard(c, Action.Back()))
        assertIs<Decision.Allow>(sentinel.guard(c, Action.Home()))
        assertIs<Decision.Allow>(sentinel.guard(c, Action.Wait()))
        assertIs<Decision.Allow>(sentinel.guard(c, Action.AskUser("请接管", takeover = true)))
    }

    @Test
    fun launchOutsideSkillAppsIsDenied() = runTest {
        assertIs<Decision.Deny>(sentinel.guard(ctx(), Action.LaunchApp("com.tencent.mm")))
        assertIs<Decision.Allow>(sentinel.guard(ctx(), Action.LaunchApp("com.autonavi.minimap")))
        assertIs<Decision.Allow>(sentinel.guard(ctx(), Action.LaunchApp("com.android.launcher3")))
        assertIs<Decision.Allow>(sentinel.guard(ctx(), Action.LaunchApp("dev.xiaoman.app")))
        // No apps listed, or no skill: unrestricted.
        assertIs<Decision.Allow>(sentinel.guard(ctx(skill = didi.copy(apps = emptyList())), Action.LaunchApp("com.tencent.mm")))
        assertIs<Decision.Allow>(sentinel.guard(ctx(skill = null), Action.LaunchApp("com.tencent.mm")))
    }

    @Test
    fun deeplinkToForeignPackageIsDenied() = runTest {
        val d = sentinel.guard(ctx(), Action.OpenDeeplink("amapuri://route/plan/?dname=家", packageName = "com.tencent.mm"))
        assertIs<Decision.Deny>(d)
    }

    @Test
    fun touchOnForeignScreenIsDenied() = runTest {
        val foreign = ctx(screen = screen(pkg = "com.tencent.mm"))
        assertIs<Decision.Deny>(sentinel.guard(foreign, Action.Tap(Point(1, 1))))
        assertIs<Decision.Deny>(sentinel.guard(foreign, Action.LongPress(nodeId = 0)))
        assertIs<Decision.Deny>(sentinel.guard(foreign, Action.TypeText("hi")))
        assertIs<Decision.Allow>(sentinel.guard(foreign, Action.Back()))
        assertIs<Decision.Allow>(sentinel.guard(ctx(screen = screen(pkg = "com.miui.home")), Action.Tap(Point(1, 1))))
        assertIs<Decision.Allow>(sentinel.guard(ctx(screen = screen(pkg = "dev.xiaoman.app")), Action.Tap(Point(1, 1))))
        val systemUi = ctx(screen = screen(pkg = "com.android.systemui"))
        assertIs<Decision.Deny>(sentinel.guard(systemUi, Action.Tap(Point(1, 1))))
        assertIs<Decision.Allow>(sentinel.guard(systemUi, Action.Home()))
    }

    @Test
    fun customLauncherAndSelfPackage() = runTest {
        val custom = PolicySentinel(gateway, { }, launcherPackages = setOf("my.launcher"), selfPackage = "my.self")
        assertIs<Decision.Allow>(custom.guard(ctx(), Action.LaunchApp("my.launcher")))
        assertIs<Decision.Allow>(custom.guard(ctx(), Action.LaunchApp("my.self")))
        assertIs<Decision.Deny>(custom.guard(ctx(), Action.LaunchApp("dev.xiaoman.app")))
    }

    // ---- ask: confirmation words ----

    @Test
    fun asksForConfirmationWordInTarget() = runTest {
        val d = sentinel.guard(ctx(), Action.Tap(Point(500, 900), target = "确认呼叫 按钮"))
        assertIs<Decision.Ask>(d)
        assertEquals("即将点击「确认呼叫 按钮」，是否继续？", d.prompt)
        assertTrue("确认呼叫" in d.reason)
    }

    @Test
    fun latinWordsMatchCaseInsensitivelyAtWordBoundaries() = runTest {
        assertIs<Decision.Ask>(sentinel.guard(ctx(), Action.Tap(Point(1, 1), target = "Place ORDER")))
        assertIs<Decision.Ask>(sentinel.guard(ctx(), Action.Tap(Point(1, 1), target = "Send")))
        assertIs<Decision.Ask>(sentinel.guard(ctx(), Action.Tap(Point(1, 1), target = "点击send按钮")))
        assertIs<Decision.Allow>(sentinel.guard(ctx(), Action.Tap(Point(1, 1), target = "border")))
        assertIs<Decision.Allow>(sentinel.guard(ctx(), Action.Tap(Point(1, 1), target = "sender name")))
        assertIs<Decision.Allow>(sentinel.guard(ctx(), Action.Tap(Point(1, 1), target = "Payment history")))
        assertIs<Decision.Allow>(sentinel.guard(ctx(), Action.Tap(Point(1, 1), target = "搜索")))
    }

    @Test
    fun resolvesNodeById() = runTest {
        val s = screen(nodes = listOf(node(0, "首页"), node(7, desc = "删除")))
        val d = sentinel.guard(ctx(screen = s), Action.LongPress(nodeId = 7))
        assertIs<Decision.Ask>(d)
        assertEquals("即将长按「删除」，是否继续？", d.prompt)
        assertIs<Decision.Allow>(sentinel.guard(ctx(screen = s), Action.Tap(nodeId = 0)))
    }

    @Test
    fun resolvesSmallestNodeContainingPoint() = runTest {
        val s = screen(
            nodes = listOf(
                node(0, "页面", Bounds(0, 0, 1000, 1000)),
                node(1, "底栏", Bounds(0, 800, 1000, 1000)),
                node(2, "立即支付", Bounds(600, 850, 950, 950)),
            ),
        )
        val d = sentinel.guard(ctx(screen = s), Action.Tap(Point(700, 900)))
        assertIs<Decision.Ask>(d)
        assertEquals("即将点击「立即支付」，是否继续？", d.prompt)
        assertTrue("立即支付" in d.reason)
        assertIs<Decision.Allow>(sentinel.guard(ctx(screen = s), Action.Tap(Point(100, 900))))
        assertIs<Decision.Allow>(sentinel.guard(ctx(screen = s), Action.Tap(Point(100, 100))))
    }

    @Test
    fun typeChecksTargetAndFieldButNotTypedText() = runTest {
        assertIs<Decision.Allow>(sentinel.guard(ctx(), Action.TypeText("请删除这条", submit = true)))
        val s = screen(nodes = listOf(node(3, desc = "发送消息", editable = true, focused = true)))
        val d = sentinel.guard(ctx(screen = s), Action.TypeText("你好", submit = true))
        assertIs<Decision.Ask>(d)
        assertEquals("即将在「发送消息」输入并提交，是否继续？", d.prompt)
        assertIs<Decision.Ask>(sentinel.guard(ctx(), Action.TypeText("hi", target = "send box")))
    }

    @Test
    fun skillAndExtraWordsAddToBuiltIns() = runTest {
        assertIs<Decision.Ask>(sentinel.guard(ctx(), Action.Tap(Point(1, 1), target = "确认叫车")))
        assertIs<Decision.Allow>(sentinel.guard(ctx(skill = null), Action.Tap(Point(1, 1), target = "确认叫车")))
        val extra = PolicySentinel(gateway, { }, extraConfirmWords = listOf("Checkout"))
        assertIs<Decision.Ask>(extra.guard(ctx(), Action.Tap(Point(1, 1), target = "checkout now")))
        // A skill with no confirm words (or blank ones) still gets the built-ins.
        val bare = didi.copy(confirmBefore = listOf("", " "))
        assertIs<Decision.Ask>(sentinel.guard(ctx(skill = bare), Action.Tap(Point(1, 1), target = "支付")))
        assertTrue(PolicySentinel.BUILT_IN_CONFIRM_WORDS.containsAll(listOf("支付", "order")))
    }

    // ---- ask: deeplinks ----

    @Test
    fun deeplinkMatchingSkillTemplateIsAllowed() = runTest {
        assertIs<Decision.Allow>(sentinel.guard(ctx(), Action.OpenDeeplink("amapuri://route/plan/?dname=家")))
        assertIs<Decision.Allow>(sentinel.guard(ctx(), Action.OpenDeeplink("AMAPURI://Route/other")))
        assertIs<Decision.Ask>(sentinel.guard(ctx(), Action.OpenDeeplink("amapuri://navi/?x=1")))
        assertIs<Decision.Ask>(sentinel.guard(ctx(), Action.OpenDeeplink("didi://route/plan")))
        assertIs<Decision.Ask>(sentinel.guard(ctx(), Action.OpenDeeplink("not a uri")))
    }

    @Test
    fun deeplinkWithoutSkillOrTemplatesAsks() = runTest {
        val d = sentinel.guard(ctx(skill = null), Action.OpenDeeplink("amapuri://route/plan/?dname=家"))
        assertIs<Decision.Ask>(d)
        assertTrue(d.prompt.startsWith("即将打开链接"))
        assertIs<Decision.Ask>(sentinel.guard(ctx(skill = didi.copy(deeplinks = emptyMap())), Action.OpenDeeplink("amapuri://route/plan")))
    }

    @Test
    fun plainActionsAreAllowed() = runTest {
        assertIs<Decision.Allow>(sentinel.guard(ctx(), Action.Swipe(Point(500, 800), Point(500, 200))))
        assertIs<Decision.Allow>(sentinel.guard(ctx(screen = null), Action.Tap(Point(1, 1), target = "搜索")))
        assertIs<Decision.Allow>(sentinel.guard(ctx(), Action.Finish(true, "done")))
    }

    // ---- authorize ----

    @Test
    fun authorizeAuditsAllowAndDenyWithoutAsking() = runTest {
        assertTrue(sentinel.authorize(ctx(step = 3), Action.Back()))
        assertFalse(sentinel.authorize(ctx(step = 4), Action.LaunchApp("com.tencent.mm")))
        assertTrue(gateway.requests.isEmpty())
        assertEquals(2, records.size)
        assertEquals(AuditRecord(42L, "t1", 3, Action.Back(), Decision.Allow(), null), records[0])
        assertIs<Decision.Deny>(records[1].decision)
        assertNull(records[1].approved)
        assertEquals(4, records[1].stepIndex)
    }

    @Test
    fun onceApprovalAsksEveryTime() = runTest {
        val tap = Action.Tap(Point(1, 1), target = "确认呼叫")
        assertTrue(sentinel.authorize(ctx(), tap))
        assertTrue(sentinel.authorize(ctx(), tap))
        assertEquals(2, gateway.requests.size)
        assertEquals("即将点击「确认呼叫」，是否继续？", gateway.requests[0].prompt)
        assertEquals("t1", gateway.requests[0].taskId)
        assertEquals(listOf<Boolean?>(true, true), records.map { it.approved })
    }

    @Test
    fun taskApprovalIsReusedForSameWordAndTaskOnly() = runTest {
        gateway.answer = { ApprovalAnswer(true, ApprovalScope.TASK) }
        assertTrue(sentinel.authorize(ctx(), Action.Tap(Point(1, 1), target = "发送")))
        gateway.answer = { ApprovalAnswer(false) }
        assertTrue(sentinel.authorize(ctx(), Action.Tap(Point(1, 1), target = "发送 按钮")))
        assertEquals(1, gateway.requests.size)
        // Different word, same task: asks again.
        assertFalse(sentinel.authorize(ctx(), Action.Tap(Point(1, 1), target = "删除")))
        // Same word, other task: asks again.
        assertFalse(sentinel.authorize(ctx(taskId = "t2"), Action.Tap(Point(1, 1), target = "发送")))
        assertEquals(3, gateway.requests.size)
        assertEquals(listOf<Boolean?>(true, true, false, false), records.map { it.approved })
        assertTrue(records.all { it.decision is Decision.Ask })
    }

    @Test
    fun endTaskForgetsTaskApprovals() = runTest {
        gateway.answer = { ApprovalAnswer(true, ApprovalScope.TASK) }
        val tap = Action.Tap(Point(1, 1), target = "支付")
        assertTrue(sentinel.authorize(ctx(), tap))
        sentinel.endTask("t1")
        gateway.answer = { ApprovalAnswer(false) }
        assertFalse(sentinel.authorize(ctx(), tap))
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun deniedTaskScopeIsNotRemembered() = runTest {
        gateway.answer = { ApprovalAnswer(false, ApprovalScope.TASK) }
        val tap = Action.Tap(Point(1, 1), target = "支付")
        assertFalse(sentinel.authorize(ctx(), tap))
        assertFalse(sentinel.authorize(ctx(), tap))
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun taskApprovalCoversSameDeeplinkHost() = runTest {
        gateway.answer = { ApprovalAnswer(true, ApprovalScope.TASK) }
        assertTrue(sentinel.authorize(ctx(skill = null), Action.OpenDeeplink("didi://a?x=1")))
        assertTrue(sentinel.authorize(ctx(skill = null), Action.OpenDeeplink("didi://a?x=2")))
        gateway.answer = { ApprovalAnswer(false) }
        assertFalse(sentinel.authorize(ctx(skill = null), Action.OpenDeeplink("didi://b")))
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun gatewayFailureMeansNotApproved() = runTest {
        gateway.answer = { error("ui gone") }
        assertFalse(sentinel.authorize(ctx(), Action.Tap(Point(1, 1), target = "支付")))
        assertEquals(false, records.single().approved)
    }

    @Test
    fun gatewayCancellationPropagates() = runTest {
        gateway.answer = { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { sentinel.authorize(ctx(), Action.Tap(Point(1, 1), target = "支付")) }
        assertEquals(false, records.single().approved)
    }
}
