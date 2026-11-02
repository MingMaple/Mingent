package com.zmf.agent.uikit.markdown

/**
 * 负责将链接打开为浏览页。
 *
 * Markdown 中的链接（[文字](url) 与裸 URL）自带点击回调，但渲染层（MarkdownText）为纯 Compose
 * 无状态实现，无法判断界面中是否存在接收方。因此此处设置进程级回调：
 * 界面存在时注册，离开时移除。无接收方时（例如后续单独复用 MarkdownText 的调试界面）
 * 返回 false，不执行任何操作，不会意外弹出系统浏览器。
 *
 * 渲染层不得反向依赖某个 feature 的浏览器实现；注册方（聊天页）通过该回调完成跳转。
 */
object LinkRouter {
    var onOpen: ((String) -> Boolean)? = null

    /** 返回 true 表示已处理该链接。 */
    fun open(url: String): Boolean {
        val handler = onOpen ?: return false
        return runCatching { handler(url) }.getOrDefault(false)
    }
}
