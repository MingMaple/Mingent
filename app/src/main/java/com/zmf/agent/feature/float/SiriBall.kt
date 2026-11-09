package com.zmf.agent.feature.float

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

/** 慢速旋转一圈：30 秒（参考实现节奏偏快，放慢节奏以降低视觉干扰）。 */
private const val SPIN_MS = 30_000

/** 呼吸周期：4.5 秒，半径仅在 0.975 至 1.00 之间轻微变化。 */
private const val BREATH_MS = 4_500

/** 单道涟漪的持续时间：700ms。 */
private const val RIPPLE_MS = 700

/** 两道涟漪的错开间隔：0 / 1100ms（数量更少、间隔更长，存在感更低）。 */
private const val RIPPLE_STEP_MS = 1_100

/** 涟漪循环周期 = 2 道 × 1100ms + 700ms 余量。 */
private const val RIPPLE_CYCLE_MS = 3_600

/** 按压缩放：0.85，200ms FastOutSlowIn。 */
private const val PRESS_SCALE = 0.85f
private const val PRESS_MS = 200

/**
 * 球体的四个色块：均取自强调蓝同一色族（主蓝、青、亮蓝、深蓝）。
 *
 * 球体是唯一允许使用同色族多档颜色的装饰元素，作为品牌符号而非界面控件。
 * 但四种颜色必须与全局强调蓝同族，否则球悬浮于页面上时会与按钮的蓝色产生冲突。
 */
private val BallColors = listOf(
    Color(0xFF4176E6),
    Color(0xFF2FB3C9),
    Color(0xFF7AAAFF),
    Color(0xFF1B6FD0)
)

/**
 * 会话悬浮球（纯 Canvas 绘制的彩色玻璃球）。
 *
 * 不使用 TextView 与 GradientDrawable：球体的「透出背景」效果依赖多层 radialGradient
 * 通过 BlendMode.Screen 叠加形成玻璃质感，静态 drawable 无法实现。
 *
 * 调用方提供 60dp 的精确像素窗口，因此所有内容均绘制在 60dp 画布内：光晕与涟漪全部
 * 通过画布内渐变实现，不依赖放大 View（外层窗口会直接裁剪）。
 *
 * 可见时运行完整动效：30 秒自转、4.5 秒呼吸、2 道 3.6 秒涟漪；不可见时（息屏、折叠为小窗、
 * 窗口被系统隐藏）不请求任何帧，动效参数保持不变。
 *
 * @param pressed 按压状态（由服务在 ACTION_DOWN、MOVE、UP 中派发），按下时整体缩放至 0.85
 * @param visible 球此刻是否实际可见（服务提供：屏幕点亮、窗口可见且处于球态）。
 *                false 时不注册 InfiniteTransition，不计算任何帧。
 * @param onClick 点击球（服务层为触摸派发主体，该回调用于直接点击球的路径）
 */
@Composable
fun SiriBall(pressed: Boolean, visible: Boolean, onClick: () -> Unit) {
    // 按压缩放仅作用于内容层，窗口尺寸不变，因此缩放至 0.85 不会超出 60dp 而被裁剪
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        animationSpec = tween(durationMillis = PRESS_MS, easing = FastOutSlowInEasing),
        label = "siri-ball-press"
    )

    // 动效：慢速旋转、呼吸与涟漪全部由同一个 InfiniteTransition 驱动。
    // 仅在 visible 时注册：只要存在便会持续请求帧并重绘整块 Canvas。
    // 不可见时（息屏、折叠为小窗、窗口被系统隐藏）这些帧无实际展示效果，只会增加耗电。
    // 恢复可见时为重新 remember 的 transition，时钟从 0 开始，第一帧即为正常
    // 相位（0°、完整半径、涟漪间隙），不会接续上次停止的位置。
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
            // 三个动效值有意延迟到 draw 阶段读取：若在组合阶段读取 .value，球每帧都会重组并重新布局，
            // 而画面实际只需重绘这张 52dp 画布。移至 draw 后每帧仅失效 draw 阶段，像素结果完全一致。
            // clock 为 null（不可见）时以动画起点常量绘制一帧。
            drawSiriBall(
                spin = spinState?.value ?: 0f,
                breath = breathState?.value ?: 1f,
                rippleClock = rippleState?.value ?: RIPPLE_CYCLE_MS.toFloat()
            )
        }
    }
}

/** 绘制一帧球体：玻璃底、4 个 Screen 混合色块、2 道涟漪、边缘高光。 */
private fun DrawScope.drawSiriBall(spin: Float, breath: Float, rippleClock: Float) {
    val maxRadius = size.minDimension / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    // 球体半径保留 14% 余量供涟漪与偏心色块使用，保证任意一帧均不超出画布
    val ballRadius = maxRadius * 0.80f * breath

    // 1) 玻璃底：极淡白色，在纯黑或纯白壁纸上均能辨认球的轮廓
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x22FFFFFF), Color(0x0DFFFFFF), Color(0x00FFFFFF)),
            center = center,
            radius = ballRadius
        ),
        radius = ballRadius,
        center = center
    )

    // 2) 四个径向渐变色块：各自在半径 26% 的轨道上绕中心慢速旋转
    //    BlendMode.Screen 使其相互叠加增亮而非相互覆盖，玻璃质感层次由此形成
    val blobRadius = ballRadius * 0.78f
    val orbit = ballRadius * 0.26f
    BallColors.forEachIndexed { index, color ->
        // 每个色块错开 90°，随 spin 一同旋转
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

    // 3) 两道涟漪：错开 0 / 1100ms，每道持续 700ms，自球面扩散至画布边缘
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

    // 4) 左上方一处柔和高光（不绘制整圈白色描边：白色环线在壁纸上过于醒目）
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
