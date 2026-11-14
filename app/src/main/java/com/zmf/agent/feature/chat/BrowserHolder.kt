package com.zmf.agent.feature.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
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
import androidx.compose.ui.graphics.toArgb
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.uikit.theme.Palette
import java.util.concurrent.Executors

/**
 * 网页在顶栏收放时的排版策略。
 *
 * - [FIXED]：整屏排版，顶栏收放只平移；适合单屏、文档类内容。
 * - [ADAPTIVE]：动画期间只平移，端点上提交一次高度；适合长页面与带底部固定条的页面。
 */
internal enum class PageFitMode {
    /** 平铺型：网页始终按整屏排版，顶栏收放只平移，永不改高度。 */
    FIXED,

    /** 自适应型：动画期间只平移，端点上提交一次高度，让页面利用顶栏让出的空间。 */
    ADAPTIVE
}

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

    /**
     * 页面适配模式：由 [BrowserHolder] 在页面完成时按内容探测，默认自适应。
     *
     * 单屏且没有 fixed 底部条的页面走 [PageFitMode.FIXED]；长页面与带底部固定条的页面走
     * [PageFitMode.ADAPTIVE]。探测结果只写标签状态，浏览器页据此选择收放路径。
     */
    var fitMode by mutableStateOf(PageFitMode.ADAPTIVE)

    /** 顶栏和标签上显示的名字：标题优先，拿不到就退回域名。 */
    val label: String get() = title.ifBlank { browserHostOf(url) }
}

/**
 * 内置浏览器（与「计划页」对齐）：一个地址一个标签，标签并列、可左右滑。
 *
 * 点开新链接就新建一个标签、与旧标签并列，可以点回去看旧页面；标签并排超出屏幕时左右滑动切换；
 * 旧网页不删除（与计划页保持同一行为）。
 *
 * 所以：**一个标签 = 一份 WebView**。标签切走时不销毁、不重载，登录态与滚动位置都留在实例里；
 * 仅在用户点击 × 时才真正销毁。上限为 8 个（现代 Android 上所有 WebView 共用一个渲染器进程，
 * 但 8 个标签仍各自持有 JS 上下文、合成层与缓存，手机上 8 份已较多），
 * 超出上限时先关闭最旧且非当前的标签。
 *
 * 状态置于 object 而非 ViewModel：WebView 为进程级资源，与 ViewModel（随 Activity 销毁）具有两套
 * 生命周期，最终会出现「标签仍在、页面已销毁」。此处状态全部由 WebView 自身的回调写入，UI 只读。
 *
 * **网页随会话切换**：每个会话拥有各自的一套标签。切走时将当前会话的标签「挂起」至 [BrowserState.parked]，
 * 切回后原样挂回 [BrowserState.tabs]——WebView 实例不销毁、不重载，因此登录态与滚动位置均保留；
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

    /** 全部标签，含其他会话挂起的那些：深浅色切换必须作用到每一份 WebView。 */
    fun allTabs(): List<BrowserTab> = tabs + parked.values.flatMap { it.tabs }

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
    /** 同时最多保留几个标签：所有 WebView 共用一个渲染器进程，但每个标签仍有独立 JS 上下文与合成层。 */
    private const val MAX_TABS = 8

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    /**
     * 去掉页面自带的状态栏留白，并把文档底色钉成 App 的底色。
     *
     * 底色这一步是为了视口变化的瞬间：页面的 html/body 默认是白的，视口高度一变，
     * 新露出的那条在页面重排完成前就是白的——看上去就是底部的白条。
     */
    private fun gapFixJs(): String {
        val bg = String.format(
            "#%06X",
            0xFFFFFF and (if (dark) Palette.DarkBackground else Palette.LightBackground).toArgb()
        )
        return "(function(){try{var r=document.documentElement;" +
            "var id='mgt-no-top-gap';var s=document.getElementById(id);" +
            "if(!s){s=document.createElement('style');s.id=id;(document.head||r).appendChild(s);}" +
            "s.textContent=':root{--dsha-safe-area-inset-top:0px !important}" +
            "html[data-mobile-nav=\"frame\"]{padding-top:0 !important}" +
            "html,body{background-color:$bg !important}';" +
            "}catch(e){}})()"
    }
    private var nextTabId = 1L

    /** cookie 刷盘是磁盘 I/O，不放在页面完成回调的主线程上。 */
    private val cookieFlush = Executors.newSingleThreadExecutor()

    /**
     * 当前是否深色：由界面在主题变化时告知，新建标签时也要用上。
     *
     * 网页的深浅色以 App 自己的主题为准，不读系统夜间模式——本应用的深浅色是独立设置项，
     * 可以与系统相反；若交给系统的 FORCE_DARK_AUTO，两者会不一致。
     */
    private var dark = false

    /**
     * 网页文字缩放百分比：只作用于文字，页面元素尺寸不变；默认 100。
     *
     * 由 [setTextZoom] 按应用基准字号写入，新建标签时直接沿用该值。
     */
    private var textZoomPercent = 100

    /**
     * 按应用基准字号设置网页文字缩放，作用到全部标签（含其他会话挂起的那一套）。
     *
     * 使用 WebView 级 `textZoom`，不注入字号 CSS：页面自身的 `px` 字号同样生效，
     * 且不会与页面样式叠加成双重缩放。
     */
    fun setTextZoom(percent: Int) {
        val value = percent.coerceIn(60, 200)
        if (textZoomPercent == value) return
        textZoomPercent = value
        BrowserState.allTabs().forEach { tab ->
            runCatching { tab.web.settings.textZoom = value }
        }
    }

    /** 主题切换：作用到每一份已存在的 WebView，含其他会话挂起的标签。 */
    fun setDark(value: Boolean) {
        if (dark == value) return
        dark = value
        BrowserState.allTabs().forEach {
            applyDark(it.web)
            // 文档底色也随主题走：换主题时页面不必重载
            applyTopGapFix(it.web)
        }
    }

    /**
     * 打开一个新地址 = 新建一个标签，并切到它。
     *
     * 为什么不复用已有标签：新链接需打开新模块并保留旧网页，复用会覆盖上一个页面。
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
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        cookieFlush.execute { runCatching { manager.flush() } }
    }

    /**
     * 让一份 WebView 的网页配色跟随 App 的深浅色。
     *
     * 底色一并设置：网页首帧绘制前的那一瞬会露出 WebView 自身底色，深色下是刺眼的白。
     * Android 10 起用 FORCE_DARK 强制网页转为深色；更早的系统没有这项能力，网页保持原色。
     */
    private fun applyDark(web: WebView) {
        runCatching {
            web.setBackgroundColor(
                (if (dark) Palette.DarkBackground else Palette.LightBackground).toArgb()
            )
            // 外壳同色：网页滚动/重载时露出的那一层也不能是系统默认的白
            (web.parent as? android.view.View)?.setBackgroundColor(
                (if (dark) Palette.DarkBackground else Palette.LightBackground).toArgb()
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                web.settings.forceDark =
                    if (dark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
        }.onFailure { FileLog.log("browser", "网页深浅色应用失败: " + it.message) }
    }

    /**
     * 去掉网页自带的「状态栏留白」。
     *
     * dsh-web-mobile 在 `html[data-mobile-nav="frame"]` 上用 `--dsha-safe-area-inset-top`
     * 预留了一块等于状态栏高度的顶部空白（1440x3200 设备上实测 36–38px）。
     * 本应用的浏览页按约定不再为系统状态栏预留区域，顶栏收起后整屏只剩网页，
     * 这块空白就成了页内多出来的一条。这里把该变量与 frame 的内边距一起归零。
     *
     * 只作用于这个选择器与这个变量名，对其他站点无影响；样式挂在页面 head 上，
     * 页面内重新渲染也会保留。
     */
    private fun applyTopGapFix(web: WebView) {
        runCatching { web.evaluateJavascript(gapFixJs(), null) }
    }

    /**
     * 页面适配探测：只读、不修改 DOM，限制在样式表级别，跨域样式表读不到就退回自适应。
     *
     * 返回 `1` 表示平铺型：单屏，且没有 `position:fixed; bottom:0` 的底部固定条；
     * 其余（长页面、带底部固定条、读取失败）都返回 `0`，走自适应。
     */
    private fun probeFitModeJs(): String = """
        (function(){try{
          var d=document,r=d.documentElement;
          var vh=window.innerHeight||r.clientHeight||0;
          var sh=Math.max(r.scrollHeight,d.body?d.body.scrollHeight:0);
          if(!vh||sh>vh+2)return '0';
          var sheets=d.styleSheets||[];
          for(var i=0;i<sheets.length;i++){
            var rules;try{rules=sheets[i].cssRules}catch(e){continue}
            if(!rules)continue;
            var txt='';for(var j=0;j<rules.length;j++){txt+=rules[j].cssText||''}
            if(/position\s*:\s*fixed/.test(txt)&&/bottom\s*:\s*0/.test(txt))return '0';
          }
          // 跨域样式表读不到时，用计算样式兜底：单屏页最多扫 400 个元素
          var all=d.querySelectorAll('*'),n=Math.min(all.length,400);
          for(var k=0;k<n;k++){
            var cs=getComputedStyle(all[k]);
            if(cs&&cs.position==='fixed'&&cs.bottom!=='auto'&&parseFloat(cs.bottom)<=1)return '0';
          }
          return '1';
        }catch(e){return '0'}})()
    """.trimIndent()

    private fun probeFitMode(web: WebView, tab: BrowserTab) {
        runCatching {
            web.evaluateJavascript(probeFitModeJs()) { raw ->
                val fixed = raw?.trim()?.trim('"') == "1"
                val next = if (fixed) PageFitMode.FIXED else PageFitMode.ADAPTIVE
                if (tab.fitMode != next) tab.fitMode = next
            }
        }.onFailure { FileLog.log("browser", "页面适配探测失败: " + it.message) }
    }

    /**
     * 把 WebView 落到「稳态」：顶栏完全展开或完全收起，且没有动画。
     *
     * - [PageFitMode.FIXED]：WebView 永远是整屏高度，只靠 `translationY` 让位；
     * - [PageFitMode.ADAPTIVE]：展开时 `topMargin = barPx`、高度 `parent.height - barPx`，收起时整屏。
     *
     * 所有高度变更都集中在这里。动画期间不要调用它，否则就是逐帧重排。
     */
    fun applyBarState(web: WebView, barPx: Int, shown: Boolean, mode: PageFitMode) {
        val parent = web.parent as? ViewGroup ?: return
        if (parent.height <= 0 || barPx <= 0) return
        val inset = if (mode == PageFitMode.ADAPTIVE && shown) barPx else 0
        val height = if (mode == PageFitMode.ADAPTIVE && shown) {
            (parent.height - barPx).coerceAtLeast(0)
        } else {
            parent.height
        }
        applyInset(web, inset, height)
        web.translationY = if (mode == PageFitMode.FIXED && shown) barPx.toFloat() else 0f
    }

    /**
     * 动画帧位移：只写 `translationY`，绝不改 LayoutParams。
     *
     * [lpShown] 是当前已经提交的布局状态（自适应型）：展开态从 `barPx` 往回收，收起态从 0 往下推。
     * 这样即使动画中途反向，位移也始终连续，不会回到 0 重来。
     */
    fun applyBarTranslation(
        web: WebView,
        barPx: Int,
        progress: Float,
        mode: PageFitMode,
        lpShown: Boolean
    ) {
        if (barPx <= 0) return
        web.translationY = when {
            mode == PageFitMode.FIXED -> barPx * progress
            lpShown -> -barPx * (1f - progress)
            else -> barPx * progress
        }
    }

    /** 动画端点提交一次高度变化；平铺型不需要，直接返回。 */
    fun commitBarInset(web: WebView, barPx: Int, shown: Boolean, mode: PageFitMode) {
        if (mode == PageFitMode.FIXED || barPx <= 0) return
        val parent = web.parent as? ViewGroup ?: return
        if (parent.height <= 0) return
        val inset = if (shown) barPx else 0
        val height = if (shown) (parent.height - barPx).coerceAtLeast(0) else parent.height
        applyInset(web, inset, height)
    }

    private fun applyInset(web: WebView, insetPx: Int, height: Int) {
        val lp = web.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin == insetPx && lp.height == height) return
        lp.topMargin = insetPx
        lp.height = height
        web.layoutParams = lp
    }

    /**
     * 交由 AndroidView 的壳。**先将自身从旧父容器上移除**再交出：
     * 离开浏览页时 Compose 只把壳从窗口移除，壳内 WebView 与壳的父子关系仍在，
     * 直接 addView 至新容器会抛出 "already has a parent"。
     */
    fun frameFor(tab: BrowserTab): FrameLayout {
        (tab.frame.parent as? ViewGroup)?.removeView(tab.frame)
        // 壳建立时 WebView 还没有父容器，applyDark 那时设不到它：在这里补上，
        // 否则网页尺寸变化的瞬间，壳露出的是系统默认白
        tab.frame.setBackgroundColor(
            (if (dark) Palette.DarkBackground else Palette.LightBackground).toArgb()
        )
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
                // 网页文字随应用基准字号缩放：textZoom 只放大文字，图片与布局尺寸不变
                textZoom = textZoomPercent
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                // 第三方 cookie 同样放行：OAuth 登录常涉及跨站跳转，拦截会停留在「授权成功但无法跳回」
                runCatching { setAcceptThirdPartyCookies(web, true) }
            }
            val frame = FrameLayout(context)
            frame.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
            // 新建标签同样按当前主题上色：主题切换发生在页面打开之前时，不能等到下一次切换
            applyDark(web)
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
                    // 新地址先按自适应走；完成后再由探测决定是否切到平铺，避免长页面在加载期间被当成单屏
                    tab.fitMode = PageFitMode.ADAPTIVE
                    sync(tab, view, pageUrl)
                }

                override fun onPageFinished(view: WebView, pageUrl: String?) {
                    tab.loading = false
                    sync(tab, view, pageUrl)
                    // 页面自带的顶部安全区留白：归零，顶栏收起后网页直接贴到屏幕最顶
                    applyTopGapFix(view)
                    // 单屏且无底部固定条的页面走平铺型；只写标签状态，不在这里改布局
                    probeFitMode(view, tab)
                    // 登录成功时 cookie 仅在内存中；此处刷盘一次，进程被终止后再次进入仍可复用
                    flushCookies()
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    FileLog.log("browser", "渲染进程终止: crash=" + (detail?.didCrash() ?: false))
                    // 失效的 WebView 不可复用：主线程上移除并销毁；返回 true 阻止系统连带杀掉应用
                    Handler(Looper.getMainLooper()).post { close(tab.id) }
                    return true
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
