package com.zmf.agent.capability.tools.workspace.workspace_set_root

import com.zmf.agent.capability.storage.StorageReclaimer
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

/** 改映射：不移动文件，只把工作区与其余下会话指向新的存储单元。 */
class WorkspaceSetRootTool : Tool {

    override val name = "workspace_set_root"

    override val description =
        "更改工作区映射的目录，工作区与其下全部会话的运行根一起改变。" +
            "不移动、不删除文件；更改前须说明后果，原内置目录引用归零时按规则回收。" +
            "默认工作区不可改路径；需要工作区档或更高，只读档与计划模式下被拒绝。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "工作区 id，取自 workspace_list，不能是默认工作区"},
            "path": {"type": "string", "description": "新的绝对路径，目录须已存在且可写；与 treeUri 至少给一个"},
            "treeUri": {"type": "string", "description": "可选：SAF 授权目录的 tree URI，非空时优先于 path"}
          },
          "required": ["id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val path = args.optString("path", "").trim().takeIf { it.isNotEmpty() }
        val tree = args.optString("treeUri", "").trim().takeIf { it.isNotEmpty() }
        if (path == null && tree == null) {
            return@withContext ToolResult.Error("[E_INVALID] path 与 treeUri 至少要给一个，请补充其中一个。")
        }
        val spaces = WorkSpaceStore.load(ctx.context)
        val space = spaces.firstOrNull { it.id == id }
            ?: return@withContext ToolResult.Error(
                "[E_WORKSPACE_NOT_FOUND] 没有这个工作区：" + id + "；请先用 workspace_list 核对。"
            )
        if (space.isDefault) {
            return@withContext ToolResult.Error("[E_INVALID] 默认工作区不可更改储存路径，请改选其他工作区。")
        }
        val old = space.storageId
        val storage = StorageStore.createExternal(ctx.context, path, tree)
            ?: return@withContext ToolResult.Error(
                "[E_NOT_FOUND] 目录不可用：需要已存在、是目录、可写，SAF 目录需持有持久授权；请检查后重试。"
            )
        if (storage.id == old) return@withContext ToolResult.Ok("目录未变化")

        val storages = StorageStore.loadStorages(ctx.context)
        val next = if (storages.any { it.id == storage.id }) storages else storages + storage
        val nextSpaces = spaces.map { if (it.id == id) it.copy(storageId = storage.id) else it }
        StorageStore.saveRegistry(ctx.context, StorageStore.Registry(nextSpaces, next))

        // 会话的运行根随分组一起改，否则工具的相对路径会解析到旧目录
        val sessions = SessionStore.load(ctx.context)
        val moved = sessions.map { if (it.workspaceId == id) it.copy(storageId = storage.id) else it }
        SessionStore.save(ctx.context, moved)

        val outcome = StorageReclaimer.reclaim(ctx.context, old, space.name, nextSpaces, moved)
        val suffix = when (outcome.result) {
            StorageReclaimer.Result.TRASHED -> "；原内置目录已移入回收站"
            StorageReclaimer.Result.DELETED_EMPTY -> "；原内置目录为空，已直接移除"
            else -> ""
        }
        ToolResult.Ok("已更改「" + space.name + "」的储存路径" + suffix)
    }
}
