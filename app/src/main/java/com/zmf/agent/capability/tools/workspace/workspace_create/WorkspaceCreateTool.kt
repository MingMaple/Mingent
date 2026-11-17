package com.zmf.agent.capability.tools.workspace.workspace_create

import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.StorageStore
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 新建工作区；不传 path 即建内置目录。目录已被映射时拒绝并返回占用者。 */
class WorkspaceCreateTool : Tool {

    override val name = "workspace_create"

    override val description =
        "新建工作区：不传 path 时创建应用内置目录，传 path 时映射到该目录。" +
            "不创建目录、不移动已有文件；目录缺失时先用文件工具建好目录。" +
            "名称不得与已有工作区重名；需要工作区档或更高，只读档与计划模式下被拒绝。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "name": {"type": "string", "description": "工作区名称，显示在侧边栏，不能为空白且不得与已有工作区重名"},
            "path": {"type": "string", "description": "要映射的绝对路径，目录须已存在且可写；省略时使用应用内置目录"}
          },
          "required": ["name"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val name = args.optString("name", "").trim()
        if (name.isEmpty()) {
            return@withContext ToolResult.Error("[E_INVALID] name 不能为空，请填写工作区名称后重试。")
        }
        val path = args.optString("path", "").trim().takeIf { it.isNotEmpty() }
        if (path != null) {
            val dir = File(path)
            if (!dir.isDirectory) {
                return@withContext ToolResult.Error(
                    "[E_NOT_FOUND] 目录不存在或不是目录：" + path + "；本工具不创建目录，请先建好目录。"
                )
            }
            if (!dir.canWrite()) {
                return@withContext ToolResult.Error("[E_WRITE] 目录不可写：" + path + "；请更换目录或调整系统授权。")
            }
            val storage = StorageStore.findByPath(ctx.context, dir)
            if (storage != null) {
                val owner = WorkSpaceStore.load(ctx.context).firstOrNull { it.storageId == storage.id }
                if (owner != null) {
                    return@withContext ToolResult.Error(
                        "[E_INVALID] 该目录已由工作区「" + owner.name + "」使用，请更换目录或改用现有工作区。"
                    )
                }
            }
        }
        val existing = WorkSpaceStore.load(ctx.context).map { it.name }.toSet()
        if (name in existing) {
            return@withContext ToolResult.Error("[E_INVALID] 已有同名工作区：「" + name + "」，请更换名称后重试。")
        }

        val storage = if (path == null) {
            StorageStore.createPrivate(ctx.context, name)
        } else {
            StorageStore.createExternal(ctx.context, path, null)
                ?: return@withContext ToolResult.Error(
                    "[E_NOT_FOUND] 目录不可用：" + path + "；请确认目录已存在且可读写。"
                )
        }
        val space = WorkSpaceStore.newRecord(name, storage.id)
        val storages = StorageStore.loadStorages(ctx.context)
        val next = if (storages.any { it.id == storage.id }) storages else storages + storage
        StorageStore.saveRegistry(
            ctx.context,
            StorageStore.Registry(WorkSpaceStore.load(ctx.context) + space, next)
        )
        ToolResult.Ok("已新建工作区「" + space.name + "」（id=" + space.id + "）")
    }
}
