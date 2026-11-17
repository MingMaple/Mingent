package com.zmf.agent.core.llm

/**
 * 缓存未命中归因：只观测，不参与请求组装、不落盘。
 *
 * 口径对齐 DSH `core/cache-stats.js`：
 * - 噪声底 [NOISE_FLOOR_TOKENS]：未命中不超过它视为前缀抖动，不告警；
 * - 空闲阈值 [CACHE_TTL_MS]：距上一次请求超过该时长，服务端缓存可能已过期；
 * - 未命中量 = `min(上一轮输入, 本轮输入) - 本轮缓存读取`，负数归零；
 * - 没有上一轮参照（首轮 / 重置）不计未命中。
 *
 * 判定优先级固定（同一次归因只报最可能的一个原因）：
 * `FIRST_TURN` → 无输入 / 从未上报缓存（`UNKNOWN`）→ `NOISE` → `MODEL_CHANGED` → `IDLE_TTL` → `UNKNOWN`。
 * 「换模型」是确定性失效，「空闲超时」只是概率性推测，因此前者优先。
 * 模型比较两侧均取 `substringAfterLast('/')`，[Input.prevModel] 为 null 视为未变。
 *
 * 本对象位于 core 层：纯 Kotlin，不依赖 Android / Compose / 网络。
 */
object CacheAudit {

    /** 未命中不超过该 token 数时视为噪声（与 DSH 的 NOISE_FLOOR_TOKENS 一致）。 */
    const val NOISE_FLOOR_TOKENS = 1024

    /** 距上一次请求超过该时长，服务端缓存可能已过期（与 DSH 的 CACHE_TTL_MS 一致）。 */
    const val CACHE_TTL_MS = 5 * 60 * 1000L

    /** 未命中的最可能原因。 */
    enum class Reason {
        /** 无上一轮参照（会话首轮、上下文重置）。 */
        FIRST_TURN,

        /** 未命中量在噪声底以内，可能是前缀抖动或服务端统计误差。 */
        NOISE,

        /** 模型已更换，服务端缓存天然不共享。 */
        MODEL_CHANGED,

        /** 距上一轮请求已超过缓存 TTL。 */
        IDLE_TTL,

        /** 无输入、服务商从未上报缓存字段，或以上原因均不成立。 */
        UNKNOWN
    }

    /**
     * 一次请求的归因输入。
     *
     * [prevPromptTokens] 为 null 表示没有任何上一轮参照；[prevReportedCache] 表示该服务商
     * 此前是否上报过缓存字段（从未上报时无从判断缓存行为）。
     */
    data class Input(
        val prevPromptTokens: Int?,
        val prevModel: String?,
        val prevReportedCache: Boolean,
        val promptTokens: Int,
        val cacheReadTokens: Int,
        val cacheWriteTokens: Int,
        val idleMillis: Long?,
        val model: String
    )

    /** 归因结果：[missTokens] 为本轮本应命中却未命中的输入 token 数，[reason] 为最可能原因。 */
    data class Result(val missTokens: Int, val reason: Reason)

    /** 按固定优先级给出归因；只读入参，无副作用。 */
    fun audit(input: Input): Result {
        // 首轮（或上下文重置后）没有参照，DSH 同样不计未命中
        val prevPrompt = input.prevPromptTokens
            ?: return Result(missTokens = 0, reason = Reason.FIRST_TURN)

        // 没有输入或没有可比对的上一轮输入，无法计算未命中
        if (input.promptTokens <= 0 || prevPrompt <= 0) {
            return Result(missTokens = 0, reason = Reason.UNKNOWN)
        }

        // 服务商从未上报缓存字段且本轮也没有缓存活动：不能把「未上报」当成「未命中」
        if (input.cacheReadTokens <= 0 && input.cacheWriteTokens <= 0 && !input.prevReportedCache) {
            return Result(missTokens = 0, reason = Reason.UNKNOWN)
        }

        val miss = (minOf(prevPrompt, input.promptTokens) - input.cacheReadTokens).coerceAtLeast(0)
        // 噪声底以内不视为异常，避免前缀几十个 token 的抖动引发误报
        if (miss <= NOISE_FLOOR_TOKENS) return Result(missTokens = miss, reason = Reason.NOISE)

        // 换模型是确定性失效，优先于「空闲超时」这一概率性推测
        val prevModel = input.prevModel
        if (prevModel != null && shortModel(prevModel) != shortModel(input.model)) {
            return Result(missTokens = miss, reason = Reason.MODEL_CHANGED)
        }

        val idle = input.idleMillis
        if (idle != null && idle >= CACHE_TTL_MS) {
            return Result(missTokens = miss, reason = Reason.IDLE_TTL)
        }

        return Result(missTokens = miss, reason = Reason.UNKNOWN)
    }

    /** 模型标识可能带 `provider/` 前缀，只比较最后一段。 */
    private fun shortModel(model: String): String = model.substringAfterLast('/')
}
