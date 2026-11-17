package com.zmf.agent.capability.tools.session.session_archive

import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.capability.runtime.ChatBridge
import com.zmf.agent.capability.runtime.ScheduleBridge
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 归档或恢复会话。
 *
 * 归档时以该会话为目标的定时任务一并停用解绑；恢复不自动重绑。
 * 两个方向共用同一工具，由 `archived` 决定，避免模型在语义相反的两个工具之间混用。
 */
class SessionArchiveTool : Tool {

    override val name = "session_archive"

    override val description =
        "归档或恢复会话：archived 为 true 时归档，为 false 时恢复。" +
            "归档会停用并解绑以该会话为目标的定时任务，恢复不会自动重绑。" +
            "恢复时原工作区已删除则归入「未分类」；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "会话 id，取自 session_list"},
            "archived": {
              "type": "boolean",
              "description": "为 true 时归档，为 false 时恢复；默认 true"
            }
          },
          "required": ["id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    /** 控制类工具：不写文件、不执行命令，只读档与计划模式下照常可用。 */
    override val sandboxSensitive = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val archive = args.optBoolean("archived", true)
        if (archive) archive(ctx, id) else restore(ctx, id)
    }

    private fun archive(ctx: ToolContext, id: String): ToolResult {
        val all = SessionStore.load(ctx.context)
        val target = all.firstOrNull { it.id == id }
            ?: return ToolResult.Error("[E_SESSION_NOT_FOUND] 没有这个会话：" + id + "；请先用 session_list 核对。")
        if (target.archived) return ToolResult.Ok("该会话已处于归档状态，无需重复归档。")
        // 归档后不再有人读取该会话的完成通知：先终止它名下的后台任务
        JobRegistry.killSession(ctx.context, id, "会话已归档")
        val detached = ScheduleBridge.detachSession(id)
        SessionStore.save(
            ctx.context,
            all.map {
                if (it.id == id) it.copy(archived = true, updatedAt = System.currentTimeMillis()) else it
            }
        )
        ChatBridge.notifySessionsChanged()
        val suffix = if (detached > 0) "；" + detached + " 个定时任务已停用并解绑" else ""
        return ToolResult.Ok("已归档会话「" + target.title.ifBlank { "新会话" } + "」" + suffix)
    }

    private fun restore(ctx: ToolContext, id: String): ToolResult {
        val spaces = WorkSpaceStore.load(ctx.context).map { it.id }.toSet()
        val all = SessionStore.load(ctx.context)
        val target = all.firstOrNull { it.id == id }
            ?: return ToolResult.Error("[E_SESSION_NOT_FOUND] 没有这个会话：" + id + "；请先用 session_list 核对。")
        if (!target.archived) return ToolResult.Ok("该会话未处于归档状态，无需恢复。")
        val exists = target.workspaceId != null && target.workspaceId in spaces
        val workspaceId = if (exists) target.workspaceId else null
        SessionStore.save(
            ctx.context,
            all.map {
                if (it.id == id) {
                    it.copy(archived = false, workspaceId = workspaceId, updatedAt = System.currentTimeMillis())
                } else {
                    it
                }
            }
        )
        ChatBridge.notifySessionsChanged()
        val suffix = if (!exists && target.workspaceId != null) "；原工作区已删除，已归入「未分类」" else ""
        return ToolResult.Ok("已恢复会话" + suffix)
    }
}
