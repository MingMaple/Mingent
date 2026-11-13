package com.zmf.agent.feature.chat

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zmf.agent.uikit.common.TopIconButton
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.common.stableStatusBarPadding
import com.zmf.agent.uikit.theme.UiColors
import com.zmf.agent.uikit.theme.isDarkSurface
import kotlin.math.abs

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

    // 最后一个标签被关闭：该页已无内容，直接返回主页（顶栏网页图标随之消失）
    LaunchedEffect(tabs.size) {
        if (tabs.isEmpty()) onBack()
    }

    // 顶栏随滑动收起／展开（与 via 同一套判定）：一次手势结束时看竖直位移，
    // 超过 36dp 且以竖直为主才切换——手指上移收起，下移展开。
    // 不读 WebView 的 scrollY（页面滚动由渲染进程完成），也不在滑动过程中连续跟手：
    // 手势只读取位移，页面滚动照旧交给 WebView。
    var headerVisible by remember { mutableStateOf(true) }
    // 切换标签时回到展开：新标签的滚动位置与上一份无关
    LaunchedEffect(active?.id) { headerVisible = true }
    DisposableEffect(active?.id) {
        val web = active?.web ?: return@DisposableEffect onDispose { }
        installHeaderSwipe(web) { hide -> headerVisible = !hide }
        onDispose { web.setOnTouchListener(null) }
    }

    // 网页配色跟随 App 的深浅色：页内切换主题时即时生效，不必重开网页
    val dark = isDarkSurface()
    LaunchedEffect(dark) { BrowserHolder.setDark(dark) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            // 覆盖在聊天页之上：点击不可穿透；横滑由本页消费，不交给抽屉
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .stableStatusBarPadding()
        ) {
            val onSurface = MaterialTheme.colorScheme.onSurface
            val shareable = active?.url?.isNotBlank() == true
            // 顶栏一行：返回、网页标签条、刷新、转发。选中标签即当前页面标题，不再单起标题行。
            // 整行可收起：收起时高度归零，网页因此多出一行的阅读空间
            HideableHeader(visible = headerVisible) {
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

            if (active == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "没有打开的网页", fontSize = 14.sp, color = UiColors.subText)
                }
            } else {
                // key(标签 id)：切换标签时整块替换 AndroidView，新标签的壳挂入，
                // 旧标签的 WebView 保留在其壳中（不销毁、不重载）
                key(active.id) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        factory = { BrowserHolder.frameFor(active) },
                        // 空实现是有意为之：页面离开窗口时保留 WebView 实例（返回不关闭网页）
                        onRelease = { }
                    )
                }
            }
        }
    }
}

/**
 * 顶栏收起／展开的手势判定，与 via 相同。
 *
 * 只在一次手势结束时按竖直总位移判定：超过 36dp 且竖直分量大于水平，方视为有效滑动；
 * 上移收起、下移展开。滑到一半不改变状态，因此不会在慢速阅读时来回跳。
 * 监听器返回 false，WebView 的点击、滚动与缩放不受影响。
 *
 * @param onSwipe true 表示应收起顶栏
 */
internal fun installHeaderSwipe(view: View, onSwipe: (hide: Boolean) -> Unit) {
    val threshold = 36f * view.resources.displayMetrics.density
    var downX = 0f
    var downY = 0f
    var tracking = false
    view.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                tracking = true
            }
            // 多指（缩放）不当作收起手势：两指的竖直位移没有意义
            MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            MotionEvent.ACTION_UP -> if (tracking) {
                tracking = false
                val dy = event.rawY - downY
                val dx = event.rawX - downX
                if (abs(dy) > threshold && abs(dy) > abs(dx)) onSwipe(dy < 0f)
            }
            MotionEvent.ACTION_CANCEL -> tracking = false
        }
        false
    }
}
