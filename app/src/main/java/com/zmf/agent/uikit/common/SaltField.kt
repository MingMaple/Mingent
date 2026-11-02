package com.zmf.agent.uikit.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.zmf.agent.uikit.theme.UiColors

/**
 * 文本输入框的统一配色。
 *
 * 分工：
 * - **未聚焦**：边框为中性淡描边，与卡片、分割线同一层级，避免分散注意力；
 * - **聚焦**：边框与光标转为强调蓝。这是全局四类允许使用强调蓝的场景之一（主操作、选中、聚焦、进行中），
 *   语义为「当前正在此处输入」，并非装饰。
 *
 * 必须统一的原因：Material 3 文本域默认从 `colorScheme.primary` 获取焦点边框与光标。
 * 本主题的 primary 为中性墨色，未显式指定时会呈现黑框黑光标；若将 primary
 * 改为品牌色，全局输入框又会同步变为蓝色。两种结果均通过本文件统一设置避免。
 *
 * 因此约定：界面中所有文本输入框必须使用本文件的配色，不依赖默认值。
 */

/** `OutlinedTextField` 用：聚焦转强调蓝，其余中性，容器透明。 */
@Composable
fun saltFieldColors(): TextFieldColors {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val focus = UiColors.accentFg
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = focus,
        unfocusedBorderColor = onSurface.copy(alpha = 0.15f),
        focusedTextColor = onSurface,
        unfocusedTextColor = onSurface.copy(alpha = 0.9f),
        cursorColor = focus,
        focusedLabelColor = focus,
        unfocusedLabelColor = onSurface.copy(alpha = 0.55f),
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedPlaceholderColor = onSurface.copy(alpha = 0.35f),
        unfocusedPlaceholderColor = onSurface.copy(alpha = 0.35f)
    )
}

/**
 * `BasicTextField` 用：它不接受 [TextFieldColors]，光标需通过 `cursorBrush` 单独设置。
 * 与 [saltFieldColors] 同源，均取强调蓝，避免两种输入框光标颜色不一致。
 */
@Composable
fun saltCursorBrush(): Brush {
    return SolidColor(UiColors.accentFg)
}
