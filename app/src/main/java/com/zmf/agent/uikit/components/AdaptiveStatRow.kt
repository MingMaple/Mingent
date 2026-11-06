package com.zmf.agent.uikit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * 「左数值 + 左单位 + 右数值 + 右说明」固定组合的单行统计头。
 *
 * 这类组合不允许换行、不允许省略、不允许删单位：单位一旦被截掉，读数就没有量纲。
 * 收敛顺序固定为「先压间距，再按比例缩字号」：
 *
 * 1. 左右两组的间距从 6dp 降到 2dp；
 * 2. 整体在 1.0 → 0.70 之间寻找最大可容纳比例；
 * 3. 展示型数字下限 0.75，说明小字下限 0.70（小字可以比数字更小）。
 *
 * 展示型数字不随应用内字号档位放大：以 `valueSizeSp / fontScale` 作为基准，
 * 再乘收敛比例，效果是数字始终保持约 24dp 的视觉尺寸，只有正文与说明跟随档位。
 * 测量使用 [rememberTextMeasurer]，按文案、可用宽度与字号档位缓存，不额外组合子树。
 */
@Composable
fun AdaptiveStatRow(
    leftValue: String,
    leftUnit: String,
    rightValue: String,
    rightLabel: String,
    modifier: Modifier = Modifier,
    valueSizeSp: Float = 24f,
    noteSizeSp: Float = 11f,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    noteColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(0.01f)
    // 展示型数字的基准字号：先抵消应用内字号档位，再按收敛比例缩小
    val valueBaseSp = valueSizeSp / fontScale
    val valueStyle = TextStyle(fontSize = valueBaseSp.sp, fontWeight = FontWeight.SemiBold)
    val noteStyle = TextStyle(fontSize = noteSizeSp.sp)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val available = maxWidth
        // 预留 8dp：TextMeasurer 与最终排版之间存在字符级取整差异，
        // 只留 1dp 时最后一个字会被 Text 自身的约束裁掉（实测「上下文已用」少一个「用」）
        val budget = available - 8.dp

        val widths = remember(leftValue, leftUnit, rightValue, rightLabel, fontScale, budget) {
            fun measure(text: String, style: TextStyle): Dp =
                with(density) { measurer.measure(AnnotatedString(text), style, softWrap = false).size.width.toDp() }
            floatArrayOf(
                measure(leftValue, valueStyle).value,
                measure(leftUnit, noteStyle).value,
                measure(rightValue, valueStyle).value,
                measure(rightLabel, noteStyle).value
            )
        }

        val baseGap = 6.dp
        val tightGap = 2.dp
        val labelGap = 3.dp

        fun total(gap: Dp, scale: Float): Float {
            val number = max(scale, 0.75f)
            val note = max(scale, 0.70f)
            return widths[0] * number + widths[1] * note +
                widths[2] * number + widths[3] * note +
                (gap + labelGap).value
        }

        val scale: Float
        val gap: Dp
        if (total(baseGap, 1f) <= budget.value) {
            gap = baseGap
            scale = 1f
        } else if (total(tightGap, 1f) <= budget.value) {
            gap = tightGap
            scale = 1f
        } else {
            var s = 1f
            while (s > 0.70f && total(tightGap, s) > budget.value) s -= 0.02f
            gap = tightGap
            scale = s.coerceIn(0.70f, 1f)
        }

        val numberScale = max(scale, 0.75f)
        val noteScale = max(scale, 0.70f)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Start
        ) {
            Text(
                text = leftValue,
                fontSize = (valueBaseSp * numberScale).sp,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
            Text(
                text = leftUnit,
                modifier = Modifier.padding(bottom = 1.dp),
                fontSize = (noteSizeSp * noteScale).sp,
                color = noteColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
            Spacer(Modifier.width(gap))
            Text(
                text = rightValue,
                fontSize = (valueBaseSp * numberScale).sp,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
            Text(
                text = rightLabel,
                modifier = Modifier.padding(start = labelGap, bottom = 1.dp),
                fontSize = (noteSizeSp * noteScale).sp,
                color = noteColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
}
