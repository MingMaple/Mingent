package com.zmf.agent.feature.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.zmf.agent.uikit.common.TopIconButton
import kotlin.math.roundToInt

/**
 * 独立页的顶栏：返回、标签条与页级动作共用一行。
 *
 * 选中条目即当前页面的名字，因此不再另起一行写静态标题——「浏览」「预览」「计划」三页
 * 原先都是「标题行 + 标签条」两行，标签条并入后顶栏稳定在 44–48dp（`_Docs/UI与配色规范.md` §4.6）。
 *
 * @param items 标签条目；选中项的文本充当页面标题，过长时省略
 * @param onBack 返回主页；不关闭页面内容
 * @param actions 页级动作（刷新、导出等），排在标签条右侧、固定不参与横滑
 */
@Composable
internal fun TabHeaderRow(
    items: List<TabStripItem>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TopIconButton(glyph = "←", onClick = onBack)
        // 标签条按当前枚数均分返回键与动作区之间的宽度：两枚时各自收窄，尽量一屏看全；
        // 单枚上限 190dp，枚数再多则落到 96dp 下限后转为横向滑动。
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val count = items.size.coerceAtLeast(1)
            val even = (maxWidth - 12.dp - 8.dp * (count - 1)) / count
            TabStrip(
                items = items,
                selectedKey = selectedKey,
                onSelect = onSelect,
                onClose = onClose,
                modifier = Modifier.fillMaxWidth(),
                horizontalPadding = 6.dp,
                maxItemWidth = even.coerceIn(96.dp, 190.dp)
            )
        }
        actions()
    }
}

/**
 * 可随滑动收起的顶栏容器，两种放置方式：
 *
 * - [reserveSpace] = true（预览页）：顶栏占正文空间，收起后正文实打实多出一行。正文是预加载好的
 *   Compose 内容，高度变化不会让内容跳位。
 * - [reserveSpace] = false（浏览页）：顶栏尺寸不变、整块向上平移出屏，网页始终按原尺寸排版。
 *   网页以视口高度（100vh）布局，只要视图高度变一点就会重排：内容下移、顶部露出空白带、
 *   底部输入框被顶出屏幕——所以这里绝不能占位。代价是展开时压住网页顶部一条，与 via 一致。
 */
@Composable
internal fun HideableHeader(
    visible: Boolean,
    modifier: Modifier = Modifier,
    reserveSpace: Boolean = true,
    /** 由调用方驱动的展开进度（1 展开 / 0 收起）；&lt; 0 时用内部动画。 */
    progress: Float = -1f,
    /** 顶栏实际高度（px）：浏览页据此在网页上方留出等高的空位（占位立即变化、动画只走视觉）。 */
    onHeight: (Int) -> Unit = {},
    content: @Composable () -> Unit
) {
    val inner by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "header-hide"
    )
    val p = if (progress >= 0f) progress else inner
    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        }
    ) { measurables, constraints ->
        val placeables = measurables.map {
            it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        if (height > 0) onHeight(height)
        val width = placeables.maxOfOrNull { it.width } ?: constraints.maxWidth
        if (reserveSpace) {
            val shown = (height * p).roundToInt().coerceIn(0, height)
            layout(width, shown) { placeables.forEach { it.place(0, shown - height) } }
        } else {
            val offset = ((p - 1f) * height).roundToInt()
            // 完全收起时把自身体积压到 0：否则那块看不见的区域仍会拦住网页顶部的点击
            val own = if (p > 0f) height else 0
            layout(width, own) { placeables.forEach { it.place(0, offset) } }
        }
    }
}
