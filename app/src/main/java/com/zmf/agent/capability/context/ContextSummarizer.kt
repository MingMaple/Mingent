package com.zmf.agent.capability.context
import com.zmf.agent.core.llm.ApiDefaults

import com.zmf.agent.infra.llm.ChatRequest
import com.zmf.agent.infra.llm.LlmEvent
import com.zmf.agent.infra.llm.LlmProvider
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.infra.llm.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeout

/**
 * 上下文摘要器：把「较早的一段对话」压成一份检查点（checkpoint）。
 *
 * 做法：不另起一套 summarizer 系统提示词，
 * 而是把**主请求的前缀原样重放**（system + 工作区指令 + 已有检查点 + 待压区间原文），
 * 只把「请总结」作为**最后一条 user 消息**追加。
 *
 * 为什么：摘要请求因此是上一次主请求的**真前缀**，provider 的前缀缓存能一路命中，
 * 只有尾部指令与摘要输出是未缓存的新 token；若采用独立 system 并重新编号投喂原文，
 * 每次摘要都需支付完整输入的开销，且会在缓存中形成互不相关的域。
 *
 * 两个由此而来的约束：
 * - 原文**逐字节**重放，不重新编号、不截断单条（截断会破坏前缀一致性）；
 * - 上一次的检查点不能放入 system（那会改变前缀），只能拼进最后一条指令。
 */
object ContextSummarizer {

    private const val TAG = "context"

    /** 摘要请求超时：长对话需读取数万字，60 秒未返回即视为失败（转入兜底，避免阻塞用户）。 */
    private const val TIMEOUT_MS = 60_000L

    /** 摘要输出上限的参考值（65,536，含推理 token）：一份检查点允许写多长的宽松上界。 */
    private const val DSH_DEFAULT_MAX_TOKENS = 65_536

    /**
     * 摘要不超过窗口的这个比例。
     *
     * DSH 的 65,536 按其自身窗口设定；本项目 1M 窗口下直接采用将失去约束作用，
     * 取窗口的 2%（1M ≈ 20k）既可写完整检查点，也满足「摘要必须比被折叠的原文短」这一前提。
     */
    private const val SUMMARY_WINDOW_RATIO = 0.02f

    /** 超长摘要截断时插入中间的标记：提示读者此处内容被省略，避免将省略号当作正文。 */
    private const val TRUNCATE_MARK = "\n……（摘要超长，已截断中段）……\n"

    /**
     * 摘要输出上限（token）：min(65536, 窗口的 2%)。
     *
     * 窗口取 [ApiDefaults.CONTEXT_WINDOW]（全 App 只有一个口径），换更小的模型时它跟着变小。
     */
    fun maxOutputTokens(windowTokens: Int = ApiDefaults.CONTEXT_WINDOW): Int {
        val byWindow = (windowTokens.coerceAtLeast(1) * SUMMARY_WINDOW_RATIO).toInt()
        return minOf(DSH_DEFAULT_MAX_TOKENS, byWindow.coerceAtLeast(1))
    }

    /**
     * 生成（滚动融合）检查点。
     *
     * @param provider 请求通道（当前 provider）
     * @param model 摘要模型；调用方已把空串换成当前对话模型
     * @param previous 上一条检查点，空串表示第一次压缩
     * @param prefix 主请求的前缀：system → 工作区指令 → 已有检查点 → 待压区间原文（须逐字节原样，不得加工）
     * @param tools 与主请求相同的工具表（工具定义也属于前缀，缺失一份即少命中一段）
     * @param maxTokens 检查点输出上限；默认按当前窗口计算（见 [maxOutputTokens]）
     * @return 检查点正文；失败 / 超时 / 空结果一律返回 null（调用方据此转入兜底）
     */
    suspend fun summarize(
        provider: LlmProvider,
        model: String,
        previous: String,
        prefix: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        maxTokens: Int = maxOutputTokens()
    ): String? {
        if (prefix.isEmpty()) return null
        if (model.isBlank()) {
            FileLog.log(TAG, "摘要跳过：模型名为空")
            return null
        }

        // 指令一律放最后：前缀越靠前越能命中缓存，把「要做什么」放在末尾不破坏前缀。
        val instruction = buildString {
            append(INSTRUCTION)
            if (previous.isNotBlank()) {
                append("\n\n上一次的检查点（仍然有效的信息必须继承进新检查点）：\n")
                append(previous)
            }
            append("\n\n检查点正文控制在 ").append(maxTokens).append(" 字以内，超出部分会被截断。")
        }

        val messages = ArrayList<ChatMessage>(prefix.size + 1)
        messages.addAll(prefix)
        messages.add(ChatMessage(role = "user", content = instruction))

        val request = ChatRequest(
            model = model,
            messages = messages,
            // 工具表与主请求一致：它属于前缀的一部分，缺失或顺序变化都会使缓存在此中断
            tools = tools,
            // 摘要仅需结果，不启用思考：节省 token，也避免 reasoning 延长输出
            thinking = false,
            maxTokens = maxTokens
        )

        var failure: String? = null
        var output = ""
        val startedAt = System.currentTimeMillis()
        try {
            withTimeout(TIMEOUT_MS) {
                provider.stream(request)
                    // 收到 Failed 后立即停止收集，避免将不完整的输出作为摘要
                    .takeWhile { failure == null }
                    .collect { event ->
                        when (event) {
                            is LlmEvent.ContentDelta -> output += event.text
                            is LlmEvent.Finished -> FileLog.log(
                                TAG,
                                "摘要用量：输入=" + event.promptTokens + " 缓存命中=" + event.cachedTokens +
                                    "（" + (if (event.promptTokens > 0) {
                                        (event.cachedTokens * 100.0 / event.promptTokens).toInt()
                                    } else 0) + "%）输出=" + event.completionTokens
                            )

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
            FileLog.log(TAG, "摘要超时（" + (TIMEOUT_MS / 1000) + " 秒），已放弃：" + prefix.size + " 条前缀")
            return null
        } catch (e: CancellationException) {
            // 用户停止 / 退出：正常取消必须向外传递，不得吞没
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
            "摘要完成：" + prefix.size + " 条前缀 → " + text.length + " 字，用时 " +
                (System.currentTimeMillis() - startedAt) + "ms" +
                (if (text.length != raw.length) "（原始 " + raw.length + " 字，已截断到上限 " + maxTokens + "）" else "")
        )
        return text
    }

    /**
     * 超长摘要截断：保留头 60% 与尾 40%，删除中间部分。
     *
     * 为什么不只保留尾部：固定格式中【核心任务状态】位于开头（续接任务时首先需要查看），
     * 【未完成事项】位于结尾（下一步均在此处），可舍弃的是中间的【对话历程】。
     * 为什么以字符数兜底：中文 1 字 ≈ 1 token、英文 1 token ≈ 4 字符，按字符截断对混排更保守
     * （截断更短），不会超出 token 上限。
     */
    private fun truncateSummary(text: String, maxTokens: Int): String {
        if (maxTokens <= 0 || text.length <= maxTokens) return text
        val room = (maxTokens - TRUNCATE_MARK.length).coerceAtLeast(2)
        val head = (room * 0.6f).toInt().coerceAtLeast(1)
        val tail = (room - head).coerceAtLeast(1)
        return text.take(head) + TRUNCATE_MARK + text.takeLast(tail)
    }

    /**
     * 指令正文：只说明「产出什么」，不说明「原文在哪」：原文即上方重放的对话。
     *
     * 四段式固定格式采用本项目的版本，比八节英文模板更短，且符合 App 的中文语境。
     */
    private val INSTRUCTION = """
        你是压缩器。把**上面这段对话**压缩成一份检查点：让另一个模型读到它就能无缝接着干，
        既不丢关键信息，也不必重读原文。

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
        1. 必须自包含：读的人完全没有原始对话，仅凭这份检查点也能准确知道背景、当前状态和下一步。
        2. 保留路径、文件名、命令、参数、数值和关键结论的原话，不要改写成模糊描述。
        3. 宁可长一点也不要丢信息，但不要复述寒暄、重复内容和已经作废的方案。
        4. 以这段对话的最新部分为准；与更早内容冲突时采用新内容并说明变化。
        5. 不要提到「压缩」「检查点生成」这件事本身，也不要调用任何工具，只输出检查点正文。
    """.trimIndent()
}
