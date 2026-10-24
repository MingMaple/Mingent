package com.zmf.agent.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * DSH 官方图标的几枚：加号（IconPlus）、列表 + 笔（IconListPen），以及侧边栏工作区分组用的文件夹。
 *
 * 口径跟 DSH 源一致：viewBox 16×16、线宽 1、stroke / fill 都吃 currentColor。
 * 取色统一给 SolidColor(Color.Black)，真正的颜色交给调用方 Icon(tint = ...) 决定，
 * 深浅色主题直接复用同一份形状，不用各画一套。
 */

/** 统一的 builder：16×16 的画布，尺寸也用 16dp，跟图标源一一对应。 */
private fun dshIcon(): ImageVector.Builder = ImageVector.Builder(
    defaultWidth = 16.dp,
    defaultHeight = 16.dp,
    viewportWidth = 16f,
    viewportHeight = 16f
)

/**
 * 只描边、不填色的路径；端点 / 拐角都收圆。
 * 线宽做成参数：文件夹这类「分组标题」的图标要比细线图标重一点才立得住，默认仍是 1。
 */
private fun ImageVector.Builder.strokePath(data: String, width: Float = 1f) = addPath(
    pathData = addPathNodes(data),
    fill = null,
    stroke = SolidColor(Color.Black),
    strokeLineWidth = width,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round
)

/** 只填色、不描边的路径。 */
private fun ImageVector.Builder.fillPath(data: String) = addPath(
    pathData = addPathNodes(data),
    fill = SolidColor(Color.Black),
    stroke = null,
    strokeLineWidth = 1f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round
)

/** DSH 的加号：两条 stroke，一眼看就是「添加」。 */
val PlusIcon: ImageVector = dshIcon().apply {
    strokePath("M8 2V14")
    strokePath("M2 8H14")
}.build()

/**
 * DSH 的 IconListPen（列表 + 笔）：前两条 stroke 是列表里的两行字，
 * 后三条 fill 分别是纸片外框、笔身、笔尖，拼起来就是「边看清单边写东西」。
 */
val PlanIcon: ImageVector = dshIcon().apply {
    strokePath("M4.9375 5.90295H11.0625")
    strokePath("M4.9375 9.02991H8.27841")
    fillPath(
        "M12.5 1.32617C13.3039 1.32617 14 1.95171 14 2.77637V7.61328L13 8.68164V2.77637" +
            "C13 2.55186 12.8007 2.32617 12.5 2.32617H3.5C3.1993 2.32617 3 2.55186 3 2.77637" +
            "V13.2246C3.00044 13.4489 3.19963 13.6738 3.5 13.6738H8.32812L7.39258 14.6738H3.5" +
            "C2.69637 14.6738 2.00042 14.0489 2 13.2246V2.77637C2 1.95171 2.69613 1.32617 3.5 1.32617H12.5Z"
    )
    fillPath(
        "M8.97212 14.3693C9.17511 14.5723 9.37811 14.7753 9.5811 14.9783C9.67012 14.8953 " +
            "9.75914 14.8123 9.84815 14.7293C11.4505 13.2352 13.0528 11.7411 14.6551 10.247" +
            "C14.7441 10.164 14.8331 10.081 14.9221 9.99803C14.5989 9.6748 14.2756 9.35157 " +
            "13.9524 9.02834C13.8694 9.11736 13.7864 9.20637 13.7034 9.29539C12.2093 10.8977 " +
            "10.7152 12.5 9.22113 14.1023C9.13813 14.1913 9.05513 14.2803 8.97212 14.3693Z"
    )
    fillPath(
        "M11.6323 13.7841C11.6323 14.0395 11.6323 14.295 11.6323 14.5504C11.6812 14.5523 " +
            "11.7301 14.5543 11.779 14.5562C12.659 14.5913 13.539 14.6263 14.419 14.6614" +
            "C14.4679 14.6633 14.5168 14.6653 14.5657 14.6672C14.5657 14.3339 14.5657 14.0006 " +
            "14.5657 13.6672C14.5168 13.6692 14.4679 13.6711 14.419 13.6731C13.539 13.7081 " +
            "12.659 13.7432 11.779 13.7783C11.7301 13.7802 11.6812 13.7821 11.6323 13.7841Z"
    )
}.build()

/**
 * 工作区分组用的文件夹：圆角外框 + 顶部小盖（左半段是平顶，折一道斜边落到右半段的顶），纯描边。
 *
 * 线宽 1.4 而不是 1：这个图标跟 14sp 半粗的工作区名并排，细一号会显得比文字更弱，
 * 分组层级就压不住了。viewBox 仍是 16×16、走同一套 builder，颜色照旧交给调用方 tint。
 */
val FolderIcon: ImageVector = dshIcon().apply {
    strokePath(
        "M1.4 10.8V5.0C1.4 4.3 1.9 3.8 2.6 3.8H5.4L6.9 5.2H13.4C14.1 5.2 14.6 5.7 14.6 6.4" +
            "V10.8C14.6 11.5 14.1 12.0 13.4 12.0H2.6C1.9 12.0 1.4 11.5 1.4 10.8Z",
        width = 1.4f
    )
}.build()

/**
 * 24×24 网格的一套：抽屉里「主页 / 终端 / 设置」这三行，还有后面其它页面的界面骨架图标。
 *
 * 口径跟上面 16 网格那几枚一致（只描边、端点与拐角收圆、SolidColor 黑、实际颜色交给 Icon(tint)），
 * 只是网格提到 24、线宽统一 1.8 —— 这三枚要跟 16sp 的正文并排，线太细会立不住。
 */
private fun saltIcon24(): ImageVector.Builder = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
)

/** 24 网格上的统一线宽。 */
private const val ICON_STROKE = 1.8f

/** 8 齿齿轮的外轮廓：24 网格、中心 (12,12)，外径 9.3、齿根 7.0、内孔 3.3。 */
private const val GEAR_OUTLINE =
    "M10.71 2.79L13.29 2.79L13.69 5.21L15.61 6.00L17.60 4.57L19.43 6.40L18.00 8.39" +
        "L18.79 10.31L21.21 10.71L21.21 13.29L18.79 13.69L18.00 15.61L19.43 17.60" +
        "L17.60 19.43L15.61 18.00L13.69 18.79L13.29 21.21L10.71 21.21L10.31 18.79" +
        "L8.39 18.00L6.40 19.43L4.57 17.60L6.00 15.61L5.21 13.69L2.79 13.29L2.79 10.71" +
        "L5.21 10.31L6.00 8.39L4.57 6.40L6.40 4.57L8.39 6.00L10.31 5.21Z"

/** 齿轮的内孔：半径 3.3 的圆，用四段三次贝塞尔拼（矢量路径里比弧线好对齐）。 */
private const val GEAR_HUB =
    "M12 8.7C13.82 8.7 15.3 10.18 15.3 12C15.3 13.82 13.82 15.3 12 15.3" +
        "C10.18 15.3 8.7 13.82 8.7 12C8.7 10.18 10.18 8.7 12 8.7Z"

/**
 * 抽屉三行（主页 / 终端 / 设置）用的三枚图标：24 网格线稿，同一套线宽与圆角。
 *
 * 放在 object 里是为了调用方一眼看清「这三枚是一套」，也免得再往文件里撒两个同级 val。
 */
object DshIcons {

    /** 终端：圆角方框 + 里面一个「>」，尾巴带一小段下划线，读起来就是一行命令行。 */
    val Terminal: ImageVector = saltIcon24().apply {
        strokePath(
            "M6.4 3.6C4.8 3.6 3.6 4.8 3.6 6.4V17.6C3.6 19.2 4.8 20.4 6.4 20.4H17.6" +
                "C19.2 20.4 20.4 19.2 20.4 17.6V6.4C20.4 4.8 19.2 3.6 17.6 3.6H6.4Z",
            width = ICON_STROKE
        )
        strokePath("M7.4 9.1L10.4 12L7.4 14.9", width = ICON_STROKE)
        strokePath("M12.4 15H16.2", width = ICON_STROKE)
    }.build()

    /** 主页：一道屋顶折线 + 两侧墙与地面，墙顶压在屋顶线上，中间不留缝。 */
    val Home: ImageVector = saltIcon24().apply {
        strokePath("M3.6 10.9L12 3.6L20.4 10.9", width = ICON_STROKE)
        strokePath(
            "M6.3 8.9V19.3C6.3 19.9 6.8 20.4 7.4 20.4H16.6C17.2 20.4 17.7 19.9 17.7 19.3V8.9",
            width = ICON_STROKE
        )
    }.build()

    /** 设置：8 齿齿轮外轮廓 + 中间一个内孔，跟 Terminal / Home 同一套线宽。 */
    val Gear: ImageVector = saltIcon24().apply {
        strokePath(GEAR_OUTLINE, width = ICON_STROKE)
        strokePath(GEAR_HUB, width = ICON_STROKE)
    }.build()
}

