package com.zmf.agent.capability.tools.schedule.support

import com.zmf.agent.capability.runtime.ScheduleBridge
import com.zmf.agent.capability.runtime.ScheduleInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时任务工具集：创建、列出、删除。
 *
 * 三条行为约定：
 * 1. 创建前先确认时间与指令，不猜测用户未说明的时刻；
 * 2. 工作区与会话 id 一律校验，不存在即返回错误，不静默落到默认值；
 * 3. 删除只接受 scheduled_task_view 返回的 id，不按标题模糊删除。
 *
 * 数据与闹钟均由定时任务域负责；本组工具只经由 [ScheduleBridge] 调用，
 * capability 层不反向依赖 feature。
 */

/** 间隔上限：7 天，与定时任务域的约束一致。 */
internal const val MAX_EVERY_MINUTES = 7 * 24 * 60

/** 解析「时:分」形式的时刻；格式或取值范围非法时返回 null。 */
internal fun parseMinuteOfDay(raw: String): Pair<Int, Int>? {
    val parts = raw.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].trim().toIntOrNull() ?: return null
    val minute = parts[1].trim().toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour to minute
}

/** 规则文案：每天 / 每 N 分钟 / 指定时刻。 */
internal fun scheduleText(info: ScheduleInfo): String = when (info.kind) {
    "daily" -> "每天 " + info.timeOfDay
    "every" -> "每 " + info.everyMinutes + " 分钟"
    else -> "一次性 " + timeText(info.atMillis)
}

/** 时间戳的展示文案；非正值显示为「—」。 */
internal fun timeText(millis: Long): String =
    if (millis <= 0L) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
