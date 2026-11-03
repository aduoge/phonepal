package dev.phonepal.ios.runner

import dev.phonepal.api.ApprovalAnswer
import dev.phonepal.api.ApprovalGateway
import dev.phonepal.api.ApprovalRequest
import dev.phonepal.api.ApprovalScope
import dev.phonepal.api.UserChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.PrintStream

/**
 * Approvals and questions on the terminal. Non-interactive runs (no console, or `--non-interactive`)
 * deny every approval and answer questions with a fixed "nobody is here" reply, so a scripted run can
 * never pay, send or delete on its own.
 */
class ConsoleChannels(
    private val input: BufferedReader,
    private val out: PrintStream,
    val interactive: Boolean,
) : ApprovalGateway, UserChannel {
    private val mutex = Mutex()

    override suspend fun request(request: ApprovalRequest): ApprovalAnswer = mutex.withLock {
        out.println("需要确认：${request.prompt}（${request.reason}）")
        if (!interactive) {
            out.println("已自动拒绝（非交互模式）。")
            return@withLock ApprovalAnswer(false)
        }
        out.print("[y=允许一次 / t=本任务内允许 / 其他=拒绝] > ")
        out.flush()
        when (readLine()?.trim()?.lowercase()) {
            "y", "yes" -> ApprovalAnswer(true, ApprovalScope.ONCE)
            "t" -> ApprovalAnswer(true, ApprovalScope.TASK)
            else -> ApprovalAnswer(false).also { out.println("已拒绝。") }
        }
    }

    override suspend fun ask(taskId: String, question: String, takeover: Boolean): String = mutex.withLock {
        out.println("PhonePal 问你：$question")
        if (!interactive) return@withLock NOBODY_HERE
        out.print(if (takeover) "请在手机上完成这一步，完成后按回车 > " else "你的回答 > ")
        out.flush()
        readLine() ?: NOBODY_HERE
    }

    private suspend fun readLine(): String? = withContext(Dispatchers.IO) { input.readLine() }

    companion object {
        const val NOBODY_HERE = "用户不在场，无法回答。请不要继续需要用户的步骤，用 finish 说明当前状态。"
    }
}
