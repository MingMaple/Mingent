package com.zmf.agent.capability.loop

import com.zmf.agent.capability.context.ContextCompactor
import com.zmf.agent.capability.context.ContextPolicy
import com.zmf.agent.capability.context.ToolResultPruner
import com.zmf.agent.capability.prompt.AgentInstructions
import com.zmf.agent.capability.prompt.PromptBuilder
import com.zmf.agent.capability.prompt.PromptInput
import com.zmf.agent.infra.llm.ChatRequest
import com.zmf.agent.infra.llm.LlmEvent
import com.zmf.agent.infra.llm.LlmProvider
import com.zmf.agent.infra.llm.UsageBuckets
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.capability.tools.displayName
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolRegistry
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.spill.SpillStore
import com.zmf.agent.capability.jobs.JobRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 循环过程中的界面回调；AgentLoop 本身不碰 Compose。 */
interface AgentUi {
    /** 每轮开始前回调轮次序号（从 1 起）；界面只用来显示进度，不参与控制流。 */
    fun onRound(round: Int)
    /** 工具调用即将执行时回调；界面据此先摆出「执行中」的卡片。 */
    fun onToolStarted(call: ToolCall)
    /** 工具执行结束（含失败）时回调一次；界面据此换成最终结果。 */
    fun onToolFinished(call: ToolCall, result: ToolResult)
    /**
     * 一次请求的用量（已按服务商口径归一化，见 [UsageBuckets]）。
     *
     * 仅由该回调统一上报：界面累计成本轮的用量，定时任务与子智能体不关心。
     */
    fun onUsage(usage: UsageBuckets)
    /** 本轮以失败收尾时回调一句给人看的错误；被恢复掉的失败不会触发。 */
    fun onError(message: String)

    /**
     * 一次回复的时间统计（毫秒）：模型流累计 + 工具执行累计，循环结束时回调一次。
     *
     * 提供默认空实现：定时任务与子智能体无需计时，不应被迫实现空方法。
     */
    fun onTiming(modelMillis: Long, toolMillis: Long) {}

    /**
     * 一次回复的输出侧统计（本回合）：首 token 等待与纯生成时长、输出 tokens。
     *
     * 单独提供默认空实现，以免影响定时任务与子智能体：它们不显示速度，也不应被迫接收这四个数值。
     * 口径说明：[ttftMillis]/[ttftCount] 为各次请求「发出 → 第一个字」的平均分母；
     * [genMillis] 仅计算「第一个字 → 最后一个字」，不含首字等待，输出速度才具有意义。
     */
    fun onOutputTiming(timing: OutputTiming) {}
}

/** 输出侧统计；[ttftCount] 为请求次数，[ttftMillis] 为这些请求的首字等待之和。 */
data class OutputTiming(
    val ttftMillis: Long = 0,
    val ttftCount: Int = 0,
    val genMillis: Long = 0,
    val genTokens: Int = 0,
    /** 生成阶段的细分：思考块 / 正文块 / 工具参数块（互不重叠，来自流式事件类型切换）。 */
    val reasoningMillis: Long = 0,
    val textMillis: Long = 0,
    val toolArgMillis: Long = 0,
    /** 本次回复实际执行的工具调用次数。 */
    val toolCalls: Int = 0
)

/**
 * AgentLoop 组装请求视图时读的摘要状态（内存态，由 ChatViewModel 维护；落盘字段在 ChatSession 里）。
 *
 * [text] 空串 = 没有摘要，请求视图就是 summaryUpTo 之前的全量原文。
 * [fallback] = 上次摘要失败，只发最近 fallbackKeepRounds 轮。
 */
data class ContextSummaryState(
    val text: String = "",
    /** 摘要覆盖到的消息下标，必须是 user 消息边界；越界 / 不是边界时整份摘要都不用（发全量最安全）。 */
    val upTo: Int = 0,
    val fallback: Boolean = false
)

/**
 * Agent 主循环：请求 → 流式输出 → 若有工具调用则执行并回填 → 再次请求，直至模型不再请求工具。
 *
 * 消息列表由调用方持有（Compose 的 SnapshotStateList），循环内部只追加或替换，
 * 界面因此能实时显示「思考 / 正文 / 工具卡」三种状态，历史无需再同步一份。
 */
class AgentLoop(
    private val provider: LlmProvider,
    private val registry: ToolRegistry,
    private val model: String,
    private val maxRounds: Int = 10,
    /** 上下文压缩策略；每次请求前读取，修改设置后立即生效。 */
    private val contextPolicy: () -> ContextPolicy = { ContextPolicy() },
    /** 当前会话的摘要状态；同样每次请求前读取，摘要生成后即可生效。 */
    private val contextState: () -> ContextSummaryState = { ContextSummaryState() },
    /**
     * 系统提示词输入（环境 / 权限档 / Skill / 附加指令 / 覆盖）。
     *
     * 同样每次请求前读取：切换工作区、权限档或附加指令，均无需重建 AgentLoop。
     * 默认值为兜底输入，调用方尚未接线时也能获得身份、环境与「工作区档」提示。
     */
    private val promptProvider: () -> PromptInput = { PromptInput() }
) {

    /**
     * 上次注入的工作区指令指纹，仅用于在日志中标记「内容已变」，不参与是否注入的判断。
     *
     * 为什么不依据指纹决定是否注入：指令仅进入请求视图、不写入 messages，请求视图每轮重建，
     * 按指纹跳过将导致第 2..N 轮工具调用缺少工作区约定。
     */
    private var injectedInstructionsHash: String? = null

    /**
     * 上一轮 provider 流的毫秒数。
     *
     * 计时必须紧贴 stream / collect 的边界：streamOnce 还需拼装提示词、读取工作区指令，
     * 将这部分计入模型耗时会把磁盘 IO 混入模型统计。
     */
    private var lastStreamMillis = 0L

    /** 上一次请求「发出 → 第一个字」的毫秒数；一次都没出字（失败 / 只调工具）时保持 0。 */
    private var lastTtftMillis = 0L

    /** 上一次请求「第一个字 → 最后一个字」的毫秒数：输出速度只用这一段，不含等首字。 */
    private var lastGenMillis = 0L

    /** 上一次请求生成阶段的细分：思考 / 正文 / 工具参数各自的毫秒（按事件类型切块累计）。 */
    private var lastReasoningMillis = 0L
    private var lastTextMillis = 0L
    private var lastToolArgMillis = 0L

    /** 上一次请求的输入缓存命中 token 数（服务端没返回就是 0）。 */
    private var lastCachedTokens = 0

    /** 上一次请求的输出 tokens（来自 usage）：生成速度的分子。 */
    private var lastRequestOutputTokens = 0

    /**
     * 本次尝试失败时向用户展示的完整语句（可能带有「请求失败（HTTP 400）：」前缀）。
     *
     * 为什么先保存、由 run 统一上报：流开始报错时尚未确定能否「压缩后重发」，
     * 立即调用 ui.onError 会使恢复成功后界面仍保留一条已失败的红色提示。
     */
    private var lastFailure = ""

    /**
     * 同上，仅保存提供方原文（不含本应用添加的前缀）：判断「是否上下文溢出」需依据原文，
     * 前缀中的 HTTP 码与中文说明不应参与匹配。
     */
    private var lastFailureRaw = ""

    private companion object {
        /**
         * 交计划的工具名。
         *
         * 为什么单独硬编码该名称：写计划是唯一「产出即收尾」的工具，计划已交用户审阅，
         * 若再请求一轮，模型只会补充「以上是计划」，浪费一次请求。
         */
        const val PLAN_TOOL = "write_plan"

        /**
         * 状态变化时写入运行时快照「本轮变化」行的短句。
         *
         * 状态变化不再单独追加「（系统提示）…」消息（该类消息仅在该次请求出现、下一轮即消失），
         * 而是并入快照，与状态行同处一块，模型读取状态时必定同时读取变化提示。
         */
        const val PLAN_MODE_ON_NOTICE = "用户把模式切到了「计划模式」"
        const val PLAN_MODE_OFF_NOTICE = "用户关掉了「计划模式」，回到普通模式"
    }

    /** 上一次请求的前缀指纹（system 长度 / 摘要 + 工具表摘要）：变化时记录一行 [cache] 日志。 */
    private var lastCacheFingerprint: String? = null

    /** 前缀指纹用的 8 位摘要：仅用于判断「是否变化」，不用于任何安全用途。 */
    private fun shortHash(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(8)
        for (i in 0 until 4) {
            val v = digest[i].toInt() and 0xFF
            out.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return out.toString()
    }

    /**
     * 执行一次完整回复。
     *
     * @param userText 这条提问的正文；**null = 不追加新的用户消息**，直接在当前历史之后继续执行。
     *   插话（忙时写入历史的那条 user 消息）之后本轮可能已结束，需要再执行一回合接续，
     *   此时不得重复追加相同消息，否则模型会看到同一提问出现两次。
     */
    suspend fun run(
        messages: MutableList<ChatMessage>,
        userText: String?,
        thinking: Boolean,
        thinkingEffort: String?,
        toolContext: ToolContext,
        ui: AgentUi,
        /** 这条提问附带的图片：工作区相对路径（随会话落盘，模型与界面都能按路径回溯原图）。 */
        images: List<String> = emptyList(),
        /**
         * [images] 对应的 base64 data URL，只对本次请求有效（不落盘）。
         *
         * 由调用方读盘后即时生成：模型接口需要 data URL，磁盘上仅有文件；将其存入会话会使 sessions.json 膨胀。
         */
        imageDataUrls: List<String> = emptyList(),
        /**
         * 上下文溢出的恢复入口：返回 true = 已经压过、请求视图需要重建。
         *
         * 默认 null：子智能体与定时任务没有摘要能力，也不应被这条恢复路径改变行为。
         * 仅在压缩功能开启且调用方确实接入该回调时重发一次，且只重发这一次。
         */
        onOverflow: (suspend () -> Boolean)? = null,
        /**
         * 落盘检查点：调用方在这里把会话写进磁盘。
         *
         * 两个必设屏障：**发送模型请求之前**（内容 = 已提问 + 已获取的结果）、**执行有副作用的工具之前**
         * （内容 = 已记录的调用意图）。此前仅在回合结束时落盘，进程被杀会丢失整轮：实测曾出现
         * 19 条降至 14 条。null = 不落盘（子智能体 / 定时任务按原逻辑执行）。
         */
        onCheckpoint: (suspend (String) -> Unit)? = null,
        /**
         * [userText] 是不是 App 代发的。
         *
         * 只有真实用户输入才可能构成高危操作的一次性授权；计划页的「按计划开始执行」
         * 由 App 以 user 角色代发，必须标记为 true：否则模型可将授权码写入计划，
         * 用户点击按钮即形成一次伪造授权。
         */
        userTextProgrammatic: Boolean = false
    ): Int {
        if (userText != null) {
            messages.add(
                ChatMessage(
                    role = "user",
                    content = userText,
                    images = images,
                    imageDataUrls = imageDataUrls,
                    programmatic = userTextProgrammatic
                )
            )
        }
        var round = 0
        var modelMillis = 0L
        var toolMillis = 0L
        // 输出侧账目：首字等待按请求累加，生成时长只累计真正出过字的那些请求
        var ttftMillis = 0L
        var ttftCount = 0
        var genMillis = 0L
        var genTokens = 0
        var reasoningMillis = 0L
        var textMillis = 0L
        var toolArgMillis = 0L
        var toolCalls = 0
        try {
            while (round < maxRounds) {
                round++
                ui.onRound(round)
                // 后台任务结算的「繁忙注入」：每轮开始前，把本会话新结算的任务作为一条代发 user 消息
                // 接进历史，让模型在这一轮就去 job_output 读结果。取走即清空，每个结算只注入一次。
                val finished = JobRegistry.drainNotifications(toolContext.sessionId)
                if (finished.isNotEmpty()) {
                    messages.add(
                        ChatMessage(
                            role = "user",
                            content = JobRegistry.notificationText(finished),
                            programmatic = true
                        )
                    )
                    FileLog.log(
                        "jobs",
                        "繁忙注入：会话 " + toolContext.sessionId + " 有 " + finished.size +
                            " 个后台任务已结算"
                    )
                }
                var assistantIndex = messages.size
                messages.add(ChatMessage(role = "assistant", content = ""))
                var completed =
                    streamOnce(messages, assistantIndex, thinking, thinkingEffort, toolContext, ui, onCheckpoint)
                modelMillis += lastStreamMillis
                if (lastTtftMillis > 0) {
                    ttftMillis += lastTtftMillis
                    ttftCount++
                }
                genMillis += lastGenMillis
                genTokens += lastRequestOutputTokens
                reasoningMillis += lastReasoningMillis
                textMillis += lastTextMillis
                toolArgMillis += lastToolArgMillis

                // 溢出恢复：只提供一次机会。
                // 为什么不是「重试到成功」：溢出说明发送内容过多，压缩一次仍不足时应让用户知晓，
                // 否则会在同一轮重复消耗整次请求。
                if (!completed && canRecoverOverflow(onOverflow)) {
                    FileLog.log(
                        "context",
                        "溢出恢复：压缩后重发第 " + round + " 轮（原文：" + lastFailureRaw.take(200) + "）"
                    )
                    val compacted = onOverflow != null && onOverflow.invoke()
                    if (compacted) {
                        // 失败的尝试已删除占位消息，此处按当前长度重新占位
                        assistantIndex = messages.size
                        messages.add(ChatMessage(role = "assistant", content = ""))
                        completed =
                            streamOnce(messages, assistantIndex, thinking, thinkingEffort, toolContext, ui, onCheckpoint)
                        modelMillis += lastStreamMillis
                        if (lastTtftMillis > 0) {
                            ttftMillis += lastTtftMillis
                            ttftCount++
                        }
                        genMillis += lastGenMillis
                        genTokens += lastRequestOutputTokens
                        reasoningMillis += lastReasoningMillis
                        textMillis += lastTextMillis
                        toolArgMillis += lastToolArgMillis
                    }
                }
                if (!completed) {
                    // 无法恢复（未接线 / 未开启压缩 / 压缩无效）或重发仍失败：原样返回提供方信息，
                    // 不包装为「压缩失败」，也不吞没：用户需要看到提供方的原始说明
                    if (lastFailure.isNotEmpty()) ui.onError(lastFailure)
                    return round
                }
                val calls = messages.getOrNull(assistantIndex)?.toolCalls.orEmpty()
                if (calls.isEmpty()) return round

                // 本轮产出计划后立即结束：计划已落地，不再请求下一轮
                var producedPlan = false
                for (call in calls) {
                    ui.onToolStarted(call)
                    toolCalls++
                    if (call.name == PLAN_TOOL) producedPlan = true
                    // 屏障②：执行有副作用的工具之前落盘。
                    // 仅为「已派发」保留证据：若在工具执行中崩溃，重载后能看到该调用并补一条「结果未知」，
                    // 而非让模型误判未执行或已执行。只读工具不写盘，避免无效 IO。
                    if (registry.mutates(call)) checkpoint(onCheckpoint, "before-tool:" + call.name)
                    val toolStartedAt = System.nanoTime()
                    val raw = withContext(Dispatchers.IO) { registry.execute(call, toolContext) }
                    toolMillis += (System.nanoTime() - toolStartedAt) / 1_000_000
                    // 超长输出不整段写入上下文：落盘后仅返回「头 + 省略 + 尾 + 文件路径」，
                    // 模型需要细节时再用 read_file 分页读取。
                    val result = if (raw.ok) {
                        val spilled = withContext(Dispatchers.IO) {
                            SpillStore.process(toolContext.context, toolContext.sessionId, call.name, raw.text)
                        }
                        if (spilled.spilled) ToolResult.Ok(spilled.text) else raw
                    } else {
                        raw
                    }
                    FileLog.log(
                        "tool",
                        call.name + " " + (if (result.ok) "ok" else "fail") +
                            " args=" + call.arguments.take(300) + " -> " + result.text.take(300)
                    )
                    ui.onToolFinished(call, result)
                    messages.add(
                        ChatMessage(
                            role = "tool",
                            content = result.text,
                            toolCallId = call.id,
                            toolName = call.name
                        )
                    )
                }

                if (producedPlan) {
                    // 记入日志是因为该轮结束得「早」：无正文、无下一轮，排查时需确认由工具触发
                    FileLog.log("plan", "write_plan 已产出计划，结束本轮")
                    return round
                }
            }
            ui.onError("本轮已连续调用 " + maxRounds + " 次工具，先停下等你确认下一步。")
            return round
        } finally {
            // 放在 finally：被「停止」打断的那一轮也需上报已消耗的时间，
            // 否则计时仅在成功回合中累计，任务一旦停止即整段丢失
            ui.onTiming(modelMillis, toolMillis)
            ui.onOutputTiming(
                OutputTiming(
                    ttftMillis = ttftMillis,
                    ttftCount = ttftCount,
                    genMillis = genMillis,
                    genTokens = genTokens,
                    reasoningMillis = reasoningMillis,
                    textMillis = textMillis,
                    toolArgMillis = toolArgMillis,
                    toolCalls = toolCalls
                )
            )
        }
    }

    /**
     * 调一次落盘检查点，失败**只记日志、不拦回合**。
     *
     * 此处有意比 fail-closed 更宽松（写入失败也不拦截请求 / 工具）：
     * 写失败几乎只由「磁盘满」这类全局故障引起，此时拦截模型请求会导致用户无法提问，
     * 也无法请求模型协助清理。因此降级为 best-effort，但必须留痕。
     */
    private suspend fun checkpoint(onCheckpoint: (suspend (String) -> Unit)?, reason: String) {
        if (onCheckpoint == null) return
        try {
            onCheckpoint(reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLog.log("session", "检查点写盘失败（" + reason + "）：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * 这次失败值不值得试一次「压缩后重发」。
     *
     * 三个条件缺一不可：调用方给了压缩入口、压缩功能是开的、失败原因确实是上下文超限。
     * 前两条保证「未接线的调用方（子智能体 / 定时任务）与未开启压缩的用户」行为完全不变：
     * 未开启压缩则无内容可压，额外执行一次摘要只会增加用户等待时间。
     */
    private fun canRecoverOverflow(onOverflow: (suspend () -> Boolean)?): Boolean {
        if (onOverflow == null) return false
        if (!contextPolicy().enabled) return false
        // 提供方原文优先：本应用添加的中文前缀不参与判断
        return isContextOverflow(if (lastFailureRaw.isNotEmpty()) lastFailureRaw else lastFailure)
    }

    /**
     * 提供方说的是不是「上下文太长」。
     *
     * 为什么仅识别这几组词：各家措辞不同（DeepSeek 常用 "maximum context length" / "too many tokens"），
     * 误判代价明确：浪费一次完整请求，且可能将「网络中断」误判为「上下文溢出」，误导用户。
     * 因此宁可漏判（用户仍可手动触发压缩），也不放宽至「只要提及 token 即判定」。
     */
    private fun isContextOverflow(message: String): Boolean {
        val text = message.lowercase()
        if (text.isEmpty()) return false
        if (text.contains("context length")) return true
        if (text.contains("context window")) return true
        if (text.contains("maximum context")) return true
        if (text.contains("too many tokens")) return true
        // 「exceeds ... token(s)」「... token limit exceeded」这类组合：两个词都得在，避免单词误伤
        if (text.contains("exceeds") && text.contains("token")) return true
        if (text.contains("too long") && text.contains("token")) return true
        return false
    }

    /**
     * 跑一轮流式请求。返回 false 表示这一轮失败，失败原文记在 [lastFailure] / [lastFailureRaw]，
     * 由调用方决定「压缩后重发」还是「直接报给用户」；流中不上报，因为这两种可能尚未区分。
     */
    private suspend fun streamOnce(
        messages: MutableList<ChatMessage>,
        index: Int,
        thinking: Boolean,
        thinkingEffort: String?,
        toolContext: ToolContext,
        ui: AgentUi,
        onCheckpoint: (suspend (String) -> Unit)? = null
    ): Boolean {
        // 先清零：请求拼装阶段就抛异常时，调用方读到的应该是「这一轮没等到模型」，
        // 而不是上一轮留下的旧耗时
        lastStreamMillis = 0L
        lastTtftMillis = 0L
        lastGenMillis = 0L
        lastReasoningMillis = 0L
        lastTextMillis = 0L
        lastToolArgMillis = 0L
        lastCachedTokens = 0
        lastRequestOutputTokens = 0
        // 失败信息也一样：只属于本次尝试。留着上一次的，会把正常请求误判成「又溢出了」
        lastFailure = ""
        lastFailureRaw = ""
        val history = ArrayList<ChatMessage>(index)
        for (i in 0 until index) history.add(messages[i])
        // 只改「发给模型的请求视图」，messages 与界面历史一律不动
        val policy = contextPolicy()
        val state = contextState()

        // ① 摘要覆盖过的部分不再进入请求：从 user 边界 summaryUpTo 起（分界始终落在整轮开头）
        var start = 0
        var withSummary = false
        // 此处仅判断「是否存在摘要」：摘要开关控制「自动触发」，手动压缩生成的摘要在任何情况下均应生效
        if (state.text.isNotEmpty()) {
            val upTo = state.upTo
            if (upTo in 0 until history.size && history[upTo].role == "user") {
                start = upTo
                withSummary = true
            } else {
                // 回滚 / 删除消息后分界可能失效：宁可多带原文，也不将一轮切成半截
                FileLog.log("context", "摘要分界失效（" + upTo + " / " + history.size + " 条），本次退回全量")
            }
        }

        // ② 摘要失败的兜底：只发最近 fallbackKeepRounds 轮原文（整轮边界，工具消息不会断）
        if (policy.enabled && state.fallback) {
            val fallbackStart = history.size - ContextCompactor.compact(history, policy).messages.size
            if (fallbackStart > start) start = fallbackStart
        }

        // 每次请求前读取提示词输入（权限档 / 计划模式均在其中，用户中途切换后下一句即生效）。
        // 摘要不再进入快照 / 提示词：它作为独立的检查点消息插在历史之前（见下方 withSummary 部分）。
        val input = promptProvider()

        // 运行时快照：挂在本轮那条 user 消息上，**只写一次**，此后逐字节复用。
        // 必须在下方 sanitized / request 组装**之前**挂载：组装读取的是消息对象，挂载晚一步这一轮
        // 的快照就无法发出，只会在下一轮出现（模型会回答「本轮没有附带新的状态块」）。
        // 「本轮变化」由**历史中最后一份快照**比对得出，不使用实例字段（字段在 run() 开头即被清空，
        // 跨轮切换将无法获得变化提示）。与上一份完全相同则不追加。
        val previous = messages.indices.reversed()
            .map { messages[it].runtimeSnapshot }
            .firstOrNull { it.isNotEmpty() }
        val changeLines = ArrayList<String>(2)
        if (previous != null) {
            if (!previous.contains(PromptBuilder.permissionStateLine(toolContext.permission))) {
                changeLines += "用户把权限档切到了「" + toolContext.permission.displayName() + "」"
            }
            if (!previous.contains(PromptBuilder.planStateLine(input.planMode))) {
                changeLines += if (input.planMode) PLAN_MODE_ON_NOTICE else PLAN_MODE_OFF_NOTICE
            }
        }
        val snapshotOwner = messages.indexOfLast { it.role == "user" }
        PromptBuilder.buildRuntimeSnapshot(input, changeLines)?.let { snapshot ->
            if (snapshotOwner >= 0 && messages[snapshotOwner].runtimeSnapshot.isEmpty() &&
                previous != snapshot
            ) {
                messages[snapshotOwner] = messages[snapshotOwner].copy(runtimeSnapshot = snapshot)
            }
        }
        // [history] 是本函数开头从 messages 拷贝出来的一份（同对象引用）：刚替换的那条必须同步回去，
        // 否则下方 sanitized / request 获取到的仍为**没有快照的旧对象**：
        // 此处曾两次出现同一问题，第一次是在 request 组装之后才挂载快照。
        if (snapshotOwner in history.indices) history[snapshotOwner] = messages[snapshotOwner]

        val tail = history.subList(start, history.size)
        val sanitized = tail.sanitized()
         // 压缩发生之后，超长的工具输出在**请求视图**中折叠中段（见 [ToolResultPruner]：
        // 压力确认后才折叠，低于压力线不变，宁可多付 token 也保留全文）。仅修改本次发送的字节，
        // 不修改 messages 与界面；同一段内容的折叠形态恒定，前缀缓存不会因其逐轮波动。
        val pruneActive = withSummary || (policy.enabled && state.fallback)
        val viewMessages = if (pruneActive) ToolResultPruner.prune(sanitized) else sanitized
        val request = ArrayList<ChatMessage>(viewMessages.size + 1)
        // system 只放**稳定层**（身份 / 设备 / 边界 / 工具总则 / 沟通 / 记忆 / 计划确认 / Skill / 附加指令）：
         // 契约是同一会话内**一字不变**：它位于请求最前缀，只有保持恒定才能命中 provider 的前缀缓存。
        // 会变的（权限档 / 计划模式 / 工作区 / 时间 / 摘要）全部走末尾的运行时快照。
        val systemText = PromptBuilder.buildStable(input)
        if (systemText != null) request.add(ChatMessage(role = "system", content = systemText))

        // 工作区指令文件（AGENTS.md / CLAUDE.md 系列）：每次请求前读取，用户修改文件后下一轮即生效。
         // 存在时必须每轮携带：指令仅进入请求视图、不写入 messages，请求视图每轮重建，
        // 缺少一轮则模型少一轮约定可参考。位置固定在 system 之后、历史之前，前缀亦保持稳定。
        val instructions = withContext(Dispatchers.IO) { AgentInstructions.load(toolContext.workspaceRoot) }
        if (instructions != null) {
            request.add(
                ChatMessage(
                    role = "user",
                    content = "<system-reminder>Instructions from: " + instructions.displayPath +
                        "\n\n" + instructions.text + "</system-reminder>"
                )
            )
            FileLog.log(
                "instructions",
                "injected " + instructions.displayPath + " " + instructions.text.length +
                    " chars hash=" + instructions.hash
            )
            if (instructions.hash != injectedInstructionsHash) {
                FileLog.log(
                    "instructions",
                    "内容已变（" + (injectedInstructionsHash ?: "无") + " → " + instructions.hash + "）"
                )
            }
            injectedInstructionsHash = instructions.hash
        } else {
            // 文件被删除时也要清除指纹：相同内容重新出现时仍能记录一行「内容已变」
            injectedInstructionsHash = null
        }
        // 压缩检查点：一条独立的 user 消息，紧跟在 system / 工作区指令之后、历史原文之前。
         // 为何如此放置：checkpoint 是**合成 user 消息**，
        // 放在被遮蔽区间的原位（即第一个可动消息处），一次生成后冻结；node 0（system）永不进入压缩区间。
        // 这样「system + 检查点」成为永久复用的前缀，之后只追加新消息，缓存不会被压缩反复打断。
        if (withSummary) {
            request.add(ChatMessage(role = "user", content = PromptBuilder.checkpointText(state.text)))
        }
        request.addAll(viewMessages)

         // 运行状态以**独立的一条 user 消息**跟在所属用户消息之后，不得拼入用户消息正文：
         // 拼入时模型会将其视为用户原话的一部分，曾两次回答「本轮消息未附带状态块」。
        // 只识别新格式（抬头 = PromptBuilder.SNAPSHOT_MARKER）：旧会话中的旧快照带有权限档 / 计划模式，
        // 拼回请求等于将过期状态重新提供给模型，一律跳过。
        val withState = ArrayList<ChatMessage>(request.size + 4)
        for (message in request) {
            withState.add(message)
            if (message.role == "user" &&
                message.runtimeSnapshot.startsWith(PromptBuilder.SNAPSHOT_MARKER)
            ) {
                withState.add(ChatMessage(role = "user", content = message.runtimeSnapshot))
            }
        }
        request.clear()
        request.addAll(withState)
         // 模式 / 权限档的「刚刚变了」语句不再单独发送：它已作为「本轮变化」写入运行时快照
        // （见上方 changeLines），与状态行同处一块。位置与来源仅保留一处，模型不会在
         // 「快照与末尾提示不一致」之间作出选择。
        if (start > 0 || withSummary) {
            FileLog.log(
                "context",
                "请求视图：摘要" + (if (withSummary) "已带" else "未带") + "，原文从第 " + start + " 条起，" +
                    history.size + " 条历史 → " + request.size + " 条请求（约 " +
                    ContextCompactor.estimate(request) + " tokens）"
            )
        }
        // 工具表与系统提示词使用同一个 planMode 来源，避免「提示词声明处于计划模式、工具表却提供写工具」
        // 工具表恒定（不按档位 / 计划模式裁剪）：进出模式或切换档位不改变请求前缀，缓存不会整体失效
        val specs = registry.specs()
        // 记一行「本轮到底下发了哪些工具」：档位、计划模式、工具名。
         // 为何记录此行：模型曾两次声称「只有只读工具、没有 write_file」，而工具表实际包含该工具；
        // 日志仅记录档位、不记录工具表时，此类争议只能依靠推理定案；记录此行即可一次查清。
        FileLog.log(
            "tools",
            "本轮工具表 " + specs.size + " 个（档位=" + toolContext.permission.name +
                "，计划模式=" + (if (input.planMode) "开" else "关") + "）：" +
                specs.joinToString(", ") { it.name }
        )
         // 前缀指纹：system 与工具表分别判断「是否变化」，仅在变化时记录一行。
         // 前缀缓存按字节前缀匹配，任一处的变化都会导致从变化处起全部重新计算；
         // 记录该行后可快速定性「本轮为何未命中」，无需再依靠推理。
        val cacheSystem = request.firstOrNull { it.role == "system" }?.content.orEmpty()
        // 描述也属于工具定义（模型看到的是 name + description + 参数 schema），
         // 早期仅拼接 name 与 parametersJson 长度，修改描述时指纹不变，诊断会误判为「工具表未变化」。
        val cacheTools = specs.joinToString("|") { it.name + ":" + it.description.length + ":" + it.parametersJson.length }
        val fingerprint = cacheSystem.length.toString() + "/" + shortHash(cacheSystem) +
            " tools=" + specs.size + "/" + shortHash(cacheTools)
        if (fingerprint != lastCacheFingerprint) {
            FileLog.log(
                "cache",
                "请求前缀指纹变了：" + fingerprint +
                    (lastCacheFingerprint?.let { "（上一份 " + it + "）" } ?: "（本进程首次）")
            )
            lastCacheFingerprint = fingerprint
        }

        val contentFilter = DsmlFilter("正文")
        val reasoningFilter = DsmlFilter("思考")
        val calls = LinkedHashMap<Int, ToolCall>()
        var content = ""
        var reasoning = ""
        var failed = false

        /** 把流里的当前内容写回 messages 的 assistant 占位：替换同一个下标，界面才能实时刷新。 */
        fun publish() {
            if (index !in messages.indices) return
            messages[index] = ChatMessage(
                role = "assistant",
                content = content,
                reasoningContent = reasoning.ifEmpty { null },
                toolCalls = calls.values.toList()
            )
        }

        // 仅统计「等待模型」这一段：请求拼装、读取工作区指令均在此之前，不应计入模型耗时
        val streamStartedAt = System.nanoTime()
        // 首字与末字时间：TTFT 与「纯生成速度」均由这两个时间点计算。
         // 仅统计实际产生内容的片段（思考内容也算，属于模型输出），工具调用增量不计，该部分无 token 速率。
        var firstTokenAt = 0L
        var lastTokenAt = 0L
        /**
         * 生成阶段的块计时：流式事件按类型切换即切块（思考 / 正文 / 工具参数）。
         *
         * DSH 使用 block-start 标记切块；此处没有该标记，但事件类型本身即为分界，
         * 一段结束到下一段开始之间的空档归入下一段，三段互不重叠，合计即整个生成窗口。
         */
        var blockKind = 0
        var blockStartAt = 0L
        var reasoningMs = 0L
        var textMs = 0L
        var toolArgMs = 0L
        fun switchBlock(kind: Int, at: Long) {
            if (blockKind != 0) {
                val d = (at - blockStartAt) / 1_000_000
                when (blockKind) {
                    1 -> reasoningMs += d
                    2 -> textMs += d
                    3 -> toolArgMs += d
                }
            }
            blockKind = kind
            blockStartAt = at
        }
        /** 记录一次增量时间戳：首次调用确定首字起点，之后每次更新末字时间。 */
        fun markToken() {
            val now = System.nanoTime()
            if (firstTokenAt == 0L) firstTokenAt = now
            lastTokenAt = now
        }
         // 屏障①：发送请求前落盘。此刻历史中已有用户提问与上次的工具结果，
        // 在此崩溃仅丢失「尚未生成的回答」，不会产生无法解释的不完整记录。
        checkpoint(onCheckpoint, "before-request")
        try {
            provider.stream(
                ChatRequest(
                    model = model,
                    messages = request,
                    tools = specs,
                    thinking = thinking,
                    reasoningEffort = if (thinking) thinkingEffort else null
                )
            ).collect { event ->
                when (event) {
                    is LlmEvent.ReasoningDelta -> {
                        switchBlock(1, System.nanoTime())
                        val visible = reasoningFilter.feed(event.text)
                        if (visible.isNotEmpty()) {
                            markToken()
                            reasoning += visible
                            publish()
                        }
                    }

                    is LlmEvent.ContentDelta -> {
                        switchBlock(2, System.nanoTime())
                        val visible = contentFilter.feed(event.text)
                        if (visible.isNotEmpty()) {
                            markToken()
                            content += visible
                            publish()
                        }
                    }

                    is LlmEvent.ToolCallDelta -> {
                        switchBlock(3, System.nanoTime())
                        val prev = calls[event.index]
                        calls[event.index] = ToolCall(
                            id = event.id ?: prev?.id.orEmpty(),
                            name = event.name ?: prev?.name.orEmpty(),
                            arguments = (prev?.arguments.orEmpty()) + event.argumentsDelta.orEmpty()
                        )
                        publish()
                    }

                    is LlmEvent.Finished -> {
                        // 归一化仅在此处执行一次：将各家 usage 转换为统一结构，界面、落盘、日志均使用该结果
                        val buckets = UsageBuckets.of(
                            promptTokens = event.promptTokens,
                            completionTokens = event.completionTokens,
                            cachedTokens = event.cachedTokens,
                            cacheReported = event.cacheReported,
                            cacheWriteTokens = event.cacheWriteTokens,
                            reasoningTokens = event.reasoningTokens,
                            promptIncludesCache = event.promptIncludesCache
                        )
                        lastCachedTokens = buckets.cacheReadTokens
                        lastRequestOutputTokens = buckets.completionTokens
                        // 一行用量记录：命中率后续通过日志复核，不依赖状态行的单一数值。
                        // 实测参考：97.6K 输入 / 8 轮时命中 84.7%，属该长度的正常范围。
                        val hitText = if (buckets.cacheReadReported && buckets.promptTokens > 0) {
                            String.format(
                                java.util.Locale.US,
                                "%.1f%%",
                                buckets.cacheReadTokens * 100.0 / buckets.promptTokens
                            )
                        } else {
                            "未上报"
                        }
                        FileLog.log(
                            "usage",
                            "输入=" + buckets.promptTokens + " 未缓存=" + buckets.uncachedInputTokens +
                                " 缓存读取=" + buckets.cacheReadTokens + "（" + hitText + "）" +
                                " 缓存写入=" + (buckets.cacheWriteTokens?.toString() ?: "未上报") +
                                " 输出=" + buckets.completionTokens
                        )
                        ui.onUsage(buckets)
                    }

                    is LlmEvent.Failed -> {
                        failed = true
                        lastFailureRaw = event.message
                        lastFailure = if (event.code > 0) {
                            "请求失败（HTTP " + event.code + "）：" + event.message
                        } else {
                            "请求失败：" + event.message
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            lastFailureRaw = e.message.orEmpty()
            lastFailure = "请求异常：" + (e.message ?: e.javaClass.simpleName)
        } finally {
            // 取消也需记录：用户点击停止时，本轮等待时间已实际消耗
            val endedAt = System.nanoTime()
            switchBlock(0, endedAt)
            lastStreamMillis = (endedAt - streamStartedAt) / 1_000_000
            lastReasoningMillis = reasoningMs
            lastTextMillis = textMs
            lastToolArgMillis = toolArgMs
            if (firstTokenAt > 0L) {
                lastTtftMillis = (firstTokenAt - streamStartedAt) / 1_000_000
                // 仅产生一个字时 lastTokenAt == firstTokenAt：生成时长按 0 计算，速度计算处自行兜底，避免除零
                lastGenMillis = (lastTokenAt - firstTokenAt) / 1_000_000
            }
        }

        content += contentFilter.flush()
        reasoning += reasoningFilter.flush()
        if (contentFilter.tripped || reasoningFilter.tripped) {
            ui.onError("模型输出里出现了非法标记，已截断，不会显示到界面")
        }

        if (failed || (content.isBlank() && calls.isEmpty())) {
            if (index in messages.indices) messages.removeAt(index)
            return false
        }
        publish()
        return true
    }

    /**
     * 发送请求前的清洗：丢弃空 assistant 占位、孤立的 tool 消息，以及「有 tool_calls 但结果不完整」的整轮。
     * 停止按钮打断工具执行时，历史中会留下此类不完整轮次，未清洗则下一轮必定返回 400。
     */
    private fun List<ChatMessage>.sanitized(): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(size)
        var i = 0
        while (i < size) {
            val m = this[i]
            when {
                m.role == "tool" -> i++
                m.role == "assistant" && m.content.isBlank() && m.toolCalls.isEmpty() -> i++
                m.role == "assistant" && m.toolCalls.isNotEmpty() -> {
                    var j = i + 1
                    val results = ArrayList<ChatMessage>()
                    while (j < size && this[j].role == "tool") {
                        results.add(this[j])
                        j++
                    }
                    val got = results.mapNotNull { it.toolCallId }.toSet()
                    val complete = m.toolCalls.all { it.id in got }
                    if (complete) {
                        out.add(m)
                        out.addAll(results)
                    }
                    i = j
                }
                else -> {
                    out.add(m)
                    i++
                }
            }
        }
        return out
    }
}

/**
 * 一次回合的产出边界：本轮跑了几轮、新增了哪些消息、最终答复是哪一条。
 *
 * 普通发送与定时任务共用这一口径。答复只从 [added] 里取，历史消息不参与：
 * 请求失败、本轮没有产出正文时返回空串，调用方据此判为失败，而不是把上一轮的答复当成本轮结果。
 */
data class TurnOutcome(
    val rounds: Int,
    val added: List<ChatMessage>
) {
    /** 本轮新增消息里的最后一条有正文的 assistant；没有则为空串。 */
    val reply: String
        get() = added
            .lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
            ?.content
            .orEmpty()
            .trim()
}

/**
 * 伪工具标记过滤。
 *
 * 标记可能被拆分到两个 delta 中（例如「...<|DS」+「ML|>...」），因此尾部可能是标记前缀的
 * 部分需暂缓输出，确认不是标记后再放出；一旦命中即永久截断本轮。
 */
class DsmlFilter(private val label: String) {

    private val pending = StringBuilder()

    var tripped = false
        private set

    /** 喂入一个流式增量，返回此刻可安全显示的部分；末尾可能是标记前缀时先扣住。 */
    fun feed(delta: String): String {
        if (tripped || delta.isEmpty()) return ""
        pending.append(delta)
        val hit = pending.indexOf(MARK)
        if (hit >= 0) {
            tripped = true
            val visible = pending.substring(0, hit)
            pending.setLength(0)
            FileLog.log("dsml", label + " 命中非法标记，已截断（截断前 " + visible.length + " 字符）")
            return visible
        }
        var keep = 0
        val maxKeep = minOf(pending.length, MARK.length - 1)
        for (k in maxKeep downTo 1) {
            if (pending.substring(pending.length - k) == MARK.substring(0, k)) {
                keep = k
                break
            }
        }
        val emitLength = pending.length - keep
        if (emitLength <= 0) return ""
        val visible = pending.substring(0, emitLength)
        pending.delete(0, emitLength)
        return visible
    }

    /** 流结束时放出扣住的内容；已命中标记则丢弃并返回空串。 */
    fun flush(): String {
        if (tripped) {
            pending.setLength(0)
            return ""
        }
        val visible = pending.toString()
        pending.setLength(0)
        return visible
    }

    companion object {
        const val MARK = "<|DSML|"
    }
}
