package com.zmf.agent.feature.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.zmf.agent.capability.context.ContextCompactor
import com.zmf.agent.capability.context.ContextEvent
import com.zmf.agent.capability.context.ContextEventKind
import com.zmf.agent.capability.context.ContextEventStore
import com.zmf.agent.capability.context.ContextPolicy
import com.zmf.agent.capability.context.ContextSnapshot
import com.zmf.agent.capability.context.ContextStore
import com.zmf.agent.capability.context.ContextSummarizer
import com.zmf.agent.capability.context.ToolResultPruner
import com.zmf.agent.capability.context.contextSummarySystemText
import com.zmf.agent.capability.loop.ContextSummaryState
import com.zmf.agent.capability.loop.OutputTiming
import com.zmf.agent.capability.prompt.AgentInstructions
import com.zmf.agent.capability.prompt.PromptBuilder
import com.zmf.agent.capability.prompt.PromptInput
import com.zmf.agent.capability.tools.ToolRegistry
import com.zmf.agent.core.llm.ApiDefaults
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.InheritedUsage
import com.zmf.agent.core.store.LedgerComposition
import com.zmf.agent.core.store.SessionComposition
import com.zmf.agent.core.store.UsageLedger
import com.zmf.agent.infra.llm.DeepSeekProvider
import com.zmf.agent.infra.llm.LlmProvider
import com.zmf.agent.infra.llm.ProviderStore
import com.zmf.agent.infra.llm.UsageBuckets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import java.io.File

/** 洞察页趋势只画最近这么多次回复：再多就糊成一团，也看不出变化。 */
private const val INSIGHT_TREND_POINTS = 20

/**
 * 溢出恢复的等待上限。
 *
 * 摘要请求自己有 60 秒超时（ContextSummarizer.TIMEOUT_MS），这里再宽 30 秒：
 * 超过还没回调，就说明这条链路不正常了，宁可放弃也不要挂着这一轮。
 */
private const val OVERFLOW_COMPACT_TIMEOUT_MS = 90_000L

/**
 * 上下文与用量的状态与动作：策略、水位、摘要压缩、统计快照、时间账、本轮 usage 计数。
 *
 * 从 [ChatViewModel] 搬出，行为不变：字段名与原成员逐字一致，方法体原样照搬；
 * 只把跨模块的只读依赖改成注入——消息表、会话表与其写入口、当前会话 id、
 * 流式标记、进行中提示、请求链路（provider / 工具表 / 提示词输入）、工作区根目录、
 * 落盘入口——免得这个类反过来攥住整个 ViewModel。
 */
class ContextState(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val messages: SnapshotStateList<ChatMessage>,
    private val currentSessionId: () -> String,
    private val sessions: () -> List<ChatSession>,
    private val writeSessions: (List<ChatSession>) -> Unit,
    private val isStreaming: () -> Boolean,
    private val setActivity: (String?) -> Unit,
    private val modelName: () -> String,
    private val provider: () -> LlmProvider,
    private val registry: () -> ToolRegistry,
    private val promptInput: () -> PromptInput,
    private val workspaceRoot: () -> File,
    private val usageModel: () -> String,
    private val persistCurrent: () -> Unit,
    private val persistAll: () -> Unit,
    private val reloadSessionDocs: () -> Unit
) {

    /** 上下文压缩策略（摘要式滚动压缩）：只影响发给模型的请求视图，界面历史不动。 */
    var contextPolicy by mutableStateOf(ContextStore.load(appContext))
        private set

    /** 上下文快照（估算 tokens / 水位 / 覆盖条数）；由 [refreshContext] 算，界面只读。 */
    var contextSnapshot by mutableStateOf(ContextSnapshot())
        private set

    /** 当前会话摘要文本；空串 = 没有摘要，请求视图发全量。直接读会话表，落盘即生效。 */
    val contextSummary: String
        get() = sessions().firstOrNull { it.id == currentSessionId() }?.contextSummary.orEmpty()

    /** 摘要失败后置 true：请求视图退成「只发最近 fallbackKeepRounds 轮」，不再阻塞主请求。 */
    private var contextFallback = false

    /** 上下文分段估算（系统提示词 / 工具表 / user / assistant / 工具结果）；由 [refreshContext] 顺带刷新。 */
    var contextBreakdown by mutableStateOf(Breakdown())
        private set

    /** 当前会话的摘要/压缩事件（时间正序，只读展示）；换会话与每次压缩动作后刷新。 */
    var contextEventList by mutableStateOf<List<ContextEvent>>(emptyList())
        private set

    /**
     * 最近一次请求的用量文本。
     *
     * 气泡里已经把「输入 / 输出」内嵌进去了，这条不再画在列表外面（用户要的就是外面少一行）；
     * 字段留着兼容，别再加渲染。
     */
    var usageText by mutableStateOf<String?>(null)
        private set

    /**
     * 统计页的快照（轮数 / 步数 / token / 上下文水位）。
     *
     * 只有页面需要时才刷新：轮数与水位要遍历消息，不能每帧算，由 [refreshStats] 在进页面时算一次。
     */
    var stats by mutableStateOf(UsageStats())
        private set

    /** 本会话的时间账；换会话、收到 usage、回复收尾都会从会话记录重新读回。 */
    var sessionTiming by mutableStateOf(TimingSnapshot())
        private set

    /**
     * 洞察页专用时间账：只算自分支起的真实用量（分叉继承的那一段已经减掉）。
     *
     * 状态行要「克隆会话」的观感（命中率、token 都继承），洞察页反过来只报真实数据，
     * 两者口径不同，所以分成两份快照，别在一个字段里拐弯。
     */
    var insightTiming by mutableStateOf(TimingSnapshot())
        private set

    /** 洞察页的提示词长度趋势（最近若干次回复的输入 tokens）；由 [refreshInsight] 刷新。 */
    var insightTrend by mutableStateOf<List<Int>>(emptyList())
        private set

    /**
     * 本轮（这一次回复）累计的 usage：[recordUsage] 每来一次就加，收尾时一次性写进本轮最后一条 assistant。
     *
     * 一轮里可能要问模型好几次（每次工具调用后都要再问一次），气泡只该显示这一整轮的总账，
     * 所以先攒着，等 [writeTurnUsage] 才落笔。
     */
    private var turnPromptTokens = 0
    private var turnCompletionTokens = 0
    /** 本轮命中缓存的输入 tokens，以及服务端到底报没报这项（没报时面板整行不显示）。 */
    private var turnCachedTokens = 0
    private var turnCacheReported = false
    /** 本轮未缓存输入、缓存写入、推理 tokens：换别家模型时这几项才有数。 */
    private var turnUncachedInputTokens = 0
    private var turnCacheWriteTokens: Int? = null
    private var turnReasoningTokens: Int? = null
    /** 本轮用的「提供方/模型」，在开跑那一刻定格，面板照它显示。 */
    private var turnUsageModel = ""

    /** 本回合从哪条消息开始（含）：收尾只在 [turnStartIndex, 末尾] 里找本轮最后一条 assistant。 */
    private var turnStartIndex = 0

    /** 本次回复的模型 / 工具耗时（毫秒）：AgentLoop 收尾回调一次，[commitTiming] 后清零。 */
    private var turnModelMillis = 0L
    private var turnToolMillis = 0L

    /**
     * 本次回复的输出侧账目（首字等待 / 生成 / 三段细分 / 工具调用次数）。
     *
     * AgentLoop 在收尾时先回 onTiming、再回 onOutputTiming；[commitTiming] 在这之后，
     * 所以这里先攒着，落总账时一次写清。
     */
    private var turnTtftMillis = 0L
    private var turnGenMillis = 0L
    private var turnGenTokens = 0
    private var turnReasoningMillis = 0L
    private var turnTextMillis = 0L
    private var turnToolArgMillis = 0L
    private var turnToolCalls = 0

    /** 本轮起点（毫秒）：用于算总账的活跃时长（一次回复的墙钟）。 */
    private var turnStartedAt = 0L

    // ---- 上下文摘要（滚动压缩） ----

    /** 设置页改策略：夹到合法范围、落盘、立刻重算快照（下一次请求就用新的）。 */
    fun applyContextPolicy(p: ContextPolicy) {
        contextPolicy = ContextStore.normalize(p)
        ContextStore.save(appContext, contextPolicy)
        refreshContext()
        FileLog.log("context", "策略更新：" + contextPolicy.summary)
    }

    /**
     * 重算快照：估算的是「真正会发出去的那份请求视图」。
     * 有摘要就是 system(摘要) + summaryUpTo 之后的原文，没有摘要就是全量（摘要失败时按兜底轮数裁）。
     * 分母是 ApiDefaults.CONTEXT_WINDOW；不另写估算器，直接复用 ContextCompactor.estimate。
     */
    fun refreshContext() {
        val summary = contextSummary
        val upTo = if (summary.isEmpty()) 0 else normalizedSummaryUpTo()
        val start = requestStart(summary, upTo)
        val tokens = ContextCompactor.estimate(requestView(start, summary, upTo))
        val window = ApiDefaults.CONTEXT_WINDOW
        val session = sessions().firstOrNull { it.id == currentSessionId() }
        contextSnapshot = ContextSnapshot(
            estimatedTokens = tokens,
            window = window,
            ratio = if (window > 0) tokens.toFloat() / window else 0f,
            coveredMessages = start,
            totalMessages = messages.size,
            hasSummary = summary.isNotEmpty() && upTo > 0,
            lastSummaryAt = session?.lastSummaryAt ?: 0L
        )
        // 三段估算在末尾顺带刷一次：用户只有看到「系统提示词 / 工具表 / 消息」各占多少，
        // 才知道水位是被提示词撑起来的还是被历史撑起来的
        refreshContextBreakdown()
    }

    /**
     * 请求视图的起点：已摘要覆盖到的下标，摘要失败时再被兜底裁轮往后推。
     *
     * refreshContext 与 refreshContextBreakdown 都从这里取，两处口径不会走偏。
     */
    private fun requestStart(summary: String, upTo: Int): Int {
        var start = upTo
        if (contextPolicy.enabled && contextFallback) {
            start = maxOf(start, ContextCompactor.tailStartIndex(messages, contextPolicy.fallbackKeepRounds))
        }
        return start
    }

    /** 真正会发出去的那份请求视图：摘要抬头 + 起点之后的原文（口径与 AgentLoop 一致）。 */
    private fun requestView(start: Int, summary: String, upTo: Int): List<ChatMessage> {
        val request = ArrayList<ChatMessage>(messages.size - start + 1)
        if (summary.isNotEmpty() && upTo > 0) {
            // 抬头和 AgentLoop 用同一个函数，保证估算口径与实际请求一致
            request.add(ChatMessage(role = "system", content = contextSummarySystemText(summary)))
        }
        for (i in start until messages.size) request.add(messages[i])
        return request
    }

    /**
     * 重算上下文分段估算。
     *
     * - system：AgentLoop 每次请求现拼的那条系统提示词。这里用同一份 [promptInput] 再拼一次
     *   （PromptBuilder 是纯函数，多拼一次不产生副作用），拼不出来（全部为空）记 0；
     * - tool：当前权限档 / 计划模式下的工具表，按 name + 描述 + 参数 JSON 文本长度估；
     * - user / assistant / toolResult：把请求视图按 role 拆开估，工具结果是历史里涨得最快的部分。
     *
     * 各段是独立视角，加起来不保证等于水位（摘要抬头在 system 里，同时也在请求视图里）。
     */
    fun refreshContextBreakdown() {
        val summary = contextSummary
        val upTo = if (summary.isEmpty()) 0 else normalizedSummaryUpTo()
        val request = requestView(requestStart(summary, upTo), summary, upTo)
        val summaryText = if (summary.isNotEmpty() && upTo > 0) summary else ""
        // planMode 由 promptInput() 自己带（与 AgentLoop 同一份输入）；摘要单独量一遍，
        // 不然「提示词」这段把摘要也算进去，用户会以为提示词本身就有几千 token
        val promptOnly = PromptBuilder.build(promptInput().copy(summaryText = ""))
        val systemTokens = promptOnly?.let { ContextCompactor.estimateText(it) } ?: 0
        val summaryTokens = if (summaryText.isBlank()) {
            0
        } else {
            ContextCompactor.estimateText(contextSummarySystemText(summaryText))
        }
        // 工具表：真发出去的是 JSON，但引号逗号的开销相对整段可忽略；
        // 不构造 JSON 也就没有「某个工具的参数 JSON 不合法直接抛」的风险
        // 工具表恒定：与真正发出的那一份同源（见 ToolRegistry.specs 的说明），估算才不会偏
        val specs = registry().specs()
        val toolTokens = if (specs.isEmpty()) {
            0
        } else {
            ContextCompactor.estimateText(
                specs.joinToString("\n") { it.name + "\n" + it.description + "\n" + it.parametersJson }
            )
        }
        var userTokens = 0
        var assistantTokens = 0
        var toolResultTokens = 0
        for (message in request) {
            when (message.role) {
                // 摘要抬头在 system 那一段已经算过，这里跳过 system，免得重复计入
                "user" -> userTokens += ContextCompactor.estimate(message)
                "assistant" -> assistantTokens += ContextCompactor.estimate(message)
                "tool" -> toolResultTokens += ContextCompactor.estimate(message)
                else -> Unit
            }
        }
        contextBreakdown = Breakdown(
            systemTokens = systemTokens,
            summaryTokens = summaryTokens,
            toolTokens = toolTokens,
            userTokens = userTokens,
            assistantTokens = assistantTokens,
            toolResultTokens = toolResultTokens
        )
    }

    /**
     * 手动立即压缩（设置页 / 长按按钮）。流式回复中不允许：两路请求抢同一个会话没有意义。
     * 结果通过 [onDone] 回主线程：true = 摘要成功，false = 没做或失败（已退兜底）。
     */
    fun summarizeNow(onDone: (Boolean) -> Unit = {}, force: Boolean = false) {
        if (isStreaming()) {
            FileLog.log("context", "正在生成回复，手动压缩先跳过")
            onDone(false)
            return
        }
        startCompaction(onDone, force)
    }

    /**
     * 真正跑压缩（不含「流式中」那道闸）。
     *
     * 为什么要把闸门拆出去：溢出恢复必须在流式中调用。那一轮已经被提供方按上下文超限拒了，
     * 不压缩就没法重发；「两路请求抢同一个会话」这条顾虑只适用于用户手点压缩。
     */
    private fun startCompaction(onDone: (Boolean) -> Unit, force: Boolean) {
        if (!contextPolicy.enabled && !force) {
            FileLog.log("context", "摘要开关关着且未强制，手动压缩跳过")
            onDone(false)
            return
        }
        refreshContext()
        setActivity("正在压缩上下文…")
        scope.launch {
            val ok = try {
                runSummary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 手动路径同样不能抛：置兜底标记，请求视图退成最近几轮
                contextFallback = true
                refreshContext()
                recordCompressFallback("手动压缩异常：" + (e.message ?: e.javaClass.simpleName))
                FileLog.log("context", "手动压缩异常，退回兜底：" + e)
                false
            } finally {
                setActivity(null)
            }
            persistAll()
            onDone(ok)
        }
    }

    /**
     * AgentLoop 的溢出恢复回调：压一次上下文，成功就让它重发同一轮。
     *
     * 为什么套一层超时：summarizeNow 那套是回调式 API，任何一条路径漏掉回调都会把这一轮永久挂住；
     * 超时之后最坏只是「这次没压成」，AgentLoop 会把提供方原话报给用户，不会卡死。
     * 走 [startCompaction] 而不是 summarizeNow：溢出发生在流式中，那道「流式中别压」的闸必须绕开。
     */
    suspend fun onOverflowCompaction(): Boolean =
        withTimeoutOrNull(OVERFLOW_COMPACT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                startCompaction(
                    onDone = { ok -> if (cont.isActive) cont.resume(ok) },
                    force = true
                )
            }
        } ?: false

    /** 清除本会话摘要：请求视图恢复全量（旧消息本来就都在，不需要恢复任何内容）。 */
    fun clearContextSummary() {
        if (isStreaming()) return
        contextFallback = false
        val id = currentSessionId()
        // 清除前的分界要留证：清完这一条就没了，事件里得写上当时压到哪
        val boundary = sessions().firstOrNull { it.id == id }?.summaryUpTo ?: 0
        writeSessions(sessions().map {
            if (it.id == id) it.copy(contextSummary = "", summaryUpTo = 0, lastSummaryAt = 0L) else it
        })
        persistCurrent()
        persistAll()
        refreshContext()
        recordContextEvent(kind = ContextEventKind.CLEAR, boundary = boundary)
        FileLog.log("context", "已清除会话 " + id + " 的摘要，恢复全量请求视图")
    }

    /** 重读当前会话的上下文事件：列表是只读展示，整份替换即可。 */
    private fun reloadContextEvents() {
        contextEventList = ContextEventStore.load(appContext, currentSessionId())
    }

    /**
     * 记一条上下文事件并立刻刷新列表。
     *
     * 为什么写完马上重读：事件是给人排查用的，用户点完「立即压缩」就该在页面上看到这一条，
     * 等他切会话再切回来才出现，等于告诉他「刚才那次压缩没记录」。
     */
    private fun recordContextEvent(
        kind: ContextEventKind,
        ok: Boolean = true,
        fromMessages: Int = 0,
        foldedMessages: Int = 0,
        boundary: Int = 0,
        keptRounds: Int = 0,
        note: String = ""
    ) {
        ContextEventStore.append(
            appContext,
            currentSessionId(),
            ContextEvent(
                at = System.currentTimeMillis(),
                kind = kind,
                ok = ok,
                fromMessages = fromMessages,
                foldedMessages = foldedMessages,
                boundary = boundary,
                keptRounds = keptRounds,
                note = note
            )
        )
        reloadContextEvents()
    }

    /**
     * 压缩失败 → 退兜底：两条事件一起记。
     *
     * 为什么分两条：失败原因是「这次没压成」，兜底窗口是「没压成之后发的是什么」，
     * 合成一条就没法分别按类型过滤，页面上也说不清。
     */
    private fun recordCompressFallback(reason: String) {
        // 失败事件也要带上兜底轮数：描述行要写「已退回最近 N 轮」，不传就渲染成 0 轮
        recordContextEvent(
            kind = ContextEventKind.COMPRESS,
            ok = false,
            keptRounds = contextPolicy.fallbackKeepRounds,
            note = reason
        )
        recordContextEvent(
            kind = ContextEventKind.FALLBACK,
            keptRounds = contextPolicy.fallbackKeepRounds
        )
    }

    /**
     * 这次摘要要覆盖到哪：逐字保留区的起点（user 边界）。
     *
     * 保留区的口径按 DSH：优先级是「逐字保留预算（(W−O)×retainRatio 或绝对 token）」，
     * 同时不能低于 fallbackKeepRounds 轮；窗口与输出预留都从 ApiConfig 取，和请求口径一致。
     */
    private fun summaryBoundary(): Int =
        ContextCompactor.retainStart(messages, contextPolicy)

    /**
     * 双触发闸门（或关系）：
     * ① 估算 tokens / 上下文窗口 ≥ triggerRatio；
     * ② 自上次摘要后新增 user 消息数 ≥ triggerCount（可关）。
     * 前提是「分界确实往前走了」：没有可摘要的内容就不触发，免得空转重复调模型。
     */
    fun shouldSummarize(): Boolean {
        val policy = contextPolicy
        if (!policy.enabled) return false
        refreshContext()
        val boundary = summaryBoundary()
        val covered = normalizedSummaryUpTo()
        if (boundary <= covered) return false
        if (contextSnapshot.ratio >= policy.triggerRatio) return true
        if (policy.triggerByCount) {
            // +1：这条即将发出的 user 消息还没进 messages，但它本来就算「新增的一条」
            var count = 1
            for (i in covered until messages.size) if (messages[i].role == "user") count++
            if (count >= policy.triggerCount) return true
        }
        return false
    }

    /**
     * 摘要目标：请求通道与模型。
     *
     * `summaryModel` 的取值有三种：空串（跟随当前对话模型，复用主请求通道）、
     * `providerId|model`（设置页选的固定档，Key 与端点必须跟着那个提供商走）、
     * 裸模型名（旧版本落盘的值，仍按当前 provider 解释）。
     * 配置被删、Key 清空或值损坏时回落到当前对话模型——摘要只是旁路动作，不该因此失败。
     */
    private fun summaryTarget(): Pair<LlmProvider, String> {
        val raw = contextPolicy.summaryModel.trim()
        if (raw.isEmpty()) return provider() to modelName()
        val cut = raw.indexOf('|')
        if (cut <= 0) return provider() to raw
        val providerId = raw.substring(0, cut)
        val model = raw.substring(cut + 1).trim()
        val cfg = ProviderStore.load(appContext).firstOrNull { it.id == providerId }
        if (cfg == null || cfg.apiKey.isBlank() || model.isEmpty()) {
            FileLog.log("context", "摘要模型不可用（" + raw + "），回落到当前模型")
            return provider() to modelName()
        }
        val baseUrl = cfg.baseUrl.trim().trimEnd('/').ifBlank {
            // 只有内置 DeepSeek 允许回落到官方端点；别家空地址由 provider 明确报错
            if (cfg.id == ProviderStore.DEEPSEEK_ID) ApiDefaults.BASE_URL else ""
        }
        return DeepSeekProvider(apiKey = cfg.apiKey, baseUrl = baseUrl) to model
    }

    /**
     * 跑一次摘要（与上一条摘要融合成新的）：
     * 成功 → 写回会话三字段并落盘；失败/超时/空结果 → 只置兜底标记，主请求照常发。
     */
    suspend fun runSummary(): Boolean {
        val policy = contextPolicy
        val boundary = summaryBoundary()
        val covered = normalizedSummaryUpTo()
        if (boundary <= covered) {
            // 消息还不够多（或全被摘要覆盖）：没有可摘要的部分，不白花一次请求
            FileLog.log("context", "没有可摘要的部分（分界 " + boundary + " ≤ 已覆盖 " + covered + "）")
            return false
        }

        val previous = contextSummary
        // 摘要请求要「蹭」主请求的热前缀：把主请求从 system 到分界处的字节**原样重放**，
        // 只把「请压缩」作为最后一条 user 消息追加。system / 工作区指令 / 已有检查点 / 工具表少给或换了
        // 顺序，缓存就断在那一处，摘要调用就从「几乎全命中」退化成「付一整份输入」。
        val prefix = ArrayList<ChatMessage>(boundary - covered + 4)
        PromptBuilder.buildStable(promptInput())?.let {
            prefix.add(ChatMessage(role = "system", content = it))
        }
        AgentInstructions.load(workspaceRoot())?.let {
            prefix.add(
                ChatMessage(
                    role = "user",
                    content = "<system-reminder>Instructions from: " + it.displayPath +
                        "\n\n" + it.text + "</system-reminder>"
                )
            )
        }
        if (previous.isNotBlank()) {
            prefix.add(ChatMessage(role = "user", content = PromptBuilder.checkpointText(previous)))
        }
        for (i in covered until boundary) {
            val message = messages[i]
            // 与主请求同一口径：待压区间里的超长工具输出先折中段，再喂给摘要器。
            // 摘要器要的是「读懂再压成几段话」，中段原始输出进不了摘要正文，折掉只省不亏；
            // 折叠形态与压缩后的请求视图一致，压完那一轮拼出来的前缀才对得上。
            prefix.add(ToolResultPruner.pruneMessage(message))
            if (message.role == "user" &&
                message.runtimeSnapshot.startsWith(PromptBuilder.SNAPSHOT_MARKER)
            ) {
                // 运行状态块在主请求里是单独一条 user 消息，重放时也必须补上，否则那一段字节对不上
                prefix.add(ChatMessage(role = "user", content = message.runtimeSnapshot))
            }
        }
        val startedAt = System.currentTimeMillis()
        val target = summaryTarget()
        val text = ContextSummarizer.summarize(
            provider = target.first,
            model = target.second,
            previous = previous,
            prefix = prefix,
            tools = registry().specs()
        )

        if (text.isNullOrBlank()) {
            // 失败兜底：请求视图只发最近 fallbackKeepRounds 轮，绝不阻塞、也不破坏主请求
            contextFallback = true
            refreshContext()
            recordCompressFallback("摘要结果为空（" + (System.currentTimeMillis() - startedAt) + "ms）")
            FileLog.log(
                "context",
                "摘要失败（" + (System.currentTimeMillis() - startedAt) + "ms），兜底只发最近 " +
                    policy.fallbackKeepRounds + " 轮"
            )
            return false
        }

        contextFallback = false
        val id = currentSessionId()
        val now = System.currentTimeMillis()
        writeSessions(sessions().map {
            if (it.id == id) {
                it.copy(contextSummary = text, summaryUpTo = boundary, lastSummaryAt = now)
            } else {
                it
            }
        })
        // 先把三字段写进会话表，再 persistCurrent：后者只覆盖 messages/updatedAt，字段保得住
        persistCurrent()
        refreshContext()
        // 记一条压缩事件：fromMessages 是压缩前的消息数，foldedMessages 与 boundary 同口径（分界即折叠条数）
        recordContextEvent(
            kind = ContextEventKind.COMPRESS,
            ok = true,
            fromMessages = messages.size,
            foldedMessages = boundary,
            boundary = boundary
        )
        FileLog.log(
            "context",
            "摘要完成：" + (boundary - covered) + " 条原文 → " + text.length + " 字，分界 " + boundary +
                "（前缀 " + prefix.size + " 条，含 system/指令/旧检查点）"
        )
        return true
    }

    /** 给 AgentLoop 的视图信息：摘要文本 + 分界 + 兜底标记；分界失效就当作没有摘要（发全量最安全）。 */
    fun summaryState(): ContextSummaryState {
        val summary = contextSummary
        if (summary.isEmpty()) return ContextSummaryState(fallback = contextFallback)
        val upTo = normalizedSummaryUpTo()
        if (upTo <= 0) return ContextSummaryState(fallback = contextFallback)
        return ContextSummaryState(text = summary, upTo = upTo, fallback = contextFallback)
    }

    /** 落盘的 summaryUpTo 只有在「是 user 消息边界、且在范围内」时才算数；失效一律当 0（全量）。 */
    private fun normalizedSummaryUpTo(): Int {
        val saved = sessions().firstOrNull { it.id == currentSessionId() }?.summaryUpTo ?: 0
        if (saved <= 0 || saved >= messages.size) return 0
        if (messages[saved].role != "user") return 0
        return saved
    }

    // ---- 统计与时间账 ----

    /**
     * 刷新统计里需要遍历消息才得出的那几项（供统计页打开时调用一次）。
     *
     * 上下文水位的口径：真正发给模型的那份请求视图（摘要 + 最近若干轮原文），
     * 就是 refreshContext() 算出的 contextSnapshot.estimatedTokens：界面上的全量历史不算在内；
     * 分母是 ApiDefaults.CONTEXT_WINDOW。不另写估算器。
     */
    fun refreshStats() {
        refreshContext()
        // 总账在内存里读一次就够（load 重复调用是空操作）；顺手兜住「进程刚起来还没读过盘」的情况
        UsageLedger.load(appContext)
        val ledger = UsageLedger.snapshot()
        // 本会话累计的唯一来源是会话记录本身（重启后 stats 是空的，必须从那里读回来）
        val session = sessions().firstOrNull { it.id == currentSessionId() }
        // 分叉会话克隆了父会话的记录：这里报的是洞察页口径，只算自分支起的真实用量，
        // 所以统一减掉分叉时继承的基线（普通会话基线是 0，减 0 等于不减）
        val inherited = session?.inheritedUsage ?: InheritedUsage()
        stats = stats.copy(
            rounds = messages.count { it.role == "user" },
            totalPromptTokens = ((session?.promptTokens ?: 0) - inherited.promptTokens).coerceAtLeast(0),
            totalCompletionTokens = ((session?.completionTokens ?: 0) - inherited.completionTokens)
                .coerceAtLeast(0),
            totalRequests = ((session?.requests ?: 0) - inherited.requests).coerceAtLeast(0),
            contextTokens = contextSnapshot.estimatedTokens,
            contextWindow = ApiDefaults.CONTEXT_WINDOW,
            // 全局历史总账：只增不减，删会话 / 归档 / 清工作区都不动它
            globalPromptTokens = ledger.totalPromptTokens,
            globalCompletionTokens = ledger.totalCompletionTokens,
            globalRequests = ledger.totalRequests,
            todayPromptTokens = ledger.todayPromptTokens,
            todayCompletionTokens = ledger.todayCompletionTokens,
            todayRequests = ledger.todayRequests,
            firstUsedDate = UsageLedger.dateText(ledger.firstUsedAt)
        )
    }

    /**
     * 换了会话（切换 / 新建 / 删除 / 工作区跳转 / 回滚）后统计跟着换：
     * 累计与请求次数从这条会话自己的字段读回来（不再清零，切走再切回来能接上）；
     * 「本次回复步数」和「最近一次用量」归零，轮数与水位按新消息重算。
     */
    fun resetStats() {
        // 整体换一个新快照：steps / last* 归零；
        // 累计与请求次数由 refreshStats 从当前会话的字段读回来（不再清零）
        // 摘要失败的兜底标记是「上一份会话」的状态（回滚也会走到这里），换会话必须清掉，
        // 否则会把别的会话的历史也裁成最近几轮
        contextFallback = false
        stats = UsageStats()
        // refreshStats 内部会调 refreshContext，水位与覆盖条数一起重算
        refreshStats()
        // 时间账也是「这条会话」的：换会话必须从会话记录重新读，不能把上一条的耗时带过来
        syncSessionTiming()
        // 计划与目标同样按会话存：切过去就得换成那条会话的，否则计划页会显示上一条会话的计划
        reloadSessionDocs()
        // 上下文事件也是按会话存的：切过去就该看到那条会话自己的压缩记录
        reloadContextEvents()
    }

    /**
     * 把「本次回复步数」加进本会话累计与全局总账，每次回复结束只调一次
     * （onRound 每轮都会回调，在那里加会重复计）。
     */
    fun commitSteps() {
        val steps = stats.steps
        if (steps <= 0) return
        val id = currentSessionId()
        // 本会话累计步数：落进 sessions.json 的会话记录
        writeSessions(sessions().map {
            if (it.id == id) it.copy(steps = it.steps + steps) else it
        })
        UsageLedger.addSteps(appContext, steps)
    }

    /**
     * 把本次回复的时间账加进会话累计，一次回复只加一次（跟 [commitSteps] 同一个位置）。
     *
     * 加完立刻同步 [sessionTiming]：面板读的是这个快照，等下一次切会话才刷会让人以为数字没动。
     */
    fun commitTiming() {
        val model = turnModelMillis
        val tool = turnToolMillis
        // 本轮输出 tokens 在 writeTurnUsage 之后仍然留着（计数器要到下一次提问才清零），
        // 所以这里能把它和本轮耗时配成一对落进会话记录，供「输出速度」使用
        val lastOutput = turnCompletionTokens
        val active = if (turnStartedAt > 0L) {
            (System.currentTimeMillis() - turnStartedAt).coerceAtLeast(0L)
        } else {
            0L
        }
        turnModelMillis = 0
        turnToolMillis = 0
        // 总账按天：一次回复一次，把六段耗时、活跃时长与工具次数一起记进去
        UsageLedger.recordTiming(
            appContext,
            modelMs = model,
            toolMs = tool,
            activeMs = active,
            ttftMs = turnTtftMillis,
            genMs = turnGenMillis,
            reasoningMs = turnReasoningMillis,
            textMs = turnTextMillis,
            toolArgMs = turnToolArgMillis,
            genTokens = turnGenTokens,
            toolCalls = turnToolCalls
        )
        turnTtftMillis = 0L
        turnGenMillis = 0L
        turnGenTokens = 0
        turnReasoningMillis = 0L
        turnTextMillis = 0L
        turnToolArgMillis = 0L
        turnToolCalls = 0
        turnStartedAt = 0L
        if (model > 0 || tool > 0) {
            val id = currentSessionId()
            writeSessions(sessions().map {
                if (it.id == id) {
                    it.copy(
                        modelMillis = it.modelMillis + model,
                        toolMillis = it.toolMillis + tool,
                        lastTurnModelMillis = model,
                        lastTurnCompletionTokens = lastOutput
                    )
                } else {
                    it
                }
            })
        }
        syncSessionTiming()
    }

    /** 从当前会话记录读回时间账（本会话累计耗时 + 累计输出 tokens）。 */
    fun syncSessionTiming() {
        val session = sessions().firstOrNull { it.id == currentSessionId() }
        sessionTiming = TimingSnapshot(
            modelMillis = session?.modelMillis ?: 0L,
            toolMillis = session?.toolMillis ?: 0L,
            promptTokens = session?.promptTokens ?: 0,
            completionTokens = session?.completionTokens ?: 0,
            lastTurnModelMillis = session?.lastTurnModelMillis ?: 0L,
            lastTurnOutputTokens = session?.lastTurnCompletionTokens ?: 0,
            cacheHitTokens = session?.cacheHitTokens ?: 0,
            cacheMissTokens = session?.cacheMissTokens ?: 0,
            ttftMillis = session?.ttftMillis ?: 0L,
            ttftCount = session?.ttftCount ?: 0,
            genMillis = session?.genMillis ?: 0L,
            genTokens = session?.genTokens ?: 0
        )
        // 洞察页口径：减掉分叉继承的基线，只留自分支起的真实部分。
        // 缓存与速度三类累计（cacheHit/Miss、ttft、gen）洞察页不展示，这里不填，
        // 免得把继承值误当成这条分支自己的数据。
        val inherited = session?.inheritedUsage ?: InheritedUsage()
        insightTiming = TimingSnapshot(
            modelMillis = ((session?.modelMillis ?: 0L) - inherited.modelMillis).coerceAtLeast(0L),
            toolMillis = ((session?.toolMillis ?: 0L) - inherited.toolMillis).coerceAtLeast(0L),
            promptTokens = ((session?.promptTokens ?: 0) - inherited.promptTokens).coerceAtLeast(0),
            completionTokens = ((session?.completionTokens ?: 0) - inherited.completionTokens)
                .coerceAtLeast(0),
            lastTurnModelMillis = ((session?.lastTurnModelMillis ?: 0L) - inherited.lastTurnModelMillis)
                .coerceAtLeast(0L),
            lastTurnOutputTokens = ((session?.lastTurnCompletionTokens ?: 0) -
                inherited.lastTurnCompletionTokens).coerceAtLeast(0)
        )
    }

    /** 全部会话的消息总数，统计页「会话与消息」用；当前会话按内存里的 messages 算，避免落盘滞后。 */
    fun totalMessageCount(): Int =
        sessions().sumOf { if (it.id == currentSessionId()) messages.size else it.messages.size }

    /** 刷新洞察页：上下文水位 + 分段估算 + 全局总账，外加提示词长度趋势。 */
    fun refreshInsight() {
        refreshContext()
        refreshContextBreakdown()
        // 总账在内存里读一次就够（load 重复调用是空操作）
        UsageLedger.load(appContext)
        // 分叉会话：克隆过来的历史请求属于父会话的统计，洞察页只画自分支起的请求
        val inheritedMessages =
            sessions().firstOrNull { it.id == currentSessionId() }?.inheritedUsage?.messages ?: 0
        insightTrend = messages
            .drop(inheritedMessages)
            .filter { it.role == "assistant" && it.promptTokens != 0 }
            .takeLast(INSIGHT_TREND_POINTS)
            .map { it.promptTokens }
    }

    // ---- 本轮 usage 计数 ----

    /**
     * 新的一轮开跑：上一轮的用量已经写进气泡了，这里清零重新累计；
     * [turnStartIndex] 记下本轮起点，收尾写用量时不会写到上一轮的气泡里。
     */
    fun beginTurn() {
        usageText = null
        turnPromptTokens = 0
        turnCompletionTokens = 0
        turnCachedTokens = 0
        turnCacheReported = false
        turnUncachedInputTokens = 0
        turnCacheWriteTokens = null
        turnReasoningTokens = null
        turnUsageModel = usageModel()
        turnStartIndex = messages.size
        // 时间账同样按回合清零：AgentLoop 收尾时会把本回合的模型/工具耗时回调一次
        turnModelMillis = 0
        turnToolMillis = 0
        turnTtftMillis = 0L
        turnGenMillis = 0L
        turnGenTokens = 0
        turnReasoningMillis = 0L
        turnTextMillis = 0L
        turnToolArgMillis = 0L
        turnToolCalls = 0
        turnStartedAt = System.currentTimeMillis()
        // 新的一次提问：本次回复步数归零；累计 token 与轮数保留（它们是整个会话的）
        stats = stats.copy(steps = 0)
        // 发送这一刻先对一次水位：状态行与圆环读的是 contextSnapshot，本轮跑完还会再刷一次
        refreshStats()
    }

    /** 新一轮开始：记录本次回复已跑到的最大轮次（会话累计由 [commitSteps] 加一次）。 */
    fun noteRound(round: Int) {
        // 步数取本次回复跑过的最大轮次（不是当前轮次，循环退出时不至于回落）
        stats = stats.copy(steps = maxOf(stats.steps, round))
    }

    /** 清掉最近一次请求的用量文本（新提问 / 回滚时用）。 */
    fun clearUsageText() {
        usageText = null
    }

    /** 循环收尾回调一次：只累计本次回复的时间账，落会话记录在 [commitTiming] 里做。 */
    fun addTurnTiming(modelMillis: Long, toolMillis: Long) {
        turnModelMillis += modelMillis
        turnToolMillis += toolMillis
    }

    /**
     * 输出侧账目（首字等待 / 生成时长 / 输出 tokens）：跟 [addTurnTiming] 一起落进会话记录。
     *
     * 落盘而不是只放内存：这两个数决定状态行上的 tok/s 与浮窗里的 TTFT，重启一次就归零
     * 会让人以为数字坏了。落盘的时机就是这里：一次回复一次，不会写得太勤。
     */
    fun recordOutputTiming(timing: OutputTiming) {
        if (timing.ttftCount == 0 && timing.genTokens == 0 && timing.toolCalls == 0) return
        // 先攒在本轮：commitTiming 落总账时一起写（活跃时长在那里才有终点）
        turnTtftMillis = timing.ttftMillis
        turnGenMillis = timing.genMillis
        turnGenTokens = timing.genTokens
        turnReasoningMillis = timing.reasoningMillis
        turnTextMillis = timing.textMillis
        turnToolArgMillis = timing.toolArgMillis
        turnToolCalls = timing.toolCalls
        val id = currentSessionId()
        writeSessions(sessions().map {
            if (it.id == id) {
                it.copy(
                    ttftMillis = it.ttftMillis + timing.ttftMillis,
                    ttftCount = it.ttftCount + timing.ttftCount,
                    genMillis = it.genMillis + timing.genMillis,
                    genTokens = it.genTokens + timing.genTokens,
                    reasoningMillis = it.reasoningMillis + timing.reasoningMillis,
                    textMillis = it.textMillis + timing.textMillis,
                    toolArgMillis = it.toolArgMillis + timing.toolArgMillis
                )
            } else {
                it
            }
        })
        persistCurrent()
        syncSessionTiming()
    }

    /**
     * 把本轮累计的 usage 写进本轮最后一条 assistant 消息。
     *
     * 为什么只写最后一条：一轮里的工具调用会产生多条 assistant，胶囊只在气泡底部显示一行，
     * 每条都写就会冒出好几行。[turnStartIndex] 保证不会写到上一轮（插话续跑那种
     * 不追加用户消息的回合也一样）。
     */
    fun writeTurnUsage() {
        if (turnPromptTokens <= 0 && turnCompletionTokens <= 0) return
        for (i in messages.lastIndex downTo turnStartIndex) {
            if (i < 0) return
            if (messages[i].role != "assistant") continue
            messages[i] = messages[i].copy(
                promptTokens = turnPromptTokens,
                completionTokens = turnCompletionTokens,
                cachedTokens = turnCachedTokens,
                usageCacheReported = turnCacheReported,
                uncachedInputTokens = turnUncachedInputTokens,
                cacheWriteTokens = turnCacheWriteTokens,
                reasoningTokens = turnReasoningTokens,
                usageModel = turnUsageModel
            )
            return
        }
    }

    /**
     * 收到一次服务端 usage：累加本轮与会话用量、写全局总账，并立刻刷新水位与状态行。
     *
     * 服务端没报的字段（缓存、推理）不累加、也不写成 0，界面上按「未知」显示。
     */
    fun recordUsage(usage: UsageBuckets) {
        val promptTokens = usage.promptTokens
        val completionTokens = usage.completionTokens
        usageText = "输入 " + promptTokens + " / 输出 " + completionTokens + " tokens"
        // 气泡里要的是整轮总账：单个请求的数字只在这里加总，收尾时一次写进最后一条 assistant
        turnPromptTokens += promptTokens
        turnCompletionTokens += completionTokens
        turnUncachedInputTokens += usage.uncachedInputTokens
        // 缓存读写与推理只认服务端真报过的那次：没报就不累加，面板上整行不显示而不是写 0%
        if (usage.cacheReadReported) {
            turnCachedTokens += usage.cacheReadTokens
            turnCacheReported = true
        }
        usage.cacheWriteTokens?.let { turnCacheWriteTokens = (turnCacheWriteTokens ?: 0) + it }
        usage.reasoningTokens?.let { turnReasoningTokens = (turnReasoningTokens ?: 0) + it }
        // 缓存命中 / 未命中只认服务端真的报过的那次：没报就别记，否则界面上会冒出一个
        // 看着像「缓存一次都没命中」的 0%，其实只是这家服务商不给这个字段
        val hitTokens = if (usage.cacheReadReported) usage.cacheReadTokens else 0
        val missTokens = if (usage.cacheReadReported) usage.uncachedInputTokens else 0
        // 计费构成：这次请求视图的估算占比 × 真实 prompt tokens；输出直接取 completion
        val scaled = scaledComposition(promptTokens, completionTokens)
        // 本会话计数写回当前会话那条记录（切会话不丢、回来能接上），随后落盘
        val id = currentSessionId()
        writeSessions(sessions().map {
            if (it.id == id) {
                it.copy(
                    promptTokens = it.promptTokens + promptTokens,
                    completionTokens = it.completionTokens + completionTokens,
                    requests = it.requests + 1,
                    cacheHitTokens = it.cacheHitTokens + hitTokens,
                    cacheMissTokens = it.cacheMissTokens + missTokens,
                    composition = it.composition + scaled
                )
            } else {
                it
            }
        })
        persistCurrent()
        // 全局总账：只增不减，跟删会话 / 归档无关，记在同一次 usage 里
        UsageLedger.record(
            context = appContext,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cacheHitTokens = hitTokens,
            cacheMissTokens = missTokens,
            composition = LedgerComposition(
                system = scaled.system,
                skills = scaled.skills,
                tools = scaled.tools,
                user = scaled.user,
                inject = scaled.inject,
                assistant = scaled.assistant,
                toolResults = scaled.toolResults,
                output = scaled.output
            )
        )
        val ledger = UsageLedger.snapshot()
        // 统计页：最近一次取本次值，本会话累计取会话字段，全局取总账快照
        stats = stats.copy(
            lastPromptTokens = promptTokens,
            lastCompletionTokens = completionTokens,
            totalPromptTokens = stats.totalPromptTokens + promptTokens,
            totalCompletionTokens = stats.totalCompletionTokens + completionTokens,
            totalRequests = stats.totalRequests + 1,
            globalPromptTokens = ledger.totalPromptTokens,
            globalCompletionTokens = ledger.totalCompletionTokens,
            globalRequests = ledger.totalRequests,
            todayPromptTokens = ledger.todayPromptTokens,
            todayCompletionTokens = ledger.todayCompletionTokens,
            todayRequests = ledger.todayRequests,
            firstUsedDate = UsageLedger.dateText(ledger.firstUsedAt)
        )
        // 水位跟着这次请求的上下文重算：一次回复里可能问模型好几次，圆环与状态行必须在
        // 这条回复跑的过程中就往前走，不能等到整轮收尾或者切会话才有变化
        refreshContext()
        // 会话累计输出 tokens 变了：时间账面板读的是 sessionTiming，这里顺手同步
        syncSessionTiming()
    }

    /**
     * 把「本次请求视图的估算构成」折算成计费构成。
     *
     * 服务端给的 prompt tokens 是真实总额，这里按七段估算占比分摊；输出直接取 completion。
     * 先重算一次 breakdown：一次回复里工具结果会变，拿旧快照分摊会让各段比例失真。
     */
    private fun scaledComposition(promptTokens: Int, completionTokens: Int): SessionComposition {
        refreshContextBreakdown()
        val b = contextBreakdown
        val total = b.totalTokens
        if (total <= 0) return SessionComposition(output = completionTokens)
        fun part(value: Int): Int =
            (value.toDouble() * promptTokens / total).roundToInt().coerceAtLeast(0)
        return SessionComposition(
            system = part(b.systemTokens),
            // Skill 段的单独拆分在 PromptBuilder 分段接口里补（当前先记 0，后续批次启用）
            skills = 0,
            tools = part(b.toolTokens),
            user = part(b.userTokens),
            // 摘要属于「注入内容」
            inject = part(b.summaryTokens),
            assistant = part(b.assistantTokens),
            toolResults = part(b.toolResultTokens),
            output = completionTokens
        )
    }

    /** 本轮（从 turnStartIndex 起）有没有工具结果：用它判断模型是不是真的动手了。 */
    fun turnUsedTools(): Boolean {
        for (i in messages.lastIndex downTo turnStartIndex) {
            if (i < 0) return false
            if (messages[i].role == "tool") return true
        }
        return false
    }

    // ---- 状态行的三个派生值 ----

    /**
     * 状态行显示的轮数：界面历史里用户问过几次。
     *
     * 为什么不用 stats.rounds：那个只有进统计页时才刷，状态行会一直停在旧数字上。
     */
    val statusRounds: Int get() = messages.count { it.role == "user" }

    /**
     * 状态行显示的步数：本会话已提交的步数 + 本次回复跑过的步数。
     *
     * 为什么要把两段加起来：stats.steps 只是「本次回复」，每次新提问都会归零，
     * 只看它会让状态行在提问的一瞬间掉回 0。
     */
    val statusSteps: Int get() =
        (sessions().firstOrNull { it.id == currentSessionId() }?.steps ?: 0) + stats.steps

    /**
     * 洞察页显示的步数：跟 [statusSteps] 同一口径，但减掉分叉继承的步数。
     *
     * 状态行要「克隆会话」的观感（步数继承），洞察页只报自分支起的真实步数。
     */
    val insightSteps: Int
        get() {
            val inherited =
                sessions().firstOrNull { it.id == currentSessionId() }?.inheritedUsage ?: InheritedUsage()
            return (statusSteps - inherited.steps).coerceAtLeast(0)
        }

    /**
     * 本会话的「活跃时长」：第一条与最后一条消息的时间差。
     *
     * 为什么不用累计耗时：那只是模型和工具真的在跑的时间，中间放着不管的几小时不算；
     * 洞察页要的是「这段对话跨了多久」，所以取消息时间轴的两端。没消息就是 0。
     */
    val statusActiveMillis: Long
        get() {
            if (messages.isEmpty()) return 0L
            return (messages.last().createdAt - messages.first().createdAt).coerceAtLeast(0L)
        }
}

/**
 * 上下文分段估算（粗估 tokens，口径同 [ContextCompactor]）。
 *
 * 为什么把「消息」拆成 user / assistant / tool 三段：历史里三者的增长速度完全不同
 * （工具结果才是真正的大头），只给一个合计看不出该压缩谁。
 *
 * 六段合起来才是**整份请求**（提示词 + 摘要 + 工具表 + 三类消息），
 * 水位那条只按「消息 + 摘要」算，两者口径不同、数值不等，界面上必须分开说明。
 */
data class Breakdown(
    /** 系统提示词正文（**不含**会话摘要）。 */
    val systemTokens: Int = 0,
    /** 会话摘要：它是拼进 system 的一段，单列出来，否则「提示词」会显得莫名其妙地大。 */
    val summaryTokens: Int = 0,
    val toolTokens: Int = 0,
    val userTokens: Int = 0,
    val assistantTokens: Int = 0,
    val toolResultTokens: Int = 0
) {
    /** 三段的合计（旧界面口径「消息占多少」，也是水位里除提示词与工具表之外的部分）。 */
    val messageTokens: Int get() = userTokens + assistantTokens + toolResultTokens

    /** 整份请求的估算：提示词 + 摘要 + 工具表 + 三类消息（= 成分条六段之和）。 */
    val totalTokens: Int
        get() = systemTokens + summaryTokens + toolTokens + messageTokens
}

/**
 * 本会话的时间账：模型等待 / 工具执行各自的累计毫秒数，外加会话累计输出 tokens。
 *
 * 分母都是「本会话」，不是全局：用户看的是这条会话到底把时间花在哪。
 */
data class TimingSnapshot(
    val modelMillis: Long = 0,
    val toolMillis: Long = 0,
    /** 本会话累计的输入 / 输出 tokens（状态行右侧那枚胶囊报的是两者之和）。 */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** 上一轮回复的模型耗时与输出 tokens：输出速度只用这一对算（累计值在旧会话里是残缺的）。 */
    val lastTurnModelMillis: Long = 0,
    val lastTurnOutputTokens: Int = 0,
    /** 提示词缓存的累计命中 / 未命中输入 tokens。 */
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    /** 首 token 等待累计与次数、纯生成时长与输出 tokens（速度与 TTFT 的分母）。 */
    val ttftMillis: Long = 0,
    val ttftCount: Int = 0,
    val genMillis: Long = 0,
    val genTokens: Int = 0
) {
    /** 首 token 平均（毫秒）；一次都没等到过就是 null，界面显示「—」。 */
    val ttftAverageMillis: Long? get() = if (ttftCount > 0) ttftMillis / ttftCount else null

    /**
     * 输出速度（tok/s）：分子是输出 tokens，分母只算「第一个字到最后一个字」。
     *
     * 为什么**不**用累计模型耗时：那里面含等首字的时间，网络慢的时候会把速度压得莫名其妙。
     */
    val tokensPerSec: Int?
        get() = if (genMillis > 0L && genTokens > 0) ((genTokens * 1000L) / genMillis).toInt() else null

    /** 缓存命中率（0–100）；服务端从没报过缓存字段就是 null，界面显示「—」。 */
    val cacheHitPercent: Float?
        get() {
            val total = cacheHitTokens + cacheMissTokens
            return if (total > 0) cacheHitTokens * 100f / total else null
        }
}
