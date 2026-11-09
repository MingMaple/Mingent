package com.zmf.agent.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「今天」折线取数（纯逻辑，不依赖 Android 框架）。
 *
 * 约定：只认 `yyyy-MM-dd-HH` 且属于当天的键，输出固定 24 个桶（0 时至 23 时）；
 * 其他日期与无法解析的键一律忽略。
 */
class HourlyBucketsTest {

    private val date = "2026-11-09"

    private fun usage(prompt: Int, completion: Int) =
        LedgerUsage(prompt = prompt, completion = completion, requests = 1)

    @Test
    fun onlyMatchingDateIsUsed() {
        val buckets = hourlyBucketsOf(
            mapOf(
                "2026-11-09-08" to usage(100, 20),
                "2026-11-08-08" to usage(999, 0)
            ),
            date
        )
        assertEquals(24, buckets.size)
        assertEquals(100, buckets[8].prompt)
        assertEquals(20, buckets[8].completion)
        assertEquals(0, buckets[0].prompt)
        assertEquals(0, buckets[7].prompt)
    }

    @Test
    fun everyHourMapsToItsOwnBucket() {
        val buckets = hourlyBucketsOf(
            mapOf(
                "2026-11-09-00" to usage(1, 0),
                "2026-11-09-12" to usage(2, 0),
                "2026-11-09-23" to usage(3, 0)
            ),
            date
        )
        assertEquals(1, buckets[0].prompt)
        assertEquals(2, buckets[12].prompt)
        assertEquals(3, buckets[23].prompt)
    }

    @Test
    fun invalidHoursAreIgnored() {
        val buckets = hourlyBucketsOf(
            mapOf(
                "2026-11-09-24" to usage(500, 0),
                "2026-11-09-ab" to usage(500, 0),
                "2026-11-09-" to usage(500, 0)
            ),
            date
        )
        assertEquals(0, buckets.sumOf { it.prompt })
    }

    @Test
    fun emptyDateReturnsEmptyBuckets() {
        val buckets = hourlyBucketsOf(mapOf("2026-11-09-10" to usage(10, 0)), "")
        assertEquals(24, buckets.size)
        assertTrue(buckets.all { it.prompt == 0 && it.completion == 0 })
    }
}
