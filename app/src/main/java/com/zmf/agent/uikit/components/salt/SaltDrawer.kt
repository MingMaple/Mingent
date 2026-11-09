package com.zmf.agent.uikit.components.salt

import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.zmf.agent.uikit.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * progress 只有三个写入方，依靠「谁在何时可写」互斥，不得新增第四个：
 *
 * 1. 手指：根 Box 在 Main pass 上接管的位移，以及关闭态向左的累计位移，跟手阶段独占 progress；
 * 2. nestedScroll：pager 消费后的越界量（onPostScroll）与松手速度（onPostFling），
 *    仅在弹簧未运行时按「剩余行程」封顶修改 progress，弹簧启动后整体让位；
 * 3. 弹簧：SettleSlot 中唯一的 animate 协程，只能由 requestSettle 启动；启动前先取消上一个，
 *    并通过 epoch 丢弃被取消协程可能已排入下一帧的写入。
 *
 * 规则：任何事件都不得自行 scope.launch 一段 animate。要么直接写 progress（跟手），
 * 要么调用 requestSettle（松手或 open 变化）。多条路径各自启动弹簧会导致同一 progress 被并发写入，
 * 表现为「超过阈值后平滑减速」。
 * 手指按下时先 cancelSettle 将 progress 交还手指；抬手时若本次按下并未拖拽，则恢复弹簧，
 * 既避免「弹簧与手指同时写入」，也不会因点击空白而使正在开关的抽屉停在中间。
 */

/*
 * 事件传递阶段决定「谁先获得横向位移」，本文件两条接管路径分别挂载于两个阶段，不得混用：
 *
 * - Initial 从根到叶：父层先看到事件。仅「必须抢在子节点之前」的路径挂载于此：
 *   interceptRightSwipe 段（pager 在 Main 上必然先消费位移，不抢占则始终为两段式）。
 * - Main 从叶到根：附件行、多图气泡、pager、纵向列表等子节点先取得事件并 consume。
 *   普通页的整屏横滑接管挂载于此，一旦发现 isConsumed 即整段让出，仅在子节点未消费时修改 progress。
 */

// 普通页（Main pass 接管的松手）和 pager 页（nestedScroll 的 onPostFling）共用这一组判据，
// 两处各自实现会导致不一致：速度超过 FLING_VELOCITY 直接展开或收回，否则按位置超过 SETTLE_THRESHOLD 吸附。
private const val FLING_VELOCITY = 600f
private const val SETTLE_THRESHOLD = 0.35f

// 关闭态左滑的位移阈值按面板宽度取比例，更换机型无需重新调整
private const val LEFT_SWIPE_TRAVEL_RATIO = 0.2f

// 用于判定「本次仅为点击」的 progress 容差：小于该值视为无拖拽接管
private const val TAP_SLOP_PROGRESS = 0.002f

// 松手目标的唯一判据：pager 页传 onPostFling 的速度，普通页传 VelocityTracker 的速度，同量纲（px/s）
private fun settleTarget(velocity: Float, progress: Float): Float = when {
    velocity > FLING_VELOCITY -> 1f
    velocity < -FLING_VELOCITY -> 0f
    progress > SETTLE_THRESHOLD -> 1f
    else -> 0f
}

/** 吸附协程的唯一槽位；细节见文件顶部的互斥说明。 */
private class SettleSlot {
    var job: Job? = null
    var target = 0f

    // 旧协程被取消时可能已排入下一帧的写入，通过代次予以拦截
    var epoch = 0
}

/** 关闭态左滑的累计位移与「本次手势已触发」标记；使用普通类而非 State，避免每帧触发重组。 */
private class LeftSwipeState {
    var travel = 0f
    var fired = false

    fun reset() {
        travel = 0f
        fired = false
    }
}

/**
 * Salt 风格侧边栏：并非「浮在页面上的卡片」，而是页面整体右移、抽屉从左侧同步滑入，两块连体平移。
 *
 * - 关闭时在主页任意位置向右拖均可拉开，打开时向左拖可收起；例外是内容区中可横向滚动的行
 *   （待发附件行、多图气泡），位移先归其处理，滑到边界后才交由抽屉；
 * - 松手后按位置或速度吸附，使用统一弹簧，而非线性补间；
 * - 页面层 translationX = panelWidth * progress，抽屉 translationX = -panelWidth * (1 - progress)，
 *   两者拼缝位置同为 panelWidth * progress，无外边距、圆角、阴影与遮罩。
 *
 * gestureEnabled = false 时整屏不响应横向拖拽：全屏页面（设置、工作区等）处于下层时，
 * 在页面上横向滑动不会拉出抽屉。
 *
 * 包管理、会话列表等页面使用 HorizontalPager，横向拖拽会先被 pager 消费，Main pass 上的
 * 整屏接管无法获得位移；因此根 Box 再挂载一层 nestedScroll，将 pager 消费后仍无归属的
 * 横向越界量转为抽屉位移（见下方 connection 的注释），无需再依赖左边缘手势条。
 *
 * onSwipeLeft 仅服务于「轨迹页」这类需要左滑返回的页面：抽屉关闭状态下继续向左滑动并越过阈值时回调一次。
 * 内容区自行消费的横滑（图片横向滚动、多图气泡、pager 翻页、纵向滚动）不会触发：Main pass 上
 * 子节点先消费，检测到 isConsumed 即整段让出，而 onPostScroll 中本就只剩越界量。
 *
 * interceptRightSwipe = true 仅用于「内容区为 HorizontalPager 且当前停在第 1 页」的页面。
 * 上述 nestedScroll 只能接收 pager 消费后的越界量，导致第一段位移全部被 pager 消耗、抽屉
 * 无响应，直至 pager 到达边界才开始移动，行程翻倍且明显分为两段。该开关使根 Box 在 Initial pass 上
 * 先于 pager 将「向右」的位移写入 progress，从第一个像素起即跟手，全程为一条连续手势；
 * 向左位移完全不消费，翻页行为不变。这是全文件唯一挂载于 Initial 的抢占：Initial 上父层先于所有
 * 子节点取得事件，再挂一条会使该区域的横向滚动全部失效。
 */
@Composable
fun SaltDrawer(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    panelWidth: Dp = 304.dp,
    gestureEnabled: Boolean = true,
    onSwipeLeft: (() -> Unit)? = null,
    interceptRightSwipe: Boolean = false,
    panel: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val panelPx = with(LocalDensity.current) { panelWidth.toPx() }
    var progress by remember { mutableFloatStateOf(if (open) 1f else 0f) }
    // 关闭状态下的点按层仅在越过阈值时增删；经 derivedStateOf 收敛，避免跟手拖动期间逐帧重组
    val scrimVisible by remember { derivedStateOf { progress > 0.02f } }

    val settleSlot = remember { SettleSlot() }
    val swipe = remember { LeftSwipeState() }

    // 上层每次重组都会提供新的 lambda；经 rememberUpdatedState 保存后，remember 中的 connection
    // 与 pointerInput 始终获取当前实例，无需因 lambda 身份变化重建
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOpen by rememberUpdatedState(open)
    // nestedScroll 连接为 remember 生成的长生命周期对象，必须读取「最新」的手势开关：
    // 经 State 保存后再访问，页面切换（计划页 → 主页）时才能获得当前值
    val currentGestureEnabled by rememberUpdatedState(gestureEnabled)

    // 将 progress 交还手指：手指按下或请求新目标时调用
    fun cancelSettle() {
        settleSlot.job?.cancel()
        settleSlot.job = null
        settleSlot.epoch++
    }

    // 弹簧的唯一入口。同一目标正在吸附时直接复用：松手路径与 LaunchedEffect(open) 会先后
    // 各请求一次相同 target；若每次均取消后重启，弹簧速度会被清零、轨迹重新起步，
    // 产生可见顿挫。
    fun requestSettle(target: Float) {
        val running = settleSlot.job?.isActive == true
        if (running) {
            if (settleSlot.target == target) return
            settleSlot.job?.cancel()
        } else if (abs(progress - target) < 0.001f) {
            // 已贴边时无需执行弹簧动画（每次翻页额外播放一帧并无意义）
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

    // 松手与翻页结束的唯一出口：状态交由 onOpenChange 驱动 LaunchedEffect(open) 收口，
    // 同时请求一次吸附，覆盖「状态未变化、LaunchedEffect 不会重跑」的情况（关闭状态下拉到一半又松回，
    // open 始终为 false）。相同目标的重复请求由 requestSettle 去重，不会二次驱动。
    fun releaseTo(target: Float) {
        currentOnOpenChange(target == 1f)
        requestSettle(target)
    }

    // 关闭态向左拖动超过阈值视为一次左滑；标记用于阻止同一段拖拽重复回调
    fun fireLeftSwipe() {
        if (swipe.fired) return
        swipe.fired = true
        currentOnSwipeLeft?.invoke()
    }

    // 仅统计「抽屉已合上且方向向左」的位移：内容区自行消费的横滑既不会出现在
    // 本层的 delta 中（子节点在 Main pass 上先消费），也不会出现在 onPostScroll 的 available 中
    fun observeLeftSwipe(deltaPx: Float) {
        if (swipe.fired) return
        swipe.travel += deltaPx
        if (swipe.travel <= -LEFT_SWIPE_TRAVEL_RATIO * panelPx) fireLeftSwipe()
    }

    // 跟手阶段写入 progress 的唯一入口（普通页与 interceptRightSwipe 页共用），返回该位移
    // 是否实际推动抽屉：已贴至 0 仍继续向左时仅触发左滑回调、progress 不变并返回 false，
    // 该段位移不予消费，交由轨迹页处理；贴至 1 时同理。返回值决定是否 consume。
    fun pullBy(deltaPx: Float): Boolean {
        if (progress <= 0f && deltaPx < 0f) {
            observeLeftSwipe(deltaPx)
            return false
        }
        val next = (progress + deltaPx / panelPx).coerceIn(0f, 1f)
        if (next == progress) return false
        progress = next
        return true
    }

    LaunchedEffect(open) {
        // 手指与翻页路径仅修改 open，实际吸附统一在此处收口
        requestSettle(if (open) 1f else 0f)
        // 合上后左滑需可再次使用
        if (!open) swipe.reset()
    }

    // pager 会自行消费横向拖拽，根 Box 在 Main pass 上始终取得 0 位移；nestedScroll 的
    // post 阶段取得的正是「子节点消费后仍无归属」的余量，将其交由抽屉，使 pager 处于第一页、
    // 第二页及抽屉关闭或打开状态时均不再出现「横滑无法拉出抽屉」的死区。
    val connection = remember(panelPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 该连接仅服务「子节点为 pager、横向拖拽被 pager 消费」的页面。
                // 手势关闭的页面（设置 / 计划 / 工作区等）完全不接收：若无条件挂载，
                // 计划页的横向计划条缓慢推至边界不会打开抽屉，快速甩动却仍会进入抽屉，
                // 因为甩动的余量经由此处处理，绕过了外层手势开关。
                if (!currentGestureEnabled) return Offset.Zero
                // 弹簧正在收口时整体让位：手指或翻页再叠加增量会导致两个写入方相互冲突
                if (settleSlot.job?.isActive == true) return Offset.Zero

                // 尚未完全打开且右侧存在越界余量：向外拉出。take 按「剩余行程」封顶，progress 不会越界。
                if (progress < 1f && available.x > 0f) {
                    val take = min(available.x, (1f - progress) * panelPx)
                    progress = (progress + take / panelPx).coerceIn(0f, 1f)
                    return Offset(take, 0f)
                }
                // 已拉开且左侧存在越界余量：向回收起。同样按「已拉出的行程」封顶。
                if (progress > 0f && available.x < 0f) {
                    val take = max(available.x, -progress * panelPx)
                    progress = (progress + take / panelPx).coerceIn(0f, 1f)
                    return Offset(take, 0f)
                }
                // 抽屉已合上仍继续向左滑动：此处 progress 不得减为负数（否则面板会被推出画面），
                // 因此仅累计并触发左滑回调，且不消费该段余量，交由内容区自行处理越界
                if (progress <= 0f && available.x < 0f) observeLeftSwipe(available.x)
                return Offset.Zero
            }

            // 松手（pager 自身的 fling 也会进入此处）：判据与手指路径使用同一个 settleTarget；
            // available.x 为 px/s，与 VelocityTracker 计算出的 velocity 同量纲；并将该部分速度全部视为已消费，
            // 否则抽屉已确定开或关之后，pager 仍会按残余速度再翻一页。
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // 与 onPostScroll 一致：手势关闭的页面不接受任何「子节点抛出的余量」
                if (!currentGestureEnabled) return Velocity.Zero
                // 关闭态仍被向左甩出即左滑返回，需优先判断，避免被下方的「吸附至 0」覆盖
                if (progress <= 0f && available.x < -FLING_VELOCITY) fireLeftSwipe()
                releaseTo(settleTarget(available.x, progress))
                return available
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 仅观察、从不消费：pager 横滑、列表滚动与点击仍由子节点处理。按下时先复位左滑标记，
            // 并让弹簧让位；抬手时若本次按下未发生拖拽（仅为点击），则恢复弹簧。
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val wasSettling = settleSlot.job?.isActive == true
                    val startProgress = progress
                    swipe.reset()
                    if (wasSettling) cancelSettle()
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        pressed = change?.pressed == true
                    }
                    if (wasSettling && abs(progress - startProgress) < TAP_SLOP_PROGRESS) {
                        requestSettle(if (currentOpen) 1f else 0f)
                    }
                }
            }
            // pager 页专用：横滑在 Main pass 上先由 pager 取得，因此该路径必须挂载于 Initial pass，
            // 才能在 pager 之前接管「向右」的位移。仅消费向右位移，向左与纵向一律原样放行。
            .pointerInput(interceptRightSwipe) {
                if (!interceptRightSwipe) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // 已拉开或正在拉开时交由 Main pass 的整屏接管处理，两条路径同时写 progress 会相互冲突
                    if (progress > 0f) return@awaitEachGesture

                    // 手指按下时将 progress 交还手指，与上方 pointerInput 遵循同一规则
                    val wasSettling = settleSlot.job?.isActive == true
                    val startProgress = progress
                    if (wasSettling) cancelSettle()

                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val slop = viewConfiguration.touchSlop
                    var sumX = 0f
                    var sumY = 0f
                    var dragging = false

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) break
                        val dx = change.position.x - change.previousPosition.x
                        val dy = change.position.y - change.previousPosition.y
                        if (!dragging) {
                            sumX += dx
                            sumY += dy
                            // 纵向或向左位移不属于该路径：不消费任何事件，立即交还
                            if (abs(sumY) > slop && abs(sumY) >= abs(sumX)) break
                            if (sumX < -slop) break
                            if (sumX > slop) {
                                dragging = true
                                // 超过 slop 的该帧将此前整段位移一次性补入，否则该段滑动不会生效
                                pullBy(sumX)
                                change.consume()
                            }
                        } else {
                            pullBy(dx)
                            // pager 页必须消费向右的位移，否则其会在下层继续翻页
                            if (dx > 0f) change.consume()
                        }
                    }

                    if (dragging) {
                        // 松手仍使用 settleTarget / releaseTo 这一唯一入口，不另开 settle 协程
                        releaseTo(settleTarget(tracker.calculateVelocity().x, progress))
                    } else if (wasSettling && abs(progress - startProgress) < TAP_SLOP_PROGRESS) {
                        // 本次未接管（点击、纵向滑动或向左），按当前状态恢复此前让位的弹簧
                        requestSettle(if (currentOpen) 1f else 0f)
                    }
                }
            }
            .nestedScroll(connection)
            // 普通页的整屏横滑接管：挂载于 Main pass（叶 → 根），子节点先取得位移。附件行、
            // 多图气泡、pager、纵向列表一旦 consume，此处立即整段让出；纵向位移及贴边后继续
            // 向外的位移完全不予消费（见文件顶部阶段说明与 pullBy）。
            .pointerInput(panelPx, gestureEnabled) {
                if (!gestureEnabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    // 按下时将 progress 交由手指、未拖拽时再恢复弹簧，由上方 Initial 观察者负责
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var engaged = false
                    var sumX = 0f
                    var sumY = 0f

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) break
                        val dx = change.position.x - change.previousPosition.x

                        if (!engaged) {
                            // 子节点已消费该段位移：本次手势归其处理，本层完全不予处理
                            if (change.isConsumed) break
                            sumX += dx
                            sumY += change.position.y - change.previousPosition.y
                            // 纵向位移不属于本层，立即交还
                            if (abs(sumY) > slop && abs(sumY) >= abs(sumX)) break
                            if (abs(sumX) <= slop) continue
                            engaged = true
                            // 超过 slop 的该帧将此前整段位移一次性补入，否则该段滑动不会生效
                            if (pullBy(sumX)) change.consume()
                        } else if (dx != 0f) {
                            if (pullBy(dx)) change.consume()
                        }
                    }

                    // 未接管（点击、纵向滑动或被节点消费）时不执行任何操作，弹簧由观察者恢复
                    if (engaged) {
                        // 松手仍使用 settleTarget / releaseTo 这一唯一入口，不另开 settle 协程
                        val velocity = tracker.calculateVelocity().x
                        // 关闭态被快速甩向左：位移可能未达阈值，速度足够即直接判定为一次左滑
                        if (progress <= 0f && velocity < -FLING_VELOCITY) fireLeftSwipe()
                        releaseTo(settleTarget(velocity, progress))
                    }
                }
            }
    ) {
        // 页面层整体右移，抽屉同步从左侧补入等宽区域，两者拼缝均位于 panelPx * progress
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = panelPx * progress }
        ) {
            content()
        }

        // 面板：跟手位移，铺设至画面左侧之外；位移仅修改图层，不参与重排
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = -panelPx * (1f - progress) }
                .width(panelWidth)
                .fillMaxHeight()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize()
            ) {
                panel()
            }
        }

        // 打开时点击右侧空白区域收起（不做压黑，仅设置点击区域）
        if (scrimVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = panelWidth)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { currentOnOpenChange(false) }
            )
        }
    }
}
