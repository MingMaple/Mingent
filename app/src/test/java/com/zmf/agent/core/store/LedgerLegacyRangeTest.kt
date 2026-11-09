package com.zmf.agent.core.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史遗留项的归窗判定（纯逻辑，不依赖 Android 框架）。
 *
 * 约定：窗口起点早于「第一条有明细的日期」时遗留与窗口重叠，必须计入；窗口整段落在有明细的
 * 区间内时不计。「全部」在任何情况下都计入。
 */
class LedgerLegacyRangeTest {

    @Test
    fun allRangeAlwaysCovers() {
        assertTrue(legacyCoversRange(allRange = true, firstUsageDay = "2026-09-01", windowStartDate = "2026-11-04"))
    }

    @Test
    fun windowInsideDetailsExcludesLegacy() {
        assertFalse(legacyCoversRange(allRange = false, firstUsageDay = "2026-09-01", windowStartDate = "2026-11-04"))
    }

    @Test
    fun windowStartingBeforeDetailsIncludesLegacy() {
        assertTrue(legacyCoversRange(allRange = false, firstUsageDay = "2026-09-01", windowStartDate = "2026-08-31"))
    }

    @Test
    fun unknownFirstDetailDayIncludesLegacy() {
        assertTrue(legacyCoversRange(allRange = false, firstUsageDay = "", windowStartDate = "2026-11-04"))
    }

    @Test
    fun windowStartOnFirstDetailDayExcludesLegacy() {
        assertFalse(legacyCoversRange(allRange = false, firstUsageDay = "2026-11-04", windowStartDate = "2026-11-04"))
    }
}
