package com.zmf.agent.feature.chat
import com.zmf.agent.core.model.BusySendMode
import com.zmf.agent.infra.llm.WireDump
import com.zmf.agent.core.llm.ApiDefaults

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.zmf.agent.capability.config.ApiConfig
import com.zmf.agent.capability.context.ContextEvent
import com.zmf.agent.capability.context.ContextPolicy
import com.zmf.agent.capability.context.ContextSnapshot
import com.zmf.agent.core.store.Goal
import com.zmf.agent.core.store.label
import com.zmf.agent.capability.loop.AgentLoop
import com.zmf.agent.capability.mcp.McpManager
import com.zmf.agent.capability.loop.AgentUi
import com.zmf.agent.capability.memory.MemoryState
import com.zmf.agent.capability.loop.ContextSummaryState
import com.zmf.agent.capability.loop.OutputTiming
import com.zmf.agent.capability.permission.PermissionStore
import com.zmf.agent.capability.prompt.PromptInput
import com.zmf.agent.capability.prompt.PromptStore
import com.zmf.agent.capability.skill.SkillStore
import com.zmf.agent.infra.llm.DeepSeekProvider
import com.zmf.agent.infra.llm.ProviderConfig
import com.zmf.agent.infra.llm.ProviderStore
import com.zmf.agent.infra.llm.UsageBuckets
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.PlanDoc
import com.zmf.agent.core.store.Todo
import com.zmf.agent.core.store.UsageLedger
import com.zmf.agent.capability.subagent.SubAgentDeps
import com.zmf.agent.capability.subagent.SubAgentRunner
import com.zmf.agent.core.store.UploadStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.core.AppForeground
import com.zmf.agent.infra.backup.BackupManifest
import com.zmf.agent.infra.backup.BackupOptions
import com.zmf.agent.infra.backup.ImportReport
import com.zmf.agent.core.log.AuditLog
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.core.notify.Notifier
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolRegistry
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.SandboxRegression
import com.zmf.agent.capability.tools.file.TrashStore
import com.zmf.agent.capability.tools.displayName
import com.zmf.agent.capability.tools.spill.SpillStore
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.io.File

/**
 * 图片路径说明这段文字在提问里的固定开头。
 *
 * 它只写给模型看（模型要靠这些相对路径 read_file 读图），界面改用图片网格显示，
 * 所以发送时拼文案、气泡渲染时裁掉，两处必须用同一个字面量，不能各写一遍。
 */
internal const val IMAGE_PATHS_MARKER = "【图片·已上传到当前工作区】"

/** 附件路径说明这段文字在提问里的固定开头：发送时拼文案、回滚时反向解析，同样只留一个出处。 */
internal const val ATTACHMENT_PATHS_MARKER = "【附件·已上传到当前工作区】"

/**
 * 批准执行时把计划正文（标题 / 步骤 / 备注）粘进提问的固定开头。
 *
 * 正文必须带给模型：不然它只会按自己最后写的那份做，选中的是哪份就丢了；
 * 但**不该出现在用户气泡里**：计划页上已经能逐条看，聊天里再糊一屏只会把这条气泡
 * 撑成一个要上下滑的模块。
 * 所以发送时拼、气泡渲染时按这个标记整段裁掉。
 */
internal const val PLAN_DETAIL_MARKER = "【计划原文·仅供模型执行】"

// 下面几个常量放文件顶层而不是 companion：它们只被 ChatViewModel 的正文用到，
// 顶层声明的作用域最直白（不必再猜「这个名字在哪个 object 里」）。

/**
 * 对话页状态容器（M4：接上 Agent 循环）。
 *
 * 故意不继承 ViewModel（工程里没有 lifecycle-viewmodel 依赖）：
 * 由 Compose 的 remember 持有实例，由 rememberCoroutineScope 驱动循环。
 * 所有可变字段都是 Compose 快照状态，UI 读它们就会自动重组。
 */
class ChatViewModel(context: Context) : AgentUi {

    /**
     * 单轮工具调用次数的上限：对齐 DSH 的「一次提问内尽量跑完」。
     */
    companion object {
        /**
         * 单轮最多跑多少次工具。
         *
         * 原来是 10，用户实测「每十次工具调用就断一次、必须回『继续』」，实际用起来像被打断。
         * 抬到 64：一次提问里装得下装环境、编译、批量改文件这种长活；真死循环还有「停止」按钮兜底。
         */
        private const val MAX_ROUNDS = 64
    }

    private val appContext: Context = context.applicationContext

    /** 用户自填的 Key；没填过时 ApiConfig 会回落到源码里的默认 Key。 */
    var apiKey by mutableStateOf(ApiConfig.loadApiKey(appContext))
        private set

    /** 当前模型（deepseek-flash / deepseek-v4-pro / 别家的模型 id）；改了立刻重建请求链路。 */
    var model by mutableStateOf(ApiConfig.loadModel(appContext))
        private set

    /** 全部提供商；多提供商设置页直接读它，[currentProviderId] 指向其中一条。 */
    var providers by mutableStateOf<List<ProviderConfig>>(ProviderStore.load(appContext))
        private set

    /** 当前提供商 id；选中态落在 app_prefs 里，重启后还停在这一条。 */
    var currentProviderId by mutableStateOf(ProviderStore.currentId(appContext))
        private set

    // 端点与 Key 都取自当前 provider：一个 provider 都没配时 ProviderStore 会自动建一条内置
    // DeepSeek（老用户的 Key / 端点 / 模型都在里面），所以默认行为与单提供商版本完全一致
    private var provider = DeepSeekProvider(
        apiKey = ApiConfig.loadApiKey(appContext),
        baseUrl = ApiConfig.loadBaseUrl(appContext)
    )

    private val registry = ToolRegistry()

    /** 工具表对外只读：包管理页（MCP 启停）要拿它去注册/注销，别的地方不要改。 */
    val toolRegistry: ToolRegistry get() = registry

    /** 上下文压缩策略（摘要式滚动压缩）：只影响发给模型的请求视图，界面历史不动。 */
    val contextPolicy: ContextPolicy get() = context.contextPolicy

    init {
        // 调试用线上请求落盘：pref `wire_dump`=true 时，每条请求 JSON 存到 appdata/wire/（只留最近 12 份）。
        // 排查「为什么没命中前缀缓存」时打开它，离线 diff 两条请求即可定位第一处差异。
        val prefs = appContext.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        WireDump.dir = if (prefs.getBoolean("wire_dump", false)) {
            java.io.File(com.zmf.agent.core.AppPaths.dataDir(appContext), "wire")
        } else {
            null
        }
    }

    /** 上下文快照（估算 tokens / 水位 / 覆盖条数）；由 [refreshContext] 算，界面只读。 */
    val contextSnapshot: ContextSnapshot get() = context.contextSnapshot

    /** 当前会话摘要文本；空串 = 没有摘要，请求视图发全量。直接读会话表，落盘即生效。 */
    val contextSummary: String get() = context.contextSummary

    /**
     * 手动压缩用的作用域：send 用的是界面传进来的 scope，[summarizeNow] 没有 scope 可拿，
     * 只能自己开一个（Main 调度器，顺带保证状态写入都在主线程）。
     */
    private val summaryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var loop = AgentLoop(
        provider = provider,
        registry = registry,
        model = model,
        maxRounds = MAX_ROUNDS,
        contextPolicy = { contextPolicy },
        contextState = { summaryState() },
        promptProvider = { promptInput() }
    )

    /** 对话全量消息（user / assistant / tool），循环直接往里追加。 */
    val messages: SnapshotStateList<ChatMessage> = mutableStateListOf()

    /** tool_call_id -> 工具是否成功；只用于界面给工具卡上色，不参与请求。 */
    val toolOutcomes: Map<String, Boolean> get() = toolCalls.outcomes

    /** 当前会话的待办清单，实现与依赖装配见 [TodoController]；界面仍通过 [todos] 读。 */
    private val todoStore = TodoController(
        appContext = appContext,
        scope = summaryScope,
        currentSessionId = { currentSessionId }
    )

    /** 当前会话的待办清单（模型用 todo_write 写、TodoStore 落盘）；换会话与每轮结束后都重读一次。 */
    val todos: List<Todo> get() = todoStore.todos

    /** 备份 / 恢复的状态与动作，实现与依赖装配见 [BackupController]；下面成员保持原有调用口。 */
    private val backup = BackupController(appContext = appContext)

    /** 会话与工作区的状态与动作都在这里；下面四个转发口就是原来的四个属性。 */
    private val sessionStore: SessionController = SessionController(
        appContext = appContext,
        messages = messages,
        scope = summaryScope,
        isStreaming = { isStreaming },
        clearAttachments = { composer.clearAttachments() },
        pendingUploadPaths = {
            composer.attachments.map { it.relPath } + composer.pendingImages.map { it.relPath }
        },
        clearToolOutcomes = { toolCalls.clear() },
        clearTodos = { todoStore.clearTodos() },
        reloadTodos = { reloadTodos() },
        resetStats = { resetStats() },
        reportError = { lastError = it },
        persistenceBlocked = { needsRestart }
    )

    /** 全部会话，按 updatedAt 降序（第一个就是最近用过的）；侧边栏直接用。 */
    val sessionList: List<ChatSession> get() = sessionStore.sessions

    /** 当前会话 id；空串只可能出现在 init 跑完之前。 */
    val currentSessionId: String get() = sessionStore.currentSessionId

    /** 全部工作区：默认工作区 + 用户自建的，侧边栏按它分组（至少保证有一个）。 */
    val workspaceList: List<WorkSpace> get() = sessionStore.workspaces

    /** 当前工作区 id；null 表示「未分类」（不属于任何工作区的会话都挂在这里）。 */
    val currentWorkspaceId: String? get() = sessionStore.currentWorkspaceId

    var thinkingEnabled by mutableStateOf(ApiConfig.loadThinkingEnabled(appContext))
        private set

    /** 思考强度：low / high / max。长按「思考」chip 可改。 */
    var thinkingEffort by mutableStateOf(ApiConfig.loadThinkingEffort(appContext))
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    /** 系统当前是否有可用网络（监听默认网络回调，不需要主动发请求）。 */
    var networkAvailable by mutableStateOf(true)
        private set

    /** 上一次模型请求的结果：null = 还没请求过，true = 成功，false = 失败。 */
    var lastRequestOk by mutableStateOf<Boolean?>(null)
        private set

    /**
     * 最近一次请求的用量文本。
     *
     * 气泡里已经把「输入 / 输出」内嵌进去了，这条不再画在列表外面（用户要的就是外面少一行）；
     * 字段留着兼容，别再加渲染。
     */
    val usageText: String? get() = context.usageText

    /**
     * 统计页的快照（轮数 / 步数 / token / 上下文水位）。
     *
     * 只有页面需要时才刷新：轮数与水位要遍历消息，不能每帧算，由 [refreshStats] 在进页面时算一次。
     */
    val stats: UsageStats get() = context.stats

    var permissionLevel by mutableStateOf(PermissionStore.load(appContext))
        private set

    /** 可用 Skill 的名字+描述缓存（给系统提示词的 Skill 段用；装/删 Skill 后调 refreshSkillCatalog）。 */
    private var skillCatalog: List<Pair<String, String>> = emptyList()

    /** 可用 Skill 的名字，只给输入区的「/」斜杠补全用；随 [refreshSkillCatalog] 一起更新。 */
    var skillNames by mutableStateOf<List<String>>(emptyList())
        private set

    /** 计划模式 / 计划 / 目标 / 斜杠命令，实现与依赖装配见 [PlanGoalController]。 */
    private val planGoal: PlanGoalController = PlanGoalController(
        appContext = appContext,
        currentSessionId = { currentSessionId },
        isStreaming = { isStreaming },
        setActivity = { activityText = it },
        reportError = { lastError = it },
        applyPermission = { setPermission(it) },
        summarizeNow = { summarizeNow() },
        sendNow = { text, programmatic -> send(text, summaryScope, programmatic) }
    )

    /** 计划模式（全局开关）；只读转发。 */
    val planMode: Boolean get() = planGoal.planMode

    /** 当前会话的计划列表；只读转发。 */
    val plans: List<PlanDoc> get() = planGoal.plans

    /** 计划预览里选中的那一份；只读转发。 */
    val selectedPlanId: String? get() = planGoal.selectedPlanId

    /** 当前会话的长期目标；只读转发。 */
    val goal: Goal? get() = planGoal.goal

    /**
     * 输入区状态与动作（草稿、附件、图片、占用文案），实现与依赖装配见 [ComposerState]。
     *
     * 下面几个成员保持原有调用口，界面与发送流程无需改动。
     */
    private val composer = ComposerState(
        appContext = appContext,
        workspaceRoot = { currentWorkspaceRoot() },
        sessionsReferencingUploads = { sessionsOfCurrentWorkspace() },
        scope = summaryScope,
        reportError = { lastError = it }
    )

    /** 输入框内容（受控状态）。回滚时把被删掉的那条提问填回这里，光标落在末尾。 */
    val inputText: TextFieldValue get() = composer.inputText

    /** 待发送附件：选中的文件已经复制进当前工作区，点发送时才拼进提问内容。 */
    val attachments: SnapshotStateList<PendingFile> get() = composer.attachments

    /**
     * 待发送图片：单独一条队列，因为图片除了路径还要读盘编码成 data URL 直接发给模型。
     *
     * relPath 仍是工作区相对路径：界面靠它解码缩略图，会话落盘也只存这个路径。
     */
    val pendingImages: SnapshotStateList<PendingFile> get() = composer.pendingImages

    /**
     * 上下文与用量（策略、水位、摘要压缩、统计、时间账、本轮 usage 计数），实现见 [ContextState]。
     *
     * 下面若干成员保持原有调用口，界面与发送流程无需改动。
     */
    private val context = ContextState(
        appContext = appContext,
        scope = summaryScope,
        messages = messages,
        currentSessionId = { currentSessionId },
        sessions = { sessionList },
        writeSessions = { sessionStore.replaceSessions(it) },
        isStreaming = { isStreaming },
        setActivity = { activityText = it },
        modelName = { model },
        provider = { provider },
        registry = { registry },
        promptInput = { promptInput() },
        workspaceRoot = { currentWorkspaceRoot() },
        usageModel = { currentUsageModel() },
        persistCurrent = { persistCurrent() },
        persistAll = { persistAll() },
        reloadSessionDocs = { reloadSessionDocs() }
    )

    /** 当前在干什么：「第 2 轮」「调用 read_file」之类，空闲时为 null。 */
    var activityText by mutableStateOf<String?>(null)
        private set

    /** 工具调用展示：成败表与工具起止提示；界面仍通过 [toolOutcomes] 读。 */
    private val toolCalls = ToolCallController(
        setActivity = { activityText = it },
        reloadGoal = { reloadGoal() }
    )

    /**
     * 发送与流式回合（发送/排队/插话/续跑/回滚/删改/停止），实现与依赖装配见 [SendController]。
     *
     * 下面几个成员保持原有调用口，界面与其它模块无需改动。
     */
    private val sender: SendController = SendController(
        appContext = appContext,
        messages = messages,
        context = this.context,
        session = sessionStore,
        composer = composer,
        scope = summaryScope,
        loop = { loop },
        agentUi = { this },
        slashCommand = { handleSlashCommand(it) },
        restartBlocked = { needsRestart },
        readError = { lastError },
        reportError = { lastError = it },
        setActivity = { activityText = it },
        thinkingOn = { thinkingEnabled },
        thinkingEffortProvider = { thinkingEffort },
        permissionProvider = { permissionLevel },
        planModeOn = { planMode },
        goalNow = { goal },
        setGoal = { planGoal.applyGoal(it) },
        reloadTodos = { reloadTodos() },
        reloadPlans = { reloadPlans() },
        dropFinishedTodos = { dropFinishedTodosBeforeNewRound() },
        abandonPendingPlans = { abandonPendingPlansBeforeNewTurn() }
    )

    /** 是否正在生成回复；只读转发。 */
    val isStreaming: Boolean get() = sender.isStreaming

    /** 繁忙时的发送行为；只读转发。 */
    val busyMode: BusySendMode get() = sender.busyMode

    /** 排队待发的消息；只读转发（界面只读，改动都走 [editQueued] / [deleteQueued]）。 */
    val pendingQueue: SnapshotStateList<QueuedMessage> get() = sender.pendingQueue

    /** 上下文分段估算（系统提示词 / 工具表 / user / assistant / 工具结果）；由 [refreshContext] 顺带刷新。 */
    val contextBreakdown: Breakdown get() = context.contextBreakdown

    /** 当前会话的摘要/压缩事件（时间正序，只读展示）；换会话与每次压缩动作后刷新。 */
    val contextEventList: List<ContextEvent> get() = context.contextEventList

    /** 本会话的时间账；换会话、收到 usage、回复收尾都会从会话记录重新读回。 */
    val sessionTiming: TimingSnapshot get() = context.sessionTiming

    /** 洞察页的提示词长度趋势（最近若干次回复的输入 tokens）；由 [refreshInsight] 刷新。 */
    val insightTrend: List<Int> get() = context.insightTrend

    /** 日志导出与记忆库的状态与动作，实现与依赖装配见 [InsightController]；下面成员保持原有调用口。 */
    private val insight = InsightController(
        appContext = appContext,
        scope = summaryScope,
        workspaceRoot = { currentWorkspaceRoot() }
    )

    /** 日志导出的状态文本（成功/失败各一句）；null = 没导过。 */
    val logExportStatus: String? get() = insight.logExportStatus

    /** 记忆内核对外的状态（字段由 MemoryAdmin 定义）；由 [refreshMemory] 刷新。 */
    val memoryState: MemoryState get() = insight.memoryState

    /** 向量模型开关（记忆页「向量模型」按钮）：只影响内核是否启用稠密通道，默认关。 */
    val memoryVectorEnabled: Boolean get() = insight.memoryVectorEnabled

    /** 向量模型开关下方的临时状态：检测、安装、结果；null = 显示默认注释。 */
    val memoryVectorStatus: String? get() = insight.memoryVectorStatus

    /** 状态文案是否为失败结果，界面据此换错误色。 */
    val memoryVectorFailed: Boolean get() = insight.memoryVectorFailed

    /** 依赖检测或安装正在进行。 */
    val memoryVectorBusy: Boolean get() = insight.memoryVectorBusy

    // ---- 状态行的三个派生值（口径见 ContextState）----

    /** 状态行显示的轮数：界面历史里用户问过几次。 */
    val statusRounds: Int get() = context.statusRounds

    /** 状态行显示的步数：本会话已提交的步数 + 本次回复跑过的步数。 */
    val statusSteps: Int get() = context.statusSteps

    /** 本会话的「活跃时长」：第一条与最后一条消息的时间差。 */
    val statusActiveMillis: Long get() = context.statusActiveMillis

    init {
        FileLog.init(appContext)
        // 安全审计日志（删除/截断/禁区写/权限切换都记这里）：与 FileLog 分开两个文件，
        // 用户排查「是不是模型删错了」时不用在几万行运行日志里翻
        AuditLog.init(appContext)
        // 沙箱回归：启动时跑一遍纯逻辑判定用例，把「闸门是否还认得根/工作区/系统目录」钉住。
        // 只写日志不阻断启动：判定错了要能看见，但用户不该因为一条断言失败就打不开 App
        runCatching { SandboxRegression.run(appContext) }
            .onFailure { FileLog.log("sandbox-reg", "回归自检异常：" + (it.message ?: it.javaClass.simpleName)) }
        // 全局总账先读回来：stats.json 坏掉 / 缺字段时里面自己回落到 0，不会影响会话表
        UsageLedger.load(appContext)
        // 工作区表与会话表读回来，决定当前会话并载入它的消息（见 SessionController.loadInitial）
        sessionStore.loadInitial()
        // 顺带扫一次 Skill 目录：提示词里的 available_skills 段要用，不能等到第一次请求再读盘
        refreshSkillCatalog()
        // 顺手清掉 7 天前的 spill 落盘（后台做，失败也无所谓，不阻塞启动）
        summaryScope.launch {
            runCatching { withContext(Dispatchers.IO) { SpillStore.cleanup(appContext) } }
        }
        // 回收站淘汰（7 天 / 单池 512 MB FIFO）：同样后台跑，不经工具层也不需要令牌。
        // 这件事的主体是 App 自己，不是 Agent
        summaryScope.launch {
            runCatching {
                val removed = withContext(Dispatchers.IO) { TrashStore.sweep(appContext) }
                if (removed > 0) FileLog.log("trash", "启动淘汰清掉 " + removed + " 条")
            }.onFailure { FileLog.log("trash", "启动淘汰失败：" + (it.message ?: it.javaClass.simpleName)) }
        }
        // MCP：启动时把已启用服务接上，工具注册进来之后下一轮请求就能看到
        summaryScope.launch {
            runCatching { McpManager.reload(appContext, registry) }
                .onFailure { FileLog.log("mcp", "启动加载失败：" + (it.message ?: it.javaClass.simpleName)) }
        }
        // 子智能体的 provider 与工具表只能从「外面」注入：工具接口 invoke(args, ctx) 里拿不到这两样。
        // 没注入时 subagent 工具会明确报「子智能体未初始化」，不会假装成功
        SubAgentDeps.provider = { provider }
        SubAgentDeps.registry = { registry }
        // 待办清单跟着会话走：先把当前会话的清单读回来，界面一进来就是对的
        reloadTodos()
        FileLog.log("chat", "初始化完成，权限=" + permissionLevel.displayName())
        FileLog.log(
            "session",
            "载入 " + sessionList.size + " 个会话、" + workspaceList.size + " 个工作区，当前=" + currentSessionId +
                "，工作区=" + (currentWorkspaceId ?: "未分类") + "，消息 " + messages.size + " 条"
        )
        // 启动时先算一次水位：进来的第一眼顶部状态就是对的（有历史会话时尤其重要）
        refreshContext()
        // 顶栏状态点要反映真实网络：监听默认网络回调即可，取不到 manager 就当作有网
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { networkAvailable = true }
                override fun onLost(network: Network) { networkAvailable = false }
            })
        }.onFailure { FileLog.log("net", "注册网络回调失败：" + it.message) }
        // 最后按「已经载入的那条会话」重算一次统计：不跑这一步，冷启动首屏的轮数/步数是 0，
        // 因为 stats 初始是空快照，要靠 resetStats 从会话记录里把累计读回来
        resetStats()
    }

    /** 推理开关的唯一写法：改状态并立即落盘，下一次请求就按新值走。 */
    fun toggleThinking() {
        thinkingEnabled = !thinkingEnabled
        ApiConfig.saveThinkingEnabled(appContext, thinkingEnabled)
    }

    /** 注意：名字不能叫 setThinkingEffort，会和 thinkingEffort 的属性 setter 撞 JVM 签名。 */
    fun chooseThinkingEffort(effort: String) {
        if (effort == thinkingEffort) return
        thinkingEffort = effort
        ApiConfig.saveThinkingEffort(appContext, effort)
    }

    // ---- 上下文摘要（滚动压缩） ----

    /** 设置页改策略：夹到合法范围、落盘、立刻重算快照（口径见 [ContextState.applyContextPolicy]）。 */
    fun applyContextPolicy(p: ContextPolicy) = context.applyContextPolicy(p)

    /** 重算快照：估算真正会发出去的那份请求视图（口径见 [ContextState.refreshContext]）。 */
    fun refreshContext() = context.refreshContext()

    /** 重算上下文分段估算（口径见 [ContextState.refreshContextBreakdown]）。 */
    fun refreshContextBreakdown() = context.refreshContextBreakdown()

    /** 手动立即压缩；流式回复中不执行，结果通过 [onDone] 回主线程。 */
    fun summarizeNow(onDone: (Boolean) -> Unit = {}, force: Boolean = false) =
        context.summarizeNow(onDone, force)

    /** 清除本会话摘要：请求视图恢复全量（见 [ContextState.clearContextSummary]）。 */
    fun clearContextSummary() = context.clearContextSummary()

    /** 给 AgentLoop 的摘要视图信息：摘要文本 + 分界 + 兜底标记。 */
    private fun summaryState(): ContextSummaryState = context.summaryState()

    /** 发送前的双触发闸门：比例或新增轮数到达阈值就返回 true。 */
    private fun shouldSummarize(): Boolean = context.shouldSummarize()

    /** 跑一次摘要，成功写回会话三字段并落盘（见 [ContextState.runSummary]）。 */
    private suspend fun runSummary(): Boolean = context.runSummary()

    /** AgentLoop 的溢出恢复回调：压一次上下文，成功就让它重发同一轮。 */
    private suspend fun onOverflowCompaction(): Boolean = context.onOverflowCompaction()

    // ---- API Key 与模型 ----

    /** 保存并立即生效（重建 provider 与 loop）。 */
    fun applyApiKey(newKey: String) {
        val trimmed = newKey.trim()
        if (trimmed.isEmpty() || trimmed == apiKey) return
        apiKey = trimmed
        ApiConfig.saveApiKey(appContext, trimmed)
        // Key 写在当前 provider 里：设置页的列表也要跟着变
        providers = ProviderStore.load(appContext)
        rebuildClient()
        FileLog.log("api", "已更新 API Key（长度 " + trimmed.length + "）")
    }

    /** 切换模型：落盘并重建请求链路；值没变时直接返回，避免无谓的重建。 */
    fun applyModel(newModel: String) {
        if (newModel == model) return
        model = newModel
        ApiConfig.saveModel(appContext, newModel)
        // 用 rebuildPipeline 而不是 rebuildClient：后者会把模型重新读一遍，
        // 而「记下的模型不在 provider 的模型列表里就回落第一个」会把刚选的值悄悄改掉
        rebuildPipeline()
        FileLog.log("api", "模型切到 " + newModel)
    }

    /**
     * 保存当前 provider 的对话端点并立刻生效（重建 provider 与 loop）。
     *
     * 空串对内置 DeepSeek 等于「回落官方地址」（ApiConfig 内部处理），别家则保持空、由 provider 报错提示。
     */
    fun applyBaseUrl(url: String) {
        val before = ApiConfig.loadBaseUrl(appContext)
        ApiConfig.saveBaseUrl(appContext, url)
        // 端点是当前 provider 的一部分：设置页的列表也要跟着变
        providers = ProviderStore.load(appContext)
        rebuildClient()
        FileLog.log("api", "对话端点：" + before + " → " + ApiConfig.loadBaseUrl(appContext))
    }

    // ---- 多提供商 ----

    /**
     * 切换当前提供商：落盘 + 重建请求链路 + 刷新列表状态。
     *
     * Key / 端点 / 模型全都跟着 provider 走，所以不在这里单独读它们：[rebuildClient] 统一读。
     */
    fun selectProvider(id: String) {
        if (providers.none { it.id == id }) return
        if (id == currentProviderId) return
        ProviderStore.select(appContext, id)
        providers = ProviderStore.load(appContext)
        rebuildClient()
        FileLog.log("api", "切换提供商 " + id + "（模型=" + model + "）")
    }

    /** 新增或整条替换一个提供商；改的正好是当前这条时才需要重建请求链路。 */
    fun saveProvider(cfg: ProviderConfig) {
        ProviderStore.upsert(appContext, cfg)
        providers = ProviderStore.load(appContext)
        if (cfg.id == currentProviderId) rebuildClient()
        FileLog.log("api", "保存提供商 " + cfg.id + "（" + cfg.name + "）")
    }

    /** 删除提供商；最后一个删不掉（[ProviderStore.remove] 会拒绝，这里什么都不做）。 */
    fun deleteProvider(id: String) {
        if (!ProviderStore.remove(appContext, id)) return
        providers = ProviderStore.load(appContext)
        // 删的是当前 provider：ProviderStore 已把选中态挪到第一个，这里跟着切过去重建
        if (ProviderStore.currentId(appContext) != currentProviderId) rebuildClient()
        FileLog.log("api", "删除提供商 " + id + "，当前=" + currentProviderId)
    }

    /** 换 Key / 换端点 / 换提供商后重建请求链路：Key、端点、模型都重新从当前 provider 读一遍。 */
    private fun rebuildClient() {
        val cfg = ProviderStore.current(appContext)
        currentProviderId = cfg.id
        apiKey = ApiConfig.loadApiKey(appContext)
        model = ApiConfig.loadModel(appContext)
        rebuildPipeline()
    }

    /**
     * 只换请求对象、不重新读配置。
     *
     * provider 与 loop 的 Key / 端点 / 模型都是构造时固定的，配置一变就必须换一套新对象；
     * 单独拆出来是给「换模型」用的（见 [applyModel]）。
     */
    private fun rebuildPipeline() {
        provider = DeepSeekProvider(apiKey = apiKey, baseUrl = ApiConfig.loadBaseUrl(appContext))
        // provider 是新对象：子智能体必须拿到新的那个，否则它还在用旧 Key / 旧端点
        SubAgentDeps.provider = { provider }
        SubAgentDeps.registry = { registry }
        loop = AgentLoop(
            provider = provider,
            registry = registry,
            model = model,
            maxRounds = MAX_ROUNDS,
            contextPolicy = { contextPolicy },
            contextState = { summaryState() },
            promptProvider = { promptInput() }
        )
    }

    /**
     * 系统提示词的输入：每次请求前现取，所以换工作区 / 切权限档 / 改附加指令都立刻生效。
     *
     * Skill 目录走缓存：解析 SKILL.md 要读文件，不能每轮请求都在主线程读盘；
     * 装了新 Skill 之后调 [refreshSkillCatalog] 刷一次即可。
     */
    private fun promptInput(): PromptInput {
        val space = workspaceList.firstOrNull { it.id == currentWorkspaceId }
            ?: WorkSpaceStore.defaultSpace(appContext)
        val skills = if (PromptStore.loadSkillsEnabled(appContext)) skillCatalog else emptyList()
        return PromptInput(
            model = model,
            workspacePath = rootOf(space).absolutePath,
            permission = permissionLevel,
            // 计划模式在这里现取：AgentLoop 每次请求都会新调一次本函数，
            // 于是「思考中途打开计划」从下一轮请求起就生效，不用重建循环
            planMode = planMode,
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            skills = skills,
            extraInstructions = PromptStore.loadExtra(appContext),
            systemOverride = PromptStore.loadOverride(appContext),
            // 目标只进尾部快照（不进 system）：目标变了不该让整段前缀作废
            goalText = goalText()
        )
    }

    /** 当前目标的一句话：写给快照用，没有目标返回空串（那一行就不出）。 */
    private fun goalText(): String {
        val current = goal ?: return ""
        return current.objective.take(200) + "（" + current.phase.label() + "，第 " + current.rounds +
            " 轮 / 上限 " + MAX_GOAL_ROUNDS + " 轮）"
    }

    /** 重新扫描 appdata/skills（导入/删除 Skill 之后调）。 */
    fun refreshSkillCatalog() {
        skillCatalog = runCatching { SkillStore.catalog(appContext) }.getOrDefault(emptyList())
        // 斜杠补全只认名字：在这里从同一份缓存派生，装/删 Skill 后不会出现「提示词里有、补全里没有」
        skillNames = skillCatalog.map { it.first }
        FileLog.log("skill", "可用 Skill " + skillCatalog.size + " 个")
    }

    /** 切换权限档：落盘并写一条审计记录；档位没变时不做任何事。 */
    fun setPermission(level: PermissionLevel) {
        if (level == permissionLevel) return
        val from = permissionLevel
        permissionLevel = level
        PermissionStore.save(appContext, level)
        FileLog.log("permission", "权限档切到 " + level.displayName())
        // 档位切换改的是「Agent 能碰多远」，属于安全相关状态变更：审计日志必须留一条
        AuditLog.record(
            appContext, "permission",
            "from" to from.displayName(),
            "to" to level.displayName(),
            "session" to currentSessionId
        )
    }

    // ---- 计划模式 / 计划 / 目标 / 斜杠命令（实现见 PlanGoalController.kt，这里只留对外的调用口）----

    /** 面板里点「计划」：切换并落盘。 */
    fun togglePlanMode() = planGoal.togglePlanMode()

    /** 斜杠命令入口：true = 这句话已经被命令吃掉，不要再当普通消息发出去。 */
    fun handleSlashCommand(text: String): Boolean = planGoal.handleSlashCommand(text)

    /** 重读当前会话的计划列表（流结束与进计划页时各调一次）。 */
    fun reloadPlans() = planGoal.reloadPlans()

    /** 新一轮对话开始前，把还悬着待确认的计划标成废弃。 */
    private fun abandonPendingPlansBeforeNewTurn() = planGoal.abandonPendingPlansBeforeNewTurn()

    /** 重读当前会话的目标。 */
    fun reloadGoal() = planGoal.reloadGoal()

    /** 换会话路径统一调这一个：两份文档都跟当前会话绑定。 */
    private fun reloadSessionDocs() = planGoal.reloadSessionDocs()

    /** 计划预览切换选中项。 */
    fun selectPlan(id: String) = planGoal.selectPlan(id)

    /** 删除一份计划。 */
    fun deletePlan(id: String) = planGoal.deletePlan(id)

    /** 「按计划开始执行」：关计划模式，再把所选计划连正文当成一次普通发送。 */
    fun approvePlan(planId: String) = planGoal.approvePlan(planId)

    /** 「继续修改计划」：只把计划留在页面上，不替用户发消息。 */
    fun continuePlanEdit(planId: String) = planGoal.continuePlanEdit(planId)

    /** 暂停目标（/goal pause）。 */
    fun pauseGoal() = planGoal.pauseGoal()

    /** 继续目标（/goal resume）。 */
    fun resumeGoal() = planGoal.resumeGoal()

    /** 改写目标正文（/goal edit <文本>）。 */
    fun editGoal(text: String) = planGoal.editGoal(text)

    /** 删除当前会话的目标。 */
    fun clearGoal() = planGoal.clearGoal()

    /** 把输入框的内容（含光标位置）同步进状态；不触发任何发送或落盘。 */
    fun updateInput(text: TextFieldValue) = composer.updateInput(text)

    /** 清掉顶栏的错误提示，只改界面状态。 */
    fun clearError() {
        lastError = null
    }

    // ---- 附件（状态与实现见 ComposerState.kt，这里只留对外的调用口）----

    /** 当前工作区 uploads 的占用文本（含未引用部分）；null = 还没统计。 */
    val attachmentUsageText: String? get() = composer.attachmentUsageText

    /** 选中的文件复制进当前工作区；IO 线程做，结果回主线程。 */
    fun attachFile(uri: Uri, scope: CoroutineScope) = composer.attachFile(uri, scope)

    /** 从待发列表里移除一个普通附件；下标越界时忽略。 */
    fun detachFile(index: Int) = composer.detachFile(index)

    /** 清空全部待发普通附件（图片有自己的列表，不受影响）。 */
    fun clearAttachments() = composer.clearAttachments()

    /** 从待发图片里移除一张；下标越界时忽略。 */
    fun detachImage(index: Int) = composer.detachImage(index)

    /** 清空全部待发图片（普通附件不受影响）。 */
    fun clearPendingImages() = composer.clearPendingImages()

    /** 设置页进入时刷一次占用：IO 里扫 uploads 目录，回来只更新一句文本。 */
    fun refreshAttachmentUsage() = composer.refreshAttachmentUsage()

    /** 清理当前工作区里没有被任何会话引用的附件（设置页「清理未引用附件」）。 */
    fun cleanOrphanImages(onDone: (String) -> Unit) = composer.cleanOrphanImages(onDone)

    /**
     * 给一个已结束的子智能体追加一条消息，接着它自己的上下文再跑一次。
     *
     * 界面只在子智能体详情页调它；依赖与 [send] 同源（provider / 工具表 / 当前权限档 / 当前工作区）。
     */
    fun continueSubAgent(id: String, text: String, scope: CoroutineScope) {
        if (id.isBlank() || text.isBlank()) return
        scope.launch {
            val outcome = SubAgentRunner.resume(
                context = appContext,
                id = id,
                text = text,
                provider = provider,
                tools = registry,
                permission = permissionLevel,
                workspaceRoot = currentWorkspaceRoot(),
                sessionId = currentSessionId
            )
            // 不存在 / 正在运行 / 并发闸满这几种不会进记录表，原因只能在这里回显给用户
            if (!outcome.ok) lastError = outcome.answer.removePrefix(SubAgentRunner.FAIL_PREFIX)
            FileLog.log("subagent", "续聊 " + id + " 结果=" + outcome.ok)
        }
    }

    // ---- 工作区目录（实现见 SessionController.kt，这里只留调用口）----

    /** 当前工作区目录：未分类（或找不到）时回落到默认工作区，和 send / attachFile 完全同一口径。 */
    private fun currentWorkspaceRoot(): File = sessionStore.currentWorkspaceRoot()

    /** 工作区根目录：优先用户自定义目录，读不到就退回私有目录（见 SessionController.rootOf）。 */
    private fun rootOf(space: WorkSpace): File = sessionStore.rootOf(space)

    /** 可能把附件写进当前工作区目录的会话（含未分类与输入框里还没发出去的附件）。 */
    private fun sessionsOfCurrentWorkspace(): List<ChatSession> = sessionStore.sessionsOfCurrentWorkspace()

    /**
     * 重读当前会话的待办清单（口径见 [TodoController.reloadTodos]）。
     */
    private fun reloadTodos() = todoStore.reloadTodos()

    /**
     * 新一轮提问前清掉上一轮「全部完成」的清单（口径见 [TodoController.dropFinishedTodosBeforeNewRound]）。
     */
    private fun dropFinishedTodosBeforeNewRound() = todoStore.dropFinishedTodosBeforeNewRound()

    // ---- 备份 / 恢复（状态与实现见 BackupController.kt，这里只留对外的调用口）----

    /** 备份正在跑：导出/导入按钮一起禁用。 */
    val backupBusy: Boolean get() = backup.backupBusy

    /** 备份进度文案（「导出中 37%」）；空闲为 null。 */
    val backupStatus: String? get() = backup.backupStatus

    /** 上一次导出 / 导入的结果，非空时在数据页里显示一行。 */
    val backupNotice: String? get() = backup.backupNotice

    /** 导入前的清单摘要；非空时界面弹确认框。 */
    val pendingManifest: BackupManifest? get() = backup.pendingManifest

    /** 导入结果；非空时界面弹报告。 */
    val importReport: ImportReport? get() = backup.importReport

    /** 刚导入完、还没重启：此时发送直接拒绝、落盘全部跳过。 */
    val needsRestart: Boolean get() = backup.needsRestart

    /** 导出到系统里选的 zip 目标。 */
    fun exportData(uri: Uri, options: BackupOptions, scope: CoroutineScope) =
        backup.exportData(uri, options, scope)

    /** 只读备份清单：先给用户看清楚要导入什么，再决定。 */
    fun loadBackupManifest(uri: Uri, scope: CoroutineScope) = backup.loadBackupManifest(uri, scope)

    /** 放弃已解析但还没确认导入的备份：只清待确认状态，不动磁盘数据。 */
    fun cancelImport() = backup.cancelImport()

    /** 关掉导入结果报告；报告本身是界面状态，关掉不影响已导入的数据。 */
    fun dismissImportReport() = backup.dismissImportReport()

    /** 开始导入备份文件。 */
    fun importData(uri: Uri, scope: CoroutineScope) = backup.importData(uri, scope)

    // ---- 发送与流式回合（状态与实现见 SendController.kt，这里只留对外的调用口）----

    /** 发送按钮回调（旧入口）：空闲直接发，繁忙时按 [busyMode] 排队或插话。 */
    fun onSendOrStop(text: String, scope: CoroutineScope) = sender.onSendOrStop(text, scope)

    /** 发消息的唯一入口：空闲直接发，繁忙时按 [busyMode] 排队或插话。 */
    fun sendWhileBusy(text: String, scope: CoroutineScope) = sender.sendWhileBusy(text, scope)

    /** 切换繁忙时的发送行为并落盘（只影响下一次「繁忙时发送」）。 */
    fun applyBusyMode(mode: BusySendMode) = sender.applyBusyMode(mode)

    /** 改排队消息的正文；改成空串等于把它移出队列。 */
    fun editQueued(id: Long, text: String) = sender.editQueued(id, text)

    /** 把某条排队消息移出队列。 */
    fun deleteQueued(id: Long) = sender.deleteQueued(id)

    /** 立即发送队列里的某一条。 */
    fun sendQueuedNow(id: Long, scope: CoroutineScope) = sender.sendQueuedNow(id, scope)

    /**
     * 发一条消息：只做校验与拼 prompt，真正开跑交给 [SendController]。
     *
     * @param programmatic true = 本句由 App 代发（「按计划开始执行」等），不获得高危操作授权资格
     */
    fun send(text: String, scope: CoroutineScope, programmatic: Boolean = false) =
        sender.send(text, scope, programmatic)

    /** 重新生成：把这条用户消息之后的内容全删掉，用同一句话再跑一次。 */
    fun regenerateFrom(userIndex: Int, scope: CoroutineScope) =
        sender.regenerateFrom(userIndex, scope)

    /** 回滚到此处：删掉这条用户消息及之后的所有内容，回到还没提问的状态。 */
    fun rollbackTo(userIndex: Int) = sender.rollbackTo(userIndex)

    /** 删除单条消息：连带清掉它依赖的上下文，然后重算统计并立即落盘。 */
    fun deleteMessage(index: Int) = sender.deleteMessage(index)

    /** 编辑并重发：截断到这条 user 消息之前，用改过的文字重新走一次 [send]。 */
    fun editAndResend(index: Int, text: String, scope: CoroutineScope) =
        sender.editAndResend(index, text, scope)

    /** 用户点「停止」：取消当前流式任务并清掉进行中提示。 */
    fun stop() = sender.stop()
    // ---- 会话与工作区（状态与实现见 SessionController.kt，这里只留对外的调用口）----

    /** 新建会话：当前会话先存回，再插一条空的到最前并切过去；新会话归当前工作区。 */
    fun newSession() = sessionStore.newSession()

    /** 切换会话；id 与当前会话相同时也走一遍「盘上是否更长」的同步。 */
    fun switchSession(id: String) = sessionStore.switchSession(id)

    /** 分叉会话：把当前历史复制成一条新会话，父会话原样不动。 */
    fun forkSession(atIndex: Int? = null) = sessionStore.forkSession(atIndex)

    /** 删除会话：删的是当前会话就在当前工作区里挑下一条，一个都不剩就新建一条空的。 */
    fun deleteSession(id: String) = sessionStore.deleteSession(id)

    /** 重命名会话；标题 trim 后为空就退回「新会话」的空标题。 */
    fun renameSession(id: String, title: String) = sessionStore.renameSession(id, title)

    /** 置顶 / 取消置顶；只改 pinned，不动 updatedAt。 */
    fun pinSession(id: String, pinned: Boolean) = sessionStore.pinSession(id, pinned)

    /** 归档会话；归档的是当前会话时，直接进一个空对话。 */
    fun archiveSession(id: String) = sessionStore.archiveSession(id)

    /** 从归档恢复。 */
    fun unarchiveSession(id: String) = sessionStore.unarchiveSession(id)

    /** 当前工作区里没归档的会话（已按 updatedAt 倒序）。 */
    fun sessionsInCurrentWorkspace(): List<ChatSession> = sessionStore.sessionsInCurrentWorkspace()

    /** 当前工作区里已归档的会话（侧边栏折叠区用）。 */
    fun archivedInCurrentWorkspace(): List<ChatSession> = sessionStore.archivedInCurrentWorkspace()

    /** 切换工作区（null = 未分类）：先存回当前会话，再切选中态，最后载入目标工作区最近的会话。 */
    fun switchWorkspace(id: String?) = sessionStore.switchWorkspace(id)

    /** 改工作区显示名：只动名字，目录名与会话归属都不变。 */
    fun renameWorkspace(id: String, name: String): Boolean = sessionStore.renameWorkspace(id, name)

    /** 新建工作区：名字空白 / 目录不可用返回 false；建完切过去（会顺便建一条空会话）。 */
    fun createWorkspace(name: String, rootPath: String? = null, treeUri: String? = null): Boolean =
        sessionStore.createWorkspace(name, rootPath, treeUri)

    /** 删除工作区：默认工作区不删；目录软删除，会话解绑到未分类。 */
    fun deleteWorkspace(id: String) = sessionStore.deleteWorkspace(id)

    /** 侧边栏/顶栏显示的当前会话名，没起标题就是「新会话」。 */
    fun currentTitle(): String = sessionStore.currentTitle()

    /** 退到后台 / 按返回键时落盘。 */
    fun persistForBackground(reason: String) = sessionStore.persistForBackground(reason)

    /** 从磁盘重读会话列表：以磁盘为准，当前会话取内存里还没落盘的那份。 */
    fun reloadSessionsFromDisk() = sessionStore.reloadSessionsFromDisk()

    /** 把内存里的 messages 写回当前会话那条记录，并把列表重排成 updatedAt 降序。 */
    private fun persistCurrent(override: List<ChatMessage>? = null) = sessionStore.persistCurrent(override)

    /** 落盘检查点：剔掉在飞的那条 assistant 占位（见 SessionController.persistCheckpoint）。 */
    private fun persistCheckpoint(reason: String) = sessionStore.persistCheckpoint(reason)

    private fun persistAll() = sessionStore.persistAll()

    /** 当前会话还没标题时，把这句话前 20 字当标题。 */
    private fun autoTitleIfNeeded(prompt: String) = sessionStore.autoTitleIfNeeded(prompt)

    /** 刷新统计里要遍历消息才得出的那几项（口径见 [ContextState.refreshStats]）。 */
    fun refreshStats() = context.refreshStats()

    /** 换会话后统计、时间账、计划/目标、上下文事件一起跟着换（见 [ContextState.resetStats]）。 */
    private fun resetStats() = context.resetStats()

    /** 把「本次回复步数」加进本会话累计与全局总账，一次回复只加一次。 */
    private fun commitSteps() = context.commitSteps()

    /** 把本次回复的时间账加进会话累计，一次回复只加一次。 */
    private fun commitTiming() = context.commitTiming()

    /** 本轮（从 turnStartIndex 起）有没有工具结果：用它判断模型是不是真的动手了。 */
    private fun turnUsedTools(): Boolean = context.turnUsedTools()

    /** 把本轮累计的 usage 写进本轮最后一条 assistant 消息。 */
    private fun writeTurnUsage() = context.writeTurnUsage()

    /** 全部会话的消息总数，统计页「会话与消息」用。 */
    fun totalMessageCount(): Int = context.totalMessageCount()

    // ---- 洞察 / 日志 / 记忆 ----

    /** 刷新洞察页：上下文水位 + 分段估算 + 全局总账，外加提示词长度趋势。 */
    fun refreshInsight() = context.refreshInsight()

    // ---- 日志 / 记忆（状态与实现见 InsightController.kt，这里只留对外的调用口）----

    /** 把 logs 目录下的日志打成 zip 交给用户选的位置。 */
    fun downloadLogs(uri: Uri) = insight.downloadLogs(uri)

    /** 重扫当前工作区的记忆库，结果写回 [memoryState]。 */
    fun refreshMemory() = insight.refreshMemory()

    /** 检索记忆；空查询直接忽略。 */
    fun searchMemory(q: String) = insight.searchMemory(q)

    /** 跑一次维护扫描（遗忘扫描 + 报出待巩固批次）。 */
    fun runMemoryCycle() = insight.runMemoryCycle()

    /** 导出给人看的记忆视图。 */
    fun exportMemory(outPath: String = "") = insight.exportMemory(outPath)

    /** 记忆页「向量模型」开关。 */
    fun toggleMemoryVector(enabled: Boolean) = insight.toggleMemoryVector(enabled)

    /** 重启记忆页时清掉上一次的临时状态；安装进行中则保留进度。 */
    fun clearMemoryVectorStatus() = insight.clearMemoryVectorStatus()

    // ---- AgentUi ----

    /** 新一轮开始：更新进行中提示，并记录本次回复已跑到的最大轮次。 */
    override fun onRound(round: Int) {
        activityText = "第 " + round + " 轮"
        // 只记「本次回复」的步数口径，会话累计由流结束时的 commitSteps 加一次
        context.noteRound(round)
    }

    /** 工具开始执行：只改进行中提示，不提前写结果。 */
    override fun onToolStarted(call: ToolCall) = toolCalls.onToolStarted(call)

    /** 工具执行结束：记下这一步的成败供工具卡读，goal 工具成功时顺带刷新目标。 */
    override fun onToolFinished(call: ToolCall, result: ToolResult) =
        toolCalls.onToolFinished(call, result)

    /**
     * 本轮用量的「提供方/模型」：写成 `deepseek/deepseek-flash` 这种形态。
     *
     * 用 id 而不是显示名：面板是账目，用户要能对着日志和配置核对；显示名放在设置页看就够了。
     * 服务商已经不在列表里（用户删了）时退回 id 本身，不留空行。
     */
    private fun currentUsageModel(): String {
        val provider = providers.firstOrNull { it.id == currentProviderId }?.id
            ?: currentProviderId.takeIf { it.isNotBlank() }
            ?: "内置"
        val model = model.takeIf { it.isNotBlank() } ?: ApiDefaults.MODEL
        return provider + "/" + model
    }

    /**
     * 收到一次服务端 usage：累加本轮与会话用量、写全局总账，并立刻刷新水位与状态行。
     *
     * 服务端没报的字段（缓存、推理）不累加、也不写成 0，界面上按「未知」显示。
     */
    override fun onUsage(usage: UsageBuckets) {
        context.recordUsage(usage)
        // 收到 usage 说明这一轮请求真的成功了，顶栏状态点可以回绿
        lastRequestOk = true
    }

    /** 请求失败：把错误挂到界面并把顶栏状态点置为失败，直到下一次成功。 */
    override fun onError(message: String) {
        lastError = message
        // 请求失败：顶栏状态点变红，直到下一次成功
        lastRequestOk = false
        FileLog.log("error", message)
    }

    /** 循环收尾回调一次：只累计本次回复的时间账，落会话记录在 [commitTiming] 里做。 */
    override fun onTiming(modelMillis: Long, toolMillis: Long) {
        context.addTurnTiming(modelMillis, toolMillis)
    }

    /**
     * 输出侧账目（首字等待 / 生成时长 / 输出 tokens）：跟 [onTiming] 一起落进会话记录。
     *
     * 落盘而不是只放内存：这两个数决定状态行上的 tok/s 与浮窗里的 TTFT，重启一次就归零
     * 会让人以为数字坏了。落盘的时机就是这里：一次回复一次，不会写得太勤。
     */
    override fun onOutputTiming(timing: OutputTiming) {
        context.recordOutputTiming(timing)
    }
}
