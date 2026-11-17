package com.zmf.agent.capability.tools.shell.job_list

import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import org.json.JSONObject

/**
 * job_list：列出当前会话的后台任务，一行一条 `<id> [<kind>] <status> — <label>`。
 *
 * 只列本会话的任务（归属由 [JobRegistry] 按 sessionId 过滤），其他会话的任务不可见也不可操作。
 * 语义对齐 DSH 的 `job_list`，输出格式应便于模型判断任务的运行与结算状态。
 */
class JobListTool : Tool {

    override val name = "job_list"

    override val description =
        "列出当前会话的后台任务，一行一个：任务 id、类型、状态（running/completed/killed/failed）与启动说明。" +
            "其他会话的任务不可见，读取输出改用 job_output。" +
            "不需要任何参数，只读档可用。"

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
        if (entries.isEmpty()) return ToolResult.Ok("（当前会话没有后台任务。可用 job_start 启动一条耗时的终端命令。）")
        val text = buildString {
            append("当前会话的后台任务共 ").append(entries.size).append(" 个：\n")
            for (e in entries) {
                append(e.id).append(" [").append(e.kind).append("] ")
                    .append(e.status.name.lowercase())
                if (e.label.isNotBlank()) append(" — ").append(e.label)
                append('\n')
            }
            append("读某个任务的输出用 job_output，取消用 job_kill。")
        }
        return ToolResult.Ok(text)
    }
}
