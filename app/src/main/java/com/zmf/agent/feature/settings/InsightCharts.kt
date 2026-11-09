package com.zmf.agent.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.zmf.agent.uikit.theme.Palette
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
 * 用量折线：底部一条轴线、一条平滑折线、线下方渐变填充、末点一个圆点。
 *
 * 按最大值归一（顶部留 8% 余量，避免峰值贴边）；值全 0 时只画轴线。
 * [labels] 与 [values] 等长，空串表示该点不标日期——标签由调用方抽稀，
 * 这里按「首点贴左、末点贴右、其余居中于点」绘制，保证字与点严格对齐。
 */
@Composable
internal fun UsageLineChart(
    values: List<Long>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp
) {
    val axis = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Palette.DarkStroke else Palette.LightStroke
    val fade = color.copy(alpha = 0f)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    )
    // 标签区随字号缩放，避免大字体下轴标签被画布下边缘裁掉（1x 时为 18dp）
    val labelBandDp = with(LocalDensity.current) { labelStyle.fontSize.toDp() } + 8.dp
    Canvas(modifier.fillMaxWidth().height(height + labelBandDp)) {
        val labelBand = labelBandDp.toPx()
        val width = size.width
        val high = size.height - labelBand
        // 左右各留出半个点的位置：否则首末点的圆会被画布边缘切掉一半
        val inset = 4.dp.toPx()
        val plotWidth = (width - inset * 2).coerceAtLeast(1f)
        drawLine(axis, Offset(0f, high), Offset(width, high), strokeWidth = 1.dp.toPx())
        if (values.isEmpty()) return@Canvas
        val peak = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val usable = high * 0.92f
        val stepX = if (values.size > 1) plotWidth / (values.size - 1) else 0f
        val points = values.mapIndexed { index, value ->
            val ratio = (value.toFloat() / peak).coerceIn(0f, 1f)
            Offset(
                x = if (values.size > 1) inset + index * stepX else width / 2f,
                y = high - usable * ratio
            )
        }
        if (points.size > 1) {
            // 用「相邻中点」做三次贝塞尔的控制点，线条平滑但不过冲
            val line = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (index in 1 until points.size) {
                    val previous = points[index - 1]
                    val current = points[index]
                    val midX = (previous.x + current.x) / 2f
                    cubicTo(midX, previous.y, midX, current.y, current.x, current.y)
                }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(points.last().x, high)
                lineTo(points.first().x, high)
                close()
            }
            drawPath(area, brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.26f), fade)))
            drawPath(line, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        drawCircle(color = color, radius = 3.5.dp.toPx(), center = points.last())

        /*
         * 轴标签：调用方已经抽稀过一次，但窄屏 + 点数变化仍可能让相邻两字重叠
         * （例如近 30 天时 `10-26` 与 `10-29` 会重叠）。这里按实际字宽做碰撞剔除：
         * 先固定首末两个标签，其余按从左到右依次判断是否放得下，保证任何宽度下都不重叠。
         */
        val textGap = 4.dp.toPx()
        class LabelBox(val left: Float, val text: androidx.compose.ui.text.TextLayoutResult)

        val boxes = ArrayList<LabelBox?>(labels.size)
        for (index in labels.indices) {
            val text = labels[index]
            if (text.isEmpty() || index >= points.size) {
                boxes.add(null)
                continue
            }
            val layout = measurer.measure(text, labelStyle)
            val textWidth = layout.size.width.toFloat()
            val center = points[index].x
            val left = when (index) {
                0 -> 0f
                points.lastIndex -> width - textWidth
                else -> center - textWidth / 2f
            }
            boxes.add(LabelBox(left.coerceIn(0f, (width - textWidth).coerceAtLeast(0f)), layout))
        }
        val occupied = ArrayList<FloatArray>(boxes.size)
        val tryDraw: (Int) -> Unit = draw@{ index ->
            val box = boxes[index] ?: return@draw
            val boxWidth = box.text.size.width.toFloat()
            val right = box.left + boxWidth
            for (range in occupied) {
                if (box.left < range[1] + textGap && right > range[0] - textGap) return@draw
            }
            occupied.add(floatArrayOf(box.left, right))
            drawText(
                textLayoutResult = box.text,
                topLeft = Offset(box.left, high + 4.dp.toPx())
            )
        }
        tryDraw(0)
        if (labels.lastIndex > 0) tryDraw(labels.lastIndex)
        for (index in 1 until labels.lastIndex) tryDraw(index)
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
