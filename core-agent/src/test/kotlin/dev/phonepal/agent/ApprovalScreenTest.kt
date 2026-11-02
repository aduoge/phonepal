package dev.phonepal.agent

import dev.phonepal.api.Action
import dev.phonepal.api.AgentConfig
import dev.phonepal.api.Bounds
import dev.phonepal.api.Decision
import dev.phonepal.api.GuardContext
import dev.phonepal.api.Point
import dev.phonepal.api.ScreenState
import dev.phonepal.api.Sentinel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApprovalScreenTest {
    private val before = screen("com.MobileTicket", "G1639 二等座 87元 预订")
    private val tap = Action.Tap(point = Point(870, 820), target = "二等座预订")

    private suspend fun execute(after: ScreenState, stopDuringApproval: Boolean = false,
                                restored: ScreenState? = null): Pair<Executed, FakeDevice> {
        val device = FakeDevice(*listOfNotNull(after, restored).toTypedArray())
        val stop = Stop()
        val gate = object : Sentinel {
            override suspend fun guard(context: GuardContext, action: Action) = Decision.Ask("确认预订入口", "预订")
            override suspend fun authorize(context: GuardContext, action: Action): Boolean {
                stop.value = stopDuringApproval
                return true
            }
        }
        val loop = OperatorLoop(ScriptedLlm(), device, gate, FakeUsers(), AgentConfig())
        return loop.execute(TaskRun("approval", "测试入口", null, Events(), stop), tap, before) to device
    }

    @Test fun approvalInPhonePalCannotTapOldCoordinates() = runTest {
        val (result, device) = execute(screen("dev.phonepal.app", "允许一次"))
        assertIs<Executed.Blocked>(result)
        assertTrue(device.performed.isEmpty())
    }

    @Test fun changedPriceCannotReuseApproval() = runTest {
        val (result, device) = execute(screen("com.MobileTicket", "G1639 二等座 140元 预订"))
        assertIs<Executed.Blocked>(result)
        assertTrue(device.performed.isEmpty())
    }

    @Test fun movedTargetCannotReuseCoordinates() = runTest {
        val moved = ScreenState(before.screenshotPng, before.widthPx, before.heightPx,
            before.packageName, nodes = before.nodes.map { it.copy(bounds = Bounds(0, 300, 1000, 390)) })
        val (result, device) = execute(moved)
        assertIs<Executed.Blocked>(result)
        assertTrue(device.performed.isEmpty())
    }

    @Test fun unchangedScreenExecutesApprovedActionOnce() = runTest {
        val (result, device) = execute(before)
        assertIs<Executed.Done>(result)
        assertEquals(listOf<Action>(tap), device.performed)
        assertEquals(1, device.observations)
    }

    @Test fun waitsForAppSwitchBeforeUsingApprovedCoordinates() = runTest {
        val (result, device) = execute(screen("dev.phonepal.app", "允许一次"), restored = before)
        assertIs<Executed.Done>(result)
        assertEquals(listOf<Action>(tap), device.performed)
        assertEquals(2, device.observations)
    }

    @Test fun secureScreenDoesNotWaitForLaterReturn() = runTest {
        val (result, device) = execute(screen("com.MobileTicket", "受保护", secure = true), restored = before)
        assertIs<Executed.Blocked>(result)
        assertTrue(device.performed.isEmpty())
        assertEquals(1, device.observations)
    }

    @Test fun stopDuringApprovalPreventsAction() = runTest {
        val (result, device) = execute(before, stopDuringApproval = true)
        assertIs<Executed.Blocked>(result)
        assertTrue(device.performed.isEmpty())
    }

    @Test fun protectedScreenAfterApprovalPreventsAction() = runTest {
        val (result, device) = execute(screen("com.MobileTicket", "G1639 二等座 87元 预订", secure = true))
        assertIs<Executed.Blocked>(result)
        assertTrue(device.performed.isEmpty())
    }
}
