package com.zmf.agent.feature.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
            .heightIn(min = 44.dp)
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
 * 可随滑动收起的顶栏容器。
 *
 * 收起时高度真正归零：正文因此多出这一行的高度，阅读空间是实打实变大的。
 * 内容按自身高度测量（不设最大高度），收起过程中整体上移并由本层裁掉——视觉上是一条
 * 平滑滑出的 180ms 滑出／滑入，与 via 的节奏一致（不再用弹簧，避免回弹与拖尾）。
 */
@Composable
internal fun HideableHeader(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "header-hide"
    )
    Layout(modifier = modifier.clipToBounds(), content = content) { measurables, constraints ->
        val placeables = measurables.map {
            it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val shown = (height * progress).roundToInt().coerceIn(0, height)
        layout(placeables.maxOfOrNull { it.width } ?: constraints.maxWidth, shown) {
            placeables.forEach { it.place(0, shown - height) }
        }
    }
}
