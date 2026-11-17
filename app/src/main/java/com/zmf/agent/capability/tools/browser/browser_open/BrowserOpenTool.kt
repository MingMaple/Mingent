package com.zmf.agent.capability.tools.browser.browser_open

import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.browser.support.control
import com.zmf.agent.capability.tools.browser.support.toToolResult
import com.zmf.agent.capability.tools.browser.support.unavailable
import com.zmf.agent.core.model.PermissionLevel
import org.json.JSONObject

/** 在浏览页中打开网页，可选择新建标签。 */
class BrowserOpenTool : Tool {

    override val name = "browser_open"

    override val description =
        "在内置浏览页中打开网页，只放行 http 与 https 地址。" +
            "需要页面元素时先调用本工具再用 browser_snapshot；抓取静态网页用 web_fetch，不要用本工具。" +
            "new_tab 默认 true；站点内导航传 false 在当前标签打开并返回标签 id；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "url": {"type": "string", "description": "要打开的地址，必填，必须以 http:// 或 https:// 开头"},
            "new_tab": {"type": "boolean", "description": "为 true 时新建标签，默认 true；为 false 时在当前标签打开并返回标签 id"}
          },
          "required": ["url"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    /** 控制类工具：不写文件、不执行命令，只读档与计划模式下照常可用。 */
    override val sandboxSensitive = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val url = args.optString("url", "").trim()
        if (url.isEmpty()) {
            return ToolResult.Error("[E_INVALID_ARGUMENT] 缺少参数 url：给出 http 或 https 地址后重试")
        }
        val web = control() ?: return unavailable()
        return web.open(ctx.sessionId, url, args.optBoolean("new_tab", true)).toToolResult()
    }
}
