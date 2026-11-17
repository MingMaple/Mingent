package com.zmf.agent.capability.tools.session.session_list

import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.session.support.LIST_LIMIT
import com.zmf.agent.capability.tools.session.support.sessionLine
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 列出会话；默认隐藏空白会话与已归档会话。 */
class SessionListTool : Tool {

    override val name = "session_list"

    override val description =
        "列出会话的 id、标题、归档与空白状态。" +
            "只返回列表信息，不返回消息正文；检索正文改用 session_search。" +
            "默认隐藏空白会话与已归档会话，默认最多返回 20 条；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "workspaceId": {"type": "string", "description": "工作区 id，取自 workspace_list；省略时列出全部工作区"},
            "includeArchived": {"type": "boolean", "description": "为 true 时包含已归档会话；默认 false"}
          },
          "required": []
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val workspaceId = args.optString("workspaceId", "").trim().takeIf { it.isNotEmpty() }
        val includeArchived = args.optBoolean("includeArchived", false)
        val all = SessionStore.load(ctx.context).sortedByDescending { it.updatedAt }
        val filtered = all.filter { session ->
            (includeArchived || !session.archived) &&
                session.listed &&
                (workspaceId == null || session.workspaceId == workspaceId)
        }
        if (filtered.isEmpty()) return@withContext ToolResult.Ok("没有符合条件的会话")
        val shown = filtered.take(LIST_LIMIT)
        val text = buildString {
            append("会话 ").append(filtered.size).append(" 条")
            if (filtered.size > shown.size) append("（仅列前 ").append(shown.size).append(" 条）")
            append("：\n")
            shown.forEach { append(sessionLine(it)).append("\n") }
        }
        ToolResult.Ok(text.trimEnd())
    }
}
