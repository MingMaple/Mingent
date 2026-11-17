package com.zmf.agent.capability.tools.schedule.scheduled_task_view

import com.zmf.agent.capability.runtime.ScheduleBridge
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.schedule.support.detailText
import com.zmf.agent.capability.tools.schedule.support.lineText
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 查看定时任务：不带 id 时列出全部任务，带 id 时返回单条详情。
 *
 * 与设置页读取同一份数据；本工具只读，不改变任务与闹钟。
 */
class ScheduleViewTool : Tool {

    override val name = "scheduled_task_view"

    override val description =
        "查看定时任务：不带 id 时列出全部任务，带 id 时返回单条详情。" +
            "本工具只读，不修改任务与闹钟；修改改用 scheduled_task_edit。" +
            "列表默认返回 50 条、上限 200 条；id 不存在时返回 schedule_not_found；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "任务 id，取自本工具列表；省略或留空时列出全部任务"},
            "include_disabled": {"type": "boolean", "description": "为 true 时包含已停用任务；默认 true"},
            "include_prompt": {"type": "boolean", "description": "为 true 时列表显示提示词；默认 false"},
            "limit": {"type": "integer", "description": "列表最多返回条数，范围 1–200，默认 50"}
          },
          "required": []
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val id = args.optString("id", "").trim()
            val includeDisabled = args.optBoolean("include_disabled", true)
            val includePrompt = args.optBoolean("include_prompt", false)
            val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
            val spaces = WorkSpaceStore.load(ctx.context)

            if (id.isNotEmpty()) {
                val info = ScheduleBridge.list().firstOrNull { it.id == id }
                    ?: return@withContext ToolResult.Ok(
                        JSONObject().put("id", id).put("code", "schedule_not_found").toString()
                    )
                return@withContext ToolResult.Ok(detailText(info, spaces))
            }

            val tasks = ScheduleBridge.list().filter { includeDisabled || it.enabled }
            if (tasks.isEmpty()) return@withContext ToolResult.Ok("当前没有定时任务")
            val shown = tasks.take(limit)
            val text = buildString {
                append("定时任务 ").append(tasks.size).append(" 条")
                if (tasks.size > shown.size) {
                    append("（仅列前 ").append(shown.size).append(" 条）")
                }
                append("：\n")
                shown.forEach { info ->
                    append("- ").append(lineText(info, includePrompt, spaces)).append('\n')
                }
            }
            ToolResult.Ok(text.trimEnd())
        }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}
