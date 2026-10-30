package com.zmf.agent.feature.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.zmf.agent.capability.context.ContextEventStore
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.GoalStore
import com.zmf.agent.core.store.PlanStore
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.UploadStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
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
 * 从 [ChatViewModel] 搬出，行为不变：字段名与原成员逐字一致，方法体原样照搬；
 * 只把跨模块动作改成注入——消息容器、流式标记、待发附件清理与引用路径、工具成败清理、
 * 待办/统计/错误的回显入口、导入后暂缓落盘的开关——免得这个类反过来攥住整个 ViewModel。
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
    private val persistenceBlocked: () -> Boolean
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
     * 会话落盘队列。
     *
     * 原来 persistAll 在主线程整表写盘：点一下会话得等写完才能重绘，涟漪和选中态全都迟到。
     * 现在状态先改、界面先动，写盘挪到 IO；连点排进来的多拍只保留最新一拍（旧快照是它的子集）。
     */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistGeneration = AtomicLong(0)

    /**
     * 启动时读回工作区表与会话表，决定当前会话并载入它的消息。
     *
     * 顺序要跟原来一致：先工作区（决定当前工作区时要用它兜底），再会话。
     */
    fun loadInitial() {
        // 工作区表先读回来：默认工作区永远在，后面决定当前工作区时要用它兜底
        workspaces = WorkSpaceStore.load(appContext)
        // 再把会话表读回来，决定当前会话：有历史就续上最近那条，没有就开一条空的
        // 顺便兜底：会话引用了已不存在的工作区（workspaces.json 丢了/坏了）就当未分类，
        // 否则这些会话在界面分组里会直接消失
        val loaded = SessionStore.load(appContext).sortedByDescending { it.updatedAt }
        val validIds = workspaces.map { space -> space.id }.toSet()
        val sessions = loaded.map { session ->
            val workspaceId = session.workspaceId
            if (workspaceId != null && !validIds.contains(workspaceId)) {
                session.copy(workspaceId = null)
            } else {
                session
            }
        }
        if (sessions.isEmpty()) {
            // 一条会话都没有：先落在第一个工作区（列表空就先进未分类），新会话跟着它走
            currentWorkspaceId = workspaces.firstOrNull()?.id
            val fresh = SessionStore.newSession().copy(workspaceId = currentWorkspaceId)
            this.sessions = listOf(fresh)
            currentSessionId = fresh.id
        } else {
            this.sessions = sessions
            // 恢复用户最后停留的那条，而不是 updatedAt 最新的一条：定时任务、后台唤醒、
            // 程序代发的消息都会刷新 updatedAt，按它取会把用户带进一条被后台叫醒的会话
            // （用户实测：杀后台重进，进的是最新被唤醒的会话而非离开前那条）。
            val remembered = persistedSessionId()
            val latest = sessions.firstOrNull { it.id == remembered } ?: sessions.first()
            currentSessionId = latest.id
            // 「上次的选择」就用最近那条会话的归属：它属于哪个工作区就进哪个工作区；
            // 它没有归属（老数据 → 未分类）时退回列表第一个，保证当前工作区始终是合法 id
            currentWorkspaceId = latest.workspaceId ?: workspaces.firstOrNull()?.id
            messages.clear()
            messages.addAll(latest.messages)
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

    /** 新建会话：当前会话先存回，再插一条空的到最前并切过去；新会话归当前工作区。 */
    fun newSession() {
        if (isStreaming()) return
        // 附件是「当前这条提问」的，换会话就不该带过去
        clearAttachments()
        persistCurrent()
        val fresh = SessionStore.newSession().copy(workspaceId = currentWorkspaceId)
        sessions = listOf(fresh) + sessions
        currentSessionId = fresh.id
        persistOpenedSession(fresh.id)
        messages.clear()
        clearToolOutcomes()
        // 新会话的累计就是它自己那四个字段（刚建出来全是 0）
        resetStats()
        persistAll()
        reloadTodos()
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
        currentSessionId = target.id
        persistOpenedSession(target.id)
        // 会话可能属于别的工作区（UI 直接点某个分组里的会话）：选中态跟着它走，避免两边对不上
        currentWorkspaceId = target.workspaceId
        messages.clear()
        messages.addAll(target.messages)
        clearToolOutcomes()
        // 上一个会话的累计不回带：累计从目标会话自己的字段读回来（切回来就能接上）
        resetStats()
        persistAll()
        reloadTodos()
        FileLog.log("session", "切到会话 " + target.id + "（" + messages.size + " 条消息）")
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
        currentSessionId = fresh.id
        persistOpenedSession(fresh.id)
        // 分支沿用父会话的工作区，选中态跟着它走，避免两边对不上
        currentWorkspaceId = fresh.workspaceId
        messages.clear()
        messages.addAll(fresh.messages)
        clearToolOutcomes()
        // 待办留在父会话：新会话的清单从空表开始
        clearTodos()
        resetStats()
        persistAll()
        FileLog.log(
            "session",
            "分叉会话 " + base.id + " → " + fresh.id + "（" + messages.size + " 条消息，boundary=" + boundary + "）"
        )
    }

    /** 删除会话：删的是当前会话就在当前工作区里挑下一条，一个都不剩就新建一条空的。 */
    fun deleteSession(id: String) {
        if (isStreaming()) return
        if (sessions.none { it.id == id }) return
        // 先把当前会话的最新消息存回列表，免得删除操作把这次的改动丢了
        persistCurrent()
        // 先留一份：列表里删掉之后，就再也看不出这条会话引用过哪些图片了
        val removed = sessions.firstOrNull { it.id == id }
        sessions = sessions.filter { it.id != id }
        // 盘上也要真删：只删内存的话，合并写盘会把这条会话当成「磁盘新增」又搬回来
        SessionStore.removeSession(appContext, id)
        // 会话真删了：它名下的计划/目标/上下文事件也一起收掉，否则那几个 JSON 里永远留着孤儿
        clearSessionDocs(id)
        ContextEventStore.clear(appContext, id)
        if (id == currentSessionId) {
            // 只在当前工作区里挑下一条：删完一个会话不该跳到别的工作区的对话
            val remaining = sessions.filter { !it.archived && it.workspaceId == currentWorkspaceId }
            if (remaining.isEmpty()) {
                val fresh = SessionStore.newSession().copy(workspaceId = currentWorkspaceId)
                sessions = listOf(fresh) + sessions
                currentSessionId = fresh.id
                persistOpenedSession(fresh.id)
                messages.clear()
            } else {
                val next = remaining.first()
                currentSessionId = next.id
                persistOpenedSession(next.id)
                messages.clear()
                messages.addAll(next.messages)
            }
            clearToolOutcomes()
            // 删除只删会话本身：全局总账（UsageLedger）是历史总账，一分不动
            resetStats()
        }
        persistAll()
        reloadTodos()
        // 会话没了，它带进来的图片可能再没人引用：在 IO 里顺手清一遍
        removed?.let { cleanupImagesOf(it) }
        FileLog.log("session", "删除会话 " + id + "，剩 " + sessions.size + " 个，当前=" + currentSessionId)
    }

    /**
     * 删除会话后清理它引用的图片：同工作区里已经没有别的会话引用才真删。
     *
     * 只按这条会话自己的归属工作区找目录：images 是工作区相对的，拿别的工作区的目录去删
     * 既找不到也会删错。判定不准时最多是「少删」一个文件，绝不会误删别人的图。
     */
    private fun cleanupImagesOf(removed: ChatSession) {
        if (removed.messages.none { it.images.isNotEmpty() }) return
        // 先取快照：IO 期间用户还可能切会话、继续删，用副本判断「还有没有人引用」
        val remaining = sessions.filter { it.workspaceId == removed.workspaceId }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    // 目录也在这里取：dirOf 会按需建目录，不该占着主线程删会话
                    val space = workspaces.firstOrNull { it.id == removed.workspaceId }
                        ?: WorkSpaceStore.defaultSpace(appContext)
                    UploadStore.cleanSessionImages(removed, remaining, rootOf(space))
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
     * 顺手刷新时间会让它假装成「最近用过」，排序和「最近」的语义就乱了。
     */
    fun pinSession(id: String, pinned: Boolean) {
        if (sessions.none { it.id == id }) return
        sessions = sessions.map { if (it.id == id) it.copy(pinned = pinned) else it }
        persistAll()
        FileLog.log("session", (if (pinned) "置顶" else "取消置顶") + "会话 " + id)
    }

    /** 归档会话；归档的是当前会话时，直接进一个空对话。 */
    fun archiveSession(id: String) {
        if (isStreaming()) return
        val target = sessions.firstOrNull { it.id == id } ?: return
        // 先确保当前会话消息已经落到 sessions，否则后面 newSession 会把当前对话冲掉
        persistCurrent()
        sessions = sessions.map {
            if (it.id == id) it.copy(archived = true, updatedAt = System.currentTimeMillis()) else it
        }
        // 归档是可逆的：计划与目标一律不清，取消归档回来还要原样在那儿（只有真删才清，见 deleteSession）
        persistAll()
        if (id == currentSessionId) {
            // 当前会话被归档：清空 messages 并落一条新的空会话
            newSession()
        }
        FileLog.log("session", "归档会话 " + target.id + "，当前=" + currentSessionId)
    }

    /** 从归档恢复。 */
    fun unarchiveSession(id: String) {
        if (isStreaming()) return
        sessions = sessions.map {
            if (it.id == id) it.copy(archived = false, updatedAt = System.currentTimeMillis()) else it
        }
        persistAll()
        FileLog.log("session", "恢复会话 " + id)
    }

    /** 当前工作区里没归档的会话（sessions 已按 updatedAt 倒序，直接就能用）。 */
    fun sessionsInCurrentWorkspace(): List<ChatSession> =
        sessions.filter { !it.archived && it.workspaceId == currentWorkspaceId }

    /** 当前工作区里已归档的会话（侧边栏折叠区用）。 */
    fun archivedInCurrentWorkspace(): List<ChatSession> =
        sessions.filter { it.archived && it.workspaceId == currentWorkspaceId }

    /**
     * 切换工作区（null = 未分类）：先存回当前会话，再切选中态，最后载入目标工作区最近的会话。
     */
    fun switchWorkspace(id: String?) {
        if (isStreaming()) return
        // 目标工作区不存在（比如刚被删掉）就不切，免得停在空分组上
        if (id != null && workspaces.none { it.id == id }) return
        persistCurrent()
        currentWorkspaceId = id
        openRecentIn(id)
        persistAll()
        FileLog.log("workspace", "切到工作区 " + (id ?: "未分类") + "，当前会话=" + currentSessionId)
    }

    /**
     * 改工作区显示名：只动名字，目录名与会话归属都不变（见 [WorkSpaceStore.rename]）。
     * 名字空白 / 工作区不存在返回 false，界面据此提示一句就行。
     */
    fun renameWorkspace(id: String, name: String): Boolean {
        val renamed = WorkSpaceStore.rename(appContext, id, name) ?: return false
        workspaces = workspaces.map { if (it.id == id) renamed else it }
        FileLog.log("workspace", "工作区 " + id + " 改名为 " + renamed.name)
        return true
    }

    /**
     * 新建工作区：名字空白返回 false；建目录 + 插进列表 + 切过去（切换会顺便建一条空会话）。
     *
     * [rootPath] / [treeUri] 都空 = 沿用应用私有目录（老行为）；给了用户目录就指向它，
     * 不在私有目录再建同名目录。目录不可用时返回 false，界面自己提示。
     */
    fun createWorkspace(name: String, rootPath: String? = null, treeUri: String? = null): Boolean {
        if (isStreaming()) return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        persistCurrent()
        val space = if (rootPath == null && treeUri == null) {
            WorkSpaceStore.create(appContext, trimmed)
        } else {
            WorkSpaceStore.createWithRoot(appContext, trimmed, rootPath, treeUri) ?: return false
        }
        workspaces = workspaces + space
        WorkSpaceStore.save(appContext, workspaces)
        currentWorkspaceId = space.id
        // 新工作区里一条会话都没有：openRecentIn 会给它建一条空会话
        openRecentIn(space.id)
        persistAll()
        FileLog.log(
            "workspace",
            "新建工作区 " + trimmed + "（" + space.id + "）root=" + (rootPath ?: space.rootPath ?: "私有目录") +
                " tree=" + (treeUri ?: "无")
        )
        return true
    }

    /**
     * 删除工作区：默认工作区不删；目录软删除（rename 进 .trash，留快照）；
     * 该工作区下所有会话（含归档）解绑到「未分类」；删的是当前工作区就切到未分类。
     */
    fun deleteWorkspace(id: String) {
        if (isStreaming()) return
        if (id == WorkSpaceStore.DEFAULT_ID) {
            FileLog.log("workspace", "默认工作区不允许删除")
            return
        }
        val space = workspaces.firstOrNull { it.id == id } ?: return
        persistCurrent()
        // 只 rename 目录、绝不真删：删除先留快照。
        val moved = WorkSpaceStore.softDelete(appContext, space)
        if (!moved) {
            // 目录没挪走也照样解绑：目录还完整留在磁盘上，会话不能跟着工作区一起消失
            FileLog.log("workspace", "工作区 " + space.name + " 的目录软删除失败，仍把会话解绑到未分类")
        }
        // 该工作区下所有会话（含归档）解绑到「未分类」。
        // 计划与目标不清：会话只是换了归属，数据没被删（清只在 deleteSession 里做）
        sessions = sessions.map { if (it.workspaceId == id) it.copy(workspaceId = null) else it }
        workspaces = workspaces.filter { it.id != id }
        WorkSpaceStore.save(appContext, workspaces)
        if (currentWorkspaceId == id) {
            // 删的是当前工作区：切到未分类，载入未分类里最近的那条（刚解绑的会话就在里面）
            currentWorkspaceId = null
            openRecentIn(null)
        }
        persistAll()
        FileLog.log(
            "workspace",
            "删除工作区 " + space.name + "（" + id + "），剩 " + workspaces.size +
                " 个，当前工作区=" + (currentWorkspaceId ?: "未分类")
        )
    }

    /**
     * 载入某个工作区（null = 未分类）里 updatedAt 最新且未归档的会话；一条都没有就新建一条属于它的空会话。
     * 只动 currentSessionId 和 messages，工作区选中态由调用方先改好。
     */
    private fun openRecentIn(workspaceId: String?) {
        val target = sessions
            .filter { !it.archived && it.workspaceId == workspaceId }
            .maxByOrNull { it.updatedAt }
        clearToolOutcomes()
        if (target != null) {
            currentSessionId = target.id
            persistOpenedSession(target.id)
            messages.clear()
            messages.addAll(target.messages)
            resetStats()
            reloadTodos()
            return
        }
        val fresh = SessionStore.newSession().copy(workspaceId = workspaceId)
        sessions = listOf(fresh) + sessions
        currentSessionId = fresh.id
        persistOpenedSession(fresh.id)
        messages.clear()
        messages.addAll(fresh.messages)
        resetStats()
        reloadTodos()
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
                // copy 只改 messages/updatedAt：archived、title 原样保留，归档状态不会被消息落盘冲掉
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
                    workspaceId = currentWorkspaceId
                )
            )
        }
        sessions = updated.sortedByDescending { it.updatedAt }
    }

    /**
     * 落盘检查点：把当前会话写进磁盘，但**剔掉在飞的那条 assistant 占位**。
     *
     * 由 [com.zmf.agent.capability.loop.AgentLoop] 在两个边界调用（发模型请求前、执行有副作用的工具前）。
     * 为什么剔占位：loop 会先 append 一条空 assistant 再流式填字，这条在崩掉后没有任何内容，
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
     * 但系统仍可能在后台回收进程，所以退后台这一刻要把会话写进去。
     * 空会话不写（避免每次切后台都产生一条空记录）。
     */
    fun persistForBackground(reason: String) {
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
        // 导入后没重启之前一律不落盘：内存里还是旧列表，写下去会把刚导入的会话盖掉
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
                    // 磁盘更长且内存那份是它的前缀 ⇒ 别人（定时任务）追加过，以磁盘为准；
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
        // 只换 sessions 的话，正在看的那条会话仍然停在旧快照上，通知点回来就是空白。
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
     * 会话不再属于当前对话时，把它名下的计划与目标一起收掉。
     *
     * 不清会留下孤儿记录：plans.json / goals.json 按 sessionId 存，会话都删了，
     * 那两行就永远没人读得到，还跟着备份一起搬来搬去。
     */
    private fun clearSessionDocs(sessionId: String) {
        if (sessionId.isBlank()) return
        PlanStore.clearPlans(appContext, sessionId)
        GoalStore.clear(appContext, sessionId)
    }

    /** 当前工作区目录：未分类（或找不到）时回落到默认工作区，和 send / attachFile 完全同一口径。 */
    fun currentWorkspaceRoot(): File =
        rootOf(
            workspaces.firstOrNull { it.id == currentWorkspaceId }
                ?: WorkSpaceStore.defaultSpace(appContext)
        )

    /**
     * 工作区根目录：优先用户在「自定义」里选的目录（`rootPath`）。
     *
     * 选了却读不到（授权失效、目录被删、路径不可写）就退回私有目录，但要记一条日志，
     * 免得工具悄悄写到别处、用户还以为落在自己选的文件夹里。
     * （只有 SAF 授权、没有可读绝对路径的工作区目前仍走私有目录，URI 分支待接。）
     */
    fun rootOf(space: WorkSpace): File {
        val userRoot = WorkSpaceStore.resolveRoot(appContext, space)
        if (userRoot != null) return userRoot
        if (!space.rootPath.isNullOrBlank()) {
            FileLog.log("workspace", "工作区 " + space.id + " 的自定义目录不可用：" + space.rootPath)
        }
        return WorkSpaceStore.dirOf(appContext, space)
    }

    /**
     * 可能把附件写进当前工作区目录的会话。
     *
     * 除本工作区外还要带上「未分类」的会话：它们新建时落在默认工作区，之后可能跟着当前工作区漂走，
     * 路径却仍写在会话里。多算几条引用只会少删，不会误删。
     * 输入框上方还排着的附件/图片同样算引用：文件已经在磁盘上，只是还没发出去。
     */
    fun sessionsOfCurrentWorkspace(): List<ChatSession> {
        val base = sessions.filter { it.workspaceId == currentWorkspaceId || it.workspaceId == null }
        val pending = pendingUploadPaths()
        if (pending.isEmpty()) return base
        return base + ChatSession(
            id = "pending-uploads",
            messages = listOf(ChatMessage(role = "user", images = pending))
        )
    }
}
