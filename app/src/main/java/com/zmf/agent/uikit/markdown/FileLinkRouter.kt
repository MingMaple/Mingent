package com.zmf.agent.uikit.markdown

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 行内代码里的产物路径 → 预览页。
 *
 * 与 [LinkRouter] 同一思路：渲染层不认识会话与工作区，只把「这一串字符是不是产物路径」
 * 交回给注册方判断；没有注册方时，所有路径仍按普通行内代码显示。
 */
object FileLinkRouter {

    const val SCHEME = "mingent-file:"

    /** 输入行内代码原文，返回可打开的绝对路径；不是产物路径返回 null。 */
    var resolve: ((String) -> String?)? = null
        private set

    /**
     * 产物集合的代次：注册方每次更换集合就 +1。
     *
     * 行内解析结果按原文缓存，而「同一段文字在 A 会话里是产物、在 B 会话里不是」是常态；
     * 代次进缓存键，换会话后不会把上一个会话的链接状态沿用过来。
     * 用 Compose 状态承载：代次一变，读过它的正文会重算，链接即刻生效。
     */
    var generation by mutableIntStateOf(0)
        private set

    /** 注册方用新的产物集合替换解析器。 */
    fun attach(resolver: ((String) -> String?)?) {
        resolve = resolver
        generation++
    }

    /** 注册方收到点击后打开预览页；返回 false 表示没处理。 */
    var open: ((String) -> Boolean)? = null

    fun urlFor(token: String): String? {
        val handler = resolve ?: return null
        val path = runCatching { handler(token) }.getOrNull() ?: return null
        return SCHEME + path
    }

    fun openUrl(url: String): Boolean {
        if (!url.startsWith(SCHEME)) return false
        val handler = open ?: return false
        return runCatching { handler(url.removePrefix(SCHEME)) }.getOrDefault(false)
    }
}
