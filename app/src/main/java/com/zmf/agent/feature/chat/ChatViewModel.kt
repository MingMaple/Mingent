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
import com.zmf.agent.capability.context.ContextCompactor
import com.zmf.agent.capability.context.ContextEvent
import com.zmf.agent.capability.context.ContextPolicy
import com.zmf.agent.capability.context.ContextSnapshot
import com.zmf.agent.core.store.Goal
import com.zmf.agent.core.store.GoalPhase
import com.zmf.agent.core.store.GoalStore
import com.zmf.agent.core.store.label
import com.zmf.agent.capability.loop.AgentLoop
import com.zmf.agent.capability.mcp.McpManager
import com.zmf.agent.capability.loop.AgentUi
import com.zmf.agent.capability.memory.MemoryAdmin
import com.zmf.agent.capability.memory.MemoryKernel
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
import com.zmf.agent.core.store.PlanStore
import com.zmf.agent.core.store.Todo
import com.zmf.agent.core.store.TodoStore
import com.zmf.agent.core.store.UsageLedger
import com.zmf.agent.capability.subagent.SubAgentDeps
import com.zmf.agent.capability.subagent.SubAgentRunner
import com.zmf.agent.core.store.UploadStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.core.AppForeground
import com.zmf.agent.infra.backup.BackupArchive
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

/**
 * 排队等发送的一条消息。
 *
 * 只存纯文本，不存附件/图片：等它轮到时走的就是普通发送流程（[ChatViewModel.send]），
 * 附件按那一刻输入框里的状态一起拼进 prompt：和手动发一条完全同路径，
 * 排过队的消息不会多带一份、也不会丢一份文件。
 *
 * @param id 队列内的稳定标识，编辑 / 删除 / 立即发送都按它找
 */
data class QueuedMessage(val id: Long, val text: String)

/**
 * 上下文分段估算（粗估 tokens，口径同 [ContextCompactor]）。
 *
 * 为什么把「消息」拆成 user / assistant / tool 三段：历史里三者的增长速度完全不同
 * （工具结果才是真正的大头），只给一个合计看不出该压缩谁。
 *
 * 六段合起来才是**整份请求**（提示词 + 摘要 + 工具表 + 三类消息），
 * 水位那条只按「消息 + 摘要」算，两者口径不同、数值不等，界面上必须分开说明。
 */
data class Breakdown(
    /** 系统提示词正文（**不含**会话摘要）。 */
    val systemTokens: Int = 0,
    /** 会话摘要：它是拼进 system 的一段，单列出来，否则「提示词」会显得莫名其妙地大。 */
    val summaryTokens: Int = 0,
    val toolTokens: Int = 0,
    val userTokens: Int = 0,
    val assistantTokens: Int = 0,
    val toolResultTokens: Int = 0
) {
    /** 三段的合计（旧界面口径「消息占多少」，也是水位里除提示词与工具表之外的部分）。 */
    val messageTokens: Int get() = userTokens + assistantTokens + toolResultTokens

    /** 整份请求的估算：提示词 + 摘要 + 工具表 + 三类消息（= 成分条六段之和）。 */
    val totalTokens: Int
        get() = systemTokens + summaryTokens + toolTokens + messageTokens
}

/**
 * 本会话的时间账：模型等待 / 工具执行各自的累计毫秒数，外加会话累计输出 tokens。
 *
 * 分母都是「本会话」，不是全局：用户看的是这条会话到底把时间花在哪。
 */
data class TimingSnapshot(
    val modelMillis: Long = 0,
    val toolMillis: Long = 0,
    /** 本会话累计的输入 / 输出 tokens（状态行右侧那枚胶囊报的是两者之和）。 */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** 上一轮回复的模型耗时与输出 tokens：输出速度只用这一对算（累计值在旧会话里是残缺的）。 */
    val lastTurnModelMillis: Long = 0,
    val lastTurnOutputTokens: Int = 0,
    /** 提示词缓存的累计命中 / 未命中输入 tokens。 */
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    /** 首 token 等待累计与次数、纯生成时长与输出 tokens（速度与 TTFT 的分母）。 */
    val ttftMillis: Long = 0,
    val ttftCount: Int = 0,
    val genMillis: Long = 0,
    val genTokens: Int = 0
) {
    /** 首 token 平均（毫秒）；一次都没等到过就是 null，界面显示「—」。 */
    val ttftAverageMillis: Long? get() = if (ttftCount > 0) ttftMillis / ttftCount else null

    /**
     * 输出速度（tok/s）：分子是输出 tokens，分母只算「第一个字到最后一个字」。
     *
     * 为什么**不**用累计模型耗时：那里面含等首字的时间，网络慢的时候会把速度压得莫名其妙。
     */
    val tokensPerSec: Int?
        get() = if (genMillis > 0L && genTokens > 0) ((genTokens * 1000L) / genMillis).toInt() else null

    /** 缓存命中率（0–100）；服务端从没报过缓存字段就是 null，界面显示「—」。 */
    val cacheHitPercent: Float?
        get() {
            val total = cacheHitTokens + cacheMissTokens
            return if (total > 0) cacheHitTokens * 100f / total else null
        }
}

// 下面几个常量放文件顶层而不是 companion：它们只被 ChatViewModel 的正文用到，
// 顶层声明的作用域最直白（不必再猜「这个名字在哪个 object 里」）。

/** 批准计划时替用户发出的那句话；正文与日志共用一份，别两处各写一遍。 */
private const val PLAN_APPROVE_TEXT = "按计划开始执行"

/** 记忆动作的总超时：正常一次存储动作约 1.5 秒（真机实测 status 0.98–1.54 秒），留足余量后兜底。 */
private const val MEMORY_ACTION_TIMEOUT_MS = 120_000L

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
        clearTodos = { todos = emptyList() },
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

    /** 计划模式（全局开关）：开启后模型只调研、只给计划，等确认了才动手。 */
    var planMode by mutableStateOf(PlanStore.load(appContext))
        private set

    /** 当前会话的计划列表（模型每次 write_plan 追加一份）；空 = 还没有计划。 */
    var plans by mutableStateOf<List<PlanDoc>>(emptyList())
        private set

    /** 计划预览里选中的那一份；null = 没有计划可选。只存在内存里：这是「看哪份」的选择，不是数据。 */
    var selectedPlanId by mutableStateOf<String?>(null)
        private set

    /** 当前会话的长期目标；null = 没有目标。 */
    var goal by mutableStateOf<Goal?>(null)
        private set

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

    /** 当前会话的待办清单（模型用 todo_write 写、TodoStore 落盘）；换会话与每轮结束后都重读一次。 */
    var todos by mutableStateOf<List<Todo>>(emptyList())
        private set

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
        setGoal = { goal = it },
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

    /** 日志导出的状态文本（成功/失败各一句）；null = 没导过。 */
    var logExportStatus by mutableStateOf<String?>(null)
        private set

    /** 记忆内核对外的状态（字段由 MemoryAdmin 定义）；由 [refreshMemory] 刷新。 */
    var memoryState by mutableStateOf(MemoryState())
        private set

    /** 向量模型开关（记忆页「向量模型」按钮）：只影响内核是否启用稠密通道，默认关。 */
    var memoryVectorEnabled by mutableStateOf(MemoryKernel.isVectorEnabled(appContext))
        private set

    /** 向量模型开关下方的临时状态：检测、安装、结果；null = 显示默认注释。 */
    var memoryVectorStatus by mutableStateOf<String?>(null)
        private set

    /** 状态文案是否为失败结果，界面据此换错误色。 */
    var memoryVectorFailed by mutableStateOf(false)
        private set

    /** 依赖检测或安装正在进行。 */
    var memoryVectorBusy by mutableStateOf(false)
        private set

    /** 向量依赖检测/安装任务；关闭开关时取消，避免旧结果回写。 */
    private var vectorJob: Job? = null
    private var vectorGeneration = 0

    /**
     * 状态行显示的轮数：界面历史里用户问过几次。
     *
     * 为什么不用 stats.rounds：那个只有进统计页时才刷，状态行会一直停在旧数字上。
     */
    val statusRounds: Int get() = messages.count { it.role == "user" }

    /**
     * 状态行显示的步数：本会话已提交的步数 + 本次回复跑过的步数。
     *
     * 为什么要把两段加起来：stats.steps 只是「本次回复」，每次新提问都会归零，
     * 只看它会让状态行在提问的一瞬间掉回 0。
     */
    val statusSteps: Int get() =
        (sessionList.firstOrNull { it.id == currentSessionId }?.steps ?: 0) + stats.steps

    /**
     * 本会话的「活跃时长」：第一条与最后一条消息的时间差。
     *
     * 为什么不用累计耗时：那只是模型和工具真的在跑的时间，中间放着不管的几小时不算；
     * 洞察页要的是「这段对话跨了多久」，所以取消息时间轴的两端。没消息就是 0。
     */
    val statusActiveMillis: Long
        get() {
            if (messages.isEmpty()) return 0L
            return (messages.last().createdAt - messages.first().createdAt).coerceAtLeast(0L)
        }

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

    /**
     * 计划模式开关的唯一写法：状态、落盘、日志三处保持一致（斜杠命令也走它）。
     *
     * 名字必须是 apply* 而不是 set*：`var planMode` 的自动 setter 已占用 setPlanMode 的 JVM 签名，
     * 同名函数会 Platform declaration clash（busyMode 那次踩过同一个坑）。
     */
    private fun applyPlanMode(enabled: Boolean) {
        if (planMode == enabled) return
        planMode = enabled
        PlanStore.save(appContext, enabled)
        FileLog.log("plan", "计划模式 " + (if (enabled) "开启" else "关闭"))
    }

    /** 面板里点「计划」：切换并落盘。 */
    fun togglePlanMode() {
        applyPlanMode(!planMode)
    }

    // ---- 斜杠命令 ----

    /**
     * 斜杠命令入口：输入以 / 开头时先在这里拦一道。
     *
     * 返回值就是「这句话已经被吃掉了」：true = 不要再当普通消息发出去。
     * 认不出来的 /xxx（比如 /技能名）一律返回 false，让它照常发出去。
     */
    fun handleSlashCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return false
        val body = trimmed.removePrefix("/").trim()
        // 单独一个 "/" 当普通输入：它不是命令，也没有可执行的语义
        if (body.isEmpty()) return false
        val name = body.substringBefore(' ').lowercase()
        val arg = body.substringAfter(' ', "").trim()
        return when (name) {
            "plan" -> handlePlanCommand(arg)
            "goal" -> handleGoalCommand(arg)
            "compact" -> handleCompactCommand()
            "permission" -> handlePermissionCommand(arg)
            else -> false
        }
    }

    /**
     * /compact：立刻压一次上下文。
     *
     * 为什么要这个命令：以前只有加号面板里的「压缩」入口，没法写进脚本、也没法在对话里直接说；
     * 压缩做成命令的同时也是自测入口（先发一句话、再 /compact，就能验证压缩与缓存）。
     */
    private fun handleCompactCommand(): Boolean {
        summarizeNow()
        return true
    }

    /**
     * /permission <只读|工作区|完全>（也认 read/workspace/full）：切权限档。
     *
     * 切档入口就是 `/permission <preset>`；原来只有左下角那颗 chip，
     * 自动化测试得靠点坐标，做成命令后一条 input text 就能切。
     */
    private fun handlePermissionCommand(arg: String): Boolean {
        val level = when (arg.trim().lowercase()) {
            "read", "read-only", "readonly", "只读", "仅可查看" -> PermissionLevel.READ_ONLY
            "workspace", "workspace-write", "工作区", "工作区内修改" -> PermissionLevel.WORKSPACE
            "full", "danger-full-access", "danger", "完全", "完全权限" -> PermissionLevel.FULL
            "" -> null
            else -> null
        }
        if (level == null) {
            activityText = "用法：/permission 只读｜工作区｜完全"
            return true
        }
        setPermission(level)
        activityText = "权限档已切到「" + level.displayName() + "」"
        return true
    }

    /** /plan [off] [本轮提问]：开 / 关计划模式；带正文时那段文字就是本轮提问。 */
    private fun handlePlanCommand(arg: String): Boolean {
        if (arg.equals("off", ignoreCase = true)) {
            applyPlanMode(false)
            activityText = "计划模式已关闭"
            return true
        }
        applyPlanMode(true)
        if (arg.isEmpty()) {
            activityText = "计划模式已开启"
            return true
        }
        // 带正文：模式先打开，再把这段文字当成一次普通提问发出去（走同一条发送路径）
        send(arg, summaryScope)
        return true
    }

    /**
     * /goal ...：目标是「命令流」，不进对话上下文，也不需要预览页。
     *
     * - 无参：只把当前状态写进状态行，不发消息；
     * - pause / resume / clear / edit <文本>：改状态，不发消息；
     * - 其它：整段当成新目标（覆盖旧目标），并立刻开跑一轮把它推进起来。
     */
    private fun handleGoalCommand(arg: String): Boolean {
        if (arg.isEmpty()) {
            reportGoalStatus()
            return true
        }
        val sub = arg.substringBefore(' ').lowercase()
        val rest = arg.substringAfter(' ', "").trim()
        // pause/resume/clear/edit 都是「对已有目标动手」：没有目标就先说清楚，别假装成功
        if ((sub == "pause" || sub == "resume" || sub == "clear" || sub == "edit") && goal == null) {
            activityText = "还没有目标：发 /goal <目标> 先建一个"
            return true
        }
        when (sub) {
            "pause" -> {
                pauseGoal()
                activityText = "目标已暂停"
            }
            "resume" -> {
                resumeGoal()
                activityText = "目标已继续"
            }
            "clear" -> {
                clearGoal()
                activityText = "目标已删除"
            }
            "edit" -> {
                if (rest.isEmpty()) {
                    activityText = "用法：/goal edit <新的目标>"
                } else {
                    editGoal(rest)
                    activityText = "目标已改写"
                }
            }
            // 不是子命令：整段就是新目标
            else -> createGoal(arg)
        }
        return true
    }

    /** 新建（或覆盖）目标并立刻开跑一轮：目标是「还在做的事」，不能建完还等用户再问一遍。 */
    private fun createGoal(text: String) {
        val objective = text.trim()
        if (objective.isEmpty()) return
        val now = System.currentTimeMillis()
        val next = Goal(
            objective = objective,
            phase = GoalPhase.ACTIVE,
            rounds = 0,
            createdAt = now,
            updatedAt = now
        )
        goal = next
        GoalStore.save(appContext, currentSessionId, next)
        FileLog.log("goal", "新目标（第 0 轮）：" + objective.take(60))
        // 流式中 send 会直接返回：那一轮收尾时 goal 已经是 ACTIVE，续跑链会把这一轮补上
        send(objective, summaryScope)
    }

    /** 状态行里说一句当前目标；没有目标就说怎么建。 */
    private fun reportGoalStatus() {
        val current = goal
        activityText = if (current == null) {
            "还没有目标：发 /goal <目标> 新建"
        } else {
            "目标" + current.phase.label() + " · 第 " + current.rounds + " 轮" +
                (if (current.note.isNotEmpty()) " · " + current.note else "")
        }
        FileLog.log("goal", activityText.orEmpty())
    }

    // ---- 计划 / 目标（当前会话的两份文档） ----

    /**
     * 重读当前会话的计划列表：流结束与进计划页时各调一次，别每帧调（要读盘）。
     *
     * 选中态在这里一起校正：原选中那份被删掉、或第一次读到计划时，落到最新的一份。
     * 用户刚看到的应该就是模型刚交的那份。
     */
    fun reloadPlans() {
        // 记住重读之前有哪几份：用来认出「这次真的多了新计划」
        val before = plans.map { it.id }.toSet()
        val list = PlanStore.loadPlans(appContext, currentSessionId)
        plans = list
        val current = selectedPlanId
        val fresh = list.filter { it.id !in before }
        selectedPlanId = when {
            // 模型刚交了新计划：直接把预览切到最新那份（用户点右上角按钮就该看到它，
            // 而不是还停在上次看过的旧计划上）
            fresh.isNotEmpty() -> fresh.last().id
            // 没有新计划就停在用户上次看的那份：从计划页返回主页再进来，还是同一页
            current != null && list.any { it.id == current } -> current
            list.isNotEmpty() -> list.last().id
            else -> null
        }
    }

    /**
     * 新一轮对话开始前把「还没处理过」的计划标成废弃。
     *
     * 判据在 [PlanStore.abandonPending] 里，这里只负责同步界面上的两份真值：
     * 列表（按钮要变灰）与选中态（被废弃的那份仍然要能看到详情）。
     */
    private fun abandonPendingPlansBeforeNewTurn() {
        if (plans.none { it.status == PlanStore.STATUS_PENDING }) return
        val updated = PlanStore.abandonPending(appContext, currentSessionId)
        if (updated.isEmpty()) return
        plans = updated
        selectedPlanId = updated.lastOrNull()?.id ?: selectedPlanId
    }

    /** 重读当前会话的目标：切会话、以及 goal 工具跑完之后都要对齐。 */
    fun reloadGoal() {
        goal = GoalStore.load(appContext, currentSessionId)
    }

    /** 换会话路径统一调这一个：两份文档都跟当前会话绑定。 */
    private fun reloadSessionDocs() {
        reloadPlans()
        reloadGoal()
    }

    /** 计划预览切换选中项；id 不在列表里就当没点（界面上的选中态不该跟着乱跳）。 */
    fun selectPlan(id: String) {
        if (plans.none { it.id == id }) return
        if (id == selectedPlanId) return
        selectedPlanId = id
        FileLog.log("plan", "预览切到计划 " + id)
    }

    /** 删掉一份计划；删的是选中那份就把选中态落到剩下的第一份（没有就清空）。 */
    fun deletePlan(id: String) {
        if (plans.none { it.id == id }) return
        // deletePlan 直接把删完的列表还回来：省一次读盘，选中态也基于同一份数据校正
        val remaining = PlanStore.deletePlan(appContext, currentSessionId, id)
        val wasSelected = selectedPlanId == id
        plans = remaining
        if (wasSelected || remaining.none { it.id == selectedPlanId }) {
            selectedPlanId = remaining.firstOrNull()?.id
        }
        FileLog.log("plan", "删除计划 " + id + "，剩 " + plans.size + " 份")
    }

    /**
     * 「按计划开始执行」：关掉计划模式，再把所选计划连正文一起当成一次普通发送。
     *
     * 为什么走 send 而不是直接开跑：用户批准的是一句话，它该和其他提问一样留下 user 气泡、
     * 记日志、落盘，而不是偷偷改状态让模型「自己开始」。流式中直接忽略：会和正在跑的那轮抢 messages。
     * [plans] 不清：同意之后计划预览还要能对着逐条执行、回看。
     */
    fun approvePlan(planId: String) {
        if (isStreaming) return
        val target = plans.firstOrNull { it.id == planId } ?: plans.lastOrNull()
        // 双保险：页面上按钮已经灰了，这里再拦一道，两份真值可能差一拍
        //（比如这页还开着，另一处刚把它废弃了），过期状态绝不能执行
        if (target != null && !target.actionable) {
            lastError = "该计划此前已处理，不可重复执行；如需执行请让模型重新提交一份计划"
            FileLog.log("plan", "拒绝执行已处理的计划 " + target.id + "（状态=" + target.status + "）")
            return
        }
        if (target != null) selectedPlanId = target.id
        // 先落状态再发消息：下面是 send()，而 send 会把「还悬着待确认」的计划当废弃处理，
        // 顺序反过来的话刚被批准的这份会先被废弃、再被当成可执行的那份发出去
        if (target != null) {
            plans = PlanStore.markPlan(
                appContext,
                currentSessionId,
                target.id,
                PlanStore.STATUS_APPROVED,
                // 同一时刻只该有一份等着被批准：其余还在等确认的跟着作废
                abandonOtherPending = true
            )
        }
        PlanStore.save(appContext, false)
        planMode = false
        FileLog.log(
            "plan",
            "用户批准计划 " + (target?.id ?: "-") + "（" + (target?.title ?: "无标题") + "）：关闭计划模式并开始执行"
        )
        // programmatic = true：这条「用户消息」是 App 替用户代发的（按钮点击），不是用户手打的一句话。
        // 高危删除的授权码只认真实用户消息，代发消息里的码一律不认：否则「批准计划」就成了
        // 一个可以顺带批准删除的后门（令牌必须绑定最近一条真实 user 消息）。
        send(if (target != null) approvePlanText(target) else PLAN_APPROVE_TEXT, summaryScope, programmatic = true)
    }

    /**
     * 同意执行时发出去的那条 user 消息：带上所选计划的正文。
     *
     * 为什么必须带上：一个会话里可能存了好几份计划，只发一句「按计划开始执行」，
     * 模型只能按它最后写的那份做；把标题 / 步骤 / 备注原样带上，选哪份就执行哪份。
     * 为什么不用 system 发：这本来就是用户说的话，进历史后还要能回看、回滚、编辑重发。
     */
    private fun approvePlanText(plan: PlanDoc): String = buildString {
        // 气泡里只留这一句：同意执行的是哪一份，一眼能看出来
        append(PLAN_APPROVE_TEXT).append("：").append(plan.title.ifBlank { "未命名计划" })
        append("\n\n").append(PLAN_DETAIL_MARKER)
        append("\n计划标题：").append(plan.title)
        append("\n步骤：")
        plan.steps.forEachIndexed { index, step ->
            append("\n").append(index + 1).append(". ").append(step)
        }
        if (plan.note.isNotEmpty()) append("\n备注：").append(plan.note)
    }

    /**
     * 「继续修改计划」：只把计划留在页面上。
     *
     * 有意什么都不做：用户点这个是为了回到对话里补充要求，这里替他发消息反而会把「再看看」变成一次发送。
     */
    fun continuePlanEdit(planId: String) {
        // 点了就等于这份被消费掉：两个按钮变灰，不能回头再点一次「同意执行」
        if (plans.none { it.id == planId }) return
        plans = PlanStore.markPlan(appContext, currentSessionId, planId, PlanStore.STATUS_EDITED)
        FileLog.log("plan", "用户选择继续修改计划 " + planId + "，该份标记为已处理")
        // 其余什么都不做：页面自己负责返回主页，替用户发消息会把「再看看」变成一次发送
    }

    /** 暂停目标（/goal pause）：暂停后这一轮结束就不再自动续跑。 */
    fun pauseGoal() = applyGoalPhase(GoalPhase.PAUSED)

    /** 继续目标（/goal resume）。 */
    fun resumeGoal() = applyGoalPhase(GoalPhase.ACTIVE)

    /**
     * 改写目标正文（/goal edit <文本>）。
     *
     * 只改 objective：轮次与阶段是进度，改一句话不该把它们清零。
     * 那会让「已经跑到第 5 轮」变成重新开始。
     */
    fun editGoal(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val current = goal ?: return
        val next = current.copy(objective = trimmed)
        goal = next
        GoalStore.save(appContext, currentSessionId, next)
        FileLog.log("goal", "目标正文改为：" + trimmed.take(60))
    }

    /** 删除当前会话的目标：内存与盘上一起清，聊天记录不动。 */
    fun clearGoal() {
        if (goal == null) return
        GoalStore.clear(appContext, currentSessionId)
        goal = null
        FileLog.log("goal", "已删除当前会话的目标")
    }

    /** 改阶段并落盘：改完 [goal] 立刻变，输入框那个只读图标靠它判断要不要亮。 */
    private fun applyGoalPhase(phase: GoalPhase) {
        val current = goal ?: return
        if (current.phase == phase) return
        val next = current.copy(phase = phase)
        goal = next
        GoalStore.save(appContext, currentSessionId, next)
        FileLog.log("goal", "目标阶段改为 " + phase.label())
    }

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
     * 重读当前会话的待办清单：IO 里读、主线程赋值。
     *
     * 清单是模型用 todo_write 写的，App 侧只读不算，所以换会话与每轮结束后各对齐一次。
     * 读盘期间又切走了会话就丢弃这次结果，别把上一个会话的清单显示到当前会话上。
     */
    private fun reloadTodos() {
        val id = currentSessionId
        if (id.isEmpty()) {
            todos = emptyList()
            return
        }
        summaryScope.launch {
            val loaded = withContext(Dispatchers.IO) { TodoStore.load(appContext, id) }
            if (currentSessionId == id) todos = loaded
        }
    }

    /**
     * 新一轮提问前清掉上一轮「全部完成」的清单；还有 pending / in_progress 就保留。
     *
     * 清单是这一轮的执行计划：全部打勾说明这一轮已经收尾，留着只会挡住新一轮的进度；
     * 没做完的还得继续挂着，否则用户会以为任务自己消失了。
     * 落盘必须在这一轮开跑之前同步做完：模型的 todo_write 紧接着就会写同一份文件，
     * 放到 IO 线程里异步清，可能反过来把刚写好的新清单抹掉。
     */
    private fun dropFinishedTodosBeforeNewRound() {
        val id = currentSessionId
        if (id.isEmpty()) return
        // 以盘上的清单为准：reloadTodos 是异步的，刚结束的那一轮可能还没读回来
        val stored = TodoStore.load(appContext, id)
        if (stored.isEmpty() || stored.any { it.status != TodoStore.COMPLETED }) return
        TodoStore.save(appContext, id, emptyList())
        todos = emptyList()
        FileLog.log("todo", "上一轮清单已全部完成，新一轮提问时清空")
    }

    // ---- 备份 / 恢复 ----

    /** 备份正在跑：导出/导入按钮一起禁用。 */
    var backupBusy by mutableStateOf(false)
        private set

    /** 备份进度文案（「导出中 37%」）；空闲为 null。 */
    var backupStatus by mutableStateOf<String?>(null)
        private set

    /**
     * 上一次导出 / 导入的结果，非空时在**数据页**里显示一行。
     *
     * 为什么不再挂到主页那条状态行上：导出是从数据页点起来的，进度也在那儿看着；
     * 结果却跑到主页去，人还留在数据页上等于什么都没看到。
     */
    var backupNotice by mutableStateOf<String?>(null)
        private set

    /** 导入前的清单摘要；非空时界面弹确认框。 */
    var pendingManifest by mutableStateOf<BackupManifest?>(null)
        private set

    /** 导入结果；非空时界面弹报告。 */
    var importReport by mutableStateOf<ImportReport?>(null)
        private set

    /**
     * 刚导入完、还没重启。
     *
     * 磁盘上的数据已经是备份里的那一份，但内存里还是旧列表：这时任何一次落盘
     * 都会把刚导入的数据盖掉。所以从导入成功起：发送直接拒绝、落盘全部跳过，
     * 等用户重启进程（内存从磁盘重新读）才放开。
     */
    var needsRestart by mutableStateOf(false)
        private set

    /** 导出到系统里选的 zip 目标。 */
    fun exportData(uri: Uri, options: BackupOptions, scope: CoroutineScope) {
        if (backupBusy) return
        backupBusy = true
        backupStatus = "准备导出…"
        backupNotice = null
        scope.launch {
            val result = BackupArchive.export(appContext, uri, options) { percent, stage ->
                backupStatus = stage + " " + percent + "%"
            }
            backupBusy = false
            backupStatus = null
            result.fold(
                onSuccess = { outcome ->
                    val size = "%.1f".format(outcome.bytes / 1024.0 / 1024.0) + " MB"
                    backupNotice = buildString {
                        append("导出成功：")
                        append(displayNameOf(uri) ?: "备份文件")
                        append("（").append(size).append("）")
                        // 终端环境被跳过时把原因说出来，别让人以为整包都齐了
                        outcome.note?.let { append("\n").append(it) }
                    }
                    FileLog.log(
                        "backup",
                        "导出完成：" + outcome.bytes + " 字节" + (outcome.note?.let { "，$it" } ?: "")
                    )
                },
                onFailure = { e ->
                    backupNotice = "导出失败：" + (e.message ?: e.javaClass.simpleName) +
                        "\n半截文件已删除，可以重新导出一次。"
                    FileLog.log("backup", "导出失败：" + e)
                }
            )
        }
    }

    /** 系统文件选择器里那个目标文件叫什么（数据页那句「导出到 xxx」用）。 */
    private fun displayNameOf(uri: Uri): String? = try {
        appContext.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        null
    }

    /** 只读备份清单：先给用户看清楚要导入什么，再决定。 */
    fun loadBackupManifest(uri: Uri, scope: CoroutineScope) {
        if (backupBusy) return
        backupBusy = true
        backupStatus = "读取备份信息…"
        scope.launch {
            val result = BackupArchive.peekManifest(appContext, uri)
            backupBusy = false
            backupStatus = null
            result.fold(
                onSuccess = { manifest -> pendingManifest = manifest },
                onFailure = { e ->
                    backupNotice = "无法识别这个备份：" + (e.message ?: e.javaClass.simpleName)
                }
            )
        }
    }

    /** 放弃已解析但还没确认导入的备份：只清待确认状态，不动磁盘数据。 */
    fun cancelImport() {
        pendingManifest = null
    }

    /** 关掉导入结果报告；报告本身是界面状态，关掉不影响已导入的数据。 */
    fun dismissImportReport() {
        importReport = null
    }

    /** 开始导入备份文件；[backupBusy] 期间重复调用会被忽略。 */
    fun importData(uri: Uri, scope: CoroutineScope) {
        if (backupBusy) return
        pendingManifest = null
        backupBusy = true
        backupStatus = "准备导入…"
        scope.launch {
            val result = BackupArchive.import(appContext, uri) { percent, stage ->
                backupStatus = stage + " " + percent + "%"
            }
            backupBusy = false
            backupStatus = null
            result.fold(
                onSuccess = { report ->
                    importReport = report
                    needsRestart = true
                    FileLog.log(
                        "backup",
                        "导入完成：成功 " + report.restored.size + " 项、跳过 " + report.skipped.size +
                            " 项、失败 " + report.failed.size + " 项"
                    )
                },
                onFailure = { e ->
                    // 导入失败也落在数据页那一行（报告对话框之外还能看到上一句）
                    backupNotice = "导入失败：" + (e.message ?: e.javaClass.simpleName)
                    FileLog.log("backup", "导入失败：" + e)
                }
            )
        }
    }

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

    /**
     * 把 logs 目录下的日志打成 zip 交给用户选的位置。
     *
     * 状态文本落在 [logExportStatus]（成功/失败各一句），同时记一条 FileLog：
     * 导出的意义就是拿去排查，成败都要能在日志里回溯。
     */
    fun downloadLogs(uri: Uri) {
        logExportStatus = null
        summaryScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { zipLogs(uri) } }
            result.fold(
                onSuccess = { count ->
                    logExportStatus = "已导出 " + count + " 个日志文件"
                    FileLog.log("log", "日志导出成功：" + count + " 个文件 → " + uri)
                },
                onFailure = { e ->
                    logExportStatus = "导出日志失败：" + (e.message ?: e.javaClass.simpleName)
                    FileLog.log("log", "日志导出失败：" + e)
                }
            )
        }
    }

    /** 打包动作本体（IO 线程调）：返回打进去的文件数。 */
    private fun zipLogs(uri: Uri): Int {
        val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "logs")
        // 只认 *.log：目录里可能还有别的临时文件，打包进去既没用又容易带出半截内容
        val logs = dir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedBy { it.name }
            .orEmpty()
        val raw = appContext.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("打不开所选位置")
        // ZipOutputStream 关闭时会 flush 并关掉底层流，所以只包这一层，不再套 use
        ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
            for (log in logs) {
                zip.putNextEntry(ZipEntry(log.name))
                log.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return logs.size
    }

    /** 重扫当前工作区的记忆库，结果写回 [memoryState]（长动作，统一走 runMemoryAction 的超时兜底）。 */
    fun refreshMemory() {
        runMemoryAction { MemoryAdmin.refresh(appContext, currentWorkspaceRoot()) }
    }

    /** 检索记忆；空查询直接忽略（内核会以退出码 2 报「--query 必填」，白等一次往返）。 */
    fun searchMemory(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        runMemoryAction { MemoryAdmin.search(appContext, currentWorkspaceRoot(), query) }
    }

    /** 跑一次维护扫描（遗忘扫描 + 报出待巩固批次），结果由内核决定，这里不解释。 */
    fun runMemoryCycle() {
        runMemoryAction { MemoryAdmin.cycle(appContext, currentWorkspaceRoot()) }
    }

    /** 导出给人看的记忆视图。 */
    fun exportMemory(outPath: String = "") {
        runMemoryAction { MemoryAdmin.export(appContext, currentWorkspaceRoot(), outPath) }
    }

    /**
     * 记忆页「向量模型」开关。
     *
     * 开启时先快速检测运行依赖，缺失则立即安装；关闭时只写偏好并清空临时状态。
     */
    fun toggleMemoryVector(enabled: Boolean) {
        MemoryKernel.setVectorEnabled(appContext, enabled)
        memoryVectorEnabled = enabled
        vectorGeneration++
        vectorJob?.cancel()
        vectorJob = null
        memoryVectorBusy = false
        memoryVectorStatus = null
        memoryVectorFailed = false
        if (enabled) ensureVectorRuntime(vectorGeneration)
    }

    /** 重启记忆页时清掉上一次的临时状态；安装进行中则保留进度。 */
    fun clearMemoryVectorStatus() {
        if (!memoryVectorBusy) {
            memoryVectorStatus = null
            memoryVectorFailed = false
        }
    }

    /** 检测稠密运行依赖，缺失时安装并复检。 */
    private fun ensureVectorRuntime(generation: Int) {
        vectorJob = summaryScope.launch {
            memoryVectorBusy = true
            memoryVectorStatus = "正在检测运行依赖…"
            memoryVectorFailed = false
            try {
                val check = withContext(Dispatchers.IO) { MemoryKernel.checkDenseRuntime(appContext) }
                if (generation != vectorGeneration) return@launch
                val runtime = check.getOrNull()
                if (runtime == null) {
                    memoryVectorStatus = "依赖检测失败：" + (check.exceptionOrNull()?.message ?: "未知原因")
                    memoryVectorFailed = true
                    return@launch
                }
                if (runtime.ready) {
                    memoryVectorStatus = "依赖正常：" + runtime.detail
                    return@launch
                }
                memoryVectorStatus = "缺少运行依赖，正在安装…"
                val install = withContext(Dispatchers.IO) { MemoryKernel.installDenseRuntime(appContext) }
                if (generation != vectorGeneration) return@launch
                if (install.isFailure) {
                    memoryVectorStatus = install.exceptionOrNull()?.message ?: "依赖安装失败"
                    memoryVectorFailed = true
                    return@launch
                }
                val recheck = withContext(Dispatchers.IO) { MemoryKernel.checkDenseRuntime(appContext) }
                if (generation != vectorGeneration) return@launch
                val ready = recheck.getOrNull()
                if (ready?.ready == true) {
                    memoryVectorStatus = "依赖正常：" + ready.detail
                    memoryVectorFailed = false
                } else {
                    memoryVectorStatus = "依赖安装未通过检测：" +
                        (ready?.detail ?: recheck.exceptionOrNull()?.message ?: "未知原因")
                    memoryVectorFailed = true
                }
            } finally {
                if (generation == vectorGeneration) memoryVectorBusy = false
            }
        }
    }

    /**
     * 记忆动作统一入口：进 IO 跑内核，回来整份替换 [memoryState]。
     *
     * 先置 busy 是因为一次动作要跑两三个 CLI，页面上得先有反馈；MemoryAdmin 返回的 state
     * 自带 busy=false，所以回来直接替换就是「结束」状态。
     * 整段包 runCatching：铺库/跑内核涉及外部存储与 python3，抛异常是常态，
     * 那种情况该退回「无记忆」继续聊，不能让一次记忆故障把对话带崩。
     */
    private fun runMemoryAction(action: suspend () -> MemoryState) {
        memoryState = memoryState.copy(busy = true)
        summaryScope.launch {
            val startedAt = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching { withTimeoutOrNull(MEMORY_ACTION_TIMEOUT_MS) { action() } }
            }
            val elapsed = System.currentTimeMillis() - startedAt
            val done = result.getOrNull()
            if (done != null) {
                FileLog.log("memory", "记忆操作完成：耗时 " + elapsed + " 毫秒")
                memoryState = done
                return@launch
            }
            // 超时与异常都必须把 busy 收回来。此前没有任何兜底：一次不返回，
            // 「处理中…」与「扫描中…」就永久留在页面上，且日志里连失败都看不到。
            val timedOut = result.isSuccess
            val reason = result.exceptionOrNull()
            val msg = if (timedOut) {
                "记忆操作超时（" + (MEMORY_ACTION_TIMEOUT_MS / 1000) + " 秒未返回），已恢复可用，可重试一次"
            } else {
                "记忆操作失败：" + (reason?.message ?: reason?.javaClass?.simpleName ?: "未知原因")
            }
            FileLog.log("memory", msg + "（耗时 " + elapsed + " 毫秒）")
            memoryState = memoryState.copy(busy = false, error = msg)
        }
    }

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
