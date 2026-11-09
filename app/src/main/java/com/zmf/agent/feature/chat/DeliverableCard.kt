package com.zmf.agent.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.model.FileKinds
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors

/**
 * 回合末的产物卡片：位于收尾正文与动作行之间，点击进预览页。
 *
 * 默认只列三条：卡片是「这一轮产出了什么」的索引，不是文件管理器。四行以上会把一条回复
 * 撑成一屏半，正文被挤到看不见；其余条目就地展开，因为预览页只装用户点开过的文件，
 * 跳过去也看不到那些没点开过的条目。
 */
@Composable
internal fun DeliverableCards(
    files: List<ProducedFile>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (files.isEmpty()) return
    var expanded by remember(files.size) { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) Palette.DarkSurfaceHigh else Palette.LightSurfaceHigh
    val shown = if (expanded) files else files.take(MAX_VISIBLE)

    Column(
        modifier = modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (files.size == 1) "产出文件" else "本轮产出 " + files.size + " 个文件",
            fontSize = 11.sp,
            color = UiColors.subText
        )
        for (file in shown) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(cardColor)
                    .border(1.dp, UiColors.stroke, RoundedCornerShape(10.dp))
                    .clickable { onOpen(file.path) }
                    // 高度压到约 44dp：一行放得下文件名与类型，不再让类型另占一行
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FileGlyph(tint = UiColors.subText)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = file.name,
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = FileKinds.label(file.kind),
                    fontSize = 11.sp,
                    color = UiColors.subText,
                    maxLines = 1
                )
                Spacer(Modifier.width(6.dp))
                Text(text = "›", fontSize = 15.sp, color = UiColors.subText)
            }
        }
        if (!expanded && files.size > MAX_VISIBLE) {
            Text(
                text = "查看其余 " + (files.size - MAX_VISIBLE) + " 个",
                modifier = Modifier.clickable { expanded = true }.padding(vertical = 4.dp),
                fontSize = 12.sp,
                color = UiColors.accentFg
            )
        }
    }
}

private const val MAX_VISIBLE = 3
