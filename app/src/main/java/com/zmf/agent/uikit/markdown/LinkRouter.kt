package com.zmf.agent.uikit.markdown

/**
 * 谁负责把链接变成浏览页。
 *
 * Markdown 里的链接（[文字](url) 和裸 URL）自带点击回调，但渲染层（MarkdownText）是纯 Compose
 * 无状态的、也不知道界面里有没有人接得住这个点击。所以这里挂一个进程级的把手：
 * 界面在场时注册，离开时摘掉。没人接（比如以后单独复用 MarkdownText 的调试界面）
 * 就返回 false，什么也不发生，绝不会突然弹系统浏览器。
 *
 * 2026-10-30 随 MarkdownText 上移 uikit：渲染层不能再反过来依赖某个 feature 的浏览器实现，
 * 注册方（聊天页）改用这个把手即可。
 */
object LinkRouter {
    var onOpen: ((String) -> Boolean)? = null

    /** 返回 true 表示「已经接住了」。 */
    fun open(url: String): Boolean {
        val handler = onOpen ?: return false
        return runCatching { handler(url) }.getOrDefault(false)
    }
}
