package com.zmf.agent.uikit.gesture

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

/**
 * 横向行滑动到边界后，将剩余的横向位移就地消费，不交由外层抽屉或轨迹页处理。
 *
 * 不使用 nestedScroll 的原因（上一版 ConsumeHorizontalOverflow 实测无效，已删除）：
 * horizontalScroll 到达边界时既不继续消费，也不将该段余量交给 onPostScroll，
 * 因此 onPostScroll 无法接收，继续推动即会拉出抽屉。
 * 前述指「手指仍在屏幕上」的阶段，由下方的 Main 阶段拦截负责。
 * 手指抬起后还存在第二阶段：甩动（fling）。快速甩动时行会先滚至边界，
 * 剩余**速度**仍会向上派发至 onPostFling，抽屉接收到超过 600px/s 的速度即直接展开。
 * 现象为「缓慢推至边界不会打开抽屉，但快速甩动仍会进入抽屉」。
 * 因此除拦截手指外，还需挂载仅消费横向余量的 nestedScroll 连接（见 HorizontalOverflowEater）。
 *
 * 在 **Main** 阶段消费而非 Initial 的原因：Initial 为父到子派发，在此 consume
 * 会使子节点的拖拽检测认为手势被外层接管而整体取消，表现为「滑到边界后手指仍在屏幕上，
 * 反向滑动却无响应，必须抬手重按」。Main 为子到父派发：子节点的 horizontalScroll
 * 先处理该帧（此时 state 为最新值，反向位移可立即生效），随后此处消费贴边的该帧，
 * 仍早于外层抽屉或轨迹手势，因此不会拉出抽屉。
 *
 * 判定仅看单帧：是否贴到该侧，以及本帧是否仍向外推。贴边则 consume；行内仍有余量时
 * 一律不处理，滑动仍归 horizontalScroll；以横向为主的位移才消费，纵向位移仍交由消息列表。
 *
 * 原为 ChatScreen.kt 中的 private 版本，移至此处（同包）；计划页的横向计划条亦复用同一实现。
 */
internal fun Modifier.blockHorizontalAtEdge(state: ScrollState): Modifier = this
    // 必须置于 horizontalScroll 之前（即外层）：同一节点上 nestedScroll 仅接收其内部滚动体的派发
    .nestedScroll(HorizontalOverflowEater)
    .pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // 累计位移仅用于判断该手势是否为横向：单帧数像素不足以判定，慢速拖动会被误判为纵向
            var dx = 0f
            var dy = 0f
            while (true) {
                // Main 阶段：子节点的 horizontalScroll 已处理完该帧，state 为最新值；
                // 且 Main 自内向外派发，此处 consume 仍早于外层抽屉或轨迹手势
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val stepX = change.position.x - change.previousPosition.x
                val stepY = change.position.y - change.previousPosition.y
                dx += stepX
                dy += stepY
                // 单帧判定「是否贴边 + 是否仍向外推」：反向位移必须交由 horizontalScroll 处理，
                // 否则需抬手重按才能继续滑动
                val atStart = state.value == 0 && stepX > 0
                val atEnd = state.value >= state.maxValue && stepX < 0
                if ((atStart || atEnd) && abs(dx) >= abs(dy)) change.consume()
            }
        }
    }

/**
 * 将该行无法消费的横向余量就地截留，不再向父层传递。
 *
 * 两个入口均需拦截：onPostScroll 为手指仍在屏幕上时逐帧派发的余量，
 * onPostFling 为抬手后甩动剩余的速度，后者正是「快速甩动仍会打开抽屉」的成因。
 * 仅消费 x 轴：纵向余量原样返回，消息列表的越界回弹等行为完全不受影响。
 */
private object HorizontalOverflowEater : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.x != 0f) Offset(available.x, 0f) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.x != 0f) Velocity(available.x, 0f) else Velocity.Zero
}
