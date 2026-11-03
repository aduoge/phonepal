package dev.phonepal.mcp

import dev.phonepal.api.ToolApprovalGateway
import dev.phonepal.api.ToolApprovalRequest
import dev.phonepal.api.UserChannel

class UserChannelApprovals(private val users: UserChannel) : ToolApprovalGateway {
    override suspend fun approve(request: ToolApprovalRequest): Boolean {
        val arguments = request.argumentsJson.let { if (it.length > 1000) it.take(1000) + "[参数已截断]" else it }
        val reply = users.ask(request.taskId,
            "要调用「${request.title ?: request.tool}」（${request.server}），参数：$arguments。${request.reason}。回复“确认”继续，其他回复取消。",
            takeover = false)
        return reply.trim().lowercase() in setOf("确认", "是", "好", "可以", "y", "yes")
    }
}
