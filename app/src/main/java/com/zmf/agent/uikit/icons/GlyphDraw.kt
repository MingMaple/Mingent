package com.zmf.agent.uikit.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp

/**
 * 18×18 单位坐标系的小图标：调用方按 18 的坐标写路径，这里按实际 dp 等比缩放。
 * 图标尺寸换了几档线宽也不会跟着变粗，深色浅色共用同一份形状。
 *
 * 这四个原语原本寄居在 `Composer.kt`：主页气泡、悬浮小窗、输入条、轨迹页、设置首页都在用同一套字形，
 * 把它们留在聊天实现文件里，会让「通用图标」和「输入框」互相绑死——改输入框就动到全 App 的图标。
 */
@Composable
internal fun UnitGlyph(iconSize: Dp, draw: DrawScope.() -> Unit) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val unit = this.size.minDimension / 18f
        scale(scaleX = unit, scaleY = unit, pivot = Offset.Zero) {
            draw()
        }
    }
}

/** 单位坐标系里的描边规格：端点与拐角都收圆，1.4 单位宽的线缩放后不显尖。 */
internal fun unitStroke(width: Float): Stroke =
    Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** 拼路径的小工具，专门给 [UnitGlyph] 里的 18×18 坐标用。 */
internal fun unitPath(build: Path.() -> Unit): Path = Path().apply(build)

/** 上下文 / 模型共用的圆柱体：数据装在该装的地方，一眼看得懂。 */
@Composable
internal fun CylinderGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawOval(
            color = tint,
            topLeft = Offset(3.6f, 2.6f),
            size = Size(10.8f, 3.6f),
            style = unitStroke(1.5f)
        )
        drawPath(
            path = unitPath {
                moveTo(3.6f, 4.4f)
                lineTo(3.6f, 12.4f)
                moveTo(14.4f, 4.4f)
                lineTo(14.4f, 12.4f)
            },
            color = tint,
            style = unitStroke(1.5f)
        )
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(3.6f, 10.6f),
            size = Size(10.8f, 3.6f),
            style = unitStroke(1.5f)
        )
        drawPath(
            path = unitPath {
                moveTo(4.8f, 8.6f)
                lineTo(13.2f, 8.6f)
            },
            color = tint,
            style = unitStroke(1.5f)
        )
    }
}
