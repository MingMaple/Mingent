package com.zmf.agent.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.ui.theme.Palette

/**
 * 一条可横滑的条目栏：**计划页的计划条和浏览页的网页条共用这一份**（2026-10-25 用户：两个页面的
 * 条目样式不一样，统一一下——那边选中是蓝色、这边是灰绿实心，看着像两个 App）。
 *
 * 行为口径：
 *  - 条目并排，超出屏幕就左右滑；滑到两端继续推时余量就地吃掉，不会把抽屉拉出来；
 *  - **选中项自动滚进视野**（新计划生成、切到新标签、进入页面时落在某一条上）；
 *  - 但**用户手滑后点的那一条不矫正**——他可能就是想看半截，再自动滚回去等于抢他的操作。
 */
data class TabStripItem(
    val key: String,
    val label: String,
    /** 已消费的条目画淡（计划里是「已执行 / 已作废」那种）。 */
    val muted: Boolean = false
)

@Composable
fun TabStrip(
    items: List<TabStripItem>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp
) {
    val scroll = rememberScrollState()
    // 每枚条目在滚动内容里的位置：自动滚动靠它判断「有没有露在外面」
    val bounds = remember(items) { mutableMapOf<String, TabBounds>() }
    // 用户自己点条目时把他手滑出来的位置保住，不要被自动滚动又拽回去
    var autoScrollOnSelect by remember { mutableStateOf(true) }
    LaunchedEffect(selectedKey, items) {
        if (!autoScrollOnSelect) {
            // 这次是用户点的：只把开关复位，一颗像素都不动
            autoScrollOnSelect = true
            return@LaunchedEffect
        }
        val key = selectedKey ?: return@LaunchedEffect
        // 首帧时条目还没量出位置、视口宽度也是 0：等几帧再来，量到才滚
        repeat(8) {
            val box = bounds[key]
            val viewport = scroll.viewportSize
            if (box != null && viewport > 0) {
                val left = scroll.value.toFloat()
                val right = left + viewport
                val target = when {
                    box.x < left -> box.x
                    box.x + box.width > right -> box.x + box.width - viewport
                    else -> null
                }
                if (target != null) scroll.animateScrollTo(target.toInt().coerceAtLeast(0))
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 拦截器在 horizontalScroll 之前（= 外层）：滑到头之后余量就地吃掉
            .blockHorizontalAtEdge(scroll)
            .horizontalScroll(scroll)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            TabChip(
                label = item.label,
                selected = item.key == selectedKey,
                muted = item.muted,
                modifier = Modifier.onGloballyPositioned { coords ->
                    bounds[item.key] = TabBounds(
                        x = coords.positionInParent().x,
                        width = coords.size.width.toFloat()
                    )
                },
                onClick = {
                    // 只有真的换了一条才算「用户手动选的」：点已选中那条不该改变滚动行为
                    if (item.key != selectedKey) autoScrollOnSelect = false
                    onSelect(item.key)
                },
                onClose = { onClose(item.key) }
            )
        }
    }
}

/**
 * 一枚条目：选中吃中性实心 + 深色字，未选中吃 8% 中性底。
 *
 * × 单独一个 clickable：点条目是切换、点 × 是删掉/关掉，两个动作不能共用一次点击；
 * 内层 clickable 会先吃掉事件，所以点 × 不会顺带把选中项切过去。
 */
@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val content = if (selected) {
        Palette.DarkBackground
    } else {
        onSurface.copy(alpha = if (muted) 0.42f else 0.75f)
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Palette.AccentGreen else onSurface.copy(alpha = 0.08f),
        modifier = modifier
            .widthIn(max = 190.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.widthIn(max = 150.dp),
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = content
            )
            Text(
                text = "×",
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clickable(onClick = onClose),
                fontSize = 13.sp,
                color = content.copy(alpha = 0.7f)
            )
        }
    }
}

/** 一枚条目在横滑内容里的位置（像素）。 */
private class TabBounds(val x: Float, val width: Float)
