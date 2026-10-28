package com.zmf.agent.agent.workspace

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.tools.PathGate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * 一个工作区：一组独立目录 + 一组会话。
 *
 * 目录归属规则：
 * - 默认工作区（id = "default"、dirName = ""）沿用老的 externalFilesDir/workspace，
 *   历史文件一个都不用搬；
 * - 自建工作区的目录是 externalFilesDir/workspaces/<dirName>。
 *
 * 「未分类」不是一个 WorkSpace：它是会话的 workspaceId == null 的状态。
 */
data class WorkSpace(
    /** 工作区 id，创建时的时间戳字符串（同一毫秒内连续创建会自增，不撞号）。 */
    val id: String,
    /** 用户可见名，如 "A"。 */
    val name: String,
    /** 目录名，只允许 [a-zA-Z0-9_-]；空串表示「用老的 workspace 目录」（只有默认工作区会这样）。 */
    val dirName: String,
    val createdAt: Long,
    /**
     * 用户自选的绝对路径；null = 用应用私有目录（老行为）。
     *
     * 非空时这个工作区的根就是该目录，**不再**在 externalFilesDir/workspaces 下另建同名目录。
     * 旧 JSON 里没有这个键，读出来就是 null，已有工作区一个都不受影响。
     */
    val rootPath: String? = null,
    /**
     * SAF 授权目录（ACTION_OPEN_DOCUMENT_TREE 拿到的 tree URI 字符串）；null = 没有。
     *
     * 有它时优先走 URI 分支：权限在选目录时就持久化了，[resolveRoot] 会返回 null 把调用方
     * 导到 [openTreeRoot]。此时 [rootPath] 只是「反推出来的本地可读路径」，方便展示与兜底。
     */
    val treeUri: String? = null
)

/**
 * 工作区落盘：filesDir/appdata/workspaces.json（老位置的 filesDir/workspaces.json 自动迁移）。
 *
 * 和 SessionStore 一样只用一个 JSON 文件整体覆盖写（工作区是用户手工点出来的，
 * 几条到几十条量级，不需要数据库），写失败只记日志不抛。
 */
object WorkSpaceStore {

    private const val TAG = "workspace"
    private const val FILE_NAME = "workspaces.json"

    private const val KEY_WORKSPACES = "workspaces"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_DIR = "dirName"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_ROOT_PATH = "rootPath"
    private const val KEY_TREE_URI = "treeUri"

    /** 默认工作区的固定 id：不允许删除。 */
    const val DEFAULT_ID = "default"

    private const val DEFAULT_NAME = "默认工作区"

    /** 自建工作区目录都放在这里。 */
    private const val WORKSPACES_DIR = "workspaces"

    /** 软删除（目录改名）的目标目录，用来实现「删除先留快照」，所以绝不做真删。 */
    private const val TRASH_DIR = ".trash"

    /** 上一次发出的 id，保证同一毫秒内连点两次「新建工作区」也不会撞 id。 */
    @Volatile
    private var lastIssuedId: Long = 0L

    /** 落盘位置：filesDir/appdata/workspaces.json（首次读时由 [AppPaths] 从旧位置迁过来）。 */
    private fun storeFile(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    /** 外部私有目录；极少数机器拿不到外部目录时退回内部目录，行为与 PathGate 一致。 */
    private fun externalBase(context: Context): File =
        context.applicationContext.getExternalFilesDir(null)
            ?: context.applicationContext.filesDir

    private fun workspacesRoot(context: Context): File = File(externalBase(context), WORKSPACES_DIR)

    // ---- 对外 ----

    /**
     * 读全部工作区。
     *
     * 第一次运行还没有文件时返回 listOf(defaultSpace)，保证界面至少有一个工作区；
     * 文件存在但内容坏掉/为空则返回空列表（真的是故障状态，交给调用方兜底）。
     */
    fun load(context: Context): List<WorkSpace> {
        val file = storeFile(context)
        if (!file.exists()) return listOf(defaultSpace(context))
        if (file.length() == 0L) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) emptyList() else parse(text)
        } catch (e: Exception) {
            FileLog.log(TAG, "读取工作区失败：" + e)
            emptyList()
        }
    }

    /** 整表覆盖写：先 .tmp 后 rename，避免半截文件；失败只记日志，内存里的列表仍是新的。 */
    fun save(context: Context, list: List<WorkSpace>) {
        try {
            val array = JSONArray()
            for (space in list) array.put(spaceToJson(space))
            val root = JSONObject()
            root.put(KEY_WORKSPACES, array)
            val json = root.toString()

            val file = storeFile(context)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, FILE_NAME + ".tmp")
            temp.writeText(json, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                // rename 失败（个别文件系统）时退化成直接覆盖，保证内容落地
                file.writeText(json, Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: Exception) {
            FileLog.log(TAG, "保存工作区失败：" + e)
        }
    }

    /** 默认工作区：dirName 为空串表示目录仍然用老的 externalFilesDir/workspace。 */
    @Suppress("UNUSED_PARAMETER")
    fun defaultSpace(context: Context): WorkSpace =
        WorkSpace(
            id = DEFAULT_ID,
            name = DEFAULT_NAME,
            dirName = "",
            // 不是用户建的，没有真实的创建时间
            createdAt = 0L
        )

    /** 新建工作区：先建目录再返回对象（目录建不出来也照样返回，工具写文件时会再 mkdirs）。 */
    fun create(context: Context, name: String): WorkSpace {
        val trimmed = name.trim().ifEmpty { "工作区" }
        val now = System.currentTimeMillis()
        val id = nextId(now)
        val dirName = dirNameFor(context, id, trimmed)
        val dir = File(workspacesRoot(context), dirName)
        if (!dir.exists()) dir.mkdirs()
        FileLog.log(TAG, "新建工作区 " + trimmed + "（id=" + id + "，目录=" + dir.absolutePath + "）")
        return WorkSpace(id = id, name = trimmed, dirName = dirName, createdAt = now)
    }

    /**
     * 新建「指向用户目录」的工作区：目录由用户自己选（绝对路径 / SAF 授权目录），
     * **不在应用私有目录里再建同名目录**：用户选了哪个目录，工作区的根就是哪个目录。
     *
     * 校验口径（任一条不满足都返回 null，宁可让界面提示一句，也不建一个指不到的坏工作区）：
     * - [rootPath] 非空：必须已存在、是目录、可写；不替用户创建、不猜；
     * - [treeUri] 非空：必须仍持有持久读授权（选目录那一刻 takePersistableUriPermission 拿的）；
     * - 两个都为空：退回 [create]，行为与老路径完全一致（默认工作区 / 未分类不受影响）；
     * - 两个都给（SAF 选完又反推出了本地路径）：都记下来，读写按 treeUri 优先。
     *
     * 和 [create] 一样**不落盘**：调用方把返回值塞进列表后再 [save]。
     */
    fun createWithRoot(
        context: Context,
        name: String,
        rootPath: String?,
        treeUri: String?
    ): WorkSpace? {
        val trimmed = name.trim().ifEmpty { "工作区" }
        val path = rootPath?.trim()?.takeIf { it.isNotEmpty() }
        val tree = treeUri?.trim()?.takeIf { it.isNotEmpty() }
        if (path == null && tree == null) return create(context, trimmed)
        if (path != null) {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                FileLog.log(TAG, "用户目录不可用：" + path + "（不存在或不是目录）")
                return null
            }
            if (!dir.canWrite()) {
                FileLog.log(TAG, "用户目录不可写：" + path)
                return null
            }
        }
        if (tree != null && !hasTreePermission(context, tree)) {
            FileLog.log(TAG, "SAF 目录没有持久授权：" + tree)
            return null
        }
        val now = System.currentTimeMillis()
        val id = nextId(now)
        // 目录名只当「标识」用（认人、日志里对得上）：用户目录本来就在，这里绝不再建一份
        val dirName = dirNameFor(context, id, trimmed)
        FileLog.log(
            TAG,
            "新建工作区 " + trimmed + "（id=" + id + "，用户目录=" + (path ?: tree) + "）"
        )
        return WorkSpace(
            id = id,
            name = trimmed,
            dirName = dirName,
            createdAt = now,
            rootPath = path,
            treeUri = tree
        )
    }

    /**
     * 工作区真正的根目录（File 分支）。
     *
     * 返回 null 只有两种情形，调用方必须能分辨并走对应分支：
     * ① 这个工作区走 SAF（[WorkSpace.treeUri] 非空）：File 表示不了它，请改用 [openTreeRoot]；
     * ② 用户选的目录被删了 / 移走了：提示用户重新选，不要默默换成私有目录。
     */
    fun resolveRoot(context: Context, record: WorkSpace): File? {
        val tree = record.treeUri
        if (tree != null && tree.isNotBlank()) return null
        val custom = record.rootPath
        if (custom != null && custom.isNotBlank()) {
            val dir = File(custom)
            return if (dir.isDirectory) dir else null
        }
        return dirOf(context, record)
    }

    /**
     * URI 分支入口：这个工作区的 SAF 根（[WorkSpace.treeUri] 非空才有）。
     *
     * 返回 null = 没有 SAF 根，或授权已失效（用户清过授权 / 换了设备）；此时应回落到
     * [resolveRoot] 并给用户一句提示。拿到 [TreeRoot] 之后所有读写都走它，
     * 不要再拿 `File` 去碰这条路（Android 11+ 上读不到）。
     */
    fun openTreeRoot(context: Context, record: WorkSpace): TreeRoot? {
        val raw = record.treeUri
        if (raw == null || raw.isBlank()) return null
        if (!hasTreePermission(context, raw)) {
            FileLog.log(TAG, "SAF 授权已失效：" + raw)
            return null
        }
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return TreeRoot(context, uri)
    }

    /** 这条 tree URI 是否还持有持久读授权（选目录时 takePersistableUriPermission 拿到的）。 */
    fun hasTreePermission(context: Context, treeUri: String): Boolean {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return false
        return try {
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        } catch (e: Exception) {
            FileLog.log(TAG, "查 SAF 授权失败：" + e)
            false
        }
    }

    /**
     * 这个工作区该用哪种读写实现：普通目录 → FileWorkspaceIo；SAF 授权目录 → SafWorkspaceIo。
     *
     * 返回 null = 用户选的目录被删了 / SAF 授权失效，调用方应提示用户重新选目录，
     * **不要**退回私有目录（那会让模型以为自己写进了用户目录）。
     */
    fun workspaceIoFor(context: Context, space: WorkSpace): WorkspaceIo? {
        if (!space.treeUri.isNullOrBlank()) {
            val tree = openTreeRoot(context, space) ?: return null
            // 相对路径是从 dirOf（私有兜底目录）解析出来的，SafWorkspaceIo 要靠它反推相对路径
            val fallback = File(workspacesRoot(context), space.dirName.ifEmpty { space.id })
            return SafWorkspaceIo(tree, space.rootPath, fallback)
        }
        val root = resolveRoot(context, space) ?: return null
        return FileWorkspaceIo(root)
    }

    /**
     * 按「工作区根目录」反查工作区：文件工具手里只有 ToolContext.workspaceRoot，
     * 靠它把 SAF 工作区认出来（工具与工作区之间没有别的通道）。
     *
     * 这里**不调 dirOf**：dirOf 对自建工作区会顺手 mkdirs，反查不该带副作用。
     */
    fun findByRoot(context: Context, root: File): WorkSpace? {
        val target = runCatching { root.canonicalPath }.getOrNull() ?: root.absolutePath
        return load(context).firstOrNull { space ->
            val dir = when {
                space.rootPath != null && space.rootPath.isNotBlank() && space.id != DEFAULT_ID ->
                    File(space.rootPath)
                space.id == DEFAULT_ID || space.dirName.isEmpty() -> PathGate.workspaceRoot(context)
                else -> File(workspacesRoot(context), space.dirName)
            }
            runCatching { dir.canonicalPath }.getOrNull() == target
        }
    }

    /**
     * 工作区对应的目录。默认工作区复用 PathGate.workspaceRoot（老的 workspace），
     * 其它工作区是 externalFilesDir/workspaces/<dirName>，不存在就建出来。
     */
    fun dirOf(context: Context, space: WorkSpace): File {
        // 用户自选的绝对路径优先：那是用户自己的目录，不替他建、也不往里加一层同名目录
        val custom = space.rootPath
        if (custom != null && custom.isNotBlank() && space.id != DEFAULT_ID) {
            return File(custom)
        }
        if (space.id == DEFAULT_ID || space.dirName.isEmpty()) {
            return PathGate.workspaceRoot(context)
        }
        val dir = File(workspacesRoot(context), space.dirName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 改工作区的显示名；id 不存在返回 null。
     *
     * **只改名字、绝不挪目录**：目录名在创建时就定了，会话里存的附件 / 图片都是相对这个目录的路径，
     * 改名顺手挪目录会把历史文件全部挪成失效路径。名字是给人看的，目录名是给路径用的，两者本来就该分开。
     * 默认工作区也允许改名（同样只是显示层的事）。
     */
    fun rename(context: Context, id: String, name: String): WorkSpace? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val list = load(context)
        val target = list.firstOrNull { it.id == id } ?: return null
        if (target.name == trimmed) return target
        val renamed = target.copy(name = trimmed)
        save(context, list.map { if (it.id == id) renamed else it })
        FileLog.log(TAG, "工作区改名：" + target.name + " → " + trimmed + "（" + id + "）")
        return renamed
    }

    /**
     * 软删除：把工作区目录改名到 externalFilesDir/.trash/<dirName>-<now>，绝不真删。
     *
     * - 默认工作区不允许删除（返回 false）：它的目录是全体历史文件的根；
     * - rename 失败返回 false，原目录保持不动。
     */
    fun softDelete(context: Context, space: WorkSpace): Boolean {
        if (space.id == DEFAULT_ID || space.dirName.isEmpty()) {
            FileLog.log(TAG, "默认工作区不允许删除")
            return false
        }
        // 指向用户目录（绝对路径 / SAF 授权目录）的工作区只能解除绑定，**绝不**把用户的目录挪进回收站：
        // 那里可能有用户自己的文件，删工作区不等于允许 App 搬他的东西
        if (space.rootPath != null || space.treeUri != null) {
            FileLog.log(TAG, "工作区 " + space.name + " 指向用户目录，只解除绑定、目录保持原地不动")
            return false
        }
        val dir = dirOf(context, space)
        val trashRoot = File(externalBase(context), TRASH_DIR)
        if (!trashRoot.exists()) trashRoot.mkdirs()
        val target = File(trashRoot, space.dirName + "-" + System.currentTimeMillis())
        val ok = try {
            dir.renameTo(target)
        } catch (e: Exception) {
            FileLog.log(TAG, "工作区目录改名失败：" + e)
            false
        }
        if (ok) {
            FileLog.log(TAG, "工作区 " + space.name + " 的目录已移入回收站：" + target.absolutePath)
        } else {
            FileLog.log(TAG, "工作区 " + space.name + " 的目录改名失败，保留原目录")
        }
        return ok
    }

    // ---- 序列化 ----

    private fun spaceToJson(space: WorkSpace): JSONObject {
        val obj = JSONObject()
        obj.put(KEY_ID, space.id)
        obj.put(KEY_NAME, space.name)
        obj.put(KEY_DIR, space.dirName)
        obj.put(KEY_CREATED_AT, space.createdAt)
        // 只有真的用了用户目录才写这两个键：老工作区落盘的内容跟从前逐字节一致
        val rootPath = space.rootPath
        if (rootPath != null && rootPath.isNotBlank()) obj.put(KEY_ROOT_PATH, rootPath)
        val treeUri = space.treeUri
        if (treeUri != null && treeUri.isNotBlank()) obj.put(KEY_TREE_URI, treeUri)
        return obj
    }

    private fun parse(text: String): List<WorkSpace> {
        val root = JSONObject(text)
        val array = root.optJSONArray(KEY_WORKSPACES) ?: return emptyList()
        val list = ArrayList<WorkSpace>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString(KEY_ID, "")
            // id 是唯一标识，缺了这条就没法切换/删除，直接丢
            if (id.isEmpty()) continue
            list.add(
                WorkSpace(
                    id = id,
                    name = obj.optString(KEY_NAME, "").ifEmpty { id },
                    dirName = sanitizeDir(obj.optString(KEY_DIR, "")),
                    createdAt = obj.optLong(KEY_CREATED_AT, 0L),
                    // 旧 JSON 没有这两个键：optString 给 ""，再折成 null（＝应用私有目录，老行为）
                    rootPath = obj.optString(KEY_ROOT_PATH, "").ifBlank { null },
                    treeUri = obj.optString(KEY_TREE_URI, "").ifBlank { null }
                )
            )
        }
        return list
    }

    // ---- 目录名 ----

    /** 目录名只允许 [a-zA-Z0-9_-]：名字里可能有中文/空格/斜杠，不能直接当目录名。 */
    private fun sanitizeDir(raw: String): String =
        raw.trim().replace(Regex("[^a-zA-Z0-9_-]"), "")

    /** 优先用名字里的 ASCII 片段当目录名（方便 adb pull 认人），没有可用字符就用 id。 */
    private fun dirNameFor(context: Context, id: String, name: String): String {
        val slug = sanitizeDir(name).take(24)
        var dirName = slug.ifEmpty { sanitizeDir(id) }
        // 同名工作区（都是 "A"）会撞目录：撞了就用 id 兜底，保证一个工作区一个目录
        if (File(workspacesRoot(context), dirName).exists()) dirName = sanitizeDir(id)
        return dirName
    }

    /** 时间戳 id；同一毫秒内第二次创建就 +1，避免撞号。 */
    private fun nextId(now: Long): String {
        val value = if (now > lastIssuedId) now else lastIssuedId + 1
        lastIssuedId = value
        return value.toString()
    }
}

/**
 * SAF 授权目录的读写入口（URI 分支）。
 *
 * 为什么不用 androidx.documentfile 的 DocumentFile：这个模块没有那条依赖，本轮也不许加依赖，
 * 所以直接走框架的 [DocumentsContract]（DocumentFile 内部就是它，行为等价）。
 * 以后真加了依赖，把实现换成 DocumentFile 即可，对外签名不用动。
 *
 * **主会话接线**（这是「URI 分支」的唯一入口）：
 * 1. `WorkSpaceStore.resolveRoot(context, space)` 拿到 File 就走老路，行为不变；
 * 2. 拿到 null 且 `space.treeUri != null`，就 `WorkSpaceStore.openTreeRoot(context, space)` 拿一个
 *    [TreeRoot]，再用 [TreeRoot.readText] / [TreeRoot.writeText] / [TreeRoot.listDirs] 做读写；
 * 3. 两个都拿不到（用户目录被删了 / 授权被清了）：提示用户重新选目录，**不要**悄悄退回私有目录。
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
            // 大小不一定有：拿不到就记 -1，别当成 0 报给模型
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
