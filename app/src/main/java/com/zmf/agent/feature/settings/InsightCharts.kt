package com.zmf.agent.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 环形图：segments 是（比例, 颜色）；中心两行字由调用方给。 */
@Composable
internal fun DonutRing(
    segments: List<Pair<Float, Color>>,
    size: Dp,
    centerTop: String,
    centerBottom: String,
    modifier: Modifier = Modifier
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
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
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
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

/** 近 N 天柱状图：按最大值归一，圆头柱；值全 0 时画轨道。 */
@Composable
internal fun SimpleBarChart(
    values: List<Long>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRoundRect(color = track, cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()))
        if (values.isEmpty()) return@Canvas
        val peak = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val gap = 8.dp.toPx()
        val barWidth = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(1f)
        val minHeight = 2.dp.toPx()
        values.forEachIndexed { index, value ->
            val barHeight = (size.height * (value.toFloat() / peak))
                .coerceIn(minHeight, size.height)
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(minOf(3.dp.toPx(), barWidth / 2f))
            )
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
        modifier = modifier.fillMaxWidth().height(24.dp),
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

/** 段落图例（只看百分比 / 时长，具体串由调用方拼好）。 */
@Composable
internal fun LegendPlain(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier.fillMaxWidth().height(34.dp),
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
            fontSize = 14.sp,
            color = onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f),
            maxLines = 1,
            softWrap = false
        )
    }
}

/** token 数缩写：百万级 M、万级 K，都留一位小数；一万以下原样。 */
internal fun tokenText(tokens: Long): String = when {
    tokens >= 1_000_000 -> oneDecimal(tokens / 1_000_000.0) + "M"
    tokens >= 10_000 -> oneDecimal(tokens / 1_000.0) + "K"
    else -> tokens.toString()
}

/** 比率百分比：一位小数。 */
internal fun percentText(ratio: Float): String =
    oneDecimal(ratio.coerceIn(0f, 1f) * 100.0) + "%"

private fun oneDecimal(value: Double): String {
    val v = (value * 10).toInt() / 10.0
    return v.toString()
}

/**
 * 时长按 DSH 的紧凑写法：秒 / 分秒 / 时分 / 天时。
 *
 * 例：`17s`、`3m17s`、`13h38m`、`2d10h`。中文「2 天 10 小时」在环形中心会被挤成两行，
 * 所以这里不写中文单位。
 */
internal fun durationText(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) + 999L) / 1000L
    if (totalSeconds < 60L) return totalSeconds.toString() + "s"
    val minutes = totalSeconds / 60L
    if (minutes < 60L) return minutes.toString() + "m" + (totalSeconds % 60L).toString() + "s"
    val hours = minutes / 60L
    if (hours < 24L) return hours.toString() + "h" + (minutes % 60L).toString() + "m"
    val days = hours / 24L
    return days.toString() + "d" + (hours % 24L).toString() + "h"
}
