package com.zmf.agent.capability.tools.agent.list_agents

import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.PermissionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * list_agents：列出当前会话启动过的子智能体。
 *
 * 只读 [SubAgentRegistry] 的内存快照，不触发执行，也不修改任何记录。
 * 子智能体同步执行，调用返回时任务已经结束；本工具用于在多轮子智能体结束后找回它的 id。
 */
class ListAgentsTool : Tool {

    override val name = "list_agents"

    override val description =
        "列出当前会话启动过的子智能体，含 id、标题与状态，running 表示正在工作，inactive 表示已结束。" +
            "只做查看，不触发执行；需要续聊时用 send_message 并带上子智能体 id。" +
            "单次最多 50 条，只读档可用；登记表只在内存中，重启后为空，不得据此判断历史任务的执行情况。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {},
          "required": []
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val matched = SubAgentRegistry.snapshot().filter { it.sessionId == ctx.sessionId }
            if (matched.isEmpty()) return@withContext ToolResult.Ok("(no subagents)")

            val text = matched.sortedByDescending { it.startedAt }.take(MAX_LIMIT).joinToString("\n") { entry ->
                entry.id + " [" + (if (entry.state == SubAgentRegistry.RUNNING) "running" else "inactive") + "] — " +
                    oneLine(entry.description).ifBlank { "subagent" }
            }
            ToolResult.Ok(text)
        }

    private companion object {
        /** 单次最多列出的条数：登记表不落盘，条数有限，此处仅防止输出过长。 */
        const val MAX_LIMIT = 50
    }
}

/** 把多行文本压成一行：工具结果中每条记录只占一行。 */
private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()
