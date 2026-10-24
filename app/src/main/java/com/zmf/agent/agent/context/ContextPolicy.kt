package com.zmf.agent.agent.context

import android.content.Context
import com.zmf.agent.agent.ApiConfig
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import kotlin.math.round

/**
 * 上下文压缩策略（摘要式滚动压缩）。
 *
 * 语义：命中闸门时，把「最早的若干轮」交给模型生成摘要，并与上一条摘要融合成新摘要（滚动），
 * 旧消息**不删**（界面照旧全在），只是不再进请求；最近 [fallbackKeepRounds] 轮永远发原文。
 * 只影响「发给模型的请求视图」，界面渲染 / 回滚 / 重新生成用的仍是全量消息。
 */
data class ContextPolicy(
    /** 总开关：关掉就完全不摘要、不裁轮，全量历史照发。 */
    val enabled: Boolean = true,
    /**
     * 闸门①：估算 tokens / 上下文窗口达到这个比例就摘要（0.1..0.95）。
     *
     * 默认 0.4：DeepSeek 是 1M 窗口，0.4 约等于 419k tokens 才摘要 ——
     * 太早摘要会白花一次模型请求，太晚又接近上限，40% 是留足余量的水位线。
     */
    val triggerRatio: Float = DEFAULT_TRIGGER_RATIO,
    /** 闸门②开关：关掉就只按比例触发（长对话里对「条数」不敏感时用）。 */
    val triggerByCount: Boolean = true,
    /** 闸门②：自上次摘要后新增的 user 消息数达到这个数就摘要（1..20）。 */
    val triggerCount: Int = DEFAULT_TRIGGER_COUNT,
    /** 保留轮数的下限（2..20）：预算再小，也至少留这么多轮原文；摘要失败时的兜底窗口同它。 */
    val fallbackKeepRounds: Int = DEFAULT_KEEP_ROUNDS,
    /**
     * 逐字保留近期历史的预算比例（对齐 DSH 的 retainRatio，作用于 W − 输出预留）。
     *
     * 默认 0.16 就是 DSH 的默认值：它才是「留多少原文」的主口径，轮数只是下限。
     * 取值 0.05..0.4，越界在 [ContextStore.normalize] 里夹住。
     */
    val retainRatio: Float = DEFAULT_RETAIN_RATIO,
    /**
     * 逐字保留的绝对 token 预算（对齐 DSH 的 retainTokens）；> 0 时优先于 [retainRatio]。
     *
     * 为什么两种都留：比例跟着窗口走，换小模型不用改设置；绝对值和阈值一样直观，
     * 用户想「就留 20k」时不用拿计算器算比例。
     */
    val retainTokens: Int = 0,
    /** 摘要用哪个模型；空串 = 跟随当前对话模型。 */
    val summaryModel: String = ""
) {

    /**
     * 设置页 / 侧边栏显示的一行说明，例如「0.4 窗口 · 16 条 · 留 8 轮 / 16%」。
     *
     * 设置页能改的每一项（触发比例 / 条数 / 保留轮数 / 保留预算）都要在这行里看得见，
     * 用户改完立刻能确认自己改的是哪一档。
     */
    val summary: String
        get() {
            if (!enabled) return "关闭"
            // 只留两位小数：0.4f 直接 toString 可能变成 0.40000001
            val ratioText = (round(triggerRatio * 100f).toInt() / 100f).toString()
            val countText = if (triggerByCount) " · " + triggerCount + " 条" else ""
            // 绝对预算优先显示 token，否则显示百分比：和逐字保留的实际口径一致
            val retainText = if (retainTokens > 0) {
                retainTokens.toString() + " tok"
            } else {
                round(retainRatio * 100f).toInt().toString() + "%"
            }
            return ratioText + " 窗口" + countText + " · 留 " + fallbackKeepRounds + " 轮 / " + retainText
        }

    companion object {
        /** 40% 窗口（1M 窗口 ≈ 419k tokens）才触发摘要，参数可在设置页改。 */
        const val DEFAULT_TRIGGER_RATIO = 0.4f
        const val DEFAULT_TRIGGER_COUNT = 16
        const val DEFAULT_KEEP_ROUNDS = 8

        /** 逐字保留的默认比例：对齐 DSH 的 retainRatio = 0.16。 */
        const val DEFAULT_RETAIN_RATIO = 0.16f

        /** 合法范围：读盘、落盘、设置页三处共用，越界一律夹住。 */
        const val MIN_RATIO = 0.1f
        const val MAX_RATIO = 0.95f
        const val MIN_RETAIN_RATIO = 0.05f
        const val MAX_RETAIN_RATIO = 0.4f
        const val MIN_TRIGGER_COUNT = 1
        const val MAX_TRIGGER_COUNT = 20
        const val MIN_KEEP_ROUNDS = 2
        const val MAX_KEEP_ROUNDS = 20
    }
}

/**
 * 上下文快照：界面上「现在塞给模型多少」的唯一口径。
 *
 * 全是粗估（复用 [ContextCompactor.estimate]），不是服务端真实 usage；只用来显示水位与判断闸门。
 */
data class ContextSnapshot(
    val estimatedTokens: Int = 0,
    val window: Int = 0,
    /** estimatedTokens / window，0f = 空对话。 */
    val ratio: Float = 0f,
    /** 不再按原文发送的消息条数（已摘要覆盖的 + 兜底裁掉的）。 */
    val coveredMessages: Int = 0,
    val totalMessages: Int = 0,
    val hasSummary: Boolean = false,
    val lastSummaryAt: Long = 0L
)

/**
 * 策略落盘（和权限档、思考档同一个 prefs 文件）。
 *
 * 旧的 mode / keep_rounds / token_budget 三个键语义已废，这里既不读也不写；
 * enabled 复用同名键（含义从「裁轮」变成「摘要」，默认值仍为 true），其余换成新键名，
 * 老用户升级后直接吃到新默认值，不会被旧值带偏。
 */
object ContextStore {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_ENABLED = "context_compact_enabled"
    // 下面三个键就是设置页「可改的三项」：触发比例 / 条数阈值 / 保留轮数，读写都覆盖
    private const val KEY_RATIO = "context_trigger_ratio"
    private const val KEY_COUNT = "context_trigger_count"
    private const val KEY_KEEP_ROUNDS = "context_fallback_keep_rounds"
    private const val KEY_BY_COUNT = "context_trigger_by_count"
    private const val KEY_SUMMARY_MODEL = "context_summary_model"

    // 逐字保留预算：比例与绝对 token 各一个键。旧版本没有它们，读到默认值（16% / 0），向后兼容。
    private const val KEY_RETAIN_RATIO = "context_retain_ratio"
    private const val KEY_RETAIN_TOKENS = "context_retain_tokens"

    fun load(context: Context): ContextPolicy {
        val p = prefs(context)
        val policy = normalize(
            ContextPolicy(
                enabled = p.getBoolean(KEY_ENABLED, true),
                triggerRatio = p.getFloat(KEY_RATIO, ContextPolicy.DEFAULT_TRIGGER_RATIO),
                triggerByCount = p.getBoolean(KEY_BY_COUNT, true),
                triggerCount = p.getInt(KEY_COUNT, ContextPolicy.DEFAULT_TRIGGER_COUNT),
                fallbackKeepRounds = p.getInt(KEY_KEEP_ROUNDS, ContextPolicy.DEFAULT_KEEP_ROUNDS),
                // 旧版本的 prefs 没有这两个键：getFloat/getInt 的默认值直接兜住
                retainRatio = p.getFloat(KEY_RETAIN_RATIO, ContextPolicy.DEFAULT_RETAIN_RATIO),
                retainTokens = p.getInt(KEY_RETAIN_TOKENS, 0),
                summaryModel = p.getString(KEY_SUMMARY_MODEL, null).orEmpty()
            )
        )
        warnIfRetainClamped(policy)
        return policy
    }

    fun save(context: Context, policy: ContextPolicy) {
        val p = normalize(policy)
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, p.enabled)
            .putFloat(KEY_RATIO, p.triggerRatio)
            .putBoolean(KEY_BY_COUNT, p.triggerByCount)
            .putInt(KEY_COUNT, p.triggerCount)
            .putInt(KEY_KEEP_ROUNDS, p.fallbackKeepRounds)
            .putFloat(KEY_RETAIN_RATIO, p.retainRatio)
            .putInt(KEY_RETAIN_TOKENS, p.retainTokens)
            .putString(KEY_SUMMARY_MODEL, p.summaryModel)
            .apply()
        warnIfRetainClamped(p)
    }

    /** 把越界值夹回合法范围：手滑传 0 或 200 也别把闸门弄成「永不触发」/「每句都触发」。 */
    fun normalize(policy: ContextPolicy): ContextPolicy = policy.copy(
        triggerRatio = policy.triggerRatio.coerceIn(ContextPolicy.MIN_RATIO, ContextPolicy.MAX_RATIO),
        triggerCount = policy.triggerCount.coerceIn(
            ContextPolicy.MIN_TRIGGER_COUNT,
            ContextPolicy.MAX_TRIGGER_COUNT
        ),
        fallbackKeepRounds = policy.fallbackKeepRounds.coerceIn(
            ContextPolicy.MIN_KEEP_ROUNDS,
            ContextPolicy.MAX_KEEP_ROUNDS
        ),
        retainRatio = policy.retainRatio.coerceIn(
            ContextPolicy.MIN_RETAIN_RATIO,
            ContextPolicy.MAX_RETAIN_RATIO
        ),
        // 负数 token 预算没有意义：当 0（= 用比例）
        retainTokens = policy.retainTokens.coerceAtLeast(0)
    )

    /** 未校正的保留预算（token）：绝对值为优先，否则按 (W − 输出预留) × 比例。 */
    private fun rawRetainBudget(policy: ContextPolicy, windowTokens: Int, outputReserveTokens: Int): Int {
        val usable = (windowTokens - outputReserveTokens.coerceAtLeast(0)).coerceAtLeast(0)
        return if (policy.retainTokens > 0) policy.retainTokens else (usable * policy.retainRatio).toInt()
    }

    /**
     * 逐字保留预算（token），已按触发阈值校正。
     *
     * DSH 的规则是「保留预算必须小于触发阈值」（否则永远压不动），并且在加载期直接拒绝。
     * 我们选择回落 + 告警，别让 App 起不来：越界时按阈值的 80% 算。
     *
     * @param outputReserveTokens 请求输出预留（DSH 的 O）。我们不给对话请求设 max_tokens，所以默认 0。
     */
    fun retainBudgetTokens(
        policy: ContextPolicy,
        windowTokens: Int = ApiConfig.CONTEXT_WINDOW,
        outputReserveTokens: Int = 0
    ): Int {
        val window = windowTokens.coerceAtLeast(0)
        if (window <= 0) return 0
        val raw = rawRetainBudget(policy, window, outputReserveTokens).coerceAtLeast(0)
        val threshold = (window * policy.triggerRatio).toInt()
        if (threshold <= 0) return 0
        return if (raw >= threshold) (threshold * 0.8f).toInt().coerceAtLeast(1) else raw
    }

    /** 现在的保留预算是否因为「不小于触发阈值」被回落了（日志与设置页提示用）。 */
    fun retainClamped(
        policy: ContextPolicy,
        windowTokens: Int = ApiConfig.CONTEXT_WINDOW
    ): Boolean {
        val window = windowTokens.coerceAtLeast(1)
        val threshold = (window * policy.triggerRatio).toInt()
        if (threshold <= 0) return false
        return rawRetainBudget(policy, window, 0) >= threshold
    }

    /**
     * 保留预算越界时记一条告警。
     *
     * 只在读盘 / 存盘时判一次：这两个时点各自代表「App 启动」与「用户改了设置」，
     * 放到每次请求里判就是刷屏。
     */
    private fun warnIfRetainClamped(policy: ContextPolicy) {
        if (!retainClamped(policy)) return
        val window = ApiConfig.CONTEXT_WINDOW
        val threshold = (window * policy.triggerRatio).toInt()
        FileLog.log(
            "context",
            "保留预算 " + rawRetainBudget(policy, window, 0) + " 不小于触发阈值 " + threshold +
                "，已按阈值的 80% 回落为 " + retainBudgetTokens(policy, window)
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * 摘要注入请求时的 system 消息文本。
 *
 * 放成同一个函数：AgentLoop（真发请求）和 ChatViewModel（算 tokens）口径必须一致，
 * 否则水位估算和实际请求会对不上。抬头写清楚「不是用户新发言」，避免模型把摘要当成新指令。
 */
fun contextSummarySystemText(summary: String): String =
    "以下是本次会话更早对话的摘要（由你此前生成，不是用户的新发言，供你续接任务用）：\n" + summary

/**
 * 请求视图的「兜底裁轮」。
 *
 * 正常路径的请求视图是「摘要 + summaryUpTo 之后的原文」（见 AgentLoop）；
 * 摘要失败 / 超时 / 被关掉时才用这里的裁轮兜底：只发最近 [ContextPolicy.fallbackKeepRounds] 轮原文。
 * 删除粒度是「整轮」——一条 user 加它之后到下一个 user 之前的全部内容，
 * 所以 assistant(tool_calls) 和它的 tool 结果永远一起存亡，不会断成孤儿消息触发 400。
 */
object ContextCompactor {

    data class Outcome(
        val messages: List<ChatMessage>,
        val droppedRounds: Int,
        val estimatedTokens: Int
    )

    fun compact(history: List<ChatMessage>, policy: ContextPolicy): Outcome {
        if (!policy.enabled || history.isEmpty()) {
            return Outcome(history, 0, estimate(history))
        }
        val start = tailStartIndex(history, policy.fallbackKeepRounds)
        if (start <= 0) return Outcome(history, 0, estimate(history))

        val kept = ArrayList<ChatMessage>(history.size - start)
        for (i in start until history.size) kept.add(history[i])
        return Outcome(kept, roundCount(history, start), estimate(kept))
    }

    /**
     * 保留最近 [keepRounds] 轮原文时，请求视图该从哪个下标开始（0 = 一条都不裁）。
     *
     * 从后往前数 user 消息，第 keepRounds 个 user 就是保留区的第一轮开头，
     * 所以分界永远落在 user 边界上，绝不会把一轮切成两半（摘要分界也复用它）。
     */
    fun tailStartIndex(history: List<ChatMessage>, keepRounds: Int): Int {
        if (history.isEmpty()) return 0
        val keep = keepRounds.coerceAtLeast(1)
        var seen = 0
        for (i in history.indices.reversed()) {
            if (history[i].role == "user") {
                seen++
                if (seen >= keep) return i
            }
        }
        return 0
    }

    /**
     * 按 token 预算算保留区的起点（0 = 一条都不裁）。
     *
     * 从最后一条往前累加估算 token，超预算就停；再往回对齐到整轮开头，
     * 免得把一轮切成两半（tool 结果会变成孤儿消息，接口直接报错）。
     * 起点永远落在 user 边界上，和 [tailStartIndex] 同一套边界规则。
     */
    fun tailStartByBudget(history: List<ChatMessage>, budgetTokens: Int): Int {
        if (history.isEmpty()) return 0
        if (budgetTokens <= 0) return 0
        var used = 0
        var start = history.size
        for (i in history.indices.reversed()) {
            val cost = estimate(history[i])
            // 至少保住最后一条：预算再小也不能把「刚问的那句」裁掉
            if (start < history.size && used + cost > budgetTokens) break
            used += cost
            start = i
        }
        while (start > 0 && history[start].role != "user") start--
        return start
    }

    /**
     * 摘要分界 = 「逐字保留」的起点：token 预算与轮数下限取更靠前的那个。
     *
     * 为什么两个条件都要：预算才是成本闸门（对齐 DSH 的 retainRatio），
     * 轮数是老用户看得懂的下限（留 N 轮）；取更靠前的起点，等于「按预算留，但不低于 N 轮」。
     */
    fun retainStart(
        history: List<ChatMessage>,
        policy: ContextPolicy,
        windowTokens: Int = ApiConfig.CONTEXT_WINDOW,
        outputReserveTokens: Int = 0
    ): Int {
        if (history.isEmpty()) return 0
        val byRounds = tailStartIndex(history, policy.fallbackKeepRounds)
        val budget = ContextStore.retainBudgetTokens(policy, windowTokens, outputReserveTokens)
        val byBudget = tailStartByBudget(history, budget)
        return minOf(byRounds, byBudget)
    }

    /** 前缀 [0, start) 里有几轮：按 user 数算，开头没有 user 的脏数据按一轮算。 */
    private fun roundCount(history: List<ChatMessage>, start: Int): Int {
        var count = 0
        for (i in 0 until start) if (history[i].role == "user") count++
        return if (count == 0) 1 else count
    }

    fun estimate(messages: List<ChatMessage>): Int = messages.sumOf { estimate(it) }

    fun estimate(message: ChatMessage): Int {
        var tokens = 8 // 每条消息的固定开销（role 等）
        tokens += estimateText(message.content)
        message.reasoningContent?.let { tokens += estimateText(it) }
        for (call in message.toolCalls) {
            tokens += estimateText(call.name) + estimateText(call.arguments)
        }
        return tokens
    }

    /**
     * 粗估：中文/全角约 1 字 1 token，英文数字约 4 字 1 token。
     *
     * 公开出来是给「上下文三段估算」用的：系统提示词与工具表不是 ChatMessage，
     * 也得用同一套口径量，否则三段加起来和水位对不上。
     */
    fun estimateText(text: String): Int {
        if (text.isEmpty()) return 0
        var wide = 0
        var narrow = 0
        for (ch in text) {
            val code = ch.code
            if (code in 0x2E80..0x9FFF || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF) wide++
            else narrow++
        }
        return wide + narrow / 4
    }
}
