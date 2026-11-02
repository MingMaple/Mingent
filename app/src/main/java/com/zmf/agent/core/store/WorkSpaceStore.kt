package com.zmf.agent.core.store

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.fs.PathGate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * 一个工作区：一组独立目录 + 一组会话。
 *
 * 目录归属规则：
 * - 默认工作区（id = "default"、dirName = ""）沿用既有的 externalFilesDir/workspace，
 *   历史文件无需迁移；
 * - 自建工作区的目录为 externalFilesDir/workspaces/<dirName>。
 *
 * 「未分类」不是 WorkSpace：它表示会话的 workspaceId == null 的状态。
 */
data class WorkSpace(
    /** 工作区 id，创建时的时间戳字符串（同一毫秒内连续创建时自增，不产生重复编号）。 */
    val id: String,
    /** 用户可见名，如 "A"。 */
    val name: String,
    /** 目录名，仅允许 [a-zA-Z0-9_-]；空串表示沿用既有的 workspace 目录（仅默认工作区如此）。 */
    val dirName: String,
    val createdAt: Long,
    /**
     * 用户自选的绝对路径；null 表示使用应用私有目录。
     *
     * 非空时该工作区的根即为该目录，**不**在 externalFilesDir/workspaces 下另建同名目录。
     * 旧 JSON 无此键，读取结果为 null，已有工作区不受影响。
     */
    val rootPath: String? = null,
    /**
     * SAF 授权目录（ACTION_OPEN_DOCUMENT_TREE 拿到的 tree URI 字符串）；null = 没有。
     *
     * 存在时优先使用 URI 分支：权限在选择目录时即持久化，[resolveRoot] 返回 null 并将调用方
     * 导向 [openTreeRoot]。此时 [rootPath] 仅为反推出的本地可读路径，用于展示与兜底。
     */
    val treeUri: String? = null
)

/**
 * 工作区落盘：filesDir/appdata/workspaces.json（旧位置 filesDir/workspaces.json 自动迁移）。
 *
 * 与 SessionStore 相同，仅使用一个 JSON 文件整体覆盖写（工作区由用户手动创建，
 * 数量在几条至几十条，无需数据库），写失败只记日志，不抛出异常。
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

    /** 软删除（目录改名）的目标目录，用于保留删除快照，因此从不执行真正的删除。 */
    private const val TRASH_DIR = ".trash"

    /** 上一次发出的 id，确保同一毫秒内连续两次新建工作区也不会产生重复 id。 */
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
     * 首次运行尚无文件时返回 listOf(defaultSpace)，保证界面至少有一个工作区；
     * 文件存在但内容损坏或为空时返回空列表（属故障状态，交由调用方处理）。
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

    /** 整表覆盖写：先写 .tmp 后 rename，避免产生不完整文件；失败只记日志，内存中的列表仍为新值。 */
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
                // rename 失败（个别文件系统）时退化为直接覆盖，确保内容写入
                file.writeText(json, Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: Exception) {
            FileLog.log(TAG, "保存工作区失败：" + e)
        }
    }

    /** 默认工作区：dirName 为空串表示目录仍沿用既有的 externalFilesDir/workspace。 */
    @Suppress("UNUSED_PARAMETER")
    fun defaultSpace(context: Context): WorkSpace =
        WorkSpace(
            id = DEFAULT_ID,
            name = DEFAULT_NAME,
            dirName = "",
            // 非用户创建，无实际创建时间
            createdAt = 0L
        )

    /** 新建工作区：先创建目录再返回对象（目录创建失败时仍返回，工具写文件时会再次 mkdirs）。 */
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
     * 新建「指向用户目录」的工作区：目录由用户选择（绝对路径 / SAF 授权目录），
     * **不在应用私有目录中另建同名目录**：用户选定哪个目录，工作区的根即为该目录。
     *
     * 校验口径（任一条件不满足即返回 null，由界面提示，避免创建无法访问的工作区）：
     * - [rootPath] 非空：必须已存在、为目录且可写；不替用户创建，也不推测路径；
     * - [treeUri] 非空：必须仍持有持久读授权（选择目录时通过 takePersistableUriPermission 获取）；
     * - 两者均为空：退回 [create]，行为与既有路径一致（默认工作区 / 未分类不受影响）；
     * - 两者均提供（SAF 选择后又反推出本地路径）：均记录，读写优先使用 treeUri。
     *
     * 与 [create] 相同，**不落盘**：调用方将返回值加入列表后再调用 [save]。
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
        // 目录名仅作标识使用（便于识别与日志对应）：用户目录已存在，此处不创建同名目录
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
     * 仅在两种情形返回 null，调用方必须区分并采用对应分支：
     * ① 该工作区使用 SAF（[WorkSpace.treeUri] 非空）：File 无法表示，应改用 [openTreeRoot]；
     * ② 用户所选目录已被删除或移动：提示用户重新选择，不得静默改用私有目录。
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
     * 返回 null 表示没有 SAF 根，或授权已失效（用户撤销授权或更换设备）；此时应回落到
     * [resolveRoot] 并向用户提示。获取 [TreeRoot] 后所有读写均经由它，
     * 不得再以 `File` 访问该路径（Android 11+ 无法读取）。
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
     * 返回 null 表示用户所选目录已被删除或 SAF 授权失效，调用方应提示用户重新选择目录，
     * **不得**退回私有目录（否则模型会误以为已写入用户目录）。
     */
    fun workspaceIoFor(context: Context, space: WorkSpace): WorkspaceIo? {
        if (!space.treeUri.isNullOrBlank()) {
            val tree = openTreeRoot(context, space) ?: return null
            // 相对路径由 dirOf（私有兜底目录）解析得出，SafWorkspaceIo 依赖它反推相对路径
            val fallback = File(workspacesRoot(context), space.dirName.ifEmpty { space.id })
            return SafWorkspaceIo(tree, space.rootPath, fallback)
        }
        val root = resolveRoot(context, space) ?: return null
        return FileWorkspaceIo(root)
    }

    /**
     * 按「工作区根目录」反查工作区：文件工具仅持有 ToolContext.workspaceRoot，
     * 据此识别 SAF 工作区（工具与工作区之间无其他通道）。
     *
     * 此处**不调用 dirOf**：dirOf 对自建工作区会执行 mkdirs，反查不应产生副作用。
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
     * 工作区对应的目录。默认工作区复用 PathGate.workspaceRoot（既有 workspace），
     * 其他工作区为 externalFilesDir/workspaces/<dirName>，不存在时创建。
     */
    fun dirOf(context: Context, space: WorkSpace): File {
        // 用户自选的绝对路径优先：该目录属于用户，不为其创建，也不追加同名子目录
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
     * **仅修改名称，不移动目录**：目录名在创建时确定，会话中存储的附件 / 图片均为相对该目录的路径，
     * 改名时移动目录会使历史文件路径全部失效。名称用于展示，目录名用于路径，两者应当分开。
     * 默认工作区同样允许改名（仅影响显示层）。
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
     * 软删除：将工作区目录改名至 externalFilesDir/.trash/<dirName>-<now>，不执行真正的删除。
     *
     * - 默认工作区不允许删除（返回 false）：它的目录是全体历史文件的根；
     * - rename 失败返回 false，原目录保持不动。
     */
    fun softDelete(context: Context, space: WorkSpace): Boolean {
        if (space.id == DEFAULT_ID || space.dirName.isEmpty()) {
            FileLog.log(TAG, "默认工作区不允许删除")
            return false
        }
        // 指向用户目录（绝对路径 / SAF 授权目录）的工作区仅解除绑定，**不得**将用户目录移入回收站：
        // 其中可能包含用户文件，删除工作区不代表允许 App 移动用户数据
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
        // 仅在确实使用用户目录时写入这两个键：既有工作区的落盘内容保持逐字节一致
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
            // id 为唯一标识，缺失时无法切换或删除，直接丢弃
            if (id.isEmpty()) continue
            list.add(
                WorkSpace(
                    id = id,
                    name = obj.optString(KEY_NAME, "").ifEmpty { id },
                    dirName = sanitizeDir(obj.optString(KEY_DIR, "")),
                    createdAt = obj.optLong(KEY_CREATED_AT, 0L),
                    // 旧 JSON 无这两个键：optString 返回 ""，再转为 null（即应用私有目录）
                    rootPath = obj.optString(KEY_ROOT_PATH, "").ifBlank { null },
                    treeUri = obj.optString(KEY_TREE_URI, "").ifBlank { null }
                )
            )
        }
        return list
    }

    // ---- 目录名 ----

    /** 目录名仅允许 [a-zA-Z0-9_-]：名称中可能包含中文、空格或斜杠，不能直接作为目录名。 */
    private fun sanitizeDir(raw: String): String =
        raw.trim().replace(Regex("[^a-zA-Z0-9_-]"), "")

    /** 优先使用名称中的 ASCII 片段作为目录名（便于 adb pull 识别），无可用字符时使用 id。 */
    private fun dirNameFor(context: Context, id: String, name: String): String {
        val slug = sanitizeDir(name).take(24)
        var dirName = slug.ifEmpty { sanitizeDir(id) }
        // 同名工作区（均为 "A"）会占用同一目录：冲突时使用 id 兜底，保证一个工作区对应一个目录
        if (File(workspacesRoot(context), dirName).exists()) dirName = sanitizeDir(id)
        return dirName
    }

    /** 时间戳 id；同一毫秒内第二次创建时递增 1，避免重复。 */
    private fun nextId(now: Long): String {
        val value = if (now > lastIssuedId) now else lastIssuedId + 1
        lastIssuedId = value
        return value.toString()
    }
}

/**
 * SAF 授权目录的读写入口（URI 分支）。
 *
 * 未使用 androidx.documentfile 的 DocumentFile：该模块未引入此依赖，当前也不允许新增依赖，
 * 因此直接使用框架的 [DocumentsContract]（DocumentFile 内部同样基于它，行为等价）。
 * 后续若引入该依赖，可将实现替换为 DocumentFile，对外签名无需改动。
 *
 * **主会话接线**（「URI 分支」的唯一入口）：
 * 1. `WorkSpaceStore.resolveRoot(context, space)` 返回 File 时按既有路径处理，行为不变；
 * 2. 返回 null 且 `space.treeUri != null`，则通过 `WorkSpaceStore.openTreeRoot(context, space)` 获取
 *    [TreeRoot]，再使用 [TreeRoot.readText] / [TreeRoot.writeText] / [TreeRoot.listDirs] 读写；
 * 3. 两者均无法获取（用户目录已删除或授权已撤销）：提示用户重新选择目录，**不得**静默退回私有目录。
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
