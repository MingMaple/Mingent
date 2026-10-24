package com.zmf.agent.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zmf.agent.core.log.FileLog

/**
 * 内置浏览器（2026-10-25 用户裁定，口径与「计划页」对齐）：一个地址一个标签，标签并列、可左右滑。
 *
 * 用户原话：
 *  - 「点击了一个新链接，就创建一个新的模块，和上一个模块并列。可以直接点上一个模块去浏览上一个模块的页面。」
 *  - 「有多个模块、并排超出屏幕，那可以左右滑动模块查看，并且切换进去。」
 *  - 「和计划页一样：不删除旧网页，旧网页就保留。」
 *
 * 所以：**一个标签 = 一份 WebView**。标签切走时不销毁、不重载，登录态与滚动位置都留在实例里；
 * 只有用户点 × 才真的销毁。8 个是上限（每份 WebView 都是实打实的渲染进程，手机上 8 份已经不少），
 * 超了就先关最老的那个、非当前的标签。
 *
 * 状态放 object 而不是 ViewModel：WebView 是进程级资源，和 ViewModel（跟 Activity 一起死）走两套
 * 生命周期，迟早出现「标签还在、页面没了」。这里的状态全由 WebView 自己的回调写，UI 只读。
 */
internal class BrowserTab(
    val id: Long,
    val web: WebView,
    val frame: FrameLayout,
    /** 用户点开它时那个地址（不会被站内跳转改掉）：判断「同一个网址」用的就是它。 */
    val originUrl: String
) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var canGoBack by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var progress by mutableStateOf(0)

    /** 顶栏和标签上显示的名字：标题优先，拿不到就退回域名。 */
    val label: String get() = title.ifBlank { browserHostOf(url) }
}

/** 打开过的网页：名单 + 当前这份。读 [tabs] 会订阅列表变化，顶栏靠 [alive] 决定图标显隐。 */
internal object BrowserState {
    val tabs = mutableStateListOf<BrowserTab>()
    var activeId by mutableStateOf<Long?>(null)

    /** 当前标签：activeId 对应的那份；id 失效（刚被关掉）时退回第一份。 */
    val active: BrowserTab? get() = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()

    val alive: Boolean get() = tabs.isNotEmpty()

    fun activate(id: Long) {
        if (tabs.any { it.id == id }) activeId = id
    }
}

/**
 * 谁负责把链接变成浏览页。
 *
 * Markdown 里的链接（[文字](url) 和裸 URL）自带点击回调，但渲染层（MarkdownText）是纯 Compose
 * 无状态的、也不知道界面里有没有人接得住这个点击。所以这里挂一个进程级的把手：
 * ChatScreen 在场时注册，离开时摘掉。没人接（比如以后单独复用 MarkdownText 的调试界面）
 * 就返回 false，什么也不发生，绝不会突然弹系统浏览器。
 */
internal object LinkRouter {
    var onOpen: ((String) -> Boolean)? = null

    /** 返回 true 表示「已经接住了」。 */
    fun open(url: String): Boolean {
        val handler = onOpen ?: return false
        return runCatching { handler(url) }.getOrDefault(false)
    }
}

internal object BrowserHolder {
    /** 同时最多留几个标签：每个 WebView 都是真渲染进程，手机内存扛不住无上限。 */
    private const val MAX_TABS = 8

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private var nextTabId = 1L

    /**
     * 打开一个新地址 = 新建一个标签，并切到它。
     *
     * 为什么不复用已有标签：用户要的就是「新链接开新模块、旧网页留着」，
     * 复用会把上一个页面顶掉，那正是他要改掉的行为。
     */
    fun open(context: Context, url: String) {
        // 同一个地址不重复开：已经在某个标签里就切回那一个（2026-10-25 用户：点同一个网址应该复用）。
        // 两边都要归一化再比：用户点的是 https://github.com，而标签里已经是 https://github.com/
        // 或者被站内跳转成了别的地址——直接比字符串就永远不相等，上一版正是这样漏掉的。
        // 先比 originUrl（点开时的原始地址），再比当前地址（允许「已经在别处逛到这一页」时复用）。
        val key = urlKeyOf(url)
        BrowserState.tabs.firstOrNull { urlKeyOf(it.originUrl) == key || urlKeyOf(it.url) == key }
            ?.let { existing ->
                BrowserState.activeId = existing.id
                return
            }
        // 先腾位置：达到上限就把「最老的、且不是当前」的标签关掉
        while (BrowserState.tabs.size >= MAX_TABS) {
            val victim = BrowserState.tabs.firstOrNull { it.id != BrowserState.activeId } ?: break
            close(victim.id)
        }
        val tab = createTab(context, url) ?: return
        BrowserState.tabs.add(tab)
        BrowserState.activeId = tab.id
        runCatching { tab.web.loadUrl(url) }
            .onFailure { FileLog.log("browser", "loadUrl 失败: " + url + " / " + it.message) }
    }

    /** 关掉一个标签：真的销毁 WebView（只有用户点 × 才走到这里）。 */
    fun close(id: Long) {
        val index = BrowserState.tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val tab = BrowserState.tabs.removeAt(index)
        // 先摘父容器再销毁：直接 destroy 一个还挂在窗口上的 WebView 会在某些机型上留下空壳
        (tab.frame.parent as? ViewGroup)?.removeView(tab.frame)
        tab.frame.removeAllViews()
        runCatching { tab.web.stopLoading() }
        runCatching { tab.web.destroy() }
        if (BrowserState.activeId == id) {
            // 关的是当前这份：优先接上它右边那张，没有就退回左边，全关了就清空
            BrowserState.activeId = BrowserState.tabs.getOrNull(index)?.id
                ?: BrowserState.tabs.lastOrNull()?.id
        }
        FileLog.log("browser", "关闭标签 " + id + "，剩 " + BrowserState.tabs.size + " 个")
    }

    /** 网页内部后退（只影响当前标签的浏览历史，不影响「回主页」）。 */
    fun goBack(): Boolean {
        val tab = BrowserState.active ?: return false
        if (!tab.web.canGoBack()) return false
        tab.web.goBack()
        return true
    }

    fun reload() {
        BrowserState.active?.let { runCatching { it.web.reload() } }
    }

    /** 把 cookie 刷到磁盘：进程被杀之后再进来，登录态还在（不然每次回来都要重登）。 */
    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    /**
     * 交给 AndroidView 的壳。**先把自己从旧父容器上摘下来**再交出去：
     * 上一轮离开浏览页时 Compose 只把壳从窗口里摘了，壳里的 WebView 和壳的父子关系还在，
     * 直接 addView 到新容器会抛 "already has a parent"。
     */
    fun frameFor(tab: BrowserTab): FrameLayout {
        (tab.frame.parent as? ViewGroup)?.removeView(tab.frame)
        return tab.frame
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(context: Context, url: String): BrowserTab? {
        return try {
            val web = WebView(context)
            web.settings.apply {
                // 真浏览器：要能跑 JS、要能登录（这两条是用户明确要求的，静态预览不算数）
                javaScriptEnabled = true
                // localStorage / sessionStorage 都归这一条管，现代站的登录态一半在这儿
                domStorageEnabled = true
                // 页面按自身宽度排版，再整体缩放：新闻/文档类页面不这么做会挤成一根窄条
                useWideViewPort = true
                loadWithOverviewMode = true
                // 缩放交给双指手势，不挂系统那对 +/- 按钮（难看，而且和长按菜单抢位置）
                builtInZoomControls = false
                displayZoomControls = false
                // 不让网页自己弹新窗口：链接一律在当前标签里打开，标签只由用户点链接决定
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                // 只放 http/https：网页读不到 App 私有目录里的文件
                allowFileAccess = false
                allowContentAccess = false
                // https 页面里夹 http 资源的老站很多，直接拦会白屏；用兼容模式按提示混着加载
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // 去掉 UA 里的 "; wv" 标记：有些站（含 GitHub 的登录页）对 WebView UA 另眼相待
                userAgentString = userAgentString.replace("; wv", "")
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                // 第三方 cookie 也放行：OAuth 登录经常跨站跳转，拦掉会卡在「授权成功但跳不回来」
                runCatching { setAcceptThirdPartyCookies(web, true) }
            }
            val frame = FrameLayout(context)
            frame.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
            val tab = BrowserTab(nextTabId++, web, frame, url)
            tab.url = url
            // 回调里写的是**这个标签自己**的状态：多个标签各记各的标题与历史
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    handleUrl(view, request.url?.toString())

                @Deprecated("API 24 以下的旧签名，26 起系统都走上面那条")
                override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
                    handleUrl(view, url)

                override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                    tab.loading = true
                    tab.progress = 0
                    sync(tab, view, pageUrl)
                }

                override fun onPageFinished(view: WebView, pageUrl: String?) {
                    tab.loading = false
                    sync(tab, view, pageUrl)
                    // 登录成功那一刻 cookie 只在内存里；这里刷一次盘，进程被杀再回来还认得你
                    flushCookies()
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (!title.isNullOrBlank()) tab.title = title
                    tab.canGoBack = view.canGoBack()
                }

                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    tab.progress = newProgress
                    if (newProgress >= 100) tab.loading = false
                }
            }
            tab
        } catch (e: Throwable) {
            // 系统 WebView 被停用/降级时 new WebView 会直接抛，不能让整个界面跟着崩
            FileLog.log("browser", "WebView 创建失败: " + e.message)
            null
        }
    }

    /** 地址里能改的只有这两样：到哪儿了、能不能退。 */
    private fun sync(tab: BrowserTab, view: WebView, pageUrl: String?) {
        tab.canGoBack = view.canGoBack()
        val current = pageUrl ?: view.url
        if (!current.isNullOrBlank()) tab.url = current
        // 标题拿不到时浏览页会退回显示域名（见 BrowserTab.label），这里不塞占位文字
        val title = view.title
        if (!title.isNullOrBlank()) tab.title = title
    }

    /**
     * 只放行 http/https 在浏览器内加载；其余 scheme（intent: / market: / weixin: / mailto:）
     * 丢给系统去处理，处理不了就什么也不做——绝不让未知 scheme 顶掉当前页面。
     */
    private fun handleUrl(view: WebView, raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return true
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> false
            else -> {
                runCatching {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }.onFailure { FileLog.log("browser", "外部 scheme 打不开: " + raw) }
                true
            }
        }
    }
}

/**
 * 判断「是不是同一个网址」用的键：忽略首尾空白、末尾斜杠与大小写。
 *
 * 只做这一层归一化：查询串和锚点必须原样保留（同页面的不同分页、不同小节是两个地址），
 * 再往下剥（去 www、补默认端口之类）就会把本来就不同的地址当成同一个。
 */
internal fun urlKeyOf(url: String?): String {
    if (url.isNullOrBlank()) return ""
    return url.trim().trimEnd('/').lowercase()
}

/** 标签上显示的地址：长地址只留域名，完整 URL 进页面里看。 */
internal fun browserHostOf(url: String?): String {
    if (url.isNullOrBlank()) return ""
    return runCatching {
        val uri = Uri.parse(url)
        uri.host ?: url
    }.getOrDefault(url)
}
