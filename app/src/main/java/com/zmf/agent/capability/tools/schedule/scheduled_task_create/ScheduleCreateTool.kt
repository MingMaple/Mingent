package com.zmf.agent.capability.tools.schedule.scheduled_task_create

import com.zmf.agent.capability.runtime.ScheduleBridge
import com.zmf.agent.capability.runtime.ScheduleSpec
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.schedule.support.MAX_EVERY_MINUTES
import com.zmf.agent.capability.tools.schedule.support.parseMinuteOfDay
import com.zmf.agent.capability.tools.schedule.support.scheduleText
import com.zmf.agent.capability.tools.schedule.support.timeText
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 创建：把一条规则写进定时任务并设置闹钟。 */
class ScheduleCreateTool : Tool {

    override val name = "scheduled_task_create"

    override val description =
        "在定时任务列表中新建一条任务，并设置下一次触发的闹钟。" +
            "提示词原文、触发方式与投递目标须由用户逐项选定，任一项未定应先询问用户，不得自行假定。" +
            "间隔上限 10080 分钟，一次性任务须晚于当前 30 秒；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "title": {"type": "string", "description": "任务名称，显示在定时任务列表中，建议不超过 20 字"},
            "prompt": {"type": "string", "description": "到点后发给助手的完整提示词原文，不能为空"},
            "kind": {
              "type": "string",
              "enum": ["once", "daily", "every"],
              "description": "触发方式，取 once（只触发一次）、daily（每天同一时刻）、every（每 N 分钟）；必填，由用户选定"
            },
            "time": {"type": "string", "description": "触发时刻，格式 HH:mm；once 与 daily 使用，once 下与 in_minutes 二选一"},
            "date": {"type": "string", "description": "触发日期，格式 yyyy-MM-dd；仅 once 使用，省略时按最近一次该时刻计算"},
            "in_minutes": {"type": "integer", "description": "仅 once：从现在起多少分钟后触发，正整数，与 time 二选一"},
            "every_minutes": {"type": "integer", "description": "仅 every：间隔分钟数，范围 1–10080"},
            "target": {
              "type": "string",
              "enum": ["session", "workspace_new", "default_new"],
              "description": "投递目标，取 session（指定工作区的指定会话）、workspace_new（指定工作区的新会话）、default_new（默认工作区的新会话）；必填，由用户选定"
            },
            "session_id": {"type": "string", "description": "target=session 时必填，目标会话 id，取自 session_list"},
            "workspace_id": {"type": "string", "description": "target=workspace_new 时必填，目标工作区 id，取自 workspace_list"}
          },
          "required": ["title", "prompt", "kind"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    /** 控制类工具：不写文件、不执行命令，只读档与计划模式下照常可用。 */
    override val sandboxSensitive = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val title = args.optString("title", "").trim()
            val prompt = args.optString("prompt", "").trim()
            val kind = args.optString("kind", "").trim().lowercase(Locale.US)
            if (title.isEmpty()) {
                return@withContext ToolResult.Error("[E_INVALID] title 不能为空，请填写任务名称后重试。")
            }
            if (prompt.isEmpty()) {
                return@withContext ToolResult.Error("[E_INVALID] prompt 不能为空，请填写到点提示词后重试。")
            }
            if (kind !in setOf("once", "daily", "every")) {
                return@withContext ToolResult.Error("[E_INVALID] kind 只能取 once、daily 或 every，请重新选择触发方式。")
            }

            val timeOfDay = args.optString("time", "").trim()
            val date = args.optString("date", "").trim()
            val inMinutes = args.optInt("in_minutes", 0)
            val everyMinutes = args.optInt("every_minutes", 0)
            val onceAt = when {
                kind != "once" -> 0L
                inMinutes > 0 -> System.currentTimeMillis() + inMinutes * 60_000L
                timeOfDay.isNotEmpty() -> onceMillis(timeOfDay, date) ?: return@withContext ToolResult.Error(
                    "[E_BAD_RULE] time 或 date 格式不正确：time 用 HH:mm，date 用 yyyy-MM-dd，且触发时刻必须在未来；请修正后重试。"
                )
                else -> return@withContext ToolResult.Error(
                    "[E_BAD_RULE] once 任务需要提供 time 或 in_minutes，请补充其中一个。"
                )
            }
            if (kind == "once" && onceAt <= System.currentTimeMillis() + 30_000L) {
                return@withContext ToolResult.Error(
                    "[E_PAST_TIME] 一次性任务的触发时刻至少要在 30 秒后，请调整触发时刻。"
                )
            }
            if (kind == "daily" && parseMinuteOfDay(timeOfDay) == null) {
                return@withContext ToolResult.Error("[E_BAD_RULE] daily 任务需要提供 HH:mm 格式的 time，请补充。")
            }
            if (kind == "every" && (everyMinutes < 1 || everyMinutes > MAX_EVERY_MINUTES)) {
                return@withContext ToolResult.Error("[E_BAD_RULE] every_minutes 需在 1–10080 之间，请调整间隔。")
            }

            // 发到哪里必须由用户选定：三种模式各自校验，不给默认值
            val target = args.optString("target", "").trim()
            val sessionArg = args.optString("session_id", "").trim()
            val workspaceArg = args.optString("workspace_id", "").trim()
            val spaces = WorkSpaceStore.load(ctx.context)
            val targetSessionId: String?
            val workspaceId: String?
            when (target) {
                "session" -> {
                    if (sessionArg.isEmpty()) {
                        return@withContext ToolResult.Error(
                            "[E_TARGET_MISSING] target=session 需要 session_id，请先向用户确认发到哪条会话。"
                        )
                    }
                    val found = SessionStore.load(ctx.context).firstOrNull { it.id == sessionArg }
                        ?: return@withContext ToolResult.Error(
                            "[E_NOT_FOUND] session_id 不存在：" + sessionArg + "；请先用 session_list 核对。"
                        )
                    targetSessionId = found.id
                    workspaceId = found.workspaceId
                }
                "workspace_new" -> {
                    if (workspaceArg.isEmpty()) {
                        return@withContext ToolResult.Error(
                            "[E_TARGET_MISSING] target=workspace_new 需要 workspace_id，请先向用户确认发到哪个工作区。"
                        )
                    }
                    if (spaces.none { it.id == workspaceArg }) {
                        return@withContext ToolResult.Error(
                            "[E_NOT_FOUND] workspace_id 不存在：" + workspaceArg + "；请先用 workspace_list 核对。"
                        )
                    }
                    targetSessionId = null
                    workspaceId = workspaceArg
                }
                "default_new" -> {
                    targetSessionId = null
                    workspaceId = spaces.firstOrNull { it.isDefault }?.id
                }
                else -> return@withContext ToolResult.Error(
                    "[E_BAD_RULE] target 必须取 session、workspace_new 或 default_new，请由用户选定投递目标。"
                )
            }

            val info = ScheduleBridge.create(
                ScheduleSpec(
                    title = title,
                    prompt = prompt,
                    kind = kind,
                    everyMinutes = everyMinutes,
                    timeOfDay = if (kind == "every" || kind == "once" && inMinutes > 0) "" else timeOfDay,
                    atMillis = onceAt,
                    targetSessionId = targetSessionId,
                    workspaceId = workspaceId
                )
            ) ?: return@withContext ToolResult.Error(
                "[E_SERVICE_UNAVAILABLE] 定时任务服务尚未初始化，无法创建任务；此项不能通过调整参数解决，请稍后重试或重启应用。"
            )

            val targetText = when {
                info.targetSessionId != null -> "已有会话 " + info.targetSessionId
                target == "workspace_new" -> "工作区「" + workspaceArg + "」的新会话"
                else -> "默认工作区的新会话"
            }
            ToolResult.Ok(
                "已创建定时任务「" + info.title + "」：" + scheduleText(info) +
                    "；下次触发 " + timeText(info.nextAt) +
                    "；结果写入" + targetText + "（id=" + info.id + "）。" +
                    "可在「设置 → 通用 → 定时任务」中查看或关闭。"
            )
        }

    /** 把 HH:mm（可带日期）换算为绝对毫秒；格式错误或时刻已过时返回 null。 */
    private fun onceMillis(time: String, date: String): Long? {
        val minute = parseMinuteOfDay(time) ?: return null
        val hour = minute.first
        val minuteOfHour = minute.second
        val now = System.currentTimeMillis()
        if (date.isNotEmpty()) {
            val parsed = runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
                    .parse(date + " " + String.format(Locale.US, "%02d:%02d", hour, minuteOfHour))
            }.getOrNull() ?: return null
            return parsed.time.takeIf { it > now + 30_000L }
        }
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minuteOfHour)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
}
