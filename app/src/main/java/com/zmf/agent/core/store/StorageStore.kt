package com.zmf.agent.core.store

import android.content.Context
import android.net.Uri
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.core.log.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 存储单元的类型。
 *
 * - [DEFAULT]：应用私有的 `workspace` 目录，全库唯一，不可删除、不可改路径，永不回收；
 * - [PRIVATE]：应用私有的 `workspaces/<dirName>`，引用归零时参与回收；
 * - [EXTERNAL]：用户目录（绝对路径或 SAF 授权目录），只记录映射，永不移动、永不回收。
 */
enum class StorageKind(val wire: String) {
    DEFAULT("default"),
    PRIVATE("private"),
    EXTERNAL("external");

    companion object {
        /** 认识三种取值；遇到损坏数据一律按 [PRIVATE] 处理，避免整条记录被丢弃。 */
        fun of(raw: String?): StorageKind = when (raw?.trim()?.lowercase()) {
            "default" -> DEFAULT
            "external" -> EXTERNAL
            else -> PRIVATE
        }
    }
}

/**
 * 存储单元：一个可被工作区与会话同时引用的目录。
 *
 * 工作区只持有它的 id，会话也只持有 id：目录本身与分组彻底解耦，
 * 因此移动会话、删除工作区都不需要搬运文件，也不会留下两份需要同步的路径。
 */
data class Storage(
    val id: String,
    /** 类型，决定是否参与回收以及路径如何解析。 */
    val kind: StorageKind,
    /** 仅 [StorageKind.PRIVATE] 使用：创建时确定，之后不变。 */
    val dirName: String = "",
    /** 绝对路径；[StorageKind.EXTERNAL] 使用（与 [treeUri] 至少有一个）。 */
    val rootPath: String? = null,
    /** SAF 授权目录；存在时优先于 [rootPath]。 */
    val treeUri: String? = null,
    val createdAt: Long = 0L
) {
    /** 这个存储单元是否参与回收判定。 */
    val reclaimable: Boolean get() = kind == StorageKind.PRIVATE
}

/** 引用统计：映射到该存储单元的工作区（至多一个）与会话条数（含归档、含空白）。 */
data class StorageRefs(val workspace: WorkSpace?, val sessions: Int) {

    val total: Int get() = (if (workspace != null) 1 else 0) + sessions

    /** 引用归零：只有此时才可能进入回收判定。 */
    val zero: Boolean get() = workspace == null && sessions == 0
}

/** 目录规模：空目录直接删除，非空目录进回收站；[truncated] 表示触到了扫描上限。 */
data class DirStats(val files: Int, val dirs: Int, val bytes: Long, val truncated: Boolean = false)

/** 回收判定的结果。判定只做判断，不执行任何文件操作。 */
sealed interface Reclaim {

    /** 不处理：仍有引用，或类型不参与回收，或目录确实不存在且无需留痕。 */
    object None : Reclaim

    /** 目录已不存在：只把存储单元记录删掉，不进回收站。 */
    data class DropRecord(val storage: Storage) : Reclaim

    /** 目录为空（文件数为 0，含隐藏文件；空子目录不算文件）：直接删除，不占回收站。 */
    data class DeleteEmpty(val storage: Storage, val dir: File) : Reclaim

    /** 目录内有文件：移入回收站保留 30 天。 */
    data class ToTrash(val storage: Storage, val dir: File, val stats: DirStats) : Reclaim
}

/**
 * 存储单元落盘与解析。
 *
 * 存放位置沿用 `appdata/workspaces.json`：同一个文件同时保存 `workspaces` 与 `storages`
 * 两个数组，一次原子替换即可保证「工作区指向的存储单元一定存在」，不会出现跨文件撕裂。
 * 本对象是该文件的**唯一**写入方，[WorkSpaceStore] 的读写经由这里转出。
 */
object StorageStore {

    private const val TAG = "storage"

    private const val FILE_NAME = "workspaces.json"

    private const val KEY_WORKSPACES = "workspaces"
    private const val KEY_STORAGES = "storages"

    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_KIND = "kind"
    private const val KEY_DIR = "dirName"
    private const val KEY_ROOT_PATH = "rootPath"
    private const val KEY_TREE_URI = "treeUri"
    private const val KEY_CREATED_AT = "createdAt"

    /** 默认存储单元的固定 id。 */
    const val DEFAULT_ID = "default"

    private const val DEFAULT_NAME = "默认工作区"

    /** 自建工作区目录都放在这里。 */
    private const val WORKSPACES_DIR = "workspaces"

    /** 统计上限：只用于判断「空 / 非空」与给出近似大小，不为精确值遍历整棵树。 */
    private const val MAX_SCAN_ENTRIES = 200_000

    /** 上一次发出的 id，保证同一毫秒内连续创建不会重号。 */
    @Volatile
    private var lastIssuedId: Long = 0L

    /** 文件内容的完整快照。 */
    data class Registry(val workspaces: List<WorkSpace>, val storages: List<Storage>)

    // ---- 落盘 ----

    private fun storeFile(context: Context): File = com.zmf.agent.core.AppPaths.dataFile(context, FILE_NAME)

    /**
     * 读整个文件。
     *
     * 文件不存在、内容损坏或为空时返回「默认工作区 + 默认存储单元」这一最小可用组合，
     * 保证界面至少有一个可用的工作区；`storages` 缺失（旧版本文件）时按工作区派生一份内存视图，
     * 真正的落盘迁移由 [StorageMigration] 完成。
     */
    @Synchronized
    fun loadRegistry(context: Context): Registry {
        val file = storeFile(context)
        if (!file.exists() || file.length() == 0L) return freshRegistry(context)
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            FileLog.log(TAG, "读取工作区与存储单元失败：" + e)
            return freshRegistry(context)
        }
        if (text.isBlank()) return freshRegistry(context)
        return try {
            val root = JSONObject(text)
            val workspaces = parseWorkspaces(root.optJSONArray(KEY_WORKSPACES))
            val storages = parseStorages(root.optJSONArray(KEY_STORAGES))
            Registry(workspaces, ensureDefault(storages))
        } catch (e: Exception) {
            FileLog.log(TAG, "解析工作区与存储单元失败：" + e)
            freshRegistry(context)
        }
    }

    /** 整份写入：先写临时文件再改名，失败只记日志。 */
    @Synchronized
    fun saveRegistry(context: Context, registry: Registry) {
        try {
            val root = JSONObject()
            val workspaces = JSONArray()
            for (space in registry.workspaces) workspaces.put(workspaceToJson(space))
            root.put(KEY_WORKSPACES, workspaces)
            val storages = JSONArray()
            for (storage in ensureDefault(registry.storages)) storages.put(storageToJson(storage))
            root.put(KEY_STORAGES, storages)
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
            FileLog.log(TAG, "保存工作区与存储单元失败：" + e)
        }
    }

    /** 只读工作区表。 */
    fun loadWorkspaces(context: Context): List<WorkSpace> = loadRegistry(context).workspaces

    /** 只写工作区表，存储单元保持原样（读改写，避免覆盖对方）。 */
    @Synchronized
    fun saveWorkspaces(context: Context, workspaces: List<WorkSpace>) {
        val storages = loadRegistry(context).storages
        saveRegistry(context, Registry(workspaces, storages))
    }

    /** 只读存储单元表。 */
    fun loadStorages(context: Context): List<Storage> = loadRegistry(context).storages

    /** 只写存储单元表，工作区保持原样。 */
    @Synchronized
    fun saveStorages(context: Context, storages: List<Storage>) {
        val workspaces = loadRegistry(context).workspaces
        saveRegistry(context, Registry(workspaces, storages))
    }

    /** 按 id 取存储单元；找不到返回 null。 */
    fun find(context: Context, id: String?): Storage? =
        id?.takeIf { it.isNotEmpty() }?.let { key -> loadStorages(context).firstOrNull { it.id == key } }

    /** 默认存储单元。 */
    fun default(context: Context): Storage = defaultStorage(context)

    /** 保证默认存储单元始终在列表中。 */
    fun ensureDefault(storages: List<Storage>): List<Storage> =
        if (storages.any { it.id == DEFAULT_ID }) storages
        else listOf(defaultStorage(null)) + storages

    // ---- 路径 ----

    /** 外部私有目录；极少数机器拿不到外部目录时退回内部目录，行为与 PathGate 一致。 */
    private fun externalBase(context: Context): File =
        context.applicationContext.getExternalFilesDir(null)
            ?: context.applicationContext.filesDir

    /** 自建目录的父目录。 */
    fun workspacesRoot(context: Context): File = File(externalBase(context), WORKSPACES_DIR)

    /**
     * 存储单元对应的目录，**不产生任何副作用**。
     *
     * SAF 目录（[Storage.treeUri] 非空）无法用 File 表示时返回 null，调用方应改用 [openTreeRoot]；
     * 外部目录被删除或移动后同样返回该路径（由调用方判断是否存在），不静默改成私有目录。
     */
    fun rootFile(context: Context, storage: Storage): File? = when (storage.kind) {
        StorageKind.DEFAULT -> File(externalBase(context), "workspace")
        StorageKind.PRIVATE -> File(workspacesRoot(context), storage.dirName.ifEmpty { storage.id })
        StorageKind.EXTERNAL -> {
            val tree = storage.treeUri
            val path = storage.rootPath
            if (!tree.isNullOrBlank()) null
            else path?.takeIf { it.isNotBlank() }?.let { File(it) }
        }
    }

    /**
     * 取存储单元的运行根，必要时创建目录（仅默认与内置目录）。
     *
     * 返回 null 表示外部目录已不可用（被删除、不是目录、SAF 授权失效），
     * 调用方必须提示用户重新选择，**不得**退回私有目录。
     */
    fun ensureRoot(context: Context, storage: Storage): File? = when (storage.kind) {
        StorageKind.DEFAULT -> PathGate.workspaceRoot(context)
        StorageKind.PRIVATE -> {
            val dir = File(workspacesRoot(context), storage.dirName.ifEmpty { storage.id })
            if (!dir.exists()) dir.mkdirs()
            dir
        }
        StorageKind.EXTERNAL -> {
            if (!storage.treeUri.isNullOrBlank()) return null
            val path = storage.rootPath?.takeIf { it.isNotBlank() } ?: return null
            val dir = File(path)
            if (dir.isDirectory) dir else null
        }
    }

    /** 这条 tree URI 是否仍持有持久读授权。 */
    fun hasTreePermission(context: Context, treeUri: String): Boolean {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return false
        return try {
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        } catch (e: Exception) {
            FileLog.log(TAG, "查 SAF 授权失败：" + e)
            false
        }
    }

    /** SAF 入口；授权失效或不是 SAF 目录时返回 null。 */
    fun openTreeRoot(context: Context, storage: Storage): TreeRoot? {
        val raw = storage.treeUri
        if (raw.isNullOrBlank()) return null
        if (!hasTreePermission(context, raw)) {
            FileLog.log(TAG, "SAF 授权已失效：" + raw)
            return null
        }
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return TreeRoot(context, uri)
    }

    /**
     * 该存储单元对应的读写实现。
     *
     * 返回 null 表示目录已不可用：调用方应提示用户重新选择目录，不得退回私有目录。
     */
    fun ioFor(context: Context, storage: Storage): WorkspaceIo? {
        if (!storage.treeUri.isNullOrBlank()) {
            val tree = openTreeRoot(context, storage) ?: return null
            // 相对路径由私有兜底目录反推：SafWorkspaceIo 依赖它把工具传来的相对路径翻成 URI 操作
            val fallback = File(workspacesRoot(context), storage.dirName.ifEmpty { storage.id })
            return SafWorkspaceIo(tree, storage.rootPath, fallback)
        }
        val root = ensureRoot(context, storage) ?: return null
        return FileWorkspaceIo(root)
    }

    /** 按规范化路径反查存储单元；同一目录只对应一个存储单元。 */
    fun findByPath(context: Context, root: File): Storage? =
        findByCanonical(context, canonicalPath(root))

    /** 按规范化路径反查存储单元。 */
    fun findByCanonical(context: Context, path: String): Storage? {
        if (path.isEmpty()) return null
        return loadStorages(context).firstOrNull { storage ->
            val dir = rootFile(context, storage) ?: return@firstOrNull false
            canonicalPath(dir) == path
        }
    }

    // ---- 创建 ----

    /** 默认存储单元：全库唯一，目录沿用既有的 `externalFilesDir/workspace`。 */
    fun defaultStorage(@Suppress("UNUSED_PARAMETER") context: Context?): Storage =
        Storage(id = DEFAULT_ID, kind = StorageKind.DEFAULT, createdAt = 0L)

    /** 新建内置存储单元：先建目录，目录名在创建时确定（仅 ASCII，便于用 adb 识别）。 */
    fun createPrivate(context: Context, name: String): Storage {
        val now = System.currentTimeMillis()
        val id = nextId(now)
        val dirName = privateDirName(context, id, name)
        val dir = File(workspacesRoot(context), dirName)
        if (!dir.exists()) dir.mkdirs()
        FileLog.log(TAG, "新建内置目录 " + name + "（id=" + id + "，目录=" + dir.absolutePath + "）")
        return Storage(id = id, kind = StorageKind.PRIVATE, dirName = dirName, createdAt = now)
    }

    /**
     * 新建外部存储单元：用户目录必须已存在、是目录且可写；SAF 目录必须仍持有持久授权。
     *
     * 一律不替用户创建目录，也不推测路径；校验失败返回 null 由界面提示。
     */
    fun createExternal(context: Context, rootPath: String?, treeUri: String?): Storage? {
        val path = rootPath?.trim()?.takeIf { it.isNotEmpty() }
        val tree = treeUri?.trim()?.takeIf { it.isNotEmpty() }
        if (path == null && tree == null) return null
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
            // 同一目录只对应一个存储单元：已有则直接复用，避免同一路径出现两份记录
            findByCanonical(context, canonicalPath(dir))?.let { return it }
        }
        if (tree != null) {
            if (!hasTreePermission(context, tree)) {
                FileLog.log(TAG, "SAF 目录没有持久授权：" + tree)
                return null
            }
            loadStorages(context).firstOrNull { it.treeUri == tree }?.let { return it }
        }
        val now = System.currentTimeMillis()
        return Storage(
            id = nextId(now),
            kind = StorageKind.EXTERNAL,
            rootPath = path,
            treeUri = tree,
            createdAt = now
        )
    }

    // ---- 引用与回收 ----

    /**
     * 统计引用者：映射到该存储单元的工作区至多一个，会话条数包含已归档与空白会话。
     *
     * 归档不减少引用：已归档会话仍可恢复，恢复后需要原目录；删除工作区只是把
     * `workspaceId` 置空，会话本身仍指向该存储单元。
     */
    fun refs(workspaces: List<WorkSpace>, sessions: List<ChatSession>, storageId: String): StorageRefs {
        val workspace = workspaces.firstOrNull { it.storageId == storageId }
        val count = sessions.count { it.storageId == storageId }
        return StorageRefs(workspace, count)
    }

    /**
     * 回收判定：只在引用归零后调用。
     *
     * 三态与设计稿一致——目录不存在则不留记录；文件数为 0（空子目录不算）直接删除；
     * 否则交回收站保留 30 天。默认与外部目录永不回收。
     */
    fun decide(context: Context, storage: Storage): Reclaim {
        if (!storage.reclaimable) return Reclaim.None
        val dir = rootFile(context, storage) ?: return Reclaim.None
        if (!dir.exists()) return Reclaim.DropRecord(storage)
        val stats = scan(dir)
        return if (stats.files == 0) Reclaim.DeleteEmpty(storage, dir) else Reclaim.ToTrash(storage, dir, stats)
    }

    /**
     * 递归统计：文件数含隐藏文件，空子目录不计入文件数；不跟随软链接（按文件计一次）。
     * 达到上限即返回，[DirStats.truncated] 标记数字只是下限。
     */
    fun scan(dir: File): DirStats {
        var files = 0
        var dirs = 0
        var bytes = 0L
        var seen = 0
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (++seen > MAX_SCAN_ENTRIES) return DirStats(files, dirs, bytes, truncated = true)
            val children = current.listFiles()
            if (children == null) {
                if (current !== dir) {
                    files++
                    bytes += runCatching { current.length() }.getOrDefault(0L)
                }
                continue
            }
            dirs++
            for (child in children) {
                if (child.isDirectory && !PathGate.isSymlink(child)) {
                    stack.addLast(child)
                } else {
                    files++
                    bytes += runCatching { child.length() }.getOrDefault(0L)
                }
            }
        }
        return DirStats(files, dirs, bytes)
    }

    // ---- 序列化 ----

    private fun freshRegistry(context: Context): Registry =
        Registry(listOf(defaultWorkspace()), listOf(defaultStorage(context)))

    /** 工作区记录的默认形态：默认工作区绑定默认存储单元。 */
    fun defaultWorkspace(): WorkSpace =
        WorkSpace(id = WorkSpaceStore.DEFAULT_ID, name = DEFAULT_NAME, storageId = DEFAULT_ID, createdAt = 0L)

    private fun workspaceToJson(space: WorkSpace): JSONObject = JSONObject().apply {
        put(KEY_ID, space.id)
        put(KEY_NAME, space.name)
        put(KEY_CREATED_AT, space.createdAt)
        // 默认工作区不写 storageId，读取时按常量兜底，旧文件与新文件都只在必要时多一个键
        if (space.storageId != DEFAULT_ID) put("storageId", space.storageId)
    }

    private fun storageToJson(storage: Storage): JSONObject = JSONObject().apply {
        put(KEY_ID, storage.id)
        put(KEY_KIND, storage.kind.wire)
        if (storage.dirName.isNotEmpty()) put(KEY_DIR, storage.dirName)
        storage.rootPath?.takeIf { it.isNotBlank() }?.let { put(KEY_ROOT_PATH, it) }
        storage.treeUri?.takeIf { it.isNotBlank() }?.let { put(KEY_TREE_URI, it) }
        if (storage.createdAt != 0L) put(KEY_CREATED_AT, storage.createdAt)
    }

    private fun parseWorkspaces(array: JSONArray?): List<WorkSpace> {
        if (array == null) return emptyList()
        val list = ArrayList<WorkSpace>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString(KEY_ID, "")
            // id 是唯一标识，缺失时无法切换或删除，直接丢弃
            if (id.isEmpty()) continue
            val storageId = obj.optString("storageId", "").ifBlank {
                // 旧文件没有该键：默认工作区固定指向默认存储单元，其余留空由迁移补齐
                if (id == WorkSpaceStore.DEFAULT_ID) DEFAULT_ID else ""
            }
            list.add(
                WorkSpace(
                    id = id,
                    name = obj.optString(KEY_NAME, "").ifEmpty { id },
                    storageId = storageId,
                    createdAt = obj.optLong(KEY_CREATED_AT, 0L)
                )
            )
        }
        return list
    }

    private fun parseStorages(array: JSONArray?): List<Storage> {
        if (array == null) return emptyList()
        val list = ArrayList<Storage>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString(KEY_ID, "")
            if (id.isEmpty()) continue
            list.add(
                Storage(
                    id = id,
                    kind = if (id == DEFAULT_ID) StorageKind.DEFAULT else StorageKind.of(obj.optString(KEY_KIND, "")),
                    dirName = obj.optString(KEY_DIR, "").replace(Regex("[^a-zA-Z0-9_-]"), ""),
                    rootPath = obj.optString(KEY_ROOT_PATH, "").ifBlank { null },
                    treeUri = obj.optString(KEY_TREE_URI, "").ifBlank { null },
                    createdAt = obj.optLong(KEY_CREATED_AT, 0L)
                )
            )
        }
        return list
    }

    // ---- 小工具 ----

    /** 规范化路径：折叠软链接、`..` 与尾斜杠，用于「同一目录只对应一个存储单元」的判断。 */
    fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }.trimEnd(File.separatorChar)

    /** 目录名仅允许 [a-zA-Z0-9_-]：名称可能含中文、空格或斜杠，不能直接作为目录名。 */
    private fun sanitizeDir(raw: String): String =
        raw.trim().replace(Regex("[^a-zA-Z0-9_-]"), "")

    /** 优先取名称中的 ASCII 片段，便于用 adb 识别；同名目录已存在时退回 id。 */
    private fun privateDirName(context: Context, id: String, name: String): String {
        val slug = sanitizeDir(name).take(24)
        val candidate = slug.ifEmpty { sanitizeDir(id) }
        return if (File(workspacesRoot(context), candidate).exists()) sanitizeDir(id) else candidate
    }

    /** 时间戳 id；同一毫秒内第二次创建时递增 1。 */
    fun nextId(now: Long = System.currentTimeMillis()): String {
        val value = if (now > lastIssuedId) now else lastIssuedId + 1
        lastIssuedId = value
        return value.toString()
    }
}
