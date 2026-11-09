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
import com.zmf.agent.capability.context.validSummaryUpTo
import com.zmf.agent.capability.loop.ContextSummaryState
import com.zmf.agent.capability.loop.OutputTiming
import com.zmf.agent.capability.prompt.AgentInstructions
import com.zmf.agent.capability.prompt.PromptBuilder
import com.zmf.agent.capability.prompt.PromptInput
import com.zmf.agent.capability.tools.ToolRegistry
import com.zmf.agent.core.llm.ApiDefaults
import com.zmf.agent.core.llm.CostBook
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.InheritedUsage
import com.zmf.agent.core.store.InsightColors
import com.zmf.agent.core.store.InsightDay
import com.zmf.agent.core.store.InsightGlobalView
import com.zmf.agent.core.store.InsightPart
import com.zmf.agent.core.store.InsightRange
import com.zmf.agent.core.store.InsightSessionRow
import com.zmf.agent.core.store.InsightSessionView
import com.zmf.agent.core.store.InsightTiming
import com.zmf.agent.core.store.LedgerComposition
import com.zmf.agent.core.store.LedgerSnapshot
import com.zmf.agent.core.store.LedgerUsage
import com.zmf.agent.core.store.merge
import com.zmf.agent.core.store.SessionComposition
import com.zmf.agent.core.store.UsageCost
import com.zmf.agent.core.store.UsageLedger
import com.zmf.agent.core.store.hourlyBucketsOf
import com.zmf.agent.core.store.legacyCoversRange
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
import java.util.Calendar

/** 洞察页趋势只显示最近这几次回复，数量过多将无法辨识变化。 */
private const val INSIGHT_TREND_POINTS = 20

/**
 * 用量折线最多显示的点数。
 *
 * 取值需容纳「近 30 天」，否则会被提前合并。跨度超过上限时按等宽分桶合并邻近日期，
 * 只保留趋势信息。
 */
private const val SERIES_MAX_POINTS = 31

/**
 * 溢出恢复的等待上限。
 *
 * 摘要请求自身超时为 60 秒（ContextSummarizer.TIMEOUT_MS），此处再放宽 30 秒；
 * 超过仍未回调说明链路异常，应放弃本轮。
 */
private const val OVERFLOW_COMPACT_TIMEOUT_MS = 90_000L

/**
 * 上下文与用量的状态与动作：策略、水位、摘要压缩、统计快照、时间账、本轮 usage 计数。
 *
 * 跨模块依赖通过构造参数注入（消息表、会话表及其写入口、当前会话 id、请求链路、
 * 工作区根目录、落盘入口），避免本类直接持有整个 ViewModel。
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

    /** 上下文压缩策略（摘要式滚动压缩）：只作用于请求视图，不影响界面历史。 */
    var contextPolicy by mutableStateOf(ContextStore.load(appContext))
        private set

    /** 上下文快照（估算 tokens / 水位 / 覆盖条数）；由 [refreshContext] 算，界面只读。 */
    var contextSnapshot by mutableStateOf(ContextSnapshot())
        private set

    /** 当前会话摘要文本；空串 = 没有摘要，请求视图发全量。直接读会话表，落盘即生效。 */
    val contextSummary: String
        get() = sessions().firstOrNull { it.id == currentSessionId() }?.contextSummary.orEmpty()

    /** 摘要失败后置为 true：请求视图退化为只发送最近 fallbackKeepRounds 轮，不阻塞主请求。 */
    private var contextFallback = false

    /** 上下文分段估算（系统提示词 / 工具表 / user / assistant / 工具结果）；由 [refreshContext] 同时刷新。 */
    var contextBreakdown by mutableStateOf(Breakdown())
        private set

    /** 当前会话的摘要/压缩事件（时间正序，只读展示）；换会话与每次压缩动作后刷新。 */
    var contextEventList by mutableStateOf<List<ContextEvent>>(emptyList())
        private set

    /**
     * 最近一次请求的用量文本。
     *
     * 气泡中已内嵌「输入 / 输出」，该字段不渲染；保留仅为兼容旧数据。
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
     * 状态行需呈现「克隆会话」的观感（命中率、token 均继承），洞察页只报真实数据，
     * 两者口径不同，因此拆成两份快照。
     */
    var insightTiming by mutableStateOf(TimingSnapshot())
        private set

    /** 洞察页的提示词长度趋势（最近若干次回复的输入 tokens）；由 [refreshInsight] 刷新。 */
    var insightTrend by mutableStateOf<List<Int>>(emptyList())
        private set

    /**
     * 本轮（这一次回复）累计的 usage：[recordUsage] 每收到一次就累加，收尾时一次性写入本轮最后一条 assistant。
     *
     * 一轮内可能多次请求模型（每次工具调用后都需再次请求），气泡只显示整轮总量，
     * 因此先累计，待 [writeTurnUsage] 时再写入。
     */
    private var turnPromptTokens = 0
    private var turnCompletionTokens = 0
    /** 本轮命中缓存的输入 tokens，以及服务端是否上报该项（未上报时面板整行不显示）。 */
    private var turnCachedTokens = 0
    private var turnCacheReported = false
    /** 本轮未缓存输入、缓存写入、推理 tokens：仅部分模型提供这几项数据。 */
    private var turnUncachedInputTokens = 0
    private var turnCacheWriteTokens: Int? = null
    private var turnReasoningTokens: Int? = null
    /** 本轮使用的「提供方/模型」，在开始执行时固定，面板据此显示。 */
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

    /** 本轮起点（毫秒）：用于计算总账的活跃时长（一次回复的实际耗时）。 */
    private var turnStartedAt = 0L

    // ---- 上下文摘要（滚动压缩） ----

    /** 设置页修改策略：限制到合法范围、落盘、立即重算快照（下一次请求使用新策略）。 */
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
        // 分段估算在末尾同时刷新一次：便于判断水位主要由系统提示词还是消息历史占用
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
     * 重算上下文分段估算：system 用同一份 [promptInput] 重新拼接（PromptBuilder 为纯函数），
     * tool 按工具名、描述与参数 JSON 估算，user / assistant / toolResult 按角色拆分统计。
     * 各段相互独立，合计不保证等于水位（摘要抬头同时计入 system 与请求视图）。
     */
    fun refreshContextBreakdown() {
        val summary = contextSummary
        val upTo = if (summary.isEmpty()) 0 else normalizedSummaryUpTo()
        val request = requestView(requestStart(summary, upTo), summary, upTo)
        val summaryText = if (summary.isNotEmpty() && upTo > 0) summary else ""
        // planMode 由 promptInput() 自带（与 AgentLoop 同源）；摘要单独计量，避免将摘要计入提示词段
        val promptOnly = PromptBuilder.build(promptInput().copy(summaryText = ""))
        val skillTokens = PromptBuilder.skillSectionTokens(promptInput())
        val systemTokens = ((promptOnly?.let { ContextCompactor.estimateText(it) } ?: 0) - skillTokens)
            .coerceAtLeast(0)
        val summaryTokens = if (summaryText.isBlank()) {
            0
        } else {
            ContextCompactor.estimateText(contextSummarySystemText(summaryText))
        }
        // 工具表按 name、描述与参数 JSON 估算；不构造 JSON 可避免参数非法导致异常。
        // 工具表与真实请求同源（见 ToolRegistry.specs 的说明），估算才不会偏
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
                // 摘要抬头已计入 system 段，此处跳过 system 以免重复
                "user" -> userTokens += ContextCompactor.estimate(message)
                "assistant" -> assistantTokens += ContextCompactor.estimate(message)
                "tool" -> toolResultTokens += ContextCompactor.estimate(message)
                else -> Unit
            }
        }
        contextBreakdown = Breakdown(
            systemTokens = systemTokens,
            skillTokens = skillTokens,
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
     * 执行压缩（不检查是否正在流式输出）。
     *
     * 溢出恢复必须在流式输出过程中调用：该轮已被提供方按上下文超限拒绝，不压缩则无法重发；
     * 「两路请求争用同一会话」仅对用户手动压缩成立。
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
                // 手动路径同样不应抛出：置兜底标记，请求视图退化为最近几轮
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
     * AgentLoop 的溢出恢复回调：压缩一次上下文，成功后允许重发同一轮。
     *
     * summarizeNow 为回调式 API，任一分支遗漏回调都会使本轮永久挂起；超时最坏仅本次未压缩成功，
     * AgentLoop 会将提供方原文返回给用户。此处走 [startCompaction] 以避开「流式中不压缩」的限制。
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
        // 清除前的分界需留存记录：该条清除后即不存在，事件中需记录当时压缩至何处
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
     * 待其切换会话再切回后才出现，等同于提示「本次压缩未记录」。
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
     * 合并为一条则无法按类型分别过滤，页面也无法明确说明。
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
     * 前提是「分界确实前移」：无可摘要内容时不触发，避免空转并重复调用模型。
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
            // 仅内置 DeepSeek 允许回退至官方端点；其他厂商的空地址由 provider 明确报错
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
            // 失败兜底：请求视图仅发送最近 fallbackKeepRounds 轮，绝不阻塞，也不影响主请求
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

    /** 落盘的 summaryUpTo 只有在「是 user 消息边界、且在范围内」时才算数；校验规则见 [validSummaryUpTo]。 */
    private fun normalizedSummaryUpTo(): Int {
        val saved = sessions().firstOrNull { it.id == currentSessionId() }?.summaryUpTo ?: 0
        return validSummaryUpTo(messages, saved)
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
        // 总账在内存中读取一次即可（重复调用 load 为空操作）；同时覆盖「进程刚启动尚未读盘」的情况
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
     * 累计与请求次数从该会话自身的字段读取（不清零，切换离开后返回仍可接续）；
     * 「本次回复步数」和「最近一次用量」归零，轮数与水位按新消息重算。
     */
    fun resetStats() {
        // 整体换一个新快照：steps / last* 归零；
        // 累计与请求次数由 refreshStats 从当前会话字段读取（不执行清零）
        // 摘要失败的兜底标记是「上一份会话」的状态（回滚也会走到这里），换会话必须清掉，
        // 否则会将其他会话的历史同样裁剪为最近几轮
        contextFallback = false
        stats = UsageStats()
        // refreshStats 内部会调 refreshContext，水位与覆盖条数一起重算
        refreshStats()
        // 时间账也是「这条会话」的：换会话必须从会话记录重新读，不能把上一条的耗时带过来
        syncSessionTiming()
        // 计划与目标同样按会话存储：切换后需替换为该会话的数据，否则计划页会显示上一会话的计划
        reloadSessionDocs()
        // 上下文事件同样按会话存储：切换后应显示该会话自身的压缩记录
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
        // 避免将继承值误认为该分支自身的数据。
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
        // 缓存命中 / 未命中仅认可服务端实际上报的那一次：未上报则不记录，否则界面会显示一个
        // 看似「缓存一次都未命中」的 0%，实际仅因该服务商不提供此字段
        val hitTokens = if (usage.cacheReadReported) usage.cacheReadTokens else 0
        val missTokens = if (usage.cacheReadReported) usage.uncachedInputTokens else 0
        // 计费构成：这次请求视图的估算占比 × 真实 prompt tokens；输出直接取 completion
        val scaled = scaledComposition(promptTokens, completionTokens)
        // 本会话计数写回当前会话记录（切换会话不丢失、返回后可接续），随后落盘
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
        val now = System.currentTimeMillis()
        val cost = CostBook.costOf(
            usageModel = turnUsageModel,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = hitTokens,
            uncachedTokens = missTokens,
            cacheWriteTokens = usage.cacheWriteTokens ?: 0,
            atMillis = now
        )
        UsageLedger.record(
            context = appContext,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cacheHitTokens = hitTokens,
            cacheMissTokens = missTokens,
            costCny = cost ?: 0.0,
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
        // 会话累计输出 tokens 变化：时间账面板读取 sessionTiming，此处同步更新
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
            skills = part(b.skillTokens),
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
            // 分叉会话的首条消息是父会话克隆来的：从分叉边界后第一条算起，才只算这条分支真正用掉的时间
            val inherited = sessions().firstOrNull { it.id == currentSessionId() }
                ?.inheritedUsage?.messages ?: 0
            if (inherited >= messages.size) return 0L
            val first = messages[inherited].createdAt
            return (messages.last().createdAt - first).coerceAtLeast(0L)
        }

    /**
     * 洞察页的轮次：只数分叉边界之后的 user 消息。
     *
     * 统计口径与正常任务一致：真人输入与定时系统唤醒都算一轮，唤醒来源不同而已；
     * App 其他代发（计划页按钮、后台任务结算）不算。分叉克隆的历史不计入。
     */
    val insightRounds: Int
        get() {
            val inherited = sessions().firstOrNull { it.id == currentSessionId() }
                ?.inheritedUsage?.messages ?: 0
            return messages.drop(inherited).count { it.countsAsRound }
        }

    // ---- 洞察页数据装配（页面只读，不做业务判断） ----

    /**
     * 组装本会话模块的数据。
     *
     * 当前会话用内存里的消息（含还没落盘的）；其余会话读它自己的消息。
     * 分叉会话一律从 `inheritedUsage.messages` 之后算，克隆段不进任何统计。
     */
    fun insightSession(sessionId: String): InsightSessionView {
        val session = sessions().firstOrNull { it.id == sessionId }
            ?: return InsightSessionView()
        val isCurrent = sessionId == currentSessionId()
        val all = if (isCurrent) messages.toList() else session.messages
        val start = session.inheritedUsage.messages.coerceIn(0, all.size)
        val own = all.drop(start)
        val summary = session.contextSummary
        // 分界校验与聊天页同一份实现，避免两处对「user 边界 / 范围」的判断分叉
        val upTo = if (summary.isEmpty()) 0 else validSummaryUpTo(all, session.summaryUpTo)
        val breakdown: Breakdown
        val estimated: Int
        if (isCurrent) {
            breakdown = contextBreakdown
        } else {
            val request = requestFor(all, summary, upTo)
            breakdown = breakdownFor(request, if (upTo > 0) summary else "")
        }
        // 水位按整份请求算（提示词 + 技能 + 摘要 + 工具表 + 三类消息），
        // 与成分条同一口径，避免「成分加起来比水位大几倍」
        estimated = breakdown.totalTokens
        val parts = listOf(
            InsightPart("系统提示词", breakdown.systemTokens, InsightColors.SYSTEM),
            InsightPart("工具定义", breakdown.toolTokens, InsightColors.TOOLS),
            InsightPart("用户消息", breakdown.userTokens, InsightColors.USER),
            InsightPart("注入内容", breakdown.summaryTokens, InsightColors.INJECT),
            InsightPart("技能注入", breakdown.skillTokens, InsightColors.SKILLS),
            InsightPart("助手消息", breakdown.assistantTokens, InsightColors.ASSISTANT),
            InsightPart("工具结果", breakdown.toolResultTokens, InsightColors.TOOL_RESULTS)
        )
        var cacheHit = 0
        var cacheMiss = 0
        var toolCalls = 0
        var hasOwnUsage = false
        for (message in own) {
            when (message.role) {
                "tool" -> toolCalls++
                "assistant" -> {
                    if (message.usageModel.isNotBlank() &&
                        (message.promptTokens > 0 || message.completionTokens > 0)
                    ) {
                        hasOwnUsage = true
                    }
                    if (message.usageCacheReported) {
                        cacheHit += message.cachedTokens
                        cacheMiss += message.uncachedInputTokens
                    }
                }
            }
        }
        val cacheRate = if (cacheHit + cacheMiss > 0) {
            cacheHit.toFloat() / (cacheHit + cacheMiss)
        } else {
            null
        }
        val inherited = session.inheritedUsage
        val ttft = (session.ttftMillis - inherited.ttftMillis).coerceAtLeast(0L)
        val reasoning = (session.reasoningMillis - inherited.reasoningMillis).coerceAtLeast(0L)
        val textRaw = (session.textMillis - inherited.textMillis).coerceAtLeast(0L)
        val toolArg = (session.toolArgMillis - inherited.toolArgMillis).coerceAtLeast(0L)
        // 老会话只有 genMillis（思考+正文合在一起）：把「新细分没有覆盖到」的那部分补进模型回复，
        // 避免整段旧生成时间归入「其他开销」
        val genMs = (session.genMillis - inherited.genMillis).coerceAtLeast(0L)
        val genLegacy = genMs
        val detailedGen = reasoning + textRaw + toolArg
        val text = textRaw + (genLegacy - detailedGen).coerceAtLeast(0L)
        val toolMs = (session.toolMillis - inherited.toolMillis).coerceAtLeast(0L)
        // 洞察页的「活跃时长」= 模型流 + 工具执行（DSH 同口径），
        // 不使用消息首末差值：该跨度包含数日闲置时间，会使耗时环几乎全部归入「其他」
        val modelMs = (session.modelMillis - inherited.modelMillis).coerceAtLeast(0L)
        val active = modelMs + toolMs
        // 输出速度：与状态行、全局卡片同一口径——分母只算「第一个字到最后一个字」。
        // 早前这里用的是上一轮模型总耗时（含等首字），2 个字的回复会算出 1 tok/s 这种噪声值
        val genOwn = (session.genTokens - inherited.genTokens).coerceAtLeast(0)
        val speed = if (hasOwnUsage && genMs > 0 && genOwn > 0) {
            (genOwn * 1000L / genMs).toInt()
        } else {
            null
        }
        val window = ApiDefaults.CONTEXT_WINDOW
        return InsightSessionView(
            sessionId = sessionId,
            title = if (isCurrent) "当前上下文" else session.title.ifBlank { "新会话" },
            isCurrent = isCurrent,
            window = window,
            estimatedTokens = estimated,
            ratio = if (window > 0) estimated.toFloat() / window else 0f,
            parts = parts,
            tokenComposition = session.composition.takeIf { !it.isEmpty },
            cacheRate = cacheRate,
            requests = (session.requests - inherited.requests).coerceAtLeast(0),
            activeMillis = active,
            ttftMillis = ttft,
            reasoningMillis = reasoning,
            textMillis = text,
            toolArgMillis = toolArg,
            toolMillis = toolMs,
            outputSpeed = speed,
            // 后台执行只写最终问答，工具中间态不落消息：那部分次数记在会话字段上，两处相加才是全量
            toolCalls = toolCalls + (session.toolCalls - inherited.toolCalls).coerceAtLeast(0)
        )
    }

    /**
     * 组装全局统计区的数据。
     *
     * 口径（2026-10-31 定）：**四个范围都从按天账本求和**，不许再让「全部」去读 `total`——
     * 那样做会得到两套互不相干的数字（`total` 含 v1 时代累计，`days` 只有 v2 起的明细），
     * 表现就是「全部 6.1M / 近 30 天 23K」。历史遗留（`total − Σdays`）作为差额项，
     * 只有范围覆盖到首次使用日时才计入，并摊进折线图最早那几档。
     *
     * **与 dsh 的一处有意差异**：dsh 的全局是「现存各会话之和」，会话被删除后从全局消失；
     * 本应用以账本为准，已删除会话消耗的 token、时长与费用仍计入历史。这是设计选择：
     * 花费真实发生过，不因删除会话而消失。不要为了与 dsh 对齐把这里改成按会话求和。
     */
    fun globalInsight(range: InsightRange): InsightGlobalView {
        UsageLedger.load(appContext)
        val ledger = UsageLedger.snapshot()
        val now = System.currentTimeMillis()
        val fromMs = rangeStart(range)
        val fromDate = UsageLedger.dateText(fromMs)
        val sum = ledger.days
            .filterKeys { range == InsightRange.ALL || it >= fromDate }
            .values
            .fold(LedgerUsage()) { acc, day -> acc.merge(day) }
        // 遗留项表示「第一条有明细的日期之前」的历史。判定见 [legacyCoversRange]：
        // 折线摊分必须用同一份，否则「近 7 天」的图会比上面的区间总量多出一整块历史
        val legacy = ledger.legacy.takeIf {
            legacyCoversRange(range == InsightRange.ALL, ledger.firstUsageDay, fromDate)
        } ?: LedgerUsage()
        val usage = sum.merge(legacy)
        val composition = usage.composition
        val ttft = usage.ttftMs
        val reasoning = usage.reasoningMs
        val text = usage.textMs
        val toolArg = usage.toolArgMs
        val toolExec = usage.toolMs
        val toolCalls = usage.toolCalls
        val hit = usage.cacheHit
        val miss = usage.cacheMiss
        val tokens = usage.prompt.toLong() + usage.completion
        val requests = usage.requests
        val rounds = usage.rounds
        val genMs = usage.genMs
        val genTokens = usage.genTokens
        val costCny = usage.costCny
        val cacheRate = if (hit + miss > 0) hit.toFloat() / (hit + miss) else null
        // 与 dsh 同口径：timing.wallMs 是各会话「模型流 + 工具执行」之和，全局即各会话相加。
        // 不使用逐轮的墙钟差值：那里面含重试等待与工具间的空闲，会把「其他开销」撑成主体
        val activeWork = usage.modelMs + usage.toolMs
        val other = (activeWork - ttft - reasoning - text - toolArg - toolExec).coerceAtLeast(0L)
        val speed = if (genMs > 0 && genTokens > 0) {
            (genTokens * 1000L / genMs).toInt()
        } else {
            null
        }
        // 未唤醒的空壳会话不进入统计：它们从未产生请求，计入会话数会把
        // 「新建但没说话」的空壳算成一次使用，列表与计数都会失真。
        // 不能只看 blank：旧数据没有该键，历史里仍会留下 0 消息的「新会话」。
        val all = sessions().filter { it.awakened }
        val rows = all.map { session -> globalRow(session, fromMs, now) }
            .sortedByDescending { it.updatedAt }
        val series = usageSeries(ledger, range, now)
        return InsightGlobalView(
            range = range,
            sessions = all.size,
            archived = all.count { it.archived },
            usageSessions = rows.count { it.tokens > 0 },
            totalTokens = tokens,
            rounds = rounds,
            costCny = costCny.takeIf { it > 0.0 },
            pricedSessions = UsageCost.pricedSessionCount(all, fromMs, now),
            cacheRate = cacheRate,
            toolCalls = toolCalls,
            toolMillis = toolExec,
            activeMillis = activeWork,
            requests = requests,
            series = series,
            // 今天的折线画到当前小时为止；其余范围画满
            seriesEnd = if (range == InsightRange.TODAY) hourOfDay(now) else -1,
            composition = composition.takeIf { !it.isEmpty },
            timing = InsightTiming(
                ttft = ttft,
                reasoning = reasoning,
                text = text,
                toolArg = toolArg,
                toolExec = toolExec,
                other = other,
                active = activeWork
            ),
            outputSpeed = speed,
            rows = rows
        )
    }

    /**
     * 按范围生成折线图序列。
     *
     * 「今天」按整点给出 0 时至 23 时的 24 个点：一天只有一个按天数据，按天序列在这里没有信息量，
     * 而「这一天怎么用的」只有分时才能回答。其余范围沿用按天序列，「全部」在跨度超过
     * [SERIES_MAX_POINTS] 时**等宽分桶**合并邻近若干天——分桶不是滑动平均，所以各档相加仍等于
     * 区间总量（线上看不出「总量对不上」）。
     */
    private fun usageSeries(
        ledger: LedgerSnapshot,
        range: InsightRange,
        now: Long
    ): List<InsightDay> {
        if (range == InsightRange.TODAY) return todayHourSeries(ledger, now)
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val todayCal = calendar.clone() as Calendar
        when (range) {
            InsightRange.WEEK -> calendar.add(Calendar.DAY_OF_YEAR, -6)
            InsightRange.MONTH -> calendar.add(Calendar.DAY_OF_YEAR, -29)
            InsightRange.ALL -> {
                val first = if (ledger.firstUsedAt > 0L) {
                    ledger.firstUsedAt
                } else {
                    ledger.firstDay.ifEmpty { UsageLedger.dateText(todayCal.timeInMillis) }
                        .let { date -> dayCalendar(date)?.timeInMillis ?: now }
                }
                calendar.timeInMillis = first
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                if (calendar.after(todayCal)) calendar.timeInMillis = todayCal.timeInMillis
            }
        }
        val span = daysBetween(calendar, todayCal)
        if (span <= 0) return emptyList()
        val bucketDays = ((span + SERIES_MAX_POINTS - 1) / SERIES_MAX_POINTS).coerceAtLeast(1)
        val buckets = (span + bucketDays - 1) / bucketDays
        val tokens = LongArray(buckets)
        val requests = LongArray(buckets)
        // 每档的轮次数：既用于摊历史遗留（权重），也让「均匀摊」有个真实依据
        val rounds = LongArray(buckets)
        // 点选后要显示缓存与费用，按档累加；历史遗留摊入的档没有明细，保持 0
        val cacheHit = LongArray(buckets)
        val cacheMiss = LongArray(buckets)
        val output = LongArray(buckets)
        val cost = DoubleArray(buckets)
        val labels = arrayOfNulls<String>(buckets)
        val cursor = calendar.clone() as Calendar
        for (i in 0 until span) {
            val key = UsageLedger.dateText(cursor.timeInMillis)
            val index = i / bucketDays
            if (labels[index] == null) labels[index] = key
            ledger.days[key]?.let { day ->
                tokens[index] += day.prompt.toLong() + day.completion
                requests[index] += day.requests.toLong()
                rounds[index] += day.rounds.toLong()
                cacheHit[index] += day.cacheHit
                cacheMiss[index] += day.cacheMiss
                output[index] += day.completion
                cost[index] += day.costCny
            }
            cursor.add(Calendar.DAY_OF_YEAR, 1)
        }
        /*
         * 历史遗留摊进「第一条明细日之前」的档。
         *
         * 为什么要摊：这段用量（v1 时代与已删会话）确实发生过，但没有逐日记录，
         * 不摊就会出现「总量 6.1M、折线只有 23K」的矛盾；全压在某一天又会顶出一根假尖峰。
         * 按天轮次加权：轮次是回填时按消息时间落进按天桶的，是这里唯一可用的分布证据，
         * 所以尖峰正好落在真实忙的那几天。权重全 0 时退化成均匀分布。
         *
         * 是否该摊与上面的区间总量共用 [legacyCoversRange]：窗口整段落在有明细的区间内时
         * （如「近 7 天」）遗留不属于这个范围，摊进来会让各档之和比区间总量多出一整块历史。
         */
        val firstDetailed = ledger.firstUsageDay
        val windowStart = UsageLedger.dateText(calendar.timeInMillis)
        val legacy = ledger.legacy.takeIf {
            legacyCoversRange(range == InsightRange.ALL, firstDetailed, windowStart)
        } ?: LedgerUsage()
        val legacyTokens = legacy.prompt.toLong() + legacy.completion
        if (legacyTokens > 0 || legacy.requests > 0) {
            val targets = (0 until buckets).filter { index ->
                val label = labels[index] ?: return@filter false
                firstDetailed.isEmpty() || label < firstDetailed
            }.ifEmpty { listOf(0) }
            val weights = targets.map { index -> rounds[index] }
            val weightSum = weights.sum()
            val share = LongArray(targets.size)
            for (position in targets.indices) {
                share[position] = if (weightSum > 0L) {
                    legacyTokens * weights[position] / weightSum
                } else {
                    legacyTokens / targets.size
                }
            }
            val deficit = legacyTokens - share.sum()
            if (deficit != 0L) share[share.lastIndex] += deficit
            targets.forEachIndexed { position, index ->
                tokens[index] += share[position]
            }
            // 请求数按同一套权重摊；余数补最后一档，保证 Σ 与总账一致
            val requestShare = LongArray(targets.size)
            for (position in targets.indices) {
                requestShare[position] = if (weightSum > 0L) {
                    legacy.requests.toLong() * weights[position] / weightSum
                } else {
                    legacy.requests.toLong() / targets.size
                }
            }
            val requestDeficit = legacy.requests - requestShare.sum()
            if (requestDeficit != 0L) requestShare[requestShare.lastIndex] += requestDeficit
            targets.forEachIndexed { position, index ->
                requests[index] += requestShare[position]
            }
        }
        val points = ArrayList<InsightDay>(buckets)
        for (index in 0 until buckets) {
            points += InsightDay(
                date = labels[index] ?: "",
                tokens = tokens[index],
                requests = requests[index].toInt(),
                label = shortDate(labels[index] ?: ""),
                cacheHit = cacheHit[index].toInt(),
                cacheMiss = cacheMiss[index].toInt(),
                output = output[index].toInt(),
                costCny = cost[index]
            )
        }
        return points
    }

    /** 两个「当天零点」之间的天数差；同一秒返回 0。 */
    private fun daysBetween(from: Calendar, to: Calendar): Int {
        val millis = to.timeInMillis - from.timeInMillis
        return (millis / 86_400_000L).toInt() + 1
    }

    /**
     * 今天按整点的用量：固定 24 个点（0 时至 23 时），横轴全天不随整点移动。
     *
     * 未来时段保持 0，由 [InsightGlobalView.seriesEnd] 控制折线只画到当前小时，
     * 所以图上不会出现「现在之后被画成用量归零」的段落。
     */
    private fun todayHourSeries(ledger: LedgerSnapshot, now: Long): List<InsightDay> {
        val date = UsageLedger.dateText(now)
        return hourlyBucketsOf(ledger.hours, date).mapIndexed { hour, usage ->
            InsightDay(
                date = date,
                tokens = usage.prompt.toLong() + usage.completion,
                requests = usage.requests,
                label = hour.toString() + "时",
                cacheHit = usage.cacheHit,
                cacheMiss = usage.cacheMiss,
                output = usage.completion,
                costCny = usage.costCny
            )
        }
    }

    /** 当前整点（0..23）。 */
    private fun hourOfDay(millis: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = millis
        return calendar.get(Calendar.HOUR_OF_DAY)
    }

    private fun dayCalendar(date: String): Calendar? {
        if (date.isEmpty()) return null
        return try {
            Calendar.getInstance().apply {
                time = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)!!
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 「10-31」；解析失败原样返回。 */
    private fun shortDate(date: String): String = if (date.length >= 10) date.takeLast(5) else date

    private fun globalRow(session: ChatSession, fromMs: Long, toMs: Long): InsightSessionRow {
        val own = session.messages.drop(session.inheritedUsage.messages)
        var tokens = 0L
        var rounds = 0
        var hit = 0
        var miss = 0
        for (message in own) {
            if (message.createdAt < fromMs || message.createdAt > toMs) continue
            when (message.role) {
                // 轮次与正常任务同口径：真人输入与定时任务唤醒都算，其他 App 代发不算
                "user" -> if (message.countsAsRound) rounds++
                "assistant" -> {
                    tokens += message.promptTokens.toLong() + message.completionTokens
                    if (message.usageCacheReported) {
                        hit += message.cachedTokens
                        miss += message.uncachedInputTokens
                    }
                }
            }
        }
        val cacheRate = if (hit + miss > 0) hit.toFloat() / (hit + miss) else null
        val cost = UsageCost.session(session, fromMs, toMs).takeIf { it.hasValue }?.cny
        return InsightSessionRow(
            id = session.id,
            title = session.title.ifBlank { "新会话" },
            archived = session.archived,
            workspaceId = session.workspaceId,
            updatedAt = session.updatedAt,
            rounds = rounds,
            tokens = tokens,
            cacheRate = cacheRate,
            costCny = cost
        )
    }

    private fun rangeStart(range: InsightRange): Long {
        if (range == InsightRange.ALL) return 0L
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        when (range) {
            InsightRange.WEEK -> cal.add(Calendar.DAY_OF_YEAR, -6)
            InsightRange.MONTH -> cal.add(Calendar.DAY_OF_YEAR, -29)
            else -> Unit
        }
        return cal.timeInMillis
    }

    /** 非当前会话的请求视图：摘要抬头 + 起点之后的原文（与 AgentLoop 同一口径）。 */
    private fun requestFor(
        all: List<ChatMessage>,
        summary: String,
        upTo: Int
    ): List<ChatMessage> {
        val request = ArrayList<ChatMessage>(all.size - upTo + 1)
        if (summary.isNotEmpty() && upTo > 0) {
            request.add(ChatMessage(role = "system", content = contextSummarySystemText(summary)))
        }
        for (i in upTo until all.size) request.add(all[i])
        return request
    }

    /** 任意请求视图的六段估算（与 [refreshContextBreakdown] 同一套口径）。 */
    private fun breakdownFor(request: List<ChatMessage>, summaryText: String): Breakdown {
        val promptOnly = PromptBuilder.build(promptInput().copy(summaryText = ""))
        val skillTokens = PromptBuilder.skillSectionTokens(promptInput())
        val systemTokens = ((promptOnly?.let { ContextCompactor.estimateText(it) } ?: 0) - skillTokens)
            .coerceAtLeast(0)
        val summaryTokens = if (summaryText.isBlank()) {
            0
        } else {
            ContextCompactor.estimateText(contextSummarySystemText(summaryText))
        }
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
                "user" -> userTokens += ContextCompactor.estimate(message)
                "assistant" -> assistantTokens += ContextCompactor.estimate(message)
                "tool" -> toolResultTokens += ContextCompactor.estimate(message)
                else -> Unit
            }
        }
        return Breakdown(
            systemTokens = systemTokens,
            skillTokens = skillTokens,
            summaryTokens = summaryTokens,
            toolTokens = toolTokens,
            userTokens = userTokens,
            assistantTokens = assistantTokens,
            toolResultTokens = toolResultTokens
        )
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
    /** 系统提示词正文（**不含**会话摘要与 Skill 段）。 */
    val systemTokens: Int = 0,
    /** 技能目录（`<available_skills>` 那一段）单独估算。 */
    val skillTokens: Int = 0,
    /** 会话摘要：它是拼进 system 的一段，单列出来，否则「提示词」会显得莫名其妙地大。 */
    val summaryTokens: Int = 0,
    val toolTokens: Int = 0,
    val userTokens: Int = 0,
    val assistantTokens: Int = 0,
    val toolResultTokens: Int = 0
) {
    /** 三段的合计（旧界面口径「消息占多少」，也是水位里除提示词与工具表之外的部分）。 */
    val messageTokens: Int get() = userTokens + assistantTokens + toolResultTokens

    /** 整份请求的估算：提示词 + 技能 + 摘要 + 工具表 + 三类消息（= 成分条七段之和）。 */
    val totalTokens: Int
        get() = systemTokens + skillTokens + summaryTokens + toolTokens + messageTokens
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
