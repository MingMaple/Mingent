package com.zmf.agent.uikit.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zmf.agent.uikit.theme.Palette

/**
 * 确认弹窗，只用于不可逆动作。
 *
 * 可逆动作改用 NoticeCenter.undo：先执行，再给反悔机会。
 */
@Composable
fun SaltConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** 危险动作：确认按钮使用错误色。 */
    destructive: Boolean = false,
    /** 追加在正文之后的说明块（如「其中包含内置目录」）。 */
    body: (@Composable ColumnScope.() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                body?.let {
                    Spacer(Modifier.height(10.dp))
                    it()
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "取消",
                        modifier = Modifier
                            .saltRowClickable(RoundedCornerShape(10.dp)) { onDismiss() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    Text(
                        text = confirmLabel,
                        modifier = Modifier
                            .saltRowClickable(RoundedCornerShape(10.dp)) { onConfirm() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (destructive) MaterialTheme.colorScheme.error else Palette.Neutral
                    )
                }
            }
        }
    }
}
