package com.zmf.agent.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.theme.Palette

/**
 * 全屏浏览页：真浏览器、多标签，逻辑与计划页对齐。
 *
 * 布局是两层：
 *   顶栏   ← 回主页（**不关网页**）｜当前标签的标题 + 域名｜‹ 网页内后退｜⟳ 刷新
 *   标签栏 一个地址一枚标签，点标签切过去（切走的页面不重载、登录态不丢），点 × 才真的关掉。
 *          标签多了左右滑，和计划页那条计划条同一套手势（[blockHorizontalAtEdge]）。
 *
 * 为什么 AndroidView 的 onRelease 是空的：Compose 把这页从窗口摘下来时，默认行为会把
 * WebView 一起带走；而口径是「返回不关闭网页」，所以这里禁止销毁，只让壳和窗口脱钩。
 * 真正销毁只发生在用户点 × 关闭标签时。
 */
@Composable
fun BrowserPage(onBack: () -> Unit) {
    val tabs = BrowserState.tabs
    val active = BrowserState.active

    // 最后一个标签被关掉：这一页没有内容可留，直接回主页（顶栏的网页图标也随之消失）
    LaunchedEffect(tabs.size) {
        if (tabs.isEmpty()) onBack()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            // 盖在聊天页之上：点击不能穿透（拖动仍然放行，页面里要能横滑）
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            val onSurface = MaterialTheme.colorScheme.onSurface
            val headLine = active?.label.orEmpty()
            val host = browserHostOf(active?.url)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "←",
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = headLine,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 第二行给域名：标题和地址分开放，才知道自己在哪个站上
                    if (host.isNotBlank() && host != headLine) {
                        Text(
                            text = host,
                            fontSize = 11.sp,
                            color = Palette.Neutral,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                // 网页内部后退：退不了就画淡（不藏起来，否则顶栏左右会跳）
                val canGoBack = active?.canGoBack == true
                Box(
                    modifier = Modifier
                        .clickable(enabled = canGoBack) { BrowserHolder.goBack() }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "‹",
                        fontSize = 22.sp,
                        color = if (canGoBack) onSurface else onSurface.copy(alpha = 0.28f)
                    )
                }
                Box(
                    modifier = Modifier
                        .clickable { BrowserHolder.reload() }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(text = "⟳", fontSize = 17.sp, color = onSurface)
                }
            }

            // 标签栏常驻：只有一个页面时也保留这一行（条目只有一个），
            // 这样「页面设计」在多页与单页时是同一套，不会因为少一个标签整块布局往上跳。
            // 样式与行为都来自 TabStrip：和计划页的计划条是同一份实现（含选中项自动入视野）。
            if (tabs.isNotEmpty()) {
                TabStrip(
                    items = tabs.map { tab ->
                        TabStripItem(key = tab.id.toString(), label = tab.label.ifBlank { "新页面" })
                    },
                    selectedKey = active?.id?.toString(),
                    onSelect = { key -> key.toLongOrNull()?.let { BrowserState.activate(it) } },
                    onClose = { key -> key.toLongOrNull()?.let { BrowserHolder.close(it) } }
                )
                Spacer(Modifier.height(8.dp))
            }

            // 进度条只有真在加载时才出现（0–99）：完成后消失，不占版面
            val loading = active?.loading == true
            val progress = active?.progress ?: 0
            if (loading && progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = Palette.AccentBlue,
                    trackColor = onSurface.copy(alpha = 0.08f)
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }

            if (active == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "没有打开的网页", fontSize = 14.sp, color = Palette.Neutral)
                }
            } else {
                // key(标签 id)：切换标签时整块换掉 AndroidView，新标签的壳挂进来、
                // 旧标签的 WebView 留在自己的壳里（不销毁、不重载）
                key(active.id) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        factory = { BrowserHolder.frameFor(active) },
                        // 空实现是故意的：页面离开窗口时保留 WebView 实例（返回不关闭网页）
                        onRelease = { }
                    )
                }
            }
        }
    }
}


