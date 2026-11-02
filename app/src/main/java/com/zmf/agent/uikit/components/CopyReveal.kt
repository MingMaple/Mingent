package com.zmf.agent.uikit.components

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
 * 「触摸区块后该区块右上角显示『复制』」机制的公共实现。
 *
 * 规则：
 *  1. 仅**展开状态**的区块显示复制；折叠时只绘制一行，触摸亦不显示。
 *  2. 复制按钮在「区块内发生手势」后出现（按下即计，滑动或点击均可），并非常驻。
 *  3. **全局仅允许一个**：触摸区块 b 时，区块 a 的复制立即消失；同一时刻屏幕上最多一个「复制」。
 *  4. 区块折叠后立即消失（visible 已计入展开状态）。
 *
 * 不采用「长按才显示」的原因：整个助手回合已被长按菜单（复制、选择文本、回滚）占用，
 * 区块内再挂一套长按会相互冲突，按下后先弹出的是菜单。
 */

/** 屏幕上唯一「复制」按钮的所有权：最后被触摸且仍处于展开状态的区块。 */
@Stable
object CopyRevealHost {

    /** 当前的持有者；null 表示没有任何块显示复制。 */
    var owner: Any? by mutableStateOf(null)
        private set

    /** 登记所有权：后触摸的区块会取代前一个持有者。 */
    fun claim(key: Any) {
        owner = key
    }

    /** 区块收起或离开时归还；非自身持有则不作处理（避免误关其他区块的按钮）。 */
    fun release(key: Any) {
        if (owner === key) owner = null
    }
}

/** 为区块生成稳定身份；remember 保证重组后仍为同一实例。 */
@Composable
fun rememberCopyBlockKey(): Any = remember { Any() }

/**
 * 区块内任意手势（按下即计）均将自身登记为「唯一显示复制」的区块。
 *
 * Initial 阶段先于子节点执行且不消费事件，因此其中的 clickable 与滚动仍正常工作。
 */
fun Modifier.claimCopyOnGesture(key: Any): Modifier = pointerInput(key) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        CopyRevealHost.claim(key)
    }
}
