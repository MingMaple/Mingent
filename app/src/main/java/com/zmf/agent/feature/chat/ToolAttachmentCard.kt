package com.zmf.agent.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/*
 * 工具输出中的图片附件卡：缩略图、文件名及「image/webp · 58KB · 1307 × 1616」。
 *
 * 点击交由页面的查看器处理（复用主页的查看器，不另建一套）。
 * 仅负责绘制，不做解析：解析位于 ToolSummary.toolAttachments 中，为纯函数，可单元测试。
 */

/** 缩略图边长：足以辨认图片，且不使结果区过高。 */
private val ThumbSide = 40.dp

/**
 * 工具输出中的图片附件列表：缩略图、文件名及一行「mime · 大小 · 尺寸」，纵向逐条排列。
 *
 * 仅负责绘制，数据由 [toolAttachments] 解析后传入；[attachments] 为空时整块不渲染。
 *
 * @param root 工作区根目录，用于将相对路径解析为可读文件；null 时仅按相对路径本身查找
 * @param onOpen 点某张图时回调（参数为整组路径与起点下标）；null 表示不可点
 */
@Composable
internal fun ToolAttachmentRow(
    attachments: List<ToolAttachment>,
    root: File?,
    modifier: Modifier = Modifier,
    onOpen: ((List<String>, Int) -> Unit)? = null
) {
    if (attachments.isEmpty()) return
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(onSurface.copy(alpha = 0.05f))
                    .then(
                        if (onOpen != null && root != null) {
                            Modifier.clickable { onOpen(attachments.map { it.relPath }, index) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AttachmentThumb(relPath = item.relPath, root = root)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = onSurface.copy(alpha = 0.85f)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.meta,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = onSurface.copy(alpha = 0.45f)
                    )
                }
            }
        }
    }
}

/** 缩略图：解码失败时保留一块底色，不崩溃，也不留空洞。 */
@Composable
private fun AttachmentThumb(relPath: String, root: File?) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val bitmap = rememberThumbnail(
        if (root != null) File(root, relPath) else File(relPath),
        THUMB_EDGE_PX
    )
    Box(
        modifier = Modifier
            .size(ThumbSide)
            .clip(RoundedCornerShape(8.dp))
            .background(onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(ThumbSide),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(text = "图", fontSize = 11.sp, color = onSurface.copy(alpha = 0.4f))
        }
    }
}

/** 工具附件缩略图最长边。 */
private const val THUMB_EDGE_PX = 256
