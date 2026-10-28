package com.zmf.agent.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/**
 * 文本输入框的统一配色。
 *
 * 为什么要单独抽一份：Material 3 的文本域默认从 `colorScheme.primary` 取焦点边框与光标颜色，
 * 而本应用的主题把这个位置留给了 `Palette.AccentBlue`（#0078FF）。结果凡是没显式传 `colors`
 * 的输入框，一旦获得焦点就刷出一圈饱和蓝，在单色界面里格外突兀：模型页、定时任务、包管理、
 * 日志搜索四处都踩过同一个坑。
 *
 * 因此约定：界面里所有文本输入框都必须使用本文件的配色，不再依赖默认值。
 * 强调一律靠同色的浓淡，不引入第二种色相。
 */

/** `OutlinedTextField` 用：焦点边框与光标取 onSurface，容器透明。 */
@Composable
fun saltFieldColors(): TextFieldColors {
    val onSurface = MaterialTheme.colorScheme.onSurface
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = onSurface.copy(alpha = 0.45f),
        unfocusedBorderColor = onSurface.copy(alpha = 0.15f),
        focusedTextColor = onSurface,
        unfocusedTextColor = onSurface.copy(alpha = 0.9f),
        cursorColor = onSurface,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedPlaceholderColor = onSurface.copy(alpha = 0.35f),
        unfocusedPlaceholderColor = onSurface.copy(alpha = 0.35f)
    )
}

/**
 * `BasicTextField` 用：它不吃 [TextFieldColors]，光标靠 `cursorBrush` 单独给，
 * 不显式传同样会回落成主题的强调蓝。
 */
@Composable
fun saltCursorBrush(): Brush {
    return SolidColor(MaterialTheme.colorScheme.onSurface)
}
