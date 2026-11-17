package com.zmf.agent.capability.tools.session.session_move

import com.zmf.agent.capability.runtime.ChatBridge
import com.zmf.agent.capability.storage.StorageReclaimer
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.session.support.workspaceOf
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 移动会话：分组与运行根同时改。 */
class SessionMoveTool : Tool {

    override val name = "session_move"

    override val description =
        "把会话移到另一工作区，分组与运行根同时改变，不移动任何文件。" +
            "目标工作区须已存在，先用 workspace_list 核对；会话已在该工作区时不迁移。" +
            "原内置目录引用归零时删除或移入回收站；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "会话 id，取自 session_list"},
            "workspaceId": {"type": "string", "description": "目标工作区 id，取自 workspace_list"}
          },
          "required": ["id", "workspaceId"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    /** 控制类工具：不写文件、不执行命令，只读档与计划模式下照常可用。 */
    override val sandboxSensitive = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val workspaceId = args.optString("workspaceId", "").trim()
        val space = workspaceOf(ctx.context, workspaceId)
            ?: return@withContext ToolResult.Error(
                "[E_WORKSPACE_NOT_FOUND] 没有这个工作区：" + workspaceId + "；请先用 workspace_list 核对。"
            )
        val spaces = WorkSpaceStore.load(ctx.context)
        val all = SessionStore.load(ctx.context)
        val target = all.firstOrNull { it.id == id }
            ?: return@withContext ToolResult.Error("[E_SESSION_NOT_FOUND] 没有这个会话：" + id + "；请先用 session_list 核对。")
        if (target.workspaceId == workspaceId && target.storageId == space.storageId) {
            return@withContext ToolResult.Ok("会话已在该工作区")
        }
        val oldStorage = target.storageId
        val moved = all.map {
            if (it.id == id) {
                it.copy(workspaceId = workspaceId, storageId = space.storageId, updatedAt = System.currentTimeMillis())
            } else {
                it
            }
        }
        SessionStore.save(ctx.context, moved)
        val outcome = StorageReclaimer.reclaim(ctx.context, oldStorage, space.name, spaces, moved)
        ChatBridge.notifySessionsChanged()
        val suffix = if (outcome.result == StorageReclaimer.Result.TRASHED) {
            "；原内置目录已无剩余引用，已移入回收站"
        } else {
            ""
        }
        ToolResult.Ok("已移动到「" + space.name + "」" + suffix)
    }
}
