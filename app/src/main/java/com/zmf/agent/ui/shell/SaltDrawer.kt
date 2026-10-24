package com.zmf.agent.ui.shell

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
import com.zmf.agent.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * progress 只有三个写入方，靠「谁在什么时候能写」互斥，不要再往里加第四个：
 *
 * 1. 手指 —— 根 Box 在 Main pass 上接管的位移，以及关闭态向左的累计位移，跟手阶段独占 progress；
 * 2. nestedScroll —— pager 消费后的越界量（onPostScroll）与松手速度（onPostFling），
 *    只在弹簧没跑的时候按「还差的行程」封顶地改 progress，弹簧一起来就整体让位；
 * 3. 弹簧 —— SettleSlot 里唯一的 animate 协程，只能由 requestSettle 启动；启动前先取消上一个，
 *    并用 epoch 丢弃被取消协程可能已排进下一帧的那次写入。
 *
 * 规矩：任何事件都不许自己 scope.launch 一段 animate。要么直接写 progress（跟手），
 * 要么调 requestSettle（松手 / open 变化）。以前手指和翻页两条路径各 settle 一次、又额外触发
 * LaunchedEffect(open) 里的 settle，两个弹簧从不同起点并发写同一个 progress，表现就是
 * 「过了阈值后平滑地变慢一下」——只出现在 pager 页，因为只有它两条路径都会走。
 * 手指按下先 cancelSettle 把 progress 交还给手指，抬手时若这次按下其实没拖拽就把弹簧补回来，
 * 这样既不会「弹簧和手指同时写」，也不会点一下空白就把正在开关的抽屉冻在半路。
 */

/*
 * 事件传递阶段决定「谁先拿到横向位移」，本文件两条接管路径分别挂在两个阶段上，别混用：
 *
 * - Initial 从根到叶：父层先看到事件。只有「必须抢在子节点之前」的路径才挂这里 ——
 *   interceptRightSwipe 那一段（pager 在 Main 上必然先吃掉位移，不抢就永远是两段式）。
 * - Main 从叶到根：附件行、多图气泡、pager、竖向列表这些子节点先拿到事件并 consume。
 *   普通页的整屏横滑接管挂在这里，一看到 isConsumed 就整段让出去，只有子节点没消费时才动 progress。
 */

// 普通页（Main pass 接管的松手）和 pager 页（nestedScroll 的 onPostFling）共用这一组判据，
// 两处各写一套就会漂移：速度超过 FLING_VELOCITY 直接甩开/甩回，否则按位置过 SETTLE_THRESHOLD 吸附。
private const val FLING_VELOCITY = 600f
private const val SETTLE_THRESHOLD = 0.35f

// 关闭态左滑的位移阈值按面板宽度取比例，换机型不用重新调数
private const val LEFT_SWIPE_TRAVEL_RATIO = 0.2f

// 判定「这一下只是点了点」的 progress 容差：小于它就认为没有任何拖拽接管过
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

    // 旧协程被取消时可能已经排好下一帧的写入，用代次把它挡掉
    var epoch = 0
}

/** 关闭态左滑的累计位移与「本次手势已触发」标记；普通类而不是 State，免得每帧触发重组。 */
private class LeftSwipeState {
    var travel = 0f
    var fired = false

    fun reset() {
        travel = 0f
        fired = false
    }
}

/**
 * Salt 那种侧边栏：不是「浮在页面上的一块卡片」，而是页面整体右移、抽屉从左侧同步滑进来，两块连体平移。
 *
 * - 主页任意位置向右拖都能拉开（关闭时），向左拖收起（打开时）；例外是内容区里自己能横向
 *   滚动的那些行（待发附件行、多图气泡），位移先归它们，滑到头才轮到抽屉
 * - 松手按位置/速度吸附，用的是统一弹簧，不是线性补间
 * - 页面层 translationX = panelWidth * progress，抽屉 translationX = -panelWidth * (1 - progress)，
 *   两者拼缝位置同为 panelWidth * progress，无外距 / 圆角 / 阴影 / 遮罩
 *
 * gestureEnabled = false 时整屏不再响应横向拖拽：全屏页面（设置、工作区等）在背后时，
 * 手指在页面上横滑不会把抽屉拽出来。
 *
 * 包管理 / 会话列表这种页面里是 HorizontalPager，横向拖拽会先被 pager 消费掉，Main pass 上的
 * 整屏接管等不到位移；所以根 Box 再挂一层 nestedScroll，把 pager 消费后仍然没处安放的
 * 横向越界量接过来转成抽屉位移（见下面 connection 的注释），不再需要左边缘那条手势条。
 *
 * onSwipeLeft 只服务「轨迹页」这类需要左滑返回的页面：抽屉关闭态继续向左滑越过阈值时回调一次。
 * 内容区自己消费掉的横滑（图片横滚、多图气泡、pager 翻页、竖向滚动）不会触发：Main pass 上
 * 子节点先消费，看到 isConsumed 这一层就整段让出去，而 onPostScroll 里本来就只剩越界量。
 *
 * interceptRightSwipe = true 只给「内容区是 HorizontalPager 且当前停在第 1 页」的页面用。
 * 上面那条 nestedScroll 只能接到 pager 吃完之后的越界量，于是第一段位移全被 pager 吸掉、抽屉
 * 纹丝不动，等 pager 到底才开始动——行程翻倍且明显两段。这个开关让根 Box 在 Initial pass 上
 * 抢在 pager 之前把「向右」的位移吃进 progress，从第一个像素起就跟手，全程一条连续手势；
 * 向左一个像素都不吃，翻页照旧。这是全文件唯一挂在 Initial 上的抢占：Initial 上父层先于所有
 * 子节点拿到事件，再挂一条就等于把那片区域的横向滚动全部废掉。
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

    val settleSlot = remember { SettleSlot() }
    val swipe = remember { LeftSwipeState() }

    // 上层每次重组都会给新的 lambda；收进 rememberUpdatedState 后，remember 里的 connection
    // 和 pointerInput 拿到的永远是当前那个，不必跟着 lambda 身份重建
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOpen by rememberUpdatedState(open)
    // nestedScroll 那条连接是 remember 出来的长生命周期对象，必须读「最新」的手势开关：
    // 收进 State 之后再访问，页面切换（计划页 → 主页）时它拿到的才是当前值
    val currentGestureEnabled by rememberUpdatedState(gestureEnabled)

    // 把 progress 交还给手指：手指按下或请求新目标时调用
    fun cancelSettle() {
        settleSlot.job?.cancel()
        settleSlot.job = null
        settleSlot.epoch++
    }

    // 弹簧的唯一入口。同一个目标正在吸附时直接复用：松手路径和 LaunchedEffect(open) 会先后
    // 各请求一次同一个 target，如果每次都「取消 + 重启」，弹簧速度被清零、轨迹重新起步，
    // 又变成用户看到的那一下顿挫。
    fun requestSettle(target: Float) {
        val running = settleSlot.job?.isActive == true
        if (running) {
            if (settleSlot.target == target) return
            settleSlot.job?.cancel()
        } else if (abs(progress - target) < 0.001f) {
            // 已经贴边就别空跑一遍弹簧（每次翻页都多播一帧没有意义）
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

    // 松手 / 翻页结束的唯一出口：状态交给 onOpenChange 驱动 LaunchedEffect(open) 收口，
    // 同时请求一次吸附，覆盖「状态没变、LaunchedEffect 不会重跑」的情况（关闭态拉到一半又松回去，
    // open 一直是 false）。同一个目标被重复请求由 requestSettle 去重，不会二次驱动。
    fun releaseTo(target: Float) {
        currentOnOpenChange(target == 1f)
        requestSettle(target)
    }

    // 关闭态向左拖过阈值算一次左滑；标记挡在同一段拖拽里重复回调
    fun fireLeftSwipe() {
        if (swipe.fired) return
        swipe.fired = true
        currentOnSwipeLeft?.invoke()
    }

    // 只统计「抽屉已合上、方向向左」的那部分位移：内容区自己吃掉的横滑既不会出现在
    // 本层的 delta 里（子节点在 Main pass 上先消费），也不会出现在 onPostScroll 的 available 里
    fun observeLeftSwipe(deltaPx: Float) {
        if (swipe.fired) return
        swipe.travel += deltaPx
        if (swipe.travel <= -LEFT_SWIPE_TRAVEL_RATIO * panelPx) fireLeftSwipe()
    }

    // 跟手阶段写 progress 的唯一入口（普通页与 interceptRightSwipe 页共用），返回这次位移
    // 是否真的推动了抽屉：已经贴到 0 还继续向左只喂左滑回调、progress 不动，返回 false，
    // 这段位移就不消费，留给轨迹页；贴到 1 同理。返回值决定要不要 consume。
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
        // 手指 / 翻页路径只改 open，真正的吸附统一在这里收口
        requestSettle(if (open) 1f else 0f)
        // 合上以后左滑要能再用一次
        if (!open) swipe.reset()
    }

    // pager 会把横向拖拽自己消费掉，根 Box 在 Main pass 上拿到的永远是 0 位移；nestedScroll 的
    // post 阶段拿到的正是「子节点消费完仍没处安放」的那部分余量，把它让给抽屉，pager 在第一页 /
    // 第二页、抽屉关闭态 / 打开态都不会再出现「横滑拉不出抽屉」的死区。
    val connection = remember(panelPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 这条连接只服务「子节点是 pager、横向拖拽被 pager 吃掉」的页面。
                // 手势关掉的页面（设置 / 计划 / 工作区…）一个字节都不接：以前它无条件挂着，
                // 结果计划页那条横向计划条慢慢推到边不会开抽屉，快速一甩却仍然进抽屉——
                // 因为甩动的余量走的是这里，绕过了外层的手势开关（2026-10-25 用户报的现象）。
                if (!currentGestureEnabled) return Offset.Zero
                // 弹簧正在收口时整体让位：手指/翻页再叠增量就是两个写入方互相拉扯
                if (settleSlot.job?.isActive == true) return Offset.Zero

                // 还没开满，右侧又有越界余量：往外拉。take 按「还差的行程」封顶，progress 不会越界。
                if (progress < 1f && available.x > 0f) {
                    val take = min(available.x, (1f - progress) * panelPx)
                    progress = (progress + take / panelPx).coerceIn(0f, 1f)
                    return Offset(take, 0f)
                }
                // 已经拉开，左侧又有越界余量：往回收。同理按「已经拉出的行程」封顶。
                if (progress > 0f && available.x < 0f) {
                    val take = max(available.x, -progress * panelPx)
                    progress = (progress + take / panelPx).coerceIn(0f, 1f)
                    return Offset(take, 0f)
                }
                // 抽屉已合上还继续往左滑：这里 progress 绝不能减成负数（面板会被推出画面），
                // 所以只累计给左滑回调，并且不消费这段余量，留给内容区自己的越界处理
                if (progress <= 0f && available.x < 0f) observeLeftSwipe(available.x)
                return Offset.Zero
            }

            // 松手（pager 自己的 fling 也会走到这里）：判据和手指路径同一个 settleTarget，
            // available.x 是 px/s，和 VelocityTracker 算出来的 velocity 同量纲；并把这部分速度全当已消费，
            // 否则抽屉已经决定开/关之后 pager 还会按残余速度再补翻一页。
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // 同 onPostScroll：手势关掉的页面不接受任何「子节点甩出来的余量」
                if (!currentGestureEnabled) return Velocity.Zero
                // 关闭态还被向左甩出去 = 左滑返回，先判它，别让它被下面的「吸附到 0」吃掉
                if (progress <= 0f && available.x < -FLING_VELOCITY) fireLeftSwipe()
                releaseTo(settleTarget(available.x, progress))
                return available
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 只观察、从不消费：pager 横滑、列表滚动、点击照旧由子节点处理。按下先复位左滑标记，
            // 并让弹簧让位；抬手时如果这次按下没有拖拽（只是点了一下），再把弹簧补回来。
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
            // pager 页专用：横滑在 Main pass 上先到 pager 手里，所以这条必须挂在 Initial pass，
            // 才能在 pager 之前把「向右」的位移接管掉。只吃向右，向左和竖向一律原样放行。
            .pointerInput(interceptRightSwipe) {
                if (!interceptRightSwipe) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // 已经拉开 / 正在拉开时交给 Main pass 那条整屏接管，两条同时写 progress 会互相拉扯
                    if (progress > 0f) return@awaitEachGesture

                    // 手指按下就把 progress 交还给手指，和上面那段 pointerInput 同一套规矩
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
                            // 竖向或向左都不是这条路径的事：一个事件都不消费，立刻还回去
                            if (abs(sumY) > slop && abs(sumY) >= abs(sumX)) break
                            if (sumX < -slop) break
                            if (sumX > slop) {
                                dragging = true
                                // 过 slop 的这一帧把前面整段位移一次补上，不然等于白滑一段
                                pullBy(sumX)
                                change.consume()
                            }
                        } else {
                            pullBy(dx)
                            // pager 页必须吃掉向右的位移，否则它会在底下接着翻页
                            if (dx > 0f) change.consume()
                        }
                    }

                    if (dragging) {
                        // 松手仍走 settleTarget / releaseTo 这唯一入口，不另开 settle 协程
                        releaseTo(settleTarget(tracker.calculateVelocity().x, progress))
                    } else if (wasSettling && abs(progress - startProgress) < TAP_SLOP_PROGRESS) {
                        // 这一下没接管（点了一下 / 竖滑 / 向左），把刚才让位的弹簧按当前状态补回来
                        requestSettle(if (currentOpen) 1f else 0f)
                    }
                }
            }
            .nestedScroll(connection)
            // 普通页的整屏横滑接管：挂在 Main pass（叶 → 根）上，子节点先拿位移。附件行、
            // 多图气泡、pager、竖向列表一旦 consume，这里立刻整段让出去；竖向、以及贴边后继续
            // 向外的位移一个字节都不消费（见文件顶部阶段说明与 pullBy）。
            .pointerInput(panelPx, gestureEnabled) {
                if (!gestureEnabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    // 按下时把 progress 交给手指、没拖拽再补回弹簧，由上面那条 Initial 观察者负责
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
                            // 子节点已经吃掉这段位移：这一下归它，本层一个字节都不碰
                            if (change.isConsumed) break
                            sumX += dx
                            sumY += change.position.y - change.previousPosition.y
                            // 竖向不是本层的事，立刻还回去
                            if (abs(sumY) > slop && abs(sumY) >= abs(sumX)) break
                            if (abs(sumX) <= slop) continue
                            engaged = true
                            // 过 slop 的这一帧把前面整段位移一次补上，不然等于白滑一段
                            if (pullBy(sumX)) change.consume()
                        } else if (dx != 0f) {
                            if (pullBy(dx)) change.consume()
                        }
                    }

                    // 没接管（点了 / 竖滑 / 被子节点消费）就什么都不做，弹簧由观察者补回
                    if (engaged) {
                        // 松手仍走 settleTarget / releaseTo 这唯一入口，不另开 settle 协程
                        val velocity = tracker.calculateVelocity().x
                        // 关闭态被快速甩向左：位移可能没过阈值，速度够就直接算一次左滑
                        if (progress <= 0f && velocity < -FLING_VELOCITY) fireLeftSwipe()
                        releaseTo(settleTarget(velocity, progress))
                    }
                }
            }
    ) {
        // 页面层整体右移，抽屉同步从左侧补进等宽的缝，二者拼缝都落在 panelPx * progress
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = panelPx * progress }
        ) {
            content()
        }

        // 面板：跟手位移，铺到画面左侧之外；位移只改图层，不参与重排
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

        // 打开时右侧空白区域点一下收起（不做压黑，只做点击区域）
        if (progress > 0.02f) {
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
