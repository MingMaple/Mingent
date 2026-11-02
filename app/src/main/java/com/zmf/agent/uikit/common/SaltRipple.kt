package com.zmf.agent.uikit.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.theme.LocalAccentFg

/**
 * 设置页里「整行可点」的统一写法：先按行自己的形状裁剪，再挂 bounded 水波纹。
 *
 * 为什么不直接用 `Modifier.clickable {}`：默认的 indication 是不裁剪的方形波纹，
 * 铺在圆角卡片里就会在四角露出方块，一眼就是没裁干净。
 * 所以形状必须由调用方按「这一行真正所属的容器」传进来（卡片内首行/末行可以用带圆角的那两角，
 * 中间行直接矩形即可），这里只负责裁 + 波动。
 *
 * 波纹颜色取 onSurface 的 10%（深色 14%）：点击反馈是「按下处起一圈淡淡的扩散」，
 * 与 Salt Player 的手感一致。曾经试过整块平涂压暗（黑 12%／30%），实测在长列表里
 * 缺少「按下的是哪一行」的定位感，已回退。
 *
 * 深色为什么是 14% 而不是同一个 10%：DSH 的 `interactive-bg-active` 就是
 * 浅色 10% 墨、深色 14% 白——暗底上同一档透明度看起来更弱，要补三个点才等效。
 *
 * ## 焦点环
 *
 * 手指点、鼠标点都不出环，只有键盘 Tab / 方向键 / 手柄把焦点移上来时才画一圈 2dp 强调色描边
 * （`focus-visible` 的语义）。它回答的是「现在按回车会触发谁」，与按压波纹是两件事，
 * 所以不用同一个 interaction 状态：波纹读 Press，环读 Focus。
 *
 * 画在 `.clip(shape)` 之前，是外层修饰符，因此不会被形状裁掉；
 * 同样因为它是绘制而不是布局，加了环的行位置尺寸一点不变。
 */
@Composable
fun Modifier.saltRowClickable(
    shape: Shape,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val alpha = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.14f else 0.10f
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    // 焦点态与按压态分开读：同一个 interactionSource 上，Press 与 Focus 是不同的事件
    val focused = interaction.collectIsFocusedAsState().value
    val ringColor = LocalAccentFg.current
    return this
        .then(
            if (focused && enabled) Modifier.border(2.dp, ringColor, shape) else Modifier
        )
        .clip(shape)
        .clickable(
            interactionSource = interaction,
            indication = ripple(bounded = true, color = color),
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * 卡片内整行的标准写法：**不自己画圆角**，交给卡片自身的 `clip(RoundedCornerShape(16.dp))` 去裁。
 *
 * 这样得到的就是同一套判定：
 *  - 首行：波纹顶部被卡片上圆弧裁掉 → 上圆下平；
 *  - 中间行：上下都不碰圆弧 → 纯矩形，不是圆角框；
 *  - 末行：波纹底部被卡片下圆弧裁掉 → 上平下圆；
 *  - 只有一行：四角都跟卡片圆弧（卡片裁的就是四角）。
 *
 * 前提：行必须**横向通栏**（卡片内的 Column 不能有 horizontal padding，行的内距自己带）。
 * 卡片 Column 若有 vertical padding，首/末行与圆弧之间会露一条小直边：那种情况
 * 改用 [saltRowClickable] 显式给角（`RoundedCornerShape(16.dp)`）。
 */
@Composable
fun Modifier.saltCardRow(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    saltRowClickable(RectangleShape, enabled, onClick)

/**
 * 顶栏图标按钮：40dp 热区 + 10dp 圆角，波纹按自身圆角裁剪。
 *
 * 为什么不用裸 `Modifier.clickable`：默认 indication 不裁剪，按下去会在圆角按钮的
 * 四角露出方形波纹，和按钮形状对不上（计划页、浏览页原先都是这个问题）。
 * 这里统一走 [saltRowClickable]，顺带把键盘聚焦环也接上。
 *
 * @param glyph 图标字形（本 App 的顶栏一律用字形，不引图标包）
 * @param enabled 为 false 时只画淡、不接点击：与「点了没反应」区分开
 */
@Composable
fun TopIconButton(
    glyph: String,
    enabled: Boolean = true,
    fontSize: TextUnit = 20.sp,
    onClick: () -> Unit
) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(40.dp)
            .saltRowClickable(RoundedCornerShape(10.dp), enabled, onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            fontSize = fontSize,
            color = if (enabled) color else color.copy(alpha = 0.28f)
        )
    }
}
