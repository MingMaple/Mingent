package com.zmf.agent.feature.chat

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
 * 内置浏览器（与「计划页」对齐）：一个地址一个标签，标签并列、可左右滑。
 *
 * 点开新链接就新建一个标签、与旧标签并列，可以点回去看旧页面；标签并排超出屏幕时左右滑动切换；
 * 旧网页不删除（与计划页保持同一行为）。
 *
 * 所以：**一个标签 = 一份 WebView**。标签切走时不销毁、不重载，登录态与滚动位置都留在实例里；
 * 仅在用户点击 × 时才真正销毁。上限为 8 个（每份 WebView 均为独立渲染进程，手机上 8 份已较多），
 * 超出上限时先关闭最旧且非当前的标签。
 *
 * 状态置于 object 而非 ViewModel：WebView 为进程级资源，与 ViewModel（随 Activity 销毁）具有两套
 * 生命周期，最终会出现「标签仍在、页面已销毁」。此处状态全部由 WebView 自身的回调写入，UI 只读。
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
    var loading by mutableStateOf(false)
    /** 加载进度（0–100）：界面只在 1..99 时画进度条。 */
    var progress by mutableStateOf(0)

    /** 顶栏和标签上显示的名字：标题优先，拿不到就退回域名。 */
    val label: String get() = title.ifBlank { browserHostOf(url) }
}

/**
 * 打开过的网页：名单 + 当前这份。读 [tabs] 会订阅列表变化，顶栏靠 [alive] 决定图标显隐。
 *
 * **网页随会话切换**：每个会话拥有各自的一套标签。切走时将当前会话的标签「挂起」至 [parked]，
 * 切回后原样挂回 [tabs]——WebView 实例不销毁、不重载，因此登录态与滚动位置均保留；
 * 进程被终止后该表自然清空，回到该会话时自然没有网页。
 */
internal object BrowserState {
    val tabs = mutableStateListOf<BrowserTab>()
    var activeId by mutableStateOf<Long?>(null)

    /** 标签归属的会话 id。 */
    private var owner by mutableStateOf("")

    private class Parked(val tabs: List<BrowserTab>, val activeId: Long?)

    /** 非当前会话的标签：会话 id → 那一套标签。只在切换会话时读写。 */
    private val parked = mutableMapOf<String, Parked>()

    /** 当前标签：activeId 对应的那份；id 失效（刚被关掉）时退回第一份。 */
    val active: BrowserTab? get() = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()

    /** 是否至少有一个标签：顶栏网页图标的显隐依据。 */
    val alive: Boolean get() = tabs.isNotEmpty()

    /** 切到已经存在的标签；id 不在名单里时什么都不做。 */
    fun activate(id: Long) {
        if (tabs.any { it.id == id }) activeId = id
    }

    /**
     * 会话切换：挂起旧会话的标签，挂回新会话的那一套（新会话没有就清空）。
     *
     * 仅在 id 确实变化时执行，因此界面每帧调用也不会打断当前页面。
     */
    fun switchTo(sessionId: String) {
        if (sessionId == owner) return
        if (owner.isNotEmpty()) parked[owner] = Parked(tabs.toList(), activeId)
        owner = sessionId
        val back = parked.remove(sessionId)
        tabs.clear()
        if (back == null) {
            activeId = null
            return
        }
        tabs.addAll(back.tabs)
        activeId = back.activeId?.takeIf { id -> back.tabs.any { it.id == id } }
            ?: back.tabs.firstOrNull()?.id
    }
}

/**
 * 内置浏览器的进程级持有者：标签的新建 / 关闭 / 复用、WebView 的配置与回调都收在这里。
 *
 * 与 [BrowserState] 分工：状态放那边供界面订阅，这里只负责动作与 WebView 实例；
 * 两者都是 object，因为 WebView 的生命周期必须长于任何 Activity。
 */
internal object BrowserHolder {
    /** 同时最多保留几个标签：每个 WebView 均为独立渲染进程，手机内存无法承受无上限。 */
    private const val MAX_TABS = 8

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private var nextTabId = 1L

    /**
     * 打开一个新地址 = 新建一个标签，并切到它。
     *
     * 为什么不复用已有标签：新链接需打开新模块并保留旧网页，复用会覆盖上一个页面。
     * 复用会覆盖上一个页面，而这正是需要修正的行为。
     */
    fun open(context: Context, url: String) {
        // 同一地址不重复打开：已存在于某个标签中则切换回该标签。比较前需归一化（用户点击的 https://github.com 与标签中的 https://github.com/，以及站内跳转后的地址），直接比较字符串将始终不相等。
        // 先比较 originUrl（打开时的原始地址），再比较当前地址（允许「已在其他位置浏览到该页」时复用）。
        val key = urlKeyOf(url)
        BrowserState.tabs.firstOrNull { urlKeyOf(it.originUrl) == key || urlKeyOf(it.url) == key }
            ?.let { existing ->
                BrowserState.activeId = existing.id
                return
            }
        // 先腾出位置：达到上限时关闭「最旧且非当前」的标签
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

    /** 关闭一个标签：真正销毁 WebView（仅在用户点击 × 时进入此路径）。 */
    fun close(id: Long) {
        val index = BrowserState.tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val tab = BrowserState.tabs.removeAt(index)
        // 先移除父容器再销毁：直接 destroy 仍挂在窗口上的 WebView 会在部分机型上留下空壳
        (tab.frame.parent as? ViewGroup)?.removeView(tab.frame)
        tab.frame.removeAllViews()
        runCatching { tab.web.stopLoading() }
        runCatching { tab.web.destroy() }
        if (BrowserState.activeId == id) {
            // 关闭的是当前标签：优先接续右侧标签，否则退回左侧，全部关闭则清空
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

    /** 重新加载当前标签；没有标签时是空操作。 */
    fun reload() {
        BrowserState.active?.let { runCatching { it.web.reload() } }
    }

    /**
     * 用系统默认浏览器打开当前网页（顶栏「↗」）。
     *
     * 只发 ACTION_VIEW，不手动挑包：让系统按用户设定的默认浏览器去接，才是「转发」的本意。
     * 无法打开（无可用浏览器）时不执行任何操作，绝不让一次分享覆盖内置页面。
     */
    fun openExternally(context: Context): Boolean {
        val url = BrowserState.active?.url?.takeIf { it.isNotBlank() } ?: return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        }.getOrDefault(false)
    }

    /** 将 cookie 刷至磁盘：进程被终止后再次进入时，登录态仍保留（否则每次返回均需重新登录）。 */
    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    /**
     * 交由 AndroidView 的壳。**先将自身从旧父容器上移除**再交出：
     * 上一轮离开浏览页时 Compose 仅将壳从窗口移除，壳内 WebView 与壳的父子关系仍存在，
     * 直接 addView 至新容器会抛出 "already has a parent"。
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
                // 真实浏览器：需支持 JavaScript 与登录（静态预览不满足要求）
                javaScriptEnabled = true
                // localStorage / sessionStorage 均由此项控制，现代站点的登录态多数依赖此处
                domStorageEnabled = true
                // 页面按自身宽度排版后再整体缩放：新闻/文档类页面若不如此会被压缩为窄条
                useWideViewPort = true
                loadWithOverviewMode = true
                // 缩放交由双指手势，不启用系统的 +/- 按钮（影响观感且与长按菜单争用位置）
                builtInZoomControls = false
                displayZoomControls = false
                // 禁止网页自行弹出新窗口：链接一律在当前标签中打开，标签仅由用户点击链接决定
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                // 仅放行 http/https：网页无法读取 App 私有目录中的文件
                allowFileAccess = false
                allowContentAccess = false
                // 许多旧站点在 https 页面中混用 http 资源，直接拦截会导致白屏；使用兼容模式按提示混合加载
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // 移除 UA 中的 "; wv" 标记：部分站点（含 GitHub 登录页）对 WebView UA 另行处理
                userAgentString = userAgentString.replace("; wv", "")
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                // 第三方 cookie 同样放行：OAuth 登录常涉及跨站跳转，拦截会停留在「授权成功但无法跳回」
                runCatching { setAcceptThirdPartyCookies(web, true) }
            }
            val frame = FrameLayout(context)
            frame.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
            val tab = BrowserTab(nextTabId++, web, frame, url)
            tab.url = url
            // 回调中写入的是**该标签自身**的状态：多个标签各自记录标题与历史
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
                    // 登录成功时 cookie 仅在内存中；此处刷盘一次，进程被终止后再次进入仍可复用
                    flushCookies()
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (!title.isNullOrBlank()) tab.title = title
                }

                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    tab.progress = newProgress
                    if (newProgress >= 100) tab.loading = false
                }
            }
            tab
        } catch (e: Throwable) {
            // 系统 WebView 被停用或降级时 new WebView 会直接抛出异常，不可使整个界面随之崩溃
            FileLog.log("browser", "WebView 创建失败: " + e.message)
            null
        }
    }

    /** 将网页状态同步回标签：当前地址与标题。 */
    private fun sync(tab: BrowserTab, view: WebView, pageUrl: String?) {
        val current = pageUrl ?: view.url
        if (!current.isNullOrBlank()) tab.url = current
        // 无法获取标题时浏览页会回退显示域名（见 BrowserTab.label），此处不填入占位文字
        val title = view.title
        if (!title.isNullOrBlank()) tab.title = title
    }

    /**
     * 仅放行 http/https 在浏览器内加载；mailto / tel / market 交由系统处理。
     * 其余 scheme（含 intent: / file: / content: / 自定义 scheme）一律丢弃并记日志，
     * 既不让未知 scheme 覆盖当前页面，也避免网页借本应用身份拉起任意组件。
     */
    private fun handleUrl(view: WebView, raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return true
        val scheme = uri.scheme?.lowercase() ?: return true
        if (scheme == "http" || scheme == "https") return false
        if (scheme !in EXTERNAL_SCHEMES) {
            FileLog.log("browser", "已忽略非白名单 scheme: " + scheme)
            return true
        }
        runCatching {
            view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure { FileLog.log("browser", "外部 scheme 打不开: " + scheme) }
        return true
    }
}

private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "market")

/**
 * 判断「是否为同一网址」所用的键：忽略首尾空白、末尾斜杠与大小写。
 *
 * 仅执行此层归一化：查询串与锚点必须原样保留（同页面的不同分页、不同小节属于不同地址），
 * 继续剥离（去除 www、补默认端口等）会将本就不同的地址视为同一个。
 */
internal fun urlKeyOf(url: String?): String {
    if (url.isNullOrBlank()) return ""
    return url.trim().trimEnd('/').lowercase()
}

/** 标签上显示的地址：长地址仅保留域名，完整 URL 在页面内查看。 */
internal fun browserHostOf(url: String?): String {
    if (url.isNullOrBlank()) return ""
    return runCatching {
        val uri = Uri.parse(url)
        uri.host ?: url
    }.getOrDefault(url)
}
