package com.zmf.agent.core.store

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.zmf.agent.core.log.FileLog
import java.io.InputStream

/**
 * 一个工作区：名称 + 一个存储单元。
 *
 * 工作区只是一层映射：移动工作区是改映射，删除工作区是解除映射，两者都不移动、不删除任何用户文件。
 * 目录信息全部存放在 [Storage] 上，因此工作区改名、会话换分组都不会出现两份需要同步的路径。
 *
 * 「未分类」不是 WorkSpace：它表示会话的 `workspaceId == null` 的状态，没有操作菜单。
 */
data class WorkSpace(
    /** 工作区 id，创建时的时间戳字符串（同一毫秒内连续创建时自增，不产生重复编号）。 */
    val id: String,
    /** 用户可见名，如「A」。 */
    val name: String,
    /** 该工作区映射到的存储单元 id；默认工作区恒为 [StorageStore.DEFAULT_ID]。 */
    val storageId: String,
    val createdAt: Long = 0L
) {
    /** 默认工作区：不可删除、不可更改储存路径，可改名。 */
    val isDefault: Boolean get() = id == WorkSpaceStore.DEFAULT_ID
}

/**
 * 工作区落盘：`filesDir/appdata/workspaces.json`（旧位置 `filesDir/workspaces.json` 自动迁移）。
 *
 * 该文件同时保存工作区表与存储单元表，因此读写统一经由 [StorageStore]：
 * 本对象只提供工作区语义上的便捷入口，不自行写文件，避免两处各写一次造成互相覆盖。
 */
object WorkSpaceStore {

    private const val TAG = "workspace"

    /** 默认工作区的固定 id：不允许删除。 */
    const val DEFAULT_ID = StorageStore.DEFAULT_ID

    /** 读全部工作区；文件缺失或损坏时返回仅含默认工作区的最小可用集合。 */
    fun load(context: Context): List<WorkSpace> = StorageStore.loadWorkspaces(context)

    /** 整表覆盖写（存储单元表保持原样）。 */
    fun save(context: Context, list: List<WorkSpace>) = StorageStore.saveWorkspaces(context, list)

    /** 默认工作区：绑定默认存储单元。 */
    fun defaultSpace(): WorkSpace = StorageStore.defaultWorkspace()

    /**
     * 组装一条新的工作区记录，**不落盘**：调用方把存储单元与记录一并写回，
     * 保证「记录存在则存储单元必然存在」。
     */
    fun newRecord(name: String, storageId: String, createdAt: Long = System.currentTimeMillis()): WorkSpace =
        WorkSpace(
            id = StorageStore.nextId(createdAt),
            name = name.trim().ifEmpty { "工作区" },
            storageId = storageId,
            createdAt = createdAt
        )

    /** 该工作区映射到的存储单元；记录损坏（指向不存在的单元）时返回 null。 */
    fun storageOf(context: Context, space: WorkSpace): Storage? = StorageStore.find(context, space.storageId)

    /**
     * 改工作区的显示名；id 不存在返回 null。
     *
     * **只改名称，不动目录**：目录属于存储单元，且会话中的附件、图片都是相对该目录的路径，
     * 目录名一旦变化，历史文件的引用会全部失效。
     *
     * 默认工作区的名称固定「默认工作区」：它是全局兜底分组，改名会让「默认」这层语义在界面与
     * 提示词里同时消失，因此与「不可删除、不可改路径」一并拒绝。
     */
    fun rename(context: Context, id: String, name: String): WorkSpace? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        if (id == DEFAULT_ID) {
            FileLog.log(TAG, "默认工作区不允许改名")
            return null
        }
        val list = load(context)
        val target = list.firstOrNull { it.id == id } ?: return null
        if (target.name == trimmed) return target
        val renamed = target.copy(name = trimmed)
        save(context, list.map { if (it.id == id) renamed else it })
        FileLog.log(TAG, "工作区改名：" + target.name + " → " + trimmed + "（" + id + "）")
        return renamed
    }
}

/**
 * SAF 授权目录的读写入口（URI 分支）。
 *
 * 未使用 androidx.documentfile 的 DocumentFile：该模块未引入此依赖，当前也不允许新增依赖，
 * 因此直接使用框架的 [DocumentsContract]（DocumentFile 内部同样基于它，行为等价）。
 * 后续若引入该依赖，可将实现替换为 DocumentFile，对外签名无需改动。
 *
 * **接线**：`StorageStore.ioFor` 负责判断该用 File 分支还是本类；
 * `StorageStore.openTreeRoot` 返回 null 表示授权已失效，调用方必须提示重新授权，
 * **不得**静默退回私有目录。
 *
 * 一个 [TreeRoot] 只绑一个目录；要进子目录用 [TreeRoot.childDir]。
 */
class TreeRoot internal constructor(
    private val context: Context,
    val treeUri: Uri
) {

    /** 一个子项：文档 id + 显示名 + 是不是目录 + 字节数（provider 不给就是 -1）。 */
    class Item internal constructor(
        val docId: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long
    )

    private val rootDocId: String =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")

    /** 这个根本身能不能用（拿不到 treeDocumentId 就是坏的）。 */
    val isAvailable: Boolean get() = rootDocId.isNotEmpty()

    /** 全部子项（不递归）。 */
    fun listChildren(): List<Item> = queryChildren(rootDocId)

    /** 子文件夹，按名字排序；跳过 `.` 开头的隐藏目录（跟本地列表同一个口径）。 */
    fun listDirs(): List<Item> = listChildren()
        .filter { it.isDirectory && !it.name.startsWith(".") }
        .sortedBy { it.name.lowercase() }

    /** 全部文件（不递归）。 */
    fun listFiles(): List<Item> = listChildren().filter { !it.isDirectory }

    /** 某个名字的子项；没有返回 null。 */
    fun find(name: String): Item? = listChildren().firstOrNull { it.name == name }

    /** 这个目录下有没有这个名字的子项；不存在返回 false，不抛异常。 */
    fun exists(name: String): Boolean = find(name) != null

    /** 这个目录下某个文件的字节数；找不到或 provider 不给就是 -1。 */
    fun size(name: String): Long = find(name)?.size ?: -1L

    /** 打开这个目录下某个文件的输入流（按行读大文件用）；失败返回 null。 */
    fun openInputStream(name: String): InputStream? {
        val item = find(name) ?: return null
        if (item.isDirectory) return null
        return runCatching { context.contentResolver.openInputStream(documentUri(item.docId)) }
            .getOrNull()
    }

    /** 删除这个目录下的文件或子目录（子目录递归由 provider 处理）；成功 true。 */
    fun delete(name: String): Boolean {
        val item = find(name) ?: return false
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, documentUri(item.docId))
        } catch (e: Exception) {
            FileLog.log(LOG_TAG, "删 SAF 文件失败：" + name + "，" + e)
            false
        }
    }

    /** 文档 id → 树内的 document URI（读写都用它）。 */
    fun documentUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /** 根目录自己的 URI。 */
    fun rootUri(): Uri = documentUri(rootDocId)

    /** 读一个文本文件；不存在 / 是目录 / 读不了都返回 null。 */
    fun readText(name: String): String? {
        val item = find(name) ?: return null
        if (item.isDirectory) return null
        return try {
            context.contentResolver.openInputStream(documentUri(item.docId))?.use { input ->
                String(input.readBytes(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            FileLog.log(LOG_TAG, "读 SAF 文件失败：" + name + "，" + e)
            null
        }
    }

    /** 写一个文本文件：不存在就新建，存在就整篇覆盖。 */
    fun writeText(name: String, text: String): Boolean {
        val item = find(name)
        val uri = when {
            item == null -> createFile(name) ?: return false
            item.isDirectory -> return false
            else -> documentUri(item.docId)
        }
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
            } != null
        } catch (e: Exception) {
            FileLog.log(LOG_TAG, "写 SAF 文件失败：" + name + "，" + e)
            false
        }
    }

    /** 在根下建一个文件，返回它的 URI；失败返回 null。 */
    fun createFile(name: String, mime: String = "application/octet-stream"): Uri? = try {
        DocumentsContract.createDocument(context.contentResolver, rootUri(), mime, name)
    } catch (e: Exception) {
        FileLog.log(LOG_TAG, "在 SAF 目录里建文件失败：" + name + "，" + e)
        null
    }

    /** 在根下建一个子目录，返回它的 URI；失败返回 null。 */
    fun mkdir(name: String): Uri? = try {
        DocumentsContract.createDocument(
            context.contentResolver,
            rootUri(),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        )
    } catch (e: Exception) {
        FileLog.log(LOG_TAG, "在 SAF 目录里建子目录失败：" + name + "，" + e)
        null
    }

    /** 进入子目录，拿到它的 [TreeRoot]；不是目录 / 名字不存在返回 null。 */
    fun childDir(name: String): TreeRoot? {
        val item = find(name) ?: return null
        if (!item.isDirectory) return null
        val authority = treeUri.authority ?: return null
        val childTree = DocumentsContract.buildTreeDocumentUri(authority, item.docId)
        return TreeRoot(context, childTree)
    }

    private fun queryChildren(docId: String): List<Item> {
        if (docId.isEmpty()) return emptyList()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            // 大小可能缺失：无法获取时记为 -1，不得按 0 返回给模型
            DocumentsContract.Document.COLUMN_SIZE
        )
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val result = ArrayList<Item>()
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docIdValue = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (cursor.isNull(3)) -1L else cursor.getLong(3)
                    result.add(Item(docIdValue, displayName, isDir, size))
                }
            }
        } catch (e: Exception) {
            FileLog.log(LOG_TAG, "列 SAF 目录失败：" + e)
        }
        return result
    }

    private companion object {
        const val LOG_TAG = "workspace"
    }
}
