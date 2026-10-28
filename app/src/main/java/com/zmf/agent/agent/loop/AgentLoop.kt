package com.zmf.agent.agent.loop

import com.zmf.agent.agent.context.ContextCompactor
import com.zmf.agent.agent.context.ContextPolicy
import com.zmf.agent.agent.context.ToolResultPruner
import com.zmf.agent.agent.instructions.AgentInstructions
import com.zmf.agent.agent.prompt.PromptBuilder
import com.zmf.agent.agent.prompt.PromptInput
import com.zmf.agent.agent.provider.ChatRequest
import com.zmf.agent.agent.provider.LlmEvent
import com.zmf.agent.agent.provider.LlmProvider
import com.zmf.agent.agent.provider.UsageBuckets
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.tools.displayName
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolRegistry
import com.zmf.agent.tools.ToolResult
import com.zmf.agent.tools.spill.SpillStore
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
     * 只在 [Args.thinking] 无关的这个回调里报账：界面累计成本轮的账，定时任务与子智能体不关心。
     */
    fun onUsage(usage: UsageBuckets)
    /** 本轮以失败收尾时回调一句给人看的错误；被恢复掉的失败不会触发。 */
    fun onError(message: String)

    /**
     * 一次回复的时间账（毫秒）：模型流累计 + 工具执行累计，循环结束时回调一次。
     *
     * 给默认空实现：定时任务与子智能体不关心计时，它们不该被迫实现一个空方法。
     */
    fun onTiming(modelMillis: Long, toolMillis: Long) {}

    /**
     * 一次回复的输出侧账目（本回合计）：首 token 等待与纯生成时长、输出 tokens。
     *
     * 单列一个默认空实现，是为了不动定时任务与子智能体：它们不显示速度，也不该被迫接这四个数。
     * 口径说明：[ttftMillis]/[ttftCount] 是各次请求「发出 → 第一个字」的平均分母；
     * [genMillis] 只算「第一个字 → 最后一个字」，不含等首字，输出速度才有意义。
     */
    fun onOutputTiming(timing: OutputTiming) {}
}

/** 输出侧账目；[ttftCount] 是请求次数，[ttftMillis] 是这些请求的首字等待之和。 */
data class OutputTiming(
    val ttftMillis: Long = 0,
    val ttftCount: Int = 0,
    val genMillis: Long = 0,
    val genTokens: Int = 0
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
 * Agent 主循环：请求 → 流式输出 → 若有工具调用就执行并回填 → 再请求，直到模型不再要工具。
 *
 * 消息列表直接由调用方持有（Compose 的 SnapshotStateList），循环内部只追加/替换，
 * 这样界面能实时看到「思考 / 正文 / 工具卡」三种状态，历史也不用再同步一份。
 */
class AgentLoop(
    private val provider: LlmProvider,
    private val registry: ToolRegistry,
    private val model: String,
    private val maxRounds: Int = 10,
    /** 上下文压缩策略；每次请求前现取，改设置立刻生效。 */
    private val contextPolicy: () -> ContextPolicy = { ContextPolicy() },
    /** 当前会话的摘要状态；同样每次请求前现取，摘要刚生成也能立刻生效。 */
    private val contextState: () -> ContextSummaryState = { ContextSummaryState() },
    /**
     * 系统提示词输入（环境 / 权限档 / Skill / 附加指令 / 覆盖）。
     *
     * 同样每次请求前现取：换工作区、切权限档、改附加指令都不用重建 AgentLoop。
     * 默认值是一份兜底输入，调用方还没接线时也至少能拿到身份、环境和「工作区档」提示。
     */
    private val promptProvider: () -> PromptInput = { PromptInput() }
) {

    /**
     * 上次注入的工作区指令指纹，只用来在日志里标出「内容已变」，不参与「投不投」的判断。
     *
     * 为什么不按指纹决定投不投：指令只进请求视图、不写 messages，请求视图每轮都重建，
     * 按指纹跳过等于让第 2..N 轮工具调用丢掉工作区约定。
     */
    private var injectedInstructionsHash: String? = null

    /**
     * 上一轮 provider 流花了多少毫秒。
     *
     * 计时必须贴着 stream / collect 的边界量：streamOnce 里还要拼提示词、读工作区指令，
     * 那部分算进「模型耗时」会把磁盘 IO 混进模型的账里。
     */
    private var lastStreamMillis = 0L

    /** 上一次请求「发出 → 第一个字」的毫秒数；一次都没出字（失败 / 只调工具）时保持 0。 */
    private var lastTtftMillis = 0L

    /** 上一次请求「第一个字 → 最后一个字」的毫秒数：输出速度只用这一段，不含等首字。 */
    private var lastGenMillis = 0L

    /** 上一次请求的输入缓存命中 token 数（服务端没返回就是 0）。 */
    private var lastCachedTokens = 0

    /** 上一次请求的输出 tokens（来自 usage）：生成速度的分子。 */
    private var lastRequestOutputTokens = 0

    /**
     * 本次尝试失败时给用户看的那一整句（可能带我们加的「请求失败（HTTP 400）：」前缀）。
     *
     * 为什么先存下来、由 run 统一报：流一开始报错时我们还不知道能不能「压缩后重发」，
     * 当场 ui.onError 会让恢复成功后界面上还挂着一条其实已经失败过的红字。
     */
    private var lastFailure = ""

    /**
     * 同上，但只存提供方原文（不带我们的前缀）：判「是不是上下文溢出」要用原话，
     * 前缀里的 HTTP 码和中文说明都不该参与匹配。
     */
    private var lastFailureRaw = ""

    private companion object {
        /**
         * 交计划的工具名。
         *
         * 为什么单独硬编码一个名字：写计划是唯一「产出即收尾」的工具，计划已经交给用户过目了，
         * 再让模型往下问一轮，它只会补几句「以上是计划」，白花一次请求。
         */
        const val PLAN_TOOL = "write_plan"

        /**
         * 状态变化时写进运行时快照「本轮变化」那一行的短句。
         *
         * 以前是独立追加一条「（系统提示）…」消息，只在该次请求出现、下一轮就消失；
         * 现在并进快照，与状态行同处一块，模型读状态时一定同时读到「刚刚变了」。
         */
        const val PLAN_MODE_ON_NOTICE = "用户把模式切到了「计划模式」"
        const val PLAN_MODE_OFF_NOTICE = "用户关掉了「计划模式」，回到普通模式"
    }

    /**
     * 状态变化**不再靠实例字段**判断：字段在每轮开跑时会被清空（[run] 开头），
     * 跨轮切换就永远拿不到变化提示：打开计划模式后模型仍答「普通模式」，就是被清空的结果。
     * 现在改为与历史里最后一份运行时快照比对，见 [streamOnce] 里的 previous / changeLines。
     */
    /** 上一次请求的前缀指纹（system 长度/摘要 + 工具表摘要）：变化时记一行 [cache] 日志。 */
    private var lastCacheFingerprint: String? = null

    /** 前缀指纹用的 8 位摘要：只回答「变没变」，不做任何安全用途。 */
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
     * 跑一次完整回复。
     *
     * @param userText 这条提问的正文；**null = 不追加新的用户消息**，直接在当前历史之后继续跑。
     *   插话（忙时往历史里塞的那条 user 消息）之后本轮可能已经结束，需要再跑一回合把它接上，
     *   这时不能再追加一条一样的消息，否则模型会看到同一句话被问了两遍。
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
         * 由调用方读盘现编：模型接口要 data URL，磁盘上只有文件；把它存进会话会撑爆 sessions.json。
         */
        imageDataUrls: List<String> = emptyList(),
        /**
         * 上下文溢出的恢复入口：返回 true = 已经压过、请求视图需要重建。
         *
         * 默认 null：子智能体与定时任务没有摘要能力，也不该被这条恢复路径改变行为。
         * 只有「压缩功能是开的、调用方真的接了这个回调」时才会重发一次，且只重发这一次。
         */
        onOverflow: (suspend () -> Boolean)? = null,
        /**
         * 落盘检查点：调用方在这里把会话写进磁盘。
         *
         * 两个必设屏障：**发模型请求之前**（内容 = 已提问 + 已拿到的结果）、**执行有副作用的工具之前**
         * （内容 = 已记录的调用意图）。之前只有回合结束才落盘，进程被杀就会把整轮丢掉：实测过一次
         * 19 条掉到 14 条。null = 不落盘（子智能体 / 定时任务按原样跑）。
         */
        onCheckpoint: (suspend (String) -> Unit)? = null,
        /**
         * [userText] 是不是 App 代发的。
         *
         * 只有真实用户输入才可能构成高危操作的一次性授权；计划页的「按计划开始执行」
         * 是 App 以 user 角色代发的，必须标 true：否则模型可以把授权码写进计划，
         * 用户点一下按钮就成了一次伪造授权。
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
        try {
            while (round < maxRounds) {
                round++
                ui.onRound(round)
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

                // 溢出恢复：只给一次机会。
                // 为什么不是「重试到成功」：溢出说明发的东西太多，压一次还不够就该让用户看见，
                // 否则会在同一轮里反复花整次请求的钱。
                if (!completed && canRecoverOverflow(onOverflow)) {
                    FileLog.log(
                        "context",
                        "溢出恢复：压缩后重发第 " + round + " 轮（原文：" + lastFailureRaw.take(200) + "）"
                    )
                    val compacted = onOverflow != null && onOverflow.invoke()
                    if (compacted) {
                        // 失败的尝试已经把占位气泡删掉了，这里按当前长度重新占位
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
                    }
                }
                if (!completed) {
                    // 恢复不了（没接线 / 没开压缩 / 压不动）或重发仍失败：原样把提供方那句话给用户，
                    // 不包装成「压缩失败」，也不吞掉：用户要看到的就是提供方到底说了什么
                    if (lastFailure.isNotEmpty()) ui.onError(lastFailure)
                    return round
                }
                val calls = messages.getOrNull(assistantIndex)?.toolCalls.orEmpty()
                if (calls.isEmpty()) return round

                // 本轮产出了计划就直接收尾：计划已经落地，不再问下一轮
                var producedPlan = false
                for (call in calls) {
                    ui.onToolStarted(call)
                    if (call.name == PLAN_TOOL) producedPlan = true
                    // 屏障②：执行有副作用的工具之前落盘。
                    // 只为「已派发」留证据：崩在工具执行中，重载后能看到这条调用、并补一条「结果未知」，
                    // 而不是让模型以为它没做过（或者以为做完了）。只读工具不写盘，那是纯 IO 浪费。
                    if (registry.mutates(call)) checkpoint(onCheckpoint, "before-tool:" + call.name)
                    val toolStartedAt = System.nanoTime()
                    val raw = withContext(Dispatchers.IO) { registry.execute(call, toolContext) }
                    toolMillis += (System.nanoTime() - toolStartedAt) / 1_000_000
                    // 超大输出不整段塞进上下文：落盘后只回「头 + 省略 + 尾 + 文件路径」，
                    // 模型要细看再用 read_file 分页读。
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
                    // 写进日志是因为这一轮结束得「早」：没有正文、没有下一轮，排查时要知道是工具触发的
                    FileLog.log("plan", "write_plan 已产出计划，结束本轮")
                    return round
                }
            }
            ui.onError("本轮已连续调用 " + maxRounds + " 次工具，先停下等你确认下一步。")
            return round
        } finally {
            // 放在 finally：被「停止」打断的那一轮也要把已经花掉的时间报上去，
            // 否则计时只在成功的回合里累计，任务一停就整段丢失
            ui.onTiming(modelMillis, toolMillis)
            ui.onOutputTiming(
                OutputTiming(
                    ttftMillis = ttftMillis,
                    ttftCount = ttftCount,
                    genMillis = genMillis,
                    genTokens = genTokens
                )
            )
        }
    }

    /**
     * 调一次落盘检查点，失败**只记日志、不拦回合**。
     *
     * 这里有意比 fail-closed 更宽松（写不进去也不拦请求 / 不拦工具）：
     * 我们的写失败几乎只来自「磁盘满」这种全局故障，这时拦住模型请求只会让用户连话都问不出去、
     * 也没法让它帮忙清理。所以这里降级成 best-effort，但一定留痕。
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
     * 前两条保证「没接线的调用方（子智能体 / 定时任务）与没开压缩的用户」行为完全不变：
     * 没开压缩就没什么可压的，白跑一次摘要只是给用户多等几秒。
     */
    private fun canRecoverOverflow(onOverflow: (suspend () -> Boolean)?): Boolean {
        if (onOverflow == null) return false
        if (!contextPolicy().enabled) return false
        // 提供方原话优先：我们自己加的中文前缀不参与判断
        return isContextOverflow(if (lastFailureRaw.isNotEmpty()) lastFailureRaw else lastFailure)
    }

    /**
     * 提供方说的是不是「上下文太长」。
     *
     * 为什么只认这几组词：各家措辞不同（DeepSeek 常用 "maximum context length" / "too many tokens"），
     * 而误判的代价很实在：白花一次完整请求，还可能把「网络断了」说成「上下文溢出」，把用户引到错的方向。
     * 所以宁可漏判（用户还能像以前那样手点压缩），也不放宽到「只要提到 token 就算」。
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
     * 由调用方决定「压缩后重发」还是「直接报给用户」，流里不报，是因为这两种可能还没分出来。
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

        // ① 摘要覆盖过的部分不再进请求：从 user 边界 summaryUpTo 起（分界永远落在整轮开头）
        var start = 0
        var withSummary = false
        // 这里只认「有没有摘要」：摘要开关管的是「自动触发」，手动压缩出来的摘要任何情况下都要生效
        if (state.text.isNotEmpty()) {
            val upTo = state.upTo
            if (upTo in 0 until history.size && history[upTo].role == "user") {
                start = upTo
                withSummary = true
            } else {
                // 回滚 / 删消息后分界可能失效：宁可多带原文，也不把一轮切成半截
                FileLog.log("context", "摘要分界失效（" + upTo + " / " + history.size + " 条），本次退回全量")
            }
        }

        // ② 摘要失败的兜底：只发最近 fallbackKeepRounds 轮原文（整轮边界，工具消息不会断）
        if (policy.enabled && state.fallback) {
            val fallbackStart = history.size - ContextCompactor.compact(history, policy).messages.size
            if (fallbackStart > start) start = fallbackStart
        }

        // 每次请求现取提示词输入（权限档 / 计划模式都在里面，用户中途切换下一句就生效）。
        // 摘要不再进快照/提示词：它作为独立的检查点消息插在历史之前（见下面 withSummary 那段）。
        val input = promptProvider()

        // 运行时快照：挂在本轮那条 user 消息上，**只写一次**，此后逐字节复用。
        // 必须在下面 sanitized / request 组装**之前**挂：组装拿的是消息对象，挂晚一步这一轮的快照
         // 就发不出去、只会在下一轮才出现（模型会答「本轮没有附带新的状态块」）。
        // 「本轮变了什么」由**历史里最后一份快照**比对得出，不用实例字段（字段在 run() 开头就被清空，
         // 跨轮切换永远拿不到变化提示）。与上一份完全相同就不再追加。
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
        // [history] 是本函数开头从 messages 拷出来的一份（同对象引用）：刚换掉的那条必须同步回去，
         // 否则下面 sanitized / request 拿到的仍是**没有快照的旧对象**：
         // 这里踩过两次同样的坑，第一次就是在 request 组装之后才挂快照。
        if (snapshotOwner in history.indices) history[snapshotOwner] = messages[snapshotOwner]

        val tail = history.subList(start, history.size)
        val sanitized = tail.sanitized()
         // 压缩发生之后，超长的工具输出在**请求视图**里折掉中段（见 [ToolResultPruner]：
        // 压力确认后才折，低于压力线不动，宁可多付 token 也留全）。只改这次发出去的字节，
        // 不改 messages、不改界面；同一段内容的折叠形态恒定，前缀缓存不会因为它逐轮抖动。
        val pruneActive = withSummary || (policy.enabled && state.fallback)
        val viewMessages = if (pruneActive) ToolResultPruner.prune(sanitized) else sanitized
        val request = ArrayList<ChatMessage>(viewMessages.size + 1)
        // system 只放**稳定层**（身份 / 设备 / 边界 / 工具总则 / 沟通 / 记忆 / 计划确认 / Skill / 附加指令）：
         // 契约是同一会话内**一字不变**：它在请求最前缀，只有恒定才吃得到 provider 的前缀缓存。
        // 会变的（权限档 / 计划模式 / 工作区 / 时间 / 摘要）全部走末尾的运行时快照。
        val systemText = PromptBuilder.buildStable(input)
        if (systemText != null) request.add(ChatMessage(role = "system", content = systemText))

        // 工作区指令文件（AGENTS.md / CLAUDE.md 系列）：每次请求前现读，用户改完文件下一轮就生效。
         // 有就必须每轮都带：指令只进请求视图、不写 messages，请求视图每轮重建，
        // 少带一轮模型就少一轮约定可看。位置固定在 system 之后、历史之前，prefix 也稳定。
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
            // 文件被删也要把指纹清掉：同样的内容再放回来时还能打出一行「内容已变」
            injectedInstructionsHash = null
        }
        // 压缩检查点：一条独立的 user 消息，紧跟在 system / 工作区指令之后、历史原文之前。
         // 为什么这么摆：checkpoint 是**合成 user 消息**，
        // 放在被遮蔽区间的原位（也就是第一个可动消息处），一次生成后冻结；node 0（system）永不进压缩区间。
        // 这样「system + 检查点」成为永久复用的前缀，之后只追加新消息，缓存不会被压缩反复打断。
        if (withSummary) {
            request.add(ChatMessage(role = "user", content = PromptBuilder.checkpointText(state.text)))
        }
        request.addAll(viewMessages)

         // 运行状态以**独立的一条 user 消息**跟在它所属的用户消息后面。不要再拼进用户那句话里：
         // 拼进去的时候模型把它当成用户原话的
         // 一部分，两次回答「本轮消息未附带状态块」。
        // 只认新格式（抬头 = PromptBuilder.SNAPSHOT_MARKER）：老会话里的旧快照带着权限档/计划模式，
        // 拼回去等于把过期状态重新塞给模型，一律跳过。
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
         // 模式 / 权限档的「刚刚变了」那句不再单独发：它已经作为「本轮变化」写进运行时快照
        // （见上面 changeLines），与状态行同处一块。位置、来源都只剩一处，模型不会在
         // 「快照说一套、末尾提示说另一套」之间做选择。
        if (start > 0 || withSummary) {
            FileLog.log(
                "context",
                "请求视图：摘要" + (if (withSummary) "已带" else "未带") + "，原文从第 " + start + " 条起，" +
                    history.size + " 条历史 → " + request.size + " 条请求（约 " +
                    ContextCompactor.estimate(request) + " tokens）"
            )
        }
        // 工具表与系统提示词用同一个 planMode 来源，避免「提示词说在计划模式、工具表却给了写工具」
        // 工具表恒定（不按档位 / 计划模式裁剪）：进出模式或切档不再改请求前缀，缓存不会被整体作废
        val specs = registry.specs()
        // 记一行「本轮到底下发了哪些工具」：档位、计划模式、工具名。
         // 为什么要记这一行：曾经模型两次声称「只有只读工具、没有 write_file」，而工具表里其实有；
        // 日志只记档位、不记工具表时，这类争议只能靠推理定案；多记这一行就能一次查清。
        FileLog.log(
            "tools",
            "本轮工具表 " + specs.size + " 个（档位=" + toolContext.permission.name +
                "，计划模式=" + (if (input.planMode) "开" else "关") + "）：" +
                specs.joinToString(", ") { it.name }
        )
         // 前缀指纹：system 与工具表各自「变没变」，只在变化时记一行。
         // 前缀缓存按字节前缀匹配，这两处任何一处变了，从变化处起后面全部重算，
         // 有了这行，「这轮为什么没命中」一眼能定性，不用再靠推理。
        val cacheSystem = request.firstOrNull { it.role == "system" }?.content.orEmpty()
        // 描述也算工具定义的一部分（模型看到的就是 name + description + 参数 schema），
         // 早期只串了 name + parametersJson 长度，改描述时指纹不变，诊断会误判成「工具表没变」。
        val cacheTools = specs.joinToString("|") { it.name + ":" + it.description.length + ":" + it.parametersJson.length }
        val fingerprint = cacheSystem.length.toString() + "/" + shortHash(cacheSystem) +
            " tools=" + specs.size + "/" + shortHash(cacheTools)
         // 一次性诊断：把本轮请求「最后一条消息」的尾部记下来，直接确认状态块到底进没进请求。
        // 只在「线上请求落盘」打开时才记：这是排查用的，平时每轮打一行只会把日志淹掉。
        if (com.zmf.agent.agent.ApiConfig.wireDumpDir != null) {
            val lastRequest = request.lastOrNull()
            FileLog.log(
                "request",
                "请求末尾：role=" + (lastRequest?.role ?: "-") +
                    " 含状态块=" + request.count { it.content.contains(PromptBuilder.SNAPSHOT_MARKER) } +
                    " 尾部=[" + (lastRequest?.content?.takeLast(90) ?: "") + "]"
            )
        }
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

        // 只量「等模型」这一段：请求拼装、读工作区指令都在这之前，不该算成模型耗时
        val streamStartedAt = System.nanoTime()
        // 首字时间与末字时间：TTFT 与「纯生成速度」都从这两根针里算。
         // 只认真正出了字的片段（思考也算，它是模型在说话），工具调用增量不算，那部分没有 token 速率可言
        var firstTokenAt = 0L
        var lastTokenAt = 0L
        /** 记一次增量时间戳：第一次调用定首字起点，之后每次更新末字时间。 */
        fun markToken() {
            val now = System.nanoTime()
            if (firstTokenAt == 0L) firstTokenAt = now
            lastTokenAt = now
        }
         // 屏障①：发请求之前落盘。此刻历史里已经有用户的提问与上次的工具结果，
        // 崩在这里丢的只是「这一条还没生成的回答」，不会有无法解释的半截记录。
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
                        val visible = reasoningFilter.feed(event.text)
                        if (visible.isNotEmpty()) {
                            markToken()
                            reasoning += visible
                            publish()
                        }
                    }

                    is LlmEvent.ContentDelta -> {
                        val visible = contentFilter.feed(event.text)
                        if (visible.isNotEmpty()) {
                            markToken()
                            content += visible
                            publish()
                        }
                    }

                    is LlmEvent.ToolCallDelta -> {
                        val prev = calls[event.index]
                        calls[event.index] = ToolCall(
                            id = event.id ?: prev?.id.orEmpty(),
                            name = event.name ?: prev?.name.orEmpty(),
                            arguments = (prev?.arguments.orEmpty()) + event.argumentsDelta.orEmpty()
                        )
                        publish()
                    }

                    is LlmEvent.Finished -> {
                        // 归一化只在这里做一次：把各家的 usage 变成同一套桶，界面、落盘、日志都吃这一套
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
                        // 一行用量账：命中率以后用日志复核，不靠状态行单点数字。
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
            // 取消也要记：用户点停止时这一轮的等待时间是真实花掉的
            val endedAt = System.nanoTime()
            lastStreamMillis = (endedAt - streamStartedAt) / 1_000_000
            if (firstTokenAt > 0L) {
                lastTtftMillis = (firstTokenAt - streamStartedAt) / 1_000_000
                // 只出一个字时 lastTokenAt == firstTokenAt：生成时长按 0 算，速度那边自己兜底，不除零
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
     * 发请求前的清洗：丢掉空 assistant 占位、孤儿 tool 消息，以及「有 tool_calls 但结果不齐」的整轮。
     * 停止按钮打断工具执行时，历史里就会留下这种半截轮次，不清洗下一轮必定 400。
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
 * 伪工具标记过滤。
 *
 * 标记可能被拆到两个 delta 里（例如「...<|DS」+「ML|>...」），所以尾巴上可能是标记前缀的
 * 部分要先扣住不显示，确认不是标记再放出去；一旦命中就永久截断本轮。
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
