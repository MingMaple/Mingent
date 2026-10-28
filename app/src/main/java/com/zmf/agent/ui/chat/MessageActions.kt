package com.zmf.agent.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.ui.common.saltCardRow
import com.zmf.agent.ui.theme.Motion
import kotlin.math.roundToInt

/** 小窗里的一项：只负责文案与点击动作，排布与关闭都由菜单那一层决定。 */
data class MenuAction(val label: String, val onClick: () -> Unit)

/** 气泡尾部按钮的种类：图标、点击后做什么都由这一处决定。 */
enum class TailIcon { COPY, SELECT_TEXT, FORK, ROLLBACK, USAGE }

/**
 * 气泡尾部那一行：图标按钮 + 时间。
 *
 * 为什么不再用长按弹窗：动作按钮直接摆在气泡尾巴上，看一眼就知道能做什么；
 * 看一眼就知道能做什么；长按弹窗要先知道「可以长按」才会去长按。
 * 按钮数量按功能需要来，不追求和别的客户端一样多。
 *
 * [usageAnchor] 只在点用量按钮时回调，参数是那枚图标在窗口里的位置，伴随面板靠它定位。
 */
@Composable
fun MessageTailRow(
    at: String,
    alignEnd: Boolean,
    icons: List<TailIcon>,
    onAction: (TailIcon) -> Unit,
    usageAnchor: (Offset) -> Unit
) {
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icons.forEach { kind ->
            // 每枚按钮自己记位置：面板要贴在「用户点的那一枚」上方，而不是气泡的某个角
            var origin by remember { mutableStateOf(Offset.Zero) }
            // 触控反馈跟「＋」那颗一样：只有点击涟漪，不做「按住底色 + 缩小」。
            // 这些是点一下就走的按钮，按住缩放是给长按手势的语汇，放在这里反而误导
            // 这些是点一下就走的按钮，按住缩放是给长按手势的语汇，放在这里反而误导。
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .clickable {
                        if (kind == TailIcon.USAGE) usageAnchor(origin) else onAction(kind)
                    },
                contentAlignment = Alignment.Center
            ) {
                TailGlyph(kind = kind, tint = secondary, iconSize = 15.dp)
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(text = at, fontSize = 12.sp, color = secondary)
    }
}

/** 尾部按钮的图标：线宽与圆角沿用本工程手绘图标那一套（18×18 单位坐标）。 */
@Composable
private fun TailGlyph(kind: TailIcon, tint: Color, iconSize: Dp) {
    if (kind == TailIcon.USAGE) {
        // 用量复用输入条那颗小圆柱：同一件事在两处必须是同一个图标
        CylinderGlyph(tint = tint, iconSize = iconSize)
        return
    }
    UnitGlyph(iconSize = iconSize) {
        val line = 1.4f
        val stroke = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            TailIcon.COPY -> {
                // 两张叠起来的纸：后面那张露一角，前面那张完整
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(5.8f, 3.2f),
                    size = Size(9.0f, 9.0f),
                    cornerRadius = CornerRadius(2.2f, 2.2f),
                    style = stroke
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(3.2f, 5.8f),
                    size = Size(9.0f, 9.0f),
                    cornerRadius = CornerRadius(2.2f, 2.2f),
                    style = stroke
                )
            }
            TailIcon.SELECT_TEXT -> {
                // 三条横线，末行短一截：读作「一段可以被挑走的文本」
                drawLine(tint, Offset(3.8f, 5.2f), Offset(14.2f, 5.2f), line, StrokeCap.Round)
                drawLine(tint, Offset(3.8f, 9.0f), Offset(14.2f, 9.0f), line, StrokeCap.Round)
                drawLine(tint, Offset(3.8f, 12.8f), Offset(10.4f, 12.8f), line, StrokeCap.Round)
            }
            TailIcon.FORK -> {
                // 一条横线，右端分成上下两岔，末端各一个圆圈：
                // 只有两条斜线时会被读成「＜」，加上末端两点才读得出「一条线分出去两支」
                drawLine(tint, Offset(2.4f, 9.0f), Offset(9.0f, 9.0f), line, StrokeCap.Round)
                drawLine(tint, Offset(9.0f, 9.0f), Offset(12.8f, 4.8f), line, StrokeCap.Round)
                drawLine(tint, Offset(9.0f, 9.0f), Offset(12.8f, 13.2f), line, StrokeCap.Round)
                drawCircle(tint, radius = 1.7f, center = Offset(14.3f, 3.9f), style = stroke)
                drawCircle(tint, radius = 1.7f, center = Offset(14.3f, 14.1f), style = stroke)
            }
            TailIcon.ROLLBACK -> {
                // 向左回头的箭头：回到这条提问之前
                drawPath(
                    path = Path().apply {
                        moveTo(13.2f, 12.8f)
                        cubicTo(13.2f, 8.4f, 10.6f, 5.8f, 5.8f, 5.8f)
                    },
                    color = tint,
                    style = stroke
                )
                drawPath(
                    path = Path().apply {
                        moveTo(5.8f, 5.8f)
                        lineTo(8.6f, 3.0f)
                        moveTo(5.8f, 5.8f)
                        lineTo(8.6f, 8.6f)
                    },
                    color = tint,
                    style = stroke
                )
            }
            TailIcon.USAGE -> Unit
        }
    }
}

/**
 * 伴随面板：贴着触发按钮浮出来，点 / 划面板以外的任何地方都收。
 *
 * 用「全屏遮罩 + 自己排版」而不是 Dialog：面板不压暗背景、不打断视线，
 * 而且它必须能贴着被点的那一枚按钮出现：Dialog 是居中弹窗，位置由系统定死了。
 * 关卡片的观感交给调用方（本工程的 PanelCard），这里只管定位与关闭。
 *
 * 宽度由调用方给：卡片本身是 fillMaxWidth，量之前必须知道多宽，否则无约束下量不出自然宽度。
 * 高度不预设：先量卡片、再按它的真实高度决定落在按钮上方还是下方，不会出现「第一帧跳一下」。
 */
@Composable
fun SaltPopover(
    anchor: Offset,
    widthDp: Dp,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val widthPx = with(density) { widthDp.toPx() }
    val edge = with(density) { 12.dp.toPx() }
    val gap = with(density) { 8.dp.toPx() }
    val currentDismiss by rememberUpdatedState(onDismiss)

    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }
    // 不做 alpha 淡入（与输入框上那六张面板同一条结论）：
    // 节点带 alpha 时 Android 不画 elevation 阴影，阴影会拖到最后一帧才「啪」地出现。
    // 所以这里只动缩放，「弹出来」的手感交给 spring，卡片始终不透明，阴影从第一帧就在。
    val scale by animateFloatAsState(if (appear) 1f else 0.90f, Motion.softSpring(), label = "popScale")

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩不压暗背景，但吃掉这一层上的所有手势：
        // 点、划、按住都算「点到面板以外」，一律收面板，同时别让底下的消息列表跟着滚
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        currentDismiss()
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                }
        )
        Layout(
            content = {
                Box(
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                ) { content() }
            },
            modifier = Modifier.fillMaxSize()
        ) { measurables, constraints ->
            val placeable = measurables.first().measure(
                Constraints(
                    minWidth = 0,
                    maxWidth = widthPx.roundToInt(),
                    minHeight = 0,
                    maxHeight = Constraints.Infinity
                )
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                val cardWidth = placeable.width
                val cardHeight = placeable.height
                val maxX = (constraints.maxWidth - cardWidth - edge).coerceAtLeast(edge)
                val x = (anchor.x - cardWidth / 2f).coerceIn(edge, maxX)
                // 优先贴在被点按钮的上方（面板向上展开，不挡住用户正在看的那条消息）
                val above = anchor.y - cardHeight - gap
                val maxY = (constraints.maxHeight - cardHeight - edge).coerceAtLeast(edge)
                val y = if (above >= edge) above else (anchor.y + gap).coerceIn(edge, maxY)
                placeable.place(x.roundToInt(), y.roundToInt())
            }
        }
    }
}


/**
 * 长按后的小菜单：出现在按压位置附近，不是底部大面板。
 *
 * 遮罩只做很淡的一层并且只做淡入，卡片自己在原地缩放出现，
 * 之前把整块（含遮罩）一起从底部滑上来，屏幕上才会出现「上三分之一没被罩住」的分界线。
 */
@Composable
fun MessageContextMenu(
    anchor: Offset,
    actions: List<MenuAction>,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val cardWidthPx = with(density) { 176.dp.toPx() }
    val cardHeightPx = with(density) { (actions.size * 52 + 12).dp.toPx() }

    val x = (anchor.x - cardWidthPx / 2f).coerceIn(
        with(density) { 8.dp.toPx() },
        (screenWidthPx - cardWidthPx - with(density) { 8.dp.toPx() }).coerceAtLeast(with(density) { 8.dp.toPx() })
    )
    val edge = with(density) { 40.dp.toPx() }
    val maxY = (screenHeightPx - cardHeightPx - edge).coerceAtLeast(edge)
    val showAbove = anchor.y + cardHeightPx + with(density) { 24.dp.toPx() } > screenHeightPx
    val y = if (showAbove) {
        (anchor.y - cardHeightPx - with(density) { 16.dp.toPx() }).coerceIn(edge, maxY)
    } else {
        (anchor.y + with(density) { 16.dp.toPx() }).coerceIn(edge, maxY)
    }

    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }
    val scale by animateFloatAsState(if (appear) 1f else 0.9f, Motion.softSpring(), label = "menuScale")

    Box(modifier = Modifier.fillMaxSize()) {
        // 只做淡入的遮罩，不做位移
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.12f))
                .clickable(onClick = onDismiss)
        )
        Surface(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(176.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp
        ) {
            // 行自己带内距：Column 不垫上下留白，首/末行的波纹才能贴到 16dp 圆弧上
            Column(modifier = Modifier) {
                actions.forEach { action ->
                    Text(
                        text = action.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .saltCardRow { action.onClick() }
                            .padding(horizontal = 18.dp, vertical = 13.dp),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
