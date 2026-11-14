package com.zmf.agent.feature.settings

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.prefs.FontSizeStore
import com.zmf.agent.uikit.icons.ChevronDownIcon

/** 连点节流窗口：间隔小于该值的点击被丢弃，避免每次点击都触发全量文字重排。 */
private const val MinStepIntervalMs = 120L

/** 步进器容器高度：容纳上下两枚箭头与中间的数字，不改变 48dp 行高。 */
private val StepperBoxHeight = 44.dp

/** 步进器容器宽度：数字单行居中，两侧留出箭头热区。 */
private val StepperBoxWidth = 40.dp

/** 步进器圆角：与设置页其他胶囊控件一致。 */
private val StepperShape = RoundedCornerShape(10.dp)

/**
 * 通用设置里的字号步进行：标题 + 绝对 sp 数值 + 增大／减小两枚箭头。
 *
 * - 数字位于步进器容器内，向上箭头在数字上方、向下箭头在下方；单位 sp 在容器外，行高保持 48dp。
 * - 到达上下限时对应按钮置灰且不响应点击，数值本身永不越界。
 * - 连点间隔小于 120ms 的点击被丢弃，状态即时生效、落盘随每次生效写入。
 * - TalkBack：数值播报「字号 N sp」，两枚按钮分别播报「增大字号」「减小字号」，
 *   到达边界时追加「已到最大字号」「已到最小字号」。
 *
 * @param baseFontSp 当前基准字号（sp），由主题入口下发，取值范围为 [FontSizeStore.MIN_SP]–[FontSizeStore.MAX_SP]
 * @param onBaseFontSpChange 用户调整字号后的回调；只在取值实际变化时触发
 */
@Composable
internal fun FontSizeStepperRow(
    baseFontSp: Int,
    onBaseFontSpChange: (Int) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val canIncrease = baseFontSp < FontSizeStore.MAX_SP
    val canDecrease = baseFontSp > FontSizeStore.MIN_SP
    var lastAcceptedAt by remember { mutableLongStateOf(0L) }

    fun step(delta: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAcceptedAt < MinStepIntervalMs) return
        lastAcceptedAt = now
        val next = (baseFontSp + delta).coerceIn(FontSizeStore.MIN_SP, FontSizeStore.MAX_SP)
        if (next != baseFontSp) onBaseFontSpChange(next)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "字号大小",
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface
        )
        // 数字位于容器内，向上箭头在数字上方、向下箭头在下方；单位 sp 留在容器外
        Column(
            modifier = Modifier
                .width(StepperBoxWidth)
                .height(StepperBoxHeight)
                .clip(StepperShape)
                .background(onSurface.copy(alpha = 0.06f)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StepperArrow(
                upward = true,
                enabled = canIncrease,
                hint = "增大字号",
                boundaryHint = "已到最大字号",
                modifier = Modifier.weight(1f),
                onClick = { step(FontSizeStore.STEP_SP) }
            )
            Text(
                text = "$baseFontSp",
                modifier = Modifier.semantics {
                    contentDescription = "字号 $baseFontSp sp"
                    liveRegion = LiveRegionMode.Polite
                },
                fontSize = 15.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                color = onSurface
            )
            StepperArrow(
                upward = false,
                enabled = canDecrease,
                hint = "减小字号",
                boundaryHint = "已到最小字号",
                modifier = Modifier.weight(1f),
                onClick = { step(-FontSizeStore.STEP_SP) }
            )
        }
        Text(
            text = "sp",
            modifier = Modifier.padding(start = 2.dp),
            fontSize = 15.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
    }
}

/** 单枚步进箭头：占步进器一半高度，图形 12dp，无涟漪；置灰时仅降低不透明度。 */
@Composable
private fun StepperArrow(
    upward: Boolean,
    enabled: Boolean,
    hint: String,
    boundaryHint: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val ink = MaterialTheme.colorScheme.onSurface
    // 描述挂在图标、点击与禁用态挂在同一个可点击容器：读屏只会看到一个节点，
    // 不会出现「描述节点说可用、可点击节点才不可点」的分裂。
    // 到边界时把提示并入描述，避免再引入第二个语义节点。
    val label = if (enabled) hint else "$hint，$boundaryHint"
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = ChevronDownIcon,
            contentDescription = label,
            tint = ink.copy(alpha = 0.6f),
            modifier = Modifier
                .size(12.dp)
                .rotate(if (upward) 180f else 0f)
        )
    }
}
