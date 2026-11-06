package dev.xiaoman.ios.runner

import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalGateway
import dev.xiaoman.api.ApprovalRequest
import dev.xiaoman.api.ApprovalScope
import dev.xiaoman.api.ToolApprovalGateway
import dev.xiaoman.api.ToolApprovalRequest
import dev.xiaoman.api.UserChannel
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
) : ApprovalGateway, ToolApprovalGateway, UserChannel {
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

    override suspend fun approve(request: ToolApprovalRequest): Boolean = mutex.withLock {
        val args = request.argumentsJson.let { if (it.length > 300) it.take(300) + "…" else it }
        out.println("需要确认：调用「${request.title ?: request.tool}」（${request.server}），参数 $args（${request.reason}）")
        if (!interactive) {
            out.println("已自动拒绝（非交互模式）。")
            return@withLock false
        }
        out.print("[y=允许 / 其他=拒绝] > ")
        out.flush()
        (readLine()?.trim()?.lowercase() in setOf("y", "yes")).also { if (!it) out.println("已拒绝。") }
    }

    override suspend fun ask(taskId: String, question: String, takeover: Boolean): String = mutex.withLock {
        out.println("小满问你：$question")
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
