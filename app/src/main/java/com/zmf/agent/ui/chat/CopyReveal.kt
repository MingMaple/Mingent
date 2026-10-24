package com.zmf.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

/*
 * 「块内摸一下 → 该块右上角出现『复制』」这套机制的公共实现。
 *
 * 口径（用户定的）：
 *  1. 只有**展开着**的块才会出现复制；折叠时只画一行，摸到也不出现。
 *  2. 触发出现在「块内发生了手势」之后（按下即算，滑动 / 点击都行），不是常驻。
 *  3. **全局只允许一个**：摸 b 框，a 框的复制立刻消失；同一时刻屏幕上最多一个「复制」。
 *  4. 块被折叠就立刻消失（visible 里直接把展开状态算进去）。
 *
 * 为什么不做「长按才露」：整个助手回合已经被长按菜单（复制 / 选择文本 / 回滚）占用，
 * 块里再挂一套长按只会互相打架——按下去先弹出来的是菜单。
 */

/** 屏幕上唯一那个「复制」按钮归谁所有：最后被触摸的、且还展开着的那个块。 */
@Stable
object CopyRevealHost {

    /** 当前的持有者；null 表示没有任何块显示复制。 */
    var owner: Any? by mutableStateOf(null)
        private set

    /** 登记所有权：后摸的块会把前一个挤掉。 */
    fun claim(key: Any) {
        owner = key
    }

    /** 块自己收起 / 离开时归还；不是自己持有的不动（免得把别人的按钮误关）。 */
    fun release(key: Any) {
        if (owner === key) owner = null
    }
}

/** 给块生成一个稳定身份；remember 保证重组之后还是同一个。 */
@Composable
fun rememberCopyBlockKey(): Any = remember { Any() }

/**
 * 块内任意手势（按下即算）都把自己登记成「唯一显示复制」的那一块。
 *
 * Initial 阶段先于子节点执行且不消费事件，所以里面的 clickable / 滚动照常工作。
 */
fun Modifier.claimCopyOnGesture(key: Any): Modifier = pointerInput(key) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        CopyRevealHost.claim(key)
    }
}
