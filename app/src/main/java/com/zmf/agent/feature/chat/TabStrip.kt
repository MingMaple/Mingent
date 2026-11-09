package com.zmf.agent.feature.chat

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
    /** 已消费的条目淡化显示（计划页中为「已执行 / 已作废」）。 */
    val muted: Boolean = false
)

/**
 * 一条可横滑的条目栏：**计划页的计划条与浏览页的网页条共用此实现**（两页此前各绘一套，
 * 选中色一为蓝色、一为灰绿实心，风格不一致，故统一）。
 *
 * 行为口径：
 *  - 条目并排，超出屏幕时左右滑动；滑至两端继续推动时余量就地消耗，不会触发抽屉；
 *  - **选中项自动滚进视野**（新计划生成、切到新标签、进入页面时落在某一条上）；
 *  - 但**用户手动滑动后点选的条目不做矫正**：用户可能需要查看局部，自动滚回会干扰其操作。
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
    // 每枚条目在滚动内容中的位置：自动滚动据此判断是否可见
    val bounds = remember(items) { mutableMapOf<String, TabBounds>() }
    // 用户手动点选条目时保留其滑动位置，不被自动滚动拉回
    var autoScrollOnSelect by remember { mutableStateOf(true) }
    LaunchedEffect(selectedKey, items) {
        if (!autoScrollOnSelect) {
            // 本次为用户点选：仅复位开关，不改变位置
            autoScrollOnSelect = true
            return@LaunchedEffect
        }
        val key = selectedKey ?: return@LaunchedEffect
        // 首帧时条目尚未测量位置、视口宽度为 0：等待数帧后测量并滚动
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
            // 拦截器位于 horizontalScroll 之前（即外层）：滑至尽头后余量就地消耗
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
                    // 仅实际切换条目才算「用户手动选择」：点选已选中条目不应改变滚动行为
                    if (item.key != selectedKey) autoScrollOnSelect = false
                    onSelect(item.key)
                },
                onClose = { onClose(item.key) }
            )
        }
    }
}

/**
 * 一枚条目：选中时浮起一层并强调文字，未选中时使用 6% 中性底色。
 *
 * 选中底色：浅色为白卡、深色为 16% 提亮。浅色下页面底为 `#F1F4F7`，白卡是唯一比其**更亮**的一层，
 * 圆角边界可辨；深色下白卡档（`#23262A`）与未选中的 8% 蒙版几乎同值，需再提一档方可区分。
 * 此前直接复用列表行的选中色（浅 `#F2F3F4`），与页面底仅差 1 个灰阶，看似「无背景」。
 *
 * × 单独一个热区：点击条目为切换，点击 × 为删除或关闭，两个动作不可共用一次点击；
 * 内层点击优先消费事件，点击 × 不会顺带切换选中项。
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

/** 条目在横滑内容里的位置（像素）。 */
private class TabBounds(val x: Float, val width: Float)

/**
 * 正文区的过渡：计划页与预览页共用同一时长与做法。
 *
 * 切条目时上方结构不变，只有正文换内容；硬切会让整页像被刷新，故交叉淡化。
 * 两页正文都铺满剩余高度，尺寸不变，因此不需要额外的尺寸动画。
 */
internal fun tabContentTransform(): ContentTransform =
    fadeIn(tween(180)) togetherWith fadeOut(tween(180))
