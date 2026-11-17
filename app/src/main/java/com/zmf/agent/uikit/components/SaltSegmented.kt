package com.zmf.agent.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.common.saltRowClickable

/**
 * 单选分段控件：把上下文洞察页的单按钮图例合并为一把开关。
 *
 * 视觉参数与洞察页 `TabPill` 对齐：10dp 外圆角、内缩 2dp、选中 8dp 圆角实底、
 * 13sp 文字、横向 12dp／纵向 5dp 内距、未选中 `onSurface` 4% 淡底与 55% 文字；
 * 选中仍为墨底反白。轨道取 4% 淡底，两段相邻无描边。
 *
 * 尺寸只由文字与内距撑开，不设固定宽高：`13.sp` 会随「字号大小」的基准字号整体缩放，
 * 轨道与内距保持 dp 不变，符合全仓「只放大文字、不放大元素」的约定。
 */
@Composable
fun SaltSegmented(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .background(onSurface.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) onSurface else Color.Transparent)
                    .saltRowClickable(RoundedCornerShape(8.dp)) { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        onSurface.copy(alpha = 0.55f)
                    },
                    maxLines = 1
                )
            }
        }
    }
}
