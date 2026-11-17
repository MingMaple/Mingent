package com.zmf.agent.capability.tools.workspace.workspace_rename

import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 只改显示名，不动目录。 */
class WorkspaceRenameTool : Tool {

    override val name = "workspace_rename"

    override val description =
        "修改工作区的显示名称，不改目录与会话归属。" +
            "默认工作区的名称固定；更改目录改用 workspace_set_root。" +
            "名称去除首尾空白后不能为空；需要工作区档或更高，只读档与计划模式下被拒绝。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "工作区 id，取自 workspace_list"},
            "name": {"type": "string", "description": "新的显示名称，去除首尾空白后不能为空"}
          },
          "required": ["id", "name"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val name = args.optString("name", "").trim()
        if (name.isEmpty()) {
            return@withContext ToolResult.Error("[E_INVALID] name 不能为空，请填写工作区名称后重试。")
        }
        val spaces = WorkSpaceStore.load(ctx.context)
        val target = spaces.firstOrNull { it.id == id }
            ?: return@withContext ToolResult.Error(
                "[E_WORKSPACE_NOT_FOUND] 没有这个工作区：" + id + "；请先用 workspace_list 核对。"
            )
        if (target.isDefault) {
            return@withContext ToolResult.Error("[E_INVALID] 默认工作区的名称固定，不可修改，请改选其他工作区。")
        }
        val renamed = WorkSpaceStore.rename(ctx.context, id, name)
            ?: return@withContext ToolResult.Error("[E_WRITE] 改名失败：" + id + "；请重新读取工作区列表后重试。")
        ToolResult.Ok("已改名为「" + renamed.name + "」")
    }
}
