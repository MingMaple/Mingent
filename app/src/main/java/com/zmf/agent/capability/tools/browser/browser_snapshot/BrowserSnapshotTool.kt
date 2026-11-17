package com.zmf.agent.capability.tools.browser.browser_snapshot

import com.zmf.agent.capability.port.DomAction
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.browser.support.control
import com.zmf.agent.capability.tools.browser.support.tabId
import com.zmf.agent.capability.tools.browser.support.toToolResult
import com.zmf.agent.capability.tools.browser.support.unavailable
import com.zmf.agent.core.model.PermissionLevel
import org.json.JSONObject

/** 读取页面可交互元素快照。 */
class BrowserSnapshotTool : Tool {

    override val name = "browser_snapshot"

    override val description =
        "读取当前页面可交互元素的快照，作为后续点击、输入与滚动的定位依据。" +
            "页面导航后必须重新快照；需要正文而非元素时用 browser_extract。" +
            "max_nodes 上限 300；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "tab_id": {"type": "string", "description": "标签 id，省略时操作当前标签"},
            "mode": {"type": "string", "description": "快照模式，取 interactive（元素清单，含 ref、role、name、bounds、disabled 与 password 标记）或 text（可见文本）；默认 interactive"},
            "max_nodes": {"type": "integer", "description": "返回节点数上限，范围 1–300，默认 120"}
          }
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val mode = if (args.optString("mode", "interactive").lowercase() == "text") "text" else "interactive"
        val maxNodes = args.optInt("max_nodes", 120).coerceIn(1, 300)
        val web = control() ?: return unavailable()
        return web.act(ctx.sessionId, args.tabId(), DomAction.Snapshot(mode, maxNodes)).toToolResult()
    }
}
