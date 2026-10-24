package com.zmf.agent.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp

/*
 * 有限高、但「只有真超了才吃滑动」的竖滚容器。
 *
 * 为什么不能只写 heightIn(max) + verticalScroll + nestedScroll：
 * 老实现里 nestedScroll 是无条件挂的，它会认领内层滚到边界后的所有剩余位移——
 * 于是内容只有两行、根本不需要滚的块，也会把整页的滑动吃掉，
 * 用户手指划在短文本上，聊天列表一动不动（「滑不动」的手感问题）。
 *
 * 这里的口径是两种：
 *  - 内容没超过上限 —— 内层没有可滚的距离，verticalScroll 在边界不消费，
 *    位移自然交给外层 LazyColumn，等于「整页滚动」；
 *  - 内容超过上限 —— 内层先滚；滚到边界后由 [EatRemainingScroll] 把余量吃掉，
 *    整页不跟着跑（否则滑动会「穿透」到列表，手指还停在块里页面却飞了）。
 *
 * 「有没有超过」不额外量一遍：ScrollState.maxValue 是 scrollable 在测量阶段写回的可滚距离，
 * 为 0 就说明装得下。省掉一次 SubcomposeLayout 二次测量，长文本（Markdown）也不会被量两遍。
 */

/** 把内层滚到边界后的剩余位移认领掉，不让它继续往上冒。 */
private val EatRemainingScroll = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
}

/**
 * 内容最多 [maxHeight] 高，超出就在块内竖滚。
 *
 * 装得下时整块高度自适应内容，滑动交给外层页面；装不下时才在边界截住滑动。
 */
@Composable
fun BoundedScrollColumn(
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    val overflowing by remember(scrollState) { derivedStateOf { scrollState.maxValue > 0 } }
    Column(
        modifier = modifier
            .heightIn(max = maxHeight)
            // nestedScroll 必须挂在 verticalScroll **外面**（写在它前面）：
            // 它扮演的是这个滚动节点的「父级」，只有父级才收得到内层滚到边界后剩下的位移。
            // 写在 verticalScroll 后面就成了它的子级，永远收不到余量——表现就是
            // 「框里滑到底之后，整页对话跟着一起动」。
            .then(if (overflowing) Modifier.nestedScroll(EatRemainingScroll) else Modifier)
            .verticalScroll(scrollState),
        content = content
    )
}
