package com.zmf.agent.capability.tools.browser.support

import com.zmf.agent.capability.port.DomResult
import com.zmf.agent.capability.port.ToolEnv
import com.zmf.agent.capability.port.WebControl
import com.zmf.agent.capability.tools.ToolResult
import org.json.JSONObject

/**
 * 内置浏览页的助手控制工具。
 *
 * 全部动作由浏览页以固定脚本在后台执行，不使用触摸事件与屏幕坐标；页面在操作期间只读，
 * 用户仍可上下滚动与双指缩放。浏览页未打开时端口未附着，各工具返回 E_BROWSER_UNAVAILABLE。
 */

/** 浏览页未装配时的统一回执。 */
internal fun unavailable(): ToolResult =
    ToolResult.Error(
        "[E_BROWSER_UNAVAILABLE] 内置浏览页尚未打开：该错误不能通过调整参数解决，请提示用户先打开浏览页后重试"
    )

/** 当前提供浏览控制的端口；浏览页未打开时为 null。 */
internal fun control(): WebControl? = ToolEnv.services().webControl

/** 端口结果与工具结果的边界：成功原样回传 payload，失败折算为带错误码的文本。 */
internal fun DomResult.toToolResult(): ToolResult = when (this) {
    is DomResult.Ok -> ToolResult.Ok(payload)
    is DomResult.Failure -> ToolResult.Error("[" + code + "] " + message)
}

/** 可选标签 id：空白视为未指定，取当前标签。 */
internal fun JSONObject.tabId(): String? = optString("tab_id", "").trim().ifEmpty { null }

/** 可选字符串参数：空白视为未提供。 */
internal fun JSONObject.textOrNull(name: String): String? =
    optString(name, "").trim().ifEmpty { null }
