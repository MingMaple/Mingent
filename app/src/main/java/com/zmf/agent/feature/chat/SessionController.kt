package com.zmf.agent.feature.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.zmf.agent.capability.context.ContextEventStore
import com.zmf.agent.capability.runtime.ScheduleBridge
import com.zmf.agent.capability.storage.StorageReclaimer
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.wireName
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.GoalStore
import com.zmf.agent.core.store.PlanStore
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.Storage
import com.zmf.agent.core.store.StorageKind
import com.zmf.agent.core.store.StorageStore
import com.zmf.agent.core.store.UploadStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.core.store.WorkspaceIo
import com.zmf.agent.core.store.workspaceIoForRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** 「用户最后停留的会话」的落盘位置。 */
private const val SESSION_PREFS = "session_prefs"
private const val KEY_OPENED_SESSION = "last_opened_session"

/**
 * 会话与工作区：会话表、当前会话、工作区表、当前工作区，以及它们的新建/切换/归档/删除与落盘。
 *
 * 目录不再挂在会话或工作区上：两者都只持有存储单元 id，运行根由存储单元解析，
 * 因此移动会话、删除工作区、更改储存路径都不需要搬运文件。
 *
 * 从 [ChatViewModel] 拆出，仅将跨模块动作改为注入——消息容器、流式标记、待发附件清理与引用路径、
 * 工具成败清理、待办/统计/错误的回显入口、导入后暂缓落盘的开关、草稿存取与当前默认偏好。
 */
class SessionController(
    private val appContext: Context,
    private val messages: SnapshotStateList<ChatMessage>,
    private val scope: CoroutineScope,
    private val isStreaming: () -> Boolean,
    private val clearAttachments: () -> Unit,
    private val pendingUploadPaths: () -> List<String>,
    private val clearToolOutcomes: () -> Unit,
    private val clearTodos: () -> Unit,
    private val reloadTodos: () -> Unit,
    private val resetStats: () -> Unit,
    private val reportError: (String) -> Unit,
    private val persistenceBlocked: () -> Boolean,
    /** 新建会话时快照进会话字段的当前权限档。 */
    private val currentPermission: () -> PermissionLevel,
    /** 新建会话时快照进会话字段的当前思考强度（`off` 表示关闭思考）。 */
    private val currentEffort: () -> String
) {

    /** 全部会话，按 updatedAt 降序（第一个就是最近用过的）；侧边栏直接用。 */
    var sessions by mutableStateOf<List<ChatSession>>(emptyList())

    /** 当前会话 id；空串只可能出现在 [loadInitial] 跑完之前。 */
    var currentSessionId by mutableStateOf("")

    /** 全部工作区（第一个永远是默认工作区）。 */
    var workspaces by mutableStateOf<List<WorkSpace>>(emptyList())

    /** 当前工作区；null = 未分类。 */
    var currentWorkspaceId by mutableStateOf<String?>(null)

    /**
     * 草稿存取入口，由 [ChatViewModel] 在输入区状态构造完成后注入。
     *
     * 用可空端口而不是构造参数：草稿状态属于输入区，输入区又依赖本控制器提供的会话 id，
     * 两者互相引用，用端口打断这一环，避免初始化顺序耦合。
     */
    var draftPort: DraftPort? = null

    /** 草稿端口：切换会话前先把当前输入落盘，切换后回填目标会话的草稿。 */
    interface DraftPort {
        /** 立即把当前输入写入当前会话。 */
        fun flush()

        /** 载入目标会话的草稿；没有草稿时清空输入框。 */
        fun restore(sessionId: String)

        /** 删除若干条草稿（会话被删除时调用）。 */
        fun forget(sessionIds: Collection<String>)
    }

    /**
     * 会话落盘队列。
     *
     * 此前 persistAll 在主线程整表写盘：点击会话需等待写完才能重绘，涟漪与选中态均延迟。
     * 现在状态先改、界面先动，写盘挪到 IO；连点排进来的多拍只保留最新一拍（旧快照是它的子集）。
     */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistGeneration = AtomicLong(0)

    /**
     * 启动时读回工作区表与会话表，决定当前会话并载入它的消息。
     *
     * 顺序需与原先一致：先工作区（决定当前工作区时以其兜底），再会话。
     * 三份表的读盘与 JSON 解析放在 IO 线程：sessions.json 含全部会话正文，会话越多越接近
     * 冷启动 ANR 窗口；状态写入仍回到调用方（主线程），保证快照状态只在主线程修改。
     */
    suspend fun loadInitial() {
        // 工作区表先读回来：默认工作区永远在，后面决定当前工作区时要用它兜底
        val snapshot = withContext(Dispatchers.IO) {
            InitialSnapshot(
                spaces = WorkSpaceStore.load(appContext),
                storageIds = StorageStore.loadStorages(appContext).map { it.id }.toSet(),
                sessions = SessionStore.load(appContext)
            )
        }
        val loadedSpaces = snapshot.spaces
        val validStorages = snapshot.storageIds
        workspaces = loadedSpaces.map { space ->
            // 防御：存储单元记录丢失时退回默认存储单元，避免出现「工作区指向不存在的目录」
            if (space.storageId.isEmpty() || space.storageId !in validStorages) {
                space.copy(storageId = StorageStore.DEFAULT_ID)
            } else {
                space
            }
        }
        // 再重新读取会话表以确定当前会话：存在历史时接续最近一条，否则新建空会话
        // 顺便兜底：会话引用了已不存在的工作区（workspaces.json 丢了/坏了）就当未分类，
        // 否则这些会话在界面分组里会直接消失
        val loaded = snapshot.sessions.sortedByDescending { it.updatedAt }
        val validIds = workspaces.map { space -> space.id }.toSet()
        var repaired = false
        val sessions = loaded.map { session ->
            val workspaceId = session.workspaceId
            var next = session
            if (workspaceId != null && !validIds.contains(workspaceId)) {
                next = next.copy(workspaceId = null)
                repaired = true
            }
            if (next.storageId.isEmpty()) {
                next = next.copy(storageId = storageIdFor(next.workspaceId))
                repaired = true
            }
            // 导入旧备份同样会留下缺键会话：此处按当前全局值补齐，保证快照语义自洽
            if (next.permissionPreset.isEmpty()) {
                next = next.copy(permissionPreset = currentPermission().wireName())
                repaired = true
            }
            if (next.reasoningEffort.isEmpty()) {
                next = next.copy(reasoningEffort = currentEffort())
                repaired = true
            }
            next
        }
        if (repaired) {
            this.sessions = sessions
            saveLoadedOnIo(sessions)
        }
        if (sessions.isEmpty()) {
            // 一条会话都没有：落在默认工作区，并创建一条可见的空白会话
            currentWorkspaceId = workspaces.firstOrNull()?.id
            val fresh = newSessionFor(currentWorkspaceId, blank = true, listed = true)
            this.sessions = listOf(fresh)
            currentSessionId = fresh.id
            saveLoadedOnIo(this.sessions)
        } else {
            this.sessions = sessions
            // 恢复用户最后停留的会话，而非 updatedAt 最新的一条：定时任务与后台唤醒会刷新 updatedAt，
            // 按该值取会进入被后台唤醒的会话（用户实测如此）。
            val remembered = persistedSessionId()
            val latest = sessions.firstOrNull { it.id == remembered } ?: sessions.first()
            currentSessionId = latest.id
            // 「上次的选择」采用最近会话的归属：该会话属于哪个工作区即进入哪个工作区；
            // 它没有归属（老数据 → 未分类）时退回列表第一个，保证当前工作区始终是合法 id
            currentWorkspaceId = latest.workspaceId ?: workspaces.firstOrNull()?.id
            messages.clear()
            messages.addAll(latest.messages)
            draftPort?.restore(latest.id)
        }
    }

    /** 会话表整体替换（`ContextState` 摘要写回等场景用）；与内部写入口同一份状态。 */
    fun replaceSessions(next: List<ChatSession>) {
        sessions = next
    }

    /**
     * 记住用户最后停留的会话。
     *
     * 与 updatedAt 的分工：updatedAt 表示「最近被写过」，会被定时任务与后台唤醒刷新；
     * 这里记的是「用户最后在看哪一条」，只在用户主动切换时写入。
     */
    private fun persistOpenedSession(id: String) {
        appContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_OPENED_SESSION, id).apply()
    }

    private fun persistedSessionId(): String? =
        appContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OPENED_SESSION, null)

    /**
     * 新建会话。
     *
     * 同一工作区至多一条空白会话：已有空白会话（无论是否显示）就地复用，不重复新建，
     * 草稿随之一并保留。用户手动新建的空白会话默认隐藏，首轮消息后转正并进入列表。
     */
    fun newSession() {
        if (isStreaming()) return
        // 附件是「当前这条提问」的，换会话就不该带过去
        clearAttachments()
        draftPort?.flush()
        persistCurrent()
        val workspaceId = currentWorkspaceId
        val blank = sessions.firstOrNull { it.blank && !it.archived && it.workspaceId == workspaceId }
        if (blank != null) {
            // 复用前刷新时间：列表按 updatedAt 排序，「新会话」必须出现在最前面；
            // 否则复用到的是很早以前那条空白会话，它会落在十几行之后，用户看不到自己刚点出来的会话
            val touched = blank.copy(updatedAt = System.currentTimeMillis())
            sessions = sessions.map { if (it.id == touched.id) touched else it }
            persistAll()
            open(touched)
            FileLog.log("session", "复用空白会话 " + blank.id)
            return
        }
        val fresh = newSessionFor(workspaceId, blank = true, listed = false)
        sessions = listOf(fresh) + sessions
        open(fresh)
        persistAll()
        FileLog.log("session", "新建会话 " + fresh.id + "，共 " + sessions.size + " 个")
    }

    /**
     * 切换会话。
     *
     * id 与当前会话相同时也要走一遍「盘上是否更长」的同步：定时任务跑完点通知回来，
     * 目标往往正是当前这条，界面这份 messages 还是后台追加之前的快照。
     * 内存里找不到目标时先重读一次盘：后台刚建出来的结果会话只有盘上有。
     */
    fun switchSession(id: String) {
        if (isStreaming()) return
        if (id == currentSessionId) {
            syncCurrentFromDisk()
            return
        }
        clearAttachments()
        draftPort?.flush()
        persistCurrent()
        var target = sessions.firstOrNull { it.id == id }
        if (target == null) {
            reloadSessionsFromDisk()
            target = sessions.firstOrNull { it.id == id }
        }
        if (target == null) return
        if (target.id == currentSessionId) {
            syncCurrentFromDisk()
            return
        }
        open(target)
        persistAll()
        FileLog.log("session", "切到会话 " + target.id + "（" + messages.size + " 条消息）")
    }

    /** 把某条会话设为当前会话：消息、选中工作区、累计、待办与草稿一并切换。 */
    private fun open(target: ChatSession) {
        currentSessionId = target.id
        persistOpenedSession(target.id)
        // 会话可能属于其他工作区（UI 直接点击某分组中的会话）：选中态随之切换，避免两处不一致
        currentWorkspaceId = target.workspaceId
        messages.clear()
        messages.addAll(target.messages)
        clearToolOutcomes()
        // 上一个会话的累计不回带：累计从目标会话自己的字段读回来（切回来就能接上）
        resetStats()
        reloadTodos()
        draftPort?.restore(target.id)
    }

    /**
     * 分叉会话：把当前历史复制成一条新会话，父会话原样不动。
     *
     * - [atIndex] == null（会话列表里的「分叉会话」）：复制全部消息。用户的预期是
     *   「带着当前内容开一个新会话，之后在这里另开一支」，少带最后一轮就成了丢内容；
     * - [atIndex] != null：复制 [0, atIndex)，[atIndex] 是**分叉边界**（不含）。
     *   助手气泡尾巴上的「分支」按钮传的是「本轮最后一条消息的下标 +1」，因此这一轮整轮都带进新会话，
     *   分支之后的轮次全部丢弃。
     * 摘要与待办都不继承：摘要记的是父会话的消息下标，待办属于父会话这一轮的计划。
     */
    fun forkSession(atIndex: Int? = null) {
        if (isStreaming()) {
            reportError("正在生成回复，无法分叉会话")
            return
        }
        // 先把内存里的消息写回当前会话：紧接着要从会话记录里取它的标题与工作区
        persistCurrent()
        val base = sessions.firstOrNull { it.id == currentSessionId } ?: return
        val snapshot = messages.toList()
        val boundary = (atIndex ?: snapshot.size).coerceIn(0, snapshot.size)
        val fresh = SessionStore.forked(base, snapshot.subList(0, boundary).toList())
        sessions = listOf(fresh) + sessions
        open(fresh)
        clearTodos()
        persistAll()
        FileLog.log(
            "session",
            "分叉会话 " + base.id + " → " + fresh.id + "（" + messages.size + " 条消息，boundary=" + boundary + "）"
        )
    }

    /**
     * 删除会话。**仅限已归档会话**：删除统一由归档页发起，避免误触。
     *
     * 会话真删时，其名下的计划、目标、上下文事件、草稿与无引用图片一并清理；
     * 若它是某内置目录的最后一个引用，目录按引用归零的规则进入回收站。
     */
    fun deleteSession(id: String) {
        if (isStreaming()) return
        val removed = sessions.firstOrNull { it.id == id } ?: return
        if (!removed.archived) {
            FileLog.log("session", "未归档的会话不允许删除：" + id)
            return
        }
        // 先将当前会话的最新消息存回列表，避免删除操作丢失本次改动
        persistCurrent()
        sessions = sessions.filter { it.id != id }
        // 磁盘上同样需实际删除：仅删除内存时，合并写盘会将该会话视为「磁盘新增」并重新写入
        SessionStore.removeSession(appContext, id)
        // 会话真删了：它名下的计划/目标/上下文事件也一起收掉，否则那几个 JSON 里永远留着孤儿
        clearSessionDocs(id)
        ContextEventStore.clear(appContext, id)
        draftPort?.forget(listOf(id))
        if (id == currentSessionId) {
            // 仅从当前工作区选择下一条：删除会话后不应跳转至其他工作区的对话
            val remaining = sessions.filter { !it.archived && it.workspaceId == currentWorkspaceId }
            if (remaining.isEmpty()) {
                val fresh = newSessionFor(currentWorkspaceId, blank = true, listed = true)
                sessions = listOf(fresh) + sessions
                open(fresh)
            } else {
                open(remaining.first())
            }
        }
        persistAll()
        // 会话删除后，其引入的图片可能已无引用：在 IO 中一并清理
        cleanupImagesOf(removed)
        reclaimStorage(removed.storageId, null)
        FileLog.log("session", "删除会话 " + id + "，剩 " + sessions.size + " 个，当前=" + currentSessionId)
    }

    /**
     * 删除已归档会话的确认文案中需要追加的一句。
     *
     * 仅当该会话是某内置目录的最后一个引用时返回非空：这时删除会连带把目录移入回收站，
     * 属于用户看不到的副作用，必须在确认前说明。
     */
    fun deleteSessionExtraNote(id: String): String? {
        val target = sessions.firstOrNull { it.id == id } ?: return null
        val storage = StorageStore.find(appContext, target.storageId) ?: return null
        if (storage.kind != StorageKind.PRIVATE) return null
        val refs = StorageStore.refs(workspaces, sessions, storage.id)
        if (refs.total != 1) return null
        return "其关联的内置目录将一并移入回收站并保留 30 天，可恢复或导出。"
    }

    /**
     * 删除会话后清理其引用的图片：同一存储单元中已无其他会话引用时才实际删除。
     *
     * 仅按该会话自身的存储单元查找目录：images 为存储单元相对路径，使用其他目录删除
     * 既找不到目标，也可能误删。判定不准确时最多「少删」一个文件，绝不会误删其他会话的图片。
     */
    private fun cleanupImagesOf(removed: ChatSession) {
        if (removed.messages.none { it.images.isNotEmpty() }) return
        // 先取快照：IO 期间用户还可能切会话、继续删，用副本判断「还有没有人引用」
        val remaining = sessions.filter { it.storageId == removed.storageId }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    UploadStore.cleanSessionImages(removed, remaining, rootOfStorage(removed.storageId))
                }.onFailure { FileLog.log("upload", "删除会话后清理图片失败：" + it) }
            }
        }
    }

    /** 重命名会话；标题 trim 后为空就退回「新会话」的空标题。 */
    fun renameSession(id: String, title: String) {
        if (isStreaming()) return
        val trimmed = title.trim()
        // 只改列表里的 title，不走 persistCurrent（那会用内存里的 messages 覆盖这条会话的记录）
        sessions = sessions.map { if (it.id == id) it.copy(title = trimmed) else it }
        persistAll()
        FileLog.log("session", "重命名会话 " + id + "：" + (if (trimmed.isEmpty()) "（空标题）" else trimmed))
    }

    /**
     * 置顶 / 取消置顶。
     *
     * 只改这一条记录的 pinned 标记，**不动 updatedAt**：置顶是把旧会话留在上面，
     * 同时刷新时间会使其看似「最近使用」，排序与「最近」的语义将混乱。
     */
    fun pinSession(id: String, pinned: Boolean) {
        if (sessions.none { it.id == id }) return
        sessions = sessions.map { if (it.id == id) it.copy(pinned = pinned) else it }
        persistAll()
        FileLog.log("session", (if (pinned) "置顶" else "取消置顶") + "会话 " + id)
    }

    /**
     * 归档前的特例说明，供确认弹窗使用；列表为空表示常规归档（只需一句轻提示）。
     *
     * 唯一的特例：有定时任务以该会话为目标，归档会把这些任务自动停用并解绑。
     * 内置目录的引用数不在此提示：归档不改存储单元、不改引用关系，句中所说的
     * 「以后删除会把它移入回收站」属于删除动作的后果，由 [deleteSessionExtraNote]
     * 在归档页的删除确认中说明；在可逆的归档前再说一遍属于重复告知。
     */
    fun archiveNotes(id: String): List<String> {
        if (sessions.none { it.id == id }) return emptyList()
        val notes = ArrayList<String>(1)
        if (ScheduleBridge.affectsSession(id)) {
            notes += "该会话绑定的定时任务已自动停用并解绑，若需启用，请重新配置该定时任务。"
        }
        return notes
    }

    /**
     * 归档会话；归档的是当前会话时，直接进入一个空对话。
     *
     * 归档会解除定时任务的绑定并停用该任务（单向，恢复会话不会自动重绑）：
     * 任务继续指向一条已归档的会话，只会在后台静默往不可见的记录里写内容。
     */
    fun archiveSession(id: String) {
        if (isStreaming()) return
        val target = sessions.firstOrNull { it.id == id } ?: return
        // 先确保当前会话消息已经落到 sessions，否则后面新建会话会把当前对话冲掉
        persistCurrent()
        // 解绑与停用放在改写之前：ScheduleBridge 需要读到「归档前」的会话状态
        ScheduleBridge.detachSession(id)
        sessions = sessions.map {
            if (it.id == id) it.copy(archived = true, updatedAt = System.currentTimeMillis()) else it
        }
        // 归档是可逆的：计划与目标一律不清，恢复回来还要原样在那儿（只有真删才清，见 deleteSession）
        persistAll()
        promoteBlankIfAlone(target.workspaceId)
        if (id == currentSessionId) {
            // 当前会话被归档：清空 messages 并落一条新的空会话
            newSession()
        }
        FileLog.log("session", "归档会话 " + target.id + "，当前=" + currentSessionId)
    }

    /**
     * 从归档恢复。
     *
     * 原工作区已删除时归入「未分类」，存储单元保持不变（附件与产出物仍在原目录）；
     * 返回 true 表示发生了这一降级，界面据此提示一句。
     */
    fun unarchiveSession(id: String): Boolean {
        if (isStreaming()) return false
        val target = sessions.firstOrNull { it.id == id } ?: return false
        val exists = target.workspaceId != null && workspaces.any { it.id == target.workspaceId }
        val workspaceId = if (exists) target.workspaceId else null
        sessions = sessions.map {
            if (it.id == id) {
                it.copy(archived = false, workspaceId = workspaceId, updatedAt = System.currentTimeMillis())
            } else {
                it
            }
        }
        persistAll()
        FileLog.log("session", "恢复会话 " + id + "，归属=" + (workspaceId ?: "未分类"))
        return !exists && target.workspaceId != null
    }

    /**
     * 把会话移到目标工作区。
     *
     * 分组与运行根同时改：工作区决定目录，会话的运行根必须跟随，否则两者会指向不同位置。
     * 返回需要补充的提示（原内置目录已无剩余引用时移入回收站），没有则返回 null。
     */
    fun moveSession(id: String, workspaceId: String): String? {
        if (isStreaming()) {
            reportError("正在生成回复，无法移动会话")
            return null
        }
        val target = sessions.firstOrNull { it.id == id } ?: return null
        val space = workspaces.firstOrNull { it.id == workspaceId } ?: return null
        if (target.workspaceId == workspaceId && target.storageId == space.storageId) return null
        draftPort?.flush()
        persistCurrent()
        val oldStorage = target.storageId
        val oldWorkspace = target.workspaceId
        sessions = sessions.map {
            if (it.id == id) {
                it.copy(workspaceId = workspaceId, storageId = space.storageId, updatedAt = System.currentTimeMillis())
            } else {
                it
            }
        }
        if (id == currentSessionId) currentWorkspaceId = workspaceId
        persistAll()
        promoteBlankIfAlone(oldWorkspace)
        val outcome = reclaimStorage(oldStorage, null)
        FileLog.log("session", "移动会话 " + id + " → 工作区 " + space.name)
        return if (outcome.result == StorageReclaimer.Result.TRASHED) {
            "已移动。原内置目录已无剩余引用，已移入回收站。"
        } else {
            null
        }
    }

    /** 当前工作区里未归档且可见的会话（sessions 已按 updatedAt 倒序，直接就能用）。 */
    fun sessionsInCurrentWorkspace(): List<ChatSession> =
        sessions.filter { !it.archived && it.listed && it.workspaceId == currentWorkspaceId }

    /** 当前工作区里已归档的会话（侧边栏折叠区用）。 */
    fun archivedInCurrentWorkspace(): List<ChatSession> =
        sessions.filter { it.archived && it.workspaceId == currentWorkspaceId }

    /**
     * 切换工作区（null = 未分类）：先存回当前会话，再切选中态，最后载入目标工作区最近的会话。
     */
    fun switchWorkspace(id: String?) {
        if (isStreaming()) return
        // 目标工作区不存在（例如刚被删除）则不切换，避免停留在空分组
        if (id != null && workspaces.none { it.id == id }) return
        draftPort?.flush()
        persistCurrent()
        currentWorkspaceId = id
        openRecentIn(id)
        persistAll()
        FileLog.log("workspace", "切到工作区 " + (id ?: "未分类") + "，当前会话=" + currentSessionId)
    }

    /**
     * 改工作区显示名：只动名字，存储单元与会话归属都不变（见 [WorkSpaceStore.rename]）。
     * 名字空白 / 工作区不存在返回 false，界面据此提示一句就行。
     */
    fun renameWorkspace(id: String, name: String): Boolean {
        val renamed = WorkSpaceStore.rename(appContext, id, name) ?: return false
        workspaces = workspaces.map { if (it.id == id) renamed else it }
        FileLog.log("workspace", "工作区 " + id + " 改名为 " + renamed.name)
        return true
    }

    /**
     * 新建工作区：名字空白返回 false。
     *
     * [rootPath] / [treeUri] 都空 = 建一个内置目录（`workspaces/<dirName>`）；给了用户目录就指向它，
     * 不在私有目录另建同名目录。目录不可用时返回 false，界面自己提示。
     */
    fun createWorkspace(
        name: String,
        rootPath: String? = null,
        treeUri: String? = null,
        /** 是否顺带切过去并开一条可见的空白会话；由「更改工作区」创建时不切，随后直接移入目标会话。 */
        openBlank: Boolean = true
    ): Boolean {
        if (isStreaming()) return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        draftPort?.flush()
        persistCurrent()

        val storage = if (rootPath == null && treeUri == null) {
            StorageStore.createPrivate(appContext, trimmed)
        } else {
            StorageStore.createExternal(appContext, rootPath, treeUri) ?: return false
        }
        val space = WorkSpaceStore.newRecord(trimmed, storage.id)
        val storages = StorageStore.loadStorages(appContext)
        val nextStorages = if (storages.any { it.id == storage.id }) storages else storages + storage
        workspaces = workspaces + space
        StorageStore.saveRegistry(
            appContext,
            StorageStore.Registry(workspaces, nextStorages)
        )
        if (openBlank) {
            currentWorkspaceId = space.id
            // 新工作区里一条会话都没有：openRecentIn 会给它建一条可见的空白会话
            openRecentIn(space.id)
        }
        persistAll()
        FileLog.log(
            "workspace",
            "新建工作区 " + trimmed + "（" + space.id + "）存储单元=" + storage.id + "（" + storage.kind.wire + "）"
        )
        return true
    }

    /**
     * 在同一弹窗内「新建工作区 + 移入会话」，一次提交。
     *
     * 与先建再移的区别：不预先切到一个空工作区，避免新建后留下一串多余的空白会话。
     */
    fun createWorkspaceAndMove(
        sessionId: String,
        name: String,
        rootPath: String?,
        treeUri: String?
    ): Boolean {
        if (isStreaming()) return false
        val before = workspaces.map { it.id }.toSet()
        if (!createWorkspace(name, rootPath, treeUri, openBlank = false)) return false
        val created = workspaces.firstOrNull { it.id !in before } ?: return false
        moveSession(sessionId, created.id)
        switchWorkspace(created.id)
        switchSession(sessionId)
        persistAll()
        FileLog.log("workspace", "新建工作区 " + created.name + " 并移入会话 " + sessionId)
        return true
    }

    /**
     * 该目录是否已由某个工作区映射；返回占用者名称，未占用返回 null。
     *
     * 「更改工作区」弹窗里新建工作区时用它做前置校验：同一目录不允许对应两个工作区，
     * 但允许复用「没有工作区、仅被会话引用」的存储单元。
     */
    fun workspaceUsingDir(rootPath: String?, treeUri: String?): String? {
        val path = rootPath?.trim()?.takeIf { it.isNotEmpty() }
        val tree = treeUri?.trim()?.takeIf { it.isNotEmpty() }
        if (path == null && tree == null) return null
        val storage = StorageStore.loadStorages(appContext).firstOrNull { item ->
            when {
                tree != null -> item.treeUri == tree
                path != null -> item.rootPath?.let {
                    StorageStore.canonicalPath(File(it)) == StorageStore.canonicalPath(File(path))
                } ?: false
                else -> false
            }
        } ?: return null
        return workspaces.firstOrNull { it.storageId == storage.id }?.name
    }

    /**
     * 删除工作区的确认文案：按「有会话 / 无会话且目录非空 / 无会话且目录为空」三选一。
     *
     * 恒定说明「只解除映射、不删文件」，差异只在引用归零后目录的去向。
     */
    fun deleteWorkspacePrompt(id: String): String {
        val space = workspaces.firstOrNull { it.id == id } ?: return ""
        val storage = StorageStore.find(appContext, space.storageId)
        val sessionCount = sessions.count { it.workspaceId == id }
        if (sessionCount > 0) {
            return "此操作仅解除工作区映射，不会删除物理文件。其下会话将移至「未分类」。"
        }
        if (storage == null || storage.kind == StorageKind.EXTERNAL || storage.kind == StorageKind.DEFAULT) {
            return "此操作仅解除工作区映射，不会删除物理文件。"
        }
        val dir = StorageStore.rootFile(appContext, storage)
        val empty = dir == null || !dir.exists() || StorageStore.scan(dir).files == 0
        return if (empty) {
            "此工作区没有关联会话，其内置目录为空，将直接移除，不占用回收站。"
        } else {
            "此操作仅解除工作区映射，不会删除目录中的文件。该内置目录将移入回收站并保留 30 天。"
        }
    }

    /**
     * 删除工作区：默认工作区不删。
     *
     * 语义是**解除映射**：记录移除、其下会话转「未分类」，`storageId` 保持不变（文件仍在原目录）；
     * 其下的空白会话直接删除——它从未转正，转过去只会成为不可见的残留并撑住目录引用。
     * 最后按引用归零判定存储单元是否回收。
     */
    fun deleteWorkspace(id: String) {
        if (isStreaming()) return
        if (id == WorkSpaceStore.DEFAULT_ID) {
            FileLog.log("workspace", "默认工作区不允许删除")
            return
        }
        val space = workspaces.firstOrNull { it.id == id } ?: return
        draftPort?.flush()
        persistCurrent()

        val blanks = sessions.filter { it.workspaceId == id && it.blank }
        val blankIds = blanks.map { it.id }.toSet()
        sessions = sessions.mapNotNull { session ->
            when {
                session.workspaceId != id -> session
                session.blank -> null
                else -> session.copy(workspaceId = null)
            }
        }
        if (blankIds.isNotEmpty()) {
            blankIds.forEach { clearSessionDocs(it) }
            draftPort?.forget(blankIds)
        }
        workspaces = workspaces.filter { it.id != id }
        WorkSpaceStore.save(appContext, workspaces)
        if (currentWorkspaceId == id || currentSessionId in blankIds) {
            // 删除的是当前工作区，或当前会话正是被一并删除的空白会话：
            // 一律切到未分类并载入其最近一条（刚解绑的会话即在此），避免当前会话指向已删除的记录
            currentWorkspaceId = null
            openRecentIn(null)
        }
        persistAll()
        reclaimStorage(space.storageId, space.name)
        FileLog.log(
            "workspace",
            "删除工作区 " + space.name + "（" + id + "），剩 " + workspaces.size +
                " 个，当前工作区=" + (currentWorkspaceId ?: "未分类")
        )
    }

    /** 更改储存路径的确认文案：按原目录类型三选一，恒定说明「只改映射、不移动文件」。 */
    fun changeStoragePrompt(id: String): String {
        val space = workspaces.firstOrNull { it.id == id } ?: return ""
        val storage = StorageStore.find(appContext, space.storageId)
        if (storage == null || storage.kind != StorageKind.PRIVATE) {
            return "此操作仅更改应用内的目录映射，不会移动或删除原目录文件。"
        }
        val dir = StorageStore.rootFile(appContext, storage)
        val empty = dir == null || !dir.exists() || StorageStore.scan(dir).files == 0
        return if (empty) {
            "此操作仅更改应用内的目录映射。原内置目录为空，将直接移除。"
        } else {
            "此操作仅更改应用内的目录映射。原内置目录将移入回收站并保留 30 天。"
        }
    }

    /**
     * 更改工作区的储存路径。
     *
     * 工作区与其下**全部会话**的 `storageId` 一起改：会话的运行根必须与分组一致，
     * 否则工具的相对路径会解析到旧目录。原存储单元随之归零并按规则回收。
     */
    fun changeWorkspaceStorage(id: String, rootPath: String?, treeUri: String?): Boolean {
        if (isStreaming()) {
            reportError("有会话正在运行，无法更改储存路径")
            return false
        }
        val space = workspaces.firstOrNull { it.id == id } ?: return false
        if (space.isDefault) return false
        draftPort?.flush()
        persistCurrent()
        val oldStorage = space.storageId
        val storage = if (rootPath == null && treeUri == null) {
            StorageStore.createPrivate(appContext, space.name)
        } else {
            StorageStore.createExternal(appContext, rootPath, treeUri) ?: return false
        }
        if (storage.id == oldStorage) return true
        val storages = StorageStore.loadStorages(appContext)
        val nextStorages = if (storages.any { it.id == storage.id }) storages else storages + storage
        workspaces = workspaces.map { if (it.id == id) it.copy(storageId = storage.id) else it }
        sessions = sessions.map { if (it.workspaceId == id) it.copy(storageId = storage.id) else it }
        StorageStore.saveRegistry(appContext, StorageStore.Registry(workspaces, nextStorages))
        persistAll()
        reclaimStorage(oldStorage, space.name)
        FileLog.log("workspace", "工作区 " + space.name + " 改储存路径 → " + storage.id)
        return true
    }

    /**
     * 载入某个工作区（null = 未分类）里 updatedAt 最新且未归档的会话；
     * 一条都没有就新建一条属于它的**可见**空白会话。
     */
    private fun openRecentIn(workspaceId: String?) {
        val pool = sessions.filter { !it.archived && it.workspaceId == workspaceId }
        clearToolOutcomes()
        // 空白会话只在「没有任何正式会话」时才有资格作为当前会话
        val target = pool.filter { !it.blank }.maxByOrNull { it.updatedAt } ?: pool.maxByOrNull { it.updatedAt }
        if (target != null) {
            // 只剩一条隐藏的空白会话：把它提升为显示（不另建），否则界面上会出现空分组
            val promoted = if (target.blank && !target.listed && pool.size == 1) {
                val next = target.copy(listed = true)
                sessions = sessions.map { if (it.id == next.id) next else it }
                next
            } else {
                target
            }
            open(promoted)
            return
        }
        val fresh = newSessionFor(workspaceId, blank = true, listed = true)
        sessions = listOf(fresh) + sessions
        open(fresh)
    }

    /** 侧边栏/顶栏显示的当前会话名，没起标题就是「新会话」。 */
    fun currentTitle(): String {
        val current = sessions.firstOrNull { it.id == currentSessionId }
        return if (current == null || current.title.isBlank()) "新会话" else current.title
    }

    /** 把内存里的 messages 写回当前会话那条记录，并把列表重排成 updatedAt 降序。 */
    internal fun persistCurrent(override: List<ChatMessage>? = null) {
        if (persistenceBlocked()) return
        val id = currentSessionId
        if (id.isEmpty()) return
        val now = System.currentTimeMillis()
        val snapshot = override ?: messages.toList()
        var found = false
        val updated = ArrayList<ChatSession>(sessions.size)
        for (session in sessions) {
            if (session.id == id) {
                found = true
                // copy 只改 messages/updatedAt：archived、title、storageId 原样保留，归档状态不会被消息落盘冲掉
                val changed = snapshot != session.messages
                updated.add(session.copy(messages = snapshot, updatedAt = if (changed) now else session.updatedAt))
            } else {
                updated.add(session)
            }
        }
        if (!found) {
            // 兜底：列表里没有当前会话（正常流程不会发生），补一条空的占位，避免消息丢在内存里
            updated.add(
                ChatSession(
                    id = id,
                    title = "",
                    messages = snapshot,
                    updatedAt = now,
                    workspaceId = currentWorkspaceId,
                    storageId = storageIdFor(currentWorkspaceId)
                )
            )
        }
        sessions = updated.sortedByDescending { it.updatedAt }
    }

    /**
     * 落盘检查点：把当前会话写进磁盘，但**剔掉在飞的那条 assistant 占位**。
     *
     * 由 [com.zmf.agent.capability.loop.AgentLoop] 在两个边界调用（发模型请求前、执行有副作用的工具前）。
     * 为什么剔占位：loop 会先 append 一条空 assistant 再流式填字，该条在崩溃后没有任何内容，
     * 写进去只会让重载后的界面多一个空气泡（未完成的 assistant frame 保持瞬态）。
     * 有 tool_calls 的 assistant 不能剔：它正是「已派发」的证据。
     */
    internal fun persistCheckpoint(reason: String) {
        if (persistenceBlocked()) return
        if (currentSessionId.isEmpty()) return
        val durable = checkpointMessages()
        persistCurrent(durable)
        // 检查点是「崩溃后还能接上」的底线，必须当场落地（见 persistAllNow 的注释）
        persistAllNow()
        FileLog.log("session", "检查点写盘（" + reason + "）：" + durable.size + " 条 / 内存 " + messages.size + " 条")
    }

    /**
     * 退到后台 / 按返回键时落盘。
     *
     * **按返回键永远不杀后台**：主页按返回只是退到桌面。
     * 但系统仍可能在后台回收进程，所以退后台这一刻要把会话与草稿一起写进去。
     */
    fun persistForBackground(reason: String) {
        draftPort?.flush()
        if (messages.isEmpty()) return
        runCatching { persistCheckpoint(reason) }
    }

    /** 供落盘用的消息快照：去掉尾部「还没填字的 assistant 占位」。 */
    private fun checkpointMessages(): List<ChatMessage> {
        var end = messages.size
        while (end > 0) {
            val message = messages[end - 1]
            if (message.role == "assistant" && message.content.isBlank() && message.toolCalls.isEmpty()) {
                end--
            } else {
                break
            }
        }
        return if (end == messages.size) messages.toList() else messages.subList(0, end).toList()
    }

    internal fun persistAll() {
        // 导入后未重启前一律不落盘：内存中仍为旧列表，写入会覆盖刚导入的会话
        if (persistenceBlocked()) return
        // 合并写：定时任务在后台往会话里追加过消息，整份覆盖会把它们抹掉，
        // 表现就是「迭代到四又从头开始」「记录里只剩第一条」
        val generation = persistGeneration.incrementAndGet()
        val snapshot = sessions
        persistScope.launch {
            // 连点会排进好几拍：只有最新一拍要写。旧的快照是它的子集，写了反而可能把删除的会话带回来
            if (generation != persistGeneration.get()) return@launch
            SessionStore.saveMerged(appContext, snapshot)
        }
    }

    /**
     * 同步落盘：退到后台 / 落盘检查点用。这两处要的是「返回时已经写进去了」，
     * 异步队列可能还没轮到，进程一被杀就丢了。
     */
    private fun persistAllNow() {
        if (persistenceBlocked()) return
        // 让排队中的异步写全部作废：它们的内容比这一刻旧
        persistGeneration.incrementAndGet()
        SessionStore.saveMerged(appContext, sessions)
    }

    /**
     * [loadInitial] 的落盘：只在「修复缺键」与「首次建空会话」两种情况下触发。
     * 此时处在启动阶段，整份会话表可能是数 MB，写到 IO 线程以免占用主线程。
     */
    private suspend fun saveLoadedOnIo(snapshot: List<ChatSession>) {
        if (persistenceBlocked()) return
        // 与 persistAllNow 同一口径：让排队中的异步写作废
        persistGeneration.incrementAndGet()
        withContext(Dispatchers.IO) { SessionStore.saveMerged(appContext, snapshot) }
    }

    /**
     * 当前会话若在盘上被追加过，就把消息补进界面。
     *
     * 只补「盘上更长」这一种情况：用户正在编辑的那份如果更长，仍以内存为准。
     */
    private fun syncCurrentFromDisk() {
        if (persistenceBlocked()) return
        persistCurrent()
        val disk = SessionStore.load(appContext).firstOrNull { it.id == currentSessionId } ?: return
        if (disk.messages.size <= messages.size) return
        messages.clear()
        messages.addAll(disk.messages)
        sessions = sessions.map { if (it.id == currentSessionId) disk else it }
        resetStats()
        FileLog.log("session", "当前会话同步盘上新增消息，共 " + messages.size + " 条")
    }

    /**
     * 从磁盘重读工作区表（助手工具新增或删除工作区之后调用）。
     *
     * 当前工作区若已被删除，退回第一个；其余状态不动，避免用户正在进行的对话被打断。
     */
    fun reloadWorkspaces() {
        if (persistenceBlocked()) return
        val next = StorageStore.loadWorkspaces(appContext)
        workspaces = next
        val current = currentWorkspaceId
        if (current != null && next.none { it.id == current }) {
            currentWorkspaceId = next.firstOrNull()?.id
        }
    }

    /** 从磁盘重读会话列表：以磁盘为准，只有当前会话的消息取内存里还没落盘的那份。 */
    fun reloadSessionsFromDisk() {
        if (persistenceBlocked()) return
        if (isStreaming()) return
        persistCurrent()
        val disk = SessionStore.load(appContext)
        val id = currentSessionId
        val mine = sessions.firstOrNull { it.id == id }
        val merged = ArrayList<ChatSession>(disk.size + 1)
        for (session in disk) {
            // 当前会话换成内存里的 messages/updatedAt，标题等其它字段仍跟磁盘走
            merged.add(
                if (mine != null && session.id == id) {
                    // 磁盘内容更长且内存内容为其前缀 ⇒ 其他来源（定时任务）追加过，以磁盘为准；
                    // 否则仍以内存为准（用户正在编辑的那份不能被旧快照顶掉）
                    if (session.messages.size > mine.messages.size &&
                        session.messages.take(mine.messages.size) == mine.messages
                    ) {
                        session
                    } else {
                        session.copy(messages = mine.messages, updatedAt = mine.updatedAt)
                    }
                } else {
                    session
                }
            )
        }
        if (mine != null && disk.none { it.id == id }) merged.add(mine)
        sessions = merged.sortedByDescending { it.updatedAt }
        // 当前会话在盘上更长（后台追加过）时，界面这份 messages 也要一起换掉：
        // 若仅替换 sessions，正在查看的会话仍停留在旧快照上，从通知返回时即为空白。
        val refreshed = merged.firstOrNull { it.id == id }
        val memoryCount = messages.size
        if (refreshed != null && refreshed.messages.size > memoryCount) {
            messages.clear()
            messages.addAll(refreshed.messages)
            resetStats()
            FileLog.log("session", "当前会话已同步盘上新增的 " + (refreshed.messages.size - memoryCount) + " 条")
        }
        FileLog.log("session", "从磁盘重载 " + sessions.size + " 条会话")
    }

    /** 当前会话还没标题时，把这句话前 20 字当标题。 */
    internal fun autoTitleIfNeeded(prompt: String) {
        val id = currentSessionId
        if (id.isEmpty()) return
        val current = sessions.firstOrNull { it.id == id } ?: return
        if (current.title.isNotEmpty()) return
        val title = prompt.replace('\n', ' ').trim().take(20)
        if (title.isEmpty()) return
        sessions = sessions.map { if (it.id == id) it.copy(title = title) else it }
        persistCurrent()
        FileLog.log("session", "会话 " + id + " 起标题：" + title)
    }

    /**
     * 首轮消息发出后转正：空白标记清除、隐藏的会话进入列表。
     *
     * 由发送流程在追加用户消息时调用；重复调用无副作用。
     */
    internal fun markSessionStarted(id: String = currentSessionId) {
        if (id.isEmpty()) return
        val current = sessions.firstOrNull { it.id == id } ?: return
        if (!current.blank && current.listed) return
        sessions = sessions.map {
            if (it.id == id) it.copy(blank = false, listed = true) else it
        }
        persistAll()
    }

    /** 清空当前会话的草稿（发送成功后调用）。 */
    fun clearCurrentDraft() {
        if (currentSessionId.isEmpty()) return
        draftPort?.forget(listOf(currentSessionId))
    }

    /** 当前会话的权限档：会话字段优先，缺失时取全局默认。 */
    fun sessionPermission(): PermissionLevel {
        val current = sessions.firstOrNull { it.id == currentSessionId } ?: return currentPermission()
        return com.zmf.agent.core.model.permissionLevelOfWire(current.permissionPreset) ?: currentPermission()
    }

    /** 当前会话的思考强度（`off` 表示关闭思考）。 */
    fun sessionEffort(): String {
        val current = sessions.firstOrNull { it.id == currentSessionId } ?: return currentEffort()
        return current.reasoningEffort.takeIf { it.isNotEmpty() } ?: currentEffort()
    }

    /** 只改当前会话的权限档，不触碰全局默认。 */
    fun setSessionPermission(level: PermissionLevel) {
        val id = currentSessionId
        if (id.isEmpty()) return
        if (sessionPermission() == level) return
        sessions = sessions.map { if (it.id == id) it.copy(permissionPreset = level.wireName()) else it }
        persistCurrent()
        persistAll()
        FileLog.log("permission", "会话 " + id + " 权限改为 " + level.displayNameCn())
    }

    /** 只改当前会话的思考强度，不触碰全局默认。 */
    fun setSessionEffort(effort: String) {
        val id = currentSessionId
        if (id.isEmpty()) return
        val value = effort.trim().lowercase()
        if (value.isEmpty() || sessionEffort() == value) return
        sessions = sessions.map { if (it.id == id) it.copy(reasoningEffort = value) else it }
        persistCurrent()
        persistAll()
        FileLog.log("session", "会话 " + id + " 思考强度改为 " + value)
    }

    /**
     * 会话不属于当前对话时，将其名下的计划与目标一并清除。
     *
     * 不清会留下孤儿记录：plans.json / goals.json 按 sessionId 存，会话都删了，
     * 该两行将始终无人读取，并随备份反复迁移。
     */
    private fun clearSessionDocs(sessionId: String) {
        if (sessionId.isBlank()) return
        PlanStore.clearPlans(appContext, sessionId)
        GoalStore.clear(appContext, sessionId)
    }

    /** 当前会话的运行根：以会话自己的存储单元为准，未分类或找不到时回落到默认目录。 */
    fun currentWorkspaceRoot(): File {
        val current = sessions.firstOrNull { it.id == currentSessionId }
        val storageId = current?.storageId?.takeIf { it.isNotEmpty() } ?: storageIdFor(currentWorkspaceId)
        return rootOfStorage(storageId)
    }

    /** 工作区根目录：按该工作区映射的存储单元解析，读不到时退回默认工作区目录并记日志。 */
    fun rootOf(space: WorkSpace): File = rootOfStorage(space.storageId)

    /** 存储单元根目录；不可用时退回默认目录（工具不能没有根，否则相对路径无从解析）。 */
    fun rootOfStorage(storageId: String): File {
        val storage = StorageStore.find(appContext, storageId)
        if (storage == null) {
            FileLog.log("workspace", "存储单元不存在（" + storageId + "），退回默认目录")
            return PathGate.workspaceRoot(appContext)
        }
        val root = StorageStore.ensureRoot(appContext, storage)
        if (root != null) return root
        FileLog.log("workspace", "存储单元 " + storageId + " 的目录不可用，退回默认目录")
        return PathGate.workspaceRoot(appContext)
    }

    /** 工作区对应的存储单元；不存在返回 null。 */
    fun storageOf(space: WorkSpace): Storage? = StorageStore.find(appContext, space.storageId)

    /** 当前会话的存储根：预览页与产物推导都按它解析相对路径。 */
    fun currentStorageRoot(): File =
        rootOfStorage(sessions.firstOrNull { it.id == currentSessionId }?.storageId?.takeIf { it.isNotEmpty() }
            ?: storageIdFor(currentWorkspaceId))

    /** 当前会话的读取入口：走 [WorkspaceIo] 才能兼容系统授权目录（SAF）。 */
    fun currentStorageIo(): WorkspaceIo = workspaceIoForRoot(appContext, currentStorageRoot())

    /**
     * 可能把附件写进当前存储目录的会话。
     *
     * 除当前工作区外还要带上「未分类」的会话：它们新建时落在默认目录，之后可能跟着当前工作区漂走，
     * 路径却仍写在会话里。多算几条引用只会少删，不会误删。
     * 输入框上方还排着的附件/图片同样算引用：文件已经在磁盘上，只是还没发出去。
     */
    fun sessionsOfCurrentWorkspace(): List<ChatSession> {
        val storageId = sessions.firstOrNull { it.id == currentSessionId }?.storageId
            ?.takeIf { it.isNotEmpty() } ?: storageIdFor(currentWorkspaceId)
        val base = sessions.filter { it.storageId == storageId || it.workspaceId == null }
        val pending = pendingUploadPaths()
        if (pending.isEmpty()) return base
        return base + ChatSession(
            id = "pending-uploads",
            messages = listOf(ChatMessage(role = "user", images = pending))
        )
    }

    // ---- 内部 ----

    /** 新建一条会话并带上当前默认偏好的快照。 */
    private fun newSessionFor(workspaceId: String?, blank: Boolean, listed: Boolean): ChatSession =
        SessionStore.newSession(
            storageId = storageIdFor(workspaceId),
            blank = blank,
            listed = listed,
            permissionPreset = currentPermission().wireName(),
            reasoningEffort = currentEffort()
        ).copy(workspaceId = workspaceId)

    /** 工作区的运行根存储单元；未分类与异常一律指向默认存储单元。 */
    private fun storageIdFor(workspaceId: String?): String =
        workspaces.firstOrNull { it.id == workspaceId }
            ?.storageId
            ?.takeIf { it.isNotEmpty() }
            ?: StorageStore.DEFAULT_ID

    /**
     * 工作区变空时把隐藏的空白会话提升为显示。
     *
     * 与「另建一条」的区别：草稿与消息都留在原会话里，用户看不到任何多余的空会话。
     */
    private fun promoteBlankIfAlone(workspaceId: String?) {
        if (workspaceId == null) return
        val pool = sessions.filter { !it.archived && it.workspaceId == workspaceId }
        if (pool.any { !it.blank && it.listed }) return
        val blank = pool.firstOrNull { it.blank && !it.listed } ?: return
        sessions = sessions.map { if (it.id == blank.id) it.copy(listed = true) else it }
        persistAll()
    }

    /** 引用归零后的回收；返回结果供界面决定是否补一句提示。 */
    private fun reclaimStorage(storageId: String, workspaceName: String?): StorageReclaimer.Outcome {
        if (storageId.isEmpty()) return StorageReclaimer.Outcome(StorageReclaimer.Result.NONE)
        return StorageReclaimer.reclaim(
            appContext,
            storageId,
            workspaceName,
            workspaces = workspaces,
            sessions = sessions
        )
    }
}

/** 权限档的中文名，供日志使用（界面文案在 capability 的 displayName 里）。 */
private fun PermissionLevel.displayNameCn(): String = when (this) {
    PermissionLevel.READ_ONLY -> "只读"
    PermissionLevel.WORKSPACE -> "工作区"
    PermissionLevel.FULL -> "完全"
}

/** [SessionController.loadInitial] 在 IO 线程一次读回的三份表；仅为减少上下文切换。 */
private data class InitialSnapshot(
    val spaces: List<WorkSpace>,
    val storageIds: Set<String>,
    val sessions: List<ChatSession>
)
