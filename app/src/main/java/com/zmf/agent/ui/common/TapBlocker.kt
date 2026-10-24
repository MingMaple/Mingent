package com.zmf.agent.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 吞「点击」、不吞「拖动」的拦截层。
 *
 * 全屏二级页盖在聊天页之上时，点击不能穿透到下面；但抽屉的横滑是父节点的手势，
 * 一旦用了 detectTapGestures，它会在 DOWN 那一帧就把事件 consume 掉，父层的拖拽再也起不来。
 * 这里改成「不消费 DOWN，只在自己没移动、手指抬起时消费 UP」：
 * - 点击：抬起事件被吞，不会穿透到下层；
 * - 横滑：DOWN 没被吞，父层的 draggable 能正常开始，页面里的分页器也能抢到。
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
