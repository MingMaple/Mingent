package com.zmf.agent.capability.tools.session.session_rename

import com.zmf.agent.capability.runtime.ChatBridge
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 改标题。 */
class SessionRenameTool : Tool {

    override val name = "session_rename"

    override val description =
        "修改会话标题。" +
            "只改标题，不改消息、工作区与运行根；移动会话改用 session_move。" +
            "标题去除首尾空白后不能为空；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "id": {"type": "string", "description": "会话 id，取自 session_list"},
            "title": {"type": "string", "description": "新标题，去除首尾空白后不能为空"}
          },
          "required": ["id", "title"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    /** 控制类工具：不写文件、不执行命令，只读档与计划模式下照常可用。 */
    override val sandboxSensitive = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val title = args.optString("title", "").trim()
        if (title.isEmpty()) {
            return@withContext ToolResult.Error("[E_INVALID] title 不能为空，请填写会话标题后重试。")
        }
        val all = SessionStore.load(ctx.context)
        if (all.none { it.id == id }) {
            return@withContext ToolResult.Error("[E_SESSION_NOT_FOUND] 没有这个会话：" + id + "；请先用 session_list 核对。")
        }
        SessionStore.save(ctx.context, all.map { if (it.id == id) it.copy(title = title) else it })
        ChatBridge.notifySessionsChanged()
        ToolResult.Ok("已改名为「" + title + "」")
    }
}
