package com.zmf.agent.capability.tools.schedule.support

import com.zmf.agent.capability.runtime.ScheduleInfo
import com.zmf.agent.core.store.WorkSpace

/** 一行任务摘要：标题、id、版本、规则、下次触发、目标与启用状态。 */
internal fun lineText(info: ScheduleInfo, includePrompt: Boolean, spaces: List<WorkSpace>): String =
    buildString {
        append("「").append(info.title).append("」（id=").append(info.id)
        append("，revision=").append(info.revision).append("）：")
        append(ruleText(info))
        append("；下次 ").append(if (info.nextAt > 0L) timeText(info.nextAt) else "—")
        append("；目标 ").append(targetText(info, spaces))
        append("；").append(if (info.enabled) "启用" else "停用")
        if (includePrompt && info.prompt.isNotEmpty()) {
            append("；提示词：").append(oneLine(info.prompt))
        }
    }

/** 单条详情：在前述摘要之后追加提示词与上次触发时刻。 */
internal fun detailText(info: ScheduleInfo, spaces: List<WorkSpace>): String = buildString {
    append(lineText(info, includePrompt = false, spaces = spaces))
    if (info.prompt.isNotEmpty()) {
        append('\n').append("提示词：").append(oneLine(info.prompt))
    }
    append('\n').append("上次触发：")
    append(if (info.lastRunAt > 0L) timeText(info.lastRunAt) else "尚未触发")
}

/** 触发规则：每天 HH:mm、每 N 分钟或指定的一次性时刻。 */
internal fun ruleText(info: ScheduleInfo): String = when (info.kind) {
    "daily" -> "每天 " + info.timeOfDay
    "every" -> "每 " + info.everyMinutes + " 分钟"
    else -> "一次性 " + timeText(info.atMillis)
}

/** 目标：已有会话，或某工作区的新会话。 */
internal fun targetText(info: ScheduleInfo, spaces: List<WorkSpace>): String {
    val sessionId = info.targetSessionId?.takeIf { it.isNotEmpty() }
    if (sessionId != null) return "会话 " + sessionId
    val name = spaces.firstOrNull { it.id == info.workspaceId }?.name ?: "默认工作区"
    return "工作区「" + name + "」的新会话"
}

/** 把多行文本压成一行：提示词与片段在工具结果中不得占用多行。 */
private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()
