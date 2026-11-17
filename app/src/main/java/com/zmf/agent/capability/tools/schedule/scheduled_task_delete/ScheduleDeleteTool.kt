package com.zmf.agent.capability.tools.schedule.scheduled_task_delete

import com.zmf.agent.capability.runtime.ScheduleBridge
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.PermissionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 删除一条定时任务并取消闹钟。 */
class ScheduleDeleteTool : Tool {

    override val name = "scheduled_task_delete"

    override val description =
        "删除一条定时任务并取消其闹钟。" +
            "只接受 scheduled_task_view 返回的 id，不按标题删除；删除前应向用户复述任务名称。" +
            "任务不存在时返回 schedule_not_found；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "任务 id，取自 scheduled_task_view，必须是该工具返回的原值"}
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
            val title = ScheduleBridge.list().firstOrNull { it.id == id }?.title
            if (!ScheduleBridge.delete(id)) {
                return@withContext ToolResult.Ok(
                    JSONObject().put("id", id).put("code", "schedule_not_found").toString()
                )
            }
            ToolResult.Ok("已删除定时任务" + (title?.let { "「" + it + "」" } ?: "") + "（id=" + id + "）")
        }
}
