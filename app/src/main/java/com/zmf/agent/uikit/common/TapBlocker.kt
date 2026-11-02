package com.zmf.agent.uikit.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 拦截点击、不拦截拖动的图层。
 *
 * 全屏二级页覆盖在聊天页之上时，点击不得穿透至下层；但抽屉横向滑动属于父节点手势，
 * 若使用 detectTapGestures，它会在 DOWN 事件时即 consume，父层拖拽无法启动。
 * 此处改为「不消费 DOWN，仅在未移动且手指抬起时消费 UP」：
 * - 点击：抬起事件被消费，不会穿透至下层；
 * - 横滑：DOWN 未被消费，父层 draggable 可正常启动，页面内分页器亦可接管。
 */
fun Modifier.blockTapsOnly(): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            var moved = false
            var lastX = down.position.x
            var lastY = down.position.y
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dx = kotlin.math.abs(change.position.x - lastX)
                val dy = kotlin.math.abs(change.position.y - lastY)
                if (dx > 4f || dy > 4f) moved = true
                lastX = change.position.x
                lastY = change.position.y
                if (!change.pressed) {
                    if (!moved) change.consume()
                    break
                }
            }
        }
    }
)
