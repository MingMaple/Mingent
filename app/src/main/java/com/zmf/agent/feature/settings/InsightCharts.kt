package com.zmf.agent.feature.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import com.zmf.agent.uikit.theme.Palette
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** 环形图：segments 是（比例, 颜色）；中心两行字由调用方给。 */
@Composable
internal fun DonutRing(
    segments: List<Pair<Float, Color>>,
    size: Dp,
    centerTop: String,
    centerBottom: String,
    modifier: Modifier = Modifier
) {
    val track = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Palette.DarkStroke else Palette.LightStroke
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2 + 1.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            var start = -90f
            for ((fraction, color) in segments) {
                val sweep = fraction.coerceIn(0f, 1f) * 360f
                if (sweep <= 0f) continue
                drawArc(
                    color = color,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 中心数字按长度收字号：天数级（如 2d10h）也必须单行，不能换到第二行
            val topSize = when {
                centerTop.length <= 4 -> 18.sp
                centerTop.length <= 6 -> 16.sp
                centerTop.length <= 8 -> 14.sp
                else -> 12.sp
            }
            Text(
                text = centerTop,
                fontSize = topSize,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = centerBottom,
                fontSize = 12.sp,
                color = onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/** 堆叠条：按 tokens 等比切段，两端圆头；总长按 [total] 计，剩余部分留轨道色。 */
@Composable
internal fun StackedBar(
    segments: List<Pair<Int, Color>>,
    total: Int,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp
) {
    val track = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Palette.DarkStroke else Palette.LightStroke
    Canvas(modifier.fillMaxWidth().height(height)) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = track, cornerRadius = radius)
        if (total <= 0) return@Canvas
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    radiusX = size.height / 2f,
                    radiusY = size.height / 2f
                )
            )
        }
        clipPath(path) {
            var x = 0f
            for ((tokens, color) in segments) {
                val width = size.width * (tokens.toFloat() / total)
                if (width > 0f) {
                    drawRect(color = color, topLeft = Offset(x, 0f), size = Size(width, size.height))
                }
                x += width
            }
        }
    }
}

/**
 * 折线图横轴的一个刻度。
 *
 * [fraction] 是绘图区宽度的比例（0 = 左端，1 = 右端），与数据下标无关。
 * 用比例而不是下标，是为了让「今天」的 7 个刻度（0/4/8/12/16/20/23 时）在整条轴上等距排开：
 * 若按小时数对齐，末段只有 3 小时，右端会空出一截、看起来疏密不均。
 */
internal data class AxisTick(val fraction: Float, val label: String)

/**
 * 折线图的绘图区几何：绘制与浮层定位共用一份，避免两处算出不同坐标。
 *
 * [top] 是顶部刻度线（纵轴上限）所在高度，[bottom] 是零线。纵轴上限按「好看的数字」动态取，
 * 不是直接拿最大值：最大值换成上限后，峰值永远落在同一相对高度，换个月份也不会顶到边框。
 */
private class UsagePlot(
    val count: Int,
    val width: Float,
    val top: Float,
    val bottom: Float,
    val max: Long,
    val inset: Float,
    val stepX: Float
) {
    /** 节点的横坐标；允许小数，供浮层弹簧跟随插值。 */
    fun x(index: Float): Float =
        if (count > 1) inset + index.coerceIn(0f, (count - 1).toFloat()) * stepX else width / 2f

    fun point(index: Int, value: Long): Offset {
        val ratio = (value.toDouble() / max.toDouble()).coerceIn(0.0, 1.0).toFloat()
        return Offset(x = x(index.toFloat()), y = bottom - (bottom - top) * ratio)
    }
}

private fun usagePlot(
    values: List<Long>,
    width: Float,
    chartHeight: Float,
    topBand: Float,
    labelBand: Float,
    inset: Float
): UsagePlot {
    val plotWidth = (width - inset * 2).coerceAtLeast(1f)
    return UsagePlot(
        count = values.size,
        width = width,
        top = topBand,
        bottom = chartHeight - labelBand,
        max = niceMax(values.maxOrNull() ?: 0L),
        inset = inset,
        stepX = if (values.size > 1) plotWidth / (values.size - 1) else 0f
    )
}

/**
 * 纵轴上限取「好看的数字」：1、1.5、2、2.5、3、4、5、6、8 乘 10 的整数次幂。
 *
 * 用 Double 计算，五个亿、十个亿这种量级也不会溢出；全 0 时给 1，避免除零。
 * 直接拿最大值当上限会让峰值永远贴在顶部，换一个月份上限就从 700M 跳到 1.2B，读数无法对照。
 */
internal fun niceMax(value: Long): Long {
    if (value <= 0L) return 1L
    val magnitude = 10.0.pow(kotlin.math.floor(kotlin.math.log10(value.toDouble())))
    for (step in doubleArrayOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)) {
        val candidate = step * magnitude
        if (candidate >= value.toDouble()) return candidate.roundToLong().coerceAtLeast(1L)
    }
    return (10.0 * magnitude).roundToLong()
}

/**
 * 用量折线：顶部纵轴上限刻度、底部轴线、平滑折线、线下渐变、末点圆点。
 *
 * 绘图区就是「用量统计」标题以下到时间刻度之间的整块区域（[topBand] 只留一行刻度字），
 * 不为浮层预留空带：浮层浮在曲线上、纵向固定、只随节点横向平移。
 * [endIndex] 限制折线与圆点画到哪一点（-1 = 画到末尾）：横轴可以铺满全天，
 * 而「今天」只有已过的小时才有数据，未来时段不画，避免被读成用量归零。
 * [selection] 是当前选中的节点：[tooltip] 据此给出浮层内容。
 * [onSelect] 点按与拖动都走它：点按任意位置都切到最近的节点，浮层平移过去，不关不弹。
 * 为 null 时整张图不可交互。
 */
@Composable
internal fun UsageLineChart(
    values: List<Long>,
    ticks: List<AxisTick>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    endIndex: Int = -1,
    selection: Int? = null,
    tooltip: ((Int) -> TooltipData?)? = null,
    axisText: (Long) -> String = { tokenText(it) },
    onSelect: ((Int) -> Unit)? = null,
    onChartPress: (() -> Unit)? = null
) {
    val axis = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Palette.DarkStroke else Palette.LightStroke
    val surface = MaterialTheme.colorScheme.surface
    val fade = color.copy(alpha = 0f)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    )
    // 标签区随字号缩放，避免大字体下轴标签被画布下边缘裁掉（1x 时为 18dp）
    val labelBandDp = with(LocalDensity.current) { labelStyle.fontSize.toDp() } + 8.dp
    // 顶部刻度字的高度：算进画布，但曲线不画进这一带
    val topBandDp = with(LocalDensity.current) { labelStyle.fontSize.toDp() } + 6.dp
    val last = if (endIndex in values.indices) endIndex else values.lastIndex
    // 点选态：索引必须落在已绘制区里，未来时段没有数据可说明
    val selected = selection?.takeIf { it in 0..last }
    val data = selected?.let { tooltip?.invoke(it) }
    /*
     * 浮层宽度在同一张图里恒定：按这张图所有节点里最宽的一条算，而不是当前这一条。
     *
     * 逐节点自适应会让面板在拖动时忽宽忽窄，看着像在抖；跨图又必须各自适配（今天的小时数
     * 与近 30 天的日期、Token 与请求的位数都不一样），所以宽度只能「每张图算一次」。
     */
    val density = LocalDensity.current
    // 关键：values 也要进键。切 Token / 请求 时数据换了但点数不变，只靠 tooltip 的 lambda
    // 身份判断并不稳：面板宽度会沿用上一个口径的，看起来两边一样宽。
    val popupWidth = remember(tooltip, values, last, measurer, density) {
        val headerStyle = TextStyle(fontSize = 11.sp)
        val totalStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val rowStyle = TextStyle(fontSize = 11.sp)
        val padding = with(density) { POPUP_PADDING.roundToPx() }
        val columnGap = with(density) { COLUMN_GAP.roundToPx() }
        val dot = with(density) { DOT.roundToPx() }
        val dotGap = with(density) { DOT_GAP.roundToPx() }
        /*
         * 左右分区：左列只放「行名」与左上角日期，取两者最宽的一个，宽度固定；
         * 右列只放数字，取这张图所有数字里最宽的一个（通常是右上角的总量）；
         * 中间留固定空白。数字一律右对齐，最宽的那个正好贴着空白右缘。
         * 这样面板宽度整张图恒定，且长数字永远不会压到左列。
         */
        var maxLabel = 0
        var maxHeader = 0
        var maxNumber = 0
        for (index in 0..last) {
            val item = tooltip?.invoke(index) ?: continue
            val headerWidth = measurer.measure(item.header, headerStyle).size.width
            if (headerWidth > maxHeader) maxHeader = headerWidth
            if (item.total != null) {
                val totalWidth = measurer.measure(item.total, totalStyle).size.width
                if (totalWidth > maxNumber) maxNumber = totalWidth
            }
            for (row in item.rows) {
                val labelWidth = measurer.measure(row.label, rowStyle).size.width
                val valueWidth = measurer.measure(row.value, rowStyle).size.width
                if (labelWidth > maxLabel) maxLabel = labelWidth
                if (valueWidth > maxNumber) maxNumber = valueWidth
            }
        }
        val leftWidth = maxOf(maxHeader, dot + dotGap + maxLabel)
        padding * 2 + leftWidth + columnGap + maxNumber
    }
    val popupWidthDp = with(density) { popupWidth.toDp() }
    /*
     * 浮层的横坐标只由手势驱动，不跟着 selected 走。
     *
     * 跟 selected 走就必须「每换一个节点重启一次动画」，弹簧每重启一次速度就归零，
     * 连续左右滑动时看起来是一段一段的（用户反馈「动效是断开的」）。改成：
     * 拖动时锚点直接贴手指（1:1 跟手，天然连续），抬手/点按才用弹簧吸附到节点。
     */
    val anchor = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val selectionCurrent by rememberUpdatedState(selection)
    /*
     * 手势：只在「横向位移超过触摸阈值且大于纵向」时才接管，并按节点回调选中。
     * 纵向滑动一律不消费、也不弹浮层——页面滚动不能被图表抢走（手指略微倾斜也一样）。
     * 接管后消费每一帧与抬手，避免位移继续传给外层抽屉。
     * 回调走 rememberUpdatedState：切「Token / 请求」会重建页面的选中状态对象，
     * 而 pointerInput 的协程只在 size/endIndex 变化时重启，直接捕获旧 lambda 会继续写进废弃的 state。
     */
    val selectCurrent by rememberUpdatedState(onSelect)
    val pressCurrent by rememberUpdatedState(onChartPress)
    val touchModifier = if (onSelect == null || values.isEmpty()) {
        Modifier
    } else {
        Modifier.pointerInput(values.size, endIndex) {
            val inset = 4.dp.toPx()
            fun indexAt(x: Float): Int {
                val plotWidth = (size.width - inset * 2).coerceAtLeast(1f)
                val step = if (values.size > 1) plotWidth / (values.size - 1) else 0f
                return if (step > 0f) {
                    ((x - inset) / step).roundToInt().coerceIn(0, last)
                } else {
                    0
                }
            }
            fun nodeX(index: Int): Float {
                val plotWidth = (size.width - inset * 2).coerceAtLeast(1f)
                val step = if (values.size > 1) plotWidth / (values.size - 1) else 0f
                return if (step > 0f) inset + index.coerceIn(0, last) * step else size.width / 2f
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // 告诉页面「这一下按在图表里了」：页面据此不在按下时收起浮层。
                // 用计数而不是坐标判断，避免滚动容器里 boundsInRoot 与触点坐标不在同一套空间。
                pressCurrent?.invoke()
                var dx = 0f
                var dy = 0f
                var dragging = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        val index = indexAt(change.position.x)
                        val target = nodeX(index)
                        val wasVisible = dragging || selectionCurrent != null
                        selectCurrent?.invoke(index)
                        // 面板原本不在就落位；已经在就平移过去（不关也不重开）
                        if (wasVisible) {
                            scope.launch { anchor.animateTo(target, POPUP_SPRING) }
                        } else {
                            scope.launch { anchor.snapTo(target) }
                        }
                        break
                    }
                    dx += change.position.x - change.previousPosition.x
                    dy += change.position.y - change.previousPosition.y
                    if (!dragging) {
                        // 纵向为主的滑动交给页面滚动：不消费、也不弹浮层
                        if (abs(dy) > abs(dx) && abs(dy) > viewConfiguration.touchSlop) break
                        if (abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy)) {
                            dragging = true
                            selectCurrent?.invoke(indexAt(change.position.x))
                            scope.launch { anchor.snapTo(change.position.x.coerceIn(nodeX(0), nodeX(last))) }
                            change.consume()
                        }
                    } else {
                        selectCurrent?.invoke(indexAt(change.position.x))
                        // 锚点 1:1 贴手：手指在哪面板就在哪，连续滑动不会一顿一顿
                        scope.launch { anchor.snapTo(change.position.x.coerceIn(nodeX(0), nodeX(last))) }
                        change.consume()
                    }
                }
            }
        }
    }

    /*
     * 结构固定：曲线永远是 Layout 的第一个子节点，浮层按需跟在后面。
     *
     * 这一点必须守住——早前把「无浮层」与「有浮层」写成两棵不同的子树，
     * 选中一变曲线就被重新创建，正在进行的拖动手势当场被取消，
     * 表现为浮层只在手指按下处闪一下、不再跟手。
     */
    Layout(
        // 手势挂在容器上而不是画布上：浮层的任何一点落在图表区域内也要算图表触摸，
        // 否则从浮层上起手的滑动会漏给外层抽屉（实测会把抽屉拉出来）
        modifier = modifier.fillMaxWidth().then(touchModifier),
        content = {
            Canvas(Modifier.fillMaxWidth().height(height + topBandDp + labelBandDp)) {
                val topBand = topBandDp.toPx()
                val labelBand = labelBandDp.toPx()
                // 左右各留出半个点的位置：否则首末点的圆会被画布边缘切掉一半
                val inset = 4.dp.toPx()
                val plot = usagePlot(values, size.width, size.height, topBand, labelBand, inset)
                // 顶部刻度线（纵轴上限）+ 零线
                drawLine(axis, Offset(0f, plot.top), Offset(size.width, plot.top), strokeWidth = 1.dp.toPx())
                drawLine(axis, Offset(0f, plot.bottom), Offset(size.width, plot.bottom), strokeWidth = 1.dp.toPx())
                // 全 0 时不画上限刻度：没有数据，标一个「1」反而是噪音
                if ((values.maxOrNull() ?: 0L) > 0L) {
                    val maxText = measurer.measure(axisText(plot.max), labelStyle)
                    drawText(
                        textLayoutResult = maxText,
                        topLeft = Offset(0f, plot.top - maxText.size.height - 1.dp.toPx())
                    )
                }
                if (values.isNotEmpty()) {
                    val points = values.mapIndexed { index, value -> plot.point(index, value) }
                    // 只画到 last：「今天」的未来时段没有数据，画成 0 会读成「用量掉到零」
                    val drawn = points.subList(0, last + 1)
                    if (drawn.size > 1) {
                        // 用「相邻中点」做三次贝塞尔的控制点，线条平滑但不过冲
                        val line = Path().apply {
                            moveTo(drawn.first().x, drawn.first().y)
                            for (index in 1 until drawn.size) {
                                val previous = drawn[index - 1]
                                val current = drawn[index]
                                val midX = (previous.x + current.x) / 2f
                                cubicTo(midX, previous.y, midX, current.y, current.x, current.y)
                            }
                        }
                        val area = Path().apply {
                            addPath(line)
                            lineTo(drawn.last().x, plot.bottom)
                            lineTo(drawn.first().x, plot.bottom)
                            close()
                        }
                        drawPath(area, brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.26f), fade)))
                        drawPath(line, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                    }
                    drawCircle(color = color, radius = 3.5.dp.toPx(), center = drawn.last())

                    // 选中的节点：竖直虚线 + 比末点更大的圆点（外圈用卡片底色留白，压在曲线上也看得清）
                    if (selected != null) {
                        val center = points[selected]
                        drawLine(
                            color = color.copy(alpha = 0.35f),
                            start = Offset(center.x, plot.top),
                            end = Offset(center.x, plot.bottom),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(6.dp.toPx(), 6.dp.toPx())
                            )
                        )
                        drawCircle(color = surface, radius = 7.dp.toPx(), center = center)
                        drawCircle(color = color, radius = 4.5.dp.toPx(), center = center)
                    }
                }

                /*
                 * 轴标签：位置由调用方给的等分比例决定，与数据下标无关。
                 * 窄屏 + 点数变化仍可能让相邻两字重叠（例如近 30 天时 `10-26` 与 `10-29`），
                 * 这里按实际字宽做碰撞剔除：先固定首末两个，其余按从左到右依次判断是否放得下。
                 */
                if (ticks.isEmpty()) return@Canvas
                val textGap = 4.dp.toPx()
                val plotWidth = (size.width - inset * 2).coerceAtLeast(1f)
                class LabelBox(val left: Float, val text: androidx.compose.ui.text.TextLayoutResult)

                val boxes = ticks.map { tick ->
                    val layout = measurer.measure(tick.label, labelStyle)
                    val textWidth = layout.size.width.toFloat()
                    val center = inset + tick.fraction.coerceIn(0f, 1f) * plotWidth
                    val left = (center - textWidth / 2f)
                        .coerceIn(0f, (size.width - textWidth).coerceAtLeast(0f))
                    LabelBox(left, layout)
                }
                val occupied = ArrayList<FloatArray>(boxes.size)
                val tryDraw: (Int) -> Unit = draw@{ index ->
                    val box = boxes[index]
                    val boxWidth = box.text.size.width.toFloat()
                    val right = box.left + boxWidth
                    for (range in occupied) {
                        if (box.left < range[1] + textGap && right > range[0] - textGap) return@draw
                    }
                    occupied.add(floatArrayOf(box.left, right))
                    drawText(
                        textLayoutResult = box.text,
                        topLeft = Offset(box.left, plot.bottom + 4.dp.toPx())
                    )
                }
                tryDraw(0)
                if (boxes.lastIndex > 0) tryDraw(boxes.lastIndex)
                for (index in 1 until boxes.lastIndex) tryDraw(index)
            }
            if (data != null) UsageChartPopup(data, popupWidthDp)
        }
    ) { measurables, constraints ->
        val chartPlaceable = measurables[0].measure(constraints)
        val chartWidth = chartPlaceable.width
        val plot = usagePlot(
            values = values,
            width = chartWidth.toFloat(),
            chartHeight = chartPlaceable.height.toFloat(),
            topBand = topBandDp.toPx(),
            labelBand = labelBandDp.toPx(),
            inset = 4.dp.toPx()
        )
        val overlay = measurables.getOrNull(1)?.measure(
            Constraints(maxWidth = chartWidth.coerceAtLeast(0))
        )
        layout(chartWidth, chartPlaceable.height) {
            chartPlaceable.place(0, 0)
            if (overlay != null) {
                val edge = 6.dp.toPx()
                // 纵向恒在纵轴上限那条线下方一点，只做横向跟随；左右越界贴边
                val x = (anchor.value - overlay.width / 2f)
                    .coerceIn(edge, (chartWidth - overlay.width - edge).coerceAtLeast(edge))
                overlay.place(x.roundToInt(), plot.top.roundToInt() + 2.dp.roundToPx())
            }
        }
    }
}

/**
 * 触摸浮层的内容（对齐 DSH 的 API 统计卡片）：第一行是时段与合计，下面逐行给出构成明细。
 *
 * 明细**始终全列**，值为 0 也照样写出——只看得到一个 0 等于没给信息。
 * 宽度不写死：数字位数多时靠内容自然撑开（DSH 的月度统计就是这样被撑宽的）。
 */
internal data class TooltipData(
    val header: String,
    val total: String?,
    val rows: List<TooltipRow>
)

/** 浮层吸附用的弹簧：刚度高、阻尼接近临界，快速甩动时只留一点回弹。 */
private val POPUP_SPRING = spring<Float>(dampingRatio = 0.82f, stiffness = 900f)

/** 浮层内的固定间距：测量宽度与真正绘制必须用同一组值，否则宽度会差一两 dp。 */
private val POPUP_PADDING = 11.dp
private val COLUMN_GAP = 22.dp
private val DOT = 6.dp
private val DOT_GAP = 7.dp

/** 浮层里的一行明细：色点 + 名称 + 右侧数值。 */
internal data class TooltipRow(val label: String, val value: String, val color: Color)

/**
 * 触摸/点按节点后浮在曲线上方的说明卡。
 *
 * 配色按《UI 与配色规范》§5.1 的伴随面板口径：卡片底色 + 描边 + 8dp 阴影，
 * 不照搬 DSH 的深色胶囊（反色黑块在本应用里只出现在提示条）。
 */
@Composable
private fun UsageChartPopup(data: TooltipData, width: Dp) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.width(width),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(horizontal = POPUP_PADDING, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = data.header,
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    softWrap = false
                )
                if (data.total != null) {
                    Text(
                        text = data.total,
                        modifier = Modifier.padding(start = COLUMN_GAP),
                        fontSize = 13.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = onSurface,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            for (row in data.rows) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(DOT)
                            .background(row.color, RoundedCornerShape(1.5.dp))
                    )
                    Text(
                        text = row.label,
                        modifier = Modifier.weight(1f).padding(start = DOT_GAP),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = onSurface.copy(alpha = 0.70f),
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = row.value,
                        modifier = Modifier.padding(start = COLUMN_GAP),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = onSurface,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}



/** 图例一行：色点 + 名称 + 右侧「≈tokens · 百分比」。 */
@Composable
internal fun LegendRow(
    label: String,
    tokens: Long,
    fraction: Float?,
    color: Color,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            fontSize = 13.sp,
            lineHeight = 16.sp,
            color = onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = (if (tokens > 0) "≈" + tokenText(tokens) else "—") +
                (if (fraction != null) "  " + percentText(fraction) else ""),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = onSurface.copy(alpha = 0.45f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 段落图例（两行）：第一行「名称 + 右对齐百分比」，第二行灰色补充值（DSH 同款）。 */
@Composable
internal fun LegendTwoLine(
    label: String,
    rightTop: String,
    rightBottom: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 1.dp)
    ) {
        // 色标是标题行的兄弟节点：与标题文字垂直居中，不在两行之间居中
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(color, RoundedCornerShape(2.dp))
            )
            Text(
                text = label,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                fontSize = 12.sp,
                lineHeight = 14.sp,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = rightTop,
                modifier = Modifier.offset(y = 1.8.dp),
                fontSize = 12.sp,
                lineHeight = 14.sp,
                color = onSurface.copy(alpha = 0.65f),
                maxLines = 1,
                softWrap = false
            )
        }
        Text(
            text = rightBottom,
            modifier = Modifier.padding(start = 17.dp),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = onSurface.copy(alpha = 0.40f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * token 数缩写：十亿 B、百万 M、千 k，均留一位小数；不足一千原样输出。
 *
 * 阈值与 dsh-context 的 fmt 一致（1e3 / 1e6 / 1e9）：每一档都带单位，
 * 不会出现四位数没有单位、或一万与百万共用同一档的情况。
 */
internal fun tokenText(tokens: Long): String {
    val sign = if (tokens < 0) "-" else ""
    val value = if (tokens < 0) -tokens else tokens
    return when {
        value >= 1_000_000_000L -> sign + oneDecimal(value / 1_000_000_000.0) + "B"
        value >= 1_000_000L -> sign + oneDecimal(value / 1_000_000.0) + "M"
        value >= 1_000L -> sign + oneDecimal(value / 1_000.0) + "k"
        else -> sign + value.toString()
    }
}

/** 比率百分比：一位小数。 */
internal fun percentText(ratio: Float): String =
    oneDecimal(ratio.coerceIn(0f, 1f) * 100.0) + "%"

/**
 * 浮层里的原始计数：千分位加逗号，不缩写。
 *
 * 折线轴的刻度用 [tokenText] 缩写（1.1M），浮层要给出确切值（1,134,567）——
 * 与 dsh 的后台统计一致：概览给缩写，明细给整数。
 */
internal fun countText(value: Long): String = String.format(Locale.US, "%,d", value)

/**
 * 固定一位小数，不省略末尾的 `.0`。
 *
 * 与 dsh 的 toFixed(1) 同口径：`10.0k` 而不是 `10k`，`1.0M` 而不是 `1M`。
 * 省掉小数位会让同一列数字的字宽跳动，也会让「10」与「10.0k」看起来不像同一档。
 */
private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

/**
 * 时长按 DSH 的紧凑写法：秒 / 分秒 / 时分 / 天时。
 *
 * 例：`17s`、`3m17s`、`13h38m`、`2d10h`。中文「2 天 10 小时」在环形中心会被挤成两行，
 * 所以这里不写中文单位。
 *
 * 取整用「四舍五入」而不是「一律向上」：六段耗时要与中心的活跃时长对得上，
 * 每段各自向上取整会让合计比总数大出一截（实测 6 秒的一轮，六段能加成 9 秒）。
 */
internal fun durationText(millis: Long): String {
    val totalSeconds = kotlin.math.round(millis.coerceAtLeast(0L) / 1000.0).toLong()
    if (totalSeconds < 60L) return totalSeconds.toString() + "s"
    val minutes = totalSeconds / 60L
    if (minutes < 60L) return minutes.toString() + "m" + (totalSeconds % 60L).toString() + "s"
    val hours = minutes / 60L
    if (hours < 24L) return hours.toString() + "h" + (minutes % 60L).toString() + "m"
    val days = hours / 24L
    return days.toString() + "d" + (hours % 24L).toString() + "h"
}
