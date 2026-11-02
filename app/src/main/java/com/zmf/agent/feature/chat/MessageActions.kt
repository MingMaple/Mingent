package com.zmf.agent.feature.chat
import com.zmf.agent.uikit.icons.CylinderGlyph
import com.zmf.agent.uikit.icons.UnitGlyph

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
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.theme.Motion
import kotlin.math.roundToInt

/** 小窗里的一项：只负责文案与点击动作，排布与关闭都由菜单那一层决定。 */
data class MenuAction(val label: String, val onClick: () -> Unit)

/** 气泡尾部按钮的种类：图标、点击后做什么都由这一处决定。 */
enum class TailIcon { COPY, SELECT_TEXT, FORK, ROLLBACK, USAGE }

/**
 * 气泡尾部那一行：图标按钮 + 时间。
 *
 * 为什么不使用长按弹窗：动作按钮直接置于气泡尾部，一眼即可得知可执行的操作；
 * 长按弹窗需先知晓「可以长按」才会使用。
 * 按钮数量按功能需要确定，不追求与其他客户端一致。
 *
 * [usageAnchor] 仅在点击用量按钮时回调，参数为该图标在窗口中的位置，伴随面板据此定位。
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
            // 每枚按钮自行记录位置：面板需贴附「用户点击的那一枚」上方，而非气泡的某个角
            var origin by remember { mutableStateOf(Offset.Zero) }
            // 触控反馈与「＋」按钮一致：仅有点击涟漪，不做「按住底色 + 缩小」；按住缩放的交互语汇属于长按手势，用于点击即执行的按钮会造成误导。
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

/** 尾部按钮的图标：线宽与圆角沿用本项目手绘图标的规范（18×18 单位坐标）。 */
@Composable
private fun TailGlyph(kind: TailIcon, tint: Color, iconSize: Dp) {
    if (kind == TailIcon.USAGE) {
        // 用量复用输入条的小圆柱：同一功能在两处必须使用同一图标
        CylinderGlyph(tint = tint, iconSize = iconSize)
        return
    }
    UnitGlyph(iconSize = iconSize) {
        val line = 1.4f
        val stroke = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            TailIcon.COPY -> {
                // 两张叠放的纸：后方纸张露出一角，前方纸张完整
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
                // 三条横线，末行较短：表示「一段可被选中的文本」
                drawLine(tint, Offset(3.8f, 5.2f), Offset(14.2f, 5.2f), line, StrokeCap.Round)
                drawLine(tint, Offset(3.8f, 9.0f), Offset(14.2f, 9.0f), line, StrokeCap.Round)
                drawLine(tint, Offset(3.8f, 12.8f), Offset(10.4f, 12.8f), line, StrokeCap.Round)
            }
            TailIcon.FORK -> {
                // 一条横线，右端分成上下两岔，末端各一个圆圈：
                // 仅有两条斜线时会被识别为「＜」，加上末端两点方可读出「一条线分出两支」
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
 * 伴随面板：贴附触发按钮浮出，点击或滑动面板以外的任何位置均收起。
 *
 * 使用「全屏遮罩 + 自行排版」而非 Dialog：面板不压暗背景、不打断视线，
 * 且必须能贴附被点击的那一枚按钮：Dialog 为居中弹窗，位置由系统固定。
 * 卡片观感交由调用方（本项目的 PanelCard），此处仅负责定位与关闭。
 *
 * 宽度由调用方提供：卡片本身为 fillMaxWidth，测量之前必须已知宽度，否则在无约束下无法量出自然宽度。
 * 高度不预设：先测量卡片，再按其真实高度决定位于按钮上方或下方，不会出现「第一帧跳动」。
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
    // 不做 alpha 淡入（与输入框上的六张面板结论相同）：
    // 节点带 alpha 时 Android 不绘制 elevation 阴影，阴影会延至最后一帧突然出现。
    // 因此此处仅改变缩放，「弹出」效果交由 spring，卡片始终不透明，阴影自第一帧即存在。
    val scale by animateFloatAsState(if (appear) 1f else 0.90f, Motion.softSpring(), label = "popScale")

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩不压暗背景，但消费该层上的所有手势：
        // 点击、滑动、按住均视为「点击面板以外」，一律收起面板，同时避免底层消息列表随之滚动
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
                // 优先贴附于被点击按钮的上方（面板向上展开，不遮挡用户正在查看的消息）
                val above = anchor.y - cardHeight - gap
                val maxY = (constraints.maxHeight - cardHeight - edge).coerceAtLeast(edge)
                val y = if (above >= edge) above else (anchor.y + gap).coerceIn(edge, maxY)
                placeable.place(x.roundToInt(), y.roundToInt())
            }
        }
    }
}


/**
 * 长按后的小菜单：出现在按压位置附近，而非底部大面板。
 *
 * 遮罩只做很淡的一层并且只做淡入，卡片自己在原地缩放出现，
 * 此前将整块（含遮罩）自底部滑入，屏幕上才会出现「上三分之一未被遮罩覆盖」的分界线。
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
            // 行自带内距：Column 不设上下留白，首/末行的波纹方可贴附 16dp 圆弧并被裁为圆角
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
