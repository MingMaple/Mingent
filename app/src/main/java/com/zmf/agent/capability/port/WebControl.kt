package com.zmf.agent.capability.port

/** 浏览器标签快照。 */
data class WebTabInfo(
    val id: String,
    val title: String,
    val url: String,
    val loading: Boolean,
    val active: Boolean
)

/**
 * 待执行的页面动作。
 *
 * 参数原样下发给界面实现；能力层不解释 DOM，也不持有脚本文本。
 */
sealed interface DomAction {
    data class Snapshot(val mode: String, val maxNodes: Int) : DomAction
    data class Click(val ref: String?, val selector: String?, val waitMs: Int) : DomAction
    data class TypeText(
        val ref: String,
        val text: String,
        val clear: Boolean,
        val submit: Boolean
    ) : DomAction

    data class Scroll(val deltaY: Int?, val to: String?, val ref: String?) : DomAction
    data class Extract(
        val ref: String?,
        val selector: String?,
        val format: String,
        val maxChars: Int
    ) : DomAction

    data class Wait(
        val ms: Long?,
        val selector: String?,
        val text: String?,
        val timeoutMs: Long
    ) : DomAction
}

/** 页面动作结果；[payload] 为 JSON 文本，由界面实现生成。 */
sealed interface DomResult {
    data class Ok(val payload: String) : DomResult
    data class Failure(val code: String, val message: String) : DomResult
}

/**
 * 网页控制端口。
 *
 * 命令按标签串行执行，全程使用注入脚本，不使用触摸事件与屏幕坐标。
 * 用户终止只结束本次页面操作，不取消助手协程。
 *
 * **每个方法都带 [sessionId]**（用户 2026-11-16）：页面实例按会话隔离，A 的 `browser_*`
 * 只能操作 A 自己打开的标签；该会话没有打开浏览页时明确失败，不回落到显示会话。
 * 工具实现一律传 `ctx.sessionId`。
 */
interface WebControl {

    /** 打开地址；[newTab] 为真时新建标签。 */
    suspend fun open(sessionId: String, url: String, newTab: Boolean): DomResult

    /** 该会话的标签列表。 */
    fun tabs(sessionId: String): List<WebTabInfo>

    /** 在指定标签执行动作；[tabId] 为空表示该会话的活动标签。 */
    suspend fun act(sessionId: String, tabId: String?, action: DomAction): DomResult

    /** 终止该会话某个标签的本次页面操作。 */
    fun abort(sessionId: String, tabId: String?, reason: String)
}
