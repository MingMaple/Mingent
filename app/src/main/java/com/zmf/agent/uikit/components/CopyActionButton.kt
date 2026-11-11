package com.zmf.agent.uikit.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zmf.agent.uikit.icons.CopyGlyph
import com.zmf.agent.uikit.icons.DshIcons

/** 常显的复制图标按钮：固定占位，成功态由调用方置位，避免右侧元素随之位移。 */

/** 按钮边长：与代码块工具条、气泡尾部按钮同档（30dp）。 */
private val CopyButtonSize = 30.dp

/** 图标本体尺寸：小于按钮，涟漪才不会贴住图形。 */
private val CopyButtonIconSize = 14.dp

/** 常态与成功态的不透明度：同一颜色只用浓淡区分，不引入第二种色相。 */
private const val CopyIdleAlpha = 0.5f
private const val CopyDoneAlpha = 0.92f

/**
 * 「复制」图标按钮：两张叠放的纸，点击成功后由调用方置为对勾，1.2 秒后复原。
 *
 * [ink] 传**不透明**的正文色，浓淡由本组件决定：气泡、工具卡、思考块、轨迹明细卡与
 * 代码块工具条共用这一份口径，不得各自再定一套透明度。
 *
 * [copied] 由调用方维护（点击时置 true，延时复位也由调用方负责），
 * 使同一块的「已复制」状态与复制内容一一对应。
 */
@Composable
fun CopyActionButton(
    copied: Boolean,
    ink: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(CopyButtonSize)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "复制", onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (copied) {
            Icon(
                imageVector = DshIcons.Check,
                contentDescription = null,
                tint = ink.copy(alpha = CopyDoneAlpha),
                modifier = Modifier.size(CopyButtonIconSize)
            )
        } else {
            CopyGlyph(tint = ink.copy(alpha = CopyIdleAlpha), iconSize = CopyButtonIconSize)
        }
    }
}
