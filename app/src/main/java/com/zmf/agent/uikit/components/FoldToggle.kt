package com.zmf.agent.uikit.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 气泡右下角的展开 / 收起按钮：圆角条与细箭头，底色取气泡自身的一层淡色。
 *
 * 不采用圆形按钮，也不添加外圈：环状阴影与气泡的扁平风格不协调；改为圆角矩形后，视觉上相当于气泡自身的角标。
 *
 * 主页气泡与悬浮小窗共用同一组件：两处的折叠交互须保持一致，因此置于 uikit 而非某个 feature。
 */
@Composable
internal fun FoldToggle(expanded: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(onSurface.copy(alpha = 0.06f))
            .clickable(onClickLabel = if (expanded) "收起" else "展开", onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(14.dp)) {
            val path = Path().apply {
                if (expanded) {
                    moveTo(size.width * 0.18f, size.height * 0.64f)
                    lineTo(size.width * 0.5f, size.height * 0.34f)
                    lineTo(size.width * 0.82f, size.height * 0.64f)
                } else {
                    moveTo(size.width * 0.18f, size.height * 0.36f)
                    lineTo(size.width * 0.5f, size.height * 0.66f)
                    lineTo(size.width * 0.82f, size.height * 0.36f)
                }
            }
            drawPath(
                path = path,
                color = onSurface.copy(alpha = 0.55f),
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}
