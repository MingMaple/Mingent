package com.zmf.agent.feature.chat
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
import com.zmf.agent.feature.float.ChatBridge
import com.zmf.agent.feature.settings.AboutPage
import com.zmf.agent.feature.settings.CreateWorkspaceDialog
import com.zmf.agent.feature.settings.LogsPage
import com.zmf.agent.feature.settings.ArchivePage
import com.zmf.agent.feature.settings.TrashPage
import com.zmf.agent.feature.settings.PackagePage
import com.zmf.agent.feature.settings.PermissionPage
import com.zmf.agent.feature.schedule.SchedulePage
import com.zmf.agent.feature.settings.ContextConfigPage
import com.zmf.agent.feature.settings.ContextHubPage
import com.zmf.agent.feature.settings.ContextInsight
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


/**
 * 主页：消息流 + 底部输入区，以及抽屉与各二级全屏页的唯一宿主。
 *
 * 页面状态全在内部：会话与模型状态来自自建的 [ChatViewModel]，页面层级由 [AppPage] 表达，
 * 二次确认、查看器、长按小窗等瞬时状态用局部 remember。父层只传主题、字号与启动会话 id。
 *
 * @param themeMode 当前主题模式；由外部持有并落盘
 * @param uiScale 全局界面字号倍率（只放大 sp，不动 dp）
 * @param initialSessionId 从通知点进来要切换到的会话；消费一次后自行清空
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    /** 全局界面字号（只放大 sp，不动 dp）；由 MainActivity 持有并落盘。 */
    uiScale: Float = 1f,
    onUiScaleChange: (Float) -> Unit = {},
    /** 从通知点进来要切到的会话 id；这里消费一次就清空。 */
    initialSessionId: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 全部瞬时界面状态集中在这里：拆块时子组合用 `var x by ui.x` 拿到同一份状态
    val ui = remember { ChatUiState(context) }
    val viewModel = remember { ChatViewModel(context) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var page by ui.page
    // 回收站页返回时自增：数据页一直挂在这层下面，不换 key 那行统计就不会刷新
    var trashRefreshKey by ui.trashRefreshKey
    var drawerOpen by ui.drawerOpen
    // 上下文洞察页是从哪进来的：主页输入框那条路要退回主页，设置首页那条要退回设置。
    // 只记一个来源就够，不必为每个二级页都备一套返回栈（页面上只有它有两个入口）。
    var contextHubFromHome by ui.contextHubFromHome
    // 抽屉里的「＋ 新建工作区」：抽屉没有输入框，弹一个极简输入框
    var showCreateWorkspace by ui.showCreateWorkspace
    // 轨迹页固定显示代码块，不受「显示工作代码视图」开关影响
    val traceView = true
    // 输入框弹出面板的「开着没 / 关掉」把手：面板状态仍归 Composer，父层只借它铺遮罩
    val composerPanel = ui.composerPanel
    // 包管理 / 会话列表这两页的内容区是 HorizontalPager：只有它停在第一页时，
    // 抽屉才敢在 Initial pass 抢走向右的位移（否则从第二页往右翻页就翻不动了）
    var pagerAtFirstPage by ui.pagerAtFirstPage
    var extraPrompt by ui.extraPrompt
    var overridePrompt by ui.overridePrompt
    // 上下文页要的全局总账快照：进页面时读一次盘（UsageLedger.load 自带「只读一次」的闸门）
    val lastLedger = remember(page) {
        UsageLedger.load(context)
        UsageLedger.snapshot()
    }
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    // 输入栏实测高度：附件面板要浮在它上面，写死 dp 会跟字号档位对不上
    var inputBarHeightPx by ui.inputBarHeightPx
    var topBarHeightPx by ui.topBarHeightPx
    // 图片详情：这条消息的全部图片 + 起点下标（详情里按缩略图顺序左右切换）
    var viewer by ui.viewer

    // 子任务面板：Registry 是内存 object（子智能体在 IO 协程里写）。
    // 以前这里是 300ms 轮询：空闲、一个子智能体都没有时也在每 300ms 醒一次并分配一份快照。
    // 现在改成订阅版本号：没有子智能体在跑时，那条 Flow 一条都不会发，主线程一次都不会被叫醒。
    var subAgentPanelOpen by ui.subAgentPanelOpen
    var subAgentDetailId by ui.subAgentDetailId
    val subAgentVersion by SubAgentRegistry.version.collectAsState()
    // 只在版本真的变了时重取快照（首帧也走这条 remember，等于开页面先取一次）
    val subAgentEntries = remember(subAgentVersion) { SubAgentRegistry.snapshot() }
    val subAgentRunning = subAgentEntries.count { it.state == SubAgentRegistry.RUNNING }
    // 记录被清空（或全部被清掉）就把面板收起：胶囊已经消失，卡片不该继续挂着
    LaunchedEffect(subAgentEntries.isEmpty()) {
        if (subAgentEntries.isEmpty()) {
            subAgentPanelOpen = false
        }
    }

    // 数据备份：导出选项 / 导入确认 / 导入报告
    var showExportDialog by ui.showExportDialog
    var includeTerminal by ui.includeTerminal
    var includeLogs by ui.includeLogs
    var includeMemory by ui.includeMemory
    // Ubuntu 环境段的真实体积（字节）：0 表示还没量出来（弹窗刚开、盘上文件多时要走一两秒）
    var terminalBytes by ui.terminalBytes
    // 每次打开弹窗都重量一次：用户可能在两次导出之间用 apt 装了不少东西
    LaunchedEffect(showExportDialog) {
        if (showExportDialog) {
            terminalBytes = withContext(Dispatchers.IO) { BackupArchive.terminalSectionBytes(context) }
        }
    }
    var importUri by ui.importUri

    // 「清理未引用附件」的结果：只在这一层显示，页面关掉就没了
    var orphanStatus by ui.orphanStatus

    // 余额是「查了才有」的临时结果，不落盘：只在模型页显示
    var balance by ui.balance
    var balanceBusy by ui.balanceBusy

    // 分叉会话的「瞬灭再加载」：分叉完屏幕内容要闪一下，
    // 否则复制整条会话时画面纹丝不动，看不出已经分叉成功。
    var forkReloading by ui.forkReloading
    val forkReloadAlpha by animateFloatAsState(
        targetValue = if (forkReloading) 1f else 0f,
        animationSpec = tween(durationMillis = if (forkReloading) 70 else 140),
        label = "forkReload"
    )
    // 所有「分叉」入口都走它：遮罩盖住 → 真正切会话 → 淡出，露出重新加载后的内容
    val forkWithReload: (() -> Unit) -> Unit = { action -> ui.forkWithReload(scope, action) }
    // 「本轮用量」伴随面板：贴着被点的那枚用量按钮弹出，点面板以外任何地方收掉
    var turnUsage by ui.turnUsage
    var selectionTarget by ui.selectionTarget
    var selectionVisible by ui.selectionVisible
    // 图片长按小窗：内容与可见性分开存，收起那 140ms 里内容还得留在场上播淡出
    var imageMenuTarget by ui.imageMenuTarget
    var imageMenuVisible by ui.imageMenuVisible
    var rollbackUserIndex by ui.rollbackUserIndex
    // 清单条点开后的全表弹窗（只读）
    var todoDialogVisible by ui.todoDialogVisible
    // 清单条是否被用户收起：只影响这一屏，不动盘上的清单
    var todoBarHidden by ui.todoBarHidden

    // 从通知点进来：切一次就把 id 清掉，界面重组时不再重复切
    // （switchSession 对「已经是当前会话」本来就是空操作，这里只是别让它每帧都调一次）
    var pendingSession by remember(initialSessionId) { mutableStateOf(initialSessionId) }
    LaunchedEffect(pendingSession) {
        val id = pendingSession
        if (id != null) {
            // 点通知要先回主页：用户可能正停在设置 / 轨迹 / 浏览器这些子页上，
            // 只切会话不换页的话，看到的仍然是原来那一屏
            page = null
            drawerOpen = false
            viewModel.switchSession(id)
            pendingSession = null
        }
    }

    // 回前台从盘上重读会话；输入法故意不在这里收，切后台再回来时它应该还在输入框上等着。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.reloadSessionsFromDisk()
                // 退到后台就落一次盘：用户口径是「按返回只进后台、永不杀后台」，
                // 但系统照样可能在后台回收进程，所以这一刻要把会话写进去。
                Lifecycle.Event.ON_STOP -> viewModel.persistForBackground("on-stop")
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 悬浮小窗要读到当前会话：把 ChatScreen 的 viewModel 挂到桥上，离开页面时摘掉。
    // 注销只在「桥上还是自己」时才清空，避免把别处后注册的实例顶掉。
    DisposableEffect(viewModel) {
        ChatBridge.viewModel = viewModel
        onDispose {
            if (ChatBridge.viewModel === viewModel) ChatBridge.viewModel = null
        }
    }

    // 两条选文件入口：OpenDocument 是系统文稿页，GetContent 会进设备自己的文件管理器
    // （部分机型的文稿页侧边栏里没有「文件管理」入口，留第二个入口兜底）。
    // 文稿页这条不用契约类：ActivityResultContracts.OpenDocument 自己造 Intent，
    // 塞不进 EXTRA_INITIAL_URI，而「上次停在哪个目录」只有自己发 Intent 才能指定。
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null) {
            // 这一下是系统唯一允许拿持久读权限的时机：之后重试导入、或工作区文件被清掉再读，都还读得到
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            rememberPickerDir(context, uri, viewModel.currentSessionId)
            viewModel.attachFile(uri, scope)
        }
    }
    // 导出：让用户在系统里选保存位置，不申请任何存储权限
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportData(
                uri = uri,
                options = BackupOptions(
                    includeTerminal = includeTerminal,
                    includeLogs = includeLogs,
                    includeMemory = includeMemory
                ),
                scope = scope
            )
        }
    }

    // 下载日志：同样走 SAF，用户自己挑位置；文件名带时间戳，多下几次不会互相覆盖
    val logLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) viewModel.downloadLogs(uri)
    }

    // 导入：先只读清单，确认框里看清楚再真的覆盖
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importUri = uri
            viewModel.loadBackupManifest(uri, scope)
        }
    }

    val lastMessage = viewModel.messages.lastOrNull()
    // 一个回合 = 一次提问到下一条提问之前（含中间的工具结果消息）。
    // 结构只在「换会话 / 条数 / 最后一条内容」变化时才会变；流式输出每来一个 token 都重扫一遍
    // 全量消息纯属白算（长会话里就是每次 O(n) 的扫描 + 一次列表分配）。
    // 用最后一条对象当 key 是精确的，没变时也只是一次引用比较。
    val items = remember(viewModel.currentSessionId, viewModel.messages.size, lastMessage) {
        buildChatItems(viewModel.messages)
    }

    // 状态行那两项在 ViewModel 里是计算属性：轮数要扫一遍全部消息、步数要扫一遍会话表。
    // 组合期每来一个 token 都会读一次，所以按同一组特征记住上一次的结果；
    // 步数还要跟着本轮 stats.steps 走（一轮之内步数会涨，不带它就会停在旧数字）。
    val statusRounds = remember(viewModel.currentSessionId, viewModel.messages.size, lastMessage) {
        viewModel.statusRounds
    }
    val statusSteps = remember(
        viewModel.currentSessionId,
        viewModel.messages.size,
        lastMessage,
        viewModel.stats.steps
    ) {
        viewModel.statusSteps
    }

    // 图片路径都是工作区相对的：气泡里的图片网格与输入框上方的缩略图都从这个根解码，
    // 口径跟发送时一致（未分类会话落在默认工作区）。按工作区/选中态缓存，别每帧都去看目录。
    val imageRoot = remember(viewModel.workspaceList, viewModel.currentWorkspaceId) {
        val space = viewModel.workspaceList.firstOrNull { it.id == viewModel.currentWorkspaceId }
            ?: WorkSpaceStore.defaultSpace(context)
        // 与 ChatViewModel.rootOf 同一口径：优先用户选的自定义目录
        WorkSpaceStore.resolveRoot(context, space) ?: WorkSpaceStore.dirOf(context, space)
    }
    val followKey = listOf(
        items.size,
        viewModel.messages.size,
        lastMessage?.content?.length ?: 0,
        lastMessage?.reasoningContent?.length ?: 0
    )

    val density = LocalDensity.current
    // 输入法要占的高度：整屏唯一的那一份 IME 占位就从这里出（别处不许再写死 IME 高度）。
    // 窗口是 adjustNothing + edgeToEdge，系统既不缩窗口也不吃掉 ime inset，
    // 所以这里读到的就是键盘真正压住的高度。
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    // Android 11 起系统按动画逐帧上报 ime inset：直接跟它走，输入框上移与键盘上滑就是同一段运动，
    // 不滞后也不提前。更早的系统只在最后给一次终点值（等于瞬移），才退回一段 tween 兜底；
    // 两条路只走一条，绝不叠加：叠加就是先被 tween 拖着走、再被真实值拽一下。
    val imePad = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        imeBottomDp
    } else {
        animateDpAsState(
            targetValue = imeBottomDp,
            animationSpec = tween(durationMillis = 240),
            label = "imeBottomPadding"
        ).value
    }

    // 输入法弹出时：本来就停在底部（或在底部 200dp 内）才把最后一条抬进视野，
    // 翻着历史看的时候不打断。
    val imeVisible = WindowInsets.isImeVisible
    val bottomTolerancePx = with(density) { 200.dp.toPx() }.toInt()

    // 键盘弹起时视口会先变矮：那一刻再问「是不是停在底部」，原来贴着底的位置已经被顶开了，
    // 问出来的永远是「不在底部」，列表就一条都不动。所以趁键盘收着的时候一直记着这个判断，
    // 真正弹起时用的是「弹起之前」的那一份。
    var wasAtBottom by ui.wasAtBottom
    LaunchedEffect(imeVisible) {
        if (imeVisible) return@LaunchedEffect
        snapshotFlow { listState.isNearBottom(bottomTolerancePx) }.collect { wasAtBottom = it }
    }

    // 新消息 / 工具返回 / 流式正文都会让 followKey 变。只有用户本来就贴在底部时才跟随，
    // 上翻看历史的时候不再被拽回底部（原来每来一次工具调用就弹回底）。
    LaunchedEffect(followKey) {
        if (!wasAtBottom) return@LaunchedEffect
        followBottom(listState, items.lastIndex)
    }

    // 冷启动 / 切会话的第一次落位：必须直接落在最后一条，而不是列表顶部。
    // 为什么不能只靠上面那条跟随逻辑：它要问 wasAtBottom，而 wasAtBottom 是由下面那条
    // 「是否贴底」采样写进去的：重开 App 那一刻列表还在顶部，采样先跑出 false，
    // 跟随逻辑就被自己让位掉了，于是停在最顶上。
    // 这里单独一条一次性锚定：不管当下停在哪，先落到最后一条并恢复「贴底」这个前提。
    var anchoredInitialPosition by remember(viewModel.currentSessionId) { mutableStateOf(false) }
    LaunchedEffect(items.size, viewModel.currentSessionId) {
        if (anchoredInitialPosition || items.isEmpty()) return@LaunchedEffect
        anchoredInitialPosition = true
        wasAtBottom = true
        followBottom(listState, items.lastIndex)
    }

    // 键盘动画期间逐帧跟住底部：视口每矮 Δ，列表就同步滚 Δ，最后一条消息在屏幕上原地不动。
    // 效果就是「输入框与列表同一段运动」。只在本来就贴着底部时跟，翻着历史看的时候不打断。
    //
    // 只跟「视口变矮」这一侧。键盘收起时视口是变高的，那时候再按 Δ 滚（Δ 为负）等于把内容
    // 往下推，最后一条连同气泡底部的用量胶囊就被推到输入框和待办条底下去了：遮挡就是这么来的。
    var appliedImePadPx by remember { mutableStateOf(0f) }
    LaunchedEffect(listState, imePad) {
        val targetPx = with(density) { imePad.toPx() }
        val delta = targetPx - appliedImePadPx
        // 先记「已经跟到哪」再决定动不动：中途让位（不在底部）时，下一次的差量才不会算错
        appliedImePadPx = targetPx
        if (delta <= 0f) return@LaunchedEffect
        if (!wasAtBottom) return@LaunchedEffect
        listState.scroll { scrollBy(delta) }
    }

    // 消息区的可见高度：键盘开合、待办条（n/n 完成）/ 待发附件行 / 排队行冒出或收起都会改它。
    // 这些兄弟节点是从下面把列表顶掉一截的，视口一变，原来贴着底的那条就可能落到视口外，
    // 所以「贴底」这件事不能只在键盘动画里做一次，得跟着视口的变化重算。
    // 视口落定后再对一次底部（键盘开合、待办条、待发附件行都会改视口）。
    // 这里绝不能把 imePad / 视口值放进 effect 的 key：键盘动画期间它们逐帧变化，
    // effect 会被取消并重启几十次，等于每帧都排一次「等一会儿再吸附」：这就是掉帧主因。
    // 用 collectLatest 只在视口安静 120ms 之后执行一次。
    val lastItemIndex = rememberUpdatedState(items.lastIndex)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportEndOffset }.collectLatest {
            delay(120)
            if (!wasAtBottom) return@collectLatest
            settleToBottom(listState, lastItemIndex.value)
        }
    }

    /**
     * 选文件 / 选图片走同一个入口：回到页面时输入法要跟「进去之前」一致。
     * 进去前是收着的，光靠系统是收不住的：输入框一直握着焦点，回到前台系统会把键盘重新弹出来，
     * 所以先把焦点和键盘收掉；进去前是展开的就什么都不碰，回来它自己还贴在输入框上。
     */
    fun launchPicker(pick: () -> Unit) {
        if (!imeVisible) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        pick()
    }

    /** 进上下文洞察页；[fromHome] 决定返回落到哪一层（主页 / 设置首页）。 */
    fun openContextHub(fromHome: Boolean) {
        contextHubFromHome = fromHome
        drawerOpen = false
        page = AppPage.CONTEXT_HUB
    }

    /**
     * 打开系统文稿页。
     *
     * 初始目录只在「同一会话 + 距上次不到 30 分钟」时指定；其余一律不给 EXTRA_INITIAL_URI（回到顶层），
     * 否则上次（甚至十天前、别的会话里）选过的目录会一直把人困在深处。
     */
    fun openFilePicker() {
        launchPicker {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
            pickerInitialDir(context, viewModel.currentSessionId)?.let { dir ->
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, dir)
            }
            filePicker.launch(intent)
        }
    }

    // 卡片是临时菜单：一滚动消息就收起
    LaunchedEffect(listState.isScrollInProgress) {
    }

    // 长按小窗只在一个前提下弹：列表此刻没在滑。
    // 快速滑完、手指按住让惯性收住的那一下，手指确实没动，会被认成长按；
    // 这时候弹「复制 / 选择文本 / 分支」纯是打断。
    fun longPressAllowed(): Boolean = !listState.isScrollInProgress

    // 抽屉与任何二级页都不该留着输入法：进来之前先把焦点和键盘收掉
    LaunchedEffect(drawerOpen, page) {
        if (drawerOpen || page != null) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    // 计划全被删光就自动回主页：页面上已经一份可看的都没有，留着一层空白只会让人以为卡住了
    LaunchedEffect(page, viewModel.plans.isEmpty()) {
        if (page == AppPage.PLAN && viewModel.plans.isEmpty()) page = null
    }

    // 模型刚交出一份新计划：本轮跑完就直接进计划页，不用再自己去点右上角。
    // 两条 effect 分工：planIdsKnown 负责认出「这次真的多了新计划」，
    // pendingPlanJump 把跳转压到本轮结束那一刻：工具调用的中途跳页会把还在流式的回复切走。
    var planIdsKnown by remember(viewModel.currentSessionId) { mutableStateOf<Set<String>?>(null) }
    var pendingPlanJump by remember(viewModel.currentSessionId) { mutableStateOf(false) }
    LaunchedEffect(viewModel.plans, viewModel.currentSessionId) {
        val ids = viewModel.plans.map { it.id }.toSet()
        val known = planIdsKnown
        planIdsKnown = ids
        // 首次（冷启动 / 切会话）只把现有计划记下来，别把历史计划当成「刚生成」而弹页面
        if (known == null) return@LaunchedEffect
        val freshPending = (ids - known).any { id ->
            viewModel.plans.any { it.id == id && it.actionable }
        }
        if (freshPending) pendingPlanJump = true
    }
    LaunchedEffect(viewModel.isStreaming) {
        if (viewModel.isStreaming || !pendingPlanJump) return@LaunchedEffect
        pendingPlanJump = false
        // 只从主页跳：用户正停在设置 / 终端时，不该被一个新计划抢走页面
        if (page == null) page = AppPage.PLAN
    }

    // 链接的落点：注册期间，回复里（含用户气泡、子智能体页）所有 http/https 链接
    // 都进内置浏览页。没注册时 LinkRouter.open 返回 false，什么也不会发生：
    // 不能退回去弹系统浏览器，那等于把用户扔出 App。
    DisposableEffect(Unit) {
        LinkRouter.onOpen = { url ->
            BrowserHolder.open(context, url)
            page = AppPage.BROWSER
            true
        }
        onDispose { LinkRouter.onOpen = null }
    }

    // 主页按返回 = **只退到桌面（进后台），绝不结束 Activity**。
    // 为什么不能让它按系统默认结束 Activity：ChatViewModel 是 remember 在 Compose 里的普通对象，
    // Activity 一销毁它就没了，内存里还没落盘的消息跟着丢：正是之前「按返回键丢尾部」的真因。
    // 注册位置说明：这条在**最前面**注册 = 优先级最低；下面那些更具体的返回（抽屉 / 二级页 / 浮层）
    // 仍然先拦，它们都不拦的时候才轮到这条。
    BackHandler(enabled = true) {
        viewModel.persistForBackground("back-to-home")
        val activity = findActivity(context)
        if (activity != null) {
            activity.moveTaskToBack(true)
        } else {
            // 拿不到 Activity（极端情况）就发 HOME intent，效果一样：回桌面、进程留着
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    // 返回键按「最具体」到「最宽」的顺序拦截，最后注册的优先级最高：
    // 选择页 > 长按小窗 > 抽屉 > 二级页（回各自上一级）> 设置首页 > 一级页 > 主页时退到桌面
    // 浏览页的返回只回主页：网页实例留着（返回不关闭网页），顶栏网页图标再点回来
    BackHandler(enabled = page == AppPage.BROWSER) { page = null }
    BackHandler(enabled = page == AppPage.WORKSPACE) { page = null }
    BackHandler(enabled = page == AppPage.SESSIONS) { page = null }
    BackHandler(enabled = page == AppPage.SUBAGENT) { page = null }
    // 结构：设置首页 → 通用设置 / 模型 / 长期记忆 / 上下文洞察 / 上下文配置 / 包管理 / 数据，
    // 通用设置 → 权限、权限档、定时任务；数据 → 日志、归档。
    // 每一级只退回到它的上一级，不能跳级。
    BackHandler(enabled = page == AppPage.TRACE) { page = null }
    BackHandler(enabled = page == AppPage.PERMISSION_TIER) { page = AppPage.GENERAL }
    BackHandler(enabled = page == AppPage.MODEL) { page = AppPage.SETTINGS }
    BackHandler(enabled = page == AppPage.ARCHIVE) { page = AppPage.SETTINGS_DATA }
    BackHandler(enabled = page == AppPage.TRASH) {
        trashRefreshKey++
        page = AppPage.SETTINGS_DATA
    }
    BackHandler(enabled = page == AppPage.PERMISSION) { page = AppPage.GENERAL }
    BackHandler(enabled = page == AppPage.PACKAGE) { page = AppPage.SETTINGS }
    BackHandler(enabled = page == AppPage.LOGS) { page = AppPage.SETTINGS_DATA }
    BackHandler(enabled = page == AppPage.SCHEDULE) { page = AppPage.GENERAL }
    BackHandler(enabled = page == AppPage.GENERAL) { page = AppPage.SETTINGS }
    BackHandler(enabled = page == AppPage.MEMORY) { page = AppPage.SETTINGS }
    BackHandler(enabled = page == AppPage.PLAN) { page = null }
    // 洞察页的返回要回它进来的那一层：从主页输入框进的退回主页，从设置进的退回设置
    BackHandler(enabled = page == AppPage.CONTEXT_HUB) {
        page = if (contextHubFromHome) null else AppPage.SETTINGS
    }
    BackHandler(enabled = page == AppPage.CONTEXT_CONFIG) { page = AppPage.SETTINGS }
    BackHandler(enabled = page == AppPage.ABOUT) { page = AppPage.SETTINGS }
    BackHandler(enabled = page == AppPage.SETTINGS_DATA) { page = AppPage.SETTINGS }
    BackHandler(enabled = page == AppPage.SETTINGS) { page = null }
    BackHandler(enabled = subAgentPanelOpen) { subAgentPanelOpen = false }
    BackHandler(enabled = viewer != null) { viewer = null }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    BackHandler(enabled = turnUsage != null) { turnUsage = null }
    BackHandler(enabled = imageMenuVisible) { imageMenuVisible = false }
    BackHandler(enabled = selectionVisible) { selectionVisible = false }

    // 根底：抽屉、页面、主页都铺在这一层底色上。窗口是 edgeToEdge，没有它时页面滑动的余量
    // 会透出系统窗口自己的底色（深色模式下像一道白边），而且二级页透明处会看到杂乱的重绘。
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(modifier = Modifier.fillMaxSize()) {
        SaltDrawer(
            open = drawerOpen,
            onOpenChange = { drawerOpen = it },
            // 抽屉在**任何页面**都能拉出来（一级页、二级页都一样）。
            // 包管理 / 会话列表的内容区是 HorizontalPager，横向拖拽会被它先吃掉，所以不走
            // 顶栏那条路：pager 滑到第一页还继续往右拖时，多余位移由 SaltDrawer 内部的
            // nestedScroll 接住变成抽屉的 progress（见 SaltDrawer.kt 的 connection）；
            // 这两页的顶栏 / tab 行不在 pager 里，横滑仍由根 Box 的整屏 draggable 直接拉起。
            // 二级页（计划页 / 将来的内置浏览器）不开「向右滑开抽屉」：
            // 浏览器内部自己就有横滑内容，一滑就开抽屉没法用。回主页只能点返回或系统侧滑返回。
            // 包管理 / 会话列表是 pager，本来就靠 nestedScroll 衔接，保留；抽屉开着时允许滑回。
            // 设置层的一级页、二级页全都要能右滑出抽屉；
            // 只有计划页与内置浏览器例外（浏览器内部自己横滑内容，一滑就开抽屉没法用）。
            gestureEnabled = drawerOpen || page == null ||
                (page != AppPage.PLAN && page != AppPage.BROWSER),
            // 主页左滑进轨迹页；开关关着就不传回调，左滑完全没反应
            onSwipeLeft = null,
            // 只有「当前就在包管理 / 会话列表、且它停在第一页」时，才让抽屉抢向右的位移
            interceptRightSwipe = (page == AppPage.PACKAGE || page == AppPage.SESSIONS) && pagerAtFirstPage,
            panel = {
                AppDrawerContent(
                    workspaces = viewModel.workspaceList,
                    sessions = viewModel.sessionList,
                    currentWorkspaceId = viewModel.currentWorkspaceId,
                    currentSessionId = viewModel.currentSessionId,
                    themeMode = themeMode,
                    // 主题不再跳页面：抽屉里点开是内联三选，选中即落盘（MainActivity 写 ThemeStore）
                    onThemePick = { onThemeModeChange(it) },
                    onHome = {
                        drawerOpen = false
                        page = null
                    },
                    onTerminal = {
                        drawerOpen = false
                        context.startActivity(Intent(context, com.zmf.agent.feature.terminal.TerminalActivity::class.java))
                    },
                    onSettings = {
                        drawerOpen = false
                        page = AppPage.SETTINGS
                    },
                    onSearchSessions = {
                        drawerOpen = false
                        page = AppPage.SESSIONS
                    },
                    // 抽屉只在 4 种情况下收回：点会话进入、分叉进入、删掉当前会话后自动新建、
                    // 新建工作区后自动建会话进入。其余（重命名 / 置顶 / 归档 / 复制 / 删别的会话 /
                    // 删工作区 / 折叠展开 / 在这个工作区里新建会话）一律保持抽屉展开。
                    onPickSession = { id ->
                        drawerOpen = false
                        page = null
                        viewModel.switchSession(id)
                    },
                    onCreateSession = { workspaceId ->
                        if (workspaceId != viewModel.currentWorkspaceId) viewModel.switchWorkspace(workspaceId)
                        viewModel.newSession()
                    },
                    onCreateWorkspace = { showCreateWorkspace = true },
                    onRenameWorkspace = { id, name -> viewModel.renameWorkspace(id, name) },
                    onDeleteWorkspace = { id -> viewModel.deleteWorkspace(id) },
                    onRenameSession = { id, name -> viewModel.renameSession(id, name) },
                    onForkSession = { id ->
                        drawerOpen = false
                        page = null
                        forkWithReload {
                            viewModel.switchSession(id)
                            viewModel.forkSession()
                        }
                    },
                    onArchiveSession = { id -> viewModel.archiveSession(id) },
                    onPinSession = { id, pinned -> viewModel.pinSession(id, pinned) },
                    onCopySessionId = { id ->
                        copyToClipboard(context, id)
                        Toast.makeText(context, "已复制 Session ID", Toast.LENGTH_SHORT).show()
                    },
                    onDeleteSession = { id ->
                        // 删的就是当前会话：ViewModel 会补一个空会话，界面对齐回主页并收起抽屉
                        val wasCurrent = id == viewModel.currentSessionId
                        viewModel.deleteSession(id)
                        if (wasCurrent) {
                            drawerOpen = false
                            page = null
                        }
                    },
                    // 打开系统悬浮小窗：先把抽屉收掉，再按权限决定直接起服务还是先要权限。
                    // 小窗是独立的系统悬浮窗，所以起完服务把 App 退到后台，只留小窗在桌面上。
                    onFloatingWindow = {
                        drawerOpen = false
                        if (Settings.canDrawOverlays(context)) {
                            // expand = true：点入口直接是小窗；球态只在用户按「∨」折叠后才出现
                            FloatingBallService.start(context, expand = true)
                            // 只有「在 App 里主动点启动」这一下才退后台；拿不到 Activity 就回桌面兜底
                            val activity = findActivity(context)
                            if (activity != null) {
                                activity.moveTaskToBack(true)
                            } else {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_MAIN)
                                            .addCategory(Intent.CATEGORY_HOME)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        } else {
                            Toast.makeText(context, "请允许悬浮窗权限", Toast.LENGTH_SHORT).show()
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + context.packageName)
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }
                )
            }
        ) {
            // 轨迹页和主页是「连体平移」的一对：向左滑把主页推出左边界、轨迹从右边界补进来，
            // 向右滑原路退回。它套在抽屉 content 里面（必须如此）：SaltFullPane 在关闭态只消费
            // 向左的位移，向右仍然落到外面这层抽屉上，两个手势不会互相抢。
            SaltFullPane(
                open = page == AppPage.TRACE,
                onOpenChange = { open -> page = if (open) AppPage.TRACE else null },
                // 只有主页能左滑进轨迹：别的页面（设置/包管理…）里左滑不该冒出轨迹页
                gestureEnabled = page == null || page == AppPage.TRACE,
                panel = {
                    TracePage(
                        sessionTitle = viewModel.currentTitle(),
                        messages = viewModel.messages,
                        // 轨迹页的缩略图与主页走同一个图片根：两边解码的是同一份文件，
                        // 少传一个根就会出现「主页能看、轨迹页是灰块」
                        imageRoot = imageRoot,
                        onBack = { page = null }
                    )
                }
            ) {
            // 页面层（设置 / 工作区 / 思考 / 权限 / 主题 / 会话列表 / 模型 / 统计 / 上下文 /
            // 权限管理 / 提示词 / 包管理）必须放进 content 子树：页面层若和 SaltDrawer 平级，
            // Compose 的指针命中只会走最上面的那个兄弟节点，抽屉根 Box 上的整屏 draggable
            // 就永远拿不到事件，二级页横滑拉不出抽屉（包管理 / 会话列表的左边缘手势条同理被挡）。
            Box(modifier = Modifier.fillMaxSize()) {
        // 查看器打开时把会话内容整体磨砂（API 31+ 生效），查看器只负责半透明底
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // 16dp 而不是 24dp：磨砂到底下要还能看出「这里原来有几行字」，
                // 24dp 会把整屏糊成一块纯色，查看器再叠半透明底就看不出深度了
                .then(if (viewer != null) Modifier.blur(16.dp) else Modifier),
            color = MaterialTheme.colorScheme.background
        ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // 输入法占位只走这一份：上面已经按动画值算好，别再叠 imePadding（会加双份）
                        .padding(bottom = imePad)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { topBarHeightPx = it.size.height }
                    ) {
                        ChatTopBar(
                            viewModel = viewModel,
                            onMenuClick = { drawerOpen = true },
                            subAgentCount = subAgentEntries.size,
                            subAgentRunning = subAgentRunning,
                            onSubAgentClick = { subAgentPanelOpen = !subAgentPanelOpen },
                            plans = viewModel.plans,
                            // 进页面前重读一次：模型可能刚交了新计划，那一刻预览要落在最新那份上
                            onPlanClick = {
                                viewModel.reloadPlans()
                                page = AppPage.PLAN
                            },
                            // 有没有活着的网页：有才画那枚图标（返回主页后靠它回去）
                            browserOpen = BrowserState.alive,
                            onBrowserClick = { page = AppPage.BROWSER }
                        )
                    }

                    // 消息区 + 挂在上方的面板：面板是浮层，不吃列表高度（列表不会重排）
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        // 内容不满一屏时贴底（Bottom）：新消息应该从底部往上长，而不是浮在半空。
                        // 内容超过一屏后 Bottom 对齐不再起作用，滚动行为与原来的 spacedBy 完全一致。
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom)
                    ) {
                        if (items.isEmpty()) {
                            item {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "输入消息开始对话",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    )
                                }
                            }
                        }
                        // 必须给稳定 key：items(count = ...) 默认拿下标当 key，而流式输出时
                        // 消息条数一直在变，同一下标会指向「另一个回合」，LazyColumn 就把新内容
                        // 复用进旧槽位，槽位里残留的展开/点击状态跟着错位。
                        // UserItem 用消息下标；AssistantItem 用这一轮第一条消息的下标（一轮内不变）。
                        items(
                            count = items.size,
                            key = { position ->
                                when (val item = items[position]) {
                                    is ChatItem.UserItem -> "u" + item.index
                                    is ChatItem.AssistantItem -> "a" + item.indices.first()
                                }
                            },
                            // contentType 分开：用户气泡与助手回合结构不同，不分开会让列表
                            // 把这两种行当同一类型复用，重组时更容易带错状态。
                            contentType = { position ->
                                when (items[position]) {
                                    is ChatItem.UserItem -> "user"
                                    is ChatItem.AssistantItem -> "assistant"
                                }
                            }
                        ) { position ->
                            when (val item = items[position]) {
                                is ChatItem.UserItem -> UserRow(
                                    message = item.message,
                                    roundsFromEnd = item.roundsFromEnd,
                                    imageRoot = imageRoot,
                                    onCopy = { text -> copyToClipboard(context, text) },
                                    onSelectText = { text ->
                                        selectionTarget = SelectionTarget(
                                            title = "选择文本",
                                            text = text,
                                            reasoning = ""
                                        )
                                        selectionVisible = true
                                    },
                                    onRollback = { rollbackUserIndex = item.index },
                                    onImageClick = { paths, index -> viewer = ViewerTarget(paths, index) },
                                    onImageLongPress = { rel, anchor ->
                                        // 同 openMenu：惯性还没停就按住的那一下不算长按
                                        if (longPressAllowed()) {
                                            imageMenuTarget = ImageMenuTarget(
                                                relPath = rel,
                                                userIndex = item.index,
                                                anchor = anchor
                                            )
                                            imageMenuVisible = true
                                        }
                                    }
                                )

                                is ChatItem.AssistantItem -> AssistantRow(
                                    all = viewModel.messages,
                                    indices = item.indices,
                                    streaming = viewModel.isStreaming &&
                                        item.indices.lastOrNull() == viewModel.messages.lastIndex,
                                    toolOutcomes = viewModel.toolOutcomes,
                                    onCopy = { text -> copyToClipboard(context, text) },
                                    onSelectText = { text, reasoning ->
                                        selectionTarget = SelectionTarget(
                                            title = "选择文本",
                                            text = text,
                                            reasoning = reasoning
                                        )
                                        selectionVisible = true
                                    },
                                    onFork = { boundary -> forkWithReload { viewModel.forkSession(boundary) } },
                                    onUsage = { usage, anchor ->
                                        turnUsage = TurnUsageTarget(usage = usage, anchor = anchor)
                                    }
                                )
                            }
                        }
                    }

                    // 面板打开时铺一层透明遮罩：点/划「输入条以外的区域」就收面板。
                    // 必须是消息区 Box 的最后一个孩子位：输入条与面板浮层都在它上层，点面板不会走到这里。
                    // 早先误放进「待办条可见」的 if 分支里，没有待办的会话整个就没有遮罩：面板开着仍能
                    // 滚列表、还能点开折叠条，面板赖着不走。
                    ComposerScrim(
                        visible = composerPanel.open,
                        onDismiss = { composerPanel.dismiss() }
                    )

                    // 分叉时的「瞬灭再加载」：用页面底色盖住消息区再淡出。
                    // 放在这一层最后：要盖住列表与上面的透明遮罩；不挂手势，闪完就透明。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = forkReloadAlpha }
                            .background(MaterialTheme.colorScheme.background)
                    )
                    }

                    viewModel.activityText?.let { activity ->
                        Text(
                            text = activity,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            // 提示条属于「状态文字」，不是可点强调项：跟图标/次要文字同一支中性灰
                            color = Palette.Neutral
                        )
                    }

                    viewModel.lastError?.let { error ->
                        Text(
                            text = error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 4.dp)
                                .clickable { viewModel.clearError() },
                            fontSize = 12.sp,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // 待发送附件：文件和图片合成**同一行**，按添加顺序从左往右排，超出屏幕左右滑。
                    // 之前是上下两行（文件一行、图片一行），图片那层把文件顶上去还互相压住。
                    // 两条队列在 ViewModel 里分开存（图片要单独压 JPEG），拿不到统一的入队顺序，
                    // 所以按各自落盘的时间戳还原真实先后（见 mergePending）。
                    val pendingFiles = viewModel.attachments.toList()
                    val pendingImagesNow = viewModel.pendingImages.toList()
                    val pendingEntries = remember(imageRoot, pendingFiles, pendingImagesNow) {
                        mergePending(imageRoot, pendingFiles, pendingImagesNow)
                    }
                    if (pendingEntries.isNotEmpty()) {
                        // 同一个 state 喂给拦截器和横向行：判断「到没到边」和真正滑动必须是同一份
                        val pendingScroll = rememberScrollState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // blockHorizontalAtEdge 必须先挂（更外层），否则边界余量收不到
                                .blockHorizontalAtEdge(pendingScroll)
                                .horizontalScroll(pendingScroll)
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            pendingEntries.forEach { entry ->
                                // relPath 在这次入队里唯一：列表中途插删时，缩略图的滑动位置和
                                // 解码缓存不会被旁边的项顶掉
                                key(entry.pending.relPath) {
                                    if (entry.isImage) {
                                        PendingImageThumb(
                                            relPath = entry.pending.relPath,
                                            root = imageRoot,
                                            onClick = { viewer = ViewerTarget(listOf(entry.pending.relPath), 0) },
                                            onRemove = { viewModel.detachImage(entry.index) }
                                        )
                                    } else {
                                        PendingFileChip(
                                            name = entry.pending.name,
                                            onRemove = { viewModel.detachFile(entry.index) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 清单条：模型用 todo_write 写过清单之后显示一行进度，点开看全表（只读）
                    // 收起后清单内容一变（新一轮又写了表）就自动展开：否则收起一次就再也看不到进度
                    val todoSignature = viewModel.todos.joinToString("\n") { it.status + " " + it.content }
                    LaunchedEffect(todoSignature) { todoBarHidden = false }
                    if (viewModel.todos.isNotEmpty() && !todoBarHidden) {
                        val doneCount = viewModel.todos.count { it.status == TodoStore.COMPLETED }
                        val currentTodo = viewModel.todos.firstOrNull { it.status == TodoStore.IN_PROGRESS }
                            ?: viewModel.todos.lastOrNull { it.status == TodoStore.PENDING }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { todoDialogVisible = true }
                                .padding(horizontal = 24.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = doneCount.toString() + "/" + viewModel.todos.size + " 完成 · 当前: " +
                                    (currentTodo?.content ?: "全部完成"),
                                modifier = Modifier.weight(1f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (doneCount == viewModel.todos.size) {
                                    Palette.AccentGreen
                                } else {
                                    Palette.AccentBlue
                                }
                            )
                            Text(
                                text = "›",
                                modifier = Modifier.padding(start = 6.dp),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            )
                            // 内层可点会先吃掉这一次点击，整行的「打开全表」不会被误触发
                            Text(
                                text = "×",
                                modifier = Modifier
                                    .clickable { todoBarHidden = true }
                                    .padding(start = 12.dp, end = 2.dp),
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { inputBarHeightPx = it.size.height }
                    ) {
                    ChatComposer(
                        value = viewModel.inputText,
                        onValueChange = { viewModel.updateInput(it) },
                        isStreaming = viewModel.isStreaming,
                        // 只挂了图片/文件、一个字没写也算能发：发送键原来只按文本判可发
                        hasAttachment = viewModel.attachments.isNotEmpty() ||
                            viewModel.pendingImages.isNotEmpty(),
                        planMode = viewModel.planMode,
                        // 目标激活时输入框右侧才长图标；流程结束（complete/pause）后 ViewModel 会把 goal 置成非 ACTIVE，图标自动消失
                        activeGoal = viewModel.goal?.phase == GoalPhase.ACTIVE,
                        skills = viewModel.skillNames,
                        // 技能不走命令枚举：面板把技能名递进来，这里只负责补成 /名字 交给用户继续写
                        onSkillPick = { name -> viewModel.updateInput(TextFieldValue("/" + name)) },
                        busyMode = viewModel.busyMode,
                        permissionLabel = viewModel.permissionLevel.displayName(),
                        permissionLevel = viewModel.permissionLevel,
                        queue = viewModel.pendingQueue.toList(),
                        // 「编辑」= 从队列取出回填输入框：不留在队列里，避免同一句话被发两次
                        onEditQueued = { queued ->
                            viewModel.updateInput(TextFieldValue(queued.text, TextRange(queued.text.length)))
                            viewModel.deleteQueued(queued.id)
                        },
                        onDeleteQueued = { queued -> viewModel.deleteQueued(queued.id) },
                        onSendQueued = { queued -> viewModel.sendQueuedNow(queued.id, scope) },
                        onPlus = {},
                        // 我们没有 @ 会话/文件 的补全列表，「@」先等同「选文件」
                        onMention = { openFilePicker() },
                        onSend = {
                            // 用户主动发送 = 要回到对话底部；否则翻在上面发消息后会停在原地
                            wasAtBottom = true
                            val text = viewModel.inputText.text
                            if (text.isNotBlank() || viewModel.attachments.isNotEmpty() ||
                                viewModel.pendingImages.isNotEmpty()
                            ) {
                                // 空闲 = 直接发；流式中按「繁忙时发送行为」排队或插话（不再等同停止）
                                viewModel.sendWhileBusy(text, scope)
                                viewModel.updateInput(TextFieldValue(""))
                            }
                        },
                        onStop = { viewModel.stop() },
                        // 面板把手交给 Composer：它只用来让父层铺遮罩、不做别的事
                        panelHost = composerPanel,
                        onPermissionPick = { level -> viewModel.setPermission(level) },
                        onComposerCommand = { command ->
                            when (command) {
                                ComposerCommand.ADD_FILE -> openFilePicker()
                                // 目标没有页面了：往输入框里补一条 /goal 模板，由用户接着写内容再发，
                                // 真正解析交给数据层的斜杠命令（和面板里「命令」组插的模板同一个口径，带尾空格）
                                ComposerCommand.ADD_GOAL -> viewModel.updateInput(
                                    TextFieldValue("/goal ", TextRange("/goal ".length))
                                )
                                ComposerCommand.ADD_PLAN -> viewModel.togglePlanMode()
                                ComposerCommand.COMPACT -> viewModel.summarizeNow()
                                ComposerCommand.DOWNLOAD_LOGS -> {
                                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                                    logLauncher.launch("mingent-logs-" + stamp + ".zip")
                                }
                                // 上下文只有「洞察」一个入口了：加号面板进来的也是主页，返回回主页
                                ComposerCommand.CONTEXT -> openContextHub(fromHome = true)
                            }
                        },
                        status = ComposerStatus(
                            // 轮数 / 步数只认 ViewModel 里那份实时状态：stats 里那两个要等进统计页才刷，
                            // 用户看到的就是「发完下一句数字才动」
                            rounds = statusRounds,
                            steps = statusSteps,
                            // 速度、累计 token、缓存命中全部来自本会话的 timing 快照（会落盘，重启不丢）；
                            // 水位不在这行了：「上下文」按钮的浮窗里本来就有百分比
                            tokensPerSec = viewModel.sessionTiming.tokensPerSec,
                            totalTokens = viewModel.sessionTiming.promptTokens +
                                viewModel.sessionTiming.completionTokens,
                            cacheHitPercent = viewModel.sessionTiming.cacheHitPercent
                        ),
                        stats = ComposerStatsInfo(
                            modelMillis = viewModel.sessionTiming.modelMillis,
                            toolMillis = viewModel.sessionTiming.toolMillis,
                            ttftAverageMillis = viewModel.sessionTiming.ttftAverageMillis,
                            tokensPerSec = viewModel.sessionTiming.tokensPerSec,
                            cacheHitTokens = viewModel.sessionTiming.cacheHitTokens,
                            cacheMissTokens = viewModel.sessionTiming.cacheMissTokens,
                            cacheHitPercent = viewModel.sessionTiming.cacheHitPercent,
                            outputTokens = viewModel.sessionTiming.completionTokens
                        ),
                        usage = ContextUsageInfo(
                            used = viewModel.contextSnapshot.estimatedTokens,
                            window = viewModel.contextSnapshot.window,
                            systemTokens = viewModel.contextBreakdown.systemTokens,
                            toolTokens = viewModel.contextBreakdown.toolTokens,
                            messageTokens = viewModel.contextBreakdown.messageTokens
                        ),
                        onOpenContextPage = { openContextHub(fromHome = true) },
                        // 当前模型显示名：内置 DeepSeek 的两个别名之外（自定义 provider）就显示 id 本身
                        modelLabel = modelDisplayName(viewModel.model),
                        // 强度也一律显示中文：一级窗里给用户看的是「高／最高」，接口取值只在数据层出现
                        effortLabel = if (viewModel.thinkingEnabled) effortDisplayName(viewModel.thinkingEffort) else "Off",
                        // 二级窗就地展开：选完即生效并收起，不再离开对话去设置页
                        quick = QuickSheetState(
                            providers = viewModel.providers.map { QuickProvider(it.id, it.name, it.models) },
                            currentProviderId = viewModel.currentProviderId,
                            currentModelId = viewModel.model,
                            effort = viewModel.thinkingEffort,
                            thinkingEnabled = viewModel.thinkingEnabled,
                            onPickModel = { providerId, modelId ->
                                // 先切服务商再切模型：切服务商会把模型重置成它上次用的那个，
                                // 顺序反过来就会被这一步覆盖掉，用户点了像没生效
                                if (providerId.isNotBlank() && providerId != viewModel.currentProviderId) {
                                    viewModel.selectProvider(providerId)
                                }
                                viewModel.applyModel(modelId)
                            },
                            onPickEffort = { key ->
                                if (key == EFFORT_OFF) {
                                    if (viewModel.thinkingEnabled) viewModel.toggleThinking()
                                } else {
                                    // 选具体档位本身就意味着「要思考」：从「关」直接点「最高」应该是开+最高，
                                    // 而不是把档位存进去却仍然关着
                                    if (!viewModel.thinkingEnabled) viewModel.toggleThinking()
                                    viewModel.chooseThinkingEffort(key)
                                }
                            }
                        )
                    )
                    }
                }
            }

        // 子任务面板（全屏页之下的一层浮层，层序与拆分前一致）
        ChatSubAgentPanel(
            ui = ui,
            viewModel = viewModel,
            subAgentEntries = subAgentEntries
        )

        // 设置 / 工作区 / 会话列表与设置下的所有页面都是独立全屏页，每页各一层 AnimatedVisibility：
        // 下层页原地留着，中间不会闪出一帧主页；页面自身不透明，所以只做横向滑动。
        AnimatedVisibility(
            // 所有设置层页面也要算进这一层：滑入时设置首页必须原地留着，否则底下会露出主页
            visible = page == AppPage.SETTINGS || page == AppPage.SETTINGS_DATA ||
                page == AppPage.MODEL ||
                page == AppPage.PERMISSION || page == AppPage.PACKAGE || page == AppPage.LOGS ||
                page == AppPage.SCHEDULE || page == AppPage.PERMISSION_TIER ||
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
                onContextInsight = { openContextHub(fromHome = false) },
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
                onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
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

        // 通用设置层同理：权限 / 权限档 / 定时任务三个三级页滑入时，这一层必须原地留着
        AnimatedVisibility(
            visible = page == AppPage.GENERAL || page == AppPage.PERMISSION ||
                page == AppPage.PERMISSION_TIER || page == AppPage.SCHEDULE,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            GeneralSettingsPage(
                onBack = { page = AppPage.SETTINGS },
                permissionLabel = viewModel.permissionLevel.displayName(),
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
            ContextHubPage(
                onBack = { page = if (contextHubFromHome) null else AppPage.SETTINGS },
                insight = ContextInsight(
                    ratio = viewModel.contextSnapshot.ratio,
                    estimatedTokens = viewModel.contextSnapshot.estimatedTokens,
                    window = viewModel.contextSnapshot.window,
                    coveredMessages = viewModel.contextSnapshot.coveredMessages,
                    totalMessages = viewModel.contextSnapshot.totalMessages,
                    // 三个分项与三段估算是同一份口径（contextBreakdown），别各自再估一遍
                    systemTokens = viewModel.contextBreakdown.systemTokens,
                    summaryTokens = viewModel.contextBreakdown.summaryTokens,
                    toolTokens = viewModel.contextBreakdown.toolTokens,
                    userTokens = viewModel.contextBreakdown.userTokens,
                    assistantTokens = viewModel.contextBreakdown.assistantTokens,
                    toolResultTokens = viewModel.contextBreakdown.toolResultTokens,
                    // 会话 / 今日 / 全局三段都在 stats 里按同一口径汇总好了，直接搬
                    sessionPromptTokens = viewModel.stats.totalPromptTokens,
                    sessionCompletionTokens = viewModel.stats.totalCompletionTokens,
                    sessionRequests = viewModel.stats.totalRequests,
                    todayPrompt = viewModel.stats.todayPromptTokens,
                    todayCompletion = viewModel.stats.todayCompletionTokens,
                    todayRequests = viewModel.stats.todayRequests,
                    globalPromptTokens = viewModel.stats.globalPromptTokens,
                    globalCompletionTokens = viewModel.stats.globalCompletionTokens,
                    globalRequests = viewModel.stats.globalRequests,
                    // 活跃时长 = 本会话首末消息的时间差（对话实际占用的时间）；模型 / 工具耗时另有时间账
                    activeMillis = viewModel.statusActiveMillis,
                    modelMillis = viewModel.sessionTiming.modelMillis,
                    toolMillis = viewModel.sessionTiming.toolMillis,
                    outputTokens = viewModel.sessionTiming.completionTokens,
                    // 趋势是最近几次回复的输入 tokens（refreshInsight 里算好），页面只负责画
                    trend = viewModel.insightTrend,
                    rounds = viewModel.statusRounds,
                    steps = viewModel.statusSteps,
                    lastTurnModelMillis = viewModel.sessionTiming.lastTurnModelMillis,
                    lastTurnOutputTokens = viewModel.sessionTiming.lastTurnOutputTokens,
                    sessionFirstAt = viewModel.messages.firstOrNull()?.createdAt ?: 0L,
                    firstUsedDate = viewModel.stats.firstUsedDate,
                    // 总账是进程内单例，进页面时读一次盘拿最后使用时刻
                    lastUsedAt = lastLedger.lastUsedAt
                ),
                onRefresh = viewModel::refreshInsight
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

        // 权限档从通用设置进来，返回回到上一级；会话列表才是抽屉的一级页
        AnimatedVisibility(
            visible = page == AppPage.PERMISSION_TIER,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            PermissionTierPage(
                current = viewModel.permissionLevel,
                onPick = { level -> viewModel.setPermission(level) },
                onBack = { page = AppPage.GENERAL }
            )
        }

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

        // 压在页面层之上的浮层：本轮用量 / 图片长按菜单 / 选择文本 / 图片查看器
        ChatOverlays(
            viewModel = viewModel,
            ui = ui,
            context = context,
            scope = scope,
            imageRoot = imageRoot,
            subAgentEntries = subAgentEntries
        )
            }
            }
        }
        }
    }

    // 六个模态框：新建工作区 / 导出选项 / 导入确认 / 导入结果 / 清单全表 / 回滚确认
    ChatDialogs(
        viewModel = viewModel,
        ui = ui,
        context = context,
        scope = scope,
        onExportLaunch = { name -> exportLauncher.launch(name) }
    )
}
