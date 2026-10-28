package com.zmf.agent.tools.agent

import com.zmf.agent.agent.ApiConfig
import com.zmf.agent.agent.subagent.SubAgentDeps
import com.zmf.agent.agent.subagent.SubAgentRunner
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolResult
import org.json.JSONObject

/**
 * subagent 工具：把一段能独立执行的任务交给子智能体，拿回它的结论。
 *
 * provider 与 registry 工具层拿不到，靠 [SubAgentDeps] 在启动时注入；
 * 没接线就明确报「子智能体未初始化」，不做「假装成功」的降级。
 */
class TaskTool : Tool {

    override val name = "subagent"

    override val description =
        "把一段能独立完成的任务交给子智能体执行（调研、查证、写代码、改文件等）。" +
            "子智能体有自己的上下文和工具，跑完只把结论交回来，不占用父会话的历史。" +
            "prompt 要写得自包含：子智能体看不到当前对话，把背景、目标、验收标准都写进去。" +
            "可选参数：thinking（思考开关）、thinking_effort（low/high/max）、model（模型名），" +
            "不传都默认继承父会话当前设置。" +
            "multi_turn（是否多轮，默认 false）：单轮子智能体执行完不能再发消息，需要后续追问时传 true。" +
            "可以同时开多个子任务，但同时最多 2 个在跑，拿不到名额会直接报错，等已有子任务结束再试。" +
            "多轮任务的执行结果末尾会有一行 agent_id=，之后可用 send_message 让它继续。" +
            "两三步就能做完的事不要拆出去：自己做完更快，也没有交接损耗。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "description": {
              "type": "string",
              "description": "子任务的短标题（一句话），用于日志与错误提示"
            },
            "prompt": {
              "type": "string",
              "description": "交给子智能体的完整任务：背景、目标、要产出什么，必须自包含"
            },
            "thinking": {
              "type": "boolean",
              "description": "这个子任务是否开启思考；不传默认继承父会话当前的思考开关（不是固定值）"
            },
            "thinking_effort": {
              "type": "string",
              "enum": ["low", "high", "max"],
              "description": "思考强度；不传默认继承父会话当前的强度。只在思考开启时生效"
            },
            "model": {
              "type": "string",
              "description": "这个子任务用哪个模型（如 deepseek-flash）；不传默认父会话当前模型"
            },
            "run_in_background": {
              "type": "boolean",
              "description": "是否以后台方式提交，默认 false；手机端没有独立线程池，仍会如实执行，只是返回文本会带 [background] 前缀"
            },
            "multi_turn": {
              "type": "boolean",
              "description": "是否多轮，默认 false：单轮子智能体执行完不能再发消息；需要后续追问时传 true"
            }
          },
          "required": ["description", "prompt"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val description = args.optString("description", "").trim()
        if (description.isEmpty() || description == "null") {
            return ToolResult.Error("缺少 description 参数：给这个子任务起一个短标题")
        }
        val prompt = args.optString("prompt", "").trim()
        if (prompt.isEmpty() || prompt == "null") {
            return ToolResult.Error("缺少 prompt 参数：把子智能体要独立完成的任务完整写进 prompt")
        }
        // 显式 null 也按 false：org.json 区分不了「没传」和「传了 null」，这里统一兜住
        val background = args.optBoolean("run_in_background", false)
        val multiTurn = args.optBoolean("multi_turn", false)

        // 三个覆盖项：没传/传 null 一律当 null（= 继承父会话），不做任何猜测性兜底
        val thinking = optBooleanOrNull(args, "thinking")
        val effort = optTextOrNull(args, "thinking_effort")
        if (effort != null && !ApiConfig.EFFORTS.contains(effort)) {
            return ToolResult.Error("thinking_effort 只能是 " + ApiConfig.EFFORTS.joinToString(" / "))
        }
        val model = optTextOrNull(args, "model")

        val provider = SubAgentDeps.provider?.invoke()
        val registry = SubAgentDeps.registry?.invoke()
        if (provider == null || registry == null) {
            return ToolResult.Error("子智能体未初始化")
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
        // 末尾带回 id 供续聊；被闸拒掉没有记录，单轮的给了 id 也用不上，两种都不写 agent_id 行
        val text = if (outcome.id.isBlank() || !multiTurn) body else body + "\n\nagent_id=" + outcome.id
        // 失败文本按 Error 回给模型，界面上工具卡才不会显示成「成功」
        return if (outcome.ok) ToolResult.Ok(text) else ToolResult.Error(text)
    }

    /** 没传或传了 null 都返回 null（= 继承父会话），交给 Runner 现读。 */
    private fun optBooleanOrNull(args: JSONObject, key: String): Boolean? =
        if (args.has(key) && !args.isNull(key)) args.getBoolean(key) else null

    /** 同上，字符串版本：空串也当没传，免得把空白参数当成有效覆盖。 */
    private fun optTextOrNull(args: JSONObject, key: String): String? {
        if (!args.has(key) || args.isNull(key)) return null
        return args.getString(key).trim().takeIf { it.isNotEmpty() }
    }
}
