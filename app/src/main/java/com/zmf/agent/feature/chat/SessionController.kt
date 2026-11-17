package com.zmf.agent.feature.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.zmf.agent.capability.context.ContextEventStore
import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.capability.runtime.ScheduleBridge
import com.zmf.agent.capability.storage.StorageReclaimer
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.capability.tools.shell.support.SessionCwd
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.prefs.UiStateStore
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ORIGIN_RECOVERY
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.wireName
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.GoalStore
import com.zmf.agent.core.store.PlanStore
import com.zmf.agent.core.store.SessionLog
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.Storage
import com.zmf.agent.core.store.StorageKind
import com.zmf.agent.core.store.StorageStore
import com.zmf.agent.core.store.UploadStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.core.store.WorkspaceIo
import com.zmf.agent.core.store.workspaceIoForRoot
import com.zmf.agent.infra.llm.LlmEvent
import com.zmf.agent.uikit.common.NoticeCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 「用户最后停留的会话」的落盘位置。 */
private const val SESSION_PREFS = "session_prefs"
private const val KEY_OPENED_SESSION = "last_opened_session"

/** P32 恢复标记正文（仅界面可见）：说明上一轮在工具执行期间被中断，不再重试该调用。 */
private const val RECOVERY_NOTICE =
    "上次运行在工具执行期间被中断，该调用结果未知；本条只是界面记录，不要按它重试。"

/**
 * 恢复时给未结算工具调用补的合成结果正文。
 *
 * 与 DSH `dsh-session` 的 `CLOSER_TEXT.interrupted.started` 对齐（结局未知、按工具语义决定是否重试），
 * 并追加一句更保守的约束：未收到用户明确要求前不重试，避免新回合把上一条命令原样再跑一遍。
 */
private const val RECOVERY_TOOL_RESULT =
    "该工具调用已被记录，但结果未落盘，结局未知。" +
        "是否重试按工具语义判断：仅只读或幂等操作可重试；可能产生副作用时，先核实外部状态或询问用户。" +
        "未收到用户明确要求前不要重试。"

/**
 * 会话与工作区：会话表、当前会话、工作区表、当前工作区，以及它们的新建/切换/归档/删除与落盘。
 *
 * 目录不再挂在会话或工作区上：两者都只持有存储单元 id，运行根由存储单元解析，
 * 因此移动会话、删除工作区、更改储存路径都不需要搬运文件。
 *
 * 从 [ChatViewModel] 拆出，仅将跨模块动作改为注入——消息容器、流式标记、待发附件引用路径、
 * 工具成败清理、待办/统计/错误的回显入口、导入后暂缓落盘的开关、草稿存取与当前默认偏好。
 */
class SessionController(
    private val appContext: Context,
    /** 按会话取正文列表：每个会话各持一份，切会话只换显示，不换写入目标。 */
    private val messagesOf: (String) -> SnapshotStateList<ChatMessage>,
    /** 已载入内存的那些会话及其正文（落盘时折回各自的会话记录）。 */
    private val loadedMessages: () -> Map<String, SnapshotStateList<ChatMessage>>,
    /** 正在生成的那一轮所属会话；空闲时为 null。 */
    /** 这条会话是否正在跑回合（并行时代各会话各跑一个）。 */
    private val isTurnRunning: (String) -> Boolean,
    /** 正在跑回合的那些会话：落盘要把它们各写一次。 */
    private val turnSessions: () -> Set<String>,
    /** 会话被真删时丢弃内存里那一份正文。 */
    private val forgetMessages: (String) -> Unit,
    /** 会话被真删时丢弃其待发队列条目（内存队列 + queues.json，P33）。 */
    private val forgetQueues: (String) -> Unit,
    private val scope: CoroutineScope,
    private val isStreaming: () -> Boolean,
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
    private val currentEffort: () -> String,
    /** 标题请求的用量回调（会话 id + 服务端用量）：这条请求同样计费，交给上下文层入账。 */
    private val onTitleUsage: (String, LlmEvent.Finished) -> Unit
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
     * 当前会话的正文。本类里所有「当前会话」语义（打开、落盘、标题、盘上同步）都走它；
     * 换会话只是换成另一份列表，所以切走再切回不需要重建内容。
     */
    private val messages: SnapshotStateList<ChatMessage> get() = messagesOf(currentSessionId)

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

    /** 每条会话正在进行的模型标题任务；同一会话开始新任务前先取消旧的（对应 DSH 的 supersede）。 */
    private val titleJobs = HashMap<String, Job>()

    /** 用户显式改过名的会话：本次运行内不再接受自动标题（对应 DSH 用标题来源 `user` 钉住）。 */
    private val userNamedSessions = HashSet<String>()

    /**
     * 启动时读回工作区表与会话表，决定当前会话并载入它的消息。
     *
     * 顺序需与原先一致：先工作区（决定当前工作区时以其兜底），再会话。
     * 三份表的读盘与 JSON 解析放在 IO 线程：sessions.json 含全部会话正文，会话越多越接近
     * 冷启动 ANR 窗口；状态写入仍回到调用方（主线程），保证快照状态只在主线程修改。
     */
    suspend fun loadInitial() {
        // 先读回工作区表：默认工作区始终存在，随后决定当前工作区时用它兜底
        val snapshot = withContext(Dispatchers.IO) {
            // D1 迁移先行：旧 sessions.json → 每会话 JSONL（幂等；失败时本次启动降级为旧格式，
            // 下次重试）。必须先于读会话表，否则模式判定会抖动
            runCatching { SessionLog.migrateIfNeeded(appContext) }
                .onFailure { FileLog.log("session", "会话存储迁移异常：" + it) }
            InitialSnapshot(
                spaces = WorkSpaceStore.load(appContext),
                storageIds = StorageStore.loadStorages(appContext).map { it.id }.toSet(),
                // 列表页快读（D1.3-F 随机读保护）：只读索引，不解压任何会话文件；
                // 旧格式模式（迁移失败的降级）退回全量读
                sessions = SessionStore.loadSummaries(appContext)
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
        // 兜底处理：会话引用了已不存在的工作区（workspaces.json 丢了/坏了）就当未分类，
        // 否则这些会话在界面分组里会直接消失
        val loaded = snapshot.sessions.sortedByDescending { it.updatedAt }
        val validIds = workspaces.map { space -> space.id }.toSet()
        var repaired = false
        var sessions = loaded.map { session ->
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
        // P32：上次进程被杀时回合未收尾（turnOpen=true）的会话，先按 DSH 的合成 closer 口径
        // 给未结算的工具调用补错误结果（结局未知），再补一条仅界面可见的中断标记。
        // 索引条目里没有正文，须从该会话的事件流读回。
        var recovered = 0
        val openTurns = sessions.filter { it.turnOpen }
        if (openTurns.isNotEmpty()) {
            val recoveredSessions = withContext(Dispatchers.IO) {
                sessions.map { session ->
                    if (!session.turnOpen) {
                        session
                    } else {
                        val full = SessionStore.loadOne(appContext, session.id) ?: session
                        recovered++
                        full.copy(
                            turnOpen = false,
                            messages = closeOpenToolCalls(full.messages) + ChatMessage(
                                role = "user",
                                content = RECOVERY_NOTICE,
                                programmatic = true,
                                origin = ORIGIN_RECOVERY,
                                createdAt = System.currentTimeMillis()
                            ),
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                }
            }
            sessions = recoveredSessions
            this.sessions = recoveredSessions
            saveLoadedOnIo(recoveredSessions)
            FileLog.log("session", "P32 恢复：" + recovered + " 条会话补中断标记")
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
            // 当前会话的正文从它自己的事件流读回（D1 懒加载：索引条目里没有正文）
            val latestMessages = withContext(Dispatchers.IO) {
                runCatching { SessionStore.loadOne(appContext, latest.id)?.messages }
                    .getOrDefault(latest.messages)
            }
            messages.clear()
            messages.addAll(latestMessages.orEmpty())
            draftPort?.restore(latest.id)
        }
    }

    /** 会话表整体替换（`ContextState` 摘要写回等场景用）；与内部写入口同一份状态。 */
    fun replaceSessions(next: List<ChatSession>) {
        sessions = next
    }

    /**
     * 给「有 tool_calls、但没有对应 tool 结果」的调用补合成错误结果（DSH 的 TOOL_OUTCOME_UNKNOWN 口径）。
     *
     * 只处理正文末尾那条未收尾的回合：从后往前找最后一条带 tool_calls 的 assistant，
     * 取其后紧邻的 tool 结果，缺失的按调用顺序补在已有结果之后。
     * 补完后配对完整，请求视图不再把它整组丢弃——模型看到的是「结局未知」的错误结果，
     * 而不是空白加一句「请重试」的提示。
     */
    private fun closeOpenToolCalls(messages: List<ChatMessage>): List<ChatMessage> {
        val callIndex = messages.indexOfLast { it.role == "assistant" && it.toolCalls.isNotEmpty() }
        if (callIndex < 0) return messages
        var end = callIndex + 1
        while (end < messages.size && messages[end].role == "tool") end++
        val answered = messages.subList(callIndex + 1, end).mapNotNull { it.toolCallId }.toSet()
        val missing = messages[callIndex].toolCalls.filter { it.id !in answered }
        if (missing.isEmpty()) return messages
        val synthetic = missing.map { call ->
            ChatMessage(
                role = "tool",
                content = RECOVERY_TOOL_RESULT,
                toolCallId = call.id,
                toolName = call.name,
                toolOk = null,
                createdAt = System.currentTimeMillis()
            )
        }
        return messages.take(end) + synthetic + messages.drop(end)
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
        // 生成中也允许新建：正文按会话各持一份，新会话只是多一份空列表
        // （运行中那一轮继续写自己的，见 _Docs/上下文与落盘.md §2.5）
        // 待发附件按会话分槽：新建不是「换一条提问」，原会话的槽保留，新会话的槽从空开始
        draftPort?.flush()
        persistCurrent()
        val workspaceId = currentWorkspaceId
        val blank = sessions.firstOrNull { it.blank && !it.archived && it.workspaceId == workspaceId }
        if (blank != null) {
            // 复用前刷新时间：列表按 updatedAt 排序，「新会话」必须出现在最前；
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
     *
     * **生成中同样允许切**（用户 2026-11-15 要求）：正文按会话各持一份，正在跑的那一轮钉着自己的
     * 会话继续写，切走只改变界面显示的是哪一份，不打断流式输出。
     *
     * **待发附件与待发图片不清空**（用户 2026-11-16）：两者按会话分槽，切走只是换看另一条会话的槽，
     * 切回来仍在原位；清空只发生在该会话的消息把附件带走之后。
     */
    fun switchSession(id: String) {
        if (id == currentSessionId) {
            syncCurrentFromDisk()
            return
        }
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
        // 内存里那一份是正文的唯一真相：只有第一次打开（这份还是空的）时才用记录填一次。
        // 不能无条件 clear+addAll：生成中的回合可能正在向这份列表追加，清掉即等于丢失这些回合
        val list = messagesOf(target.id)
        if (list.isEmpty() && target.messages.isNotEmpty()) {
            list.addAll(target.messages)
        } else if (list.isEmpty() && !isTurnRunning(target.id)) {
            // D1 懒加载：预热尚未完成时记录里也没有正文，从该会话的事件流现读（IO）。
            // 读到后直接填进这份列表：Compose 观察的就是它，晚几毫秒出现正文没有副作用
            scope.launch {
                val disk = withContext(Dispatchers.IO) {
                    runCatching { SessionStore.loadOne(appContext, target.id)?.messages }.getOrNull()
                }
                // 读取期间回合开跑或预热已折入正文时让位：内存里那份才是唯一真相
                if (disk != null && list.isEmpty() && !isTurnRunning(target.id)) {
                    list.addAll(disk)
                    resetStats()
                    FileLog.log("session", "懒加载会话正文：" + target.id + "，" + disk.size + " 条")
                }
            }
        }
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
        // 分叉要读当前会话的正文快照：对正在生成的那条分叉会切出一份不完整的历史
        if (isTurnRunning(currentSessionId)) {
            reportError("这条会话正在生成回复，结束后才能分叉")
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
        // 运行中那条会话不能删：它的回合还在往这份正文里写，删了会连正文一起丢
        if (isTurnRunning(id)) {
            reportError("这条会话正在生成回复，结束后才能删除")
            return
        }
        val removed = sessions.firstOrNull { it.id == id } ?: return
        if (!removed.archived) {
            FileLog.log("session", "未归档的会话不允许删除：" + id)
            return
        }
        // 先将当前会话的最新消息存回列表，避免删除操作丢失本次改动
        persistCurrent()
        // 会话不再存在：它名下的后台任务一并终止，终止原因随任务结算保留
        JobRegistry.killSession(appContext, id, "会话已删除")
        sessions = sessions.filter { it.id != id }
        // 内存里那份正文一并丢弃：id 复用或会话表回退时不能再读到它
        forgetMessages(id)
        // 磁盘上同样需实际删除：仅删除内存时，合并写盘会将该会话视为「磁盘新增」并重新写入
        SessionStore.removeSession(appContext, id)
        // 会话真删了：它名下的计划/目标/上下文事件也一起收掉，否则那几个 JSON 里永远留着孤儿
        clearSessionDocs(id)
        ContextEventStore.clear(appContext, id)
        // 界面格局键同点删除（D3）：不留孤儿键，id 复用后也不会恢复到别人的页面
        UiStateStore.forget(appContext, id)
        draftPort?.forget(listOf(id))
        // 待发队列条目与子智能体登记条目同批清（X10）：删除会话后残留会让 queues.json 与
        // 子智能体面板各留一份孤儿，且前者的持久化条目还会随下一次 persistQueues 写回
        forgetQueues(id)
        SubAgentRegistry.clearSession(id)
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
        val trimmed = title.trim()
        // 显式改名会作废仍在进行的自动命名并钉住标题（对应 DSH 的 `rename`）
        titleJobs.remove(id)?.cancel()
        if (trimmed.isNotEmpty()) userNamedSessions.add(id)
        // 用户输入同样受 80 字节上限约束（DSH 在受理改名时归一化）
        val accepted = if (trimmed.isEmpty()) "" else SessionStore.normalizeTitle(trimmed)
        // 只改列表里的 title，不走 persistCurrent（那会用内存里的 messages 覆盖这条会话的记录）
        sessions = sessions.map { if (it.id == id) it.copy(title = accepted) else it }
        persistAll()
        FileLog.log("session", "重命名会话 " + id + "：" + (if (accepted.isEmpty()) "（空标题）" else accepted))
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
     * 在归档页的删除确认中说明；在可逆的归档前重复说明属于冗余告知。
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
        if (isTurnRunning(id)) {
            reportError("这条会话正在生成回复，结束后才能归档")
            return
        }
        val target = sessions.firstOrNull { it.id == id } ?: return
        // 归档后不再有人读取该会话的完成通知：先终止它名下的后台任务
        JobRegistry.killSession(appContext, id, "会话已归档")
        // 先确保当前会话消息已经落到 sessions，否则随后新建会话会把当前对话冲掉
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
        // 移动只改归属，不碰正文；只拦正在生成的那条（它的运行根在回合开局时已固定）
        if (isTurnRunning(id)) {
            reportError("这条会话正在生成回复，结束后才能移动")
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
        // 目标工作区不存在（例如刚被删除）则不切换，避免停留在空分组
        if (id != null && workspaces.none { it.id == id }) return
        draftPort?.flush()
        persistCurrent()
        currentWorkspaceId = id
        openRecentIn(id)
        persistAll()
        notifyIfTerminalUnmapped(id)
        FileLog.log("workspace", "切到工作区 " + (id ?: "未分类") + "，当前会话=" + currentSessionId)
    }

    /**
     * 工作区对应的终端目录：能映射进 PRoot 时是工作区根，否则为 `/root`。
     *
     * 新建/切换工作区的不可映射提示与终端目录派生共用这一个计算点，避免界面与执行期各算一套。
     */
    fun terminalDirOf(workspaceId: String?): String {
        val storage = StorageStore.find(appContext, storageIdFor(workspaceId))
            ?: return SessionCwd.DEFAULT_GUEST
        val root = StorageStore.ensureRoot(appContext, storage) ?: return SessionCwd.DEFAULT_GUEST
        return SessionCwd.workspaceTerminalGuest(appContext, root)
    }

    /** 当前工作区的终端目录。 */
    fun terminalDir(): String = terminalDirOf(currentWorkspaceId)

    /**
     * 新建或切换工作区后提示一次：派生值为 `/root` 说明该工作区不在终端可访问范围内。
     * 只提示、不拦截；可映射工作区不打扰用户。
     */
    private fun notifyIfTerminalUnmapped(workspaceId: String?) {
        if (terminalDirOf(workspaceId) == SessionCwd.DEFAULT_GUEST) {
            NoticeCenter.info("该工作区不在终端可访问范围内，终端命令将使用 /root。")
        }
    }

    /**
     * 改工作区显示名：只动名字，存储单元与会话归属都不变（见 [WorkSpaceStore.rename]）。
     * 名字为空或工作区不存在时返回 false，界面据此给出提示即可。
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
        // 「新建 + 移入」由随后的 switchWorkspace 统一提示，避免同一次操作弹两遍
        if (openBlank) notifyIfTerminalUnmapped(space.id)
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
        // 只拦正在生成的那条会话：它的运行根在回合开局时已固定，搬走会让工具写到新目录
        if (isTurnRunning(sessionId)) {
            reportError("这条会话正在生成回复，结束后才能移动")
            return false
        }
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

    /**
     * 把内存里的正文写回会话记录，并把列表重排成 updatedAt 降序。
     *
     * 生成中用户可能正在看另一条会话：此时「当前显示会话」与「正在跑的那一轮所属会话」不是同一条，
     * 两条都要写——只写当前那条的话，回合那条的正文要等下次落盘才进记录，进程被杀就丢了。
     * [override] 是落盘检查点用的「去掉在飞占位」快照，只对正在跑的那条生效。
     */
    internal fun persistCurrent(override: List<ChatMessage>? = null) {
        // 并行时可能有多条会话在跑：每条在跑的会话各落一次，再落当前显示的会话
        for (id in turnSessions() - currentSessionId) persistSession(id, null)
        persistSession(currentSessionId, override)
    }

    /** 把某条会话的内存正文写回它的记录；列表里没有这条时补一条占位，避免正文只留在内存里。 */
    internal fun persistSessionNow(id: String) = persistSession(id, null)

    /** 把某条会话的内存正文写回它的记录；列表里没有这条时补一条占位，避免正文只留在内存里。 */
    private fun persistSession(id: String, override: List<ChatMessage>?) {
        if (persistenceBlocked()) return
        if (id.isEmpty()) return
        val now = System.currentTimeMillis()
        val snapshot = override ?: messagesOf(id).toList()
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
            // 兜底：列表里没有这条会话（正常流程不会发生），补一条空的占位，避免消息丢在内存里
            val workspaceId = if (id == currentSessionId) currentWorkspaceId else null
            updated.add(
                ChatSession(
                    id = id,
                    title = "",
                    messages = snapshot,
                    updatedAt = now,
                    workspaceId = workspaceId,
                    storageId = storageIdFor(workspaceId)
                )
            )
        }
        sessions = updated.sortedByDescending { it.updatedAt }
        // D1：正文回写以本会话为单位直接落盘（允许截断——回滚路径会把磁盘长出的尾部裁掉）。
        // 每会话独立追加不存在「旧快照覆盖新快照」，旧整写时代的代际闸随之删除（X05 保留
        // 进程内单写者 + 索引 len 一致性检查）。实际写盘在 IO；事件为空时 upsert 不产生任何写入
        val record = updated.firstOrNull { it.id == id } ?: return
        persistScope.launch {
            runCatching { SessionStore.saveAuthoritative(appContext, record) }
                .onFailure { FileLog.log("session", "会话落盘失败：" + (it.message ?: it.javaClass.simpleName)) }
        }
    }

    /**
     * 落盘检查点：把当前会话写进磁盘，但**剔掉在飞的那条 assistant 占位**。
     *
     * 由 [com.zmf.agent.capability.loop.AgentLoop] 在两个边界调用（发模型请求前、执行有副作用的工具前）。
     * 为什么剔占位：loop 会先 append 一条空 assistant 再流式填字，该条在崩溃后没有任何内容，
     * 写进去只会让重载后的界面多一个空气泡（未完成的 assistant frame 保持瞬态）。
     * 有 tool_calls 的 assistant 不能剔：它正是「已派发」的证据。
     */
    internal fun persistCheckpoint(reason: String, sessionId: String = currentSessionId) {
        if (persistenceBlocked()) return
        // 检查点属于「正在生成的那一轮」：并行时用户可能已经在看别的会话，不能拿当前会话的正文去写
        val active = sessionId
        if (active.isEmpty()) return
        val durable = checkpointMessages(messagesOf(active))
        // 快照只属于这条会话：先写它，再把其余在跑的会话与当前会话各落一次
        persistSession(active, durable)
        persistCurrent()
        // 检查点仍在屏障处立即排队写盘：本条会话的逐会话写盘本来就异步，
        // 这里只是把其余会话折回后交给 persistAll，由 persistScope（IO）完成磁盘写入，不占主线程
        persistAll()
        FileLog.log(
            "session",
            "检查点写盘（" + reason + "）：" + durable.size + " 条 / 内存 " + messagesOf(active).size + " 条",
            sessionId = active
        )
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
    private fun checkpointMessages(list: List<ChatMessage>): List<ChatMessage> {
        var end = list.size
        while (end > 0) {
            val message = list[end - 1]
            if (message.role == "assistant" && message.content.isBlank() && message.toolCalls.isEmpty()) {
                end--
            } else {
                break
            }
        }
        return if (end == list.size) list.toList() else list.subList(0, end).toList()
    }

    /**
     * 把内存里**所有已载入的会话正文**折回各自的会话记录。
     *
     * 一份会话各持一份列表之后，记录里的 messages 只有在落盘这一刻才被内存覆盖；
     * 不折回的话，用户切换过的那几条会话在盘上仍停在上一次写回的版本。
     */
    private fun foldLoadedMessages() {
        val loaded = loadedMessages()
        if (loaded.isEmpty()) return
        var changed = false
        sessions = sessions.map { session ->
            val list = loaded[session.id] ?: return@map session
            // 空列表不折：可能是「刚建还没填」的那份，用空内容覆盖记录会把正文抹掉
            if (list.isEmpty() || list == session.messages) return@map session
            changed = true
            session.copy(messages = list.toList())
        }
        if (!changed) return
    }

    internal fun persistAll() {
        // 导入后未重启前一律不落盘：内存中仍为旧列表，写入会覆盖刚导入的会话
        if (persistenceBlocked()) return
        // 先把内存里各会话的正文折回记录，再走逐会话 upsert
        foldLoadedMessages()
        val snapshot = sessions
        persistScope.launch {
            // D1：saveMerged ＝ 逐会话 upsert（不截断）；正在跑回合的会话由 persistSession 单独落，
            // 这里再 upsert 一次也安全：内容一致时差异为空，不写任何事件。
            // 旧整写时代的「连点只写最新一拍」代际闸删除：追加写没有覆盖问题
            runCatching { SessionStore.saveMerged(appContext, snapshot) }
                .onFailure { FileLog.log("session", "会话表落盘失败：" + (it.message ?: it.javaClass.simpleName)) }
        }
    }

    /**
     * [loadInitial] 的落盘：只在「修复缺键」「P32 恢复补标记」与「首次建空会话」三种情况下触发。
     * 此时处在启动阶段，写到 IO 线程以免占用主线程。
     */
    private suspend fun saveLoadedOnIo(snapshot: List<ChatSession>) {
        if (persistenceBlocked()) return
        withContext(Dispatchers.IO) {
            runCatching { SessionStore.saveMerged(appContext, snapshot) }
                .onFailure { FileLog.log("session", "会话表落盘失败：" + (it.message ?: it.javaClass.simpleName)) }
        }
    }

    /**
     * 启动预热（D1.3-F）：首屏已经按索引渲染完毕，这里把各会话正文从事件流折回内存。
     *
     * 只给「内存里还没有正文」的记录补正文，绝不覆盖在跑回合或用户正在编辑的内容；
     * 依赖正文的消费者（总账回填、附件引用统计、洞察统计）从此读到全量数据，
     * 与旧版「启动即全量在内存」的语义对齐——只是把解压挪出了首屏关键路径。
     */
    suspend fun warmUpSessions() {
        if (persistenceBlocked()) return
        val full = withContext(Dispatchers.IO) {
            runCatching { SessionStore.load(appContext) }.getOrDefault(emptyList())
        }
        if (full.isEmpty()) return
        val loaded = loadedMessages()
        sessions = sessions.map { record ->
            val disk = full.firstOrNull { it.id == record.id } ?: return@map record
            val memory = loaded[record.id]
            if (record.messages.isNotEmpty() || (memory != null && memory.isNotEmpty())) {
                record
            } else {
                record.copy(messages = disk.messages)
            }
        }
        FileLog.log("session", "会话预热完成：" + sessions.size + " 条记录已带正文")
    }

    /**
     * 当前会话若在盘上被追加过，就把消息补进界面。
     *
     * 只补「盘上更长」这一种情况：用户正在编辑的那份如果更长，仍以内存为准。
     */
    private fun syncCurrentFromDisk() {
        if (persistenceBlocked()) return
        // 正在被本轮写的那条会话，盘上必然比内存旧：不能拿它覆盖内存，否则流式内容会断
        if (isTurnRunning(currentSessionId)) return
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

    /**
     * 从磁盘重读会话列表：以磁盘为准，只有「内存里已载入的正文」取内存那份。
     *
     * 一份会话各持一份列表之后，判断口径从「只护当前会话」放宽到「护所有已载入的会话」：
     * 切走的那些会话正文同样只在内存里最新，被盘上的旧快照顶掉就等于丢消息。
     */
    fun reloadSessionsFromDisk() {
        if (persistenceBlocked()) return
        if (isStreaming()) {
            // 回合进行中不能整表重载（会用盘上的旧正文顶掉正在流的这条），
            // 但助手工具在回合内改了会话设置（session_update_settings）时必须带回来：
            // 否则内存仍是旧值，下一次检查点落盘会把磁盘上的新设置冲掉。
            mergeSessionSettingsFromDisk()
            return
        }
        persistCurrent()
        foldLoadedMessages()
        val disk = SessionStore.load(appContext)
        val id = currentSessionId
        val mine = sessions.firstOrNull { it.id == id }
        val merged = ArrayList<ChatSession>(disk.size + 1)
        for (session in disk) {
            val memory = sessions.firstOrNull { it.id == session.id }
            // 盘上内容更长且内存内容为其前缀 ⇒ 其他来源（定时任务）追加过，以磁盘为准；
            // 否则仍以内存为准（用户正在编辑的那份不能被旧快照顶掉）
            val memoryWins = memory != null && !(
                session.messages.size > memory.messages.size &&
                    session.messages.take(memory.messages.size) == memory.messages
                )
            merged.add(
                if (memoryWins) {
                    session.copy(messages = memory.messages, updatedAt = memory.updatedAt)
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
        val memoryCount = messagesOf(id).size
        if (refreshed != null && refreshed.messages.size > memoryCount) {
            val list = messagesOf(id)
            list.clear()
            list.addAll(refreshed.messages)
            resetStats()
            FileLog.log("session", "当前会话已同步盘上新增的 " + (refreshed.messages.size - memoryCount) + " 条")
        }
        FileLog.log("session", "从磁盘重载 " + sessions.size + " 条会话")
    }

    /**
     * 回合进行中只把「会话设置」从盘上合并回内存（正文一律不动）。
     *
     * 工具改设置在回合内完成时，整表重载会被 `isStreaming` 拦下；不同步这一份就会出现
     * 「设置看起来改了、下一回合不生效、并被检查点落盘写回旧值」的静默丢失。
     */
    private fun mergeSessionSettingsFromDisk() {
        val disk = runCatching { SessionStore.load(appContext) }.getOrDefault(emptyList())
        if (disk.isEmpty()) return
        val byId = disk.associateBy { it.id }
        var changed = false
        sessions = sessions.map { memory ->
            val saved = byId[memory.id] ?: return@map memory
            if (
                memory.permissionPreset == saved.permissionPreset &&
                memory.reasoningEffort == saved.reasoningEffort
            ) {
                memory
            } else {
                changed = true
                memory.copy(
                    permissionPreset = saved.permissionPreset,
                    reasoningEffort = saved.reasoningEffort
                )
            }
        }
        if (changed) FileLog.log("session", "回合中合并会话设置（权限/思考强度）")
    }

    /**
     * 当前会话还没有标题时：先同步写入兜底标题，再异步请模型起一个更好的。
     *
     * 优先级与 DSH（DeepSeek Harness）的 `session-title` 一致：
     * 1. 兜底标题（前 5 个词 / 40 字节）在发送路径上立即可见，任何情况下都先落这一层；
     * 2. 只有首条用户消息才请求模型（对应 `first-prompt`：消息数为 1 且尚无标题）；
     * 3. 模型结果仅在标题仍是本次写入的兜底名时才替换；用户改名或其它写入一律不覆盖；
     * 4. 失败、超时、输入超过 4096 字节与输出被截断时静默保留兜底标题。
     *
     * [sessionId] 是**发起回合的那条会话**：队列补发与分享发送可能发生在用户已经切走之后，
     * 标题必须写回原会话，不能落到当时显示的那条上。首条消息的判定也按该会话自己的正文。
     */
    internal fun autoTitleIfNeeded(prompt: String, sessionId: String = currentSessionId) {
        val id = sessionId
        if (id.isEmpty()) return
        val current = sessions.firstOrNull { it.id == id } ?: return
        if (current.title.isNotEmpty()) return
        val fallback = SessionStore.fallbackTitle(prompt)
        if (fallback.isEmpty()) return
        // 首条用户消息：此刻消息容器还是空的（发送流程尚未追加），模型标题只在这一步调度
        val firstPrompt = messagesOf(id).isEmpty()
        sessions = sessions.map { if (it.id == id) it.copy(title = fallback) else it }
        persistCurrent()
        FileLog.log("session", "会话 " + id + " 起标题：" + fallback)
        if (!firstPrompt) return
        requestModelTitle(id, prompt, fallback)
    }

    /** 异步请求模型标题；期间会话被改名、删除或由别处写过标题，结果一律丢弃。 */
    private fun requestModelTitle(id: String, firstPrompt: String, fallback: String) {
        titleJobs.remove(id)?.cancel()
        val job = scope.launch {
            try {
                val generated = SessionTitleNamer.generate(
                    appContext,
                    firstPrompt,
                    onUsage = { finished -> onTitleUsage(id, finished) }
                ) ?: return@launch
                // 模型输出同样受 80 字节上限约束（DSH 在受理 provider 结果时归一化）
                val accepted = SessionStore.normalizeTitle(generated)
                if (accepted.isEmpty()) return@launch
                val current = sessions.firstOrNull { it.id == id } ?: return@launch
                // 只覆盖本次写入的兜底名：用户改名、工具改名或其它自动命名都优先
                if (current.title != fallback || id in userNamedSessions) return@launch
                sessions = sessions.map { if (it.id == id) it.copy(title = accepted) else it }
                persistAll()
                FileLog.log("session", "会话 " + id + " 模型起标题：" + accepted)
            } finally {
                if (titleJobs[id] === coroutineContext[Job]) titleJobs.remove(id)
            }
        }
        titleJobs[id] = job
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

    /**
     * 清空指定会话的草稿（发送成功后调用）。
     *
     * [sessionId] 默认当前显示会话；回合补发到别的会话时必须显式带上，
     * 否则会清掉用户眼前这条会话正在写的草稿。
     */
    fun clearCurrentDraft(sessionId: String = currentSessionId) {
        if (sessionId.isEmpty()) return
        draftPort?.forget(listOf(sessionId))
    }

    /** P32：回合开合标记的唯一写入口（meta-only，落盘不碰消息）。 */
    fun setTurnOpen(id: String, open: Boolean) {
        if (id.isEmpty()) return
        sessions = sessions.map { if (it.id == id) it.copy(turnOpen = open) else it }
        runCatching { SessionStore.setTurnOpen(appContext, id, open) }
            .onFailure { FileLog.log("session", "turnOpen 落盘失败：" + it) }
    }

    /** 当前会话的权限档：会话字段优先，缺失时取全局默认。 */
    fun sessionPermission(): PermissionLevel {        val current = sessions.firstOrNull { it.id == currentSessionId } ?: return currentPermission()
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
