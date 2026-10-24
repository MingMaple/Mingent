package com.zmf.agent.ui.chat

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.zmf.agent.agent.ApiConfig
import com.zmf.agent.agent.context.ContextCompactor
import com.zmf.agent.agent.context.ContextEvent
import com.zmf.agent.agent.context.ContextEventKind
import com.zmf.agent.agent.context.ContextEventStore
import com.zmf.agent.agent.context.ContextPolicy
import com.zmf.agent.agent.context.ContextSnapshot
import com.zmf.agent.agent.context.ContextStore
import com.zmf.agent.agent.context.ContextSummarizer
import com.zmf.agent.agent.context.contextSummarySystemText
import com.zmf.agent.agent.goal.Goal
import com.zmf.agent.agent.goal.GoalPhase
import com.zmf.agent.agent.goal.GoalStore
import com.zmf.agent.agent.goal.label
import com.zmf.agent.agent.loop.AgentLoop
import com.zmf.agent.agent.mcp.McpManager
import com.zmf.agent.agent.loop.AgentUi
import com.zmf.agent.agent.memory.MemoryAdmin
import com.zmf.agent.agent.memory.MemoryKernel
import com.zmf.agent.agent.memory.MemoryState
import com.zmf.agent.agent.loop.ContextSummaryState
import com.zmf.agent.agent.permission.PermissionStore
import com.zmf.agent.agent.prompt.PromptBuilder
import com.zmf.agent.agent.prompt.PromptInput
import com.zmf.agent.agent.prompt.PromptStore
import com.zmf.agent.agent.skill.SkillStore
import com.zmf.agent.agent.provider.DeepSeekProvider
import com.zmf.agent.agent.provider.ProviderConfig
import com.zmf.agent.agent.provider.ProviderStore
import com.zmf.agent.agent.session.ChatSession
import com.zmf.agent.agent.session.PlanDoc
import com.zmf.agent.agent.session.PlanStore
import com.zmf.agent.agent.session.SessionStore
import com.zmf.agent.agent.session.Todo
import com.zmf.agent.agent.session.TodoStore
import com.zmf.agent.agent.stats.UsageLedger
import com.zmf.agent.agent.subagent.SubAgentDeps
import com.zmf.agent.agent.subagent.SubAgentRunner
import com.zmf.agent.agent.workspace.UploadStore
import com.zmf.agent.agent.workspace.WorkSpace
import com.zmf.agent.agent.workspace.WorkSpaceStore
import com.zmf.agent.core.AppForeground
import com.zmf.agent.core.backup.BackupArchive
import com.zmf.agent.core.backup.BackupManifest
import com.zmf.agent.core.backup.BackupOptions
import com.zmf.agent.core.backup.ImportReport
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.core.notify.Notifier
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolRegistry
import com.zmf.agent.tools.ToolResult
import com.zmf.agent.tools.displayName
import com.zmf.agent.tools.spill.SpillStore
import com.zmf.agent.ui.settings.BusySendMode
import com.zmf.agent.ui.settings.GeneralPrefs
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * 正文必须带给模型——不然它只会按自己最后写的那份做，选中的是哪份就丢了；
 * 但**不该出现在用户气泡里**：计划页上已经能逐条看，聊天里再糊一屏只会把这条气泡
 * 撑成一个要上下滑的模块（2026-10-25 用户：既然有具体计划页面了，为什么还要在气泡里打印出来）。
 * 所以发送时拼、气泡渲染时按这个标记整段裁掉。
 */
internal const val PLAN_DETAIL_MARKER = "【计划原文·仅供模型执行】"

/**
 * 排队等发送的一条消息。
 *
 * 只存纯文本，不存附件/图片：等它轮到时走的就是普通发送流程（[ChatViewModel.send]），
 * 附件按那一刻输入框里的状态一起拼进 prompt —— 和用户此刻手动发一条完全同路径，
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
    val completionTokens: Int = 0,
    /** 上一轮回复的模型耗时与输出 tokens：输出速度只用这一对算（累计值在旧会话里是残缺的）。 */
    val lastTurnModelMillis: Long = 0,
    val lastTurnOutputTokens: Int = 0
)

// 下面几个常量放文件顶层而不是 companion：它们只被 ChatViewModel 的正文用到，
// 顶层声明的作用域最直白（不必再猜「这个名字在哪个 object 里」）。

/** 洞察页趋势只画最近这么多次回复：再多就糊成一团，也看不出变化。 */
private const val INSIGHT_TREND_POINTS = 20

/**
 * 目标自动续跑的轮次上限。
 *
 * 上限是为了防跑飞而不是省钱：目标跑歪了、模型自己判不出做完时，
 * 八轮之后必须停下来让人看一眼，不能靠它自己醒。
 */
private const val MAX_GOAL_ROUNDS = 8

/** 批准计划时替用户发出的那句话；正文与日志共用一份，别两处各写一遍。 */
private const val PLAN_APPROVE_TEXT = "按计划开始执行"

/** 目标工具名：它一跑完目标就变了，得立刻重读（见 onToolFinished）。 */
private const val GOAL_TOOL = "goal"

/**
 * 溢出恢复的等待上限。
 *
 * 摘要请求自己有 60 秒超时（ContextSummarizer.TIMEOUT_MS），这里再宽 30 秒：
 * 超过还没回调，就说明这条链路不正常了，宁可放弃也不要挂着这一轮。
 */
private const val OVERFLOW_COMPACT_TIMEOUT_MS = 90_000L

/**
 * 对话页状态容器（M4：接上 Agent 循环）。
 *
 * 故意不继承 ViewModel（工程里没有 lifecycle-viewmodel 依赖）：
 * 由 Compose 的 remember 持有实例，由 rememberCoroutineScope 驱动循环。
 * 所有可变字段都是 Compose 快照状态，UI 读它们就会自动重组。
 */
class ChatViewModel(context: Context) : AgentUi {

    /**
     * 待发队列与单条消息的数量上限：对齐 DSH。
     */
    companion object {
        /** 单条消息最多带几张图片：对齐 DSH。待发图片行的展示上限也复用它，别另写数字。 */
        internal const val MAX_IMAGES = 20

        /** 单条消息最多带几个普通附件：对齐 DSH。 */
        /** 单张图片的源文件上限：20 MB（模型看到的是压缩后的 JPEG，这条只拦超大原图）。 */
    private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

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
    var contextPolicy by mutableStateOf(ContextStore.load(appContext))
        private set

    /** 上下文快照（估算 tokens / 水位 / 覆盖条数）；由 [refreshContext] 算，界面只读。 */
    var contextSnapshot by mutableStateOf(ContextSnapshot())
        private set

    /** 当前会话摘要文本；空串 = 没有摘要，请求视图发全量。直接读会话表，落盘即生效。 */
    val contextSummary: String
        get() = sessionList.firstOrNull { it.id == currentSessionId }?.contextSummary.orEmpty()

    /** 摘要失败后置 true：请求视图退成「只发最近 fallbackKeepRounds 轮」，不再阻塞主请求。 */
    private var contextFallback = false

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
    val toolOutcomes = mutableStateMapOf<String, Boolean>()

    /** 全部会话，按 updatedAt 降序（第一个就是最近用过的）；侧边栏直接用。 */
    var sessionList by mutableStateOf<List<ChatSession>>(emptyList())
        private set

    /** 当前会话 id；空串只可能出现在 init 跑完之前。 */
    var currentSessionId by mutableStateOf("")
        private set

    /** 全部工作区：默认工作区 + 用户自建的，侧边栏按它分组（至少保证有一个）。 */
    var workspaceList by mutableStateOf<List<WorkSpace>>(emptyList())
        private set

    /** 当前工作区 id；null 表示「未分类」（不属于任何工作区的会话都挂在这里）。 */
    var currentWorkspaceId by mutableStateOf<String?>(null)
        private set

    var isStreaming by mutableStateOf(false)
        private set

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
    var usageText by mutableStateOf<String?>(null)
        private set

    /**
     * 统计页的快照（轮数 / 步数 / token / 上下文水位）。
     *
     * 只有页面需要时才刷新：轮数与水位要遍历消息，不能每帧算，由 [refreshStats] 在进页面时算一次。
     */
    var stats by mutableStateOf(UsageStats())
        private set

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

    /** 输入框内容（受控状态）。回滚时把被删掉的那条提问填回这里，光标落在末尾。 */
    var inputText by mutableStateOf(TextFieldValue(""))
        private set

    /** 待发送附件：选中的文件已经复制进当前工作区，点发送时才拼进提问内容。 */
    val attachments = mutableStateListOf<PendingFile>()

    /**
     * 待发送图片：单独一条队列，因为图片除了路径还要读盘编码成 data URL 直接发给模型。
     *
     * relPath 仍是工作区相对路径：界面靠它解码缩略图，会话落盘也只存这个路径。
     */
    val pendingImages = mutableStateListOf<PendingFile>()

    /** 当前会话的待办清单（模型用 todo_write 写、TodoStore 落盘）；换会话与每轮结束后都重读一次。 */
    var todos by mutableStateOf<List<Todo>>(emptyList())
        private set

    /** 当前在干什么：「第 2 轮」「调用 read_file」之类，空闲时为 null。 */
    var activityText by mutableStateOf<String?>(null)
        private set

    private var streamJob: Job? = null

    /**
     * 本轮（这一次回复）累计的 usage：[onUsage] 每来一次就加，收尾时一次性写进本轮最后一条 assistant。
     *
     * 一轮里可能要问模型好几次（每次工具调用后都要再问一次），气泡只该显示这一整轮的总账，
     * 所以先攒着，等 [streamJob] 的 finally 才落笔。
     */
    private var turnPromptTokens = 0
    private var turnCompletionTokens = 0

    /** 本回合从哪条消息开始（含）：收尾只在 [turnStartIndex, 末尾] 里找本轮最后一条 assistant。 */
    private var turnStartIndex = 0

    /** 繁忙（正在生成回复）时又发消息的行为：排队等本轮结束，或插进正在跑的这一轮。 */
    var busyMode by mutableStateOf(GeneralPrefs.loadBusyMode(appContext))
        private set

    /** 排队待发的消息（只有排队模式会进这里）；本轮结束后自动取第一条发出去。 */
    val pendingQueue = mutableStateListOf<QueuedMessage>()

    /** 队列 id 自增：同一毫秒连发两条也不会撞 id。 */
    private var nextQueueId = 1L

    /**
     * 有没被回答的插话。
     *
     * 插话是「往历史里塞一条 user 消息」，模型可能在本轮后续的请求里就把它答了，
     * 也可能这一轮已经结束（见 streamJob 的 finally）：后者要再跑一个不追加用户消息的回合接上。
     */
    private var steerPending = false

    /** 上下文分段估算（系统提示词 / 工具表 / user / assistant / 工具结果）；由 [refreshContext] 顺带刷新。 */
    var contextBreakdown by mutableStateOf(Breakdown())
        private set

    /** 当前会话的摘要/压缩事件（时间正序，只读展示）；换会话与每次压缩动作后刷新。 */
    var contextEventList by mutableStateOf<List<ContextEvent>>(emptyList())
        private set

    /** 本次回复的模型 / 工具耗时（毫秒）：AgentLoop 收尾回调一次，[commitTiming] 后清零。 */
    private var turnModelMillis = 0L
    private var turnToolMillis = 0L

    /** 本会话的时间账；换会话、收到 usage、回复收尾都会从会话记录重新读回。 */
    var sessionTiming by mutableStateOf(TimingSnapshot())
        private set

    /** 洞察页的提示词长度趋势（最近若干次回复的输入 tokens）；由 [refreshInsight] 刷新。 */
    var insightTrend by mutableStateOf<List<Int>>(emptyList())
        private set

    /** 日志导出的状态文本（成功/失败各一句）；null = 没导过。 */
    var logExportStatus by mutableStateOf<String?>(null)
        private set

    /** 记忆内核对外的状态（字段由 MemoryAdmin 定义）；由 [refreshMemory] 刷新。 */
    var memoryState by mutableStateOf(MemoryState())
        private set

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
        // 全局总账先读回来：stats.json 坏掉 / 缺字段时里面自己回落到 0，不会影响会话表
        UsageLedger.load(appContext)
        // 工作区表先读回来：默认工作区永远在，后面决定当前工作区时要用它兜底
        workspaceList = WorkSpaceStore.load(appContext)
        // 再把会话表读回来，决定当前会话：有历史就续上最近那条，没有就开一条空的
        // 顺便兜底：会话引用了已不存在的工作区（workspaces.json 丢了/坏了）就当未分类，
        // 否则这些会话在界面分组里会直接消失
        val loaded = SessionStore.load(appContext).sortedByDescending { it.updatedAt }
        val validIds = workspaceList.map { space -> space.id }.toSet()
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
            currentWorkspaceId = workspaceList.firstOrNull()?.id
            val fresh = SessionStore.newSession().copy(workspaceId = currentWorkspaceId)
            sessionList = listOf(fresh)
            currentSessionId = fresh.id
        } else {
            sessionList = sessions
            val latest = sessions.first()
            currentSessionId = latest.id
            // 「上次的选择」就用最近那条会话的归属：它属于哪个工作区就进哪个工作区；
            // 它没有归属（老数据 → 未分类）时退回列表第一个，保证当前工作区始终是合法 id
            currentWorkspaceId = latest.workspaceId ?: workspaceList.firstOrNull()?.id
            messages.clear()
            messages.addAll(latest.messages)
        }
        // 顺带扫一次 Skill 目录：提示词里的 available_skills 段要用，不能等到第一次请求再读盘
        refreshSkillCatalog()
        // 顺手清掉 7 天前的 spill 落盘（后台做，失败也无所谓，不阻塞启动）
        summaryScope.launch {
            runCatching { withContext(Dispatchers.IO) { SpillStore.cleanup(appContext) } }
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

    /** 设置页改策略：夹到合法范围、落盘、立刻重算快照（下一次请求就用新的）。 */
    fun applyContextPolicy(p: ContextPolicy) {
        contextPolicy = ContextStore.normalize(p)
        ContextStore.save(appContext, contextPolicy)
        refreshContext()
        FileLog.log("context", "策略更新：" + contextPolicy.summary)
    }

    /**
     * 重算快照：估算的是「真正会发出去的那份请求视图」——
     * 有摘要就是 system(摘要) + summaryUpTo 之后的原文，没有摘要就是全量（摘要失败时按兜底轮数裁）。
     * 分母是 ApiConfig.CONTEXT_WINDOW；不另写估算器，直接复用 ContextCompactor.estimate。
     */
    fun refreshContext() {
        val summary = contextSummary
        val upTo = if (summary.isEmpty()) 0 else normalizedSummaryUpTo()
        val start = requestStart(summary, upTo)
        val tokens = ContextCompactor.estimate(requestView(start, summary, upTo))
        val window = ApiConfig.CONTEXT_WINDOW
        val session = sessionList.firstOrNull { it.id == currentSessionId }
        contextSnapshot = ContextSnapshot(
            estimatedTokens = tokens,
            window = window,
            ratio = if (window > 0) tokens.toFloat() / window else 0f,
            coveredMessages = start,
            totalMessages = messages.size,
            hasSummary = summary.isNotEmpty() && upTo > 0,
            lastSummaryAt = session?.lastSummaryAt ?: 0L
        )
        // 三段估算在末尾顺带刷一次：用户只有看到「系统提示词 / 工具表 / 消息」各占多少，
        // 才知道水位是被提示词撑起来的还是被历史撑起来的
        refreshContextBreakdown()
    }

    /**
     * 请求视图的起点：已摘要覆盖到的下标，摘要失败时再被兜底裁轮往后推。
     *
     * refreshContext 与 refreshContextBreakdown 都从这里取，两处口径不会走偏。
     */
    private fun requestStart(summary: String, upTo: Int): Int {
        var start = upTo
        if (contextPolicy.enabled && contextFallback) {
            start = maxOf(start, ContextCompactor.tailStartIndex(messages, contextPolicy.fallbackKeepRounds))
        }
        return start
    }

    /** 真正会发出去的那份请求视图：摘要抬头 + 起点之后的原文（口径与 AgentLoop 一致）。 */
    private fun requestView(start: Int, summary: String, upTo: Int): List<ChatMessage> {
        val request = ArrayList<ChatMessage>(messages.size - start + 1)
        if (summary.isNotEmpty() && upTo > 0) {
            // 抬头和 AgentLoop 用同一个函数，保证估算口径与实际请求一致
            request.add(ChatMessage(role = "system", content = contextSummarySystemText(summary)))
        }
        for (i in start until messages.size) request.add(messages[i])
        return request
    }

    /**
     * 重算上下文分段估算。
     *
     * - system：AgentLoop 每次请求现拼的那条系统提示词。这里用同一份 [promptInput] 再拼一次
     *   （PromptBuilder 是纯函数，多拼一次不产生副作用），拼不出来（全部为空）记 0；
     * - tool：当前权限档 / 计划模式下的工具表，按 name + 描述 + 参数 JSON 文本长度估；
     * - user / assistant / toolResult：把请求视图按 role 拆开估，工具结果是历史里涨得最快的部分。
     *
     * 各段是独立视角，加起来不保证等于水位（摘要抬头在 system 里，同时也在请求视图里）。
     */
    fun refreshContextBreakdown() {
        val summary = contextSummary
        val upTo = if (summary.isEmpty()) 0 else normalizedSummaryUpTo()
        val request = requestView(requestStart(summary, upTo), summary, upTo)
        val summaryText = if (summary.isNotEmpty() && upTo > 0) summary else ""
        // planMode 由 promptInput() 自己带（与 AgentLoop 同一份输入）；摘要单独量一遍，
        // 不然「提示词」这段把摘要也算进去，用户会以为提示词本身就有几千 token
        val promptOnly = PromptBuilder.build(promptInput().copy(summaryText = ""))
        val systemTokens = promptOnly?.let { ContextCompactor.estimateText(it) } ?: 0
        val summaryTokens = if (summaryText.isBlank()) {
            0
        } else {
            ContextCompactor.estimateText(contextSummarySystemText(summaryText))
        }
        // 工具表：真发出去的是 JSON，但引号逗号的开销相对整段可忽略；
        // 不构造 JSON 也就没有「某个工具的参数 JSON 不合法直接抛」的风险
        val specs = registry.specs(permissionLevel, planMode)
        val toolTokens = if (specs.isEmpty()) {
            0
        } else {
            ContextCompactor.estimateText(
                specs.joinToString("\n") { it.name + "\n" + it.description + "\n" + it.parametersJson }
            )
        }
        var userTokens = 0
        var assistantTokens = 0
        var toolResultTokens = 0
        for (message in request) {
            when (message.role) {
                // 摘要抬头在 system 那一段已经算过，这里跳过 system，免得重复计入
                "user" -> userTokens += ContextCompactor.estimate(message)
                "assistant" -> assistantTokens += ContextCompactor.estimate(message)
                "tool" -> toolResultTokens += ContextCompactor.estimate(message)
                else -> Unit
            }
        }
        contextBreakdown = Breakdown(
            systemTokens = systemTokens,
            summaryTokens = summaryTokens,
            toolTokens = toolTokens,
            userTokens = userTokens,
            assistantTokens = assistantTokens,
            toolResultTokens = toolResultTokens
        )
    }

    /**
     * 手动立即压缩（设置页 / 长按按钮）。流式回复中不允许：两路请求抢同一个会话没有意义。
     * 结果通过 [onDone] 回主线程：true = 摘要成功，false = 没做或失败（已退兜底）。
     */
    fun summarizeNow(onDone: (Boolean) -> Unit = {}, force: Boolean = false) {
        if (isStreaming) {
            FileLog.log("context", "正在生成回复，手动压缩先跳过")
            onDone(false)
            return
        }
        startCompaction(onDone, force)
    }

    /**
     * 真正跑压缩（不含「流式中」那道闸）。
     *
     * 为什么要把闸门拆出去：溢出恢复必须在流式中调用——那一轮已经被提供方按上下文超限拒了，
     * 不压缩就没法重发；「两路请求抢同一个会话」这条顾虑只适用于用户手点压缩。
     */
    private fun startCompaction(onDone: (Boolean) -> Unit, force: Boolean) {
        if (!contextPolicy.enabled && !force) {
            FileLog.log("context", "摘要开关关着且未强制，手动压缩跳过")
            onDone(false)
            return
        }
        refreshContext()
        activityText = "正在压缩上下文…"
        summaryScope.launch {
            val ok = try {
                runSummary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 手动路径同样不能抛：置兜底标记，请求视图退成最近几轮
                contextFallback = true
                refreshContext()
                recordCompressFallback("手动压缩异常：" + (e.message ?: e.javaClass.simpleName))
                FileLog.log("context", "手动压缩异常，退回兜底：" + e)
                false
            } finally {
                activityText = null
            }
            persistAll()
            onDone(ok)
        }
    }

    /**
     * AgentLoop 的溢出恢复回调：压一次上下文，成功就让它重发同一轮。
     *
     * 为什么套一层超时：summarizeNow 那套是回调式 API，任何一条路径漏掉回调都会把这一轮永久挂住；
     * 超时之后最坏只是「这次没压成」，AgentLoop 会把提供方原话报给用户，不会卡死。
     * 走 [startCompaction] 而不是 summarizeNow：溢出发生在流式中，那道「流式中别压」的闸必须绕开。
     */
    private suspend fun onOverflowCompaction(): Boolean =
        withTimeoutOrNull(OVERFLOW_COMPACT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                startCompaction(
                    onDone = { ok -> if (cont.isActive) cont.resume(ok) },
                    force = true
                )
            }
        } ?: false

    /** 清除本会话摘要：请求视图恢复全量（旧消息本来就都在，不需要恢复任何内容）。 */
    fun clearContextSummary() {
        if (isStreaming) return
        contextFallback = false
        val id = currentSessionId
        // 清除前的分界要留证：清完这一条就没了，事件里得写上当时压到哪
        val boundary = sessionList.firstOrNull { it.id == id }?.summaryUpTo ?: 0
        sessionList = sessionList.map {
            if (it.id == id) it.copy(contextSummary = "", summaryUpTo = 0, lastSummaryAt = 0L) else it
        }
        persistCurrent()
        persistAll()
        refreshContext()
        recordContextEvent(kind = ContextEventKind.CLEAR, boundary = boundary)
        FileLog.log("context", "已清除会话 " + id + " 的摘要，恢复全量请求视图")
    }

    /** 重读当前会话的上下文事件：列表是只读展示，整份替换即可。 */
    private fun reloadContextEvents() {
        contextEventList = ContextEventStore.load(appContext, currentSessionId)
    }

    /**
     * 记一条上下文事件并立刻刷新列表。
     *
     * 为什么写完马上重读：事件是给人排查用的，用户点完「立即压缩」就该在页面上看到这一条，
     * 等他切会话再切回来才出现，等于告诉他「刚才那次压缩没记录」。
     */
    private fun recordContextEvent(
        kind: ContextEventKind,
        ok: Boolean = true,
        fromMessages: Int = 0,
        foldedMessages: Int = 0,
        boundary: Int = 0,
        keptRounds: Int = 0,
        note: String = ""
    ) {
        ContextEventStore.append(
            appContext,
            currentSessionId,
            ContextEvent(
                at = System.currentTimeMillis(),
                kind = kind,
                ok = ok,
                fromMessages = fromMessages,
                foldedMessages = foldedMessages,
                boundary = boundary,
                keptRounds = keptRounds,
                note = note
            )
        )
        reloadContextEvents()
    }

    /**
     * 压缩失败 → 退兜底：两条事件一起记。
     *
     * 为什么分两条：失败原因是「这次没压成」，兜底窗口是「没压成之后发的是什么」，
     * 合成一条就没法分别按类型过滤，页面上也说不清。
     */
    private fun recordCompressFallback(reason: String) {
        recordContextEvent(kind = ContextEventKind.COMPRESS, ok = false, note = reason)
        recordContextEvent(
            kind = ContextEventKind.FALLBACK,
            keptRounds = contextPolicy.fallbackKeepRounds
        )
    }

    /**
     * 这次摘要要覆盖到哪：逐字保留区的起点（user 边界）。
     *
     * 保留区的口径按 DSH：优先级是「逐字保留预算（(W−O)×retainRatio 或绝对 token）」，
     * 同时不能低于 fallbackKeepRounds 轮；窗口与输出预留都从 ApiConfig 取，和请求口径一致。
     */
    private fun summaryBoundary(): Int =
        ContextCompactor.retainStart(messages, contextPolicy)

    /**
     * 双触发闸门（或关系）：
     * ① 估算 tokens / 上下文窗口 ≥ triggerRatio；
     * ② 自上次摘要后新增 user 消息数 ≥ triggerCount（可关）。
     * 前提是「分界确实往前走了」——没有可摘要的内容就不触发，免得空转重复调模型。
     */
    private fun shouldSummarize(): Boolean {
        val policy = contextPolicy
        if (!policy.enabled) return false
        refreshContext()
        val boundary = summaryBoundary()
        val covered = normalizedSummaryUpTo()
        if (boundary <= covered) return false
        if (contextSnapshot.ratio >= policy.triggerRatio) return true
        if (policy.triggerByCount) {
            // +1：这条即将发出的 user 消息还没进 messages，但它本来就算「新增的一条」
            var count = 1
            for (i in covered until messages.size) if (messages[i].role == "user") count++
            if (count >= policy.triggerCount) return true
        }
        return false
    }

    /**
     * 跑一次摘要（与上一条摘要融合成新的）：
     * 成功 → 写回会话三字段并落盘；失败/超时/空结果 → 只置兜底标记，主请求照常发。
     */
    private suspend fun runSummary(): Boolean {
        val policy = contextPolicy
        val boundary = summaryBoundary()
        val covered = normalizedSummaryUpTo()
        if (boundary <= covered) {
            // 消息还不够多（或全被摘要覆盖）：没有可摘要的部分，不白花一次请求
            FileLog.log("context", "没有可摘要的部分（分界 " + boundary + " ≤ 已覆盖 " + covered + "）")
            return false
        }

        val transcript = ArrayList<ChatMessage>(boundary - covered)
        for (i in covered until boundary) transcript.add(messages[i])
        val previous = contextSummary
        val useModel = policy.summaryModel.ifBlank { model }
        val startedAt = System.currentTimeMillis()
        val text = ContextSummarizer.summarize(
            provider = provider,
            model = useModel,
            previous = previous,
            transcript = transcript
        )

        if (text.isNullOrBlank()) {
            // 失败兜底：请求视图只发最近 fallbackKeepRounds 轮，绝不阻塞、也不破坏主请求
            contextFallback = true
            refreshContext()
            recordCompressFallback("摘要结果为空（" + (System.currentTimeMillis() - startedAt) + "ms）")
            FileLog.log(
                "context",
                "摘要失败（" + (System.currentTimeMillis() - startedAt) + "ms），兜底只发最近 " +
                    policy.fallbackKeepRounds + " 轮"
            )
            return false
        }

        contextFallback = false
        val id = currentSessionId
        val now = System.currentTimeMillis()
        sessionList = sessionList.map {
            if (it.id == id) {
                it.copy(contextSummary = text, summaryUpTo = boundary, lastSummaryAt = now)
            } else {
                it
            }
        }
        // 先把三字段写进 sessionList，再 persistCurrent：后者只覆盖 messages/updatedAt，字段保得住
        persistCurrent()
        refreshContext()
        // 记一条压缩事件：fromMessages 是压缩前的消息数，foldedMessages 与 boundary 同口径（分界即折叠条数）
        recordContextEvent(
            kind = ContextEventKind.COMPRESS,
            ok = true,
            fromMessages = messages.size,
            foldedMessages = boundary,
            boundary = boundary
        )
        FileLog.log("context", "摘要完成：" + transcript.size + " 条原文 → " + text.length + " 字，分界 " + boundary)
        return true
    }

    /** 给 AgentLoop 的视图信息：摘要文本 + 分界 + 兜底标记；分界失效就当作没有摘要（发全量最安全）。 */
    private fun summaryState(): ContextSummaryState {
        val summary = contextSummary
        if (summary.isEmpty()) return ContextSummaryState(fallback = contextFallback)
        val upTo = normalizedSummaryUpTo()
        if (upTo <= 0) return ContextSummaryState(fallback = contextFallback)
        return ContextSummaryState(text = summary, upTo = upTo, fallback = contextFallback)
    }

    /** 落盘的 summaryUpTo 只有在「是 user 消息边界、且在范围内」时才算数；失效一律当 0（全量）。 */
    private fun normalizedSummaryUpTo(): Int {
        val saved = sessionList.firstOrNull { it.id == currentSessionId }?.summaryUpTo ?: 0
        if (saved <= 0 || saved >= messages.size) return 0
        if (messages[saved].role != "user") return 0
        return saved
    }

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
     * Key / 端点 / 模型全都跟着 provider 走，所以不在这里单独读它们——[rebuildClient] 统一读。
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
            nowText = nowText(),
            skills = skills,
            extraInstructions = PromptStore.loadExtra(appContext),
            systemOverride = PromptStore.loadOverride(appContext)
        )
    }

    /** 时间只精确到分钟：提示词里的当前时间只为让模型知道「今天是哪天」。 */
    private fun nowText(): String = runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm EEE", Locale.CHINA).format(Date())
    }.getOrDefault("")

    /** 重新扫描 appdata/skills（导入/删除 Skill 之后调）。 */
    fun refreshSkillCatalog() {
        skillCatalog = runCatching { SkillStore.catalog(appContext) }.getOrDefault(emptyList())
        // 斜杠补全只认名字：在这里从同一份缓存派生，装/删 Skill 后不会出现「提示词里有、补全里没有」
        skillNames = skillCatalog.map { it.first }
        FileLog.log("skill", "可用 Skill " + skillCatalog.size + " 个")
    }

    fun setPermission(level: PermissionLevel) {
        if (level == permissionLevel) return
        permissionLevel = level
        PermissionStore.save(appContext, level)
        FileLog.log("permission", "权限档切到 " + level.displayName())
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
            else -> false
        }
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
     * 选中态在这里一起校正：原选中那份被删掉、或第一次读到计划时，落到最新的一份——
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
            // 而不是还停在上次看过的旧计划上——2026-10-25 用户的明确要求）
            fresh.isNotEmpty() -> fresh.last().id
            // 没有新计划就停在用户上次看的那份：从计划页返回主页再进来，还是同一页
            current != null && list.any { it.id == current } -> current
            list.isNotEmpty() -> list.last().id
            else -> null
        }
    }

    /**
     * 新一轮对话开始前把「还没处理过」的计划标成废弃（用户 2026-10-25 的规则）。
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
     * 记日志、落盘，而不是偷偷改状态让模型「自己开始」。流式中直接忽略——会和正在跑的那轮抢 messages。
     * [plans] 不清：同意之后计划预览还要能对着逐条执行、回看。
     */
    fun approvePlan(planId: String) {
        if (isStreaming) return
        val target = plans.firstOrNull { it.id == planId } ?: plans.lastOrNull()
        // 双保险：页面上按钮已经灰了，这里再拦一道——两份真值可能差一拍
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
        send(if (target != null) approvePlanText(target) else PLAN_APPROVE_TEXT, summaryScope)
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
     * 只改 objective：轮次与阶段是进度，改一句话不该把它们清零——
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

    /** 删除当前会话的目标。 */
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

    fun updateInput(text: TextFieldValue) {
        inputText = text
    }

    fun clearError() {
        lastError = null
    }

    // ---- 附件 ----

    /** 当前工作区 uploads 的占用文本（含未引用部分）；null = 还没统计。 */
    var attachmentUsageText by mutableStateOf<String?>(null)
        private set

    /**
     * 选中的文件复制进当前工作区；IO 线程做，结果回主线程。
     *
     * 图片走 [UploadStore.importImage]（降采样 + 压 JPEG），其它文件原样复制：
     * 图片要「直接被模型看见」，只有压过尺寸的 JPEG 才能当 data URL 塞进请求。
     */
    fun attachFile(uri: Uri, scope: CoroutineScope) {
        // 有些 provider 给不出类型（null）：取不到就按普通文件处理，别把文件当图片压坏
        val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        val isImage = mime?.startsWith("image/") == true
        // 满了就不再导入：连文件都不往工作区复制，省得白留一份没人引用的附件
        if (!canEnqueuePending(isImage)) return
        // 单张图片源文件上限 20 MB（对齐 DSH）：拿不到长度（-1）就不拦，交给导入时的压缩兜底
        if (isImage) {
            val bytes = runCatching {
                appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            if (bytes > MAX_IMAGE_BYTES) {
                lastError = "单张图片最大 20 MB"
                return
            }
        }
        scope.launch(Dispatchers.IO) {
            // 取当前工作区的口径和 send 完全一致：未分类 / 找不到时用默认工作区
            val space = workspaceList.firstOrNull { it.id == currentWorkspaceId }
                ?: WorkSpaceStore.defaultSpace(appContext)
            val root = rootOf(space)
            val result = if (isImage) {
                UploadStore.importImage(appContext, uri, root)
            } else {
                UploadStore.import(appContext, uri, root)
            }
            result.fold(
                onSuccess = { imported ->
                    val added = withContext(Dispatchers.Main) {
                        // 并发选多张时这里再查一次：入口那次检查可能都没满，回来却已经超了
                        if (!canEnqueuePending(isImage)) return@withContext false
                        val pending = PendingFile(imported.name, imported.relPath)
                        if (isImage) pendingImages.add(pending) else attachments.add(pending)
                        true
                    }
                    // 没进队列（超上限）就别记「已加入发送队列」，免得日志和界面说法对不上
                    if (added) {
                        FileLog.log(
                            "upload",
                            (if (isImage) "图片已加入发送队列：" else "附件已加入发送队列：") +
                                imported.relPath + "（" + imported.size + " 字节）"
                        )
                    }
                },
                onFailure = { e ->
                    val message = e.message ?: e.javaClass.simpleName
                    withContext(Dispatchers.Main) { lastError = "上传失败：" + message }
                    FileLog.log("upload", (if (isImage) "图片上传失败：" else "附件上传失败：") + message)
                }
            )
        }
    }

    /** 队列还有空位就返回 true；满了顺手把提示写进 lastError，调用方只管按结果决定加不加。 */
    private fun canEnqueuePending(isImage: Boolean): Boolean {
        // 只有图片有数量上限：普通附件按 DSH 口径不限个数，正文里给的是路径
        if (isImage && pendingImages.size >= MAX_IMAGES) {
            lastError = "图片最多 " + MAX_IMAGES + " 张"
            return false
        }
        return true
    }

    fun detachFile(index: Int) {
        if (index in attachments.indices) attachments.removeAt(index)
    }

    fun clearAttachments() {
        attachments.clear()
    }

    fun detachImage(index: Int) {
        if (index in pendingImages.indices) pendingImages.removeAt(index)
    }

    fun clearPendingImages() {
        pendingImages.clear()
    }

    /** 设置页进入时刷一次占用：IO 里扫 uploads 目录，回来只更新一句文本。 */
    fun refreshAttachmentUsage() {
        summaryScope.launch { attachmentUsageText = computeAttachmentUsageText() ?: "统计失败" }
    }

    /**
     * 清理当前工作区里没有被任何会话引用的附件（设置页「清理未引用附件」）。
     *
     * 删除必须放 IO：uploads 里可能是几千个小文件；扫完回主线程拼一句中文给界面。
     * 会话里的路径都是工作区相对的，所以只跟「本工作区 + 未分类」的会话比对，别的工作区不算引用。
     */
    fun cleanOrphanImages(onDone: (String) -> Unit) {
        summaryScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    UploadStore.cleanOrphans(appContext, sessionsOfCurrentWorkspace(), currentWorkspaceRoot())
                }.onFailure {
                    FileLog.log("upload", "清理未引用附件失败：" + it)
                }.getOrNull()
            }
            val text = if (result == null) {
                "清理失败，请查看日志"
            } else if (result.first == 0) {
                "没有可清理的附件"
            } else {
                "已删除 " + result.first + " 个文件，释放 " + mbText(result.second)
            }
            // 清完立刻重算占用，界面上的数字和磁盘一致
            attachmentUsageText = computeAttachmentUsageText() ?: "统计失败"
            onDone(text)
            FileLog.log("upload", "清理未引用附件：" + text)
        }
    }

    /** 「总占用（未引用 N）」文本；统计失败回 null，由调用方给一句失败提示。 */
    private suspend fun computeAttachmentUsageText(): String? {
        val pair = withContext(Dispatchers.IO) {
            runCatching {
                val root = currentWorkspaceRoot()
                val total = UploadStore.usageBytes(root)
                val orphans = UploadStore.orphanReport(appContext, sessionsOfCurrentWorkspace(), root).second
                total to orphans
            }.getOrNull()
        } ?: return null
        return if (pair.second > 0) {
            mbText(pair.first) + "（未引用 " + mbText(pair.second) + "）"
        } else {
            mbText(pair.first)
        }
    }

    /** 字节数 → 一位小数的 MB，跟导出成功那句用同一种写法。 */
    private fun mbText(bytes: Long): String = "%.1f".format(bytes / 1024.0 / 1024.0) + " MB"

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

    /** 当前工作区目录：未分类（或找不到）时回落到默认工作区，和 send / attachFile 完全同一口径。 */
    private fun currentWorkspaceRoot(): File =
        rootOf(
            workspaceList.firstOrNull { it.id == currentWorkspaceId }
                ?: WorkSpaceStore.defaultSpace(appContext)
        )

    /**
     * 工作区根目录：优先用户在「自定义」里选的目录（`rootPath`）。
     *
     * 选了却读不到（授权失效、目录被删、路径不可写）就退回私有目录——但要记一条日志，
     * 免得工具悄悄写到别处、用户还以为落在自己选的文件夹里。
     * （只有 SAF 授权、没有可读绝对路径的工作区目前仍走私有目录，URI 分支待接。）
     */
    private fun rootOf(space: WorkSpace): File {
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
     * 输入框上方还排着的附件/图片同样算引用——文件已经在磁盘上，只是还没发出去。
     */
    private fun sessionsOfCurrentWorkspace(): List<ChatSession> {
        val base = sessionList.filter { it.workspaceId == currentWorkspaceId || it.workspaceId == null }
        val pending = attachments.map { it.relPath } + pendingImages.map { it.relPath }
        if (pending.isEmpty()) return base
        return base + ChatSession(
            id = "pending-uploads",
            messages = listOf(ChatMessage(role = "user", images = pending))
        )
    }

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
     * 落盘必须在这一轮开跑之前同步做完——模型的 todo_write 紧接着就会写同一份文件，
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

    /**
     * 图片的相对路径 → data URL（"data:image/jpeg;base64,..."）。
     *
     * 读盘 + base64 一律在 IO：单张压完 2MB 内，主线程做会明显卡一帧。
     * 单张失败只丢这一张（记日志），不让整条提问发不出去。
     */
    private suspend fun encodeImages(root: File, relPaths: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            relPaths.mapNotNull { rel ->
                runCatching {
                    val bytes = File(root, rel).readBytes()
                    "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                }.onFailure {
                    FileLog.log(
                        "chat",
                        "图片编码失败：" + rel + " / " + (it.message ?: it.javaClass.simpleName)
                    )
                }.getOrNull()
            }
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
     * 结果却跑到主页去，人还留在数据页上等于什么都没看到（2026-10-25 用户的要求）。
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
     * 磁盘上的数据已经是备份里的那一份，但内存里还是旧列表——这时任何一次落盘
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

    fun cancelImport() {
        pendingManifest = null
    }

    fun dismissImportReport() {
        importReport = null
    }

    /** 用户点了「确认导入」。 */
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

    /**
     * 发送按钮回调（旧入口，留给还没把「发送」与「停止」拆成两个键的界面）。
     *
     * 语义已改成「发一条消息」：空闲直接发，正在生成回复时按 [busyMode] 排队或插话。
     * 停止有独立的 [stop]——把发送键当停止键用，会让「排队/插话」这两个设置形同虚设。
     */
    fun onSendOrStop(text: String, scope: CoroutineScope) {
        sendWhileBusy(text, scope)
    }

    /**
     * 发消息的唯一入口：空闲直接发，繁忙时按 [busyMode] 排队或插话。
     *
     * [send] 本身在流式中仍然直接 return——「繁忙时该干什么」只在这里判一次，
     * 散成两处的话排队与插话迟早会走岔。
     */
    fun sendWhileBusy(text: String, scope: CoroutineScope) {
        // 斜杠命令先拦：它们不是消息，忙的时候也该立刻生效（比如中途 /plan off）
        if (handleSlashCommand(text)) return
        if (needsRestart) {
            lastError = "备份刚刚导入，请重启应用后再继续使用，否则新导入的数据将被覆盖"
            return
        }
        val trimmed = text.trim()
        // 与 send 同一口径：只带附件 / 只带图片也算一次提问
        if (trimmed.isEmpty() && attachments.isEmpty() && pendingImages.isEmpty()) return
        if (!isStreaming) {
            send(text, scope)
            return
        }
        if (trimmed.isEmpty()) {
            // 只有附件/图片、没有正文：队列只存文字、插话也只能插文字，
            // 与其排一条空消息到轮到时被静默丢掉，不如直接告诉用户等这一轮结束
            lastError = "正在生成回复，附件等本轮结束后再发送"
            return
        }
        // 插话会把正在回填的工具调用截成半轮（见 steerAppendable），那个窗口里退成排队：
        // 消息一条都不丢，只是晚一点发
        if (busyMode == BusySendMode.STEER && steerAppendable()) {
            steerNow(trimmed)
        } else {
            enqueue(trimmed)
        }
    }

    /**
     * 切换繁忙时的发送行为并落盘（只影响下一次「繁忙时发送」，不打断当前回合）。
     *
     * 为什么不叫 setBusyMode：属性 busyMode 的私有 setter 在 JVM 上就是这个签名，两者会撞车。
     */
    fun applyBusyMode(mode: BusySendMode) {
        if (mode == busyMode) return
        busyMode = mode
        GeneralPrefs.saveBusyMode(appContext, mode)
        FileLog.log("chat", "繁忙时发送行为：" + GeneralPrefs.busyModeLabel(mode))
    }

    /** 改排队消息的正文；改成空串等于把它移出队列（留着只会在轮到时被 send 静默丢掉）。 */
    fun editQueued(id: Long, text: String) {
        val index = pendingQueue.indexOfFirst { it.id == id }
        if (index < 0) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            pendingQueue.removeAt(index)
            FileLog.log("queue", "队列消息 " + id + " 被清空，已移出队列")
            return
        }
        pendingQueue[index] = pendingQueue[index].copy(text = trimmed)
    }

    fun deleteQueued(id: Long) {
        val index = pendingQueue.indexOfFirst { it.id == id }
        if (index < 0) return
        pendingQueue.removeAt(index)
        FileLog.log("queue", "移出队列 " + id + "，剩 " + pendingQueue.size + " 条")
    }

    /**
     * 立即发送队列里的某一条：先从队列取出来，再按 [busyMode] 走一遍繁忙逻辑。
     *
     * 本轮已经结束 → 直接发出去；还在跑 → 排队模式重新排到队尾、插话模式立刻插进历史。
     */
    fun sendQueuedNow(id: Long, scope: CoroutineScope) {
        val index = pendingQueue.indexOfFirst { it.id == id }
        if (index < 0) return
        val item = pendingQueue.removeAt(index)
        FileLog.log("queue", "立即发送队列消息 " + item.id + "：" + item.text.take(80))
        sendWhileBusy(item.text, scope)
    }

    /**
     * 插话能不能安全地追加到历史末尾。
     *
     * 末尾是一条带 tool_calls 的 assistant 时，它还在等工具结果回填：这时插一条 user 进去，
     * 工具结果就会挂在 user 后面，发请求前的那道清洗只能把整轮工具调用丢掉（不丢就是 400）。
     * 这个窗口里退成排队更划算——晚几秒发，但不丢任何一条工具结果。
     */
    private fun steerAppendable(): Boolean {
        val last = messages.lastOrNull() ?: return true
        return last.role != "assistant" || last.toolCalls.isEmpty()
    }

    /** 插话：往历史末尾追加一条 user 消息。界面上立刻可见，本轮后续请求也能看到它。 */
    private fun steerNow(text: String) {
        messages.add(ChatMessage(role = "user", content = text))
        steerPending = true
        // 单独一个分类：插话出了问题（被当成重复提问、模型答了两遍）只能靠这行日志回溯
        FileLog.log("steer", "插话（流式中追加用户消息）：" + text.take(80))
    }

    /** 排队：本轮结束后由 [continueAfterTurn] 自动发出去。 */
    private fun enqueue(text: String) {
        val item = QueuedMessage(nextQueueId++, text)
        pendingQueue.add(item)
        FileLog.log("queue", "繁忙，已排队（第 " + pendingQueue.size + " 条）：" + text.take(80))
    }

    /**
     * 发一条消息：只做校验与拼 prompt，真正开跑交给 [startTurn]。
     *
     * 流式中直接 return —— 繁忙时的行为看 [sendWhileBusy]。
     */
    fun send(text: String, scope: CoroutineScope) {
        // 命令闸门放在最前：任何入口（输入框、队列补发、脚本式调用）都先把 /xxx 吃掉
        if (handleSlashCommand(text)) return
        if (isStreaming) return
        if (needsRestart) {
            lastError = "备份刚刚导入，请重启应用后再继续使用，否则新导入的数据将被覆盖"
            return
        }
        val files = attachments.toList()
        val images = pendingImages.toList()
        // 只发附件 / 只发图片、不写字也算一次提问；三者都空才真的没得发
        if (text.isBlank() && files.isEmpty() && images.isEmpty()) return
        // 新一轮提问：上一轮已经全部打勾的清单在这里收掉（必须在开跑前同步做完，见函数注释）
        dropFinishedTodosBeforeNewRound()
        // 用户没点按钮就直接接着聊：那份计划就此作废（按钮变灰），要执行得重新交一份新的
        abandonPendingPlansBeforeNewTurn()
        // 兜底再夹一次上限：入队时已经卡过，这里防的是历史队列里带回来的超量图片
        val used = images.take(MAX_IMAGES)
        val tooManyImages = images.size > MAX_IMAGES
        val prompt = buildString {
            append(text.trim())
            if (files.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(ATTACHMENT_PATHS_MARKER)
                files.forEach { append("\n- ").append(it.relPath) }
                append("\n（相对路径，用 read_file 读）")
            }
            if (used.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(IMAGE_PATHS_MARKER)
                used.forEach { append("\n- ").append(it.relPath) }
            }
        }.trim()

        // 第一句话顺便当会话标题，侧边栏就不用显示一串「新会话」；只有附件/图片时用文件名
        autoTitleIfNeeded(
            if (text.isBlank()) (files.firstOrNull() ?: used.firstOrNull())?.name ?: prompt else text.trim()
        )

        startTurn(
            userText = prompt,
            prompt = prompt,
            files = files,
            images = used,
            scope = scope,
            tooManyImages = tooManyImages
        )
    }

    /**
     * 起一个回复回合（校验通过之后的一切都在这里）。
     *
     * @param userText 要追加进历史的用户正文；**null = 不追加任何用户消息**，
     *   直接在当前历史之后继续跑。插话之后本轮可能已经结束，需要再跑一回合把它接上，
     *   这时追加会让模型看到同一句话被问了两遍，所以单独给一个 null 入口（AgentLoop 支持）。
     * @param prompt 拼好附件/图片说明之后的正文，只用于日志；与 [userText] 同一个值
     */
    private fun startTurn(
        userText: String?,
        prompt: String,
        files: List<PendingFile>,
        images: List<PendingFile>,
        scope: CoroutineScope,
        tooManyImages: Boolean = false
    ) {
        lastError = null
        // 超过上限只在界面提示一句，不阻断发送（用户已经选了图，全丢掉比只发前几张更糟）。
        // 这句不算「本轮失败」：发通知那一步要排除它，否则成功的回合也会弹「回复失败」
        val imageNotice = if (tooManyImages) {
            "每条消息最多 " + MAX_IMAGES + " 张图片，已只发送前 " + MAX_IMAGES + " 张"
        } else {
            null
        }
        lastError = imageNotice
        usageText = null
        activityText = null
        // 新的一轮：上一轮的用量已经写进气泡了，这里清零重新累计；
        // turnStartIndex 记下本轮起点，收尾写用量时不会写到上一轮的气泡里
        turnPromptTokens = 0
        turnCompletionTokens = 0
        turnStartIndex = messages.size
        // 时间账同样按回合清零：AgentLoop 收尾时会把本回合的模型/工具耗时回调一次
        turnModelMillis = 0
        turnToolMillis = 0
        // 新的一次提问：本次回复步数归零；累计 token 与轮数保留（它们是整个会话的）
        stats = stats.copy(steps = 0)
        // 发送这一刻先对一次水位：状态行与圆环读的是 contextSnapshot，本轮跑完还会再刷一次
        refreshStats()

        val thinking = thinkingEnabled
        val effort = thinkingEffort
        val permission = permissionLevel
        isStreaming = true
        if (userText != null) {
            // 附件/图片已经拼进 prompt 与 images 了：这里立刻清空，避免下一条消息重复带上。
            // 插话续跑（userText == null）不清：输入框里排着的东西属于用户的下一条消息
            attachments.clear()
            pendingImages.clear()
        }

        FileLog.log(
            "chat",
            if (userText != null) {
                "用户输入：" + prompt.take(200) + "｜思考=" + thinking + "（" + effort + "）｜权限=" +
                    permission.displayName() + "｜附件=" + files.size + "｜图片=" + images.size
            } else {
                "插话续跑（不追加用户消息）｜思考=" + thinking + "（" + effort + "）｜权限=" +
                    permission.displayName()
            }
        )

        streamJob = scope.launch {
            // 用户点「停止」也会走到 finally：那种情况既不算完成也不算失败，不发通知
            var stopped = false
            try {
                // 发送前置检查：命中闸门就先摘要（阻塞，失败静默退兜底），摘要完再发主请求
                if (shouldSummarize()) {
                    activityText = "正在压缩上下文…"
                    FileLog.log("context", "命中摘要闸门：" + contextPolicy.summary)
                    runSummary()
                    activityText = null
                }
                // 工具的根目录跟着当前工作区：未分类（currentWorkspaceId == null）或找不到时用默认工作区
                val space = workspaceList.firstOrNull { it.id == currentWorkspaceId }
                    ?: WorkSpaceStore.defaultSpace(appContext)
                val root = rootOf(space)
                // 图片按路径现读现编成 data URL：只对这一次请求有效，不写进会话
                val imagePaths = images.map { it.relPath }
                val imageDataUrls = if (imagePaths.isEmpty()) {
                    emptyList<String>()
                } else {
                    encodeImages(root, imagePaths)
                }
                val rounds = loop.run(
                    messages = messages,
                    // null = 插话续跑：历史里已经有那条用户消息了，不能再追加一遍
                    userText = userText,
                    thinking = thinking,
                    thinkingEffort = effort,
                    toolContext = ToolContext(
                        context = appContext,
                        permission = permission,
                        workspaceRoot = root,
                        sessionId = currentSessionId
                    ),
                    ui = this@ChatViewModel,
                    // 计划模式不再当参数传：AgentLoop 每轮从 promptInput().planMode 现取
                    images = imagePaths,
                    imageDataUrls = imageDataUrls,
                    // 上下文溢出时压一次再重发（true = 压成了）。只从聊天页接：子智能体/定时任务
                    // 没有摘要能力，行为不该被这条路径改变
                    onOverflow = { onOverflowCompaction() }
                )
                FileLog.log("loop", "结束，共 " + rounds + " 轮")
            } catch (e: CancellationException) {
                stopped = true
                FileLog.log("loop", "被用户停止")
                throw e
            } catch (e: Exception) {
                lastError = "运行异常：" + (e.message ?: e.javaClass.simpleName)
                FileLog.log("error", "运行异常：" + e)
            } finally {
                isStreaming = false
                streamJob = null
                activityText = null
                // 本轮用量落到气泡里：必须排在下面的 persistCurrent 之前，否则这次数字要等
                // 下一次保存才进盘；被用户停止时也已经收到的 usage 一样写进去，不丢。
                writeTurnUsage()
                // 本次回复的步数只在这里加一次：本会话累计 + 全局总账（放 onRound 里会重复计）
                commitSteps()
                // 时间账同理：一次回复只加一次，切会话回来还能接上
                commitTiming()
                // 一轮（或被停止）结束后整条会话落盘
                persistCurrent()
                persistAll()
                // 新消息改变了请求视图：水位、轮数、步数一起重算（refreshStats 内部就带 refreshContext，
                // 下次 send 的闸门判断用的也是它）。只刷 refreshContext 不够——状态行与上下文圆环以前
                // 读的是 stats 里那几项，而 stats 只在进统计页时才刷，用户得切会话再切回来才看到数字变。
                refreshStats()
                // 这轮跑过 todo_write 的话清单已经变了：重读一次，界面上的进度条跟上
                reloadTodos()
                // 计划同理：本轮可能刚用 write_plan 追加了新计划，计划预览与入口要立刻是对的
                reloadPlans()
                // 人不在前台才提醒（Notifier 内部不判前后台，这里判）；整段 runCatching：
                // 通知失败最多是少一条提醒，绝不能把回合结果带崩
                if (!AppForeground.isForeground && !stopped) {
                    runCatching {
                        val failure = lastError?.takeIf { it != imageNotice }
                        if (failure.isNullOrBlank()) {
                            val reply = messages
                                .lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
                                ?.content
                                .orEmpty()
                            Notifier.notifyReply(
                                appContext,
                                "回复完成",
                                reply.ifBlank { "（本轮已结束，没有正文输出）" },
                                currentSessionId
                            )
                        } else {
                            Notifier.notifyReply(appContext, "回复失败", failure, currentSessionId)
                        }
                    }.onFailure {
                        FileLog.log("notify", "通知失败：" + (it.message ?: it.javaClass.simpleName))
                    }
                }
                // 收尾链放在最后：状态、落盘、通知都做完，才轮到「接着再跑一个回合」
                if (!stopped) continueAfterTurn(scope)
            }
        }
    }

    /**
     * 本轮结束后的收尾链（顺序有意义）：
     * ① 有没被回答的插话 → 再跑一个不追加用户消息的回合把它接上；
     * ② 否则队列里有消息 → 取第一条按正常发送流程发出去。
     *
     * 两者互斥：同时起两个回合会让两条流抢着写同一份 messages。
     * 用户点了「停止」时整段不执行（停止就是停止，不能自己又跑起来）。
     */
    private fun continueAfterTurn(scope: CoroutineScope) {
        if (steerPending) {
            // 插话那条已经在后续请求里被回答了（末尾不再是 user）→ 收掉标记，别让下一轮误续跑
            val unanswered = messages.lastOrNull()?.role == "user"
            steerPending = false
            if (unanswered) {
                FileLog.log("steer", "本轮已结束但插话还没被回答，续跑一回合")
                scope.launch {
                    // launch 到真正开跑之间，用户可能又发了一条（那一轮会自己接管收尾）：
                    // 这时直接让路，别并起两条流去写同一份 messages
                    if (isStreaming) return@launch
                    startTurn(
                        userText = null,
                        prompt = "",
                        files = emptyList(),
                        images = emptyList(),
                        scope = scope
                    )
                }
                return
            }
        }
        val next = pendingQueue.firstOrNull()
        if (next != null) {
            scope.launch {
                // 同理让路；队列不动，下一次回合结束时会再取到它
                if (isStreaming) return@launch
                // 只在它还在队列里时才发：别的路径先发走或删掉了，就不再重复发/发空
                if (pendingQueue.none { it.id == next.id }) return@launch
                pendingQueue.removeAll { it.id == next.id }
                FileLog.log("queue", "本轮已结束，自动发送队列第一条：" + next.text.take(80))
                send(next.text, scope)
            }
            return
        }
        // ③ 最后才轮到目标自动续跑：前面两条都是「用户还有话说」，不该被目标挤掉
        continueGoal(scope)
    }

    /**
     * 目标自动续跑：目标还在进行中、这一轮真的动过工具、且没到轮次上限时，接着跑一轮。
     *
     * 为什么非要「动过工具」：只回一句话说明模型没在推进（或者在要信息），再自动追问一轮也是空转，
     * 白烧 token 还把用户晾在自动回复里。为什么续跑不带新 user 消息：目标本身就在上下文里，
     * 再补一句话等于替用户说话。
     */
    private fun continueGoal(scope: CoroutineScope) {
        val current = goal ?: return
        if (current.phase != GoalPhase.ACTIVE) return
        if (current.rounds >= MAX_GOAL_ROUNDS) {
            FileLog.log("goal", "已到 " + MAX_GOAL_ROUNDS + " 轮上限，停止自动续跑")
            return
        }
        if (!turnUsedTools()) return
        val next = current.copy(rounds = current.rounds + 1, updatedAt = System.currentTimeMillis())
        goal = next
        GoalStore.save(appContext, currentSessionId, next)
        FileLog.log("goal", "目标第 " + next.rounds + " 轮自动续跑：" + next.objective.take(60))
        scope.launch {
            // 和插话/队列同样让路：真正开跑前忙起来了就让那一轮接管
            if (isStreaming) return@launch
            startTurn(
                userText = null,
                prompt = "",
                files = emptyList(),
                images = emptyList(),
                scope = scope
            )
        }
    }

    /** 本轮（从 turnStartIndex 起）有没有工具结果：用它判断模型是不是真的动手了。 */
    private fun turnUsedTools(): Boolean {
        for (i in messages.lastIndex downTo turnStartIndex) {
            if (i < 0) return false
            if (messages[i].role == "tool") return true
        }
        return false
    }

    /**
     * 重新生成：把这条用户消息之后的内容全删掉，用同一句话再跑一次。
     * 第一版就按「旧的不保留」实现，之后要做多版本切换再给消息加 version 字段。
     */
    fun regenerateFrom(userIndex: Int, scope: CoroutineScope) {
        if (isStreaming) return
        if (userIndex !in messages.indices) return
        val userText = messages[userIndex].content
        if (messages[userIndex].role != "user") return
        cutFrom(userIndex)
        FileLog.log("chat", "重新生成：" + userText.take(80))
        send(userText, scope)
        // send 内部流结束时也会落盘；这里再写一次，兜住「send 提前返回」的情况
        persistCurrent()
        persistAll()
    }

    /** 回滚到此处：删掉这条用户消息及之后的所有内容，回到还没提问的状态。 */
    fun rollbackTo(userIndex: Int) {
        if (isStreaming) return
        if (userIndex !in messages.indices) return
        if (messages[userIndex].role != "user") return
        val message = messages[userIndex]
        // 图片和附件退回「已选未发」：文件还在工作区里就重新排队，路径失效的直接跳过，不放坏引用
        val root = currentWorkspaceRoot()
        restorePending(root, message.images, pendingImages, MAX_IMAGES)
        restorePending(root, attachmentPathsIn(message.content), attachments, Int.MAX_VALUE)
        // 输入框只放正文：图片路径段是写给模型看的，界面另有图片网格，裁完没字就留空
        val restore = userBubbleBody(message)
        inputText = TextFieldValue(restore, selection = TextRange(restore.length))
        cutFrom(userIndex)
        usageText = null
        activityText = null
        lastError = null
        // 回滚只退消息：本会话累计保留（已发生的请求不能凭空消失），按剩下的消息重算轮数与水位
        resetStats()
        FileLog.log("chat", "回滚到第 " + userIndex + " 条（共剩 " + messages.size + " 条）")
        persistCurrent()
        persistAll()
        logOrphansAfterRollback()
    }

    /**
     * 回滚要从气泡里还原成文字的正文。
     *
     * 判定与 ChatScreen.userBubbleText 一致：images 非空时把 [IMAGE_PATHS_MARKER] 之后那段
     * 图片说明裁掉，输入框里不该出现一串图片路径。
     */
    private fun userBubbleBody(message: ChatMessage): String {
        // 图片段与附件段都以「正文 + 说明块」的形式追加在末尾：取两个标记里更早的那个切
        val imageCut = message.content.indexOf(IMAGE_PATHS_MARKER)
        val fileCut = message.content.indexOf(ATTACHMENT_PATHS_MARKER)
        val planCut = message.content.indexOf(PLAN_DETAIL_MARKER)
        val cut = listOf(imageCut, fileCut, planCut).filter { it >= 0 }.minOrNull()
            ?: return message.content
        return message.content.substring(0, cut).trimEnd()
    }
    private fun attachmentPathsIn(content: String): List<String> {
        val start = content.indexOf(ATTACHMENT_PATHS_MARKER)
        if (start < 0) return emptyList()
        return content.substring(start + ATTACHMENT_PATHS_MARKER.length)
            .lines()
            .dropWhile { it.isBlank() }
            .takeWhile { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim() }
            .filter { it.isNotEmpty() }
    }

    /** 把消息里的文件放回待发队列：工作区里找不到的跳过，队列满了也不再硬塞。 */
    private fun restorePending(
        root: File,
        relPaths: List<String>,
        queue: MutableList<PendingFile>,
        limit: Int
    ) {
        for (rel in relPaths) {
            if (queue.size >= limit) return
            if (rel.isBlank() || queue.any { it.relPath == rel }) continue
            if (!File(root, rel).isFile) continue
            queue.add(PendingFile(rel.substringAfterLast('/'), rel))
        }
    }

    /**
     * 回滚不删工作区里的文件，只在日志里记一行还剩多少未引用附件。
     *
     * 数字取自设置页「清理未引用附件」同一个统计口径，用户看到的和能清掉的是同一批。
     */
    private fun logOrphansAfterRollback() {
        val sessions = sessionsOfCurrentWorkspace()
        val root = currentWorkspaceRoot()
        summaryScope.launch {
            val orphans = withContext(Dispatchers.IO) {
                runCatching { UploadStore.orphanReport(appContext, sessions, root).first }.getOrDefault(0)
            }
            FileLog.log("chat", "回滚后仍有 " + orphans + " 个未引用附件，可在设置里清理")
        }
    }

    private fun cutFrom(index: Int) {
        while (messages.size > index) {
            messages.removeAt(messages.lastIndex)
        }
    }

    /**
     * 删除单条消息。
     *
     * 为什么删 user 要连带后面的工具消息：工具结果是对上一条 assistant 的 tool_calls 的回答，
     * 提问被删掉之后请求体里就只剩一串没有来路的 tool 消息，接口会直接报错；
     * 所以从这条 user 一直清到下一个 user 之前。
     * 反方向同理：删掉一条带 tool_calls 的 assistant 时，只清它自己发起的那些工具结果。
     */
    fun deleteMessage(index: Int) {
        if (isStreaming) {
            lastError = "正在生成回复，无法删除消息"
            return
        }
        if (index !in messages.indices) return
        val target = messages[index]
        var end = index + 1
        if (target.role == "user") {
            var hasTool = false
            while (end < messages.size && messages[end].role != "user") {
                if (messages[end].role == "tool") hasTool = true
                end++
            }
            // 后面没有工具结果就只删这一条提问，当轮的助手回复留给用户自己决定
            if (!hasTool) end = index + 1
        } else if (target.toolCalls.isNotEmpty()) {
            val callIds = target.toolCalls.map { it.id }.toSet()
            while (end < messages.size) {
                val next = messages[end]
                val callId = next.toolCallId
                // 只吃这条消息自己发起的工具结果：别的调用链可能紧挨着
                if (next.role != "tool" || callId == null || callId !in callIds) break
                end++
            }
        }
        // 从后往前删：每删一条后面的下标都会变，倒着删就不用反复算偏移
        for (i in end - 1 downTo index) messages.removeAt(i)
        lastError = null
        usageText = null
        activityText = null
        // 轮数与上下文水位都要按剩下的消息重算，否则统计页还显示删之前的条数
        refreshStats()
        persistCurrent()
        persistAll()
        FileLog.log("chat", "删除第 " + index + " 条消息（连带到第 " + (end - 1) + " 条）")
    }

    /**
     * 编辑并重发：截断到这条 user 消息之前，用改过的文字重新走一次 [send]。
     *
     * 为什么截断而不是就地改这一条：这一轮的助手回复和工具调用都是针对旧问题的，
     * 留着会和改过的提问对不上，接着往下发只会得到前后矛盾的上下文。
     */
    fun editAndResend(index: Int, text: String, scope: CoroutineScope) {
        if (isStreaming) {
            lastError = "正在生成回复，无法编辑重发"
            return
        }
        if (index !in messages.indices) return
        if (messages[index].role != "user") return
        val edited = text.trim()
        if (edited.isEmpty()) return
        cutFrom(index)
        // 文本先填回输入框：send 提前返回（导入后未重启等）时，用户改完还能自己再发一次
        inputText = TextFieldValue(edited, selection = TextRange(edited.length))
        FileLog.log("chat", "编辑重发第 " + index + " 条：" + edited.take(80))
        send(edited, scope)
        // send 真的跑起来就清掉输入框：这句话已经变成一条消息，再留一份只会重复
        if (isStreaming) inputText = TextFieldValue("")
        // send 内部流结束时也会落盘；这里再写一次，兜住「send 提前返回」的情况
        persistCurrent()
        persistAll()
    }

    fun stop() {
        if (!isStreaming) return
        // 停止就不再续跑：留着这个标记，下一次回合收尾时可能凭空多跑一个空回合
        steerPending = false
        streamJob?.cancel()
        streamJob = null
        isStreaming = false
        activityText = null
        FileLog.log("chat", "用户点了停止")
    }

    // ---- 多会话 ----

    /** 新建会话：当前会话先存回，再插一条空的到最前并切过去；新会话归当前工作区。 */
    fun newSession() {
        if (isStreaming) return
        // 附件是「当前这条提问」的，换会话就不该带过去
        attachments.clear()
        persistCurrent()
        val fresh = SessionStore.newSession().copy(workspaceId = currentWorkspaceId)
        sessionList = listOf(fresh) + sessionList
        currentSessionId = fresh.id
        messages.clear()
        toolOutcomes.clear()
        // 新会话的累计就是它自己那四个字段（刚建出来全是 0）
        resetStats()
        persistAll()
        reloadTodos()
        FileLog.log("session", "新建会话 " + fresh.id + "，共 " + sessionList.size + " 个")
    }

    /** 切换会话：id 相同直接返回；找不到目标也直接返回。 */
    fun switchSession(id: String) {
        if (id == currentSessionId) return
        if (isStreaming) return
        attachments.clear()
        persistCurrent()
        val target = sessionList.firstOrNull { it.id == id } ?: return
        currentSessionId = target.id
        // 会话可能属于别的工作区（UI 直接点某个分组里的会话）：选中态跟着它走，避免两边对不上
        currentWorkspaceId = target.workspaceId
        messages.clear()
        messages.addAll(target.messages)
        toolOutcomes.clear()
        // 上一个会话的累计不回带：累计从目标会话自己的字段读回来（切回来就能接上）
        resetStats()
        persistAll()
        reloadTodos()
        FileLog.log("session", "切到会话 " + target.id + "（" + messages.size + " 条消息）")
    }

    /**
     * 分叉会话：把当前历史复制成一条新会话，父会话原样不动。
     *
     * - [atIndex] == null（会话列表里的「分叉会话」）：复制全部消息——用户的预期是
     *   「带着当前内容开一个新会话，之后在这里另开一支」，少带最后一轮就成了丢内容；
     * - [atIndex] != null（消息长按「在新会话中分支」，传的是那条 user 的下标）：
     *   复制 [0, atIndex)，即从这条提问之前另开一支，不会留下一条没有回答的提问。
     * 摘要与待办都不继承——摘要记的是父会话的消息下标，待办属于父会话这一轮的计划。
     */
    fun forkSession(atIndex: Int? = null) {
        if (isStreaming) {
            lastError = "正在生成回复，无法分叉会话"
            return
        }
        // 先把内存里的消息写回当前会话：紧接着要从会话记录里取它的标题与工作区
        persistCurrent()
        val base = sessionList.firstOrNull { it.id == currentSessionId } ?: return
        val snapshot = messages.toList()
        val boundary = (atIndex ?: snapshot.size).coerceIn(0, snapshot.size)
        val fresh = SessionStore.forked(base, snapshot.subList(0, boundary).toList())
        sessionList = listOf(fresh) + sessionList
        currentSessionId = fresh.id
        // 分支沿用父会话的工作区，选中态跟着它走，避免两边对不上
        currentWorkspaceId = fresh.workspaceId
        messages.clear()
        messages.addAll(fresh.messages)
        toolOutcomes.clear()
        // 待办留在父会话：新会话的清单从空表开始
        todos = emptyList()
        resetStats()
        persistAll()
        FileLog.log(
            "session",
            "分叉会话 " + base.id + " → " + fresh.id + "（" + messages.size + " 条消息，boundary=" + boundary + "）"
        )
    }

    /** 删除会话：删的是当前会话就在当前工作区里挑下一条，一个都不剩就新建一条空的。 */
    fun deleteSession(id: String) {
        if (isStreaming) return
        if (sessionList.none { it.id == id }) return
        // 先把当前会话的最新消息存回列表，免得删除操作把这次的改动丢了
        persistCurrent()
        // 先留一份：列表里删掉之后，就再也看不出这条会话引用过哪些图片了
        val removed = sessionList.firstOrNull { it.id == id }
        sessionList = sessionList.filter { it.id != id }
        // 会话真删了：它名下的计划/目标/上下文事件也一起收掉，否则那几个 JSON 里永远留着孤儿
        clearSessionDocs(id)
        ContextEventStore.clear(appContext, id)
        if (id == currentSessionId) {
            // 只在当前工作区里挑下一条：删完一个会话不该跳到别的工作区的对话
            val remaining = sessionList.filter { !it.archived && it.workspaceId == currentWorkspaceId }
            if (remaining.isEmpty()) {
                val fresh = SessionStore.newSession().copy(workspaceId = currentWorkspaceId)
                sessionList = listOf(fresh) + sessionList
                currentSessionId = fresh.id
                messages.clear()
            } else {
                val next = remaining.first()
                currentSessionId = next.id
                messages.clear()
                messages.addAll(next.messages)
            }
            toolOutcomes.clear()
            // 删除只删会话本身：全局总账（UsageLedger）是历史总账，一分不动
            resetStats()
        }
        persistAll()
        reloadTodos()
        // 会话没了，它带进来的图片可能再没人引用：在 IO 里顺手清一遍
        removed?.let { cleanupImagesOf(it) }
        FileLog.log("session", "删除会话 " + id + "，剩 " + sessionList.size + " 个，当前=" + currentSessionId)
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
        val remaining = sessionList.filter { it.workspaceId == removed.workspaceId }
        summaryScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    // 目录也在这里取：dirOf 会按需建目录，不该占着主线程删会话
                    val space = workspaceList.firstOrNull { it.id == removed.workspaceId }
                        ?: WorkSpaceStore.defaultSpace(appContext)
                    UploadStore.cleanSessionImages(removed, remaining, rootOf(space))
                }.onFailure { FileLog.log("upload", "删除会话后清理图片失败：" + it) }
            }
        }
    }

    /** 重命名会话；标题 trim 后为空就退回「新会话」的空标题。 */
    fun renameSession(id: String, title: String) {
        if (isStreaming) return
        val trimmed = title.trim()
        // 只改列表里的 title，不走 persistCurrent（那会用内存里的 messages 覆盖这条会话的记录）
        sessionList = sessionList.map { if (it.id == id) it.copy(title = trimmed) else it }
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
        if (sessionList.none { it.id == id }) return
        sessionList = sessionList.map { if (it.id == id) it.copy(pinned = pinned) else it }
        persistAll()
        FileLog.log("session", (if (pinned) "置顶" else "取消置顶") + "会话 " + id)
    }

    /** 归档会话；归档的是当前会话时，直接进一个空对话。 */
    fun archiveSession(id: String) {
        if (isStreaming) return
        val target = sessionList.firstOrNull { it.id == id } ?: return
        // 先确保当前会话消息已经落到 sessionList，否则后面 newSession 会把当前对话冲掉
        persistCurrent()
        sessionList = sessionList.map {
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
        if (isStreaming) return
        sessionList = sessionList.map {
            if (it.id == id) it.copy(archived = false, updatedAt = System.currentTimeMillis()) else it
        }
        persistAll()
        FileLog.log("session", "恢复会话 " + id)
    }

    // ---- 工作区 ----

    /** 当前工作区里没归档的会话（sessionList 已按 updatedAt 倒序，直接就能用）。 */
    fun sessionsInCurrentWorkspace(): List<ChatSession> =
        sessionList.filter { !it.archived && it.workspaceId == currentWorkspaceId }

    /** 当前工作区里已归档的会话（侧边栏折叠区用）。 */
    fun archivedInCurrentWorkspace(): List<ChatSession> =
        sessionList.filter { it.archived && it.workspaceId == currentWorkspaceId }

    /**
     * 切换工作区（null = 未分类）：先存回当前会话，再切选中态，最后载入目标工作区最近的会话。
     */
    fun switchWorkspace(id: String?) {
        if (isStreaming) return
        // 目标工作区不存在（比如刚被删掉）就不切，免得停在空分组上
        if (id != null && workspaceList.none { it.id == id }) return
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
        workspaceList = workspaceList.map { if (it.id == id) renamed else it }
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
        if (isStreaming) return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        persistCurrent()
        val space = if (rootPath == null && treeUri == null) {
            WorkSpaceStore.create(appContext, trimmed)
        } else {
            WorkSpaceStore.createWithRoot(appContext, trimmed, rootPath, treeUri) ?: return false
        }
        workspaceList = workspaceList + space
        WorkSpaceStore.save(appContext, workspaceList)
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
        if (isStreaming) return
        if (id == WorkSpaceStore.DEFAULT_ID) {
            FileLog.log("workspace", "默认工作区不允许删除")
            return
        }
        val space = workspaceList.firstOrNull { it.id == id } ?: return
        persistCurrent()
        // 只 rename 目录、绝不真删：用户要求「删除先留快照」
        val moved = WorkSpaceStore.softDelete(appContext, space)
        if (!moved) {
            // 目录没挪走也照样解绑：目录还完整留在磁盘上，会话不能跟着工作区一起消失
            FileLog.log("workspace", "工作区 " + space.name + " 的目录软删除失败，仍把会话解绑到未分类")
        }
        // 该工作区下所有会话（含归档）解绑到「未分类」。
        // 计划与目标不清：会话只是换了归属，数据没被删（清只在 deleteSession 里做）
        sessionList = sessionList.map { if (it.workspaceId == id) it.copy(workspaceId = null) else it }
        workspaceList = workspaceList.filter { it.id != id }
        WorkSpaceStore.save(appContext, workspaceList)
        if (currentWorkspaceId == id) {
            // 删的是当前工作区：切到未分类，载入未分类里最近的那条（刚解绑的会话就在里面）
            currentWorkspaceId = null
            openRecentIn(null)
        }
        persistAll()
        FileLog.log(
            "workspace",
            "删除工作区 " + space.name + "（" + id + "），剩 " + workspaceList.size +
                " 个，当前工作区=" + (currentWorkspaceId ?: "未分类")
        )
    }

    /**
     * 载入某个工作区（null = 未分类）里 updatedAt 最新且未归档的会话；一条都没有就新建一条属于它的空会话。
     * 只动 currentSessionId 和 messages，工作区选中态由调用方先改好。
     */
    private fun openRecentIn(workspaceId: String?) {
        val target = sessionList
            .filter { !it.archived && it.workspaceId == workspaceId }
            .maxByOrNull { it.updatedAt }
        toolOutcomes.clear()
        if (target != null) {
            currentSessionId = target.id
            messages.clear()
            messages.addAll(target.messages)
            resetStats()
            reloadTodos()
            return
        }
        val fresh = SessionStore.newSession().copy(workspaceId = workspaceId)
        sessionList = listOf(fresh) + sessionList
        currentSessionId = fresh.id
        messages.clear()
        messages.addAll(fresh.messages)
        resetStats()
        reloadTodos()
    }

    /** 侧边栏/顶栏显示的当前会话名，没起标题就是「新会话」。 */
    fun currentTitle(): String {
        val current = sessionList.firstOrNull { it.id == currentSessionId }
        return if (current == null || current.title.isBlank()) "新会话" else current.title
    }

    /** 把内存里的 messages 写回当前会话那条记录，并把列表重排成 updatedAt 降序。 */
    private fun persistCurrent() {
        if (needsRestart) return
        val id = currentSessionId
        if (id.isEmpty()) return
        val now = System.currentTimeMillis()
        val snapshot = messages.toList()
        var found = false
        val updated = ArrayList<ChatSession>(sessionList.size)
        for (session in sessionList) {
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
        sessionList = updated.sortedByDescending { it.updatedAt }
    }

    private fun persistAll() {
        // 导入后没重启之前一律不落盘：内存里还是旧列表，写下去会把刚导入的会话盖掉
        if (needsRestart) return
        SessionStore.save(appContext, sessionList)
    }

    /** 从磁盘重读会话列表：以磁盘为准，只有当前会话的消息取内存里还没落盘的那份。 */
    fun reloadSessionsFromDisk() {
        if (needsRestart) return
        if (isStreaming) return
        persistCurrent()
        val disk = SessionStore.load(appContext)
        val id = currentSessionId
        val mine = sessionList.firstOrNull { it.id == id }
        val merged = ArrayList<ChatSession>(disk.size + 1)
        for (session in disk) {
            // 当前会话换成内存里的 messages/updatedAt，标题等其它字段仍跟磁盘走
            merged.add(
                if (mine != null && session.id == id) {
                    session.copy(messages = mine.messages, updatedAt = mine.updatedAt)
                } else {
                    session
                }
            )
        }
        if (mine != null && disk.none { it.id == id }) merged.add(mine)
        sessionList = merged.sortedByDescending { it.updatedAt }
        FileLog.log("session", "从磁盘重载 " + sessionList.size + " 条会话")
    }

    /** 当前会话还没标题时，把这句话前 20 字当标题。 */
    private fun autoTitleIfNeeded(prompt: String) {
        val id = currentSessionId
        if (id.isEmpty()) return
        val current = sessionList.firstOrNull { it.id == id } ?: return
        if (current.title.isNotEmpty()) return
        val title = prompt.replace('\n', ' ').trim().take(20)
        if (title.isEmpty()) return
        sessionList = sessionList.map { if (it.id == id) it.copy(title = title) else it }
        persistCurrent()
        FileLog.log("session", "会话 " + id + " 起标题：" + title)
    }

    /**
     * 刷新统计里需要遍历消息才得出的那几项（供统计页打开时调用一次）。
     *
     * 上下文水位的口径：真正发给模型的那份请求视图（摘要 + 最近若干轮原文），
     * 就是 refreshContext() 算出的 contextSnapshot.estimatedTokens —— 界面上的全量历史不算在内；
     * 分母是 ApiConfig.CONTEXT_WINDOW。不另写估算器。
     */
    fun refreshStats() {
        refreshContext()
        // 总账在内存里读一次就够（load 重复调用是空操作）；顺手兜住「进程刚起来还没读过盘」的情况
        UsageLedger.load(appContext)
        val ledger = UsageLedger.snapshot()
        // 本会话累计的唯一来源是会话记录本身（重启后 stats 是空的，必须从那里读回来）
        val session = sessionList.firstOrNull { it.id == currentSessionId }
        stats = stats.copy(
            rounds = messages.count { it.role == "user" },
            totalPromptTokens = session?.promptTokens ?: 0,
            totalCompletionTokens = session?.completionTokens ?: 0,
            totalRequests = session?.requests ?: 0,
            contextTokens = contextSnapshot.estimatedTokens,
            contextWindow = ApiConfig.CONTEXT_WINDOW,
            // 全局历史总账：只增不减，删会话 / 归档 / 清工作区都不动它
            globalPromptTokens = ledger.totalPromptTokens,
            globalCompletionTokens = ledger.totalCompletionTokens,
            globalRequests = ledger.totalRequests,
            todayPromptTokens = ledger.todayPromptTokens,
            todayCompletionTokens = ledger.todayCompletionTokens,
            todayRequests = ledger.todayRequests,
            firstUsedDate = UsageLedger.dateText(ledger.firstUsedAt)
        )
    }

    /**
     * 换了会话（切换 / 新建 / 删除 / 工作区跳转 / 回滚）后统计跟着换：
     * 累计与请求次数从这条会话自己的字段读回来（不再清零，切走再切回来能接上）；
     * 「本次回复步数」和「最近一次用量」归零，轮数与水位按新消息重算。
     */
    private fun resetStats() {
        // 整体换一个新快照：steps / last* 归零；
        // 累计与请求次数由 refreshStats 从当前会话的字段读回来（不再清零）
        // 摘要失败的兜底标记是「上一份会话」的状态（回滚也会走到这里），换会话必须清掉，
        // 否则会把别的会话的历史也裁成最近几轮
        contextFallback = false
        stats = UsageStats()
        // refreshStats 内部会调 refreshContext，水位与覆盖条数一起重算
        refreshStats()
        // 时间账也是「这条会话」的：换会话必须从会话记录重新读，不能把上一条的耗时带过来
        syncSessionTiming()
        // 计划与目标同样按会话存：切过去就得换成那条会话的，否则计划页会显示上一条会话的计划
        reloadSessionDocs()
        // 上下文事件也是按会话存的：切过去就该看到那条会话自己的压缩记录
        reloadContextEvents()
    }

    /**
     * 把「本次回复步数」加进本会话累计与全局总账，每次回复结束只调一次
     * （onRound 每轮都会回调，在那里加会重复计）。
     */
    private fun commitSteps() {
        val steps = stats.steps
        if (steps <= 0) return
        val id = currentSessionId
        // 本会话累计步数：落进 sessions.json 的会话记录
        sessionList = sessionList.map {
            if (it.id == id) it.copy(steps = it.steps + steps) else it
        }
        UsageLedger.addSteps(appContext, steps)
    }

    /**
     * 把本次回复的时间账加进会话累计，一次回复只加一次（跟 [commitSteps] 同一个位置）。
     *
     * 加完立刻同步 [sessionTiming]：面板读的是这个快照，等下一次切会话才刷会让人以为数字没动。
     */
    private fun commitTiming() {
        val model = turnModelMillis
        val tool = turnToolMillis
        // 本轮输出 tokens 在 writeTurnUsage 之后仍然留着（计数器要到下一次提问才清零），
        // 所以这里能把它和本轮耗时配成一对落进会话记录，供「输出速度」使用
        val lastOutput = turnCompletionTokens
        turnModelMillis = 0
        turnToolMillis = 0
        if (model > 0 || tool > 0) {
            val id = currentSessionId
            sessionList = sessionList.map {
                if (it.id == id) {
                    it.copy(
                        modelMillis = it.modelMillis + model,
                        toolMillis = it.toolMillis + tool,
                        lastTurnModelMillis = model,
                        lastTurnCompletionTokens = lastOutput
                    )
                } else {
                    it
                }
            }
        }
        syncSessionTiming()
    }

    /** 从当前会话记录读回时间账（本会话累计耗时 + 累计输出 tokens）。 */
    private fun syncSessionTiming() {
        val session = sessionList.firstOrNull { it.id == currentSessionId }
        sessionTiming = TimingSnapshot(
            modelMillis = session?.modelMillis ?: 0L,
            toolMillis = session?.toolMillis ?: 0L,
            completionTokens = session?.completionTokens ?: 0,
            lastTurnModelMillis = session?.lastTurnModelMillis ?: 0L,
            lastTurnOutputTokens = session?.lastTurnCompletionTokens ?: 0
        )
    }

    /** 全部会话的消息总数，统计页「会话与消息」用；当前会话按内存里的 messages 算，避免落盘滞后。 */
    fun totalMessageCount(): Int =
        sessionList.sumOf { if (it.id == currentSessionId) messages.size else it.messages.size }

    // ---- 洞察 / 日志 / 记忆 ----

    /**
     * 刷新洞察页：上下文水位 + 分段估算 + 全局总账，外加提示词长度趋势。
     *
     * 趋势只在 assistant 消息上取，因为只有它们记过「这一轮问上去多少 tokens」；
     * 取最后 [INSIGHT_TREND_POINTS] 条，太长的历史压成一张图也看不出走势。
     */
    fun refreshInsight() {
        refreshContext()
        refreshContextBreakdown()
        // 总账在内存里读一次就够（load 重复调用是空操作）
        UsageLedger.load(appContext)
        insightTrend = messages
            .filter { it.role == "assistant" && it.promptTokens != 0 }
            .takeLast(INSIGHT_TREND_POINTS)
            .map { it.promptTokens }
    }

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

    /** 记忆库根目录（工作区下的固定子目录）；真正要用之前先让 [MemoryKernel] 铺库。 */
    fun memoryLibraryRoot(): File = File(currentWorkspaceRoot(), MemoryKernel.LIB_DIR_NAME)

    fun refreshMemory() {
        runMemoryAction { MemoryAdmin.refresh(appContext, readyMemoryRoot()) }
    }

    /** 检索记忆；空查询直接忽略（内核会以退出码 2 报「--query 必填」，白等一次往返）。 */
    fun searchMemory(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        runMemoryAction { MemoryAdmin.search(appContext, readyMemoryRoot(), query) }
    }

    /** 跑一次维护扫描（遗忘扫描 + 报出待巩固批次），结果由内核决定，这里不解释。 */
    fun runMemoryCycle() {
        runMemoryAction { MemoryAdmin.cycle(appContext, readyMemoryRoot()) }
    }

    /** 导出给人看的记忆视图。 */
    fun exportMemory() {
        runMemoryAction { MemoryAdmin.export(appContext, readyMemoryRoot()) }
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
            val result = withContext(Dispatchers.IO) { runCatching { action() } }
            memoryState = result.getOrElse { e ->
                FileLog.log("memory", "记忆操作失败：" + (e.message ?: e.javaClass.simpleName))
                memoryState.copy(busy = false, error = "记忆操作失败：" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /**
     * 调内核前先铺库，返回库根目录。
     *
     * 铺库是幂等的（内容相同不重写），所以每次调用前都铺一遍最省心：
     * 换工作区、备份恢复了别的库、资产升级，都不用另外记「该不该重新铺」。
     */
    private fun readyMemoryRoot(): File {
        val root = memoryLibraryRoot()
        MemoryKernel.ensureLibrary(appContext, currentWorkspaceRoot())
        return root
    }

    // ---- AgentUi ----

    override fun onRound(round: Int) {
        activityText = "第 " + round + " 轮"
        // 步数取本次回复跑过的最大轮次（不是当前轮次，循环退出时不至于回落）；
        // 这里只记「本次回复」的口径，会话累计由流结束时的 commitSteps 加一次
        stats = stats.copy(steps = maxOf(stats.steps, round))
    }

    override fun onToolStarted(call: ToolCall) {
        activityText = "调用 " + call.name
    }

    override fun onToolFinished(call: ToolCall, result: ToolResult) {
        if (call.id.isNotEmpty()) toolOutcomes[call.id] = result.ok
        activityText = (if (result.ok) "已返回 " else "失败 ") + call.name
        // goal 工具改的是 goals.json：改完立刻读回来，目标页与状态行不用等下一轮
        if (result.ok && call.name == GOAL_TOOL) reloadGoal()
    }

    /**
     * 把本轮累计的 usage 写进本轮最后一条 assistant 消息。
     *
     * 为什么只写最后一条：一轮里的工具调用会产生多条 assistant，胶囊只在气泡底部显示一行，
     * 每条都写就会冒出好几行。[turnStartIndex] 保证不会写到上一轮（插话续跑那种
     * 不追加用户消息的回合也一样）。
     */
    private fun writeTurnUsage() {
        if (turnPromptTokens <= 0 && turnCompletionTokens <= 0) return
        for (i in messages.lastIndex downTo turnStartIndex) {
            if (i < 0) return
            if (messages[i].role != "assistant") continue
            messages[i] = messages[i].copy(
                promptTokens = turnPromptTokens,
                completionTokens = turnCompletionTokens
            )
            return
        }
    }

    override fun onUsage(promptTokens: Int, completionTokens: Int) {
        usageText = "输入 " + promptTokens + " / 输出 " + completionTokens + " tokens"
        // 气泡里要的是整轮总账：单个请求的数字只在这里加总，收尾时一次写进最后一条 assistant
        turnPromptTokens += promptTokens
        turnCompletionTokens += completionTokens
        // 本会话计数写回当前会话那条记录（切会话不丢、回来能接上），随后落盘
        val id = currentSessionId
        sessionList = sessionList.map {
            if (it.id == id) {
                it.copy(
                    promptTokens = it.promptTokens + promptTokens,
                    completionTokens = it.completionTokens + completionTokens,
                    requests = it.requests + 1
                )
            } else {
                it
            }
        }
        persistCurrent()
        // 全局总账：只增不减，跟删会话 / 归档无关，记在同一次 usage 里
        UsageLedger.record(appContext, promptTokens, completionTokens)
        val ledger = UsageLedger.snapshot()
        // 统计页：最近一次取本次值，本会话累计取会话字段，全局取总账快照
        stats = stats.copy(
            lastPromptTokens = promptTokens,
            lastCompletionTokens = completionTokens,
            totalPromptTokens = stats.totalPromptTokens + promptTokens,
            totalCompletionTokens = stats.totalCompletionTokens + completionTokens,
            totalRequests = stats.totalRequests + 1,
            globalPromptTokens = ledger.totalPromptTokens,
            globalCompletionTokens = ledger.totalCompletionTokens,
            globalRequests = ledger.totalRequests,
            todayPromptTokens = ledger.todayPromptTokens,
            todayCompletionTokens = ledger.todayCompletionTokens,
            todayRequests = ledger.todayRequests,
            firstUsedDate = UsageLedger.dateText(ledger.firstUsedAt)
        )
        // 水位跟着这次请求的上下文重算：一次回复里可能问模型好几次，圆环与状态行必须在
        // 这条回复跑的过程中就往前走，不能等到整轮收尾或者切会话才有变化
        refreshContext()
        // 会话累计输出 tokens 变了：时间账面板读的是 sessionTiming，这里顺手同步
        syncSessionTiming()
        // 收到 usage 说明这一轮请求真的成功了，顶栏状态点可以回绿
        lastRequestOk = true
    }

    override fun onError(message: String) {
        lastError = message
        // 请求失败：顶栏状态点变红，直到下一次成功
        lastRequestOk = false
        FileLog.log("error", message)
    }

    /** 循环收尾回调一次：只累计本次回复的时间账，落会话记录在 [commitTiming] 里做。 */
    override fun onTiming(modelMillis: Long, toolMillis: Long) {
        turnModelMillis += modelMillis
        turnToolMillis += toolMillis
    }
}

/** 待发送的附件：只记显示名和文件在工作区里的相对路径，模型靠 read_file 读。 */
data class PendingFile(val name: String, val relPath: String)
