package com.zmf.agent.uikit.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 长按检测，只观察不消费事件。
 *
 * 挂载于整个回合时，仍需由子节点处理点击（工具卡、代码块的复制按钮），因此此处从不调用
 * consume；超过触摸阈值或手指抬起即放弃，不抢占列表滚动。
 */
@Composable
fun Modifier.saltLongPress(onLongPress: (Offset) -> Unit): Modifier {
    val current by rememberUpdatedState(onLongPress)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val origin = down.position
            val slop = viewConfiguration.touchSlop
            val timeout = viewConfiguration.longPressTimeoutMillis
            val expired = withTimeoutOrNull(timeout) {
                var held = true
                while (held) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        held = false
                    } else if (event.changes.count { it.pressed } > 1) {
                        // 第二根手指落下通常表示缩放或双指拖动，长按须让步
                        held = false
                    } else if (abs(change.position.x - origin.x) > slop ||
                        abs(change.position.y - origin.y) > slop
                    ) {
                        // 按位移而非直线距离判定：横向或纵向任一方向滑出均视为非长按，
                        // 否则惯性停止时的手指微移会被误判为静止按住
                        held = false
                    }
                }
                false
            }
            if (expired == null) current(origin)
        }
    }
}
