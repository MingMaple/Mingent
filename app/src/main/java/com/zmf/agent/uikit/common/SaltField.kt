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
 * - **未聚焦**：边框是中性淡描边，与卡片、分割线同一档，不抢注意力；
 * - **聚焦**：边框与光标转强调蓝。这是全 App 四类合法用蓝之一（主操作／选中／聚焦／进行中），
 *   含义是「现在在这里打字」，不是为了好看。
 *
 * 之所以必须统一：Material 3 的文本域默认从 `colorScheme.primary` 取焦点边框与光标。
 * 本主题的 primary 是中性墨色，不显式指定就会得到「黑框黑光标」；而一旦有人把 primary
 * 改回品牌色，全 App 又会同时刷蓝。两种后果都靠本文件一处收口避免。
 *
 * 因此约定：界面里所有文本输入框都必须使用本文件的配色，不再依赖默认值。
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
 * `BasicTextField` 用：它不吃 [TextFieldColors]，光标靠 `cursorBrush` 单独给。
 * 与 [saltFieldColors] 同源，都取强调蓝，避免两种输入框光标不同色。
 */
@Composable
fun saltCursorBrush(): Brush {
    return SolidColor(UiColors.accentFg)
}
