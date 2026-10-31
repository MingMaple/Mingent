package com.zmf.agent.capability.tools.jobs

import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import org.json.JSONObject

/**
 * job_kill：请求取消一个后台任务。
 *
 * 语义对齐 DSH：请求取消后，**进程真正停掉**才以 `killed` 结算（由生产方的 onExit 回调负责），
 * 这里只是发停止信号。已经结算的任务再 kill 就报「不在运行」。
 *
 * 为什么权限档是 WORKSPACE：取消一个正在跑的终端进程是有副作用的动作（可能打断正在进行的写入），
 * 与「只读」不符，按能力声明为 WORKSPACE。
 */
class JobKillTool : Tool {

    override val name = "job_kill"

    /** 取消在跑的进程算副作用。 */
    override val mutates = true

    override val description =
        "请求取消一个还在运行的后台任务（比如一条跑个不停的终端命令）。进程真正停止后任务才以 killed 结算。" +
            "已经结束的任务不需要取消；reason 是可选的一句话，说明为什么取消。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "job_id": {"type": "string", "description": "要取消的后台任务 id，见 job_list"},
            "reason": {"type": "string", "description": "可选：为什么取消，留空也可以"}
          },
          "required": ["job_id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val id = args.optString("job_id").trim()
        if (id.isEmpty()) return ToolResult.Error("缺少 job_id：要取消哪个任务，见 job_list")

        val entry = JobRegistry.find(id)
            ?: return ToolResult.Error("没有这个后台任务：" + id + "（用 job_list 看当前有哪些）")
        if (entry.sessionId != ctx.sessionId) {
            return ToolResult.Error("这个任务不属于当前会话，无法取消：" + id)
        }
        if (entry.status != JobRegistry.Status.RUNNING) {
            return ToolResult.Error("这个任务已经不在运行（当前状态 " + entry.status.name.lowercase() + "），无需取消")
        }

        val reason = args.optString("reason", "").trim()
        val stopped = JobRegistry.requestKill(id)
        return if (stopped) {
            ToolResult.Ok("已请求取消 " + id + "，进程停止后任务会以 killed 结算。")
        } else {
            // 进程句柄缺失（可能刚结束还没结算）：如实说明，让模型再读一次状态
            ToolResult.Error("没能向进程发出停止信号：" + id + "（可能刚结束；用 job_output 或 job_list 看当前状态）")
        }
    }
}
