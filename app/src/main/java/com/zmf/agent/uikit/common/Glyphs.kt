package com.zmf.agent.uikit.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zmf.agent.uikit.icons.CylinderGlyph
import com.zmf.agent.uikit.icons.PlanIcon
import com.zmf.agent.uikit.icons.UnitGlyph
import com.zmf.agent.uikit.theme.Motion

/*
 * 全局线稿图标的规范统一在此：单位网格（18×18 / 24×24）、1.8 圆头描边、颜色统一交由调用方 tint。
 *
 * 这是图标体系统一的两项公共组件：
 * - GlyphIcon：「＋」菜单与权限档使用的面板线稿，已提取为公共组件；
 * - Chevron：全局仅使用两种箭头形态与两档尺寸，不再在各处使用「▾ ▸ › ⌄」等文字箭头。
 */

/** 面板线稿的种类；其他页面绘制同一图标时仅使用该枚举，不重复描摹路径。 */
enum class PanelGlyph { FILE, FOLDER, GOAL, PLAN, COMPACT, SHIELD, EXPORT, CONTEXT, SKILL, SLASH, WARNING }

/**
 * 按 [glyph] 画一枚线稿图标。
 *
 * 路径基于 18×18 单位空间，[size] 变化时线宽等比缩放，
 * 因此 16dp 下保持原有视觉比例；计划与模型两枚直接复用工程中已有的矢量与圆柱体。
 */
@Composable
fun GlyphIcon(glyph: PanelGlyph, size: Dp, tint: Color) {
    when (glyph) {
        PanelGlyph.PLAN -> Icon(
            imageVector = PlanIcon,
            contentDescription = null,
            modifier = Modifier.size(size),
            tint = tint
        )

        PanelGlyph.CONTEXT -> CylinderGlyph(tint = tint, iconSize = size)

        PanelGlyph.SLASH -> UnitGlyph(iconSize = size) {
            drawPath(
                path = iconPath {
                    moveTo(13.4f, 3.4f)
                    lineTo(4.6f, 14.6f)
                },
                color = tint,
                style = iconStroke()
            )
        }

        PanelGlyph.FILE -> UnitGlyph(iconSize = size) {
            drawPath(
                path = iconPath {
                    moveTo(4.6f, 2.8f)
                    lineTo(10.4f, 2.8f)
                    lineTo(13.8f, 6.2f)
                    lineTo(13.8f, 15.2f)
                    lineTo(4.6f, 15.2f)
                    close()
                },
                color = tint,
                style = iconStroke()
            )
            drawPath(
                path = iconPath {
                    moveTo(10.4f, 2.8f)
                    lineTo(10.4f, 6.2f)
                    lineTo(13.8f, 6.2f)
                },
                color = tint,
                style = iconStroke()
            )
        }

        PanelGlyph.FOLDER -> UnitGlyph(iconSize = size) {
            drawPath(
                path = iconPath {
                    moveTo(2.8f, 5.4f)
                    lineTo(7.4f, 5.4f)
                    lineTo(9.2f, 7.4f)
                    lineTo(15.2f, 7.4f)
                    lineTo(15.2f, 14.4f)
                    lineTo(2.8f, 14.4f)
                    close()
                },
                color = tint,
                style = iconStroke()
            )
        }

        PanelGlyph.GOAL -> UnitGlyph(iconSize = size) {
            drawCircle(color = tint, radius = 5.6f, center = Offset(9f, 9f), style = iconStroke())
            drawCircle(color = tint, radius = 1.9f, center = Offset(9f, 9f), style = iconStroke())
        }

        PanelGlyph.SKILL -> UnitGlyph(iconSize = size) {
            drawPath(
                path = iconPath {
                    moveTo(9f, 2.6f)
                    lineTo(10.9f, 7.1f)
                    lineTo(15.4f, 9f)
                    lineTo(10.9f, 10.9f)
                    lineTo(9f, 15.4f)
                    lineTo(7.1f, 10.9f)
                    lineTo(2.6f, 9f)
                    lineTo(7.1f, 7.1f)
                    close()
                },
                color = tint,
                style = iconStroke()
            )
        }

        PanelGlyph.COMPACT -> UnitGlyph(iconSize = size) {
            drawCircle(color = tint, radius = 5.6f, center = Offset(9f, 9f), style = iconStroke())
        }

        PanelGlyph.SHIELD -> UnitGlyph(iconSize = size) {
            drawPath(
                path = iconPath {
                    moveTo(9f, 2.6f)
                    lineTo(14.8f, 5.0f)
                    lineTo(14.8f, 9.2f)
                    cubicTo(14.8f, 12.6f, 12.2f, 15.0f, 9f, 16.2f)
                    cubicTo(5.8f, 15.0f, 3.2f, 12.6f, 3.2f, 9.2f)
                    lineTo(3.2f, 5.0f)
                    close()
                },
                color = tint,
                style = iconStroke()
            )
        }

        PanelGlyph.EXPORT -> UnitGlyph(iconSize = size) {
            drawPath(
                path = iconPath {
                    moveTo(9f, 2.8f)
                    lineTo(9f, 11.4f)
                    moveTo(5.4f, 8.0f)
                    lineTo(9f, 11.6f)
                    lineTo(12.6f, 8.0f)
                    moveTo(3.4f, 13.4f)
                    lineTo(3.4f, 15.6f)
                    lineTo(14.6f, 15.6f)
                    lineTo(14.6f, 13.4f)
                },
                color = tint,
                style = iconStroke()
            )
        }

        PanelGlyph.WARNING -> UnitGlyph(iconSize = size) {
            drawPath(
                path = iconPath {
                    moveTo(9f, 2.8f)
                    lineTo(15.6f, 15.2f)
                    lineTo(2.4f, 15.2f)
                    close()
                },
                color = tint,
                style = iconStroke()
            )
            drawPath(
                path = iconPath {
                    moveTo(9f, 7.4f)
                    lineTo(9f, 11.2f)
                },
                color = tint,
                style = iconStroke()
            )
            drawCircle(color = tint, radius = 0.9f, center = Offset(9f, 13.2f))
        }
    }
}

/**
 * 折叠箭头的两种形态，全局仅允许这两种：
 * - [RIGHT_DOWN]：折叠时朝右（›），展开时朝下（⌄），表示「展开后下方增加一层」；
 * - [UP_DOWN]：折叠时朝下（⌄），展开时朝上（⌃），表示「该条展开后可再次收起」。
 *
 * 两种形态共用同一条 45° 折线，仅旋转角度不同。
 */
enum class ChevronKind { RIGHT_DOWN, UP_DOWN }

/** 箭头仅有两档尺寸：行内小箭头 12dp，独立按钮 / 标题行 16dp，不得出现第三档。 */
enum class ChevronSize(val dp: Dp) { SMALL(12.dp), NORMAL(16.dp) }

/**
 * 折叠箭头：一条朝右的 45° 折线绕画布中心旋转，展开时转为朝下（或朝上）。
 *
 * 线宽 1.8 按 24×24 网格计算，随 [size] 等比缩放；旋转使用 [Motion.spring]，与抽屉、卡片的开合保持一致手感，
 * 切换时不会出现形状突变。
 */
@Composable
fun Chevron(
    expanded: Boolean,
    size: ChevronSize,
    tint: Color,
    kind: ChevronKind = ChevronKind.RIGHT_DOWN,
    modifier: Modifier = Modifier
) {
    val angle by animateFloatAsState(
        targetValue = when (kind) {
            ChevronKind.RIGHT_DOWN -> if (expanded) 90f else 0f
            ChevronKind.UP_DOWN -> if (expanded) 180f else 0f
        },
        animationSpec = Motion.spring(),
        label = "chevronAngle"
    )
    Canvas(modifier = modifier.size(size.dp)) {
        val unit = this.size.minDimension / 24f
        // 先绕中心转（px 坐标），再缩放到 24 网格里画路径：两档尺寸共用一份坐标
        rotate(degrees = angle, pivot = center) {
            scale(scaleX = unit, scaleY = unit, pivot = Offset.Zero) {
                drawPath(
                    path = iconPath {
                        moveTo(9f, 6f)
                        lineTo(15f, 12f)
                        lineTo(9f, 18f)
                    },
                    color = tint,
                    style = iconStroke(CHEVRON_STROKE)
                )
            }
        }
    }
}

/** 24 网格里的箭头线宽。 */
private const val CHEVRON_STROKE = 1.8f

/** 单位坐标系中的描边规格：端点与拐角均为圆角，与 DSH 图标视觉一致。 */
private fun iconStroke(width: Float = 1.8f): Stroke =
    Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** 用于构建单位坐标路径的工具函数。 */
private fun iconPath(build: Path.() -> Unit): Path = Path().apply(build)
