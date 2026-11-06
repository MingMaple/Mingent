package com.zmf.agent.feature.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 定时任务的排期计算（纯逻辑，不依赖 Android 框架）。
 *
 * 覆盖三处最容易被后续改动破坏的约定：周期任务以计划时刻为锚、间隔下限为一分钟、
 * 一次性任务过期后返回 0、非法时间格式返回 0。
 */
class ScheduleStoreTest {

    private val minute = 60_000L

    private fun everyTask(everyMinutes: Int = 1, nextAt: Long = 0L) = ScheduleTask(
        kind = ScheduleStore.KIND_EVERY,
        everyMinutes = everyMinutes,
        nextAt = nextAt
    )

    @Test
    fun every_fromPlannedTime_addsOneInterval() {
        val now = 1_000L
        assertEquals(now + minute, ScheduleStore.nextAfter(everyTask(nextAt = now), now))
    }

    @Test
    fun every_overdue_jumpsToNearestFutureCycle() {
        // 相隔三个周期以上：只取 [now] 之后最近的一次，不连续补执行
        val now = 200_000L
        assertEquals(241_000L, ScheduleStore.nextAfter(everyTask(nextAt = 1_000L), now))
    }

    @Test
    fun every_intervalBelowMinimum_isClampedToOneMinute() {
        val now = 1_000L
        assertEquals(now + minute, ScheduleStore.nextAfter(everyTask(everyMinutes = 0, nextAt = now), now))
    }

    @Test
    fun once_reachedOrExpired_returnsZero() {
        val task = ScheduleTask(kind = ScheduleStore.KIND_ONCE, atMillis = 10_000L)
        assertEquals(10_000L, ScheduleStore.nextAfter(task, 5_000L))
        assertEquals(0L, ScheduleStore.nextAfter(task, 10_000L))
    }

    @Test
    fun daily_invalidTimeOfDay_returnsZero() {
        val task = ScheduleTask(kind = ScheduleStore.KIND_DAILY, timeOfDay = "25:99")
        assertEquals(0L, ScheduleStore.nextAfter(task, 0L))
    }
}
