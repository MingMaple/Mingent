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

/** 单条消息最多带几张图片：对齐 DSH。待发图片行的展示上限也复用它，别另写数字。 */
internal const val MAX_IMAGES = 20

/** 单张图片的源文件上限：20 MB（模型看到的是压缩后的 JPEG，这条只拦超大原图）。 */
private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

/** 待发送的附件：只记显示名和文件在工作区里的相对路径，模型靠 read_file 读。 */
data class PendingFile(val name: String, val relPath: String)

/**
 * 输入区（Composer）的状态与动作：草稿、待发附件、待发图片、附件占用文案。
 *
 * 从 [ChatViewModel] 搬出，行为不变：字段名与原成员逐字一致，方法体原样照搬；
 * 只把四类只读依赖改成注入——当前工作区根目录、可能引用上传文件的会话集、
 * 错误回显入口、统计用的协程域——免得这个类反过来攥住整个 ViewModel。
 */
class ComposerState(
    private val appContext: Context,
    private val workspaceRoot: () -> File,
    private val sessionsReferencingUploads: () -> List<ChatSession>,
    private val scope: CoroutineScope,
    private val reportError: (String) -> Unit
) {

    /** 输入框内容（受控状态）。回滚时把被删掉的那条提问填回这里，光标落在末尾。 */
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

    /** 当前工作区 uploads 的占用文本（含未引用部分）；null = 还没统计。 */
    var attachmentUsageText by mutableStateOf<String?>(null)
        private set

    /** 把输入框的内容（含光标位置）同步进状态；不触发任何发送或落盘。 */
    fun updateInput(text: TextFieldValue) {
        inputText = text
    }

    /**
     * 选中的文件复制进当前工作区；IO 线程做，结果回主线程。
     *
     * 图片走 [UploadStore.importImage]（降采样 + 压 JPEG），其它文件原样复制：
     * 图片要「直接被模型看见」，只有压过尺寸的 JPEG 才能当 data URL 塞进请求。
     */
    fun attachFile(uri: Uri, scope: CoroutineScope) {
        // 有些 provider 给不出类型（null）：取不到就按普通文件处理，别把文件当图片压坏
        val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        val isImage = mime?.startsWith("image/") == true
        // 满了就不再导入：连文件都不往工作区复制，省得白留一份没人引用的附件
        if (!canEnqueuePending(isImage)) return
        // 单张图片源文件上限 20 MB（对齐 DSH）：拿不到长度（-1）就不拦，交给导入时的压缩兜底
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
            // 取当前工作区的口径和 send 完全一致：未分类 / 找不到时用默认工作区
            val root = workspaceRoot()
            val result = if (isImage) {
                UploadStore.importImage(appContext, uri, root)
            } else {
                UploadStore.import(appContext, uri, root)
            }
            result.fold(
                onSuccess = { imported ->
                    val added = withContext(Dispatchers.Main) {
                        // 并发选多张时这里再查一次：入口那次检查可能都没满，回来却已经超了
                        if (!canEnqueuePending(isImage)) return@withContext false
                        val pending = PendingFile(imported.name, imported.relPath)
                        if (isImage) pendingImages.add(pending) else attachments.add(pending)
                        true
                    }
                    // 没进队列（超上限）就别记「已加入发送队列」，免得日志和界面说法对不上
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
                    withContext(Dispatchers.Main) { reportError("上传失败：" + message) }
                    FileLog.log("upload", (if (isImage) "图片上传失败：" else "附件上传失败：") + message)
                }
            )
        }
    }

    /** 队列还有空位就返回 true；满了顺手把提示写进错误条，调用方只管按结果决定加不加。 */
    private fun canEnqueuePending(isImage: Boolean): Boolean {
        // 只有图片有数量上限：普通附件按 DSH 口径不限个数，正文里给的是路径
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

    /** 设置页进入时刷一次占用：IO 里扫 uploads 目录，回来只更新一句文本。 */
    fun refreshAttachmentUsage() {
        scope.launch { attachmentUsageText = computeAttachmentUsageText() ?: "统计失败" }
    }

    /**
     * 清理当前工作区里没有被任何会话引用的附件（设置页「清理未引用附件」）。
     *
     * 删除必须放 IO：uploads 里可能是几千个小文件；扫完回主线程拼一句中文给界面。
     * 会话里的路径都是工作区相对的，所以只跟「本工作区 + 未分类」的会话比对，别的工作区不算引用。
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
            // 清完立刻重算占用，界面上的数字和磁盘一致
            attachmentUsageText = computeAttachmentUsageText() ?: "统计失败"
            onDone(text)
            FileLog.log("upload", "清理未引用附件：" + text)
        }
    }

    /** 「总占用（未引用 N）」文本；统计失败回 null，由调用方给一句失败提示。 */
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

    /** 字节数 → 一位小数的 MB，跟导出成功那句用同一种写法。 */
    private fun mbText(bytes: Long): String = "%.1f".format(bytes / 1024.0 / 1024.0) + " MB"
}
