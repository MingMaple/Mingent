package com.zmf.agent.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import com.zmf.agent.ui.theme.Motion
import com.zmf.agent.ui.theme.Palette

/**
 * Salt 风开关：不用 Material3 的 Switch，自己拿 Box + offset 拼。
 *
 * - 轨道 52×32 全圆角，滑块 26dp，内边距 3dp，可走距离 52 - 26 - 3×2 = 20dp
 * - 开：轨道吃 Salt 绿（Palette.AccentGreen），关：轨道用 onSurface 的 12% 淡影
 * - 开：滑块用近白 0xFFF7F7FA（不用 colorScheme.surface，深色主题下深灰压绿轨显脏）；
 *   关：滑块仍是 colorScheme.surface，靠下面那圈 1dp 描边分边界
 * - 关着时滑块和底色容易糊在一起，补一圈 1dp 描边（按底色亮度在 Light/DarkStroke 里选）
 * - 位移走 Motion.spring() 弹簧，indication 置空，不要 Material 的 ripple
 * - 无障碍：clickable 带 role = Role.Switch，再补 toggleableState，
 *   悬浮朗读是「开关，已关闭 / 已开启」。
 *   这里没用 Modifier.toggleable(...)：本工程当前 foundation 1.12.1 里
 *   四参数那个重载已经是 @Deprecated(level = HIDDEN)，源码层面调不到。
 */
@Composable
fun SaltSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    // 描边颜色跟 App 里其它浮层一样，按底色亮度选，不写死单色
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val strokeColor = if (isDark) Palette.DarkStroke else Palette.LightStroke
    // 开启态滑块压在绿轨上，近白最干净；关闭态轨道是淡影，滑块继续用 surface + 描边
    val thumbColor = if (checked) Color(0xFFF7F7FA) else MaterialTheme.colorScheme.surface
    val trackColor = if (checked) {
        Palette.AccentGreen
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }

    // 0f = 关，1f = 开；弹簧位移而不是线性补间
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = Motion.spring(),
        label = "saltSwitchThumb"
    )

    val thumbX = 3.dp + 20.dp * progress

    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            // 禁用态整体压暗；clickable 的 enabled 一并关掉，点了不响应
            .alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(16.dp))
            .background(trackColor)
            // 轨道和滑块都在这个盒子里，点哪都算点到开关
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) }
            )
            // 不带这个的话朗读只知道「可点击」，不知道当前是开还是关
            .semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbX, y = 3.dp)
                .size(26.dp)
                .background(thumbColor, CircleShape)
                // 关着的时候滑块坐在淡色轨道上，加描边才有边界；开着落在绿轨上就不需要了
                .then(if (checked) Modifier else Modifier.border(1.dp, strokeColor, CircleShape))
        )
    }
}
