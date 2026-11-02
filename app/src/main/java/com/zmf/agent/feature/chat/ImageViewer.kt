package com.zmf.agent.feature.chat

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * 会话中点开图片后的全屏查看：一条消息中的图片按发送顺序组成一组，左右横滑即在该组中翻页，
 * 其他类型文件不参与，因此翻页始终仅在图片之间进行。
 *
 * 横滑必须在该层自行消费：聊天页的分页器会将手势识别为「左滑进入轨迹」，
 * 仅此处先 consume 位置变化，父层才不会收到本次滑动。
 *
 * 入场从 0.6 倍放大至原尺寸，退场缩回后再回调 onDismiss，父层无需再叠加动画。
 * 磨砂效果来自父层对会话内容的模糊，此处仅叠加半透明底与上下渐变压暗，保证图片本身清晰。
 */
@Composable
fun ImageViewer(
    paths: List<String>,
    startIndex: Int,
    root: File,
    onDismiss: () -> Unit
) {
    // 索引在此收口一次，父层传入的越界值不会使下方 paths[index] 抛出异常
    var index by remember(paths, startIndex) {
        mutableStateOf(startIndex.coerceIn(0, (paths.size - 1).coerceAtLeast(0)))
    }
    val currentPath = paths.getOrNull(index)
    val bitmap = remember(currentPath) {
        currentPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(File(root, path).absolutePath) }.getOrNull()
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // 横滑的跟手位移。相邻图片不预渲染：一张原图的位图即数十 MB，三张同时保留易触发 OOM，
    // 因此翻页实现为「当前张滑出、下一张在中间出现」，滑动完成前不切换图片。
    var dragX by remember { mutableFloatStateOf(0f) }

    val scope = rememberCoroutineScope()
    val appearScale = remember { Animatable(0.6f) }
    val appearAlpha = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    // 刚度沿用 Motion.spring()，仅将阻尼调至 1，入场顺滑且不回弹
    val appearSpec = spring<Float>(dampingRatio = 1f, stiffness = 420f)
    val exitSpec = tween<Float>(durationMillis = 180)

    // 位移仅有一个写入方：跟手时直接写 dragX，松手后交由一段 animate 处理。
    // seed 用于丢弃已取消协程可能排入下一帧的写入，避免手指与弹簧相互干扰。
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var settleSeed by remember { mutableStateOf(0) }

    /** 终止正在运行的归位动画；其已排入下一帧的写入会被 seed 校验丢弃。 */
    fun cancelSettle() {
        settleJob?.cancel()
        settleJob = null
        settleSeed++
    }

    /** 把 dragX 动画到 target，收尾执行 then；中途被 cancelSettle 打断则整段作废。 */
    fun settleTo(target: Float, spec: FiniteAnimationSpec<Float>, then: () -> Unit = {}) {
        settleJob?.cancel()
        settleSeed++
        val seed = settleSeed
        settleJob = scope.launch {
            animate(dragX, target, animationSpec = spec) { value, _ ->
                if (seed == settleSeed) dragX = value
            }
            if (seed == settleSeed) {
                dragX = target
                settleJob = null
                then()
            }
        }
    }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { appearScale.animateTo(1f, appearSpec) }
            launch { appearAlpha.animateTo(1f, appearSpec) }
        }
    }

    /** 关闭查看器：播完退场动画再回调 onDismiss；重复调用只生效一次。 */
    fun closeNow() {
        if (closing) return
        closing = true
        cancelSettle()
        dragX = 0f
        scope.launch {
            // 缩回动画必须播放完毕后再回调，否则父层移除该帧后无法看到退场
            coroutineScope {
                launch { appearScale.animateTo(0.6f, exitSpec) }
                launch { appearAlpha.animateTo(0f, exitSpec) }
            }
            onDismiss()
        }
    }

    // 底色需透出下层被模糊的对话（磨砂效果来源），因此比原先更淡：
    // 此前 0.58/0.62 几乎不透明，呈现为纯色板
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val scrim = if (isDark) Color.Black.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.34f)
    val edgeShade = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.12f),
        0.22f to Color.Transparent,
        0.78f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.12f)
    )
    val vignette = Brush.radialGradient(
        0f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.10f)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appearAlpha.value }
            .background(scrim)
            .background(edgeShade)
            .background(vignette)
            .pointerInput(Unit) { detectTapGestures { closeNow() } }
            // 缩放与拖动挂载于整块：图片以外留白被拖动时也由此消费，竖向事件不会漏至父层
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale == 1f) {
                        offsetX = 0f
                        offsetY = 0f
                    } else {
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
            }
            // 排在最后 = 事件链最内层，Main 阶段早于上述两个处理器取得事件，方可优先消费横滑
            .pointerInput(paths) {
                if (paths.size < 2) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 放大后横滑是拖图，翻页让位
                    if (scale > 1f) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    var following = false
                    var dx = 0f
                    var upAt = down.uptimeMillis
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        // 第二根手指落下为缩放：已跟手的位移回弹，不作为翻页处理
                        if (event.changes.count { it.pressed } > 1) {
                            following = false
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!following) {
                            // 横向占优才接管翻页；竖向归缩放的 pan，其自行消费，父层同样收不到
                            if (abs(dx) > slop || abs(dy) > slop) {
                                if (abs(dx) <= abs(dy)) break
                                // 接手前先终止上一段回弹，否则两处同时写入位移会发生冲突
                                cancelSettle()
                                following = true
                            }
                        }
                        if (following) {
                            change.consume()
                            // 到达尽头不移动：第一张继续向右、最后一张继续向左时，画面均不跟手
                            val neighbor = when {
                                dx < 0f -> index < paths.lastIndex
                                dx > 0f -> index > 0
                                else -> false
                            }
                            dragX = if (neighbor) dx else 0f
                        }
                        if (!change.pressed) {
                            upAt = change.uptimeMillis
                            break
                        }
                    }
                    if (following) {
                        val neighbor = when {
                            dx < 0f -> index < paths.lastIndex
                            dx > 0f -> index > 0
                            else -> false
                        }
                        val elapsed = (upAt - down.uptimeMillis).coerceAtLeast(1L)
                        val far = abs(dx) > size.width * 0.2f
                        // 快速轻扫也需支持翻页，否则滑动越快反而无法翻页
                        val fast = abs(dx) / elapsed > 0.6f && abs(dx) > slop * 2
                        if (neighbor && (far || fast)) {
                            val target = if (dx < 0f) index + 1 else index - 1
                            val off = if (target > index) -size.width.toFloat() else size.width.toFloat()
                            // 滑出使用定长而非弹簧：弹簧需 300ms 停止，换图等待会明显迟滞
                            settleTo(off, tween(160)) {
                                // 位移先回正再换图：新图第一帧即居中
                                dragX = 0f
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                                index = target
                            }
                        } else {
                            settleTo(0f, Motion.spring())
                        }
                    } else if (dragX != 0f) {
                        settleTo(0f, Motion.spring())
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    // 按位图比例收缩到实际画面：图片以外的留白才会落到外层的关闭点击上
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                    // 仅图片上的点击被消费，留白上的点击仍触发关闭
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { },
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = 2f
                                }
                            }
                        )
                    }
                    // 必须排在 pointerInput 之后：拖动位移取屏幕像素，跟手时不会相差一个缩放倍数
                    .graphicsLayer {
                        // 入场倍率乘于同一变换上，拖动与双指缩放的比例不变
                        scaleX = scale * appearScale.value
                        scaleY = scale * appearScale.value
                        translationX = offsetX + dragX
                        translationY = offsetY
                    }
            )
        } else {
            Text(
                text = "无法打开图片",
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // 只有多张时才需要知道「现在第几张」
        if (paths.size > 1) {
            Text(
                text = "${index + 1} / ${paths.size}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp)
                    .graphicsLayer { alpha = appearAlpha.value }
            )
        }
    }
}
