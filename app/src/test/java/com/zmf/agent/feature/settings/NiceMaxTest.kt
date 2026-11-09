package com.zmf.agent.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 折线图纵轴上限：必须是「好看的数字」，并且不小于峰值。 */
class NiceMaxTest {

    @Test
    fun emptySeriesUsesOne() {
        assertEquals(1L, niceMax(0L))
        assertEquals(1L, niceMax(-5L))
    }

    @Test
    fun picksNextNiceStep() {
        assertEquals(1_500_000L, niceMax(1_072_083L))
        assertEquals(2_000_000L, niceMax(1_500_001L))
        assertEquals(80L, niceMax(67L))
        assertEquals(1L, niceMax(1L))
    }

    @Test
    fun extremeValuesDoNotOverflow() {
        // 五个亿、十个亿这种量级也要给出上限，且不能因为 Int 溢出变成负数
        assertTrue(niceMax(500_000_000L) >= 500_000_000L)
        assertTrue(niceMax(1_000_000_000L) >= 1_000_000_000L)
        assertTrue(niceMax(Long.MAX_VALUE / 4) >= Long.MAX_VALUE / 4)
    }

    @Test
    fun upperBoundCoversPeak() {
        val peaks = longArrayOf(1L, 7L, 67L, 1_072_083L, 23_634L, 897_132L, 1_255_738_486L)
        for (peak in peaks) {
            assertTrue("peak=$peak", niceMax(peak) >= peak)
        }
    }
}
