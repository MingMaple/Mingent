package com.zmf.agent.ui.chat
import com.zmf.agent.ui.common.blockTapsOnly

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.displayName
import com.zmf.agent.ui.theme.Palette

/** 三档各一句最短说明，超过一行会被省略号截掉：用户要的是「这一档能干什么」，不是一段原理。 */
private val PERMISSION_HINTS = mapOf(
    PermissionLevel.READ_ONLY to "只能读，不能改",
    PermissionLevel.WORKSPACE to "只在工作区内读写",
    PermissionLevel.FULL to "全盘读写"
)

/**
 * 「权限」页（侧边栏一级页面）：三档权限各一行，选中项右侧打勾。
 *
 * 每档只留一句最短的能力差异：用户要的是「这一档能干什么」，不是一段原理说明。
 */
@Composable
fun PermissionTierPage(
    current: PermissionLevel,
    onPick: (PermissionLevel) -> Unit,
    onBack: () -> Unit
) {
    Surface(
        // 铺满且不透明；最外层吞掉点击，避免穿透点到下面的聊天界面
        modifier = Modifier
            .fillMaxSize()
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "←",
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "权限",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    PermissionLevel.entries.forEach { level ->
                        PermissionTierRow(
                            title = level.displayName(),
                            hint = PERMISSION_HINTS[level].orEmpty(),
                            selected = level == current,
                            onClick = { onPick(level) }
                        )
                    }
                }
            }
        }
    }
}

/** 一档权限：左边档名 + 一句能力差异，右边选中时打勾。 */
@Composable
private fun PermissionTierRow(
    title: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Palette.AccentGreen else onSurface
            )
            Text(
                text = hint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.45f)
            )
        }
        if (selected) {
            Text(text = "✓", fontSize = 16.sp, color = Palette.AccentGreen)
        }
    }
}
