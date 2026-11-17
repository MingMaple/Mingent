package com.zmf.agent.feature.chat

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zmf.agent.uikit.common.AgentOpBanner
import com.zmf.agent.uikit.common.TopIconButton
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.common.noticeName
import com.zmf.agent.uikit.theme.UiColors
import com.zmf.agent.uikit.theme.isDarkSurface
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 全屏浏览页：真浏览器、多标签，逻辑与计划页对齐。
 *
 * 布局是一行顶栏加正文：
 *   ← 返回来路（**不关网页**；从预览页进来时回预览页，从主页进来时回主页）｜一个地址一枚标签，点击标签切换（已切走的页面不重载、登录态不丢失），
 *   点击 × 才真正关闭｜⟳ 刷新｜↗ 用系统默认浏览器打开当前页。
 *   选中标签的文本即当前页面标题，标签多时左右滑动，与计划页的计划条共用同一套手势（[blockHorizontalAtEdge]）。
 *
 * 为什么 AndroidView 的 onRelease 为空：Compose 将该页从窗口摘下时，默认行为会
 * 一并销毁 WebView；而约定是「返回不关闭网页」，因此此处禁止销毁，仅使壳与窗口脱钩。
 * 真正销毁仅在用户点击 × 关闭标签时发生。
 */
@Composable
fun BrowserPage(onBack: () -> Unit) {
    val tabs = BrowserState.tabs
    val active = BrowserState.active
    val context = LocalContext.current

    // 网页控制端口随浏览页装配：离开本页即断开，工具调用改为回执「请用户先打开浏览页」
    DisposableEffect(Unit) {
        BrowserAutomation.attach(BrowserHolder)
        onDispose { BrowserAutomation.detach() }
    }

    // 最后一个标签被关闭：该页已无内容，直接返回主页（顶栏网页图标随之消失）
    LaunchedEffect(tabs.size) {
        if (tabs.isEmpty()) onBack()
    }

    // 顶栏随滑动收起／展开（与 via 同一套判定）：一次手势结束时看竖直位移，
    // 超过 36dp 且以竖直为主才切换——手指上移收起，下移展开。
    // 不读 WebView 的 scrollY（页面滚动由渲染进程完成），也不在滑动过程中连续跟手：
    // 手势只读取位移，页面滚动照旧交给 WebView。
    var headerVisible by remember(active?.id) { mutableStateOf(true) }

    // 只读只作用于被助手操作的标签：其余标签的点击、跳转与聚焦不受影响
    val readOnlyIds = BrowserHolder.agentReadOnlyIds
    val readOnly = active?.id?.let { it in readOnlyIds } == true

    DisposableEffect(active?.id, readOnly) {
        val web = active?.web ?: return@DisposableEffect onDispose { }
        installTouchGuard(web, readOnly) { hide -> headerVisible = !hide }
        onDispose { web.setOnTouchListener(null) }
    }

    // 进入只读：先让页面失焦并收起输入法，避免残留光标、选区与键盘
    LaunchedEffect(active?.id, readOnly) {
        val web = active?.web ?: return@LaunchedEffect
        if (!readOnly) return@LaunchedEffect
        web.evaluateJavascript(BLUR_SCRIPT, null)
        web.clearFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(web.windowToken, 0)
    }

    // 网页配色跟随 App 的深浅色：页内切换主题时即时生效，不必重开网页
    val dark = isDarkSurface()
    LaunchedEffect(dark) { BrowserHolder.setDark(dark) }

    // 网页文字随应用基准字号缩放：textZoom 只改文字，图片与元素尺寸不变。
    // snapshotFlow 观察当前倍率，conflate 合并连点期间的中间值，只让最后一次生效。
    val latestFontScale by rememberUpdatedState(LocalDensity.current.fontScale)
    LaunchedEffect(Unit) {
        snapshotFlow { latestFontScale }
            .conflate()
            .collectLatest { BrowserHolder.setTextZoom((it * 100f).roundToInt()) }
    }

    // 底部系统导航条：跟随主题底色。深色下不加这一句，重载时会露出一条白色
    val view = LocalView.current
    val bgArgb = MaterialTheme.colorScheme.background.toArgb()
    LaunchedEffect(view, bgArgb) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        @Suppress("DEPRECATION")
        window.navigationBarColor = bgArgb
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        // 没有这个标记，setNavigationBarColor 对系统条不生效
        @Suppress("DEPRECATION")
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.decorView.setBackgroundColor(bgArgb)
    }

    // 顶栏高度：网页据此让位。高度变化只做无动画落位，绝不重启收放动画。
    var headerPx by remember { mutableIntStateOf(0) }
    // 唯一的收放进度：1 展开 / 0 收起。顶栏与网页都由它驱动，不再有第二条动画时钟。
    // 按标签 id 重建：切标签直接回到展开态，不播动画，也不需要额外重置。
    val barP = remember(active?.id) { Animatable(1f) }
    var lpShown by remember(active?.id) { mutableStateOf(true) }
    var animatingBar by remember(active?.id) { mutableStateOf(false) }
    val latestHeaderPx by rememberUpdatedState(headerPx)
    val activeWeb = active?.web
    val fitMode = active?.fitMode ?: PageFitMode.ADAPTIVE

    // 稳态落位：首次测量、headerPx 变化、fitMode 变化时执行；不动画、不提交多余高度
    LaunchedEffect(activeWeb, headerPx, fitMode) {
        val web = activeWeb ?: return@LaunchedEffect
        if (headerPx <= 0 || animatingBar) return@LaunchedEffect
        val target = if (headerVisible) 1f else 0f
        if (barP.value != target) barP.snapTo(target)
        lpShown = headerVisible
        BrowserHolder.applyBarState(web, headerPx, headerVisible, fitMode)
    }

    // 收放动画：headerPx 不在 key 中，所以高度变化不会打断；切标签时 barP 重建为 1，直接落位
    LaunchedEffect(activeWeb, headerVisible, fitMode) {
        val web = activeWeb ?: return@LaunchedEffect
        if (headerPx <= 0) return@LaunchedEffect
        val target = if (headerVisible) 1f else 0f
        if (barP.value == target) {
            lpShown = headerVisible
            BrowserHolder.applyBarState(web, headerPx, headerVisible, fitMode)
            return@LaunchedEffect
        }
        val lpAtStart = lpShown
        animatingBar = true
        try {
            barP.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
            ) {
                BrowserHolder.applyBarTranslation(web, latestHeaderPx, value, fitMode, lpAtStart)
            }
        } finally {
            animatingBar = false
        }
        if (barP.value != target) return@LaunchedEffect
        // 自适应型只在端点提交一次高度；平铺型永远不提交
        if (fitMode == PageFitMode.ADAPTIVE) {
            if (!headerVisible && lpAtStart) {
                BrowserHolder.commitBarInset(web, latestHeaderPx, shown = false, mode = fitMode)
                lpShown = false
            } else if (headerVisible && !lpAtStart) {
                BrowserHolder.commitBarInset(web, latestHeaderPx, shown = true, mode = fitMode)
                lpShown = true
            }
        }
        web.translationY = if (fitMode == PageFitMode.FIXED) {
            latestHeaderPx * (if (headerVisible) 1f else 0f)
        } else {
            0f
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            // 覆盖在聊天页之上：点击不可穿透；横滑由本页消费，不交给抽屉
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (active == null) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "没有打开的网页", fontSize = 14.sp, color = UiColors.subText)
                    }
                } else {
                    // key(标签 id)：切换标签时整块替换 AndroidView，新标签的壳挂入，
                    // 旧标签的 WebView 保留在其壳中（不销毁、不重载）
                    key(active.id) {
                        AndroidView(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                // 网页底色：深色主题下重载、首帧未绘制时不再闪出白条
                                .background(MaterialTheme.colorScheme.background),
                            factory = { BrowserHolder.frameFor(active) },
                            // 空实现是有意为之：页面离开窗口时保留 WebView 实例（返回不关闭网页）
                            onRelease = { }
                        )
                    }
                }
            }

            val onSurface = MaterialTheme.colorScheme.onSurface
            val shareable = active?.url?.isNotBlank() == true
            // 顶栏一行：返回、网页标签条、刷新、转发。选中标签即当前页面标题，不再单起标题行。
            // 视觉上悬浮：收起时向上滑出屏幕、展开时滑回。占位由网页一侧的位移与高度提交完成
            // （见上方两个 LaunchedEffect），顶栏自身不占正文空间。
            HideableHeader(
                visible = headerVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                reserveSpace = false,
                progress = barP.value,
                onHeight = { headerPx = it }
            ) {
                Column {
                    TabHeaderRow(
                        items = tabs.map { tab ->
                            TabStripItem(key = tab.id.toString(), label = tab.label.ifBlank { "新页面" })
                        },
                        selectedKey = active?.id?.toString(),
                        onSelect = { key -> key.toLongOrNull()?.let { BrowserState.activate(it) } },
                        onClose = { key -> key.toLongOrNull()?.let { BrowserHolder.close(it) } },
                        onBack = onBack
                    ) {
                        // 刷新：字形「⟳」的墨迹中心天然低于「↗」，此处仅上移字形，热区与圆角不变
                        TopIconButton(
                            glyph = "⟳",
                            enabled = active != null,
                            fontSize = 17.sp,
                            glyphOffsetY = (-2).dp,
                            onClick = { BrowserHolder.reload() }
                        )
                        // 转发：将当前页交由系统默认浏览器打开，内置页的登录态与历史保持不变
                        TopIconButton(
                            glyph = "↗",
                            enabled = shareable,
                            fontSize = 17.sp,
                            onClick = { BrowserHolder.openExternally(context) }
                        )
                    }

                    // 进度条仅在确实加载时显示（0–99）：完成后消失，不占版面
                    val loading = active?.loading == true
                    val progress = active?.progress ?: 0
                    if (loading && progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = UiColors.accentFg,
                            trackColor = onSurface.copy(alpha = 0.08f)
                        )
                    } else {
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }

            // 助手操作提示：仅在租赁标签仍然存在时显示；被操作的不是当前标签时给出名称（截 6 字）。
            // 终止只结束本次页面操作：控制端口据此解除租赁并丢弃同批排队命令。
            val operatingId = BrowserAutomation.leasedTabId
            val operating = operatingId?.let { id ->
                BrowserState.allTabs().firstOrNull { it.id.toString() == id }
            }
            if (operating != null) {
                val isCurrent = operating.id == active?.id
                val text = if (isCurrent) {
                    "助手正在操作此页面，可上下滚动"
                } else {
                    "助手正在操作「" + noticeName(operating.label, 6) + "」，可上下滚动"
                }
                AgentOpBanner(
                    text = text,
                    onAbort = {
                        // 终止端口带会话 id：按标签反查持有它的会话，查不到落在当前会话上
                        val holder = operatingId
                            ?.toLongOrNull()
                            ?.let { BrowserState.sessionOf(it) }
                            ?: BrowserState.ownerSessionId
                        BrowserAutomation.abort(holder, operatingId, "用户手动终止")
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        // 让过顶栏一行（网页标签条），与提示条同一口径
                        .padding(top = 64.dp)
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }
}

/**
 * Compose 拿到的 Context 常被包了一层主题或导航壳，逐层解开才拿得到宿主 Activity。
 * 底部系统导航条的颜色只能从 Window 上设，拿不到 Activity 就整段失效。
 */
private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 顶栏收起／展开的手势判定，与 via 相同。
 *
 * 只在一次手势结束时按竖直总位移判定：超过 36dp 且竖直分量大于水平，方视为有效滑动；
 * 上移收起、下移展开。滑到一半不改变状态，因此不会在慢速阅读时来回跳。
 *
 * @param onSwipe true 表示应收起顶栏
 */
internal fun installHeaderSwipe(view: View, onSwipe: (hide: Boolean) -> Unit) {
    installTouchGuard(view, readOnly = false, onSwipe = onSwipe)
}

/**
 * 浏览页的触摸守卫：无操作时只做顶栏收放判定；助手操作期间同时使页面只读。
 *
 * 只读的边界是「不得触发点击、链接跳转与表单聚焦」，滚动与缩放必须保留：
 * - `ACTION_DOWN` 放行并记录起点，只读时按系统长按阈值投递守卫，避免长按选中文字或弹出菜单；
 * - `ACTION_MOVE` 超过触摸容差即视为滚动或缩放，撤销守卫并全部放行；
 * - `ACTION_UP` 未滚动时先向 WebView 注入一条 `ACTION_CANCEL` 再消费原事件，手势由此作废；
 * - 多指按下撤销守卫并全部放行，双指缩放不受影响。
 *
 * 监听器在放行时返回 false，WebView 的滚动、缩放与顶栏手势均按既有行为工作。
 *
 * @param readOnly 当前标签是否处于助手只读状态
 * @param onSwipe true 表示应收起顶栏
 */
internal fun installTouchGuard(
    view: View,
    readOnly: Boolean,
    onSwipe: (hide: Boolean) -> Unit
) {
    val threshold = 36f * view.resources.displayMetrics.density
    val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    val handler = Handler(Looper.getMainLooper())

    var downX = 0f
    var downY = 0f
    var tracking = false
    var scrolling = false
    var guard: Runnable? = null

    fun dropGuard() {
        guard?.let { handler.removeCallbacks(it) }
        guard = null
    }

    /** 向网页注入取消：网页内部手势随之作废，不产生点击与聚焦。 */
    fun injectCancel(source: MotionEvent) {
        val cancel = MotionEvent.obtain(source)
        cancel.action = MotionEvent.ACTION_CANCEL
        runCatching { view.onTouchEvent(cancel) }
        cancel.recycle()
    }

    view.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                tracking = true
                scrolling = false
                dropGuard()
                if (readOnly) {
                    val pending = Runnable { injectCancel(event) }
                    guard = pending
                    handler.postDelayed(pending, longPressTimeout)
                }
            }
            // 多指（缩放）不当作收起手势：两指的竖直位移没有意义
            MotionEvent.ACTION_POINTER_DOWN -> {
                dropGuard()
                tracking = false
                scrolling = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (readOnly && tracking && !scrolling) {
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) {
                        scrolling = true
                        dropGuard()
                    }
                }
                if (event.pointerCount > 1) {
                    scrolling = true
                    dropGuard()
                }
            }
            MotionEvent.ACTION_UP -> {
                dropGuard()
                if (tracking) {
                    val dy = event.rawY - downY
                    val dx = event.rawX - downX
                    if (abs(dy) > threshold && abs(dy) > abs(dx)) onSwipe(dy < 0f)
                }
                val consume = readOnly && !scrolling
                tracking = false
                if (consume) {
                    injectCancel(event)
                    return@setOnTouchListener true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dropGuard()
                tracking = false
            }
        }
        false
    }
}

/** 进入只读时的页面清理：失焦并清空选区，不修改页面内容。 */
private const val BLUR_SCRIPT =
    "(function(){try{var a=document.activeElement;if(a&&a.blur){a.blur()}" +
        "var s=window.getSelection&&window.getSelection();if(s&&s.removeAllRanges){s.removeAllRanges()}}catch(e){}})()"
