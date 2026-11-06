package com.zmf.agent.uikit.common

import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.theme.LocalAccentFg

/**
 * 设置页中「整行可点击」的统一写法：先按行自身的形状裁剪，再挂载 bounded 水波纹。
 *
 * 不直接使用 `Modifier.clickable {}` 的原因：默认 indication 为不裁剪的方形波纹，
 * 铺在圆角卡片中会在四角露出方形区域，明显未裁剪。
 * 因此形状必须由调用方按「该行实际所属的容器」传入（卡片内首行与末行可使用带圆角的两角，
 * 中间行使用矩形），此处仅负责裁剪与波纹。
 *
 * 波纹颜色取 onSurface 的 10%（深色 14%）：点击反馈为按下处起一圈淡扩散，
 * 与 Salt Player 的手感一致。曾尝试整块平涂压暗（黑 12%／30%），实测在长列表中
 * 缺少「按下的是哪一行」的定位感，已回退。
 *
 * 深色使用 14% 而非相同的 10%：DSH 的 `interactive-bg-active` 即为
 * 浅色 10% 墨、深色 14% 白；暗色背景上相同透明度视觉更弱，需提高三个百分点才能等效。
 *
 * ## 焦点环
 *
 * 手指与鼠标点击均不显示焦点环，仅键盘 Tab、方向键或手柄将焦点移入时绘制 2dp 强调色描边
 * （`focus-visible` 语义）。它表示「当前回车将触发哪个元素」，与按压波纹不同，
 * 因此不使用同一 interaction 状态：波纹读取 Press，焦点环读取 Focus。
 *
 * 绘制于 `.clip(shape)` 之前，属于外层修饰符，因此不会被形状裁剪；
 * 同时由于仅为绘制而非布局，添加焦点环不改变行的位置与尺寸。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.saltRowClickable(
    shape: Shape,
    enabled: Boolean = true,
    /**
     * 长按动作；null 表示不区分长短按。
     *
     * 传入之后使用 [combinedClickable]：长按到阈值即触发本回调，**抬手不再触发 [onClick]**。
     * 会话行依赖这一点——长按只用于「露出操作入口」，不应顺带把用户带进那条会话。
     *
     * 位置在 [onClick] 之前：Kotlin 的尾随 lambda 绑定最后一个参数，
     * `saltCardRow { ... }` 这种写法必须继续落在 onClick 上。
     */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val alpha = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.14f else 0.10f
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    // 焦点态与按压态分开读：同一个 interactionSource 上，Press 与 Focus 是不同的事件
    val focused = interaction.collectIsFocusedAsState().value
    val ringColor = LocalAccentFg.current
    val decorated = this
        .then(
            if (focused && enabled) Modifier.border(2.dp, ringColor, shape) else Modifier
        )
        .clip(shape)
    return if (onLongClick == null) {
        decorated.clickable(
            interactionSource = interaction,
            indication = ripple(bounded = true, color = color),
            enabled = enabled,
            onClick = onClick
        )
    } else {
        decorated.combinedClickable(
            interactionSource = interaction,
            indication = ripple(bounded = true, color = color),
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick
        )
    }
}

/**
 * 卡片内整行的标准写法：**不自行绘制圆角**，交由卡片自身的 `clip(RoundedCornerShape(16.dp))` 裁剪。
 *
 * 由此得到统一判定：
 *  - 首行：波纹顶部被卡片上圆弧裁剪，呈上圆下平；
 *  - 中间行：上下均不触及圆弧，为纯矩形而非圆角框；
 *  - 末行：波纹底部被卡片下圆弧裁剪，呈上平下圆；
 *  - 仅一行：四角均跟随卡片圆弧（卡片本身裁剪四角）。
 *
 * 前提：行必须**横向通栏**（卡片内 Column 不得设置 horizontal padding，行的内边距由自身提供）。
 * 若卡片 Column 设置 vertical padding，首行与末行和圆弧之间会露出直边：此时
 * 改用 [saltRowClickable] 显式指定圆角（`RoundedCornerShape(16.dp)`）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.saltCardRow(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = saltRowClickable(RectangleShape, enabled, onLongClick, onClick)

/**
 * 顶栏图标按钮：40dp 热区 + 10dp 圆角，波纹按自身圆角裁剪。
 *
 * 不使用裸 `Modifier.clickable` 的原因：默认 indication 不裁剪，按下时会在圆角按钮
 * 四角露出方形波纹，与按钮形状不符（计划页、浏览页此前均存在该问题）。
 * 此处统一使用 [saltRowClickable]，同时接入键盘焦点环。
 *
 * @param glyph 图标字形（本 App 顶栏统一使用字形，不引入图标包）
 * @param enabled 为 false 时仅降低透明度，不响应点击，以便与「点击无反应」区分
 * @param glyphOffsetY 仅偏移字形，不移动热区。不同字形的墨迹中心高度不同
 *   （「⟳」偏低、「↗」偏高），同行并排时需分别校准，见 BrowserPage 顶栏。
 */
@Composable
fun TopIconButton(
    glyph: String,
    enabled: Boolean = true,
    fontSize: TextUnit = 20.sp,
    glyphOffsetY: Dp = 0.dp,
    onClick: () -> Unit
) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(40.dp)
            .saltRowClickable(RoundedCornerShape(10.dp), enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            modifier = Modifier.offset(y = glyphOffsetY),
            fontSize = fontSize,
            color = if (enabled) color else color.copy(alpha = 0.28f)
        )
    }
}
