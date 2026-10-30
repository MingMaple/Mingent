package com.zmf.agent.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
            val stroke = 4.dp.toPx()
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
            Text(
                text = centerTop,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface
            )
            Text(
                text = centerBottom,
                fontSize = 12.sp,
                color = onSurface.copy(alpha = 0.45f)
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
        modifier = modifier.fillMaxWidth().height(30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            fontSize = 14.sp,
            color = onSurface
        )
        Text(
            text = (if (tokens > 0) "≈" + tokenText(tokens) else "—") +
                (if (fraction != null) "  " + percentText(fraction) else ""),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f)
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
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            fontSize = 14.sp,
            color = onSurface
        )
        Text(text = value, fontSize = 12.sp, color = onSurface.copy(alpha = 0.45f))
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

/** 时长按量级写成人话。 */
internal fun durationText(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000L
    if (totalSeconds < 60L) return totalSeconds.toString() + " 秒"
    val minutes = totalSeconds / 60L
    if (minutes < 60L) {
        val seconds = totalSeconds % 60L
        return minutes.toString() + ":" + (if (seconds < 10L) "0$seconds" else seconds.toString())
    }
    val hours = minutes / 60L
    if (hours < 24L) return hours.toString() + " 小时 " + (minutes % 60L).toString() + " 分"
    return (hours / 24L).toString() + " 天 " + (hours % 24L).toString() + " 小时"
}
