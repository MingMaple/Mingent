package com.zmf.agent.capability.tools.jobs

import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import org.json.JSONObject

/**
 * job_list：列出当前会话的后台任务，一行一条 `<id> [<kind>] <status> — <label>`。
 *
 * 只列本会话的（归属由 [JobRegistry] 按 sessionId 过滤），别的会话的任务既看不到也碰不到。
 * 语义对齐 DSH 的 `job_list`，输出格式让模型能一眼看出「哪个还活着、哪个已经结算」。
 */
class JobListTool : Tool {

    override val name = "job_list"

    override val description =
        "列出当前会话启动过的后台任务，一行一个：任务 id、类型、状态（running/completed/killed/failed）、" +
            "以及启动时的说明。状态还是 running 的可以用 job_output 读它的输出、用 job_kill 取消。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {},
          "required": []
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val entries = JobRegistry.forSession(ctx.sessionId)
        if (entries.isEmpty()) return ToolResult.Ok("（当前会话没有后台任务。用 job_start 起一个长任务，比如一条耗时的终端命令。）")
        val text = buildString {
            append("当前会话的后台任务共 ").append(entries.size).append(" 个：\n")
            for (e in entries) {
                append(e.id).append(" [").append(e.kind).append("] ")
                    .append(e.status.name.lowercase())
                if (e.label.isNotBlank()) append(" — ").append(e.label)
                append('\n')
            }
            append("读某个任务的输出用 job_output(job_id)，取消用 job_kill(job_id)。")
        }
        return ToolResult.Ok(text)
    }
}
