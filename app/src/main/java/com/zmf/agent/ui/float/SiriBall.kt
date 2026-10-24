package com.zmf.agent.ui.float

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 慢旋转一圈：30s（Operit 的节奏偏快，我们放慢一档，不盯着看注意不到）。 */
private const val SPIN_MS = 30_000

/** 呼吸一次：4.5s，半径只在 0.975~1.00 之间轻轻起伏。 */
private const val BREATH_MS = 4_500

/** 单道涟漪的生命：700ms。 */
private const val RIPPLE_MS = 700

/** 两道涟漪错开的间隔：0 / 1100ms（少一道、间隔更长，存在感更低）。 */
private const val RIPPLE_STEP_MS = 1_100

/** 涟漪循环周期 = 2 道 × 1100ms + 700ms 余量。 */
private const val RIPPLE_CYCLE_MS = 3_600

/** 按压缩放：0.85，200ms FastOutSlowIn。 */
private const val PRESS_SCALE = 0.85f
private const val PRESS_MS = 200

/** Operit 球体的四个色块（蓝 / 紫 / 粉 / 青）。 */
private val BallColors = listOf(
    Color(0xFF0A84FF),
    Color(0xFFBF5AF2),
    Color(0xFFFF375F),
    Color(0xFF00D4FF)
)

/**
 * 会话悬浮球（纯 Canvas 画的彩色玻璃球）。
 *
 * 为什么不用 TextView + GradientDrawable：球体「透出背景」靠的是多层
 * radialGradient 用 BlendMode.Screen 叠出来的玻璃质感，静态 drawable 做不到。
 *
 * 调用方给的窗口是 60dp 的精确像素，所以这里所有内容都画在 60dp 自己的画布内：
 * 光晕、涟漪全部靠画布里的渐变实现，**不靠放大 View**（外层窗口会直接裁掉）。
 *
 * 什么时候动：球**看得见**就跑完整动效，看不见（息屏 / 折叠成小窗 / 窗口被系统藏起来）
 * 就一次帧都不请求。动效本身一个字没改 —— 30s 自转、4.5s 呼吸、2 道 3.6s 涟漪，
 * 用户盯着球看时与改动前逐帧一致，变的只是「还要不要继续跑」。
 *
 * @param pressed 按压态（由服务在 ACTION_DOWN / MOVE / UP 里派发），按下时整体缩到 0.85
 * @param visible 球此刻是否真的看得见（服务给：屏幕亮着 + 窗口可见 + 处于球态）。
 *                false 时**连 InfiniteTransition 都不注册**，一帧都不算。
 * @param onClick 点一下球（服务那一层是触摸派发的主力，这个回调留给「直接点球」的路径）
 */
@Composable
fun SiriBall(pressed: Boolean, visible: Boolean, onClick: () -> Unit) {
    // 按压缩放只动内容层，窗口尺寸不动，所以缩到 0.85 不会超出 60dp 被裁
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = tween(durationMillis = PRESS_MS, easing = FastOutSlowInEasing),
        label = "siri-ball-press"
    )

    // 动效：慢旋转 + 呼吸 + 涟漪，全部由同一个 InfiniteTransition 驱动。
    // 只有 visible 时才注册它 —— 它只要在，就会一直请求帧、一直重绘整块 Canvas。
    // 看不见的时候（息屏 / 折叠成小窗 / 窗口被系统藏起来）这些帧没人看得见，纯功耗。
    // 恢复可见时是新 remember 出来的 transition，时钟从 0 重新开始 —— 所以第一帧就是正常
    // 相位（0° / 满半径 / 涟漪空档），不会接在「上次停在哪」那一帧上。
    val clock = if (visible) rememberInfiniteTransition(label = "siri-ball") else null
    val spinState = clock?.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = SPIN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "siri-ball-spin"
    )
    val breathState = clock?.animateFloat(
        initialValue = 0.975f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = BREATH_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "siri-ball-breath"
    )
    val rippleState = clock?.animateFloat(
        initialValue = 0f,
        targetValue = RIPPLE_CYCLE_MS.toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = RIPPLE_CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "siri-ball-ripple"
    )

    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 三个动效值故意留到 draw 阶段才读：以前在组合期读 .value，球每帧都会重组一次、
            // 连带重走一遍布局，而画面其实只需要重绘这张 52dp 画布。
            // 挪进 draw 后每帧只失效 draw phase，像素结果完全一样（同样的值、同样的帧）。
            // clock 为 null（不可见）时用动画起点的常量画一帧 —— 反正没人看得见。
            drawSiriBall(
                spin = spinState?.value ?: 0f,
                breath = breathState?.value ?: 1f,
                rippleClock = rippleState?.value ?: RIPPLE_CYCLE_MS.toFloat()
            )
        }
    }
}

/** 画一帧球体：玻璃底 → 4 个 Screen 混合的色块 → 3 道涟漪 → 边缘高光。 */
private fun DrawScope.drawSiriBall(spin: Float, breath: Float, rippleClock: Float) {
    val maxRadius = size.minDimension / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    // 球体半径留 14% 余量给涟漪与自转的偏心色块，保证任何一帧都不出画布
    val ballRadius = maxRadius * 0.80f * breath

    // 1) 玻璃底：极淡的白，纯黑/纯白壁纸上都能看出球的轮廓
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x22FFFFFF), Color(0x0DFFFFFF), Color(0x00FFFFFF)),
            center = center,
            radius = ballRadius
        ),
        radius = ballRadius,
        center = center
    )

    // 2) 四个径向渐变色块：各自在半径 26% 的轨道上绕中心慢转
    //    BlendMode.Screen 让它们互相「加亮」而不是互相盖住 —— 玻璃珠的层次全在这一步
    val blobRadius = ballRadius * 0.78f
    val orbit = ballRadius * 0.26f
    BallColors.forEachIndexed { index, color ->
        // 每个色块错开 90°，跟着 spin 一起转
        val angle = (spin + index * 90f) * (PI.toFloat() / 180f)
        val blobCenter = Offset(
            x = center.x + cos(angle) * orbit,
            y = center.y + sin(angle) * orbit
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.70f),
                    color.copy(alpha = 0.26f),
                    Color.Transparent
                ),
                center = blobCenter,
                radius = blobRadius
            ),
            radius = blobRadius,
            center = blobCenter,
            blendMode = BlendMode.Screen
        )
    }

    // 3) 三道涟漪：错开 0 / 833 / 1666ms，每道 500ms，从球面扩散到画布边缘
    for (index in 0 until 2) {
        var elapsed = rippleClock - index * RIPPLE_STEP_MS
        if (elapsed < 0f) elapsed += RIPPLE_CYCLE_MS
        if (elapsed > RIPPLE_MS) continue
        val progress = elapsed / RIPPLE_MS
        val radius = ballRadius * 0.95f + (maxRadius * 0.99f - ballRadius * 0.95f) * progress
        drawCircle(
            color = Color.White.copy(alpha = (1f - progress) * 0.10f),
            radius = radius,
            center = center,
            style = Stroke(width = (1f - progress) * 1.1f * density + 0.4f),
            blendMode = BlendMode.Screen
        )
    }

    // 4) 左上方一点柔和高光（不带整圈白色描边：那圈白线在壁纸上太跳，用户点名去掉）
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
            center = Offset(center.x - ballRadius * 0.34f, center.y - ballRadius * 0.38f),
            radius = ballRadius * 0.45f
        ),
        radius = ballRadius * 0.45f,
        center = Offset(center.x - ballRadius * 0.34f, center.y - ballRadius * 0.38f),
        blendMode = BlendMode.Screen
    )
}
