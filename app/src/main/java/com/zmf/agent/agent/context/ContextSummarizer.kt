package com.zmf.agent.agent.context

import com.zmf.agent.agent.ApiConfig
import com.zmf.agent.agent.provider.ChatRequest
import com.zmf.agent.agent.provider.LlmEvent
import com.zmf.agent.agent.provider.LlmProvider
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeout

/**
 * 上下文摘要器：一次独立的、关闭思考的模型请求，把「最早的若干轮」压成一段《对话摘要》。
 *
 * 为什么不复用 AgentLoop：摘要不展示、不带工具、不留 reasoning；失败也只是少一段摘要，
 * 绝不能把主请求带崩（调用方失败即退兜底）。
 * 提示词结构借鉴 Operit 的四段式大纲与「自包含」要求，但按中文重写得短一些。
 */
object ContextSummarizer {

    private const val TAG = "context"

    /** 摘要请求超时：长对话要读几万字，60 秒还没回来就当作失败（走兜底，不拖住用户）。 */
    private const val TIMEOUT_MS = 60_000L

    /** 单条原文进摘要请求的上限：工具结果动辄上万字，全塞进去会先把摘要请求自己撑爆。 */
    private const val ENTRY_MAX_CHARS = 1_500

    /** DSH 的摘要输出上限默认值（65,536，含推理 token）：一份检查点允许写多长的官方上界。 */
    private const val DSH_DEFAULT_MAX_TOKENS = 65_536

    /**
     * 摘要不超过窗口的这个比例。
     *
     * DSH 的 65,536 是按它自己的窗口定的；我们 1M 窗口下直接照搬会宽到毫无约束，
     * 取窗口 2%（1M ≈ 20k）既够写完整检查点，也守住「摘要必须比被折叠的原文短」这个前提。
     */
    private const val SUMMARY_WINDOW_RATIO = 0.02f

    /** 超长摘要截断时插在中间的标记：让读的人知道这里少了一截，别把省略号当成内容。 */
    private const val TRUNCATE_MARK = "\n……（摘要超长，已截断中段）……\n"

    /**
     * 摘要输出上限（token）：min(DSH 默认 65536, 窗口的 2%)。
     *
     * 窗口取 [ApiConfig.CONTEXT_WINDOW]（全 App 只有一个口径），换更小的模型时它跟着变小。
     */
    fun maxOutputTokens(windowTokens: Int = ApiConfig.CONTEXT_WINDOW): Int {
        val byWindow = (windowTokens.coerceAtLeast(1) * SUMMARY_WINDOW_RATIO).toInt()
        return minOf(DSH_DEFAULT_MAX_TOKENS, byWindow.coerceAtLeast(1))
    }

    /**
     * 生成（滚动融合）摘要。
     *
     * @param provider 请求通道（当前 provider）
     * @param model 摘要模型；调用方已把空串换成当前对话模型
     * @param previous 上一条摘要，空串表示第一次摘要
     * @param transcript 本次要摘要的原文（只含 user/assistant/tool，按时间顺序）
     * @param maxTokens 摘要输出上限；默认按当前窗口算（见 [maxOutputTokens]）
     * @return 摘要正文；失败 / 超时 / 空结果一律返回 null（调用方据此退兜底）
     */
    suspend fun summarize(
        provider: LlmProvider,
        model: String,
        previous: String,
        transcript: List<ChatMessage>,
        /** 摘要输出上限；默认按当前窗口算，调用方一般不用传。 */
        maxTokens: Int = maxOutputTokens()
    ): String? {
        if (transcript.isEmpty()) return null
        if (model.isBlank()) {
            FileLog.log(TAG, "摘要跳过：模型名为空")
            return null
        }

        val systemBase = if (previous.isBlank()) {
            SYSTEM_PROMPT
        } else {
            SYSTEM_PROMPT + "\n\n上一次的摘要（其中的有效信息必须继承并融合进新摘要）：\n" + previous
        }
        // 长度预算写进提示词：不是所有 provider 都认 max_tokens，
        // 「提示词 + 请求上限 + 超长截断」三件一起才真的兜得住。这里「字」按 token 粗算（中文 1 字 ≈ 1 token）。
        val system = systemBase + "\n\n摘要正文控制在 " + maxTokens + " 字以内，超出部分会被截断。"

        // 原文按条编号投喂：模型引用「#7」比引用一长串原文更省 token、也更不容易串行
        var transcriptText = ""
        var index = 0
        for (message in transcript) {
            val text = message.content.trim()
            if (text.isEmpty()) continue
            index++
            transcriptText += "#" + index + " " + roleLabel(message.role) + "：" +
                (if (text.length > ENTRY_MAX_CHARS) text.take(ENTRY_MAX_CHARS) + "……（已截断）" else text) +
                "\n"
        }
        if (transcriptText.isBlank()) return null

        val user = "以下是需要总结的对话原文（按时间顺序编号，越靠后越新）：\n" + transcriptText +
            "\n请按上面要求的固定格式，把上面的原文与「上一次的摘要」融合成一份新的、更完整的摘要。"

        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = system),
                ChatMessage(role = "user", content = user)
            ),
            tools = emptyList(),
            // 摘要只要结果，不要思考：省 token，也避免 reasoning 把输出拖长
            thinking = false,
            // 官方把这个上限算上推理 token；我们关了思考，所以它就是正文的长度上限
            maxTokens = maxTokens
        )

        var failure: String? = null
        var output = ""
        try {
            withTimeout(TIMEOUT_MS) {
                provider.stream(request)
                    // 收到 Failed 就立刻停止收集，别把半截输出当摘要
                    .takeWhile { failure == null }
                    .collect { event ->
                        when (event) {
                            is LlmEvent.ContentDelta -> output += event.text
                            is LlmEvent.Failed -> {
                                failure = if (event.code > 0) {
                                    "HTTP " + event.code + "：" + event.message
                                } else {
                                    event.message
                                }
                            }

                            else -> {}
                        }
                    }
            }
        } catch (e: TimeoutCancellationException) {
            FileLog.log(TAG, "摘要超时（" + (TIMEOUT_MS / 1000) + " 秒），已放弃：" + transcript.size + " 条原文")
            return null
        } catch (e: CancellationException) {
            // 用户停止 / 退出：正常取消要往外传，不能吞掉
            throw e
        } catch (e: Exception) {
            FileLog.log(TAG, "摘要请求异常：" + e)
            return null
        }

        if (failure != null) {
            FileLog.log(TAG, "摘要请求失败：" + failure)
            return null
        }

        val raw = output.trim()
        if (raw.isEmpty()) {
            FileLog.log(TAG, "摘要结果为空，按失败处理")
            return null
        }
        val text = truncateSummary(raw, maxTokens)
        FileLog.log(
            TAG,
            "摘要完成：" + transcript.size + " 条原文 → " + text.length + " 字" +
                (if (text.length != raw.length) "（原始 " + raw.length + " 字，已截断到上限 " + maxTokens + "）" else "")
        )
        return text
    }

    /**
     * 超长摘要截断：头 60% + 尾 40%，砍掉中间。
     *
     * 为什么不是「只留尾部」：固定格式里【核心任务状态】在开头（续接任务第一眼要看它），
     * 【未完成事项】在结尾（下一步都在这儿），真正可以牺牲的是中间的【对话历程】。
     * 为什么用字符数兜底：中文 1 字 ≈ 1 token、英文 1 token ≈ 4 字符，按字符截对混排只会更保守
     * （截得更短），不会超出 token 上限。
     */
    private fun truncateSummary(text: String, maxTokens: Int): String {
        if (maxTokens <= 0 || text.length <= maxTokens) return text
        val room = (maxTokens - TRUNCATE_MARK.length).coerceAtLeast(2)
        val head = (room * 0.6f).toInt().coerceAtLeast(1)
        val tail = (room - head).coerceAtLeast(1)
        return text.take(head) + TRUNCATE_MARK + text.takeLast(tail)
    }

    private fun roleLabel(role: String): String = when (role) {
        "assistant" -> "助手"
        "tool" -> "工具结果"
        "system" -> "系统"
        else -> "用户"
    }

    /** 摘要指令：四段式固定格式 + 自包含 / 保原话 / 先看最近 30% 三条硬要求。 */
    private val SYSTEM_PROMPT = """
        你是负责生成对话摘要的助手。请把「上一次的摘要」（如果有）与「本次新增的对话原文」融合成一份全新的摘要：
        这份摘要会完全取代之前的摘要，成为你之后续接任务的唯一历史依据（原始对话不会再发给你）。

        必须严格按下面的固定格式输出，不得改动分隔线与【】标题：

        ==========对话摘要==========
        【核心任务状态】
        用户最新的需求是什么，现在走到哪一步：已完成的、正在做的、下一步。写清状态（已完成 / 进行中 / 等待中），列出未完成的依赖或还缺的输入。

        【对话历程与概要】
        按「做了什么 + 为什么 + 结果如何」写清主线演进，点名转折点、已解决的问题和已达成的共识。

        【关键信息与上下文】
        - 要求与背景：用户明确的约束、偏好、场景设定。
        - 具体对象：路径、文件名、接口、参数、命令、关键数值。
        - 结论与证据：验证过的结果、关键报错原话、产出物形态。

        【未完成事项】
        - 还需要做 / 需要确认的事，以及当前的卡点、风险或待用户决定的选择。
        ============================

        内容要求：
        1. 必须自包含：读的人完全没有原始对话，仅凭这份摘要也能准确知道背景、当前状态和下一步。
        2. 保留路径、文件名、命令、参数、数值和关键结论的原话，不要改写成模糊描述。
        3. 宁可长一点也不要丢信息，但不要复述寒暄、重复内容和已经作废的方案。
        4. 先看输入的最后 30%（最新进展），以最新内容为准；与更早内容冲突时采用新内容并说明变化。
        5. 上一次摘要里仍然有效的信息要继承进新摘要，不要丢。
    """.trimIndent()
}
