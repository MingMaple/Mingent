package com.zmf.agent.core.store

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 旧数据迁移：把「工作区自带目录字段」的旧格式升级为「工作区 + 存储单元」。
 *
 * 三条约束：
 * 1. **只增不改**：直接在原 JSON 上补键（`storages`、`storageId`、权限与强度），
 *    不经 [ChatSession] 往返，避免读盘期的补齐逻辑被写回、改变原有语义；
 * 2. **幂等**：`storages` 已存在即跳过目录推导，会话缺键才补键，重复执行无副作用；
 * 3. **可回滚**：首次改写前把两个原文件复制到 `appdata/migration-backup-<时间戳>/`，
 *    失败不覆盖原文件（先写临时文件再原子替换）。
 *
 * 旧会话的权限与强度一次性写入**迁移当时**的全局值，保证快照语义自洽；
 * 不采用「缺省即跟随全局」，否则用户之后修改全局设置会悄悄改变历史会话的行为。
 */
object StorageMigration {

    private const val TAG = "migrate"
    private const val PREFS = "migration_prefs"
    private const val KEY_DONE = "storage_v1_done"
    private const val WORKSPACE_FILE = "workspaces.json"
    private const val SESSION_FILE = "sessions.json"

    /**
     * 执行迁移。
     *
     * @param permissionWire 迁移当时的全局权限档落盘名，写入仍缺该键的旧会话；
     * @param effortWire 迁移当时的全局思考强度，写入仍缺该键的旧会话。
     * @return 是否发生了写盘。
     */
    @Synchronized
    fun migrate(context: Context, permissionWire: String, effortWire: String): Boolean {
        // 一次性标记：迁移成功后不再解析会话文件，避免每次冷启动都多读一份可能很大的 JSON。
        // 导入旧备份后标记仍为 true，由 SessionController.loadInitial 的缺键兜底补齐，不影响正确性
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return false

        val workspaceFile = AppPaths.dataFile(context, WORKSPACE_FILE)
        val sessionFile = AppPaths.dataFile(context, SESSION_FILE)
        val workspaceText = readText(workspaceFile)
        val sessionText = readText(sessionFile)
        if (workspaceText == null && sessionText == null) {
            // 全新安装：没有可迁移的内容，直接落标记
            prefs.edit().putBoolean(KEY_DONE, true).apply()
            return false
        }

        var backupDir: File? = null
        var wrote = false

        if (workspaceText != null) {
            val root = runCatching { JSONObject(workspaceText) }.getOrNull()
            if (root != null && needsStorageDerivation(root)) {
                backupDir = backupOnce(context, backupDir, workspaceFile, sessionFile)
                if (backupDir == null) {
                    FileLog.log(TAG, "迁移前备份不可用，本次不写盘，下次启动重试")
                    return false
                }
                if (deriveStorages(context, root)) {
                    wrote = write(workspaceFile, root.toString()) || wrote
                }
            }
        }

        if (sessionText != null) {
            val root = runCatching { JSONObject(sessionText) }.getOrNull()
            if (root != null) {
                val storages = StorageStore.loadStorages(context)
                val byWorkspace = StorageStore.loadWorkspaces(context).associate { it.id to it.storageId }
                if (fillSessions(root, storages, byWorkspace, permissionWire, effortWire)) {
                    backupDir = backupOnce(context, backupDir, workspaceFile, sessionFile)
                    if (backupDir == null) {
                        FileLog.log(TAG, "迁移前备份不可用，本次不写盘，下次启动重试")
                        return false
                    }
                    wrote = write(sessionFile, root.toString()) || wrote
                }
            }
        }

        if (wrote) {
            FileLog.log(TAG, "旧数据迁移完成，原文件备份于 " + (backupDir?.absolutePath ?: "（无）"))
        }
        prefs.edit().putBoolean(KEY_DONE, true).apply()
        return wrote
    }

    // ---- 目录推导 ----

    /** 尚未写入 `storages` 的旧文件才需要推导。 */
    private fun needsStorageDerivation(root: JSONObject): Boolean {
        val array = root.optJSONArray(KEY_STORAGES) ?: return true
        return array.length() == 0
    }

    /**
     * 按旧工作区的目录形态生成存储单元，并给每个工作区写入 `storageId`。
     *
     * 三种形态：默认工作区（`id == default`）→ 默认存储单元；带 `rootPath` / `treeUri` → 外部存储单元
     * （同一规范化路径只生成一个）；其余 → 内置存储单元，沿用原 `dirName` 作为目录名。
     */
    private fun deriveStorages(context: Context, root: JSONObject): Boolean {
        val array = root.optJSONArray(KEY_WORKSPACES) ?: return false
        val storages = JSONArray()
        storages.put(storageJson(StorageStore.defaultStorage(context)))
        val byKey = HashMap<String, String>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString(KEY_ID, "")
            if (id.isEmpty()) continue
            val createdAt = obj.optLong(KEY_CREATED_AT, 0L)
            val dirName = obj.optString(KEY_DIR, "").replace(Regex("[^a-zA-Z0-9_-]"), "")
            val rootPath = obj.optString(KEY_ROOT_PATH, "").ifBlank { null }
            val treeUri = obj.optString(KEY_TREE_URI, "").ifBlank { null }

            val storageId = when {
                // 默认工作区：目录固定为应用私有的 workspace，永不回收
                id == WorkSpaceStore.DEFAULT_ID -> StorageStore.DEFAULT_ID
                rootPath == null && treeUri == null && dirName.isEmpty() -> StorageStore.DEFAULT_ID
                rootPath != null || treeUri != null -> {
                    val key = rootPath?.let { StorageStore.canonicalPath(File(it)) } ?: treeUri!!
                    byKey.getOrPut(key) {
                        val storage = Storage(
                            id = StorageStore.nextId(createdAt),
                            kind = StorageKind.EXTERNAL,
                            rootPath = rootPath,
                            treeUri = treeUri,
                            createdAt = createdAt
                        )
                        storages.put(storageJson(storage))
                        storage.id
                    }
                }
                else -> {
                    val storage = Storage(
                        id = StorageStore.nextId(createdAt),
                        kind = StorageKind.PRIVATE,
                        dirName = dirName.ifEmpty { id },
                        createdAt = createdAt
                    )
                    storages.put(storageJson(storage))
                    storage.id
                }
            }
            obj.put(KEY_STORAGE_ID, storageId)
        }

        root.put(KEY_STORAGES, storages)
        FileLog.log(TAG, "由旧工作区推导出 " + storages.length() + " 个存储单元")
        return true
    }

    // ---- 会话补键 ----

    /**
     * 给旧会话补 `storageId` / `permissionPreset` / `reasoningEffort`。
     *
     * 存储单元：按所属工作区反查；未分类或工作区已不存在时指向默认存储单元。
     * 权限与强度：只补缺失的键，已有值一律保留，保证重复执行不覆盖用户此后的修改。
     */
    private fun fillSessions(
        root: JSONObject,
        storages: List<Storage>,
        byWorkspace: Map<String, String>,
        permissionWire: String,
        effortWire: String
    ): Boolean {
        val array = root.optJSONArray(KEY_SESSIONS) ?: return false
        val known = storages.map { it.id }.toSet()
        var changed = false
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val workspaceId = obj.optString(KEY_WORKSPACE_ID, "").ifBlank { null }
            val current = obj.optString(KEY_STORAGE_ID, "")
            if (current.isEmpty() || current !in known) {
                val target = byWorkspace[workspaceId] ?: StorageStore.DEFAULT_ID
                obj.put(KEY_STORAGE_ID, target)
                changed = true
            }
            if (obj.optString(KEY_PERMISSION, "").isEmpty()) {
                obj.put(KEY_PERMISSION, permissionWire)
                changed = true
            }
            if (obj.optString(KEY_EFFORT, "").isEmpty()) {
                obj.put(KEY_EFFORT, effortWire)
                changed = true
            }
        }
        return changed
    }

    // ---- 写盘与备份 ----

    /** 首次改写前备份一次；返回备份目录，后续调用直接复用。 */
    private fun backupOnce(appContext: Context, existing: File?, vararg files: File): File? {
        if (existing != null) return existing
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(AppPaths.dataDir(appContext), "migration-backup-" + stamp)
        if (!dir.exists() && !dir.mkdirs()) {
            FileLog.log(TAG, "备份目录创建失败，本次不写盘（下次启动重试）")
            return null
        }
        for (file in files) {
            if (!file.exists() || file.length() == 0L) continue
            runCatching { file.copyTo(File(dir, file.name), overwrite = true) }
                .onFailure { FileLog.log(TAG, "备份 " + file.name + " 失败：" + it.message) }
        }
        FileLog.log(TAG, "迁移前备份：" + dir.absolutePath)
        return dir
    }

    private fun write(target: File, text: String): Boolean = try {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (temp.renameTo(target)) {
            true
        } else {
            target.writeText(text, Charsets.UTF_8)
            temp.delete()
            true
        }
    } catch (e: Exception) {
        FileLog.log(TAG, "写入 " + target.name + " 失败，原文件保持不动：" + e)
        false
    }

    private fun readText(file: File): String? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { file.readText(Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun storageJson(storage: Storage): JSONObject = JSONObject().apply {
        put(KEY_ID, storage.id)
        put("kind", storage.kind.wire)
        if (storage.dirName.isNotEmpty()) put(KEY_DIR, storage.dirName)
        storage.rootPath?.takeIf { it.isNotBlank() }?.let { put(KEY_ROOT_PATH, it) }
        storage.treeUri?.takeIf { it.isNotBlank() }?.let { put(KEY_TREE_URI, it) }
        if (storage.createdAt != 0L) put(KEY_CREATED_AT, storage.createdAt)
    }

    private const val KEY_WORKSPACES = "workspaces"
    private const val KEY_STORAGES = "storages"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_ID = "id"
    private const val KEY_DIR = "dirName"
    private const val KEY_ROOT_PATH = "rootPath"
    private const val KEY_TREE_URI = "treeUri"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_STORAGE_ID = "storageId"
    private const val KEY_WORKSPACE_ID = "workspaceId"
    private const val KEY_PERMISSION = "permissionPreset"
    private const val KEY_EFFORT = "reasoningEffort"
}
