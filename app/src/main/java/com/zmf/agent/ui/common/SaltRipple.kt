package com.zmf.agent.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 设置页里「整行可点」的统一写法：先按行自己的形状裁剪，再挂 bounded 水波纹。
 *
 * 为什么不直接用 `Modifier.clickable {}`：默认的 indication 是不裁剪的方形波纹，
 * 铺在圆角卡片里就会在四角露出方块，一眼就是没裁干净；
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

/**
 * 卡片内整行的标准写法：**不自己画圆角**，交给卡片自身的 `clip(RoundedCornerShape(16.dp))` 去裁。
 *
 * 这样得到的就是同一套判定（对齐 Salt Player）：
 *  - 首行：波纹顶部被卡片上圆弧裁掉 → 上圆下平；
 *  - 中间行：上下都不碰圆弧 → 纯矩形，不是圆角框；
 *  - 末行：波纹底部被卡片下圆弧裁掉 → 上平下圆；
 *  - 只有一行：四角都跟卡片圆弧（卡片裁的就是四角）。
 *
 * 前提：行必须**横向通栏**（卡片内的 Column 不能有 horizontal padding，行的内距自己带）。
 * 卡片 Column 若有 vertical padding，首/末行与圆弧之间会露一条小直边：那种情况
 * 改用 [saltRowClickable] 显式给角（`RoundedCornerShape(16.dp)`）。
 *
 * `saltRowShape(slot, corner)` + `RowSlot` 枚举那套写法已经删掉：全仓没有调用方，
 * 而且旧注释里还写着「设置页统一 22dp」，与现行 16dp 口径冲突，留着只会误导下一个人。
 */
@Composable
fun Modifier.saltCardRow(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    saltRowClickable(RectangleShape, enabled, onClick)
