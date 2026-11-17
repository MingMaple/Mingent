package com.zmf.agent.capability.tools.browser.browser_wait

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

/** 等待固定时长或等待条件出现。 */
class BrowserWaitTool : Tool {

    override val name = "browser_wait"

    override val description =
        "等待固定时长，或等待页面元素、文本出现。" +
            "页面导航后确认加载状态时优先等 selector 或 text，不要反复用固定时长空等。" +
            "ms 范围 0–60000，timeout_ms 范围 1–120000；三项等待条件至少提供一个；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "ms": {"type": "integer", "description": "固定等待时长，单位毫秒，范围 0–60000；与 selector、text 至少提供一个"},
            "selector": {"type": "string", "description": "等待出现的 CSS 选择器；与 ms、text 至少提供一个"},
            "text": {"type": "string", "description": "等待出现的页面文本；与 ms、selector 至少提供一个"},
            "timeout_ms": {"type": "integer", "description": "条件等待的超时，单位毫秒，范围 1–120000，默认 5000"}
          }
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val ms = if (args.has("ms")) args.optLong("ms", 0L).coerceIn(0L, 60_000L) else null
        val selector = args.textOrNull("selector")
        val text = args.textOrNull("text")
        if (ms == null && selector == null && text == null) {
            return ToolResult.Error("[E_INVALID_ARGUMENT] 必须提供 ms、selector 或 text 之一：补齐后重试")
        }
        val timeoutMs = args.optLong("timeout_ms", 5000L).coerceIn(1L, 120_000L)
        val web = control() ?: return unavailable()
        return web.act(ctx.sessionId, null, DomAction.Wait(ms, selector, text, timeoutMs)).toToolResult()
    }
}
