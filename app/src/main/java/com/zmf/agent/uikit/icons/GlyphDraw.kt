package com.zmf.agent.uikit.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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
 * 图标尺寸变化时线宽不会随之变粗，深色与浅色共用同一份形状。
 *
 * 这四个原语原位于 `Composer.kt`：主页气泡、悬浮小窗、输入条、轨迹页与设置首页均使用同一套字形；
 * 若保留在聊天实现文件中，会使通用图标与输入框相互耦合，修改输入框将影响全局图标。
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

/** 单位坐标系中的描边规格：端点与拐角均为圆角，1.4 单位线宽缩放后不会显得尖锐。 */
internal fun unitStroke(width: Float): Stroke =
    Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** 路径构建工具函数，专用于 [UnitGlyph] 中的 18×18 坐标。 */
internal fun unitPath(build: Path.() -> Unit): Path = Path().apply(build)

/** 代码符号「&lt;/&gt;」：用于源码仓库一类入口。 */
@Composable
internal fun CodeGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawPath(
            path = unitPath {
                moveTo(7.0f, 4.8f)
                lineTo(3.4f, 9.0f)
                lineTo(7.0f, 13.2f)
            },
            color = tint,
            style = unitStroke(1.4f)
        )
        drawPath(
            path = unitPath {
                moveTo(11.0f, 4.8f)
                lineTo(14.6f, 9.0f)
                lineTo(11.0f, 13.2f)
            },
            color = tint,
            style = unitStroke(1.4f)
        )
        drawPath(
            path = unitPath {
                moveTo(10.2f, 4.0f)
                lineTo(7.8f, 14.0f)
            },
            color = tint,
            style = unitStroke(1.4f)
        )
    }
}

/** 虫形：躯体、两根触角与四条腿，用于问题反馈。 */
@Composable
internal fun BugGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(5.4f, 6.2f),
            size = Size(7.2f, 8.4f),
            cornerRadius = CornerRadius(3.6f, 3.6f),
            style = unitStroke(1.4f)
        )
        drawPath(
            path = unitPath {
                moveTo(6.9f, 4.3f)
                lineTo(7.7f, 6.2f)
                moveTo(11.1f, 4.3f)
                lineTo(10.3f, 6.2f)
            },
            color = tint,
            style = unitStroke(1.4f)
        )
        drawPath(
            path = unitPath {
                moveTo(3.4f, 8.4f)
                lineTo(5.4f, 9.4f)
                moveTo(14.6f, 8.4f)
                lineTo(12.6f, 9.4f)
                moveTo(3.4f, 12.6f)
                lineTo(5.4f, 11.6f)
                moveTo(14.6f, 12.6f)
                lineTo(12.6f, 11.6f)
            },
            color = tint,
            style = unitStroke(1.4f)
        )
    }
}

/** 标签：一端为尖角、一端平口，尖角附近一个穿孔，用于版本一类的标记。 */
@Composable
internal fun TagGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawPath(
            path = unitPath {
                moveTo(3.6f, 5.0f)
                lineTo(10.2f, 5.0f)
                lineTo(14.4f, 9.0f)
                lineTo(10.2f, 13.0f)
                lineTo(3.6f, 13.0f)
                close()
            },
            color = tint,
            style = unitStroke(1.4f)
        )
        drawCircle(color = tint, radius = 0.9f, center = Offset(6.4f, 9.0f))
    }
}

/** 文档：右上角折起的一页纸，加两道文字线，用于声明一类的文本入口。 */
@Composable
internal fun DocGlyph(tint: Color, iconSize: Dp) {
    UnitGlyph(iconSize = iconSize) {
        drawPath(
            path = unitPath {
                moveTo(4.8f, 2.8f)
                lineTo(10.4f, 2.8f)
                lineTo(13.4f, 5.8f)
                lineTo(13.4f, 15.2f)
                lineTo(4.8f, 15.2f)
                close()
            },
            color = tint,
            style = unitStroke(1.4f)
        )
        drawPath(
            path = unitPath {
                moveTo(10.4f, 2.8f)
                lineTo(10.4f, 5.8f)
                lineTo(13.4f, 5.8f)
            },
            color = tint,
            style = unitStroke(1.4f)
        )
        drawPath(
            path = unitPath {
                moveTo(6.8f, 9.2f)
                lineTo(11.2f, 9.2f)
                moveTo(6.8f, 11.8f)
                lineTo(11.2f, 11.8f)
            },
            color = tint,
            style = unitStroke(1.4f)
        )
    }
}

/** 上下文与模型共用的圆柱体：表示数据存储位置，含义直观。 */
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
