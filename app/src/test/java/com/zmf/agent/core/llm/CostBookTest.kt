package com.zmf.agent.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 计费口径（纯逻辑，不依赖 Android 框架）。
 *
 * 价目以官方现行人民币价为准：deepseek-flash 高峰 输入 2 / 缓存命中 0.04 / 输出 8 元每百万 tokens；
 * 高峰为北京时间周一至周五 09:00–12:00、14:00–18:00，其余时段半价。
 */
class CostBookTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun peakWindowFollowsOfficialRule() {
        // 2026-11-12 是周四
        assertTrue(CostBook.isPeak(at("2026-11-12T09:00")))
        assertTrue(CostBook.isPeak(at("2026-11-12T11:59")))
        assertFalse(CostBook.isPeak(at("2026-11-12T12:00")))
        assertFalse(CostBook.isPeak(at("2026-11-12T13:59")))
        assertTrue(CostBook.isPeak(at("2026-11-12T14:00")))
        assertTrue(CostBook.isPeak(at("2026-11-12T17:59")))
        assertFalse(CostBook.isPeak(at("2026-11-12T18:00")))
        // 凌晨与夜间都是空闲
        assertFalse(CostBook.isPeak(at("2026-11-12T06:57")))
        assertFalse(CostBook.isPeak(at("2026-11-12T22:00")))
    }

    @Test
    fun weekendIsAlwaysOffPeak() {
        assertFalse(CostBook.isPeak(at("2026-11-14T10:00")))
        assertFalse(CostBook.isPeak(at("2026-11-15T15:00")))
    }

    @Test
    fun newsTaskMatchesOfficialBill() {
        // 2026-11-12 06:57 的「新闻」任务：官方口径 34 次请求、704,625 tokens、¥0.39
        val tokens = CostBook.costOf(
            usageModel = "deepseek/deepseek-flash",
            promptTokens = 685_114,
            completionTokens = 19_511,
            cachedTokens = 507_136,
            uncachedTokens = 177_978,
            cacheWriteTokens = 0,
            atMillis = at("2026-11-12T06:57")
        )!!
        assertEquals(0.2662, tokens, 0.0001)
        // 26 次检索按 5 元 / 千次计
        assertEquals(0.13, CostBook.searchCostCny(26), 1e-9)
        assertEquals(0.3962, tokens + CostBook.searchCostCny(26), 0.0001)
    }

    @Test
    fun peakChargesFullPrice() {
        val offPeak = CostBook.costOf(
            usageModel = "deepseek/deepseek-flash",
            promptTokens = 1_000_000,
            completionTokens = 0,
            cachedTokens = 0,
            uncachedTokens = 1_000_000,
            cacheWriteTokens = 0,
            atMillis = at("2026-11-12T06:57")
        )!!
        val peak = CostBook.costOf(
            usageModel = "deepseek/deepseek-flash",
            promptTokens = 1_000_000,
            completionTokens = 0,
            cachedTokens = 0,
            uncachedTokens = 1_000_000,
            cacheWriteTokens = 0,
            atMillis = at("2026-11-12T10:00")
        )!!
        assertEquals(2.0, peak, 1e-9)
        assertEquals(1.0, offPeak, 1e-9)
    }

    @Test
    fun unknownModelHasNoPrice() {
        assertNull(
            CostBook.costOf(
                usageModel = "other/some-model",
                promptTokens = 100,
                completionTokens = 10,
                cachedTokens = 0,
                uncachedTokens = 100,
                cacheWriteTokens = 0,
                atMillis = at("2026-11-12T06:57")
            )
        )
    }
}
