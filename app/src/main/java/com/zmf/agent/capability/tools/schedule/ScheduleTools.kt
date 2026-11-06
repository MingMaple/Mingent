package com.zmf.agent.capability.tools.schedule

import com.zmf.agent.capability.runtime.ScheduleBridge
import com.zmf.agent.capability.runtime.ScheduleInfo
import com.zmf.agent.capability.runtime.ScheduleSpec
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.StorageStore
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 定时任务工具集：创建、列出、删除。
 *
 * 三条行为约定：
 * 1. 创建前先确认时间与指令，不猜测用户未说明的时刻；
 * 2. 工作区与会话 id 一律校验，不存在即返回错误，不静默落到默认值；
 * 3. 删除只接受 schedule_list 返回的 id，不按标题模糊删除。
 *
 * 数据与闹钟均由定时任务域负责；本组工具只经由 [ScheduleBridge] 调用，
 * capability 层不反向依赖 feature。
 */

/** 间隔上限：7 天，与定时任务域的约束一致。 */
private const val MAX_EVERY_MINUTES = 7 * 24 * 60

/** 一次性任务至少安排在一分钟后，避免创建即触发。 */
private const val MIN_ONCE_MINUTES = 1

/** 创建：把一条规则写进定时任务并设置闹钟。 */
class ScheduleCreateTool : Tool {

    override val name = "schedule_create"

    override val description =
        "创建定时任务。用户要求在某时刻提醒、或按固定周期执行某项操作时使用；" +
            "创建后结果会追加到指定会话，并在到点时发送系统通知。" +
            "kind 取 once（一次）、daily（每天同一时刻）或 every（每 N 分钟）。" +
            "once 需提供 time 或 in_minutes；daily 需提供 time；every 需提供 every_minutes。" +
            "time 使用 24 小时制 HH:mm，date 使用 yyyy-MM-dd（仅 once 可用）。" +
            "不得替用户猜测时刻：用户未说明时先询问。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "title": {"type": "string", "description": "任务名称，显示在定时任务列表中，建议不超过 20 字"},
            "prompt": {"type": "string", "description": "到点后发给模型的完整指令，例如「提醒我喝水」"},
            "kind": {"type": "string", "enum": ["once", "daily", "every"], "description": "触发类型"},
            "time": {"type": "string", "description": "HH:mm；once 与 daily 使用"},
            "date": {"type": "string", "description": "yyyy-MM-dd；仅 once 使用，省略时按最近一次该时刻计算"},
            "in_minutes": {"type": "integer", "description": "仅 once：从现在起多少分钟后触发，与 time 二选一"},
            "every_minutes": {"type": "integer", "description": "仅 every：间隔分钟数，范围 1–10080"},
            "target_session_id": {"type": "string", "description": "可选：结果追加到哪条会话；省略时使用当前会话"},
            "workspace_id": {"type": "string", "description": "可选：任务归属工作区；省略时按当前工作区推导"}
          },
          "required": ["title", "prompt", "kind"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val title = args.optString("title", "").trim()
            val prompt = args.optString("prompt", "").trim()
            val kind = args.optString("kind", "").trim().lowercase(Locale.US)
            if (title.isEmpty()) return@withContext ToolResult.Error("title 不能为空")
            if (prompt.isEmpty()) return@withContext ToolResult.Error("prompt 不能为空")
            if (kind !in setOf("once", "daily", "every")) {
                return@withContext ToolResult.Error("kind 只能是 once、daily 或 every")
            }

            val timeOfDay = args.optString("time", "").trim()
            val date = args.optString("date", "").trim()
            val inMinutes = args.optInt("in_minutes", 0)
            val everyMinutes = args.optInt("every_minutes", 0)
            val onceAt = when {
                kind != "once" -> 0L
                inMinutes > 0 -> System.currentTimeMillis() + inMinutes * 60_000L
                timeOfDay.isNotEmpty() -> onceMillis(timeOfDay, date) ?: return@withContext ToolResult.Error(
                    "time 或 date 格式不正确：time 用 HH:mm，date 用 yyyy-MM-dd，且触发时刻必须在未来"
                )
                else -> return@withContext ToolResult.Error("once 任务需要提供 time 或 in_minutes")
            }
            if (kind == "once" && onceAt <= System.currentTimeMillis() + 30_000L) {
                return@withContext ToolResult.Error("一次性任务的触发时刻至少要在 1 分钟后")
            }
            if (kind == "daily" && parseMinuteOfDay(timeOfDay) == null) {
                return@withContext ToolResult.Error("daily 任务需要提供 HH:mm 格式的 time")
            }
            if (kind == "every" && (everyMinutes < 1 || everyMinutes > MAX_EVERY_MINUTES)) {
                return@withContext ToolResult.Error("every_minutes 需在 1–10080 之间")
            }

            val targetSession = resolveTargetSession(args, ctx)
            if (!targetSession.valid) {
                return@withContext ToolResult.Error("target_session_id 不存在")
            }
            val workspace = resolveWorkspace(args, ctx)
            if (!workspace.valid) {
                return@withContext ToolResult.Error("workspace_id 不存在")
            }
            val targetSessionId = targetSession.value
            val workspaceId = workspace.value

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
            ) ?: return@withContext ToolResult.Error("定时任务服务尚未初始化，无法创建任务")

            val targetText = info.targetSessionId?.let { "会话 " + it } ?: "新建会话"
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

/** 列出全部定时任务：类型、下次触发、目标会话与启用状态。 */
class ScheduleListTool : Tool {

    override val name = "schedule_list"

    override val description =
        "列出全部定时任务：id、名称、触发规则、下次触发时刻、目标会话与启用状态。" +
            "删除或修改任务前先调用本工具取得 id，不要凭猜测填写。"

    override val parametersJson = """
        {"type":"object","properties":{},"required":[]}
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val tasks = ScheduleBridge.list()
            if (tasks.isEmpty()) return@withContext ToolResult.Ok("当前没有定时任务")
            val text = buildString {
                append("定时任务 ").append(tasks.size).append(" 条：\n")
                tasks.forEach { task ->
                    append("- 「").append(task.title).append("」（id=").append(task.id).append("）：")
                    append(scheduleText(task))
                    append("；")
                    if (task.enabled) {
                        append("下次 ").append(timeText(task.nextAt))
                    } else {
                        append("已停用")
                    }
                    append("；目标 ")
                    append(task.targetSessionId?.let { "会话 " + it } ?: "新建会话")
                    append('\n')
                }
            }
            ToolResult.Ok(text.trimEnd())
        }
}

/** 删除一条定时任务并取消闹钟。 */
class ScheduleDeleteTool : Tool {

    override val name = "schedule_delete"

    override val description =
        "删除一条定时任务并取消其闹钟。参数 id 取 schedule_list 返回的 id；" +
            "删除前应向用户复述任务名称。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "schedule_list 返回的任务 id"}
          },
          "required": ["id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val id = args.optString("id", "").trim()
            if (id.isEmpty()) return@withContext ToolResult.Error("id 不能为空")
            val title = ScheduleBridge.list().firstOrNull { it.id == id }?.title
            if (!ScheduleBridge.delete(id)) {
                return@withContext ToolResult.Error("未找到 id=" + id + " 的定时任务")
            }
            ToolResult.Ok("已删除定时任务" + (title?.let { "「" + it + "」" } ?: "") + "（id=" + id + "）")
        }
}

/** 解析结果：valid=false 表示显式 id 不存在；value=null 表示合法的「默认工作区 / 新建会话」。 */
private data class Lookup(val valid: Boolean, val value: String?)

/** 目标会话：显式 id 必须存在；省略时使用当前会话，当前会话为空则交由定时任务域新建。 */
private fun resolveTargetSession(args: JSONObject, ctx: ToolContext): Lookup {
    val explicit = args.optString("target_session_id", "").trim()
    if (explicit.isNotEmpty()) {
        val exists = SessionStore.load(ctx.context).any { it.id == explicit }
        return Lookup(valid = exists, value = explicit.takeIf { exists })
    }
    return Lookup(valid = true, value = ctx.sessionId.takeIf { it.isNotBlank() })
}

/** 归属工作区：显式 id 必须存在；省略时按当前工作区根目录匹配，未命中则落在默认工作区。 */
private fun resolveWorkspace(args: JSONObject, ctx: ToolContext): Lookup {
    val explicit = args.optString("workspace_id", "").trim()
    if (explicit.isNotEmpty()) {
        val exists = WorkSpaceStore.load(ctx.context).any { it.id == explicit }
        return Lookup(valid = exists, value = explicit.takeIf { exists })
    }
    val root = ctx.workspaceRoot.absolutePath
    val matched = WorkSpaceStore.load(ctx.context).firstOrNull { space ->
        val storage = WorkSpaceStore.storageOf(ctx.context, space) ?: return@firstOrNull false
        StorageStore.rootFile(ctx.context, storage)?.absolutePath == root
    }
    val id = matched?.id?.takeIf { it != WorkSpaceStore.DEFAULT_ID }
    return Lookup(valid = true, value = id)
}

private fun parseMinuteOfDay(raw: String): Pair<Int, Int>? {
    val parts = raw.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].trim().toIntOrNull() ?: return null
    val minute = parts[1].trim().toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour to minute
}

/** 规则文案：每天 / 每 N 分钟 / 指定时刻。 */
private fun scheduleText(info: ScheduleInfo): String = when (info.kind) {
    "daily" -> "每天 " + info.timeOfDay
    "every" -> "每 " + info.everyMinutes + " 分钟"
    else -> "一次性 " + timeText(info.atMillis)
}

private fun timeText(millis: Long): String =
    if (millis <= 0L) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
