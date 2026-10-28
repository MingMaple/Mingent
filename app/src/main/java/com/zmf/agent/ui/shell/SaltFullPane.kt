package com.zmf.agent.ui.shell

import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.zmf.agent.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * 全窗口页（轨迹页这类）用的「连体平移」容器：手感、判据，以及「手指 / 状态 / 弹簧三个写入方
 * 靠时序互相让位」这套规矩全部沿用 SaltDrawer（见那边的文件头，这里不再抄一遍）。
 * 区别是不展开抽屉，而是整窗换页：内容层左移一个屏宽，面板从右侧等宽补进来，拼缝同样落在
 * width * progress；无遮罩 / 无圆角 / 无阴影 / 无外边距，两块是同一张纸。
 *
 * 本文件独有的两条约束：
 * - 状态只「请求目标」、不直接写位移。状态自己写的话，状态、手指、弹簧三方会同时改同一个值，
 *   跟手到一半突然被状态推一把；
 * - 手指按下先 cancelSettle 把 progress 交还手指，抬手时若这次按下其实没拖拽就按当前状态把
 *   弹簧补回来：既不会「弹簧和手指同时写」，也不会点一下空白就把正在开关的页面冻在半路。
 *
 * 事件传递阶段：Initial 从根到叶，Main 从叶到根。接管挂在 Main 上：附件行、多图气泡这些
 * horizontalScroll 和竖向列表在 Main 上先拿到同一个事件，它们 consume 了本层就整段让出去；
 * 只有子节点一个字节都没消费（这片内容根本没有横滑能力）时才轮到本层动 progress。
 * 挂 Initial 会反过来：父层先抢，子节点永远收不到横向拖拽，图片行就变成「一滑就进轨迹页」。
 *
 * 为什么只在「方向被允许」时 consume：
 * - 关闭态只接向左：向右的位移一个字节都不消费。这个面板挂在抽屉的内容层里，把向右的位移吃掉，
 *   外层抽屉就再也拉不出来了：方向不让，就整段手势都不参与；
 * - 打开态只接向右：向左没有更远的内容可推，消费了没人受益，还会挡住面板内部的横向操作；
 * - 横向没占优、或还没过触摸阈值之前一律不 consume：竖向滚动和点击照旧由子节点处理，
 *   不消费就没有抢手势的问题。
 */

// 和 SaltDrawer 同一组判据，改一处会漂移：速度超过 PANE_FLING_VELOCITY 直接到底，否则按位置过阈值吸附
private const val PANE_FLING_VELOCITY = 600f
private const val PANE_SETTLE_THRESHOLD = 0.35f

// 同 SaltDrawer 的 TAP_SLOP 口径：小于它就认为没有任何拖拽接管过
private const val PANE_TAP_SLOP = 0.002f

// 抬起前手指已经停住多久就按「速度 0」算，免得慢慢推到一半松手被判成甩动
private const val IDLE_VELOCITY_GAP_MS = 80L

/** 吸附协程的唯一槽位（与 SaltDrawer.SettleSlot 同构）。 */
private class PaneSettleSlot {
    var job: Job? = null
    var target = 0f

    // 同 SaltDrawer：用代次挡掉被取消协程已排进下一帧的写入
    var epoch = 0
}

// 松手目标的唯一判据：速度正 = 向左推（开），负 = 向右推（合），和位移同量纲（px/s）
private fun paneSettleTarget(velocity: Float, progress: Float): Float = when {
    velocity > PANE_FLING_VELOCITY -> 1f
    velocity < -PANE_FLING_VELOCITY -> 0f
    progress > PANE_SETTLE_THRESHOLD -> 1f
    else -> 0f
}

/**
 * 占满整窗、与正文连体平移的一页。
 *
 * - 关闭：内容层不动，面板整块在屏幕右侧之外；
 * - 打开：内容层整体左移一个屏宽（translationX = -width * progress），面板从右往左补进来
 *   （translationX = width * (1 - progress)），两者拼缝同为 x = width * progress；
 * - 关闭态只有向左拖能拉开，打开态只有向右拖能收起，松手按速度 / 位置吸附到 0 或 1；
 * - 无遮罩、无圆角、无阴影、无外边距：和抽屉一样是连体，不是浮在页面上的一块卡片。
 *
 * gestureEnabled = false 时整窗不响应横向拖拽：面板内部自己有横滑操作（横向列表、图片浏览）
 * 时，父会话把它关掉即可，开关改由状态驱动，手感仍然走同一个弹簧。
 */
@Composable
fun SaltFullPane(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    gestureEnabled: Boolean = true,
    panel: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(if (open) 1f else 0f) }

    val settleSlot = remember { PaneSettleSlot() }

    // 上层每次重组都会给新的 lambda；收进 rememberUpdatedState 后，pointerInput 里拿到的
    // 永远是当前那个，不必跟着 lambda 身份重建手势检测
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    val currentOpen by rememberUpdatedState(open)

    // 同 SaltDrawer：把 progress 从弹簧手里交还给手指
    fun cancelSettle() {
        settleSlot.job?.cancel()
        settleSlot.job = null
        settleSlot.epoch++
    }

    // 弹簧的唯一入口（同 SaltDrawer）：同目标正在吸附时直接复用，
    // 松手路径和 LaunchedEffect(open) 会先后各请求一次同一个 target，「取消 + 重启」会清掉速度，又是顿挫
    fun requestSettle(target: Float) {
        val running = settleSlot.job?.isActive == true
        if (running) {
            if (settleSlot.target == target) return
            settleSlot.job?.cancel()
        } else if (abs(progress - target) < 0.001f) {
            // 已经贴边就别空跑一遍弹簧
            progress = target
            return
        }
        settleSlot.target = target
        val epoch = ++settleSlot.epoch
        settleSlot.job = scope.launch {
            animate(progress, target, animationSpec = Motion.spring()) { value, _ ->
                if (epoch == settleSlot.epoch) progress = value
            }
            if (epoch == settleSlot.epoch) {
                progress = target
                settleSlot.job = null
            }
        }
    }

    // 松手唯一出口（同 SaltDrawer）：状态交给 onOpenChange，同时请求一次吸附，
    // 覆盖「状态没变、LaunchedEffect 不会重跑」的情况（关着拉到一半又松回去，open 一直是 false）
    fun releaseTo(target: Float) {
        currentOnOpenChange(target == 1f)
        requestSettle(target)
    }

    LaunchedEffect(open) {
        // 只请求目标，位移一律由弹簧跑，避免状态和手指并发写 progress
        requestSettle(if (open) 1f else 0f)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 屏宽即行程；取不到宽度时兜底 1px，避免除零把 progress 炸成 NaN
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(widthPx, gestureEnabled) {
                    if (!gestureEnabled) return@pointerInput
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        // Main 阶段看：子节点（图片行、附件行、竖向列表）已经先拿到同一个事件，
                        // 它们没消费才轮到这里决定「这一下归我」，归我就 consume，不归我则一个字节都不碰
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                        val wasSettling = settleSlot.job?.isActive == true
                        val startProgress = progress
                        if (wasSettling) cancelSettle()

                        var engaged = false // 本次手势是否已被本面板接管
                        var totalX = 0f
                        var totalY = 0f
                        var velocity = 0f   // px/s，正 = 向左推（开）
                        var lastTime = SystemClock.uptimeMillis()
                        var lastMoveTime = lastTime

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break

                            val dx = change.position.x - change.previousPosition.x
                            val dy = change.position.y - change.previousPosition.y

                            val now = SystemClock.uptimeMillis()
                            val dt = (now - lastTime).coerceAtLeast(1L)
                            lastTime = now
                            if (dx != 0f) {
                                // 指数平滑，只为「有没有超过 PANE_FLING_VELOCITY」这一档判断服务，不做拟合
                                val instant = -dx / (dt / 1000f)
                                velocity = velocity * 0.6f + instant * 0.4f
                                lastMoveTime = now
                            }

                            if (!engaged) {
                                // 子节点已经在 Main 上消费了这段位移：这一下归它（横滑图片行、
                                // pager 翻页、竖向列表），整段退出且全程不消费
                                if (change.isConsumed) break
                                totalX += dx
                                totalY += dy
                                // 横向占优且走过触摸阈值才谈接管：竖向滚动、点击都不受影响
                                if (abs(totalX) > slop && abs(totalX) > abs(totalY)) {
                                    val allowedAtStart = when {
                                        progress <= 0f -> totalX < 0f // 关闭态只接向左
                                        progress >= 1f -> totalX > 0f // 打开态只接向右
                                        else -> true                  // 半开（上次手势还没收口）两边都接
                                    }
                                    // 方向不让就整段退出、全程不消费，位移留给外层抽屉
                                    if (!allowedAtStart) break
                                    engaged = true
                                }
                            }

                            if (engaged && dx != 0f) {
                                // 已经贴到某一侧边界时，继续往外的位移同样不消费（关闭态向右 = 抽屉的）
                                val allowed = !(progress <= 0f && dx > 0f) && !(progress >= 1f && dx < 0f)
                                if (allowed) {
                                    // 向左拖 dx < 0 → progress 增大，内容层随之左移，拼缝在 width * progress
                                    progress = (progress - dx / widthPx).coerceIn(0f, 1f)
                                    change.consume()
                                }
                            }
                        }

                        // 慢慢推到一半停住再松手：没有残余速度，纯按位置吸附
                        if (SystemClock.uptimeMillis() - lastMoveTime > IDLE_VELOCITY_GAP_MS) velocity = 0f

                        if (engaged) {
                            releaseTo(paneSettleTarget(velocity, progress))
                        } else if (wasSettling && abs(progress - startProgress) < PANE_TAP_SLOP) {
                            // 只是点了一下：把刚才让位的弹簧按当前状态补回来，别把开关冻在半路
                            requestSettle(if (currentOpen) 1f else 0f)
                        }
                    }
                }
        ) {
            // 正文层整体左移一个屏宽：位移只改图层，不参与重排
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = -widthPx * progress }
            ) {
                content()
            }

            // 面板从右侧补进等宽的缝，位移 = 只剩没进来的那部分；两块拼缝同为 width * progress
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = widthPx * (1f - progress) }
            ) {
                panel()
            }
        }
    }
}
