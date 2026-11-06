package com.zmf.agent.core.store

/** 洞察页的段色（ARGB）：对齐 DSH「上下文洞察」插件的取色（Tailwind 500）。 */
object InsightColors {
    const val SYSTEM = 0xFF6366F1
    const val TOOLS = 0xFFF59E0B
    const val USER = 0xFF22C55E
    const val INJECT = 0xFFA855F7
    const val SKILLS = 0xFFF97316
    const val ASSISTANT = 0xFF3B82F6
    const val TOOL_RESULTS = 0xFF14B8A6
    const val OUTPUT = 0xFFEC4899

    /** 其他开销：不属于上述七段的时间归集。 */
    const val OTHER = 0xFF9A9AA5
}

/** 洞察页的范围筛选。 */
enum class InsightRange { TODAY, WEEK, MONTH, ALL }

/** 一段上下文构成：名称、估算 tokens、颜色（ARGB）。 */
data class InsightPart(
    val label: String,
    val tokens: Int,
    val colorArgb: Long
)

/**
 * 本会话模块的三张卡所需的全部数据（当前会话或用户选中的会话）。
 *
 * 所有「没有数据」都用 null 表示：界面显示短横线，不写 0、不加说明。
 */
data class InsightSessionView(
    val sessionId: String = "",
    val title: String = "当前上下文",
    val isCurrent: Boolean = true,
    val window: Int = 0,
    val estimatedTokens: Int = 0,
    val ratio: Float = 0f,
    /** 当前上下文的七段（system / skills / tools / user / inject / assistant / toolResults）。 */
    val parts: List<InsightPart> = emptyList(),
    /** 本会话计费构成（八段）；null = 历史会话没有明细。 */
    val tokenComposition: SessionComposition? = null,
    /** 缓存命中率；null = 服务商没报缓存字段。 */
    val cacheRate: Float? = null,
    val requests: Int = 0,
    val activeMillis: Long = 0L,
    val ttftMillis: Long = 0L,
    val reasoningMillis: Long = 0L,
    val textMillis: Long = 0L,
    val toolArgMillis: Long = 0L,
    val toolMillis: Long = 0L,
    /** 输出速度 tok/s；null = 没有上一轮数据。 */
    val outputSpeed: Int? = null,
    val toolCalls: Int = 0
)

/** 用量折线图上的一个点；分桶聚合时一天代表若干天。 */
data class InsightDay(
    val date: String,
    val tokens: Long,
    val requests: Int,
    /** 轴标签（如「10-31」）；分桶后取该档首日。 */
    val label: String = ""
)

/** 耗时六段（毫秒）。 */
data class InsightTiming(
    val ttft: Long = 0L,
    val reasoning: Long = 0L,
    val text: Long = 0L,
    val toolArg: Long = 0L,
    val toolExec: Long = 0L,
    val other: Long = 0L,
    val active: Long = 0L
) {
    val total: Long get() = ttft + reasoning + text + toolArg + toolExec + other
}

/** 全局会话列表的一行。 */
data class InsightSessionRow(
    val id: String,
    val title: String,
    val archived: Boolean = false,
    /** 所属工作区；null = 未分类。 */
    val workspaceId: String? = null,
    val updatedAt: Long,
    val rounds: Int,
    val tokens: Long,
    val cacheRate: Float?,
    val costCny: Double?
)

/**
 * 全局统计区所需的全部数据。
 *
 * [composition] 为 null 表示所选范围内还没有 v2 构成明细（界面显示短横线）。
 */
data class InsightGlobalView(
    val range: InsightRange = InsightRange.ALL,
    val sessions: Int = 0,
    val archived: Int = 0,
    /** 所选范围内产生过计费 token 的会话数：缓存命中率的分母口径，与 dsh 的「含 N 个会话」一致。 */
    val usageSessions: Int = 0,
    val totalTokens: Long = 0L,
    val rounds: Int = 0,
    val costCny: Double? = null,
    val pricedSessions: Int = 0,
    val cacheRate: Float? = null,
    val toolCalls: Int = 0,
    val toolMillis: Long = 0L,
    val activeMillis: Long = 0L,
    val requests: Int = 0,
    /** 用量折线序列：今天/近 7 天给 7 点，近 30 天给 30 点，全部按跨度分桶。 */
    val series: List<InsightDay> = emptyList(),
    val composition: LedgerComposition? = null,
    val timing: InsightTiming = InsightTiming(),
    /** 全局输出速度 tok/s；null = 没有生成数据。 */
    val outputSpeed: Int? = null,
    val rows: List<InsightSessionRow> = emptyList()
)
