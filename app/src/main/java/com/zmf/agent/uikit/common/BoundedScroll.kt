package com.zmf.agent.uikit.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp

/*
 * 有限高度、仅在内容实际超出时拦截滑动的纵向滚动容器。
 *
 * 不能仅使用 heightIn(max) + verticalScroll + nestedScroll 的原因：
 * 原实现无条件挂载 nestedScroll，它会接收内层滚动到边界后的全部剩余位移，
 * 导致内容仅两行、无需滚动的区块也会拦截整页滑动，
 * 用户手指在短文本上滑动时，聊天列表无法移动。
 *
 * 此处区分两种情况：
 *  - 内容未超过上限：内层无可用滚动距离，verticalScroll 在边界不消费，
 *    位移交由外层 LazyColumn 处理，等同于整页滚动；
 *  - 内容超过上限：内层优先滚动；到达边界后由 [EatRemainingScroll] 接收剩余位移，
 *    整页不随之滚动（否则滑动会传递至列表，手指仍在区块内而页面已移动）。
 *
 * 「是否超出」不做额外测量：ScrollState.maxValue 为 scrollable 在测量阶段写回的可滚动距离，
 * 为 0 即表示内容可容纳。如此可省去一次 SubcomposeLayout 二次测量，长文本（Markdown）不会被测量两次。
 */

/**
 * 接收内层滚动到边界后的剩余位移，使其不再向上传递。
 *
 * 判定条件置于事件中（state.maxValue > 0），而非「是否挂载该连接」：
 * 若按 overflowing 条件增删 nestedScroll，折叠动画期间 modifier 链会随之变化，
 * 同一次布局需要重建节点，表现为动画结束后出现跳动。将条件判断移入事件后，
 * modifier 链始终保持不变，短内容仍将滑动交由外层处理（行为一致）。
 */
private class EatRemainingScroll(private val state: ScrollState) : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (state.maxValue > 0) available else Offset.Zero
}

/**
 * 内容高度上限为 [maxHeight]，超出时在区块内纵向滚动。
 *
 * 内容可容纳时区块高度自适应内容，滑动交由外层页面处理；超出时方在边界拦截滑动。
 */
@Composable
fun BoundedScrollColumn(
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    val eater = remember(scrollState) { EatRemainingScroll(scrollState) }
    Column(
        modifier = modifier
            .heightIn(max = maxHeight)
            // nestedScroll 必须挂在 verticalScroll **之前**：
            // 它充当该滚动节点的父级，只有父级能接收内层滚动到边界后的剩余位移。
            // 若写在 verticalScroll 之后则成为其子级，永远无法接收剩余位移，表现为
            // 「框内滚动到底后整页对话随之移动」。
            // 此处**无条件**挂载，是否接收剩余位移由 [EatRemainingScroll] 在事件中判断（见其注释）。
            .nestedScroll(eater)
            .verticalScroll(scrollState),
        content = content
    )
}
