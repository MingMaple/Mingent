package com.zmf.agent.capability.tools.workspace.workspace_delete

import com.zmf.agent.capability.storage.StorageReclaimer
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 删除映射：其下会话转未分类，内置目录按引用归零回收。 */
class WorkspaceDeleteTool : Tool {

    override val name = "workspace_delete"

    override val description =
        "删除工作区的映射，不删除任何用户文件。" +
            "默认工作区不可删除；删除前须在对话中说明会话转「未分类」的后果。" +
            "内置目录引用归零时直接删除或移入回收站；需要工作区档或更高。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {"id": {"type": "string", "description": "工作区 id，取自 workspace_list，不能是默认工作区"}},
          "required": ["id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val spaces = WorkSpaceStore.load(ctx.context)
        val space = spaces.firstOrNull { it.id == id }
            ?: return@withContext ToolResult.Error(
                "[E_WORKSPACE_NOT_FOUND] 没有这个工作区：" + id + "；请先用 workspace_list 核对。"
            )
        if (space.isDefault) {
            return@withContext ToolResult.Error("[E_INVALID] 默认工作区不可删除，请改选其他工作区。")
        }

        val remaining = spaces.filter { it.id != id }
        WorkSpaceStore.save(ctx.context, remaining)
        // 其下空白会话直接删除：它从未转正，留在未分类只会成为不可见的残留
        val sessions = SessionStore.load(ctx.context)
        val keep = sessions.filterNot { it.workspaceId == id && it.blank }
        val released = keep.map { if (it.workspaceId == id) it.copy(workspaceId = null) else it }
        // 有变化才写盘：无变化时避免整表重写会话文件
        if (released != sessions) SessionStore.save(ctx.context, released)
        val outcome = StorageReclaimer.reclaim(ctx.context, space.storageId, space.name, remaining, released)
        val suffix = when (outcome.result) {
            StorageReclaimer.Result.TRASHED -> "；内置目录已移入回收站，保留 30 天"
            StorageReclaimer.Result.DELETED_EMPTY -> "；内置目录为空，已直接移除"
            else -> ""
        }
        ToolResult.Ok("已删除工作区「" + space.name + "」的映射" + suffix)
    }
}
