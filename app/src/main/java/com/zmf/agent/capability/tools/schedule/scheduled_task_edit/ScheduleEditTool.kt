package com.zmf.agent.capability.tools.schedule.scheduled_task_edit

import com.zmf.agent.capability.runtime.ScheduleBridge
import com.zmf.agent.capability.runtime.SchedulePatch
import com.zmf.agent.capability.runtime.ScheduleUpdate
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.schedule.support.ruleText
import com.zmf.agent.capability.tools.schedule.support.targetText
import com.zmf.agent.capability.tools.schedule.support.timeText
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * 修改一条定时任务：只改动给出的字段，未提供的参数保持不变。
 *
 * 修改前须先以 scheduled_task_view 取得当前 id 与版本号；携带 expected_revision 时，
 * 版本不一致会被拒绝并返回当前快照，避免覆盖其他入口刚完成的修改。
 */
class ScheduleEditTool : Tool {

    override val name = "scheduled_task_edit"

    override val description =
        "修改一条定时任务，只改动给出的字段，未提供的参数保持不变。" +
            "修改前先调用 scheduled_task_view 取得 id 与 revision，不得凭猜测填写。" +
            "expected_revision 与当前版本不一致时拒绝写入；间隔上限 10080 分钟；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "任务 id，取自 scheduled_task_view"},
            "expected_revision": {"type": "integer", "description": "可选：期望版本号，取自 scheduled_task_view 的 revision；与当前不一致时拒绝写入，省略时不校验版本"},
            "title": {"type": "string", "description": "新任务名称，省略时不修改"},
            "prompt": {"type": "string", "description": "新的到点提示词，省略时不修改"},
            "kind": {
              "type": "string",
              "enum": ["once", "daily", "every"],
              "description": "新的触发方式，取 once、daily、every；省略时不修改，切换时会清空不适用的字段"
            },
            "time": {"type": "string", "description": "触发时刻，格式 HH:mm；daily 与 once 使用，省略时不修改"},
            "date": {"type": "string", "description": "触发日期，格式 yyyy-MM-dd；仅 once 使用，省略时不修改"},
            "in_minutes": {"type": "integer", "description": "仅 once：从现在起多少分钟后触发，正整数，与 time 二选一，省略时不修改"},
            "every_minutes": {"type": "integer", "description": "仅 every：间隔分钟数，范围 1–10080，省略时不修改"},
            "target": {
              "type": "string",
              "enum": ["session", "workspace_new", "default_new"],
              "description": "投递目标，取 session（指定工作区的指定会话）、workspace_new（指定工作区的新会话）、default_new（默认工作区的新会话）；省略时不修改"
            },
            "session_id": {"type": "string", "description": "target=session 时的目标会话 id，取自 session_list"},
            "workspace_id": {"type": "string", "description": "target=workspace_new 时的目标工作区 id，取自 workspace_list"},
            "enabled": {"type": "boolean", "description": "为 true 时启用任务，为 false 时停用；省略时不修改"}
          },
          "required": ["id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    /** 控制类工具：不写文件、不执行命令，只读档与计划模式下照常可用。 */
    override val sandboxSensitive = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val id = args.optString("id", "").trim()
            if (id.isEmpty()) {
                return@withContext ToolResult.Error("[E_NOT_FOUND] id 不能为空，请先用 scheduled_task_view 取得任务 id。")
            }

            val kind = stringArg(args, "kind")?.takeIf { it.isNotEmpty() }?.lowercase(Locale.US)
            if (kind != null && kind !in KIND_VALUES) {
                return@withContext ToolResult.Error("[E_BAD_RULE] kind 只能取 once、daily 或 every，请修正后重试。")
            }

            val time = stringArg(args, "time")?.takeIf { it.isNotEmpty() }
            if (time != null && !validTime(time)) {
                return@withContext ToolResult.Error(
                    "[E_BAD_RULE] time 格式不正确：应使用 24 小时制 HH:mm，请修正后重试。"
                )
            }
            val date = stringArg(args, "date")?.takeIf { it.isNotEmpty() }
            if (date != null && !validDate(date)) {
                return@withContext ToolResult.Error("[E_BAD_RULE] date 格式不正确：应使用 yyyy-MM-dd，请修正后重试。")
            }

            val inMinutes = intArg(args, "in_minutes")
            if (inMinutes != null && inMinutes < 1) {
                return@withContext ToolResult.Error("[E_BAD_RULE] in_minutes 必须是大于 0 的整数，请修正后重试。")
            }
            val everyMinutes = intArg(args, "every_minutes")
            if (everyMinutes != null && (everyMinutes < 1 || everyMinutes > MAX_EVERY_MINUTES)) {
                return@withContext ToolResult.Error("[E_BAD_RULE] every_minutes 需在 1–10080 之间，请调整间隔后重试。")
            }

            val target = stringArg(args, "target")?.takeIf { it.isNotEmpty() }?.lowercase(Locale.US)
            if (target != null && target !in TARGET_VALUES) {
                return@withContext ToolResult.Error(
                    "[E_BAD_RULE] target 只能取 session、workspace_new 或 default_new，请修正后重试。"
                )
            }

            val patch = SchedulePatch(
                title = stringArg(args, "title"),
                prompt = stringArg(args, "prompt"),
                kind = kind,
                time = time,
                date = date,
                inMinutes = inMinutes,
                everyMinutes = everyMinutes,
                target = target,
                sessionId = stringArg(args, "session_id")?.takeIf { it.isNotEmpty() },
                workspaceId = stringArg(args, "workspace_id")?.takeIf { it.isNotEmpty() },
                enabled = boolArg(args, "enabled")
            )
            if (patch.isEmpty()) {
                return@withContext ToolResult.Error("[E_BAD_RULE] 至少需要提供一个要修改的字段，请补充字段后重试。")
            }

            val expectedRevision = if (args.has("expected_revision") && !args.isNull("expected_revision")) {
                args.optLong("expected_revision", -1L)
            } else {
                null
            }
            val spaces = WorkSpaceStore.load(ctx.context)
            when (val result = ScheduleBridge.update(id, patch, expectedRevision)) {
                is ScheduleUpdate.Ok -> {
                    val info = result.info
                    ToolResult.Ok(
                        "已更新定时任务「" + info.title + "」（id=" + info.id +
                            "，revision=" + info.revision + "）：" + ruleText(info) +
                            "；下次触发 " + timeText(info.nextAt) +
                            "；目标 " + targetText(info, spaces)
                    )
                }
                ScheduleUpdate.NotFound -> ToolResult.Ok(
                    JSONObject().put("id", id).put("code", "schedule_not_found").toString()
                )
                is ScheduleUpdate.Conflict -> ToolResult.Ok(
                    JSONObject()
                        .put("id", id)
                        .put("code", "schedule_conflict")
                        .put("current_revision", result.current.revision)
                        .put("message", "任务已被其他入口修改，请重新读取后再修改")
                        .toString()
                )
                is ScheduleUpdate.BadRule -> ToolResult.Error(
                    "[E_BAD_RULE] " + result.reason + "；请按参数说明修正后重试。"
                )
                is ScheduleUpdate.TargetMissing -> ToolResult.Error(
                    "[E_TARGET_MISSING] " + result.reason + "；请先用 session_list 或 workspace_list 核对目标。"
                )
                ScheduleUpdate.PastTime -> ToolResult.Error(
                    "[E_PAST_TIME] 一次性任务的触发时刻必须晚于当前时间，请调整触发时刻。"
                )
            }
        }

    private companion object {
        const val MAX_EVERY_MINUTES = 7 * 24 * 60
        val KIND_VALUES = setOf("once", "daily", "every")
        val TARGET_VALUES = setOf("session", "workspace_new", "default_new")
    }
}

/** 读取可空字符串参数：键不存在或值为 JSON null 时返回 null。 */
private fun stringArg(args: JSONObject, key: String): String? {
    if (!args.has(key) || args.isNull(key)) return null
    return args.optString(key, "").trim()
}

/** 读取可空整数参数：键不存在或值为 JSON null 时返回 null。 */
private fun intArg(args: JSONObject, key: String): Int? {
    if (!args.has(key) || args.isNull(key)) return null
    return args.optInt(key, 0)
}

/** 读取可空布尔参数：键不存在或值为 JSON null 时返回 null。 */
private fun boolArg(args: JSONObject, key: String): Boolean? {
    if (!args.has(key) || args.isNull(key)) return null
    return args.optBoolean(key, false)
}

/** "HH:mm" 是否合法。 */
private fun validTime(raw: String): Boolean {
    val parts = raw.trim().split(":")
    if (parts.size != 2) return false
    val hour = parts[0].trim().toIntOrNull() ?: return false
    val minute = parts[1].trim().toIntOrNull() ?: return false
    return hour in 0..23 && minute in 0..59
}

/** "yyyy-MM-dd" 是否合法。 */
private fun validDate(raw: String): Boolean =
    runCatching { java.time.LocalDate.parse(raw.trim()) }.isSuccess

/** 补丁是否为空：全部字段为 null 表示没有要修改的内容。 */
private fun SchedulePatch.isEmpty(): Boolean =
    title == null && prompt == null && kind == null && time == null && date == null &&
        inMinutes == null && everyMinutes == null && target == null && sessionId == null &&
        workspaceId == null && enabled == null
