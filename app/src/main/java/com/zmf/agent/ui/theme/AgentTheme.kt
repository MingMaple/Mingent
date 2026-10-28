package com.zmf.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.moriafly.salt.ui.SaltColors
import com.moriafly.salt.ui.SaltConfigs
import com.moriafly.salt.ui.SaltDynamicColors
import com.moriafly.salt.ui.SaltTheme

private fun saltLight() = SaltColors(
    highlight = Palette.AccentBlue,
    text = Palette.LightText,
    subText = Palette.LightSubText,
    background = Palette.LightBackground,
    subBackground = Palette.LightSurface,
    popup = Palette.LightSurfaceHigh,
    stroke = Palette.LightStroke,
    onHighlight = Palette.LightSurfaceHigh,
    success = Palette.AccentGreen,
    warning = Palette.AccentPurple,
    error = androidx.compose.ui.graphics.Color(0xFFE5484D)
)

private fun saltDark() = SaltColors(
    highlight = Palette.AccentBlue,
    text = Palette.DarkText,
    subText = Palette.DarkSubText,
    background = Palette.DarkBackground,
    subBackground = Palette.DarkSurface,
    popup = Palette.DarkSurfaceHigh,
    stroke = Palette.DarkStroke,
    onHighlight = Palette.DarkText,
    success = Palette.AccentGreen,
    warning = Palette.AccentPurple,
    error = androidx.compose.ui.graphics.Color(0xFFFF6369)
)

/**
 * 全局主题。
 *
 * [uiScale] 只乘到 fontScale 上：所有 sp 字号等比放大，dp 尺寸（图标、间距、圆角）不动。
 * 这样用户调字号时布局关系保持不变，不会出现「特大」把一屏挤成两行的情况。
 * 合法性由 UiScaleStore 保证（load 已经回落过），这里不再兜底。
 */
@Composable
fun AgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    uiScale: Float = 1f,
    content: @Composable () -> Unit
) {
    // 必须在 provider 外层读：provider 内部读到的已经是放大过的 density，
    // 那样每进一次组合都会再乘一遍，字号会越套越大。
    val outerDensity = LocalDensity.current
    val scaledDensity = Density(
        density = outerDensity.density,
        fontScale = outerDensity.fontScale * uiScale
    )
    val colors = if (darkTheme) saltDark() else saltLight()
    val material = if (darkTheme) {
        darkColorScheme(
            primary = Palette.AccentBlue,
            background = Palette.DarkBackground,
            surface = Palette.DarkSurface,
            onBackground = Palette.DarkText,
            onSurface = Palette.DarkText,
            // 显式给 error：Material3 深色默认是 #FFB4AB（淡粉），断网红点就成了粉色
            error = androidx.compose.ui.graphics.Color(0xFFFF6369)
        )
    } else {
        lightColorScheme(
            primary = Palette.AccentBlue,
            background = Palette.LightBackground,
            surface = Palette.LightSurface,
            onBackground = Palette.LightText,
            onSurface = Palette.LightText,
            error = androidx.compose.ui.graphics.Color(0xFFE5484D)
        )
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        SaltTheme(
            configs = SaltConfigs.default(isDarkTheme = darkTheme),
            dynamicColors = SaltDynamicColors(light = saltLight(), dark = saltDark())
        ) {
            // 选中文字的光标手柄与选择底色统一走灰阶/绿，不再跟随 colorScheme.primary（蓝）。
            // Material 默认 primary 是 Palette.AccentBlue，于是每个可点进去的输入框
            // 点击后都是蓝色。这里一次性把全 App 的选择色钉住，省得每个 TextField 各写一遍。
            CompositionLocalProvider(
                LocalTextSelectionColors provides TextSelectionColors(
                    handleColor = material.onSurface.copy(alpha = 0.7f),
                    backgroundColor = Palette.AccentGreen.copy(alpha = 0.30f)
                )
            ) {
                MaterialTheme(colorScheme = material, content = content)
            }
        }
    }
}
