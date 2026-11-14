package com.zmf.agent.uikit.components.salt

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.zmf.agent.uikit.theme.LocalAccentFg
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors

/**
 * Salt 风开关：不使用 Material3 的 Switch，自行以 Box + offset 实现。
 *
 * 几何参数取自 Salt Player 的实测采样（1440×3200 截图，360dp 屏幕，1 图像素 = 0.25dp）：
 *
 * - 轨道 **46 × 26dp**，圆角 13（全圆）；
 * - 滑块高 15dp、垂直居中（上内距 5.5dp）；
 * - 关态滑块为 **15dp 圆**，中心距左端 12.75dp；
 * - 开态滑块压缩为 **4dp 宽竖条**，圆角 2，中心距右端 12.75dp；
 *   位移 20.5dp，两个中心距轨道端均为 12.75dp，视觉上为对称移动。
 *
 * 滑块形态随状态变化，是 Salt 的识别特征：关闭为圆点、开启为竖条。
 * 因此在低对比度下也可通过**形状**区分开关状态，而非仅依赖颜色。
 *
 * 配色：轨道开启态直接复用 [UiColors.accentFill]（浅 `#4D5C93` / 深 `#B6C4FF`）。
 * Salt 同样为「浅色取较深一档、深色取较亮一档」，与本项目的强调色分工一致。
 * 滑块两态均为纯白，与 Salt 一致；开启态的白色滑块与轨道对比度较低，
 * 但开关可辨性主要来自「轨道与背景」的明度差与滑块位置，足以满足使用。
 *
 * 动画使用 [Motion.spring]；开关本体同样带涟漪（开启与关闭两个方向均需按压反馈）：
 * 形状由轨道自身的 13dp 圆角裁剪，颜色与 `saltCardRow` 一致，不使用 Material 默认带品牌色的 ripple。
 *
 * 无障碍：clickable 设置 role = Role.Switch，并补充 toggleableState，
 * 读屏朗读为「开关，已关闭 / 已开启」。
 * 此处未使用 Modifier.toggleable(...)：当前工程 foundation 版本中四参数重载已被隐藏。
 */
@Composable
fun SaltSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val trackColor = when {
        checked -> UiColors.accentFill
        isDark -> Palette.DarkSwitchOff
        else -> Palette.LightSwitchOff
    }

    // 0f = 关，1f = 开；弹簧位移而不是线性补间
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = Motion.spring(),
        label = "saltSwitchThumb"
    )

    // 三个量均随同一个 progress 变化，因此形状与位置同步更新，不会出现先变形再滑动
    val thumbWidth = 15f - 11f * progress      // 15dp → 4dp
    val thumbCenterX = 12.75f + 20.5f * progress
    val thumbLeft = thumbCenterX - thumbWidth / 2f

    // 焦点环与波纹分开读取：键盘或方向键将焦点移入时才显示焦点环，手指点击仅显示波纹
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState().value
    val focusRing = RoundedCornerShape(13.dp)
    val ringColor = LocalAccentFg.current

    Box(
        modifier = modifier
            .size(width = 46.dp, height = 26.dp)
            .then(if (focused && enabled) Modifier.border(2.dp, ringColor, focusRing) else Modifier)
            // 禁用态整体降低透明度；同时关闭 clickable 的 enabled，点击不响应
            .alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(13.dp))
            .background(trackColor)
            // 轨道与滑块均位于该盒子内，点击任意位置均视为点击开关；
            // 涟漪使用 ripple(bounded) 而非默认 indication：形状由上方 clip 裁剪，
            // 颜色与整行统一，开启与关闭状态均有反馈
            .clickable(
                interactionSource = interaction,
                indication = ripple(
                    bounded = true,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                ),
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) }
            )
            // 缺少该语义时读屏仅识别为「可点击」，无法获知当前开关状态
            .semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbLeft.dp, y = 5.5.dp)
                .size(width = thumbWidth.dp, height = 15.dp)
                // 关态半径 7.5（圆），开态半径 2（竖条），都等于「半个宽度」
                .background(Color.White, RoundedCornerShape((thumbWidth / 2f).dp))
        )
    }
}
