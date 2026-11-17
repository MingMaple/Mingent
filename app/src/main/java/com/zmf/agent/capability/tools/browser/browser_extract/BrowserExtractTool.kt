package com.zmf.agent.capability.tools.browser.browser_extract

import com.zmf.agent.capability.port.DomAction
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.browser.support.control
import com.zmf.agent.capability.tools.browser.support.textOrNull
import com.zmf.agent.capability.tools.browser.support.toToolResult
import com.zmf.agent.capability.tools.browser.support.unavailable
import com.zmf.agent.core.model.PermissionLevel
import org.json.JSONObject

/** 从页面或指定元素抽取内容。 */
class BrowserExtractTool : Tool {

    override val name = "browser_extract"

    override val description =
        "从整个页面或指定元素抽取文本、HTML、链接或表格。" +
            "ref 与 selector 二选一，均省略时抽取整页；需要元素定位时用 browser_snapshot，抓取静态网页用 web_fetch。" +
            "format 默认 text，max_chars 默认 8000、上限 20000；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "ref": {"type": "string", "description": "快照返回的元素引用，与 selector 二选一；均省略时抽取整页"},
            "selector": {"type": "string", "description": "CSS 选择器，与 ref 二选一；均省略时抽取整页"},
            "format": {"type": "string", "description": "抽取格式，取 text（纯文本，默认）、html、links（链接列表）或 table（表格）"},
            "max_chars": {"type": "integer", "description": "返回字符上限，范围 1–20000，默认 8000"}
          }
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val format = args.optString("format", "text").trim().lowercase().let {
            if (it in EXTRACT_FORMATS) it else "text"
        }
        val maxChars = args.optInt("max_chars", 8000).coerceIn(1, 20_000)
        val web = control() ?: return unavailable()
        return web.act(
            ctx.sessionId,
            null,
            DomAction.Extract(
                ref = args.textOrNull("ref"),
                selector = args.textOrNull("selector"),
                format = format,
                maxChars = maxChars
            )
        ).toToolResult()
    }

    private companion object {
        val EXTRACT_FORMATS = setOf("text", "html", "links", "table")
    }
}
