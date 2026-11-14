package com.zmf.agent.feature.chat
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import androidx.compose.ui.text.TextRange
import com.zmf.agent.core.store.StorageMigration
import com.zmf.agent.core.store.DraftStore
import com.zmf.agent.core.model.wireName
import com.zmf.agent.core.model.SessionPreference
import com.zmf.agent.core.model.BusySendMode
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
import com.zmf.agent.capability.loop.AgentLoop
import com.zmf.agent.capability.mcp.McpManager
import com.zmf.agent.capability.loop.AgentUi
import com.zmf.agent.capability.memory.MemoryState
import com.zmf.agent.capability.loop.ContextSummaryState
import com.zmf.agent.capability.loop.OutputTiming
import com.zmf.agent.capability.permission.PermissionStore
import com.zmf.agent.capability.prompt.PromptBuilder
import com.zmf.agent.capability.prompt.PromptInput
import com.zmf.agent.capability.prompt.PromptStore
import com.zmf.agent.capability.skill.SkillStore
import com.zmf.agent.infra.llm.LlmProvider
import com.zmf.agent.infra.llm.ProviderConfig
import com.zmf.agent.infra.llm.UsageBuckets
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.InsightGlobalView
import com.zmf.agent.core.store.InsightRange
import com.zmf.agent.core.store.InsightSessionView
import com.zmf.agent.core.store.PlanDoc
import com.zmf.agent.core.store.Todo
import com.zmf.agent.core.store.UsageLedger
import com.zmf.agent.core.store.WorkspaceIo
import com.zmf.agent.capability.subagent.SubAgentDeps
import com.zmf.agent.capability.subagent.SubAgentRunner
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.infra.backup.BackupManifest
import com.zmf.agent.infra.backup.BackupOptions
import com.zmf.agent.infra.backup.ImportReport
import com.zmf.agent.core.log.AuditLog
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.ToolCall
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
 * 正文必须提供给模型：否则模型只会按自身最后生成的一份执行，所选版本即丢失；
 * 但**不应出现在用户气泡中**：计划页已可逐条查看，聊天中再展示整段只会使该气泡成为需上下滚动的模块。
 * 因此发送时拼接，气泡渲染时按此标记整段裁除。
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
/** 草稿写入的防抖窗口：500 毫秒内的连续输入只写一次。 */
private const val DRAFT_DEBOUNCE_MS = 500L

class ChatViewModel(context: Context) : AgentUi {

    /**
     * 单轮工具调用次数的上限：对齐 DSH 的「一次提问内尽量跑完」。
     */
    companion object {
        /**
         * 单轮最多跑多少次工具。
         *
         * 上限原为 10，用户实测「每十次工具调用即中断一次，必须回复『继续』」，实际体验类似被打断。
         * 提高至 64：一次提问可容纳搭建环境、编译、批量修改文件等长任务；真正死循环仍有「停止」按钮兜底。
         */
        private const val MAX_ROUNDS = 64
    }

    private val appContext: Context = context.applicationContext

    private val registry = ToolRegistry()

    /** 工具表对外只读：包管理页（MCP 启停）需据此注册/注销，其他位置不要修改。 */
    val toolRegistry: ToolRegistry get() = registry

    /** 上下文压缩策略（摘要式滚动压缩）：只影响发给模型的请求视图，界面历史不动。 */
    val contextPolicy: ContextPolicy get() = context.contextPolicy

    /** 模型与提供商配置、请求链路（Key / 模型 / 端点 / 提供商列表），实现见 [LlmConfigController]。 */
    private val llm: LlmConfigController = LlmConfigController(
        appContext = appContext,
        registry = registry,
        maxRounds = MAX_ROUNDS,
        promptInput = { promptInput() },
        contextPolicy = { contextPolicy },
        contextState = { summaryState() },
        syncSubAgentDeps = { syncSubAgentDeps() }
    )

    /** provider 是新对象时子智能体必须拿到新的那个，否则它还在用旧 Key / 旧端点。 */
    private fun syncSubAgentDeps() {
        SubAgentDeps.provider = { llm.currentProvider }
        SubAgentDeps.registry = { registry }
    }

    /** 用户自填的 Key；只读转发。 */
    val apiKey: String get() = llm.apiKey

    /** 当前模型；只读转发。 */
    val model: String get() = llm.model

    /** 全部提供商；只读转发。 */
    val providers: List<ProviderConfig> get() = llm.providers

    /** 当前提供商 id；只读转发。 */
    val currentProviderId: String get() = llm.currentProviderId

    /** 当前请求对象（子智能体与工具侧要用同一个）。 */
    private val provider: LlmProvider get() = llm.currentProvider

    /** Agent 循环：换 Key / 端点 / 模型后由 [LlmConfigController] 重建。 */
    private val loop: AgentLoop get() = llm.loop

    /** 上下文快照（估算 tokens / 水位 / 覆盖条数）；由 [refreshContext] 算，界面只读。 */
    val contextSnapshot: ContextSnapshot get() = context.contextSnapshot

    /** 当前会话摘要文本；空串 = 没有摘要，请求视图发全量。直接读会话表，落盘即生效。 */
    val contextSummary: String get() = context.contextSummary

    /**
     * 手动压缩用的作用域：send 用的是界面传进来的 scope，[summarizeNow] 没有 scope 可拿，
     * 只能自己开一个（Main 调度器，顺带保证状态写入都在主线程）。
     */
    private val summaryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    /** 会话与工作区的状态与动作均在此；以下四个转发入口对应原有的四个属性。 */
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
        persistenceBlocked = { needsRestart },
        // 新建会话时把「此刻的全局默认值」快照进会话字段；此后的修改只影响之后新建的会话
        currentPermission = { PermissionStore.load(appContext) },
        currentEffort = { globalEffortWire() },
        // 标题请求也计费：用量交回上下文层，与会话累计、总账同口径入账
        onTitleUsage = { sessionId, finished ->
            this@ChatViewModel.context.recordTitleUsage(sessionId, finished)
        }
    )

    /** 全部会话，按 updatedAt 降序（第一个就是最近用过的）；侧边栏直接用。 */
    val sessionList: List<ChatSession> get() = sessionStore.sessions

    /** 当前会话 id；空串只可能出现在 init 跑完之前。 */
    val currentSessionId: String get() = sessionStore.currentSessionId

    /** 全部工作区：默认工作区 + 用户自建的，侧边栏按它分组（至少保证有一个）。 */
    val workspaceList: List<WorkSpace> get() = sessionStore.workspaces

    /** 当前工作区 id；null 表示「未分类」（不属于任何工作区的会话都挂在这里）。 */
    val currentWorkspaceId: String? get() = sessionStore.currentWorkspaceId

    /** 当前会话是否开启思考：会话字段为 `off` 即为关闭。 */
    val thinkingEnabled: Boolean get() = sessionStore.sessionEffort() != SessionPreference.EFFORT_OFF

    /** 当前会话的思考强度（low / high / max）；关闭思考时回落到全局默认，仅供面板高亮。 */
    val thinkingEffort: String
        get() = sessionStore.sessionEffort().takeUnless { it == SessionPreference.EFFORT_OFF }
            ?: globalEffortWire()

    var lastError by mutableStateOf<String?>(null)

    /**
     * 启动读盘（工作区表 + 全部会话正文）是否已完成。
     *
     * 为 false 时界面不显示「输入消息开始对话」占位、通知带来的会话 id 也暂不切换：
     * 否则会先短暂呈现空会话，或在会话表尚未载入时丢弃这次切换。
     */
    var startupReady by mutableStateOf(false)
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
     * 气泡中已内嵌「输入 / 输出」，该条不绘制在列表之外（用户需求为减少一行）；
     * 字段保留以兼容旧数据，不新增渲染。
     */
    val usageText: String? get() = context.usageText

    /**
     * 统计页的快照（轮数 / 步数 / token / 上下文水位）。
     *
     * 只有页面需要时才刷新：轮数与水位要遍历消息，不能每帧算，由 [refreshStats] 在进页面时算一次。
     */
    val stats: UsageStats get() = context.stats

    /**
     * 当前会话的权限档。
     *
     * 会话创建时快照全局默认值，此后各会话独立；此处的修改只写会话字段，不回写全局，
     * 因此设置页改档只影响之后新建的会话。旧会话未写入该字段时回落到全局默认。
     */
    val permissionLevel: PermissionLevel get() = sessionStore.sessionPermission()

    /** 可用 Skill 的名字+描述缓存（给系统提示词的 Skill 段用；装/删 Skill 后调 refreshSkillCatalog）。 */
    private var skillCatalog: List<Pair<String, String>> = emptyList()

    /** 缓存对应的内容版本；与 SkillStore.revision 不一致时说明磁盘上的技能已经变了。 */
    private var skillCatalogRevision = -1

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

    /** 当前在干什么：「调用 read_file」「已返回 read_file」之类，空闲时为 null。 */
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

    /**
     * 本轮结束后是否已经排上了下一回合（目标续跑）；只读转发。
     *
     * 收尾到续跑之间有几百毫秒 `isStreaming` 为 false，界面靠它避免在空档里把过程区折起来。
     */
    val chainPending: Boolean get() = sender.chainPending

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

    /** 洞察页的时间账：分叉会话已减掉继承的基线，只报自分支起的真实用量。 */
    val insightTiming: TimingSnapshot get() = context.insightTiming

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

    /** 洞察页显示的步数：与状态行同口径，但分叉会话减掉继承的步数。 */
    val insightSteps: Int get() = context.insightSteps

    /** 本会话的「活跃时长」：第一条与最后一条消息的时间差。 */
    val statusActiveMillis: Long get() = context.statusActiveMillis

    init {
        FileLog.init(appContext)
        // 安全审计日志（删除/截断/禁区写/权限切换都记这里）：与 FileLog 分开两个文件，
        // 用户排查「是不是模型删错了」时不用在几万行运行日志里翻
        AuditLog.init(appContext)
        // 沙箱回归：启动时执行纯逻辑判定用例，确认「闸门是否仍识别根目录、工作区与系统目录」。
        // 仅写日志，不阻断启动：判定错误必须可查，但用户不应因一条断言失败而无法打开 App。
        // 该用例只读目录并写日志，放到后台执行，避免占用冷启动的主线程时间。
        summaryScope.launch {
            runCatching { withContext(Dispatchers.IO) { SandboxRegression.run(appContext) } }
                .onFailure { FileLog.log("sandbox-reg", "回归自检异常：" + (it.message ?: it.javaClass.simpleName)) }
        }
        // 草稿端口必须先装好：下面的 loadInitial 会回填当前会话的草稿
        installDraftPort()
        // 启动读盘：stats.json、旧数据迁移、工作区表与会话表（见 SessionController.loadInitial）。
        // sessions.json 含全部会话正文，整表解析必须留在 IO；读盘结束前 startupReady 为 false，
        // 界面不显示「输入消息开始对话」占位，避免先短暂呈现空会话。
        summaryScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 全局总账先读回来：stats.json 坏掉 / 缺字段时里面自己回落到 0，不会影响会话表
                    UsageLedger.load(appContext)
                    // 旧数据迁移：把「工作区自带目录」升级为「工作区 + 存储单元」，并给旧会话补上
                    // 权限与强度的快照。幂等、失败不覆盖原文件；必须先于读会话表，
                    // 否则界面会先看到一批缺 storageId 的会话
                    runCatching {
                        StorageMigration.migrate(
                            appContext,
                            PermissionStore.load(appContext).wireName(),
                            globalEffortWire()
                        )
                    }.onFailure { FileLog.log("migrate", "旧数据迁移失败：" + it) }
                }
                // 工作区表与会话表读回来，决定当前会话并载入它的消息
                sessionStore.loadInitial()
                // 费用与轮次总账回填：按现存会话逐条计算，仅写统计文件，不影响首帧内容
                runCatching {
                    withContext(Dispatchers.IO) {
                        UsageLedger.backfillCost(
                            appContext,
                            sessionList.flatMap { it.messages.drop(it.inheritedUsage.messages) }
                        )
                        UsageLedger.backfillRounds(appContext, sessionList)
                    }
                }.onFailure { FileLog.log("usage", "总账回填失败：" + (it.message ?: it.javaClass.simpleName)) }
                // 待办清单跟着会话走：当前会话此时已载入，读回来的才是这一条的清单
                reloadTodos()
                FileLog.log("chat", "初始化完成，权限=" + permissionLevel.displayName())
                FileLog.log(
                    "session",
                    "载入 " + sessionList.size + " 个会话、" + workspaceList.size + " 个工作区，当前=" + currentSessionId +
                        "，工作区=" + (currentWorkspaceId ?: "未分类") + "，消息 " + messages.size + " 条"
                )
                // 启动时先算一次水位：进来的第一眼顶部状态就是对的（有历史会话时尤其重要）
                refreshContext()
                // 最后按「已载入的会话」重新计算统计：缺少该步骤时，冷启动首屏的轮数/步数为 0，
                // 因为 stats 初始是空快照，要靠 resetStats 从会话记录里把累计读回来
                resetStats()
            } catch (e: Exception) {
                // 读盘失败也必须放行界面，否则 startupReady 永远为 false，聊天页一直停在空白
                FileLog.log("session", "启动读盘失败：" + (e.message ?: e.javaClass.simpleName))
            } finally {
                startupReady = true
            }
        }
        // 同时扫描一次 Skill 目录：提示词里的 available_skills 段要用；目录遍历与解析在 IO 上执行。
        refreshSkillCatalog()
        // 同时清理 7 天前的 spill 落盘（后台执行，失败可忽略，不阻塞启动）
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
        syncSubAgentDeps()
        // 顶栏状态点要反映真实网络：监听默认网络回调即可，取不到 manager 就当作有网
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { networkAvailable = true }
                override fun onLost(network: Network) { networkAvailable = false }
            })
        }.onFailure { FileLog.log("net", "注册网络回调失败：" + it.message) }
    }

    /**
     * 推理开关的唯一写法：只改当前会话的强度字段，下一次请求就按新值走。
     *
     * 关闭即写入 `off`（请求中不发送思考参数）；重新打开时回到全局默认强度。
     */
    fun toggleThinking() {
        val next = if (thinkingEnabled) {
            SessionPreference.EFFORT_OFF
        } else {
            globalEffortWire()
        }
        sessionStore.setSessionEffort(next)
    }

    /** 选择思考强度；只写当前会话字段，不触碰全局默认。 */
    fun chooseThinkingEffort(effort: String) {
        sessionStore.setSessionEffort(effort)
    }

    /** 全局默认思考强度（设置页维护）：关闭思考时也以它作为「重新打开」的目标值。 */
    private fun globalEffortWire(): String =
        if (!ApiConfig.loadThinkingEnabled(appContext)) {
            SessionPreference.EFFORT_OFF
        } else {
            SessionPreference.effortOrNull(ApiConfig.loadThinkingEffort(appContext))
                ?: SessionPreference.EFFORT_DEFAULT
        }

    /** 草稿写入的防抖任务：连续输入只保留最后一次。 */
    private var draftSaveJob: Job? = null

    /**
     * 装上草稿端口：输入内容按会话存取。
     *
     * 职责划分：日常输入由 [updateInput] 防抖写入；切换会话、退到后台、会话被删除这三类时机
     * 由 [SessionController] 主动调用端口，保证「切走再回来内容还在」。
     */
    private fun installDraftPort() {
        sessionStore.draftPort = object : SessionController.DraftPort {
            override fun flush() {
                draftSaveJob?.cancel()
                val id = currentSessionId
                if (id.isEmpty()) return
                DraftStore.put(appContext, id, composer.inputText.text)
            }

            override fun restore(sessionId: String) {
                draftSaveJob?.cancel()
                val text = DraftStore.text(appContext, sessionId)
                composer.inputText = TextFieldValue(text, selection = TextRange(text.length))
            }

            override fun forget(sessionIds: Collection<String>) {
                DraftStore.remove(appContext, sessionIds)
            }
        }
    }

    /**
     * 盘上数据被后台改过之后整体读回（小窗、定时任务、助手工具共用）。
     *
     * 会话与工作区一起刷新：助手工具可以新建或删除工作区，只刷会话会让分组停留在旧状态。
     */
    fun reloadFromDisk() {
        sessionStore.reloadWorkspaces()
        sessionStore.reloadSessionsFromDisk()
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

    // ---- 模型与提供商（状态与实现见 LlmConfigController.kt，这里只留对外的调用口）----

    /** 保存并立即生效（重建 provider 与 loop）。 */
    fun applyApiKey(newKey: String) = llm.applyApiKey(newKey)

    /** 切换模型：落盘并重建请求链路。 */
    fun applyModel(newModel: String) = llm.applyModel(newModel)

    /** 保存当前 provider 的对话端点并立刻生效。 */
    fun applyBaseUrl(url: String) = llm.applyBaseUrl(url)

    /** 切换当前提供商：落盘 + 重建请求链路 + 刷新列表状态。 */
    fun selectProvider(id: String) = llm.selectProvider(id)

    /** 新增或整条替换一个提供商。 */
    fun saveProvider(cfg: ProviderConfig) = llm.saveProvider(cfg)

    /** 删除提供商；最后一个删不掉。 */
    fun deleteProvider(id: String) = llm.deleteProvider(id)

    /**
     * 系统提示词的输入：每次请求前现取，所以换工作区 / 切权限档 / 改附加指令都立刻生效。
     *
     * Skill 目录走内存缓存：日常请求不读盘；内容版本变化时（导入、删除、助手新建）
     * 在本次请求前重扫一次，保证新技能从下一条消息起就能被看到。
     */
    private fun promptInput(): PromptInput {
        val space = workspaceList.firstOrNull { it.id == currentWorkspaceId }
            ?: WorkSpaceStore.defaultSpace()
        val skills = skillCatalogForPrompt()
        return PromptInput(
            model = model,
            workspacePath = rootOf(space).absolutePath,
            permission = permissionLevel,
            // 计划模式在这里现取：AgentLoop 每次请求都会新调一次本函数，
            // 于是「思考中途打开计划」从下一轮请求起就生效，不用重建循环
            planMode = planMode,
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            // 当前时间写入尾部快照（不写入 system）：包含星期与时区偏移，避免模型按 UTC 解释
            nowText = nowText(),
            skills = skills,
            extraInstructions = PromptStore.loadExtra(appContext),
            systemOverride = PromptStore.loadOverride(appContext),
            // 目标只进尾部快照（不进 system）：目标变了不该让整段前缀作废
            goalText = goalText()
        )
    }

    /** 当前目标的一句话（口径见 [PlanGoalController.goalText]）。 */
    private fun goalText(): String = planGoal.goalText()

    /**
     * 当前时间的提示词表述：`yyyy-MM-dd HH:mm（周X，GMT+偏移）`。
     *
     * 格式由 [PromptBuilder.nowText] 统一维护：定时任务在后台冷启动时也走同一份，
     * 两处若各写一遍，日期口径迟早会漂移。每轮请求现取，所以时间始终是发请求那一刻的。
     */
    private fun nowText(): String = PromptBuilder.nowText()

    /** 重新扫描 appdata/skills（导入 / 删除 / 助手新建之后调）。读盘在 IO，结果回写状态。 */
    fun refreshSkillCatalog() {
        summaryScope.launch {
            // 版本号必须在扫描前取：扫描期间若有新写入，下次请求仍会看到版本差异并重扫
            val revision = SkillStore.revision
            val list = withContext(Dispatchers.IO) {
                runCatching { SkillStore.catalog(appContext) }.getOrDefault(emptyList())
            }
            applySkillCatalog(list, revision)
        }
    }

    private fun applySkillCatalog(list: List<Pair<String, String>>, revision: Int) {
        skillCatalog = list
        // 斜杠补全只认名字：在这里从同一份缓存派生，装/删 Skill 后不会出现「提示词里有、补全里没有」
        skillNames = list.map { it.first }
        skillCatalogRevision = revision
        FileLog.log("skill", "可用 Skill " + list.size + " 个")
    }

    /**
     * 取提示词用的技能目录。
     *
     * 只在内容版本变化时重扫：助手刚用 skill_save 新建的技能，下一条请求就能进入
     * available_skills，不必等用户去设置页手动刷新。
     */
    private fun skillCatalogForPrompt(): List<Pair<String, String>> {
        if (!PromptStore.loadSkillsEnabled(appContext)) return emptyList()
        val revision = SkillStore.revision
        if (skillCatalogRevision != revision) {
            applySkillCatalog(
                runCatching { SkillStore.catalog(appContext) }.getOrDefault(emptyList()),
                revision
            )
        }
        return skillCatalog
    }

    /** 切换当前会话的权限档：只写会话字段并记一条审计；档位没变时不做任何事。 */
    fun setPermission(level: PermissionLevel) {
        if (level == permissionLevel) return
        val from = permissionLevel
        sessionStore.setSessionPermission(level)
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

    /** 斜杠命令入口：true = 该句已被命令消费，不作为普通消息发送。 */
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
    fun updateInput(text: TextFieldValue) {
        composer.updateInput(text)
        // 防抖写入：连续输入只保留最后一次，避免每个按键都写一次文件
        draftSaveJob?.cancel()
        draftSaveJob = summaryScope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            val id = currentSessionId
            if (id.isNotEmpty()) DraftStore.put(appContext, id, text.text)
        }
    }

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

    // ---- 系统分享入口：解析、去重与落盘见 ShareIntake.kt，面板见 SharePanel.kt（主页底部槽位），这里只负责「把面板拼好的内容发出去」这一条路径 ----

    /**
     * 把面板拼好的内容发往**当前会话**。
     *
     * 目标不是另存的一份选择：面板与抽屉里选定目标时已经真的切换过会话（见 [selectShareTarget]），
     * 因此这里只负责「把中转附件落进当前工作区 → 交给发送链路」。附件与图片由本函数显式交给
     * 发送链路，不读写输入区队列，用户正在当前会话编辑的草稿不会被打扰。
     *
     * 本轮正在生成时沿用普通输入框的繁忙语义：带附件则拒绝（面板保留），纯文字则排队或插话。
     *
     * @param onResult null 表示已受理；非 null 为应显示在面板里的一句失败说明
     */
    internal fun sendShare(
        editorText: String,
        draft: ShareDraft,
        scope: CoroutineScope,
        onResult: (String?) -> Unit
    ) {
        if (draft.isEmpty) {
            onResult("没有可发送的内容")
            return
        }
        val body = ShareIntake.composeBody(draft, editorText)
        if (isStreaming) {
            // 发到正在回复的这一条：沿用普通输入框的繁忙语义（排队或插话）
            if (draft.hasFiles) {
                onResult("正在生成回复，附件等本轮结束后再发送")
                return
            }
            val queued = sender.queuePayload(body, scope)
            onResult(if (queued) null else lastError ?: "发送失败，请重试")
            return
        }
        scope.launch {
            // 中转文件落进当前会话的工作区：与普通输入框选择附件走同一套 UploadStore.import
            val attachments = withContext(Dispatchers.IO) {
                ShareIntake.stageForSend(appContext, draft, currentWorkspaceRoot())
            }
            val accepted = sender.sendPayload(
                text = body,
                files = attachments.files,
                images = attachments.images,
                titleSource = draft.titleHint.ifEmpty { null },
                scope = scope
            )
            if (accepted) {
                FileLog.log(
                    "share",
                    "按分享发送：目标=当前会话" +
                        "，正文 " + body.length + " 字，附件 " + attachments.files.size +
                        "，图片 " + attachments.images.size
                )
                onResult(null)
            } else {
                onResult(lastError ?: "发送失败，请重试")
            }
        }
    }

    /**
     * 分享面板选定目标时调用：真的切换会话，让上方的会话区立刻变成该会话的内容。
     *
     * 「新建会话」也走这里：与抽屉新建一致，立刻切到该工作区并建一条空会话。
     * 选定即切换，发送时不再二次切换，因此「用户先看再发」与「直接发」走的是同一条路径。
     */
    internal fun selectShareTarget(target: ShareTarget): Boolean = applyShareTarget(target)

    /** 分享面板所选目标是否就是当前会话本身：选它只是确认，不构成切换，生成中也应放行。 */
    internal fun isShareTargetCurrent(target: ShareTarget): Boolean =
        target is ShareTarget.Session && target.id == currentSessionId

    /**
     * 把会话切到分享面板所选的目标；返回 false 表示目标已不存在（工作区被删、会话被归档删除）。
     *
     * 「新建会话」不校验新建后的会话 id：`newSession()` 会复用同工作区的空白会话，
     * 因此只能以工作区是否切换成功作为判据。
     */
    private fun applyShareTarget(target: ShareTarget): Boolean = when (target) {
        is ShareTarget.NewSession -> {
            if (currentWorkspaceId != target.workspaceId) switchWorkspace(target.workspaceId)
            if (currentWorkspaceId != target.workspaceId) {
                false
            } else {
                newSession()
                true
            }
        }

        is ShareTarget.Session -> {
            if (currentSessionId != target.id) switchSession(target.id)
            currentSessionId == target.id
        }
    }

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
    /** 当前会话的运行根：图片解码、附件落盘与工具调用共用同一口径。 */
    fun currentWorkspaceRoot(): File = sessionStore.currentWorkspaceRoot()

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

    /** 修改排队消息的正文；改为空串即将其移出队列。 */
    fun editQueued(id: Long, text: String) = sender.editQueued(id, text)

    /** 把某条排队消息移出队列。 */
    fun deleteQueued(id: Long) = sender.deleteQueued(id)

    /** 插话发送队列里的某一条：运行中立刻插入这一轮，空闲时直接发出。 */
    fun sendQueuedNow(id: Long, scope: CoroutineScope) = sender.sendQueuedNow(id, scope)

    /** 长按发送键：把队列里的消息全部插话发送（空闲时按顺序正常发送）。 */
    fun steerAllQueued(scope: CoroutineScope) = sender.steerAllQueued(scope)

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

    /** 删除会话（仅限已归档）：删的是当前会话就在当前工作区里挑下一条，一个都不剩就新建一条空的。 */
    fun deleteSession(id: String) = sessionStore.deleteSession(id)

    /** 删除已归档会话时确认文案的追加段；null 表示无需追加。 */
    fun deleteSessionExtraNote(id: String): String? = sessionStore.deleteSessionExtraNote(id)

    /** 归档前的特例说明（定时任务被解绑）；空列表表示常规归档。 */
    fun archiveNotes(id: String): List<String> = sessionStore.archiveNotes(id)

    /** 当前会话的存储根：产物推导按它把相对路径解析成绝对路径。 */
    fun currentStorageRoot(): File = sessionStore.currentStorageRoot()

    /** 当前会话的读取入口（兼容系统授权目录）。 */
    fun currentStorageIo(): WorkspaceIo = sessionStore.currentStorageIo()

    /** 把会话移到目标工作区；返回需要补充的提示，没有则 null。 */
    fun moveSession(id: String, workspaceId: String): String? = sessionStore.moveSession(id, workspaceId)

    /** 重命名会话；标题 trim 后为空就退回「新会话」的空标题。 */
    fun renameSession(id: String, title: String) = sessionStore.renameSession(id, title)

    /** 置顶 / 取消置顶；只改 pinned，不动 updatedAt。 */
    fun pinSession(id: String, pinned: Boolean) = sessionStore.pinSession(id, pinned)

    /** 归档会话；归档的是当前会话时，直接进一个空对话。 */
    fun archiveSession(id: String) = sessionStore.archiveSession(id)

    /**
     * 归档入口的统一处理。
     *
     * 常规归档直接执行并返回空列表（界面只给一行轻提示）；
     * 命中特例（内置目录仅剩此引用、定时任务被停用解绑）时先不执行，返回说明由界面合并成一个确认弹窗。
     */
    fun requestArchive(id: String): List<String> {
        val notes = sessionStore.archiveNotes(id)
        if (notes.isEmpty()) sessionStore.archiveSession(id)
        return notes
    }

    /** 从归档恢复；返回 true 表示原工作区已删除、会话归入了「未分类」。 */
    fun unarchiveSession(id: String): Boolean = sessionStore.unarchiveSession(id)

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

    /** 删除工作区的确认文案：按有会话 / 目录空 / 目录非空三选一。 */
    fun deleteWorkspacePrompt(id: String): String = sessionStore.deleteWorkspacePrompt(id)

    /** 删除工作区：默认工作区不删；只解除映射，会话转「未分类」，内置目录按引用归零回收。 */
    fun deleteWorkspace(id: String) = sessionStore.deleteWorkspace(id)

    /** 更改储存路径的确认文案：按原目录类型三选一。 */
    fun changeStoragePrompt(id: String): String = sessionStore.changeStoragePrompt(id)

    /** 更改工作区的储存路径；返回 false 表示目录不可用或当前有会话在运行。 */
    fun changeWorkspaceStorage(id: String, rootPath: String?, treeUri: String?): Boolean =
        sessionStore.changeWorkspaceStorage(id, rootPath, treeUri)

    /** 同一弹窗内新建工作区并移入会话，一次提交。 */
    fun createWorkspaceAndMove(sessionId: String, name: String, rootPath: String?, treeUri: String?): Boolean =
        sessionStore.createWorkspaceAndMove(sessionId, name, rootPath, treeUri)

    /** 该目录已被哪个工作区映射；null 表示未占用。 */
    fun workspaceUsingDir(rootPath: String?, treeUri: String?): String? =
        sessionStore.workspaceUsingDir(rootPath, treeUri)

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

    /** 本会话模块的数据（指定会话；当前会话用内存态）。 */
    fun insightSession(sessionId: String): InsightSessionView = context.insightSession(sessionId)

    /** 全局统计区的数据（按范围）。 */
    fun globalInsight(range: InsightRange): InsightGlobalView = context.globalInsight(range)

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

    /** 新一轮开始：仅记录本次回复已达到的最大轮次；状态行的轮/步已有固定显示，不重复提示。 */
    override fun onRound(round: Int) {
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
