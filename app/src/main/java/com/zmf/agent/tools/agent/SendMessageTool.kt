package com.zmf.agent.tools.agent

import com.zmf.agent.agent.subagent.SubAgentDeps
import com.zmf.agent.agent.subagent.SubAgentRunner
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolResult
import org.json.JSONObject

/**
 * send_message 工具：给已经跑完的子智能体追加一条消息，拿回它的新结论。
 *
 * 和 subagent 工具的区别是「接着原来的上下文跑」：子智能体的历史对话存在
 * [com.zmf.agent.agent.subagent.SubAgentRegistry] 里，App 重启后登记表清空，续聊就无从谈起。
 * provider 与 registry 同样靠 [SubAgentDeps] 注入，没接线直接报错。
 */
class SendMessageTool : Tool {

    override val name = "send_message"

    override val description =
        "给已存在的子智能体追加一条消息并拿回它的新结论；agent_id 来自 subagent 工具结果里那行 agent_id=。" +
            "子智能体会带着之前的上下文继续干，适合在原任务上追加要求、接着查或接着改。" +
            "只能发一条消息、拿一份结论；要它汇报详细过程就在 message 里写清楚。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "agent_id": {
              "type": "string",
              "description": "目标子智能体的 id，取自 subagent 工具结果末尾的 agent_id= 那一行"
            },
            "message": {
              "type": "string",
              "description": "追加给子智能体的消息：要它接着做什么、还差什么，必须自包含"
            }
          },
          "required": ["agent_id", "message"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val id = args.optString("agent_id", "").trim()
        if (id.isEmpty() || id == "null") {
            return ToolResult.Error("缺少 agent_id 参数：它来自 subagent 工具结果末尾的 agent_id= 那一行")
        }
        val message = args.optString("message", "").trim()
        if (message.isEmpty() || message == "null") {
            return ToolResult.Error("缺少 message 参数：把要追加给子智能体的内容写进 message")
        }

        val provider = SubAgentDeps.provider?.invoke()
        val registry = SubAgentDeps.registry?.invoke()
        if (provider == null || registry == null) {
            return ToolResult.Error("子智能体未初始化")
        }

        val outcome = SubAgentRunner.resume(
            context = ctx.context,
            id = id,
            text = message,
            provider = provider,
            tools = registry,
            permission = ctx.permission,
            workspaceRoot = ctx.workspaceRoot,
            sessionId = ctx.sessionId
        )
        return if (outcome.ok) ToolResult.Ok(outcome.answer) else ToolResult.Error(outcome.answer)
    }
}
