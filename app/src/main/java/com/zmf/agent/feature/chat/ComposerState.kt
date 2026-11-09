package com.zmf.agent.feature.chat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.UploadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 单条消息最多携带几张图片：对齐 DSH。待发图片行的展示上限也复用该值，不另写数字。 */
internal const val MAX_IMAGES = 20

/** 单张图片的源文件上限：20 MB（模型看到的是压缩后的 JPEG，这条只拦超大原图）。 */
private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

/** 待发送的附件：仅记录显示名与文件在工作区中的相对路径，模型通过 read_file 读取。 */
data class PendingFile(val name: String, val relPath: String)

/**
 * 输入区（Composer）的状态与动作：草稿、待发附件、待发图片、附件占用文案。
 *
 * 从 [ChatViewModel] 拆出，行为不变：字段名与原成员逐字一致，方法体原样保留；
 * 仅将四类只读依赖改为注入——当前工作区根目录、可能引用上传文件的会话集、
 * 错误回显入口、统计用协程域——避免该类反向持有整个 ViewModel。
 */
class ComposerState(
    private val appContext: Context,
    private val workspaceRoot: () -> File,
    private val sessionsReferencingUploads: () -> List<ChatSession>,
    private val scope: CoroutineScope,
    private val reportError: (String) -> Unit
) {

    /** 输入框内容（受控状态）。回滚时将被删除的该条提问填回此处，光标落在末尾。 */
    var inputText by mutableStateOf(TextFieldValue(""))
        internal set

    /** 待发送附件：选中的文件已经复制进当前工作区，点发送时才拼进提问内容。 */
    val attachments = mutableStateListOf<PendingFile>()

    /**
     * 待发送图片：单独一条队列，因为图片除了路径还要读盘编码成 data URL 直接发给模型。
     *
     * relPath 仍是工作区相对路径：界面靠它解码缩略图，会话落盘也只存这个路径。
     */
    val pendingImages = mutableStateListOf<PendingFile>()

    /** 当前工作区 uploads 的占用文本（含未引用部分）；null = 尚未统计。 */
    var attachmentUsageText by mutableStateOf<String?>(null)
        private set

    /** 将输入框内容（含光标位置）同步至状态；不触发任何发送或落盘。 */
    fun updateInput(text: TextFieldValue) {
        inputText = text
    }

    /**
     * 将选中的文件复制进当前工作区；在 IO 线程执行，结果返回主线程。
     *
     * 图片走 [UploadStore.importImage]（降采样 + 压 JPEG），其它文件原样复制：
     * 图片需「直接被模型看见」，仅压缩过尺寸的 JPEG 方可作为 data URL 放入请求。
     */
    fun attachFile(uri: Uri, scope: CoroutineScope) {
        // 部分 provider 无法提供类型（null）：取不到时按普通文件处理，避免将文件作为图片压缩损坏
        val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        val isImage = mime?.startsWith("image/") == true
        // 已满则停止导入：连文件也不复制至工作区，避免保留一份无人引用的附件
        if (!canEnqueuePending(isImage)) return
        // 单张图片源文件上限 20 MB（对齐 DSH）：无法获取长度（-1）时不拦截，交由导入时的压缩兜底
        if (isImage) {
            val bytes = runCatching {
                appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            if (bytes > MAX_IMAGE_BYTES) {
                reportError("单张图片最大 20 MB")
                return
            }
        }
        scope.launch(Dispatchers.IO) {
            // 当前工作区的取法与 send 完全一致：未分类或找不到时使用默认工作区
            val root = workspaceRoot()
            val result = if (isImage) {
                UploadStore.importImage(appContext, uri, root)
            } else {
                UploadStore.import(appContext, uri, root)
            }
            result.fold(
                onSuccess = { imported ->
                    val added = withContext(Dispatchers.Main) {
                        // 并发选择多张时在此再次检查：入口检查时可能均未满，返回时已超出
                        if (!canEnqueuePending(isImage)) return@withContext false
                        val pending = PendingFile(imported.name, imported.relPath)
                        if (isImage) pendingImages.add(pending) else attachments.add(pending)
                        true
                    }
                    // 未进入队列（超上限）则不记录「已加入发送队列」，避免日志与界面描述不一致
                    if (added) {
                        FileLog.log(
                            "upload",
                            (if (isImage) "图片已加入发送队列：" else "附件已加入发送队列：") +
                                imported.relPath + "（" + imported.size + " 字节）"
                        )
                    }
                },
                onFailure = { e ->
                    val message = e.message ?: e.javaClass.simpleName
                    withContext(Dispatchers.Main) {
                        reportError("上传失败，请重试；如仍失败，请换一个文件。")
                    }
                    FileLog.log("upload", (if (isImage) "图片上传失败：" else "附件上传失败：") + message)
                }
            )
        }
    }

    /** 队列仍有空位则返回 true；已满时将提示写入错误条，调用方仅按结果决定是否加入。 */
    private fun canEnqueuePending(isImage: Boolean): Boolean {
        // 仅图片有数量上限：普通附件按 DSH 约定不限个数，正文中给出的是路径
        if (isImage && pendingImages.size >= MAX_IMAGES) {
            reportError("图片最多 " + MAX_IMAGES + " 张")
            return false
        }
        return true
    }

    /** 从待发列表里移除一个普通附件；下标越界时忽略。 */
    fun detachFile(index: Int) {
        if (index in attachments.indices) attachments.removeAt(index)
    }

    /** 清空全部待发普通附件（图片有自己的列表，不受影响）。 */
    fun clearAttachments() {
        attachments.clear()
    }

    /** 从待发图片里移除一张；下标越界时忽略。 */
    fun detachImage(index: Int) {
        if (index in pendingImages.indices) pendingImages.removeAt(index)
    }

    /** 清空全部待发图片（普通附件不受影响）。 */
    fun clearPendingImages() {
        pendingImages.clear()
    }

    /** 进入设置页时刷新一次占用：在 IO 中扫描 uploads 目录，返回后仅更新一句文本。 */
    fun refreshAttachmentUsage() {
        scope.launch { attachmentUsageText = computeAttachmentUsageText() ?: "统计失败" }
    }

    /**
     * 清理当前工作区里没有被任何会话引用的附件（设置页「清理未引用附件」）。
     *
     * 删除必须在 IO 中执行：uploads 中可能存在数千个小文件；扫描完成后回主线程拼接一句中文供界面显示。
     * 会话中的路径均为工作区相对路径，因此仅与「本工作区 + 未分类」的会话比对，其他工作区不计入引用。
     */
    fun cleanOrphanImages(onDone: (String) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    UploadStore.cleanOrphans(appContext, sessionsReferencingUploads(), workspaceRoot())
                }.onFailure {
                    FileLog.log("upload", "清理未引用附件失败：" + it)
                }.getOrNull()
            }
            val text = if (result == null) {
                "清理失败，请查看日志"
            } else if (result.first == 0) {
                "没有可清理的附件"
            } else {
                "已删除 " + result.first + " 个文件，释放 " + mbText(result.second)
            }
            // 清理完成后立即重新计算占用，界面数字与磁盘一致
            attachmentUsageText = computeAttachmentUsageText() ?: "统计失败"
            onDone(text)
            FileLog.log("upload", "清理未引用附件：" + text)
        }
    }

    /** 「总占用（未引用 N）」文本；统计失败返回 null，由调用方给出失败提示。 */
    private suspend fun computeAttachmentUsageText(): String? {
        val pair = withContext(Dispatchers.IO) {
            runCatching {
                val root = workspaceRoot()
                val total = UploadStore.usageBytes(root)
                val orphans = UploadStore.orphanReport(appContext, sessionsReferencingUploads(), root).second
                total to orphans
            }.getOrNull()
        } ?: return null
        return if (pair.second > 0) {
            mbText(pair.first) + "（未引用 " + mbText(pair.second) + "）"
        } else {
            mbText(pair.first)
        }
    }

    /** 字节数 → 一位小数的 MB，与导出成功文案使用同一写法。 */
    private fun mbText(bytes: Long): String = "%.1f".format(bytes / 1024.0 / 1024.0) + " MB"
}
