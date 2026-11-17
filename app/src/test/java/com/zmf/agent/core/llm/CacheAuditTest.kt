package com.zmf.agent.core.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 缓存归因的纯逻辑测试：只验证判定顺序与未命中量口径（对齐 DSH `core/cache-stats.js`）。
 */
class CacheAuditTest {

    private fun input(
        prevPromptTokens: Int? = 10_000,
        prevModel: String? = "deepseek/deepseek-flash",
        prevReportedCache: Boolean = true,
        promptTokens: Int = 10_000,
        cacheReadTokens: Int = 0,
        cacheWriteTokens: Int = 0,
        idleMillis: Long? = 1_000L,
        model: String = "deepseek/deepseek-flash"
    ) = CacheAudit.Input(
        prevPromptTokens = prevPromptTokens,
        prevModel = prevModel,
        prevReportedCache = prevReportedCache,
        promptTokens = promptTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        idleMillis = idleMillis,
        model = model
    )

    @Test
    fun `no previous reference is first turn with zero miss`() {
        val result = CacheAudit.audit(input(prevPromptTokens = null, cacheReadTokens = 0))
        assertEquals(CacheAudit.Reason.FIRST_TURN, result.reason)
        assertEquals(0, result.missTokens)
    }

    @Test
    fun `miss within noise floor is noise`() {
        val result = CacheAudit.audit(input(cacheReadTokens = 9_500))
        assertEquals(CacheAudit.Reason.NOISE, result.reason)
        assertEquals(500, result.missTokens)
    }

    @Test
    fun `long idle above ttl is idle ttl`() {
        val result = CacheAudit.audit(
            input(cacheReadTokens = 1_000, idleMillis = CacheAudit.CACHE_TTL_MS)
        )
        assertEquals(CacheAudit.Reason.IDLE_TTL, result.reason)
        assertEquals(9_000, result.missTokens)
    }

    @Test
    fun `model change wins over idle ttl`() {
        val result = CacheAudit.audit(
            input(
                cacheReadTokens = 1_000,
                idleMillis = CacheAudit.CACHE_TTL_MS * 2,
                model = "deepseek/deepseek-v4-pro"
            )
        )
        assertEquals(CacheAudit.Reason.MODEL_CHANGED, result.reason)
        assertEquals(9_000, result.missTokens)
    }

    @Test
    fun `same model short idle is unknown`() {
        val result = CacheAudit.audit(input(cacheReadTokens = 1_000, idleMillis = 1_000L))
        assertEquals(CacheAudit.Reason.UNKNOWN, result.reason)
        assertEquals(9_000, result.missTokens)
    }

    @Test
    fun `null previous model counts as unchanged`() {
        val result = CacheAudit.audit(
            input(prevModel = null, cacheReadTokens = 1_000, idleMillis = 1_000L)
        )
        assertEquals(CacheAudit.Reason.UNKNOWN, result.reason)
        assertEquals(9_000, result.missTokens)
    }

    @Test
    fun `never reported cache without cache activity is unknown with zero miss`() {
        val result = CacheAudit.audit(
            input(prevReportedCache = false, cacheReadTokens = 0, cacheWriteTokens = 0)
        )
        assertEquals(CacheAudit.Reason.UNKNOWN, result.reason)
        assertEquals(0, result.missTokens)
    }

    @Test
    fun `cache read above reference clamps miss to zero`() {
        val result = CacheAudit.audit(input(prevPromptTokens = 8_000, cacheReadTokens = 9_000))
        assertEquals(0, result.missTokens)
        assertEquals(CacheAudit.Reason.NOISE, result.reason)
    }

    @Test
    fun `constants match dsh`() {
        assertEquals(1024, CacheAudit.NOISE_FLOOR_TOKENS)
        assertEquals(300_000L, CacheAudit.CACHE_TTL_MS)
    }
}
