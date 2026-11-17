package com.zmf.agent.capability.tools.browser.browser_scroll

import com.zmf.agent.capability.port.DomAction
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.browser.support.control
import com.zmf.agent.capability.tools.browser.support.tabId
import com.zmf.agent.capability.tools.browser.support.textOrNull
import com.zmf.agent.capability.tools.browser.support.toToolResult
import com.zmf.agent.capability.tools.browser.support.unavailable
import com.zmf.agent.core.model.PermissionLevel
import org.json.JSONObject

/** 滚动页面或把元素滚入视口。 */
class BrowserScrollTool : Tool {

    override val name = "browser_scroll"

    override val description =
        "滚动页面，或将指定元素滚动到视口顶部。" +
            "delta_y 与 to 必须提供其一；需要元素引用时先 browser_snapshot，不要按屏幕坐标滚动。" +
            "to 取 top、bottom 或 ref，取 ref 时必须给 ref；tab_id 省略时取当前标签；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "delta_y": {"type": "integer", "description": "竖直滚动位移，单位像素，正数向下、负数向上；与 to 至少提供一个"},
            "to": {"type": "string", "description": "滚动目标，取 top、bottom 或 ref；与 delta_y 至少提供一个"},
            "ref": {"type": "string", "description": "to=ref 时必填的元素引用，取自 browser_snapshot"},
            "tab_id": {"type": "string", "description": "标签 id，省略时操作当前标签"}
          }
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val deltaY = if (args.has("delta_y")) args.optInt("delta_y", 0) else null
        val to = args.optString("to", "").trim().lowercase().ifEmpty { null }
        if (to != null && to !in SCROLL_TARGETS) {
            return ToolResult.Error("[E_INVALID_ARGUMENT] to 只能取 top、bottom 或 ref：改正后重试")
        }
        val ref = args.textOrNull("ref")
        if (deltaY == null && to == null) {
            return ToolResult.Error("[E_INVALID_ARGUMENT] 必须提供 delta_y 或 to：补齐其一后重试")
        }
        if (to == "ref" && ref == null) {
            return ToolResult.Error("[E_INVALID_ARGUMENT] to 为 ref 时必须提供 ref：先调用 browser_snapshot 取得引用后重试")
        }
        val web = control() ?: return unavailable()
        return web.act(ctx.sessionId, args.tabId(), DomAction.Scroll(deltaY, to, ref)).toToolResult()
    }

    private companion object {
        val SCROLL_TARGETS = setOf("top", "bottom", "ref")
    }
}
