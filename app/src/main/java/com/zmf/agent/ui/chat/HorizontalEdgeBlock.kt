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
 * ——这一条指的是「手指还在屏上」的那一段，它由下面的 Initial 拦截负责。
 * 但手指抬起之后还有第二阶段：甩动（fling）。快速一甩，行自己先滚到边界，
 * 剩下的**速度**会照常往上派发给 onPostFling，抽屉那边一看到超过 600px/s 就直接弹开。
 * 这就是用户 2026-10-25 报的「慢慢推到边不会开抽屉，但快速甩一下仍然会进抽屉」。
 * 所以除了拦手指，这里还要挂一条只吞横向余量的 nestedScroll 连接（见 HorizontalOverflowEater）。
 *
 * 为什么走 Initial 阶段逐帧判：到边界之后子节点本身已经不再消费，等 Main 阶段看到时
 * 外层可能已经接走了；这里只问「贴没贴到这一侧 + 方向是不是往外」，贴住了就直接 consume，
 * 行内还有余量时一律不碰，滑动仍然归 horizontalScroll。竖向为主的位移也不碰，
 * 所以消息列表的滚动不受影响。
 *
 * 2026-10-25：从 ChatScreen.kt 的 private 版搬到这里（同包），计划页那条横向计划条也用同一份。
 */
internal fun Modifier.blockHorizontalAtEdge(state: ScrollState): Modifier = this
    // 必须排在 horizontalScroll 之前（= 外层）：同节点上 nestedScroll 只接它里面那层滚动体的派发
    .nestedScroll(HorizontalOverflowEater)
    .pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // 累计位移判方向：只看单帧的话，慢速拖动一帧不过几像素，永远判不出横向
            var dx = 0f
            var dy = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                dx += change.position.x - change.previousPosition.x
                dy += change.position.y - change.previousPosition.y
                // 只有「已经贴着这一侧、还在继续往外推」才吃：行内还有余量时，滑动归 horizontalScroll
                val atStart = state.value == 0 && dx > 0
                val atEnd = state.value >= state.maxValue && dx < 0
                if (change.pressed && (atStart || atEnd) && abs(dx) > abs(dy)) change.consume()
                if (!change.pressed) break
            }
        }
    }

/**
 * 把这一行「消化不掉」的横向余量就地扣下，不再往父层送。
 *
 * 两个入口都得堵：onPostScroll 是手指还在屏上时逐帧派发的余量，
 * onPostFling 是抬手之后甩动剩下的速度——后者正是「快速甩一下抽屉还是会弹开」的成因。
 * 只吞 x：竖向余量原样返回，消息列表的越界回弹之类的行为一点不受影响。
 */
private object HorizontalOverflowEater : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.x != 0f) Offset(available.x, 0f) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.x != 0f) Velocity(available.x, 0f) else Velocity.Zero
}
