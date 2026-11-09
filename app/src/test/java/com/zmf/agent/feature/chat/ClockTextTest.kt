package com.zmf.agent.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 气泡时间三档口径（对齐 DSH 的 formatMessageClock）：当天只写时刻，同年更早补月日，跨年再加年份。 */
class ClockTextTest {

    /** 按本机时区造一个绝对时刻，避免测试机器的时区设置影响断言。 */
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun sameDayShowsClockOnly() {
        val now = at(2026, 11, 10, 9, 0)
        assertEquals("07:58", clockTextAt(at(2026, 11, 10, 7, 58), now))
        // 时与分都补零
        assertEquals("07:05", clockTextAt(at(2026, 11, 10, 7, 5), now))
    }

    @Test
    fun earlierThisYearShowsMonthAndDay() {
        val now = at(2026, 11, 10, 9, 0)
        assertEquals("11月9日 23:59", clockTextAt(at(2026, 11, 9, 23, 59), now))
        assertEquals("1月1日 00:00", clockTextAt(at(2026, 1, 1, 0, 0), now))
    }

    @Test
    fun previousYearShowsFullDate() {
        val now = at(2026, 11, 10, 9, 0)
        assertEquals("2025年12月31日 23:59", clockTextAt(at(2025, 12, 31, 23, 59), now))
    }

    @Test
    fun midnightBoundarySwitchesToDatedForm() {
        // 本地零点一过，「今天」变成 11-10，11-09 的消息应立即补上日期
        val now = at(2026, 11, 10, 0, 0)
        assertEquals("11月9日 23:59", clockTextAt(at(2026, 11, 9, 23, 59), now))
    }

    @Test
    fun startOfLocalDayIsMidnight() {
        val noon = at(2026, 11, 10, 12, 34)
        val midnight = at(2026, 11, 10, 0, 0)
        assertEquals(midnight, startOfLocalDay(noon))
    }

    @Test
    fun nextMidnightDelay() {
        // 23:59:59.500 距零点 500ms
        val almostMidnight = at(2026, 11, 10, 23, 59) + 59_500
        assertEquals(500L, msUntilNextLocalMidnight(almostMidnight))
        // 正落在零点上时给一整天的下一轮，而不是 0
        assertEquals(24L * 60 * 60 * 1000, msUntilNextLocalMidnight(at(2026, 11, 10, 0, 0)))
    }
}
