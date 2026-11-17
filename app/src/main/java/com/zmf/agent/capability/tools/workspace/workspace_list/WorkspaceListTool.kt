package com.zmf.agent.capability.tools.workspace.workspace_list

import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.workspace.support.LIST_LIMIT
import com.zmf.agent.capability.tools.workspace.support.workspaceLine
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 列出工作区：id、名称、根形态、是否默认。 */
class WorkspaceListTool : Tool {

    override val name = "workspace_list"

    override val description =
        "列出全部工作区的 id、名称、根形态、路径与是否默认工作区。" +
            "只读列表，用于取得工作区 id；不要凭猜测填写 id。" +
            "默认最多返回 20 条；只读档可用。"

    override val parametersJson = """
        {"type":"object","properties":{},"required":[]}
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val spaces = WorkSpaceStore.load(ctx.context)
        if (spaces.isEmpty()) return@withContext ToolResult.Ok("当前没有工作区")
        val shown = spaces.take(LIST_LIMIT)
        val text = buildString {
            append("工作区 ").append(spaces.size).append(" 个")
            if (spaces.size > shown.size) append("（仅列前 ").append(shown.size).append(" 个）")
            append("：\n")
            shown.forEach { append(workspaceLine(ctx.context, it)).append("\n") }
        }
        ToolResult.Ok(text.trimEnd())
    }
}
