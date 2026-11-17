package com.zmf.agent.capability.tools.browser.browser_click

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

/** 按元素引用或选择器点击页面元素。 */
class BrowserClickTool : Tool {

    override val name = "browser_click"

    override val description =
        "按快照元素引用或 CSS 选择器点击页面元素，点击后在页面内等待指定时长。" +
            "页面导航后须用 browser_snapshot 重新定位，不要沿用旧 ref；纯文本抓取用 browser_extract。" +
            "wait_ms 范围 0–30000 毫秒；tab_id 省略时取当前标签；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "ref": {"type": "string", "description": "快照返回的元素引用，与 selector 二选一"},
            "selector": {"type": "string", "description": "CSS 选择器，与 ref 二选一"},
            "tab_id": {"type": "string", "description": "标签 id，省略时操作当前标签"},
            "wait_ms": {"type": "integer", "description": "点击后的等待时长，单位毫秒，范围 0–30000，默认 800"}
          }
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    /** 控制类工具：不写文件、不执行命令，只读档与计划模式下照常可用。 */
    override val sandboxSensitive = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val ref = args.textOrNull("ref")
        val selector = args.textOrNull("selector")
        if (ref == null && selector == null) {
            return ToolResult.Error("[E_INVALID_ARGUMENT] 必须提供 ref 或 selector：补齐其一后重试")
        }
        val waitMs = args.optInt("wait_ms", 800).coerceIn(0, 30_000)
        val web = control() ?: return unavailable()
        return web.act(ctx.sessionId, args.tabId(), DomAction.Click(ref, selector, waitMs)).toToolResult()
    }
}
