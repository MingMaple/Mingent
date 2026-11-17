package com.zmf.agent.capability.tools.agent.subagent_fork

import com.zmf.agent.capability.runtime.ChatBridge
import com.zmf.agent.capability.subagent.SubAgentDeps
import com.zmf.agent.capability.subagent.SubAgentRunner
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.llm.ApiDefaults
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.infra.llm.ProviderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * subagent_fork：以父会话的一部分上下文启动子智能体。
 *
 * 与 subagent 的差别只在初始上下文：本工具按 inherit 取父会话的摘要或最近若干条正文，
 * 与 prompt 拼接后交给子智能体；并发闸、超时、系统提示词与结论上限一律沿用 [SubAgentRunner]。
 * 父会话消息取自 [ChatBridge] 的当前会话快照；该桥未绑定或会话无消息时不猜测，直接返回错误。
 */
class SubAgentForkTool : Tool {

    override val name = "subagent_fork"

    override val description =
        "以父会话的一部分上下文启动子智能体，适合需要承接当前对话背景的调研或实现任务。" +
            "不需要父会话上下文时用 subagent，已有子智能体续聊用 send_message。" +
            "继承正文上限 24000 字符，与 subagent 共用每会话 8 个名额；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "description": {
              "type": "string",
              "description": "子任务的短标题，必填且不超过 60 字，用于 list_agents 与日志"
            },
            "prompt": {
              "type": "string",
              "description": "交给子智能体的任务正文，必填且不超过 4000 字；只写本任务，继承的上下文由工具拼接"
            },
            "inherit": {
              "type": "string",
              "enum": ["none", "summary", "recent"],
              "description": "继承范围，取 none（只给 prompt）、summary（父会话摘要加最近 4 条消息）或 recent（最近 N 条正文）；默认 recent"
            },
            "recent_messages": {
              "type": "integer",
              "description": "inherit=recent 时继承的消息条数，范围 0–60，默认 20"
            },
            "model": {
              "type": "string",
              "description": "子任务使用的模型名，如 deepseek-flash；省略时继承父会话当前模型"
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
            "multi_turn": {
              "type": "boolean",
              "description": "为 true 时允许多轮续聊，默认 false；单轮任务结束后不能再发消息"
            }
          },
          "required": ["description", "prompt"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = false

    /** 与 [TaskTool][com.zmf.agent.capability.tools.agent.subagent.TaskTool] 同口径：可与其他子智能体并行。 */
    override val concurrencySafe = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val description = args.optString("description", "").trim()
            if (description.isEmpty() || description == "null") {
                return@withContext ToolResult.Error("[E_INVALID] description 不得为空：给出子任务的短标题后重试")
            }
            if (description.length > MAX_DESCRIPTION_CHARS) {
                return@withContext ToolResult.Error(
                    "[E_LIMIT] description 不得超过 " + MAX_DESCRIPTION_CHARS + " 字（当前 " +
                        description.length + " 字）：缩短后重试"
                )
            }
            val prompt = args.optString("prompt", "").trim()
            if (prompt.isEmpty() || prompt == "null") {
                return@withContext ToolResult.Error("[E_INVALID] prompt 不得为空：写清本任务的目标与验收标准后重试")
            }
            if (prompt.length > MAX_PROMPT_CHARS) {
                return@withContext ToolResult.Error(
                    "[E_LIMIT] prompt 不得超过 " + MAX_PROMPT_CHARS + " 字（当前 " + prompt.length + " 字）：" +
                        "可把背景交给 inherit 继承，prompt 只写任务本身"
                )
            }

            val inherit = optTextOrNull(args, "inherit") ?: INHERIT_RECENT
            if (inherit != INHERIT_NONE && inherit != INHERIT_SUMMARY && inherit != INHERIT_RECENT) {
                return@withContext ToolResult.Error(
                    "[E_INVALID] inherit 只能取 " + INHERIT_NONE + "、" + INHERIT_SUMMARY + " 或 " +
                        INHERIT_RECENT + "；改正后重试"
                )
            }
            val recent = args.optInt("recent_messages", DEFAULT_RECENT).coerceIn(0, MAX_RECENT)

            val thinking = optBooleanOrNull(args, "thinking")
            val effort = optTextOrNull(args, "thinking_effort")
            if (effort != null && !ApiDefaults.EFFORTS.contains(effort)) {
                return@withContext ToolResult.Error(
                    "[E_INVALID] thinking_effort 只能取 " + ApiDefaults.EFFORTS.joinToString("、") + "；改正后重试"
                )
            }
            val model = optTextOrNull(args, "model")
            val available = ProviderStore.current(ctx.context).models
            if (model != null && available.isNotEmpty() && !available.contains(model)) {
                return@withContext ToolResult.Error(
                    "[E_MODEL] 当前提供商不支持模型 " + model + "：可用模型为 " + available.joinToString("、") +
                        "，改用其中之一后重试"
                )
            }
            val multiTurn = args.optBoolean("multi_turn", false)

            val parentMessages = ChatBridge.messages()
            if (parentMessages.isEmpty() && inherit != INHERIT_NONE) {
                return@withContext ToolResult.Error(
                    "[E_NO_PARENT] 父会话没有可取的消息，无法继承上下文：" +
                        "改用 inherit=none 只交 prompt，或先在会话中发出消息"
                )
            }
            val summary = if (inherit == INHERIT_SUMMARY) contextSummary(ctx) else ""
            val inherited = inheritedText(inherit, parentMessages, summary, recent)
            if (inherit != INHERIT_NONE && inherited.isEmpty()) {
                return@withContext ToolResult.Error(
                    "[E_INHERIT_EMPTY] 可继承的正文为空（工具消息与工具调用不计入）：" +
                        "增大 recent_messages、改用 inherit=summary，或改用 inherit=none"
                )
            }
            val finalPrompt = if (inherited.isEmpty()) {
                prompt
            } else {
                INHERITED_HEADER + "\n" + inherited + "\n\n" + TASK_HEADER + "\n" + prompt
            }

            val provider = SubAgentDeps.provider?.invoke()
            val registry = SubAgentDeps.registry?.invoke()
            if (provider == null || registry == null) {
                return@withContext ToolResult.Error(
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
                prompt = finalPrompt,
                thinking = thinking,
                thinkingEffort = effort,
                model = model,
                multiTurn = multiTurn
            )
            if (!outcome.ok) {
                // 并发闸拒绝时尚未登记条目，id 为空串，据此折算为 E_LIMIT
                if (outcome.id.isBlank()) {
                    return@withContext ToolResult.Error("[E_LIMIT] " + outcome.answer)
                }
                return@withContext ToolResult.Error(outcome.answer)
            }
            // 单轮任务的 id 无法用于续聊，不写 agent_id 行
            val text = if (outcome.id.isBlank() || !multiTurn) {
                outcome.answer
            } else {
                outcome.answer + "\n\nagent_id=" + outcome.id
            }
            ToolResult.Ok(text)
        }

    /** 父会话的滚动摘要：登记表与消息桥都不持有会话元信息，只能按当前会话 id 读取落盘数据。 */
    private fun contextSummary(ctx: ToolContext): String {
        if (ctx.sessionId.isEmpty()) return ""
        return SessionStore.load(ctx.context)
            .firstOrNull { it.id == ctx.sessionId }
            ?.contextSummary
            .orEmpty()
    }

    /**
     * 按继承模式组装上下文正文：剔除工具消息与带工具调用的消息，只保留正文。
     *
     * summary 取会话摘要加最近 [SUMMARY_TURNS] 条；recent 取最近 [recent] 条；none 不继承。
     * 总字符受 [MAX_INHERIT_CHARS] 限制，超出时保留最近的部分。
     */
    private fun inheritedText(
        mode: String,
        messages: List<ChatMessage>,
        summary: String,
        recent: Int
    ): String {
        val parts = ArrayList<String>()
        when (mode) {
            INHERIT_SUMMARY -> {
                if (summary.isNotBlank()) parts.add("会话摘要：\n" + summary.trim())
                parts.addAll(turnLines(messages.takeLast(SUMMARY_TURNS)))
            }
            INHERIT_RECENT -> parts.addAll(turnLines(messages.takeLast(recent)))
            else -> return ""
        }
        return clip(parts.joinToString("\n\n"))
    }

    /** 消息正文折算为「角色：正文」；带 toolCalls 的 assistant 消息会产生对不上的调用 id，必须剔除。 */
    private fun turnLines(messages: List<ChatMessage>): List<String> {
        val lines = ArrayList<String>()
        for (message in messages) {
            if (message.role == "tool" || message.toolCalls.isNotEmpty()) continue
            val body = message.content.trim()
            if (body.isEmpty()) continue
            lines.add(roleLabel(message.role) + "：" + body)
        }
        return lines
    }

    private fun roleLabel(role: String): String = when (role) {
        "user" -> "用户"
        "assistant" -> "助手"
        "system" -> "系统"
        else -> role
    }

    /** 继承内容总字符上限：超长时保留最近的正文，并在开头注明截断。 */
    private fun clip(text: String): String {
        if (text.length <= MAX_INHERIT_CHARS) return text
        return "（继承内容过长，只保留最近 " + MAX_INHERIT_CHARS + " 字符）\n" + text.takeLast(MAX_INHERIT_CHARS)
    }

    /** 未传或传 null 均返回 null（即继承父会话）。 */
    private fun optBooleanOrNull(args: JSONObject, key: String): Boolean? =
        if (args.has(key) && !args.isNull(key)) args.getBoolean(key) else null

    /** 同上，字符串版本：空串按未传处理，避免空白参数被当作有效取值。 */
    private fun optTextOrNull(args: JSONObject, key: String): String? {
        if (!args.has(key) || args.isNull(key)) return null
        return args.getString(key).trim().takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val INHERIT_NONE = "none"
        const val INHERIT_SUMMARY = "summary"
        const val INHERIT_RECENT = "recent"

        const val MAX_DESCRIPTION_CHARS = 60
        const val MAX_PROMPT_CHARS = 4000
        const val DEFAULT_RECENT = 20
        const val MAX_RECENT = 60

        /** summary 模式固定附带的消息条数。 */
        const val SUMMARY_TURNS = 4

        /** 继承内容的字符上限：超出会显著挤占子智能体的上下文。 */
        const val MAX_INHERIT_CHARS = 24000

        const val INHERITED_HEADER = "【父会话上下文】"
        const val TASK_HEADER = "【任务】"
    }
}
