package com.zmf.agent.uikit.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 助手操作页面的常驻提示条：左侧一行说明，右侧一个终止按钮。
 *
 * 视觉与 [NoticeBanner] 同档：14dp 圆角、`surface` 底、1dp 描边（`outline` 60%）、8dp 阴影、
 * 内距 13/8/13/8、13sp 单行省略。位置由调用方给出，本组件不自行留出页头空间。
 *
 * 终止按钮只结束本次页面操作，不终止助手：按下后先播完退场动画，再回调 [onAbort]，
 * 由调用方解除租赁；同批排队命令由控制端口按世代号丢弃。
 */
@Composable
fun AgentOpBanner(text: String, onAbort: () -> Unit, modifier: Modifier = Modifier) {
    val visibleState = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { visibleState.targetState = true }
    val scope = rememberCoroutineScope()
    var aborting by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = slideInVertically(tween(180)) { -it },
        exit = slideOutVertically(tween(140)) { -it }
    ) {
        Surface(
            modifier = Modifier
                // 与提示条同一宽度上限：360dp 屏减左右 16dp
                .widthIn(max = 328.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        ) {
            Row(
                modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = text,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 13.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                AbortButton(enabled = !aborting) {
                    aborting = true
                    scope.launch {
                        visibleState.targetState = false
                        delay(140)
                        onAbort()
                    }
                }
            }
        }
    }
}

/**
 * 终止按钮：28dp 圆形热区，内部自绘 12dp 的「×」。
 *
 * 两笔线宽 1.6dp、圆头端点，颜色取次要文字色；不得复用会话页输入区的方形停止符，
 * 两者语义不同（后者终止助手，前者只终止页面操作）。
 */
@Composable
private fun AbortButton(enabled: Boolean, onClick: () -> Unit) {
    val ink = UiColors.subText
    Box(
        modifier = Modifier
            .size(28.dp)
            // 圆形热区自带裁剪与水波纹；键盘聚焦时绘制 2dp 强调色环
            .saltRowClickable(CircleShape, enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = "终止本次页面操作"
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            val stroke = 1.6.dp.toPx()
            drawLine(ink, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
            drawLine(ink, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
        }
    }
}
