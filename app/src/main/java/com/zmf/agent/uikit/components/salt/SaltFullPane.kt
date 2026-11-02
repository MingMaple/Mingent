package com.zmf.agent.uikit.components.salt

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
import com.zmf.agent.uikit.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * 全窗口页（如轨迹页）使用的「连体平移」容器：交互手感、判定条件，以及「手指、状态、弹簧三个写入方
 * 依靠时序相互让位」的规则均沿用 SaltDrawer（见该处文件头，此处不再重复）。
 * 区别在于不展开抽屉，而是整窗换页：内容层左移一个屏宽，面板从右侧等宽补入，接缝同样位于
 * width * progress；无遮罩、无圆角、无阴影、无外边距，两块属于同一平面。
 *
 * 本文件独有的两项约束：
 * - 状态仅「请求目标」、不直接写位移。若由状态直接写入，状态、手指与弹簧三方会同时修改同一值，
 *   导致跟手过程中被状态打断；
 * - 手指按下时先调用 cancelSettle 将 progress 交还手指；抬手时若本次按下并未拖拽，则按当前状态
 *   让弹簧补回：既避免「弹簧与手指同时写入」，也不会因点击空白而使正在切换的页面停在中间。
 *
 * 事件传递阶段：Initial 从根到叶，Main 从叶到根。接管挂载于 Main：附件行、多图气泡等
 * horizontalScroll 与纵向列表在 Main 上先收到同一事件，若其已 consume 则本层整段让出；
 * 仅当子节点完全未消费（该内容不具备横向滑动能力）时才由本层修改 progress。
 * 挂载于 Initial 则相反：父层优先拦截，子节点无法收到横向拖拽，图片行将变为「一滑即进入轨迹页」。
 *
 * 仅在「方向被允许」时 consume 的原因：
 * - 关闭态仅接受向左：向右的位移完全不予消费。该面板位于抽屉内容层中，消费向右位移会使
 *   外层抽屉无法拉出：方向不允许时，整段手势均不参与；
 * - 打开态仅接受向右：向左已无更多内容可推，消费无收益，且会阻碍面板内部的横向操作；
 * - 横向未占优或未超过触摸阈值前一律不 consume：纵向滚动与点击仍由子节点处理，
 *   不消费即不存在手势争夺。
 */

// 与 SaltDrawer 使用同一组判定条件，修改一处会导致不一致：速度超过 PANE_FLING_VELOCITY 直接到达端点，否则按位置超过阈值吸附
private const val PANE_FLING_VELOCITY = 600f
private const val PANE_SETTLE_THRESHOLD = 0.35f

// 与 SaltDrawer 的 TAP_SLOP 口径一致：小于该值视为无拖拽接管
private const val PANE_TAP_SLOP = 0.002f

// 抬起前手指已停住该时长即按「速度 0」计算，避免缓慢推至中途松手被判定为甩动
private const val IDLE_VELOCITY_GAP_MS = 80L

/** 吸附协程的唯一槽位（与 SaltDrawer.SettleSlot 同构）。 */
private class PaneSettleSlot {
    var job: Job? = null
    var target = 0f

    // 与 SaltDrawer 一致：通过代次阻止已取消协程在下一帧的写入
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
 * - 无遮罩、无圆角、无阴影、无外边距：与抽屉一致为连体结构，而非浮在页面上的卡片。
 *
 * gestureEnabled = false 时整窗不响应横向拖拽：面板内部存在横向滑动操作（横向列表、图片浏览）
 * 时，由父会话关闭该选项，开关改由状态驱动，手感仍使用同一弹簧。
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

    // 上层每次重组都会提供新的 lambda；经 rememberUpdatedState 保存后，pointerInput 中始终获取
    // 当前实例，无需因 lambda 身份变化重建手势检测
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    val currentOpen by rememberUpdatedState(open)

    // 与 SaltDrawer 一致：将 progress 从弹簧交还手指
    fun cancelSettle() {
        settleSlot.job?.cancel()
        settleSlot.job = null
        settleSlot.epoch++
    }

    // 弹簧的唯一入口（与 SaltDrawer 一致）：同一目标正在吸附时直接复用；
    // 松手路径与 LaunchedEffect(open) 会先后各请求一次相同 target，取消后重启会清除速度，产生顿挫
    fun requestSettle(target: Float) {
        val running = settleSlot.job?.isActive == true
        if (running) {
            if (settleSlot.target == target) return
            settleSlot.job?.cancel()
        } else if (abs(progress - target) < 0.001f) {
            // 已贴边时无需再执行弹簧动画
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

    // 松手唯一出口（与 SaltDrawer 一致）：状态交由 onOpenChange，同时请求一次吸附，
    // 覆盖「状态未变化、LaunchedEffect 不会重跑」的情况（关闭状态下拉到一半又松回，open 始终为 false）
    fun releaseTo(target: Float) {
        currentOnOpenChange(target == 1f)
        requestSettle(target)
    }

    LaunchedEffect(open) {
        // 只请求目标，位移一律由弹簧跑，避免状态和手指并发写 progress
        requestSettle(if (open) 1f else 0f)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 屏宽即行程；无法获取宽度时以 1px 兜底，避免除零使 progress 变为 NaN
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(widthPx, gestureEnabled) {
                    if (!gestureEnabled) return@pointerInput
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        // Main 阶段观察：子节点（图片行、附件行、纵向列表）已先收到同一事件，
                        // 其未消费时才由此处决定是否接管；接管则 consume，否则完全不予处理
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
                                // 指数平滑仅用于判断是否超过 PANE_FLING_VELOCITY 这一档，不做曲线拟合
                                val instant = -dx / (dt / 1000f)
                                velocity = velocity * 0.6f + instant * 0.4f
                                lastMoveTime = now
                            }

                            if (!engaged) {
                                // 子节点已在 Main 上消费这段位移：该手势归其处理（横滑图片行、
                                // pager 翻页、纵向列表），本层整段退出且全程不消费
                                if (change.isConsumed) break
                                totalX += dx
                                totalY += dy
                                // 横向占优且超过触摸阈值时才开始考虑接管：纵向滚动与点击均不受影响
                                if (abs(totalX) > slop && abs(totalX) > abs(totalY)) {
                                    val allowedAtStart = when {
                                        progress <= 0f -> totalX < 0f // 关闭态只接向左
                                        progress >= 1f -> totalX > 0f // 打开态只接向右
                                        else -> true                  // 半开状态（上次手势尚未结束）两个方向均接受
                                    }
                                    // 方向不允许时整段退出、全程不消费，位移交由外层抽屉
                                    if (!allowedAtStart) break
                                    engaged = true
                                }
                            }

                            if (engaged && dx != 0f) {
                                // 已贴到某一侧边界时，继续向外的位移同样不消费（关闭态向右的位移属于抽屉）
                                val allowed = !(progress <= 0f && dx > 0f) && !(progress >= 1f && dx < 0f)
                                if (allowed) {
                                    // 向左拖 dx < 0 → progress 增大，内容层随之左移，拼缝在 width * progress
                                    progress = (progress - dx / widthPx).coerceIn(0f, 1f)
                                    change.consume()
                                }
                            }
                        }

                        // 缓慢推至中途停住后松手：无残余速度，仅按位置吸附
                        if (SystemClock.uptimeMillis() - lastMoveTime > IDLE_VELOCITY_GAP_MS) velocity = 0f

                        if (engaged) {
                            releaseTo(paneSettleTarget(velocity, progress))
                        } else if (wasSettling && abs(progress - startProgress) < PANE_TAP_SLOP) {
                            // 仅为点击操作：按当前状态恢复此前让位的弹簧，避免开关停留在中间状态
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

            // 面板从右侧补入等宽区域，位移等于尚未进入的部分；两块拼缝同为 width * progress
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
