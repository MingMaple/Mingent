package com.zmf.agent.ui.chat

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
 * 横向行滑到头之后，把剩下那点横向位移就地吃掉，不让外层抽屉 / 轨迹页接手。
 *
 * 为什么不用 nestedScroll（上一版是 ConsumeHorizontalOverflow，实测无效已删）：
 * horizontalScroll 到边界时既不再消费、也不把这段余量交给 onPostScroll，
 * 所以 onPostScroll 根本收不到，再往外推就是「把抽屉拉出来」。
 * 上一条指的是「手指还在屏上」的那一段，它由下面的 Main 阶段拦截负责。
 * 但手指抬起之后还有第二阶段：甩动（fling）。快速一甩，行自己先滚到边界，
 * 剩下的**速度**会照常往上派发给 onPostFling，抽屉那边一看到超过 600px/s 就直接弹开。
 * 现象是「慢慢推到边不会开抽屉，但快速甩一下仍然会进抽屉」。
 * 所以除了拦手指，这里还要挂一条只吞横向余量的 nestedScroll 连接（见 HorizontalOverflowEater）。
 *
 * 为什么在 **Main** 阶段消费、而不是 Initial：Initial 是父到子派发，在这里 consume
 * 会让子节点的拖拽检测认为手势被外层抢走、整体取消——表现为「滑到头后手指还在屏上，
 * 反向再滑却滑不动，必须抬手重按」。Main 是子到父派发：子节点的 horizontalScroll
 * 先处理这一帧（所以 state 已是最新值、反向位移能立刻生效），随后这里再吃掉贴边的那一帧，
 * 仍早于外层抽屉 / 轨迹的手势，照样不会把抽屉拉出来。
 *
 * 判定只看单帧：贴没贴到这一侧 + 本帧是否还在往外推。贴住了就 consume，行内还有余量时
 * 一律不碰，滑动仍归 horizontalScroll；横向为主的位移才吃，竖向仍交给消息列表。
 *
 * 从 ChatScreen.kt 的 private 版搬到这里（同包），计划页那条横向计划条也用同一份。
 */
internal fun Modifier.blockHorizontalAtEdge(state: ScrollState): Modifier = this
    // 必须排在 horizontalScroll 之前（= 外层）：同节点上 nestedScroll 只接它里面那层滚动体的派发
    .nestedScroll(HorizontalOverflowEater)
    .pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // 累计位移只用来判「这一下手势到底是不是横向」：单帧几像素判不准，慢速拖动会被误判成竖向
            var dx = 0f
            var dy = 0f
            while (true) {
                // Main 阶段：子节点的 horizontalScroll 已经处理完这一帧，state 是最新值；
                // 且 Main 自内向外派发，在这里 consume 仍早于外层抽屉 / 轨迹的手势
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val stepX = change.position.x - change.previousPosition.x
                val stepY = change.position.y - change.previousPosition.y
                dx += stepX
                dy += stepY
                // 单帧判「贴没贴边 + 还在不往外推」：反向那一下必须落到 horizontalScroll 手里，
                // 否则又要抬手重按才能接着滑
                val atStart = state.value == 0 && stepX > 0
                val atEnd = state.value >= state.maxValue && stepX < 0
                if ((atStart || atEnd) && abs(dx) >= abs(dy)) change.consume()
            }
        }
    }

/**
 * 把这一行「消化不掉」的横向余量就地扣下，不再往父层送。
 *
 * 两个入口都得堵：onPostScroll 是手指还在屏上时逐帧派发的余量，
 * onPostFling 是抬手之后甩动剩下的速度，后者正是「快速甩一下抽屉还是会弹开」的成因。
 * 只吞 x：竖向余量原样返回，消息列表的越界回弹之类的行为一点不受影响。
 */
private object HorizontalOverflowEater : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.x != 0f) Offset(available.x, 0f) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.x != 0f) Velocity(available.x, 0f) else Velocity.Zero
}