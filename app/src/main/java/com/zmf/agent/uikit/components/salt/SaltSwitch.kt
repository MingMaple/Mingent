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
 * Salt 风开关：不用 Material3 的 Switch，自己拿 Box + offset 拼。
 *
 * 几何全部照 Salt Player 的实拍取样（1440×3200 截图，360dp 屏，1 图像素 = 0.25dp）：
 *
 * - 轨道 **46 × 26dp**，圆角 13（全圆）；
 * - 滑块高 15dp、垂直居中（上内距 5.5dp）；
 * - 关态滑块是 **15dp 圆**，中心距左端 12.75dp；
 * - 开态滑块被压成 **4dp 宽的竖条**，圆角 2，中心距右端 12.75dp；
 *   位移 20.5dp，两个中心都离轨道端 12.75dp，所以视觉上是「对称地走到对面」。
 *
 * 滑块形态随状态变，是 Salt 的识别特征：关是圆点、开是一道竖条。
 * 这样在低对比下也能靠**形状**分辨开关状态，不只靠颜色。
 *
 * 配色：轨道开态直接复用 [UiColors.accentFill]（浅 `#4176E6` / 深 `#7AAAFF`）。
 * Salt 也是「浅色取深的一档、深色取亮的一档」，与我们的强调色分工一致；
 * 它那支偏紫的 `#4D5C93` 不照搬，色相仍用我们的蓝。
 * 滑块两态都是纯白——这是 Salt 的做法，开启态白压淡蓝只有 2.3:1，
 * 但开关的可辨性主要来自「轨道 vs 背景」（深色下 7.1:1）与滑块位置，够用。
 *
 * 动画走 [Motion.spring]；开关本体也有涟漪（开与关两个方向都要有按压反馈）：
 * 形状由轨道自己的 13dp 圆角裁，颜色同 `saltCardRow`，不要 Material 默认那层带品牌色的 ripple。
 *
 * 无障碍：clickable 带 role = Role.Switch，再补 toggleableState，
 * 悬浮朗读是「开关，已关闭 / 已开启」。
 * 这里没用 Modifier.toggleable(...)：本工程当前 foundation 版本里四参数那个重载已被隐藏。
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

    // 三个量都跟着同一个 progress 走，所以形状与位置同步变，不会出现「先变形再滑动」
    val thumbWidth = 15f - 11f * progress      // 15dp → 4dp
    val thumbCenterX = 12.75f + 20.5f * progress
    val thumbLeft = thumbCenterX - thumbWidth / 2f

    // 焦点环与波纹分开读：键盘/方向键把焦点移上来才出环，手指点只出波纹
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState().value
    val focusRing = RoundedCornerShape(13.dp)
    val ringColor = LocalAccentFg.current

    Box(
        modifier = modifier
            .size(width = 46.dp, height = 26.dp)
            .then(if (focused && enabled) Modifier.border(2.dp, ringColor, focusRing) else Modifier)
            // 禁用态整体压暗；clickable 的 enabled 一并关掉，点了不响应
            .alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(13.dp))
            .background(trackColor)
            // 轨道和滑块都在这个盒子里，点哪都算点到开关；
            // 涟漪用 ripple(bounded) 而不是默认 indication：形状被上面的 clip 裁，
            // 颜色跟整行统一，开关开着关着都有反馈
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
            // 不带这个的话朗读只知道「可点击」，不知道当前是开还是关
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
