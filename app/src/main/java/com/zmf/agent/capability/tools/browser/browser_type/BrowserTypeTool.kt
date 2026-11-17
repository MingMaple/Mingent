package com.zmf.agent.capability.tools.browser.browser_type

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

/** 向输入框写入文本。 */
class BrowserTypeTool : Tool {

    override val name = "browser_type"

    override val description =
        "向页面输入框写入文本，可选择清空原值并提交所在表单。" +
            "密码框不接受自动输入，需要用户手动填写；元素定位先用 browser_snapshot。" +
            "tab_id 省略时取当前标签；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "ref": {"type": "string", "description": "快照返回的输入框引用，必填"},
            "text": {"type": "string", "description": "要写入的文本，必填，可为空串"},
            "clear": {"type": "boolean", "description": "为 true 时写入前清空原值，默认 true"},
            "submit": {"type": "boolean", "description": "为 true 时写入后提交所在表单，默认 false"},
            "tab_id": {"type": "string", "description": "标签 id，省略时操作当前标签"}
          },
          "required": ["ref", "text"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    /** 控制类工具：不写文件、不执行命令，只读档与计划模式下照常可用。 */
    override val sandboxSensitive = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val ref = args.textOrNull("ref")
        if (ref == null) {
            return ToolResult.Error("[E_INVALID_ARGUMENT] 缺少参数 ref：先调用 browser_snapshot 取得输入框引用后重试")
        }
        if (!args.has("text")) {
            return ToolResult.Error("[E_INVALID_ARGUMENT] 缺少参数 text：补齐写入内容后重试")
        }
        val web = control() ?: return unavailable()
        return web.act(
            ctx.sessionId,
            args.tabId(),
            DomAction.TypeText(
                ref = ref,
                text = args.optString("text", ""),
                clear = args.optBoolean("clear", true),
                submit = args.optBoolean("submit", false)
            )
        ).toToolResult()
    }
}
