package com.zmf.agent.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.ui.theme.Motion
import kotlin.math.roundToInt

/** 小窗里的一项。 */
data class MenuAction(val label: String, val onClick: () -> Unit)

/**
 * 长按后的小菜单：出现在按压位置附近，不是底部大面板。
 *
 * 遮罩只做很淡的一层并且只做淡入，卡片自己在原地缩放出现——
 * 之前把整块（含遮罩）一起从底部滑上来，屏幕上才会出现「上三分之一没被罩住」的分界线。
 */
@Composable
fun MessageContextMenu(
    anchor: Offset,
    actions: List<MenuAction>,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val cardWidthPx = with(density) { 176.dp.toPx() }
    val cardHeightPx = with(density) { (actions.size * 52 + 12).dp.toPx() }

    val x = (anchor.x - cardWidthPx / 2f).coerceIn(
        with(density) { 8.dp.toPx() },
        (screenWidthPx - cardWidthPx - with(density) { 8.dp.toPx() }).coerceAtLeast(with(density) { 8.dp.toPx() })
    )
    val edge = with(density) { 40.dp.toPx() }
    val maxY = (screenHeightPx - cardHeightPx - edge).coerceAtLeast(edge)
    val showAbove = anchor.y + cardHeightPx + with(density) { 24.dp.toPx() } > screenHeightPx
    val y = if (showAbove) {
        (anchor.y - cardHeightPx - with(density) { 16.dp.toPx() }).coerceIn(edge, maxY)
    } else {
        (anchor.y + with(density) { 16.dp.toPx() }).coerceIn(edge, maxY)
    }

    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }
    val scale by animateFloatAsState(if (appear) 1f else 0.9f, Motion.softSpring(), label = "menuScale")

    Box(modifier = Modifier.fillMaxSize()) {
        // 只做淡入的遮罩，不做位移
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.12f))
                .clickable(onClick = onDismiss)
        )
        Surface(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(176.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                actions.forEach { action ->
                    Text(
                        text = action.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = action.onClick)
                            .padding(horizontal = 18.dp, vertical = 13.dp),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
