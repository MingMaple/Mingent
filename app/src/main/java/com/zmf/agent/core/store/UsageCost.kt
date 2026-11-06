package com.zmf.agent.core.store

import com.zmf.agent.core.llm.CostBook
import com.zmf.agent.core.model.ChatMessage

/**
 * 一段范围的费用汇总。
 *
 * [allTurns] 是本范围内所有带 usage 的回复数；[pricedTurns] 是其中能算出钱的那些
 * （只有已注册价目的供应商才算）。两个数分开，界面才能说「已计价 N/M 个会话」。
 */
data class CostSummary(
    val cny: Double = 0.0,
    val pricedTurns: Int = 0,
    val allTurns: Int = 0
) {
    val hasValue: Boolean get() = pricedTurns > 0

    operator fun plus(other: CostSummary): CostSummary = CostSummary(
        cny = cny + other.cny,
        pricedTurns = pricedTurns + other.pricedTurns,
        allTurns = allTurns + other.allTurns
    )
}

/**
 * 费用计算：直接读消息里每轮落下的 usage，不依赖总账。
 *
 * 采用此算法的原因：会话记录已保存每轮的真实 tokens 与当次使用的模型，
 * 所以历史会话也能算真实费用；而总账只从 v2 起才有明细，不能用它回填历史。
 */
object UsageCost {

    /**
     * 一条 assistant 消息对应的一轮费用；不可计价时返回 null。
     *
     * 只有本轮最后一条 assistant 带 usage（见 [ChatMessage.promptTokens] 的口径）。
     */
    fun turnCost(message: ChatMessage): Double? {
        if (message.role != "assistant") return null
        if (message.usageModel.isBlank()) return null
        if (message.promptTokens <= 0 && message.completionTokens <= 0) return null
        val cached = message.cachedTokens.coerceAtLeast(0)
        val uncached = if (message.usageCacheReported) {
            message.uncachedInputTokens.coerceAtLeast(0)
        } else {
            (message.promptTokens - cached).coerceAtLeast(0)
        }
        return CostBook.costOf(
            usageModel = message.usageModel,
            promptTokens = message.promptTokens,
            completionTokens = message.completionTokens,
            cachedTokens = cached,
            uncachedTokens = uncached,
            cacheWriteTokens = message.cacheWriteTokens ?: 0,
            atMillis = message.createdAt
        )
    }

    /**
     * 单条会话在时间范围内的费用（只算自己的历史：分叉克隆段不计）。
     *
     * 与全局同一口径：dsh 的两级费用都由 token 折算——会话是「该会话累计 tokens 折价」，
     * 全局是「各会话 tokens 合计折价」，因此失败的请求（花掉了 token、没有留下消息）
     * 在两级都计入。这里把「消息之外」的那部分补上；只有「全部」范围补，
     * 更窄的范围按消息时间筛选，而这部分 tokens 没有可归属的时刻。
     */
    fun session(session: ChatSession, fromMs: Long, toMs: Long): CostSummary {
        val own = session.messages.drop(session.inheritedUsage.messages)
        val base = session(own, fromMs, toMs)
        if (fromMs > 0L) return base
        val extra = unmessaged(session, own) ?: return base
        return CostSummary(
            cny = base.cny + extra.cny,
            pricedTurns = base.pricedTurns + extra.pricedTurns,
            allTurns = base.allTurns + extra.allTurns
        )
    }

    /**
     * 消息之外那部分 token 的费用。
     *
     * 请求失败时会话的累计字段照样加上它的 tokens（与全局账本一致），却没有 assistant 消息承载；
     * 用「会话累计 − 消息合计」取出这部分，按该会话最后一次使用的模型折价。
     * 一条可计价消息都没有时返回 null，不做推断：更早版本的消息本身不带 usage，
     * 那时「差额」来自口径变化，折价只会得到一个凭空的金额。
     * 分叉会话同样跳过：它的累计值含着父会话的部分，而缓存计数没有随克隆记录下来，
     * 差额无法可靠切分。
     */
    private fun unmessaged(session: ChatSession, own: List<ChatMessage>): CostSummary? {
        val inherited = session.inheritedUsage
        if (inherited.messages > 0) return null
        var msgPrompt = 0
        var msgCompletion = 0
        var msgCached = 0
        var model = ""
        var hasPriced = false
        for (message in own) {
            if (message.role != "assistant") continue
            if (message.usageModel.isBlank()) continue
            if (message.promptTokens <= 0 && message.completionTokens <= 0) continue
            hasPriced = true
            model = message.usageModel
            msgPrompt += message.promptTokens
            msgCompletion += message.completionTokens
            msgCached += message.cachedTokens.coerceAtLeast(0)
        }
        if (!hasPriced || model.isBlank()) return null
        val prompt = (session.promptTokens - inherited.promptTokens - msgPrompt).coerceAtLeast(0)
        val completion = (session.completionTokens - inherited.completionTokens - msgCompletion)
            .coerceAtLeast(0)
        if (prompt == 0 && completion == 0) return null
        // 未命中 = 差额里没被缓存覆盖的部分；缓存命中数不超过差额本身
        val cached = (session.cacheHitTokens - msgCached).coerceIn(0, prompt)
        val cny = CostBook.costOf(
            usageModel = model,
            promptTokens = prompt,
            completionTokens = completion,
            cachedTokens = cached,
            uncachedTokens = prompt - cached,
            cacheWriteTokens = 0,
            atMillis = session.updatedAt
        ) ?: return null
        return CostSummary(cny = cny, pricedTurns = 1, allTurns = 1)
    }

    /**
     * 一组消息在时间范围内的费用。
     *
     * @param fromMs 起（含），0 = 不限
     * @param toMs 止（含），Long.MAX_VALUE = 不限
     */
    fun session(
        messages: List<ChatMessage>,
        fromMs: Long,
        toMs: Long
    ): CostSummary {
        var cny = 0.0
        var priced = 0
        var all = 0
        for (message in messages) {
            if (message.role != "assistant" || message.usageModel.isBlank()) continue
            if (message.promptTokens <= 0 && message.completionTokens <= 0) continue
            if (message.createdAt < fromMs || message.createdAt > toMs) continue
            all++
            val cost = turnCost(message) ?: continue
            priced++
            cny += cost
        }
        return CostSummary(cny = cny, pricedTurns = priced, allTurns = all)
    }

    /** 有至少一轮可计价的会话数（全局 KPI 的「已计价 N/M 个会话」用）。 */
    fun pricedSessionCount(sessions: List<ChatSession>, fromMs: Long, toMs: Long): Int =
        sessions.count { session(it, fromMs, toMs).pricedTurns > 0 }
}
