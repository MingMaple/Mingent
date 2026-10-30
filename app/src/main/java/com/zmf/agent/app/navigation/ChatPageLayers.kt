package com.zmf.agent.app.navigation
import com.zmf.agent.uikit.components.FoldToggle
import com.zmf.agent.feature.terminal.TerminalActivity
import com.zmf.agent.core.llm.ApiDefaults
import com.zmf.agent.uikit.common.BoundedScrollColumn
import com.zmf.agent.uikit.common.blockTapsOnly

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zmf.agent.capability.config.ApiConfig
import com.zmf.agent.core.store.GoalPhase
import com.zmf.agent.core.store.PlanDoc
import com.zmf.agent.core.store.TodoStore
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.infra.api.BalanceClient
import com.zmf.agent.infra.api.BalanceResult
import com.zmf.agent.infra.backup.BackupArchive
import com.zmf.agent.infra.backup.BackupOptions
import com.zmf.agent.infra.system.ShizukuBridge
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.feature.float.FloatingBallService
import com.zmf.agent.capability.tools.displayName
import com.zmf.agent.feature.settings.AboutPage
import com.zmf.agent.feature.settings.CreateWorkspaceDialog
import com.zmf.agent.feature.settings.LogsPage
import com.zmf.agent.feature.settings.ArchivePage
import com.zmf.agent.feature.settings.TrashPage
import com.zmf.agent.feature.settings.PackagePage
import com.zmf.agent.feature.settings.PermissionPage
import com.zmf.agent.feature.schedule.SchedulePage
import com.zmf.agent.feature.settings.ContextConfigPage
import com.zmf.agent.feature.settings.ContextInsightPage
import com.zmf.agent.feature.settings.MemoryPage
import com.zmf.agent.feature.settings.DataSettingsPage
import com.zmf.agent.feature.settings.GeneralSettingsPage
import com.zmf.agent.feature.settings.ModelSettingsPage
import com.zmf.agent.feature.settings.SettingsHomePage
import com.zmf.agent.core.store.UsageLedger
import com.zmf.agent.capability.prompt.PromptStore
import com.zmf.agent.feature.chat.ChatComposer
import com.zmf.agent.feature.chat.ComposerCommand
import com.zmf.agent.feature.chat.ComposerStatus
import com.zmf.agent.feature.chat.ContextUsageInfo
import com.zmf.agent.feature.chat.TracePage
import com.zmf.agent.feature.shell.AppDrawerContent
import com.zmf.agent.uikit.components.salt.SaltDrawer
import com.zmf.agent.uikit.components.salt.SaltFullPane
import com.zmf.agent.uikit.components.salt.SaltSwitch
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.ThemeMode
import com.zmf.agent.uikit.theme.UiScaleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import com.zmf.agent.feature.chat.BrowserPage
import com.zmf.agent.feature.chat.WorkSpacePage
import com.zmf.agent.feature.chat.AppPage
import com.zmf.agent.feature.chat.ChatUiState
import com.zmf.agent.feature.chat.ChatViewModel
import com.zmf.agent.feature.chat.PlanPage
import com.zmf.agent.feature.chat.SessionListPage
import com.zmf.agent.feature.chat.SubAgentPage

/**
 * 聊天页之上的二十层全屏页面：设置首页 / 数据 / 通用设置 / 模型 / 权限 / 包管理 / 日志 /
 * 定时任务 / 记忆 / 上下文洞察 / 上下文配置 / 关于 / 归档 / 回收站 / 工作区 / 会话列表 /
 * 计划 / 浏览器 / 子智能体详情 …
 *
 * 每页各一层 [AnimatedVisibility]，下层页原地留着，中间不会闪出一帧主页。全部是同一个 Box 的
 * 直接子节点，彼此按代码顺序叠放——这也是它们必须一次性搬进同一个组合的原因：拆成多次调用会
 * 改变层序，滑入动画时就会露出底下那一页。
 */
@Composable
internal fun ChatPageLayers(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    uiScale: Float,
    onUiScaleChange: (Float) -> Unit,
    subAgentEntries: List<SubAgentRegistry.Entry>,
    /** 导入落点由调用方选（SAF），这一层只管通知「该弹选择器了」。 */
    onImportLaunch: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by ui.page
    var trashRefreshKey by ui.trashRefreshKey
    var contextHubFromHome by ui.contextHubFromHome
    var extraPrompt by ui.extraPrompt
    var overridePrompt by ui.overridePrompt
    var pagerAtFirstPage by ui.pagerAtFirstPage
    var subAgentDetailId by ui.subAgentDetailId
    var balance by ui.balance
    var balanceBusy by ui.balanceBusy
    var orphanStatus by ui.orphanStatus
    var showExportDialog by ui.showExportDialog
    val openContextHub: (Boolean) -> Unit = ui::openContextHub
    val forkWithReload: (() -> Unit) -> Unit = { action -> ui.forkWithReload(scope, action) }
    // 上下文页要的全局总账快照：进页面时读一次盘（UsageLedger.load 自带「只读一次」的闸门）
    val lastLedger = remember(page) {
        UsageLedger.load(context)
        UsageLedger.snapshot()
    }
        // 设置 / 工作区 / 会话列表与设置下的所有页面都是独立全屏页，每页各一层 AnimatedVisibility：
        // 下层页原地留着，中间不会闪出一帧主页；页面自身不透明，所以只做横向滑动。
        AnimatedVisibility(
            // 所有设置层页面也要算进这一层：滑入时设置首页必须原地留着，否则底下会露出主页
            visible = page == AppPage.SETTINGS || page == AppPage.SETTINGS_DATA ||
                page == AppPage.MODEL ||
                page == AppPage.PERMISSION || page == AppPage.PACKAGE || page == AppPage.LOGS ||
                page == AppPage.SCHEDULE ||
                page == AppPage.GENERAL || page == AppPage.MEMORY ||
                page == AppPage.CONTEXT_HUB || page == AppPage.CONTEXT_CONFIG ||
                page == AppPage.ABOUT || page == AppPage.ARCHIVE,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            SettingsHomePage(
                onBack = { page = null },
                onGeneral = { page = AppPage.GENERAL },
                onModel = { page = AppPage.MODEL },
                onMemory = { page = AppPage.MEMORY },
                onContextInsight = { openContextHub(false) },
                onContextConfig = { page = AppPage.CONTEXT_CONFIG },
                onPackage = { page = AppPage.PACKAGE },
                onData = { page = AppPage.SETTINGS_DATA },
                onAbout = { page = AppPage.ABOUT }
            )
        }

        // 数据层要一直留到它的三级页（日志 / 归档）之上：这一层若跟着 SETTINGS_DATA 一起滑走，
        // 三级页滑入的那一下会把下面的「数据」页一起带走，中途露出的就是主页。
        AnimatedVisibility(
            visible = page == AppPage.SETTINGS_DATA || page == AppPage.ARCHIVE ||
                page == AppPage.LOGS || page == AppPage.TRASH,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            DataSettingsPage(
                onBack = { page = AppPage.SETTINGS },
                onExport = { showExportDialog = true },
                onImport = { onImportLaunch() },
                onLogs = { page = AppPage.LOGS },
                onArchive = { page = AppPage.ARCHIVE },
                onTrash = { page = AppPage.TRASH },
                trashRefreshKey = trashRefreshKey,
                archivedCount = viewModel.sessionList.count { it.archived },
                attachmentUsage = viewModel.attachmentUsageText,
                backupStatus = viewModel.backupStatus,
                // 导出/导入的结果显示在数据页，不挂到主页那条状态行上
                backupNotice = viewModel.backupNotice,
                orphanStatus = orphanStatus,
                onRefreshAttachment = { viewModel.refreshAttachmentUsage() },
                onCleanOrphans = { viewModel.cleanOrphanImages { message -> orphanStatus = message } }
            )
        }

        AnimatedVisibility(
            visible = page == AppPage.ARCHIVE,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            ArchivePage(
                workspaces = viewModel.workspaceList,
                archived = viewModel.sessionList.filter { it.archived },
                onBack = { page = AppPage.SETTINGS_DATA },
                onRestore = { id -> viewModel.unarchiveSession(id) },
                onDelete = { id -> viewModel.deleteSession(id) }
            )
        }

        // 回收站只对用户开放（D3 主体分离）：它没有对应的 Agent 工具，恢复/清空全在这一页
        AnimatedVisibility(
            visible = page == AppPage.TRASH,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            TrashPage(
                onBack = {
                    trashRefreshKey++
                    page = AppPage.SETTINGS_DATA
                }
            )
        }

        // 通用设置层同理：权限 / 定时任务两个三级页滑入时，这一层必须原地留着
        AnimatedVisibility(
            visible = page == AppPage.GENERAL || page == AppPage.PERMISSION ||
                page == AppPage.SCHEDULE,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            GeneralSettingsPage(
                onBack = { page = AppPage.SETTINGS },
                onPermission = { page = AppPage.PERMISSION },
                uiScale = uiScale,
                uiScaleLabel = UiScaleStore.label(uiScale),
                onUiScale = { onUiScaleChange(it) },
                busyMode = viewModel.busyMode,
                onBusyModeChange = { mode -> viewModel.applyBusyMode(mode) },
                onSchedule = { page = AppPage.SCHEDULE }
            )
        }

        // 长期记忆：进页面刷一次快照。记忆是读盘的，页面不该在主线程上摸文件
        AnimatedVisibility(
            visible = page == AppPage.MEMORY,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            // 页面自己进页就 onRefresh，这一层不再多刷一次（记忆每次刷新要跑内核，重复一次就是双倍开销）
            MemoryPage(
                onBack = { page = AppPage.SETTINGS },
                state = viewModel.memoryState,
                onRefresh = viewModel::refreshMemory,
                onSearch = viewModel::searchMemory,
                onRunCycle = viewModel::runMemoryCycle,
                onExport = { path -> viewModel.exportMemory(path) },
                vectorEnabled = viewModel.memoryVectorEnabled,
                vectorBusy = viewModel.memoryVectorBusy,
                vectorStatus = viewModel.memoryVectorStatus,
                vectorFailed = viewModel.memoryVectorFailed,
                onVectorToggle = viewModel::toggleMemoryVector,
                onVectorPanelOpened = viewModel::clearMemoryVectorStatus
            )
        }

        // 上下文洞察：纯只读一页。刷新由页面进页时自己发起（onRefresh），这一层不重复调。
        AnimatedVisibility(
            visible = page == AppPage.CONTEXT_HUB,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            ContextInsightPage(
                initialSessionId = viewModel.currentSessionId,
                currentSessionId = viewModel.currentSessionId,
                workspaces = viewModel.workspaceList.map { it.id to it.name },
                onBack = { page = if (contextHubFromHome) null else AppPage.SETTINGS },
                loadSession = { viewModel.insightSession(it) },
                loadGlobal = { viewModel.globalInsight(it) }
            )
        }

        AnimatedVisibility(
            visible = page == AppPage.ABOUT,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            AboutPage(onBack = { page = AppPage.SETTINGS })
        }

        AnimatedVisibility(
            visible = page == AppPage.WORKSPACE,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            WorkSpacePage(
                workspaces = viewModel.workspaceList,
                currentId = viewModel.currentWorkspaceId,
                streaming = viewModel.isStreaming,
                onPick = { id ->
                    viewModel.switchWorkspace(id)
                    page = null
                },
                onCreate = { name -> viewModel.createWorkspace(name) },
                onDelete = { id -> viewModel.deleteWorkspace(id) },
                onBack = { page = null }
            )
        }

        // 会话列表是抽屉的一级页
        AnimatedVisibility(
            visible = page == AppPage.SESSIONS,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            SessionListPage(
                // 传全量：行里要显示所属工作区名，归档 tab 也要跨工作区
                sessions = viewModel.sessionList,
                workspaces = viewModel.workspaceList,
                currentId = viewModel.currentSessionId,
                streaming = viewModel.isStreaming,
                onPick = { id ->
                    viewModel.switchSession(id)
                    page = null
                },
                onNew = { viewModel.newSession() },
                onFirstPage = { pagerAtFirstPage = it },
                onRename = { id, title -> viewModel.renameSession(id, title) },
                onArchive = { id, archived ->
                    if (archived) viewModel.archiveSession(id) else viewModel.unarchiveSession(id)
                },
                onDelete = { id -> viewModel.deleteSession(id) },
                onFork = { id ->
                    // 落回对话页：用户要接着在分支上说话，不该停在列表里
                    page = null
                    forkWithReload {
                        // forkSession 只作用于当前会话：从别的会话行进来时先切过去
                        if (id != viewModel.currentSessionId) viewModel.switchSession(id)
                        viewModel.forkSession()
                    }
                },
                onBack = { page = null }
            )
        }

        AnimatedVisibility(
            visible = page == AppPage.MODEL,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            ModelSettingsPage(
                onBack = { page = AppPage.SETTINGS },
                providers = viewModel.providers,
                currentProviderId = viewModel.currentProviderId,
                onSelectProvider = { id -> viewModel.selectProvider(id) },
                onSaveProvider = { cfg -> viewModel.saveProvider(cfg) },
                onDeleteProvider = { id -> viewModel.deleteProvider(id) },
                // 模型候选跟着当前 provider 走：内置 DeepSeek 才用 ApiDefaults.MODELS 的显示名
                models = viewModel.providers
                    .firstOrNull { it.id == viewModel.currentProviderId }
                    ?.models
                    .orEmpty()
                    .map { id ->
                        id to (ApiDefaults.MODELS.firstOrNull { it.first == id }?.second ?: id)
                    },
                // 思考开关与强度都并进模型页的行内展开：配置在一处，翻开关不用再跳一层
                thinkingEnabled = viewModel.thinkingEnabled,
                thinkingEffort = viewModel.thinkingEffort,
                // ViewModel 只暴露翻转；页面给的是「目标值」，和目标不一致时才翻，避免同一帧翻两次
                onThinkingToggle = { on ->
                    if (on != viewModel.thinkingEnabled) viewModel.toggleThinking()
                },
                onPickEffort = { effort -> viewModel.chooseThinkingEffort(effort) },
                balance = balance,
                balanceBusy = balanceBusy,
                onBalance = {
                    if (!balanceBusy) {
                        balanceBusy = true
                        scope.launch {
                            balance = BalanceClient.fetchBalance(
                                ApiConfig.loadApiKey(context),
                                ApiConfig.loadBaseUrl(context)
                            )
                            balanceBusy = false
                        }
                    }
                }
            )
        }

        // 权限管理：也是设置下的二级页，单独一层，滑入时设置页原地留着
        AnimatedVisibility(
            visible = page == AppPage.PERMISSION,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            PermissionPage(onBack = { page = AppPage.GENERAL })
        }

        // 上下文配置：提示词两段 + 压缩策略 + 摘要，一页改完。保存即落盘，页面自己不认存储
        AnimatedVisibility(
            visible = page == AppPage.CONTEXT_CONFIG,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            ContextConfigPage(
                onBack = { page = AppPage.SETTINGS },
                extraPrompt = extraPrompt,
                overridePrompt = overridePrompt,
                onSaveExtra = { text ->
                    extraPrompt = text
                    PromptStore.saveExtra(context, text)
                    viewModel.refreshContextBreakdown()
                },
                onSaveOverride = { text ->
                    overridePrompt = text
                    PromptStore.saveOverride(context, text)
                    viewModel.refreshContextBreakdown()
                },
                policy = viewModel.contextPolicy,
                snapshot = viewModel.contextSnapshot,
                summary = viewModel.contextSummary,
                onApply = { p -> viewModel.applyContextPolicy(p) },
                onSummarize = { done -> viewModel.summarizeNow(done) },
                onClearSummary = { viewModel.clearContextSummary() },
                onRefresh = { viewModel.refreshContext() },
                // 压缩/裁剪留下的操作记录（谁在什么时候压了什么）：只读列表，页面自己滚动展示
                events = viewModel.contextEventList
            )
        }

        // 计划：模型用 write_plan 写的那些。多份时上面有标签栏切着看，删光就自动回主页
        AnimatedVisibility(
            visible = page == AppPage.PLAN,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            PlanPage(
                plans = viewModel.plans,
                selectedId = viewModel.selectedPlanId,
                onSelect = { id -> viewModel.selectPlan(id) },
                onDelete = { id -> viewModel.deletePlan(id) },
                // 「继续修改」只回主页：替用户发一句反而会把「再看看」变成一次发送。
                // 带 id 进去，那份计划会被标成「已处理」，按钮随之变灰
                onContinueEdit = { id ->
                    viewModel.continuePlanEdit(id)
                    page = null
                },
                // 「同意执行」只对当前这一份生效；计划预览不清，之后还要能对着逐条回看
                onApprove = { id ->
                    viewModel.approvePlan(id)
                    page = null
                },
                onBack = { page = null }
            )
        }

        // 内置浏览器：点开回复里的链接就滑进来。它独立于设置层，返回只回主页（页面不销毁）
        AnimatedVisibility(
            visible = page == AppPage.BROWSER,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            BrowserPage(onBack = { page = null })
        }

        // 包管理：MCP 服务 / Skill 两个 tab（设置下的二级页）
        AnimatedVisibility(
            visible = page == AppPage.PACKAGE,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            PackagePage(
                registry = viewModel.toolRegistry,
                onBack = { page = AppPage.SETTINGS },
                onChanged = { viewModel.refreshSkillCatalog() },
                // 停在第一页时抽屉才抢向右位移（第二页要能正常往右翻回第一页）
                onFirstPage = { pagerAtFirstPage = it }
            )
        }

        // 日志：设置下的二级页，单独一层（读日志在 LogsPage 里自己进 IO，不阻塞这层滑入动画）
        AnimatedVisibility(
            visible = page == AppPage.LOGS,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            LogsPage(onBack = { page = AppPage.SETTINGS_DATA })
        }

        // 定时任务：一级入口在设置里，单独一层
        AnimatedVisibility(
            visible = page == AppPage.SCHEDULE,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            SchedulePage(onBack = { page = AppPage.SETTINGS })
        }

        // 子智能体详情：从右上角胶囊的卡片进来，可继续发消息
        AnimatedVisibility(
            visible = page == AppPage.SUBAGENT,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            SubAgentPage(
                entry = subAgentEntries.firstOrNull { it.id == subAgentDetailId },
                onBack = { page = null },
                onSend = { text ->
                    viewModel.continueSubAgent(subAgentDetailId ?: "", text, scope)
                }
            )
        }
}
