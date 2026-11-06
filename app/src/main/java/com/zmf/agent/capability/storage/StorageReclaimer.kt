package com.zmf.agent.capability.storage

import android.content.Context
import com.zmf.agent.capability.config.ApiConfig
import com.zmf.agent.capability.permission.PermissionStore
import com.zmf.agent.capability.runtime.ChatBridge
import com.zmf.agent.capability.tools.file.TrashStore
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.SessionPreference
import com.zmf.agent.core.model.wireName
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.Reclaim
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.Storage
import com.zmf.agent.core.store.StorageKind
import com.zmf.agent.core.store.StorageStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
import java.io.File

/**
 * 内置目录的回收与恢复。
 *
 * 分层缘由：判定（引用是否归零、目录空不空）属于数据层，见 [StorageStore.decide]；
 * 动作要碰回收站，而回收站位于 capability，因此「执行」放在这里。
 * 控制器与助手工具都经由本对象触发，保证两条入口的行为一致。
 */
object StorageReclaimer {

    private const val TAG = "storage"

    /** 回收结果；[message] 为面向用户的一句话，[TRASHED] 与 [DELETED_EMPTY] 需要提示。 */
    enum class Result { NONE, DROPPED, DELETED_EMPTY, TRASHED }

    data class Outcome(val result: Result, val message: String = "") {
        /** 是否真的动了目录（用于决定要不要给用户一句提示）。 */
        val acted: Boolean get() = result != Result.NONE
    }

    /** 恢复结果：成功时带回新建的工作区，失败时带回原因。 */
    data class RestoreOutcome(val workspace: WorkSpace?, val message: String)

    /**
     * 引用归零后执行回收判定与动作。
     *
     * [workspaceName] 用于回收站条目的显示名与恢复时的候选名称：调用方在删除工作区时仍持有该名称，
     * 而引用归零后记录本身已经不在了。传 null 时退回存储单元的目录名。
     */
    fun reclaim(
        context: Context,
        storageId: String,
        workspaceName: String? = null,
        workspaces: List<WorkSpace>? = null,
        sessions: List<ChatSession>? = null
    ): Outcome {
        if (storageId.isEmpty() || storageId == StorageStore.DEFAULT_ID) return Outcome(Result.NONE)
        val spaces = workspaces ?: StorageStore.loadWorkspaces(context)
        val list = sessions ?: SessionStore.load(context)
        val refs = StorageStore.refs(spaces, list, storageId)
        if (!refs.zero) return Outcome(Result.NONE)
        val storage = StorageStore.find(context, storageId) ?: return Outcome(Result.NONE)

        return when (val plan = StorageStore.decide(context, storage)) {
            is Reclaim.None -> Outcome(Result.NONE)

            is Reclaim.DropRecord -> {
                dropRecord(context, storage)
                FileLog.log(TAG, "存储单元目录已不存在，删除记录：" + storageId)
                Outcome(Result.DROPPED)
            }

            is Reclaim.DeleteEmpty -> {
                val removed = plan.dir.deleteRecursively()
                dropRecord(context, storage)
                FileLog.log(TAG, "内置目录为空，已直接删除：" + plan.dir.absolutePath + "（" + removed + "）")
                Outcome(Result.DELETED_EMPTY, "内置目录为空，已直接移除")
            }

            is Reclaim.ToTrash -> {
                val name = workspaceName?.takeIf { it.isNotBlank() }
                    ?: storage.dirName.ifEmpty { storageId }
                val entry = TrashStore.moveWorkspaceInto(context, plan.dir, name)
                if (entry == null) {
                    // 搬不进回收站就保留原目录：宁可多留一个目录，也不能先删后记账
                    FileLog.log(TAG, "内置目录移入回收站失败，保留原目录：" + plan.dir.absolutePath)
                    return Outcome(Result.NONE)
                }
                dropRecord(context, storage)
                FileLog.log(TAG, "内置目录已移入回收站：" + plan.dir.absolutePath + " → " + entry.dir.absolutePath)
                Outcome(Result.TRASHED, "原内置目录已移入回收站，保留 30 天")
            }
        }
    }

    /**
     * 回收站里的内置目录条目：恢复目录并重建工作区。
     *
     * 口径：不提供重命名，直接使用被删除工作区的原名称；重名追加「（1）」「（2）」；
     * 恢复后该工作区没有任何会话，因此自动创建一条**可见**会话（空白态，首轮消息后转正）。
     */
    fun restoreWorkspace(context: Context, entry: TrashStore.Entry): RestoreOutcome {
        if (!entry.isWorkspace) return RestoreOutcome(null, "这条记录不是内置目录")
        val existing = StorageStore.loadWorkspaces(context)
        val baseName = entry.name.ifBlank { "工作区" }
        val name = uniqueName(baseName, existing.map { it.name }.toSet())
        val dirName = freeDirName(context, name)
        val target = File(StorageStore.workspacesRoot(context), dirName)

        val outcome = TrashStore.restoreTo(entry, target)
        if (!outcome.ok) return RestoreOutcome(null, "恢复失败，当前目录不可写。")

        val now = System.currentTimeMillis()
        val storage = Storage(
            id = StorageStore.nextId(now),
            kind = StorageKind.PRIVATE,
            dirName = dirName,
            createdAt = now
        )
        val space = WorkSpace(
            id = StorageStore.nextId(now + 1),
            name = name,
            storageId = storage.id,
            createdAt = now
        )
        val storages = StorageStore.loadStorages(context) + storage
        StorageStore.saveRegistry(
            context,
            StorageStore.Registry(existing + space, storages)
        )

        // 恢复后的工作区没有任何会话：自动创建一条可见的空白会话，界面不会呈现空分组
        val session = SessionStore.newSession(
            storageId = storage.id,
            blank = true,
            listed = true,
            permissionPreset = PermissionStore.load(context).wireName(),
            reasoningEffort = currentEffort(context)
        ).copy(workspaceId = space.id)
        SessionStore.save(context, listOf(session) + SessionStore.load(context))

        ChatBridge.notifySessionsChanged()
        FileLog.log(TAG, "恢复内置目录并重建工作区 " + name + "（" + space.id + "）")
        val message = if (name != baseName) {
            "工作区「" + baseName + "」已存在，已恢复为「" + name + "」。"
        } else {
            "已恢复工作区「" + name + "」。"
        }
        return RestoreOutcome(space, message)
    }

    /** 删除存储单元记录（目录已不存在或已交给回收站）。 */
    private fun dropRecord(context: Context, storage: Storage) {
        val storages = StorageStore.loadStorages(context).filterNot { it.id == storage.id }
        StorageStore.saveStorages(context, storages)
    }

    /** 全局思考强度的当前落盘名：关闭时即为 `off`。 */
    private fun currentEffort(context: Context): String =
        if (!ApiConfig.loadThinkingEnabled(context)) {
            SessionPreference.EFFORT_OFF
        } else {
            SessionPreference.effortOrNull(ApiConfig.loadThinkingEffort(context))
                ?: SessionPreference.EFFORT_DEFAULT
        }

    /** 重名追加「（1）」「（2）」……，与文案口径一致。 */
    private fun uniqueName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var index = 1
        while (true) {
            val candidate = base + "（" + index + "）"
            if (candidate !in taken) return candidate
            index++
        }
    }

    /** 目录名只允许 ASCII：名称里通常没有可用字符，因此以时间戳兜底；已存在则继续加后缀。 */
    private fun freeDirName(context: Context, name: String): String {
        val slug = name.replace(Regex("[^a-zA-Z0-9_-]"), "").take(24)
        var candidate = slug.ifEmpty { "restored-" + System.currentTimeMillis() }
        var index = 1
        while (File(StorageStore.workspacesRoot(context), candidate).exists()) {
            candidate = (slug.ifEmpty { "restored" }) + "-" + index
            index++
        }
        return candidate
    }
}
