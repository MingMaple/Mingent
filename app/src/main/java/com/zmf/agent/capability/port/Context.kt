package com.zmf.agent.capability.port

/** 上下文分段；合计不等于估算值，各段独立统计。 */
data class ContextBreakdown(
    val system: Int = 0,
    val skills: Int = 0,
    val summary: Int = 0,
    val tools: Int = 0,
    val user: Int = 0,
    val assistant: Int = 0,
    val toolResults: Int = 0
)

/** 上下文策略快照。 */
data class ContextPolicyView(
    val enabled: Boolean = true,
    val triggerRatio: Float = 0f,
    val triggerCount: Int = 0,
    val keepRounds: Int = 0,
    val retainRatio: Float = 0f,
    val retainTokens: Int = 0
)

/** 一次上下文读数；全部为估算值。 */
data class ContextReading(
    val sessionId: String,
    val estimatedTokens: Int,
    val windowTokens: Int,
    val ratio: Float,
    val coveredMessages: Int,
    val totalMessages: Int,
    val hasSummary: Boolean,
    val lastSummaryAt: Long,
    val breakdown: ContextBreakdown,
    val policy: ContextPolicyView
)

/**
 * 上下文度量端口。
 *
 * 只读当前会话的请求视图读数，不触发压缩、不修改策略。
 */
interface ContextMetrics {
    fun read(sessionId: String): ContextReading?
}
