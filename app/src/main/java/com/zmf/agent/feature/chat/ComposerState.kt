package com.zmf.agent.feature.chat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
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

/** 单张图片的源文件上限：50 MB（对齐 DSH；模型看到的是归一化后的图，这条只拦超大原图）。 */
private const val MAX_IMAGE_BYTES = 50L * 1024 * 1024

/** 待发送的附件：仅记录显示名与文件在工作区中的相对路径，模型通过 read_file 读取。 */
data class PendingFile(val name: String, val relPath: String)

/**
 * 输入区（Composer）的状态与动作：草稿、待发附件、待发图片、附件占用文案。
 *
 * 从 [ChatViewModel] 拆出，行为不变：字段名与原成员逐字一致，方法体原样保留；
 * 仅将四类只读依赖改为注入——当前工作区根目录、可能引用上传文件的会话集、
 * 错误回显入口、统计用协程域——避免该类反向持有整个 ViewModel。
 *
 * **待发附件与待发图片按会话分槽**（用户 2026-11-16，对齐 DSH 的「每会话一个编辑器实例」）：
 * 切会话只换看哪一份，不再清空；发送只清本会话的槽。正文草稿本来也按会话存（`DraftStore`），
 * 两者因此口径一致。
 */
class ComposerState(
    private val appContext: Context,
    private val workspaceRoot: () -> File,
    private val sessionsReferencingUploads: () -> List<ChatSession>,
    private val scope: CoroutineScope,
    private val reportError: (String) -> Unit,
    private val sessionIdOf: () -> String
) {

    /** 单条会话的待发槽：附件与待发图片各一份，互不影响。 */
    private class Slot {
        val files = mutableStateListOf<PendingFile>()
        val images = mutableStateListOf<PendingFile>()
    }

    /** 会话 id → 待发槽；按需创建，会话从未挂过附件时不占空间。 */
    private val slots = HashMap<String, Slot>()

    private fun slotOf(sessionId: String): Slot = slots.getOrPut(sessionId) { Slot() }

    /** 输入框内容（受控状态）。回滚时将被删除的该条提问填回此处，光标落在末尾。 */
    var inputText by mutableStateOf(TextFieldValue(""))
        internal set

    /**
     * 待发送附件（**当前显示会话**的那一份）：选中的文件已经复制进工作区，点发送时才拼进提问内容。
     *
     * 是 getter 而不是固定列表：切会话后读到的是另一条会话的槽，界面随之重组。
     */
    val attachments: SnapshotStateList<PendingFile> get() = slotOf(sessionIdOf()).files

    /**
     * 待发送图片（**当前显示会话**的那一份）：单独一条队列，因为图片除了路径还要读盘编码成 data URL。
     *
     * relPath 仍是工作区相对路径：界面靠它解码缩略图，会话落盘也只存这个路径。
     */
    val pendingImages: SnapshotStateList<PendingFile> get() = slotOf(sessionIdOf()).images

    /** 某条会话的待发附件快照：发送链路按**目标会话**取，不用显示会话的槽。 */
    fun filesOf(sessionId: String): List<PendingFile> = slotOf(sessionId).files.toList()

    /** 某条会话的待发图片快照；口径同 [filesOf]。 */
    fun imagesOf(sessionId: String): List<PendingFile> = slotOf(sessionId).images.toList()

    /** 清掉某条会话的两个槽：一条消息已经把这两份内容带走（或收进队列）后调用。 */
    fun clearSlot(sessionId: String) {
        val slot = slots[sessionId] ?: return
        slot.files.clear()
        slot.images.clear()
    }

    /** 全部会话槽里还排着的路径：清理未引用附件时按引用计，宁可少删不可误删。 */
    fun allPendingRelPaths(): List<String> =
        slots.values.flatMap { slot -> slot.files.map { it.relPath } + slot.images.map { it.relPath } }

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
     *
     * 目标会话在**发起时**钉住：导入期间用户可能已经切走，文件仍应落进发起时那条会话的槽，
     * 否则切过去的另一条会话会凭空多出一份附件。
     */
    fun attachFile(uri: Uri, scope: CoroutineScope) {
        val target = sessionIdOf()
        // 部分 provider 无法提供类型（null）：取不到时按普通文件处理，避免将文件作为图片压缩损坏
        val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        val isImage = mime?.startsWith("image/") == true
        // 已满则停止导入：连文件也不复制至工作区，避免保留一份无人引用的附件
        if (!canEnqueuePending(target, isImage)) return
        // 单张图片源文件上限 50 MB（对齐 DSH）：无法获取长度（-1）时不拦截，交由导入时的归一化兜底
        if (isImage) {
            val bytes = runCatching {
                appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            if (bytes > MAX_IMAGE_BYTES) {
                reportError("单张图片最大 50 MB")
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
                        if (!canEnqueuePending(target, isImage)) return@withContext false
                        val pending = PendingFile(imported.name, imported.relPath)
                        val slot = slotOf(target)
                        if (isImage) slot.images.add(pending) else slot.files.add(pending)
                        true
                    }
                    // 未进入队列（超上限）则不记录「已加入发送队列」，避免日志与界面描述不一致
                    if (added) {
                        FileLog.log(
                            "upload",
                            (if (isImage) "图片已加入发送队列：" else "附件已加入发送队列：") +
                                imported.relPath + "（" + imported.size + " 字节，会话 " + target + "）"
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

    /** 指定会话的图片槽仍有空位则返回 true；已满时将提示写入错误条，调用方仅按结果决定是否加入。 */
    private fun canEnqueuePending(sessionId: String, isImage: Boolean): Boolean {
        // 仅图片有数量上限：普通附件按 DSH 约定不限个数，正文中给出的是路径
        if (isImage && slotOf(sessionId).images.size >= MAX_IMAGES) {
            reportError("图片最多 " + MAX_IMAGES + " 张")
            return false
        }
        return true
    }

    /** 从待发列表里移除一个普通附件；下标越界时忽略。 */
    fun detachFile(index: Int) {
        if (index in attachments.indices) attachments.removeAt(index)
    }

    /**
     * 把一批普通附件追加进指定会话的待发区，返回真正加入的条数。
     *
     * 按工作区相对路径去重：分享批次移交与队列条目取回都可能带同一份文件，
     * 待发区不得为同一路径出现两条。[sessionId] 默认当前显示会话；
     * 跨 IO 的移交（分享批次）必须显式带上发起时的会话，避免复制期间切走而落错槽。
     */
    fun addFiles(files: List<PendingFile>, sessionId: String = sessionIdOf()): Int {
        val slot = slotOf(sessionId)
        val fresh = files.filter { candidate -> slot.files.none { it.relPath == candidate.relPath } }
        slot.files.addAll(fresh)
        return fresh.size
    }

    /**
     * 把一批图片追加进指定会话的待发区，返回真正加入的张数。
     *
     * 同样按路径去重；并按单条消息的图片上限 [MAX_IMAGES] 截断，超出部分由调用方提示用户。
     */
    fun addImages(images: List<PendingFile>, sessionId: String = sessionIdOf()): Int {
        val slot = slotOf(sessionId)
        val room = (MAX_IMAGES - slot.images.size).coerceAtLeast(0)
        val fresh = images.filter { candidate -> slot.images.none { it.relPath == candidate.relPath } }
            .take(room)
        slot.images.addAll(fresh)
        return fresh.size
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
