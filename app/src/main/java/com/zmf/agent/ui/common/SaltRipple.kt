package com.zmf.agent.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape

/**
 * 设置页里「整行可点」的统一写法：先按行自己的形状裁剪，再挂 bounded 水波纹。
 *
 * 为什么不直接用 `Modifier.clickable {}`：默认的 indication 是不裁剪的方形波纹，
 * 铺在圆角卡片里就会在四角露出方块（用户原话「一个方块的很突兀」）；
 * 而各页面里手写的 `clip(RoundedCornerShape(18.dp))` 又常和卡片本身的 22dp 对不上，
 * 波纹沿 18dp 走、卡片是 22dp，弧边就对不齐。所以形状必须由调用方按「这一行真正所属的容器」
 * 传进来（卡片内首行/末行可以用带圆角的那两角，中间行直接矩形即可），这里只负责裁 + 波动。
 *
 * 波纹颜色取 onSurface 的 10%：Salt 风格里点击反馈是「按压一下深一点」，
 * 不是 Material 那种带品牌色的扩散。
 */
@Composable
fun Modifier.saltRowClickable(
    shape: Shape,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    return this
        .clip(shape)
        .clickable(
            interactionSource = interaction,
            indication = ripple(bounded = true, color = color),
            enabled = enabled,
            onClick = onClick
        )
}
