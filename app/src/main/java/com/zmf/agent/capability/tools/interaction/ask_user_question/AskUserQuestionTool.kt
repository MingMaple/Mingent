package com.zmf.agent.capability.tools.interaction.ask_user_question

import com.zmf.agent.capability.runtime.AskAnswer
import com.zmf.agent.capability.runtime.AskBridge
import com.zmf.agent.capability.runtime.AskOption
import com.zmf.agent.capability.runtime.AskQuestion
import com.zmf.agent.capability.runtime.AskRequest
import com.zmf.agent.capability.runtime.AskResponse
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.PermissionLevel
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 向用户提问并等待作答。
 *
 * 问题以结构化选项呈现，模型只能在无法从上下文推断且确需用户决定时调用。
 * **默认一直等待**，只有显式给了 timeout 才受时限约束；超时、取消与无界面绑定均给出可区分的回执，
 * 不吞掉协程取消。子智能体没有交互界面，调用一律拒绝。
 */
class AskUserQuestionTool : Tool {

    override val name = "ask_user_question"

    override val description =
        "向用户提出 1–4 个带选项的问题并等待作答，仅在无法从上下文推断且必须由用户决定时使用。" +
            "同一会话已有未决提问时不得重复提问；子智能体没有界面，不能调用。" +
            "默认一直等待，timeout 取 1–600 秒或 -1；超时后提问卡关闭，需要作答须重新调用；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "questions": {
              "type": "array",
              "description": "问题列表，1–4 项；必填，无默认值",
              "minItems": 1,
              "maxItems": 4,
              "items": {
                "type": "object",
                "properties": {
                  "id": {"type": "string", "description": "问题标识，须匹配 [A-Za-z0-9_-]{1,32}，同一请求内不得重复；必填，无默认值"},
                  "question": {"type": "string", "description": "问题正文，不超过 500 字；必填，无默认值"},
                  "header": {"type": "string", "description": "界面短标题，不超过 12 字；省略时取问题正文前 12 字"},
                  "options": {
                    "type": "array",
                    "description": "可选项，2–8 项；必填，无默认值",
                    "minItems": 2,
                    "maxItems": 8,
                    "items": {
                      "type": "object",
                      "properties": {
                        "label": {"type": "string", "description": "选项标签，不超过 60 字；必填，无默认值"},
                        "description": {"type": "string", "description": "选项说明，不超过 120 字；默认空"}
                      },
                      "required": ["label"]
                    }
                  },
                  "multi_select": {"type": "boolean", "description": "为 true 时允许多选；默认 false"},
                  "allow_custom": {"type": "boolean", "description": "为 true 时允许自由文本作答；默认 true"}
                },
                "required": ["id", "question", "options"]
              }
            },
            "timeout": {"type": "number", "description": "等待作答的秒数，取 1–600 或 -1（一直等待）；省略即一直等待，无默认时限"}
          },
          "required": ["questions"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = false

    /**
     * 不限总闸：本工具要等用户当场作答，等待时长由提问自带的 `timeout` 与界面倒计时决定。
     *
     * 设成 0 是硬要求：执行入口若给它加统一时限，用户还在看题就会被判成超时，
     * 提问卡与工具回执从此对不上（见 `Tool.timeoutMs`）。
     */
    override val timeoutMs: Long = 0L

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        // 子智能体没有提问卡可挂：端口即便绑定，卡片也只会落在父会话页上，子智能体永远等不到作答
        if (ctx.isSubAgent) {
            return ToolResult.Error(
                "[E_NO_UI] 子智能体没有可与用户交互的界面，不能向用户提问；" +
                    "请按最保守假设继续，或把需要用户决定的问题写进结论交给父智能体转达。"
            )
        }
        val questions = parseQuestions(args)
            ?: return ToolResult.Error("[E_INVALID] " + INVALID_HINT + "；请按此要求修正后重试。")
        // 缺省不限制等待：只有显式给了 timeout（或旧的 timeout_seconds）才受时限约束
        val rawTimeout = when {
            args.has("timeout") && !args.isNull("timeout") -> args.optInt("timeout", -1)
            args.has("timeout_seconds") && !args.isNull("timeout_seconds") -> args.optInt("timeout_seconds", -1)
            else -> -1
        }
        if (rawTimeout != -1 && rawTimeout !in 1..MAX_TIMEOUT_SECONDS) {
            return ToolResult.Error(
                "[E_INVALID] timeout 必须为 -1（一直等待）或 1–" + MAX_TIMEOUT_SECONDS + " 秒；省略即一直等待，请修正后重试。"
            )
        }
        val waitMillis = if (rawTimeout < 0) null else rawTimeout.toLong() * 1000L

        val sessionId = ctx.sessionId
        val request = AskRequest(
            token = UUID.randomUUID().toString(),
            sessionId = sessionId,
            questions = questions,
            timeoutMs = waitMillis
        )
        val response = try {
            // 时限由界面侧执行：用户点「延长 1 分钟」改的是同一份截止时刻，
            // 工具若在这里再套一层 withTimeout，延长就会被这层先掐断
            AskBridge.ask(request)
        } catch (e: IllegalStateException) {
            return ToolResult.Error(
                "[E_ASK_PENDING] 当前会话已有未决提问；请等待用户回答或取消后再提问。"
            )
        } catch (e: CancellationException) {
            // 用户停止生成时先撤销界面上的提问卡，再把取消原样抛出；不得把取消折算为普通错误
            AskBridge.cancel(sessionId, "用户停止生成")
            throw e
        }
        if (response == null) {
            // 端口未绑定（没有界面）、超时、用户关掉面板三者都走到这里，回执必须分清：
            // 「界面尚未绑定」是环境问题，「限时到了」与「用户关闭」是两种不同的用户行为。
            val port = AskBridge.port ?: return noUi()
            return if (port.settlementOf(sessionId) == AskBridge.AskSettlement.TIMEOUT) {
                timedOut(rawTimeout)
            } else {
                closedByUser()
            }
        }
        return answerResult(response)
    }

    /**
     * 解析并校验问题列表；任一项不合规时返回 null。
     *
     * 校验在发起请求前完成：参数错误不得走到界面，否则用户会看到一道无法作答的题目。
     */
    private fun parseQuestions(args: JSONObject): List<AskQuestion>? {
        val raw = args.optJSONArray("questions") ?: return null
        if (raw.length() !in MIN_QUESTIONS..MAX_QUESTIONS) return null
        val questions = ArrayList<AskQuestion>(raw.length())
        val ids = HashSet<String>()
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: return null
            val id = item.optString("id", "").trim()
            if (!ID_PATTERN.matches(id) || !ids.add(id)) return null
            val question = item.optString("question", "").trim()
            if (question.isEmpty() || question.length > MAX_QUESTION_CHARS) return null
            val header = item.optString("header", "").trim().ifEmpty {
                question.take(MAX_HEADER_CHARS)
            }
            if (header.length > MAX_HEADER_CHARS) return null
            val options = parseOptions(item.optJSONArray("options")) ?: return null
            questions.add(
                AskQuestion(
                    id = id,
                    question = question,
                    header = header,
                    options = options,
                    multiSelect = item.optBoolean("multi_select", false),
                    allowCustom = item.optBoolean("allow_custom", true)
                )
            )
        }
        return questions
    }

    private fun parseOptions(raw: JSONArray?): List<AskOption>? {
        if (raw == null || raw.length() !in MIN_OPTIONS..MAX_OPTIONS) return null
        val options = ArrayList<AskOption>(raw.length())
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: return null
            val label = item.optString("label", "").trim()
            if (label.isEmpty() || label.length > MAX_LABEL_CHARS) return null
            val description = item.optString("description", "").trim()
            if (description.length > MAX_DESCRIPTION_CHARS) return null
            options.add(AskOption(label, description))
        }
        return options
    }

    private fun answerResult(response: AskResponse): ToolResult {
        val answers = JSONArray()
        for (answer in response.answers) answers.put(answerJson(answer))
        return ToolResult.Ok(JSONObject().put("answers", answers).toString())
    }

    /** 只输出官方字段：id、selected，以及存在自由文本时的 custom；被跳过的回答不写 custom。 */
    private fun answerJson(answer: AskAnswer): JSONObject {
        val selected = JSONArray()
        for (label in answer.selected) selected.put(label)
        val json = JSONObject()
            .put("id", answer.id)
            .put("selected", selected)
        if (answer.custom != null) json.put("custom", answer.custom)
        return json
    }

    /**
     * 超时回执。
     *
     * 超时后提问卡已被撤销，界面上不再保留任何可作答的入口；模型若仍需要用户决定，
     * 只能再次调用本工具重新提问，因此回执里必须说清这一点。
     */
    private fun timedOut(seconds: Int): ToolResult =
        ToolResult.Ok(
            JSONObject()
                .put("timed_out", true)
                .put("pending", true)
                .put("answers", JSONArray())
                .put(
                    "note",
                    "用户未在 " + seconds + " 秒内回答，提问卡已关闭且界面不会保留任何重新作答的入口。" +
                        "如仍需用户决定，请再次调用 ask_user_question 重新提问；否则按最保守假设继续并说明阻塞。"
                )
                .toString()
        )

    private fun noUi(): ToolResult =
        ToolResult.Error("[E_NO_UI] 界面尚未绑定提问通道，无法显示提问；请在界面可用后重试。")

    /**
     * 用户主动关闭面板：等待已结算但没有作答。
     *
     * 与超时同属「问过、没答」，区别只在原因；两者都不算答复，界面也不保留任何重答入口，
     * 模型若仍需用户决定只能重新调用本工具。
     */
    private fun closedByUser(): ToolResult =
        ToolResult.Ok(
            JSONObject()
                .put("cancelled", true)
                .put("pending", true)
                .put("answers", JSONArray())
                .put(
                    "note",
                    "用户关闭了提问面板，没有作答。如仍需用户决定，请再次调用 ask_user_question 重新提问；" +
                        "否则按最保守假设继续并说明阻塞。"
                )
                .toString()
        )

    private companion object {
        const val MIN_QUESTIONS = 1
        const val MAX_QUESTIONS = 4
        const val MAX_QUESTION_CHARS = 500
        const val MAX_HEADER_CHARS = 12
        const val MIN_OPTIONS = 2
        const val MAX_OPTIONS = 8
        const val MAX_LABEL_CHARS = 60
        const val MAX_DESCRIPTION_CHARS = 120
        const val MAX_TIMEOUT_SECONDS = 600

        val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,32}")

        const val INVALID_HINT =
            "questions 必须为 1–4 项，每项含 id（[A-Za-z0-9_-]{1,32} 且不重复）、" +
                "question（不超过 500 字）、header（不超过 12 字）与 2–8 个选项" +
                "（每项 label 不超过 60 字、description 不超过 120 字）"
    }
}
