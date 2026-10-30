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
 * 为什么这么算：会话记录里已经存了每轮的真实 tokens 与当时用的模型，
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

    /** 单条会话的费用（全部历史）。 */
    fun session(session: ChatSession): CostSummary = session(session.messages, 0L, Long.MAX_VALUE)

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

    /** 多条会话在时间范围内的费用。 */
    fun sessions(
        sessions: List<ChatSession>,
        fromMs: Long,
        toMs: Long
    ): CostSummary {
        var result = CostSummary()
        for (session in sessions) {
            result += session(session.messages, fromMs, toMs)
        }
        return result
    }

    /** 有至少一轮可计价的会话数（全局 KPI 的「已计价 N/M 个会话」用）。 */
    fun pricedSessionCount(sessions: List<ChatSession>, fromMs: Long, toMs: Long): Int =
        sessions.count { session(it.messages, fromMs, toMs).pricedTurns > 0 }
}
