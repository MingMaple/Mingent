package com.zmf.agent.uikit.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.Palette

/**
 * 伴随面板：贴着触发它的那一行弹出的小菜单，不遮整屏。
 *
 * 用 Popup 而非 Dialog / 内联 Box：
 * - Dialog 会把整屏压黑并覆盖下层，无法辨识菜单属于哪一行；
 * - 内联 Box 会被列表的 verticalScroll 裁剪（菜单常需超出可见区域）；
 * - Popup 拥有独立窗口，可直接按 anchor 的窗口坐标贴边，点击外部自动关闭。
 *
 * 观感与输入框上方的伴随面板一致：面板底色、8dp 阴影、只缩放不淡入
 * （带 alpha 的节点 Android 不绘制 elevation 阴影）。圆角取 18dp：菜单块小，24dp 会过于圆润。
 *
 * 抽屉的会话／工作区菜单与回收站条目菜单共用本组件，避免同一观感出现两套实现。
 */
@Composable
fun SaltMenuPopover(
    anchor: Rect,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val gap = with(LocalDensity.current) { 6.dp.toPx() }.toInt()
    val provider = remember(anchor, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                // 右边缘与触发点的右边缘对齐；下方空间不足时改到上方
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val x = (anchor.right.toInt() - popupContentSize.width).coerceIn(0, maxX)
                val below = anchor.bottom.toInt() + gap
                val y = if (below + popupContentSize.height <= windowSize.height) {
                    below
                } else {
                    (anchor.top.toInt() - popupContentSize.height - gap).coerceAtLeast(gap)
                }
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        var shown by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (shown) 1f else 0.92f,
            animationSpec = Motion.spring(),
            label = "saltMenuScale"
        )
        LaunchedEffect(Unit) { shown = true }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (isDark) Palette.DarkSurfaceHigh else Palette.LightSurfaceHigh,
            shadowElevation = 8.dp,
            modifier = Modifier
                // 菜单按内容宽度展开：限制过窄会让较长的条目留下大片空白；
                // 上限按「全部对话（显示已归档）」这一行放得下为准，短菜单仍随内容收窄
                .widthIn(min = 148.dp, max = 224.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    // 从右上角展开：贴合触发点所在侧，视觉上明确为该行的菜单
                    transformOrigin = TransformOrigin(1f, 0f)
                }
        ) {
            // 行自带上下内距，Column 不额外填充：首末行的波纹才能贴合 18dp 圆弧并被裁剪为圆角
            Column(content = content)
        }
    }
}

/**
 * 伴随面板里的一行：14sp、左右 14dp、上下 10dp（菜单块小，内距跟着收一档）。
 *
 * [trailing] 为行尾附加内容（如选中标记），不占文字宽度；未提供时与普通文字行完全一致。
 * `onClick` 保持在最后，既有调用点可直接用尾随 lambda 写法。
 */
@Composable
fun SaltMenuRow(
    text: String,
    color: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 面板内同样使用通栏波纹：18dp 圆角由 Surface 自身裁剪，行内不单独裁剪
            .saltCardRow { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            color = color ?: MaterialTheme.colorScheme.onSurface
        )
        trailing?.invoke()
    }
}
