package com.zmf.agent.ui.chat

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
import com.zmf.agent.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * 会话里点开图片后的全屏查看：一条消息里的图片按发送顺序组成一组，左右横滑就在这组里翻页，
 * 其它类型的文件不参与，所以翻页永远只在图片之间走。
 *
 * 横滑必须在这一层自己消费：聊天页的分页器会把手势当成「左滑进轨迹」，
 * 只有这里先把位置变化 consume 掉，父层才收不到这次滑动。
 *
 * 入场从 0.6 倍长到原尺寸，退场缩回后再回调 onDismiss，父层不必再叠动画。
 * 磨砂来自父层对会话内容的模糊，这里只叠半透明底与上下渐变压暗，保证图片本身清晰。
 */
@Composable
fun ImageViewer(
    paths: List<String>,
    startIndex: Int,
    root: File,
    onDismiss: () -> Unit
) {
    // 索引在这里收口一次，父层传进来的越界值不会让下面的 paths[index] 抛异常
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
    // 横滑的跟手位移。相邻图不预渲染：一张原图的位图就是几十 MB，三张同时留着容易 OOM，
    // 所以翻页做成「这一张滑出去、下一张在中间出现」，滑完之前不换图。
    var dragX by remember { mutableFloatStateOf(0f) }

    val scope = rememberCoroutineScope()
    val appearScale = remember { Animatable(0.6f) }
    val appearAlpha = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    // 刚度沿用 Motion.spring()，只把阻尼拉满到 1，入场顺滑不回弹
    val appearSpec = spring<Float>(dampingRatio = 1f, stiffness = 420f)
    val exitSpec = tween<Float>(durationMillis = 180)

    // 位移只有一个写入方：跟手时直接写 dragX，松手后交给一段 animate。
    // seed 用来丢掉被取消的协程可能已经排进下一帧的那次写入，避免手指和弹簧互相拉扯。
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var settleSeed by remember { mutableStateOf(0) }

    fun cancelSettle() {
        settleJob?.cancel()
        settleJob = null
        settleSeed++
    }

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

    fun closeNow() {
        if (closing) return
        closing = true
        cancelSettle()
        dragX = 0f
        scope.launch {
            // 缩回必须播完再回调，否则父层摘掉这一帧就看不到退场
            coroutineScope {
                launch { appearScale.animateTo(0.6f, exitSpec) }
                launch { appearAlpha.animateTo(0f, exitSpec) }
            }
            onDismiss()
        }
    }

    // 底子要能透出下面那一层被模糊掉的对话（那是「磨砂」的来源），所以压得比原来淡很多：
    // 之前 0.58/0.62 基本等于不透光，看着就是一块纯色板
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
            // 缩放与拖动挂在整块上：图片以外的留白被拖动时也由这里一并消费，竖向才漏不到父层
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
            // 排在最后 = 事件链最内层，Main 阶段比上面两个先拿到事件，才能抢先消费横滑
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
                        // 第二根手指落下是缩放：已经跟手的位移回弹掉，不当成翻页
                        if (event.changes.count { it.pressed } > 1) {
                            following = false
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!following) {
                            // 横向占优才接管翻页；竖向归缩放的 pan，它自己会消费，父层同样收不到
                            if (abs(dx) > slop || abs(dy) > slop) {
                                if (abs(dx) <= abs(dy)) break
                                // 接手前先掐掉上一段回弹，否则两处同时写位移会打架
                                cancelSettle()
                                following = true
                            }
                        }
                        if (following) {
                            change.consume()
                            // 到头不动：第一张再往右、最后一张再往左，画面都不跟手
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
                        // 快速轻扫也要能翻页，否则手指越快反而翻不动
                        val fast = abs(dx) / elapsed > 0.6f && abs(dx) > slop * 2
                        if (neighbor && (far || fast)) {
                            val target = if (dx < 0f) index + 1 else index - 1
                            val off = if (target > index) -size.width.toFloat() else size.width.toFloat()
                            // 滑出用定长不用弹簧：弹簧要 300ms 才停，换图的等待会明显发钝
                            settleTo(off, tween(160)) {
                                // 位移先回正再换图：新的一张第一帧就已经居中
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
                    // 只有图片上的点击被吞掉，留白上的点击照样关闭
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
                    // 必须排在 pointerInput 之后：拖动位移取的是屏幕像素，跟手才不会差一个缩放倍数
                    .graphicsLayer {
                        // 入场倍率乘在同一个变换上，拖动与双指缩放的口径不变
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
