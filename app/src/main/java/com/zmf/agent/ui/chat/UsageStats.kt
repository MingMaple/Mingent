package com.zmf.agent.ui.chat

/**
 * 统计快照：ChatViewModel 每次变化都整体换一个新对象，页面直接读。
 *
 * 原来住在 StatsPage.kt 里；那个页面后来被删（数据和上下文洞察重复），
 * 但状态行、上下文洞察、状态行文案都还要读这个类型，所以搬成独立文件，
 * 免得「删页面」连带把数据模型删掉。
 *
 * 取值口径：
 * - rounds：当前会话里 role == "user" 的消息条数（换会话就重算，不跨会话累加）。
 * - steps：本次回复期间 onRound(round) 的最大值（每次提问前清零）；界面上的累计步数另算（statusSteps）。
 * - last*Tokens：最近一次服务端 usage 返回的输入 / 输出 token 数。
 * - total*Tokens / totalRequests：本会话累计（落盘在 sessions.json 的会话记录里，切会话能接上）。
 * - contextTokens：真正发给模型的那份请求视图的估算 token 数
 *   （ContextCompactor.compact(messages, contextPolicy).estimatedTokens，不含界面上的全量历史）。
 * - contextWindow：分母，取 ApiConfig.CONTEXT_WINDOW。
 * - global* / today* / firstUsedDate：全局历史总账（UsageLedger 落盘 stats.json，只增不减）。
 */
data class UsageStats(
    val rounds: Int = 0,          // 当前会话的对话轮数 = messages 里 role=="user" 的条数
    val steps: Int = 0,           // 最近一次回复的步数 = 该次 Agent 循环跑到第几轮（onRound 的最大值）
    val lastPromptTokens: Int = 0,
    val lastCompletionTokens: Int = 0,
    val totalPromptTokens: Int = 0,     // 会话累计输入
    val totalCompletionTokens: Int = 0, // 会话累计输出
    val totalRequests: Int = 0,         // 本会话成功请求次数（收到 usage 的次数）
    val contextTokens: Int = 0,         // 当前请求视图的估算 token 数
    val contextWindow: Int = 0,         // 模型窗口，取 ApiConfig.CONTEXT_WINDOW
    // ---- 全局历史总账（UsageLedger）----
    val globalPromptTokens: Int = 0,
    val globalCompletionTokens: Int = 0,
    val globalRequests: Int = 0,
    val todayPromptTokens: Int = 0,
    val todayCompletionTokens: Int = 0,
    val todayRequests: Int = 0,
    val firstUsedDate: String = ""      // "yyyy-MM-dd"，从没用过时为空串，界面显示「—」
)
