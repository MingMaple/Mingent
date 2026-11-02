package com.zmf.agent.capability.tools.jobs

import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.infra.terminal.TerminalApi
import org.json.JSONObject

/**
 * job_kill：取消一个后台任务。
 *
 * 语义对齐 DSH：先把注册表中的任务标记为「已请求取消」，再向**整个进程组**发送终止信号
 * （`SIGTERM`，一秒后以 `SIGKILL` 兜底）；进程真正停止后，由生产方的 onExit 回调以
 * `killed` 结算。已结算的任务再次取消将返回「不在运行」。
 *
 * 为什么权限档是 WORKSPACE：终止一个正在跑的终端进程是有副作用的动作（可能打断正在进行的写入），
 * 与「只读」不符，按能力声明为 WORKSPACE。
 */
class JobKillTool : Tool {

    override val name = "job_kill"

    /** 取消正在运行的进程属于副作用。 */
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
        val stopped = JobRegistry.requestKill(id, reason)
        if (!stopped) {
            return ToolResult.Error("没能标记取消：" + id + "（可能已经结算；用 job_output 或 job_list 看当前状态）")
        }

        // 标记后立即向进程组发送信号：先以 SIGTERM 使命令自行收尾，一秒后以 SIGKILL 兜底
        val signalled = TerminalApi.killJob(ctx.context.applicationContext, id)
        return ToolResult.Ok(
            if (signalled) {
                "已向 " + id + " 的进程组发送终止信号。进程真正退出后任务以 killed 结算，" +
                    "可以用 job_output 或 job_list 确认。"
            } else {
                "已标记取消 " + id + "，但没能向进程发送终止信号：可能它已经自行退出，" +
                    "也可能 pid 文件尚未写出。用 job_list 看当前状态。"
            }
        )
    }
}
