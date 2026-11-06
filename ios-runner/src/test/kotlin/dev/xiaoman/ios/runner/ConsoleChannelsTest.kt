package dev.xiaoman.ios.runner

import dev.xiaoman.api.Action
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalRequest
import dev.xiaoman.api.ApprovalScope
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import kotlin.test.*

class ConsoleChannelsTest {
    private val request = ApprovalRequest("t1", Action.Tap(nodeId = 3, target = "立即支付"), "点击「立即支付」", "确认词：支付")

    private fun channels(input: String, interactive: Boolean = true): Pair<ConsoleChannels, ByteArrayOutputStream> {
        val bytes = ByteArrayOutputStream()
        return ConsoleChannels(BufferedReader(StringReader(input)), PrintStream(bytes, true, Charsets.UTF_8), interactive) to bytes
    }

    @Test fun interactiveAnswersMapToApprovals() = runBlocking {
        val (c, out) = channels("y\nt\nn\n\n")
        assertEquals(ApprovalAnswer(true, ApprovalScope.ONCE), c.request(request))
        assertEquals(ApprovalAnswer(true, ApprovalScope.TASK), c.request(request))
        assertEquals(ApprovalAnswer(false), c.request(request))
        assertEquals(ApprovalAnswer(false), c.request(request))
        assertEquals(ApprovalAnswer(false), c.request(request)) // EOF
        assertTrue("需要确认：点击「立即支付」" in out.toString(Charsets.UTF_8))
    }

    @Test fun nonInteractiveDeniesAndAnswersFixedText() = runBlocking {
        val (c, out) = channels("y\n回答\n", interactive = false)
        assertEquals(ApprovalAnswer(false), c.request(request))
        assertEquals(ConsoleChannels.NOBODY_HERE, c.ask("t1", "要买哪个颜色？", takeover = false))
        assertTrue("已自动拒绝" in out.toString(Charsets.UTF_8))
    }

    @Test fun interactiveQuestionsReadOneLine() = runBlocking {
        val (c, out) = channels("黑色\n")
        assertEquals("黑色", c.ask("t1", "要买哪个颜色？", takeover = false))
        assertEquals(ConsoleChannels.NOBODY_HERE, c.ask("t1", "请完成验证码", takeover = true))
        assertTrue("请在手机上完成这一步" in out.toString(Charsets.UTF_8))
    }
}
