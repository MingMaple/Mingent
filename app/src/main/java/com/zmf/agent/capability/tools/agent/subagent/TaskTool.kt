package com.zmf.agent.capability.tools.agent.subagent
import com.zmf.agent.core.llm.ApiDefaults

import com.zmf.agent.capability.subagent.SubAgentDeps
import com.zmf.agent.capability.subagent.SubAgentRunner
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import org.json.JSONObject

/**
 * subagent 工具：将一段可独立执行的任务交给子智能体，并获取其结论。
 *
 * provider 与 registry 在工具层无法获取，由 [SubAgentDeps] 在启动时注入；
 * 未注入时明确返回「子智能体未初始化」，不以成功回执掩盖失败。
 */
class TaskTool : Tool {

    override val name = "subagent"

    override val description =
        "把能独立完成的任务交给子智能体执行，它使用独立上下文与工具集，只回传结论。" +
            "步骤少或无需独立上下文的任务自行处理；需要父会话上下文用 subagent_fork，续聊用 send_message。" +
            "prompt 必须自包含；每会话最多 8 个，满了立即失败；单轮不能续聊，续聊传 multi_turn=true；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "description": {
              "type": "string",
              "description": "子任务的短标题，一句话，必填且非空，用于日志与错误提示"
            },
            "prompt": {
              "type": "string",
              "description": "交给子智能体的完整任务：背景、目标与验收标准，必填且必须自包含"
            },
            "thinking": {
              "type": "boolean",
              "description": "为 true 时开启思考；省略时继承父会话当前的思考开关"
            },
            "thinking_effort": {
              "type": "string",
              "enum": ["low", "high", "max"],
              "description": "思考强度，取 low、high 或 max；省略时继承父会话当前强度，仅在思考开启时生效"
            },
            "model": {
              "type": "string",
              "description": "子任务使用的模型名，如 deepseek-flash；省略时继承父会话当前模型"
            },
            "run_in_background": {
              "type": "boolean",
              "description": "为 true 时以后台方式提交，默认 false；手机端没有独立线程池，仍同步执行，仅回执带 [background] 前缀"
            },
            "multi_turn": {
              "type": "boolean",
              "description": "为 true 时允许多轮续聊，默认 false；单轮任务结束后不能再发消息"
            }
          },
          "required": ["description", "prompt"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    /**
     * 允许多个子智能体在同一步内并行：本工具只读父会话快照，全部副作用发生在各自独立的子循环，
     * 不写父会话正文，也不共享终端通道等有状态资源。
     */
    override val concurrencySafe = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val description = args.optString("description", "").trim()
        if (description.isEmpty() || description == "null") {
            return ToolResult.Error("[E_INVALID] description 不得为空：给出子任务的短标题后重试")
        }
        val prompt = args.optString("prompt", "").trim()
        if (prompt.isEmpty() || prompt == "null") {
            return ToolResult.Error("[E_INVALID] prompt 不得为空：把子智能体要独立完成的任务完整写入 prompt 后重试")
        }
        // 显式 null 也按 false 处理：org.json 无法区分「未传」与「传 null」，此处统一处理
        val background = args.optBoolean("run_in_background", false)
        val multiTurn = args.optBoolean("multi_turn", false)

        // 三个覆盖项：未传或传 null 一律视为 null（即继承父会话），不做猜测性兜底
        val thinking = optBooleanOrNull(args, "thinking")
        val effort = optTextOrNull(args, "thinking_effort")
        if (effort != null && !ApiDefaults.EFFORTS.contains(effort)) {
            return ToolResult.Error(
                "[E_INVALID] thinking_effort 只能取 " + ApiDefaults.EFFORTS.joinToString("、") + "；改正后重试"
            )
        }
        val model = optTextOrNull(args, "model")

        val provider = SubAgentDeps.provider?.invoke()
        val registry = SubAgentDeps.registry?.invoke()
        if (provider == null || registry == null) {
            return ToolResult.Error(
                "[E_SERVICE_UNAVAILABLE] 子智能体服务未初始化：该错误不能通过调整参数解决，请重启应用后重试"
            )
        }

        val outcome = SubAgentRunner.run(
            context = ctx.context,
            provider = provider,
            tools = registry,
            permission = ctx.permission,
            workspaceRoot = ctx.workspaceRoot,
            sessionId = ctx.sessionId,
            description = description,
            prompt = prompt,
            thinking = thinking,
            thinkingEffort = effort,
            model = model,
            multiTurn = multiTurn
        )
        val body = if (background) "[background] " + outcome.answer else outcome.answer
        // 末尾带回 id 供继续会话；被闸门拒绝时无记录，单轮任务返回的 id 也无法使用，两种情况均不写 agent_id 行
        val text = if (outcome.id.isBlank() || !multiTurn) body else body + "\n\nagent_id=" + outcome.id
        // 失败文本按 Error 返回给模型，界面上的工具卡才不会显示为「成功」
        return if (outcome.ok) ToolResult.Ok(text) else ToolResult.Error(text)
    }

    /** 未传或传 null 均返回 null（即继承父会话），由 Runner 在执行时读取。 */
    private fun optBooleanOrNull(args: JSONObject, key: String): Boolean? =
        if (args.has(key) && !args.isNull(key)) args.getBoolean(key) else null

    /** 同上，字符串版本：空串按未传处理，避免空白参数被当作有效覆盖。 */
    private fun optTextOrNull(args: JSONObject, key: String): String? {
        if (!args.has(key) || args.isNull(key)) return null
        return args.getString(key).trim().takeIf { it.isNotEmpty() }
    }
}
