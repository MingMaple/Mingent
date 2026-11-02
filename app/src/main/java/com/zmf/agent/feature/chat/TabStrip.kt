package com.zmf.agent.feature.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors
import com.zmf.agent.uikit.theme.isDarkSurface
import com.zmf.agent.uikit.gesture.blockHorizontalAtEdge

/** 条目栏里的一条：[key] 是稳定身份（选中 / 关闭都按它找），[label] 是显示名，[muted] 表示已消费。 */
data class TabStripItem(
    val key: String,
    val label: String,
    /** 已消费的条目画淡（计划里是「已执行 / 已作废」那种）。 */
    val muted: Boolean = false
)

/**
 * 一条可横滑的条目栏：**计划页的计划条和浏览页的网页条共用这一份**（两页原先各画一套，
 * 一边选中是蓝色、一边是灰绿实心，看着像两个 App，所以统一成这一份）。
 *
 * 行为口径：
 *  - 条目并排，超出屏幕就左右滑；滑到两端继续推时余量就地吃掉，不会把抽屉拉出来；
 *  - **选中项自动滚进视野**（新计划生成、切到新标签、进入页面时落在某一条上）；
 *  - 但**用户手滑后点的那一条不矫正**：他可能就是想看半截，再自动滚回去等于抢他的操作。
 *
 * @param selectedKey 当前选中条目的 [TabStripItem.key]；null 表示没有选中项
 * @param onClose 点条目上的「×」时调用，只关闭不切换
 * @param horizontalPadding 条带左右留白，默认 16dp 与页面内容边对齐
 */
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
 * 一枚条目：选中「浮一层」+ 强调字，未选中吃 6% 中性底。
 *
 * 选中的底为什么浅色是白卡、深色是 16% 提亮：浅色下页面底 `#F1F4F7`，白卡是唯一比它**更亮**的一层，
 * 圆角边一眼能看出来；深色下白卡那一档（`#23262A`）和未选中的 8% 蒙版几乎同值，必须再提一档才分得开。
 * 曾经直接复用列表行的选中色（浅 `#F2F3F4`），它与页面底只差 1 个灰阶，看着像「没有背景」。
 *
 * × 单独一个热区：点条目是切换、点 × 是删掉/关掉，两个动作不能共用一次点击；
 * 内层点击会先吃掉事件，所以点 × 不会顺带把选中项切过去。
 *
 * 两处点击都走 [saltRowClickable]：波纹按各自的圆角裁剪，不会在四角露出方块。
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
    val dark = isDarkSurface()
    val shape = RoundedCornerShape(14.dp)
    val content = if (selected) {
        UiColors.accentFg
    } else {
        onSurface.copy(alpha = if (muted) 0.42f else 0.7f)
    }
    Surface(
        shape = shape,
        color = when {
            selected -> if (dark) onSurface.copy(alpha = 0.16f) else Palette.LightSurfaceHigh
            else -> onSurface.copy(alpha = if (dark) 0.08f else 0.06f)
        },
        modifier = modifier
            .widthIn(max = 190.dp)
            .saltRowClickable(shape, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
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
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .saltRowClickable(RoundedCornerShape(13.dp), onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "×",
                    fontSize = 13.sp,
                    color = content.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/** 一枚条目在横滑内容里的位置（像素）。 */
private class TabBounds(val x: Float, val width: Float)
