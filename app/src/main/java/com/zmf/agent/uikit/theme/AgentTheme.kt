package com.zmf.agent.uikit.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.moriafly.salt.ui.SaltColors
import com.moriafly.salt.ui.SaltConfigs
import com.moriafly.salt.ui.SaltDynamicColors
import com.moriafly.salt.ui.SaltTheme

/**
 * 强调文字色。
 *
 * 填充面用 [Palette.Accent]（深浅同值），文字与图标另有一支：深色下 [Palette.Accent] 对底色只有 3.28:1，
 * 撑不住正文要求的 4.5:1，必须换成 [Palette.DarkAccentFg]。
 */
val LocalAccentFg = compositionLocalOf { Palette.LightAccentFg }

/**
 * 按元素类别取色，而非按品牌色取色。
 *
 * 全局仅四类元素允许使用强调色：主操作填充、选中（文字与线条）、聚焦、进行中。
 * 其余一律使用中性色或语义色。选中**底色**不在此列，它由 [Palette.LightRowSelected] /
 * [Palette.DarkRowSelected] 的中性深浅承担，理由见 [Palette] 文件头。
 */
object UiColors {
    /** 选中项的文字、页签文字、光标。 */
    val accentFg: Color
        @Composable get() = LocalAccentFg.current
    /** 成功／已授权／可用。 */
    val success: Color
        @Composable get() = if (isDarkSurface()) Palette.DarkSuccess else Palette.LightSuccess
    /** 进行中／待确认。 */
    val warning: Color
        @Composable get() = if (isDarkSurface()) Palette.DarkWarning else Palette.LightWarning
    /** 选中项的底色：只给深浅，不染色。 */
    val rowSelected: Color
        @Composable get() = if (isDarkSurface()) Palette.DarkRowSelected else Palette.LightRowSelected
    /**
     * 主操作填充面：浅色 `#4176E6`，深色提亮到 `#7AAAFF`。
     *
     * 与 [accentFg] 分开存在代价，但合并会在深色模式下产生沉闷的暗蓝按钮。
     */
    val accentFill: Color
        @Composable get() = if (isDarkSurface()) Palette.AccentFillDark else Palette.AccentFill
    /** 压在 [accentFill] 上的图标：浅色白、深色墨。 */
    val onAccent: Color
        @Composable get() = if (isDarkSurface()) Palette.OnAccentDark else Palette.OnAccent
    /** 轨迹页：用户 / 输入。 */
    val traceUser: Color
        @Composable get() = if (isDarkSurface()) Palette.TraceUserDark else Palette.TraceUserLight
    /** 轨迹页：助手 / 模型。 */
    val traceAssistant: Color
        @Composable get() = if (isDarkSurface()) Palette.TraceAssistantDark else Palette.TraceAssistantLight
    /** 轨迹页：工具。 */
    val traceTool: Color
        @Composable get() = if (isDarkSurface()) Palette.TraceToolDark else Palette.TraceToolLight
    /** 轨迹页：上下文。 */
    val traceContext: Color
        @Composable get() = if (isDarkSurface()) Palette.TraceContextDark else Palette.TraceContextLight
    /** 描边与分割线。 */
    val stroke: Color
        @Composable get() = if (isDarkSurface()) Palette.DarkStroke else Palette.LightStroke
    /** 次要文字与图标。 */
    val subText: Color
        @Composable get() = if (isDarkSurface()) Palette.DarkSubText else Palette.LightSubText
    /** 上下文用量：数值与百分比。 */
    val meterPrimary: Color
        @Composable get() = if (isDarkSurface()) Palette.DarkMeterPrimary else Palette.LightMeterPrimary
    /** 上下文用量：明细名。 */
    val meterSecondary: Color
        @Composable get() = if (isDarkSurface()) Palette.DarkMeterSecondary else Palette.LightMeterSecondary
    /** 上下文用量：标题文字与圆环进度弧。 */
    val meterTertiary: Color
        @Composable get() = if (isDarkSurface()) Palette.DarkMeterTertiary else Palette.LightMeterTertiary
    /** 上下文用量：进度条底槽。 */
    val meterTrack: Color
        @Composable get() = if (isDarkSurface()) Palette.DarkMeterTrack else Palette.LightMeterTrack
    /** 上下文用量：圆环底槽。 */
    val meterRingTrack: Color
        @Composable get() = if (isDarkSurface()) Palette.DarkMeterRingTrack else Palette.LightMeterRingTrack
    /** 上下文用量：系统提示词一段。 */
    val meterSystem: Color
        @Composable get() = Palette.MeterSystem
    /** 上下文用量：工具定义一段。 */
    val meterTools: Color
        @Composable get() = Palette.MeterTools
    /** 上下文用量：对话消息一段。 */
    val meterMessages: Color
        @Composable get() = Palette.MeterMessages
}

/** 当前是否深色底。 */
@Composable
fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

private fun saltLight() = SaltColors(
    highlight = Palette.Accent,
    text = Palette.LightText,
    subText = Palette.LightSubText,
    background = Palette.LightBackground,
    subBackground = Palette.LightSurface,
    popup = Palette.LightSurfaceHigh,
    stroke = Palette.LightStroke,
    onHighlight = Palette.OnAccent,
    success = Palette.LightSuccess,
    warning = Palette.LightWarning,
    error = Palette.LightError
)

private fun saltDark() = SaltColors(
    highlight = Palette.Accent,
    text = Palette.DarkText,
    subText = Palette.DarkSubText,
    background = Palette.DarkBackground,
    subBackground = Palette.DarkSurface,
    popup = Palette.DarkSurfaceHigh,
    stroke = Palette.DarkStroke,
    onHighlight = Palette.OnAccent,
    success = Palette.DarkSuccess,
    warning = Palette.DarkWarning,
    error = Palette.DarkError
)

/**
 * 全局主题。
 *
 * 字号**不跟随系统**：Compose 的 fontScale 固定为 [uiScale]，系统字体大小设置不参与。
 * 调整整个 App 的字号只能通过设置中的字号选项（写入 [uiScale]）。
 * 理由：本 App 版式按 360dp 设计，系统字体会破坏行高、图标与圆角之间的比例关系；
 * 用户所需的「更大」是整体缩放，而非仅放大文字。
 * 合法性由 UiScaleStore 保证（load 已完成回落处理），此处不再重复校验。
 */
@Composable
fun AgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    uiScale: Float = 1f,
    content: @Composable () -> Unit
) {
    // 必须在 provider 外层读：provider 内部读到的已经是放大过的 density，
    // 否则每次进入组合都会再次相乘，字号持续放大。
    val outerDensity = LocalDensity.current
    val scaledDensity = Density(
        density = outerDensity.density,
        fontScale = uiScale
    )
    val colors = if (darkTheme) saltDark() else saltLight()
    // primary 必须为**中性墨色**，不得使用强调蓝。
    //
    // Material 默认配色将大量控件绑定在 primary 上：TextButton 的文字、输入框的焦点边框与光标、
    // 滑块、进度条、开关、勾选与单选。若 primary 设为蓝色，整屏控件会同时变蓝，
    // 而其中仅「主操作」应使用蓝色；这是「界面到处是蓝色」的根本原因。
    //
    // DSH 采用相同做法：其 `--primary` 为 oklch(20.5% 0 0)（浅色近黑）/ oklch(92.2% 0 0)（深色近白），
    // 蓝色仅出现在 `state-business-primary`，单独供给选中文字与下划线。
    // 此处采用相同分工：primary = 正文墨色，蓝色由 [Palette.Accent] 与 [LocalAccentFg] 显式提供。
    val material = if (darkTheme) {
        darkColorScheme(
            primary = Palette.DarkText,
            onPrimary = Palette.DarkBackground,
            primaryContainer = Palette.DarkSurfaceHigh,
            onPrimaryContainer = Palette.DarkText,
            secondary = Palette.DarkSubText,
            onSecondary = Palette.DarkBackground,
            tertiary = Palette.DarkSubText,
            background = Palette.DarkBackground,
            onBackground = Palette.DarkText,
            surface = Palette.DarkSurfaceHigh,
            onSurface = Palette.DarkText,
            surfaceVariant = Palette.DarkSurface,
            onSurfaceVariant = Palette.DarkSubText,
            outline = Palette.DarkStroke,
            outlineVariant = Palette.DarkStroke,
            error = Palette.DarkError,
            // 语义容器：M3 默认给的是固定粉红 #93000A，与本主题的红不同源，换成自家红降透明度
            errorContainer = Palette.DarkError.copy(alpha = 0.16f),
            onErrorContainer = Palette.DarkError
        )
    } else {
        lightColorScheme(
            primary = Palette.LightText,
            onPrimary = Palette.LightSurfaceHigh,
            primaryContainer = Palette.LightSurface,
            onPrimaryContainer = Palette.LightText,
            secondary = Palette.LightSubText,
            onSecondary = Palette.LightSurfaceHigh,
            tertiary = Palette.LightSubText,
            background = Palette.LightBackground,
            onBackground = Palette.LightText,
            surface = Palette.LightSurfaceHigh,
            onSurface = Palette.LightText,
            surfaceVariant = Palette.LightSurface,
            onSurfaceVariant = Palette.LightSubText,
            outline = Palette.LightStroke,
            outlineVariant = Palette.LightStroke,
            error = Palette.LightError,
            errorContainer = Palette.LightError.copy(alpha = 0.10f),
            onErrorContainer = Palette.LightError
        )
    }

    val accentFg = if (darkTheme) Palette.DarkAccentFg else Palette.LightAccentFg
    // 选中文字：手柄与底色均使用强调语义，不跟随 primary 的蓝色，因此无需在每个输入框重复设置。
    val selectionHandle = accentFg
    val selectionBackground = Palette.Accent.copy(alpha = 0.30f)

    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalAccentFg provides accentFg
    ) {
        SaltTheme(
            configs = SaltConfigs.default(isDarkTheme = darkTheme),
            dynamicColors = SaltDynamicColors(light = saltLight(), dark = saltDark())
        ) {
            CompositionLocalProvider(
                LocalTextSelectionColors provides TextSelectionColors(
                    handleColor = selectionHandle,
                    backgroundColor = selectionBackground
                )
            ) {
                MaterialTheme(colorScheme = material, content = content)
            }
        }
    }
}
