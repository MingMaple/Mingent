package com.zmf.agent.uikit.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.zmf.agent.core.model.FileKind
import com.zmf.agent.core.model.FileKinds

/** 附件类型图标：按 FileKind 的九类各画一枚，规格与 `DshIcons` 一致，颜色交由调用方。 */

/** 统一 builder：16×16 画布。 */
private fun fileIcon(): ImageVector.Builder = ImageVector.Builder(
    defaultWidth = 16.dp,
    defaultHeight = 16.dp,
    viewportWidth = 16f,
    viewportHeight = 16f
)

private fun ImageVector.Builder.stroke(data: String, width: Float = 1.1f) = addPath(
    pathData = addPathNodes(data),
    fill = null,
    stroke = SolidColor(Color.Black),
    strokeLineWidth = width,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round
)

private fun ImageVector.Builder.fill(data: String) = addPath(
    pathData = addPathNodes(data),
    fill = SolidColor(Color.Black),
    stroke = null,
    strokeLineWidth = 1f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round
)

/** 纸张外框（右上折角）与折角线：所有类型共用的底。 */
private const val PAPER = "M3.4 3.1C3.4 2.4 3.9 1.9 4.6 1.9H9.9L12.6 4.6V12.9" +
    "C12.6 13.6 12.1 14.1 11.4 14.1H4.6C3.9 14.1 3.4 13.6 3.4 12.9Z"
private const val FOLD = "M9.9 1.9V4.6H12.6"

/** 纯文本：三行字（末行短）。 */
val TextFileIcon: ImageVector = fileIcon().apply {
    stroke(PAPER)
    stroke(FOLD)
    stroke("M5.6 7.4H10.4")
    stroke("M5.6 9.6H10.4")
    stroke("M5.6 11.8H8.6")
}.build()

/**
 * Markdown：两行字 + 右下箭头。
 *
 * 箭头取「向下」而非「M↓」字面：14dp 画不下可读的字母，向下的箭头已足以与纯文本区分，
 * 且与本工程「折返 / 直行」那组箭头同一笔画语言。
 */
val MarkdownFileIcon: ImageVector = fileIcon().apply {
    stroke(PAPER)
    stroke(FOLD)
    stroke("M5.6 6.4H10.4")
    stroke("M5.6 8.4H8.2")
    stroke("M9.9 6.6V12.2")
    stroke("M8.6 11.0L9.9 12.3L11.2 11.0")
}.build()

/** 代码：一对尖括号 + 中间的斜杠。 */
val CodeFileIcon: ImageVector = fileIcon().apply {
    stroke(PAPER)
    stroke(FOLD)
    stroke("M6.6 7.0L4.9 9.2L6.6 11.4")
    stroke("M9.4 7.0L11.1 9.2L9.4 11.4")
    stroke("M8.3 6.6L7.7 11.8")
}.build()

/** PDF：纸面下部一条实心色带（读作「版式文件」的封面条），上方两行字。 */
val PdfFileIcon: ImageVector = fileIcon().apply {
    stroke(PAPER)
    stroke(FOLD)
    stroke("M5.6 6.6H10.4")
    fill("M4.6 10.0H11.4V12.2C11.4 12.5 11.2 12.7 10.9 12.7H5.1C4.8 12.7 4.6 12.5 4.6 12.2Z")
}.build()

/** Word 文档：纸面三行字 + 左下角一枚加粗的「段首块」，与纯文本的三行区分开。 */
val DocxFileIcon: ImageVector = fileIcon().apply {
    stroke(PAPER)
    stroke(FOLD)
    stroke("M6.8 6.6H10.4")
    stroke("M6.8 9.0H10.4")
    stroke("M6.8 11.4H9.2")
    fill("M4.6 6.2H5.9V8.0H4.6ZM4.6 8.6H5.9V10.4H4.6ZM4.6 11.0H5.9V12.6H4.6Z")
}.build()

/** 其它 Office 文件（表格 / 演示）：纸面内侧一张 2×2 网格。 */
val OfficeFileIcon: ImageVector = fileIcon().apply {
    stroke(PAPER)
    stroke(FOLD)
    stroke("M5.4 6.6H10.6V11.8H5.4Z")
    stroke("M8.0 6.6V11.8")
    stroke("M5.4 9.2H10.6")
}.build()

/** 压缩包：方箱 + 中间的拉链线与拉链头。 */
val ArchiveFileIcon: ImageVector = fileIcon().apply {
    stroke("M2.9 4.2C2.9 3.5 3.4 3.0 4.1 3.0H11.9C12.6 3.0 13.1 3.5 13.1 4.2V12.8" +
        "C13.1 13.5 12.6 14.0 11.9 14.0H4.1C3.4 14.0 2.9 13.5 2.9 12.8Z")
    stroke("M8 3.0V7.4", width = 1.3f)
    stroke("M8 9.0V11.0")
    stroke("M6.9 7.4H9.1V9.0H6.9Z")
}.build()

/** 图片：圆角外框 + 太阳 + 山脊。 */
val ImageFileIcon: ImageVector = fileIcon().apply {
    stroke("M2.9 4.2C2.9 3.5 3.4 3.0 4.1 3.0H11.9C12.6 3.0 13.1 3.5 13.1 4.2V11.8" +
        "C13.1 12.5 12.6 13.0 11.9 13.0H4.1C3.4 13.0 2.9 12.5 2.9 11.8Z")
    stroke("M5.6 6.0H6.9V7.3H5.6Z")
    stroke("M3.2 11.4L6.6 8.2L9.0 10.4L10.6 9.0L12.9 11.2")
}.build()

/** 无后缀、不认识类型：只有纸张与折角。 */
val PlainFileIcon: ImageVector = fileIcon().apply {
    stroke(PAPER)
    stroke(FOLD)
}.build()

/** 类别到图标的唯一映射；新增格式只改 `FileKinds`，此处不必跟着改。 */
fun fileIconOf(kind: FileKind): ImageVector = when (kind) {
    FileKind.MARKDOWN -> MarkdownFileIcon
    FileKind.TEXT -> TextFileIcon
    FileKind.CODE -> CodeFileIcon
    FileKind.PDF -> PdfFileIcon
    FileKind.DOCX -> DocxFileIcon
    FileKind.OFFICE_OTHER -> OfficeFileIcon
    FileKind.ARCHIVE -> ArchiveFileIcon
    FileKind.IMAGE -> ImageFileIcon
    FileKind.OTHER -> PlainFileIcon
}

/** 按路径直接取图标：附件 chip 只需文件名，不必先显式算出类别。 */
fun fileIconOf(path: String): ImageVector = fileIconOf(FileKinds.of(path))
