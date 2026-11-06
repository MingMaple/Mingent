package com.zmf.agent.feature.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.common.decodeSampledBitmap
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * 会话中点开图片后的全屏查看：一条消息中的图片按发送顺序组成一组，左右横滑即在该组中翻页，
 * 其他类型文件不参与，因此翻页始终仅在图片之间进行。
 *
 * 横滑必须在该层自行消费：聊天页的分页器会将手势识别为「左滑进入轨迹」，
 * 仅此处先 consume 位置变化，父层才不会收到本次滑动。
 *
 * 动画口径参照 PiliPlus 的图片查看流程：
 * - 入场从被点缩略图的矩形放大到全屏；退场沿同一路径缩回，父层无需再叠加动画；
 * - 双击以落点为锚，在 1 倍与 2 倍之间平滑切换；双指缩放限制在 1–4 倍并夹住平移边界；
 * - 未放大时向下拖动即跟手收缩并淡出，松手超过阈值或快速下扫则关闭，否则回弹；
 * - 底色仍为半透明遮罩，透出下层被模糊的会话内容，不使用纯黑底。
 */
@Composable
fun ImageViewer(
    paths: List<String>,
    startIndex: Int,
    root: File,
    origin: Rect? = null,
    onDismiss: () -> Unit
) {
    // 索引在此收口一次，父层传入的越界值不会使下方 paths[index] 抛出异常
    var index by remember(paths, startIndex) {
        mutableStateOf(startIndex.coerceIn(0, (paths.size - 1).coerceAtLeast(0)))
    }
    val currentPath = paths.getOrNull(index)
    // 文件头只读取尺寸，用于入场矩形与宽高比；像素数据在 IO 线程按视口能力采样解码，避免整幅原图进入内存。
    val aspect = (remember(currentPath) {
        currentPath?.let { path ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(File(root, path).absolutePath, bounds) }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                bounds.outWidth.toFloat() / bounds.outHeight.toFloat()
            } else {
                1f
            }
        }
    }) ?: 1f
    var decodeDone by remember(currentPath) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, currentPath) {
        value = null
        decodeDone = false
        val path = currentPath ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { decodeSampledBitmap(File(root, path).absolutePath, MAX_VIEWER_EDGE) }.getOrNull()
        }
        decodeDone = true
    }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    // 原图按屏幕宽度等比缩放后的显示尺寸；缩略图起止点与平移边界都以此为准
    val fitSize = remember(viewport, aspect) {
        if (viewport.width <= 0 || viewport.height <= 0) {
            Size.Zero
        } else {
            val vw = viewport.width.toFloat()
            val vh = viewport.height.toFloat()
            if (vw / vh > aspect) Size(vh * aspect, vh) else Size(vw, vw / aspect)
        }
    }
    val viewportCenter = rootOrigin + Offset(viewport.width / 2f, viewport.height / 2f)
    // 翻到其他图片后不再有对应缩略图，退场退回居中缩放
    val activeOrigin = origin?.takeIf { index == startIndex }
    val originScale = if (activeOrigin != null && fitSize.width > 0f) {
        activeOrigin.width / fitSize.width
    } else {
        0.6f
    }
    val originTranslation = if (activeOrigin != null) {
        activeOrigin.center - viewportCenter
    } else {
        Offset.Zero
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // 横滑的跟手位移。相邻图片不预渲染：一张原图的位图即数十 MB，三张同时保留易触发 OOM，
    // 因此翻页实现为「当前张滑出、下一张在中间出现」，滑动完成前不切换图片。
    var dragX by remember { mutableFloatStateOf(0f) }
    // 下滑关闭的跟手位移（仅向下，单位像素）
    var dragY by remember { mutableFloatStateOf(0f) }
    var imageNodeSize by remember { mutableStateOf(IntSize.Zero) }

    val scope = rememberCoroutineScope()
    val open = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    // 手势导致的动画需要可被新手势打断：每次开始手势都递增编号，旧协程的写入随即作废
    var animationToken by remember { mutableIntStateOf(0) }
    var dragJob by remember { mutableStateOf<Job?>(null) }

    fun cancelAnimation() {
        animationToken++
        dragJob?.cancel()
        dragJob = null
    }

    /** 平滑改变缩放与平移；两者同时动画，视觉上是一次连续的变焦。 */
    fun animateTransform(targetScale: Float, targetOffset: Offset) {
        cancelAnimation()
        val token = animationToken
        val spec = spring<Float>(dampingRatio = 1f, stiffness = 420f)
        scope.launch {
            animate(scale, targetScale, animationSpec = spec) { value, _ ->
                if (token == animationToken) scale = value
            }
        }
        scope.launch {
            animate(offset.x, targetOffset.x, animationSpec = spec) { value, _ ->
                if (token == animationToken) offset = Offset(value, offset.y)
            }
        }
        scope.launch {
            animate(offset.y, targetOffset.y, animationSpec = spec) { value, _ ->
                if (token == animationToken) offset = Offset(offset.x, value)
            }
        }
    }

    fun animateDragY(target: Float, then: () -> Unit = {}) {
        dragJob?.cancel()
        val token = animationToken
        dragJob = scope.launch {
            animate(dragY, target, animationSpec = Motion.spring<Float>()) { value, _ ->
                if (token == animationToken) dragY = value
            }
            if (token == animationToken) {
                dragY = target
                dragJob = null
                then()
            }
        }
    }

    /** 关闭查看器：缩放、平移、下滑位移回到初始值，同时沿缩略图路径退场。 */
    fun closeNow() {
        if (closing) return
        closing = true
        cancelAnimation()
        dragX = 0f
        val token = animationToken
        scope.launch {
            coroutineScope {
                launch { open.animateTo(0f, tween<Float>(durationMillis = 220)) }
                launch {
                    animate(dragY, 0f, animationSpec = tween<Float>(durationMillis = 220)) { value, _ ->
                        if (token == animationToken) dragY = value
                    }
                }
                launch {
                    animate(scale, 1f, animationSpec = tween<Float>(durationMillis = 160)) { value, _ ->
                        if (token == animationToken) scale = value
                    }
                }
                launch {
                    animate(offset.x, 0f, animationSpec = tween<Float>(durationMillis = 160)) { value, _ ->
                        if (token == animationToken) offset = Offset(value, offset.y)
                    }
                }
                launch {
                    animate(offset.y, 0f, animationSpec = tween<Float>(durationMillis = 160)) { value, _ ->
                        if (token == animationToken) offset = Offset(offset.x, value)
                    }
                }
            }
            onDismiss()
        }
    }

    // 系统返回与单击图片走同一条退场动画：若由外层直接把 viewer 置空，回缩略图的过程会被跳过
    BackHandler(enabled = !closing) { closeNow() }

    LaunchedEffect(viewport, fitSize) {
        if (viewport.width > 0 && viewport.height > 0 && open.value < 1f) {
            open.animateTo(1f, spring<Float>(dampingRatio = 1f, stiffness = 380f))
        }
    }

    /** 缩放到 s 时允许的最大平移量：图片边缘不得离开屏幕。 */
    fun clampOffset(value: Offset, s: Float): Offset {
        val maxX = ((fitSize.width * s - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((fitSize.height * s - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(
            value.x.coerceIn(-maxX, maxX),
            value.y.coerceIn(-maxY, maxY)
        )
    }

    // 底色需透出下层被模糊的对话（磨砂效果来源），因此比原先更淡
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
    // 下滑越多，遮罩越淡、图片略缩：松手前的反馈与 PiliPlus 一致
    val dragProgress = if (viewport.height > 0) {
        (dragY / viewport.height).coerceIn(0f, 1f)
    } else {
        0f
    }
    val backgroundAlpha = (open.value * (1f - dragProgress * 1.6f)).coerceIn(0f, 1f)
    val dismissScale = 1f - dragProgress * 0.12f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInRoot() }
            .onSizeChanged { viewport = it }
            .pointerInput(viewport, fitSize) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (closing || fitSize.width <= 0f) return@detectTransformGestures
                    cancelAnimation()
                    val nextScale = (scale * zoom).coerceIn(1f, 4f)
                    // 以双指中心为锚缩放：锚点下的像素在缩放前后保持不动
                    val focal = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
                    val nextOffset = focal - (focal - offset) * (nextScale / scale) + pan
                    scale = nextScale
                    offset = clampOffset(nextOffset, nextScale)
                }
            }
            // 排在最后 = 事件链最内层，Main 阶段早于上述处理器取得事件，方可优先消费横滑与下滑
            .pointerInput(paths, viewport) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 放大后拖动是移动图片，翻页与下滑关闭均让位
                    if (scale > 1f || closing) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    var mode = 0 // 0 = 未判定，1 = 翻页，2 = 下滑关闭
                    var dx = 0f
                    var dy = 0f
                    var upAt = down.uptimeMillis
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        // 第二根手指落下为缩放：已跟手的位移回弹，不再作为翻页或关闭处理
                        if (event.changes.count { it.pressed } > 1) {
                            mode = 0
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        dx = change.position.x - down.position.x
                        dy = change.position.y - down.position.y
                        if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                            mode = if (abs(dy) > abs(dx)) 2 else 1
                            cancelAnimation()
                        }
                        if (mode == 1) {
                            change.consume()
                            // 到达尽头不移动：第一张继续向右、最后一张继续向左时，画面均不跟手
                            val neighbor = when {
                                dx < 0f -> index < paths.lastIndex
                                dx > 0f -> index > 0
                                else -> false
                            }
                            dragX = if (neighbor) dx else 0f
                        } else if (mode == 2) {
                            change.consume()
                            dragY = dy.coerceAtLeast(0f)
                        }
                        if (!change.pressed) {
                            upAt = change.uptimeMillis
                            break
                        }
                    }
                    if (mode == 1) {
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
                            cancelAnimation()
                            val token = animationToken
                            dragJob = scope.launch {
                                animate(dragX, off, animationSpec = tween<Float>(durationMillis = 160)) { value, _ ->
                                    if (token == animationToken) dragX = value
                                }
                                if (token == animationToken) {
                                    dragX = 0f
                                    scale = 1f
                                    offset = Offset.Zero
                                    index = target
                                }
                            }
                        } else if (dragX != 0f) {
                            animateDragY(0f)
                            val token = animationToken
                            dragJob = scope.launch {
                                animate(dragX, 0f, animationSpec = spring<Float>()) { value, _ ->
                                    if (token == animationToken) dragX = value
                                }
                            }
                        }
                    } else if (mode == 2) {
                        val elapsed = (upAt - down.uptimeMillis).coerceAtLeast(1L)
                        val fast = dragY / elapsed > 0.9f && dragY > slop * 2
                        if (dragY > viewport.height * 0.16f || fast) {
                            closeNow()
                        } else {
                            animateDragY(0f)
                        }
                    } else if (dragX != 0f || dragY != 0f) {
                        dragX = 0f
                        animateDragY(0f)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // 遮罩独立成层：只淡出它，图片本身保持不透明，缩略图起止动画才不会出现半透明重影
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = backgroundAlpha }
                .background(scrim)
                .background(edgeShade)
                .background(vignette)
                .pointerInput(Unit) { detectTapGestures { closeNow() } }
        )

        val shown = bitmap
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    // 按位图比例收缩到实际画面：图片以外的留白才会落到外层的关闭点击上
                    .aspectRatio(aspect)
                    .onSizeChanged { imageNodeSize = it }
                    // 仅图片上的点击被消费，留白上的点击仍触发关闭
                    .pointerInput(Unit) {
                        detectTapGestures(
                            // 单击图片即关闭：与遮罩点击、系统返回共用同一条退场动画
                            onTap = { closeNow() },
                            onDoubleTap = { position ->
                                if (closing) return@detectTapGestures
                                val focal = position - Offset(
                                    imageNodeSize.width / 2f,
                                    imageNodeSize.height / 2f
                                )
                                if (scale > 1.01f) {
                                    animateTransform(1f, Offset.Zero)
                                } else {
                                    val targetScale = 2f
                                    val targetOffset = clampOffset(
                                        focal - (focal - offset) * (targetScale / scale),
                                        targetScale
                                    )
                                    animateTransform(targetScale, targetOffset)
                                }
                            }
                        )
                    }
                    // 必须排在 pointerInput 之后：拖动位移取屏幕像素，跟手时不会相差一个缩放倍数
                    .graphicsLayer {
                        val entrance = 1f + (originScale - 1f) * (1f - open.value)
                        scaleX = scale * entrance * dismissScale
                        scaleY = scale * entrance * dismissScale
                        translationX = offset.x + dragX + originTranslation.x * (1f - open.value)
                        translationY = offset.y + dragY + originTranslation.y * (1f - open.value)
                    }
            )
        } else if (decodeDone) {
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
                    .graphicsLayer { alpha = open.value }
            )
        }
    }
}

/** 查看器按最长边 2560 像素采样，兼顾四倍缩放清晰度与内存占用。 */
private const val MAX_VIEWER_EDGE = 2560
