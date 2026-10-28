package com.zmf.agent.ui.chat
import com.zmf.agent.ui.common.BoundedScrollColumn
import com.zmf.agent.ui.common.blockTapsOnly

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
import com.zmf.agent.agent.ApiConfig
import com.zmf.agent.agent.goal.GoalPhase
import com.zmf.agent.agent.session.PlanDoc
import com.zmf.agent.agent.session.TodoStore
import com.zmf.agent.agent.subagent.SubAgentRegistry
import com.zmf.agent.agent.workspace.WorkSpace
import com.zmf.agent.agent.workspace.WorkSpaceStore
import com.zmf.agent.core.api.BalanceClient
import com.zmf.agent.core.api.BalanceResult
import com.zmf.agent.core.backup.BackupArchive
import com.zmf.agent.core.backup.BackupOptions
import com.zmf.agent.core.capability.ShizukuBridge
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.service.FloatingBallService
import com.zmf.agent.tools.displayName
import com.zmf.agent.ui.float.ChatBridge
import com.zmf.agent.ui.settings.AboutPage
import com.zmf.agent.ui.settings.CreateWorkspaceDialog
import com.zmf.agent.ui.settings.LogsPage
import com.zmf.agent.ui.settings.ArchivePage
import com.zmf.agent.ui.settings.TrashPage
import com.zmf.agent.ui.settings.PackagePage
import com.zmf.agent.ui.settings.PermissionPage
import com.zmf.agent.ui.settings.SchedulePage
import com.zmf.agent.ui.settings.ContextConfigPage
import com.zmf.agent.ui.settings.ContextHubPage
import com.zmf.agent.ui.settings.ContextInsight
import com.zmf.agent.ui.settings.MemoryPage
import com.zmf.agent.ui.settings.DataSettingsPage
import com.zmf.agent.ui.settings.GeneralSettingsPage
import com.zmf.agent.ui.settings.ModelSettingsPage
import com.zmf.agent.ui.settings.SettingsHomePage
import com.zmf.agent.agent.stats.UsageLedger
import com.zmf.agent.agent.prompt.PromptStore
import com.zmf.agent.ui.chat.ChatComposer
import com.zmf.agent.ui.chat.ComposerCommand
import com.zmf.agent.ui.chat.ComposerStatus
import com.zmf.agent.ui.chat.ContextUsageInfo
import com.zmf.agent.ui.chat.TracePage
import com.zmf.agent.ui.shell.AppDrawerContent
import com.zmf.agent.ui.shell.SaltDrawer
import com.zmf.agent.ui.shell.SaltFullPane
import com.zmf.agent.ui.shell.SaltSwitch
import com.zmf.agent.ui.theme.Motion
import com.zmf.agent.ui.theme.Palette
import com.zmf.agent.ui.theme.ThemeMode
import com.zmf.agent.ui.theme.UiScaleStore
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
 * 「本轮用量」伴随面板的入参：[anchor] 是那枚用量按钮在窗口里的位置，[usage] 是这一轮的账。
 *
 * 两者必须一起传：面板上的数字与「贴着哪一枚按钮」是同一次点击的产物，分开存会错位。
 */
private data class TurnUsageTarget(val usage: TurnUsageInfo, val anchor: Offset)

/**
 * 长按已发送图片时的小窗：只这两件事：存下这一张、回到这条提问。
 * 带上 relPath 是因为「长按哪张就保存哪张」，不能拿整条的图片列表糊过去。
 */
private data class ImageMenuTarget(
    val relPath: String,
    /** 这条用户消息在整个列表里的下标，回滚按它定位。 */
    val userIndex: Int,
    val anchor: Offset
)

/**
 * 图片详情的入参：这条消息的全部图片（缩略图顺序 = 选择/发送顺序）+ 被点开的那张下标。
 *
 * 一起传而不是只传一张：详情里要能左右切换查看，只给一张就切不了。
 */
private data class ViewerTarget(val paths: List<String>, val startIndex: Int)

/** 选择文本页要展示的内容。 */
private data class SelectionTarget(
    val title: String,
    val text: String,
    val reasoning: String
)

/**
 * 独立全屏页面：抽屉直接进的一级页（设置 / 工作区 / 会话列表），以及设置首页下的各功能页。
 */
private enum class AppPage {
    SETTINGS, WORKSPACE, SETTINGS_DATA, SESSIONS,
    PERMISSION_TIER,
    MODEL, PERMISSION, PACKAGE, LOGS, SCHEDULE, SUBAGENT, ARCHIVE, TRASH,
    // 设置首页（SETTINGS）→ 通用设置（GENERAL）/ 模型（MODEL）/ 长期记忆（MEMORY）/
    // 上下文洞察（CONTEXT_HUB）/ 上下文配置（CONTEXT_CONFIG）/ 包管理（PACKAGE）/ 数据（SETTINGS_DATA）；
    // 轨迹是由主页左滑进入的全屏页，不属于设置层。
    // 统计 / 提示词 / 思考三页已并进「上下文洞察」与「模型」：同一件事在两处入口只会让人怀疑哪个是真的。
    GENERAL, MEMORY, CONTEXT_HUB, CONTEXT_CONFIG, TRACE,
    // 关于是只读页（版本 / 开源地址 / 第三方组件），从设置首页最底部的卡片进
    ABOUT,
    // 计划是主页右上角那枚按钮进的独立页（不挂在设置层下），返回即回主页。
    // 目标没有预览页：/goal 走命令解析，状态只看输入框右侧那枚只读图标
    PLAN,
    // 内置浏览器：回复里的链接点开进这里。返回（侧滑/返回键）只回主页，**页面不关**：
    // 顶栏会多出一枚网页图标，点回来接着用（页面实例留在 BrowserHolder 里，登录态不丢）。
    BROWSER
}

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
    val viewModel = remember { ChatViewModel(context) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var page by remember { mutableStateOf<AppPage?>(null) }
    // 回收站页返回时自增：数据页一直挂在这层下面，不换 key 那行统计就不会刷新
    var trashRefreshKey by remember { mutableStateOf(0) }
    var drawerOpen by remember { mutableStateOf(false) }
    // 上下文洞察页是从哪进来的：主页输入框那条路要退回主页，设置首页那条要退回设置。
    // 只记一个来源就够，不必为每个二级页都备一套返回栈（页面上只有它有两个入口）。
    var contextHubFromHome by remember { mutableStateOf(false) }
    // 抽屉里的「＋ 新建工作区」：抽屉没有输入框，弹一个极简输入框
    var showCreateWorkspace by remember { mutableStateOf(false) }
    // 轨迹页固定显示代码块，不受「显示工作代码视图」开关影响
    val traceView = true
    // 输入框弹出面板的「开着没 / 关掉」把手：面板状态仍归 Composer，父层只借它铺遮罩
    val composerPanel = remember { ComposerPanelHost() }
    // 包管理 / 会话列表这两页的内容区是 HorizontalPager：只有它停在第一页时，
    // 抽屉才敢在 Initial pass 抢走向右的位移（否则从第二页往右翻页就翻不动了）
    var pagerAtFirstPage by remember { mutableStateOf(true) }
    var extraPrompt by remember { mutableStateOf(PromptStore.loadExtra(context)) }
    var overridePrompt by remember { mutableStateOf(PromptStore.loadOverride(context).orEmpty()) }
    // 上下文页要的全局总账快照：进页面时读一次盘（UsageLedger.load 自带「只读一次」的闸门）
    val lastLedger = remember(page) {
        UsageLedger.load(context)
        UsageLedger.snapshot()
    }
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    // 输入栏实测高度：附件面板要浮在它上面，写死 dp 会跟字号档位对不上
    var inputBarHeightPx by remember { mutableStateOf(0) }
    var topBarHeightPx by remember { mutableStateOf(0) }
    // 图片详情：这条消息的全部图片 + 起点下标（详情里按缩略图顺序左右切换）
    var viewer by remember { mutableStateOf<ViewerTarget?>(null) }

    // 子任务面板：Registry 是内存 object（子智能体在 IO 协程里写）。
    // 以前这里是 300ms 轮询：空闲、一个子智能体都没有时也在每 300ms 醒一次并分配一份快照。
    // 现在改成订阅版本号：没有子智能体在跑时，那条 Flow 一条都不会发，主线程一次都不会被叫醒。
    var subAgentPanelOpen by remember { mutableStateOf(false) }
    var subAgentDetailId by remember { mutableStateOf<String?>(null) }
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
    var showExportDialog by remember { mutableStateOf(false) }
    var includeTerminal by remember { mutableStateOf(false) }
    var includeLogs by remember { mutableStateOf(false) }
    var includeMemory by remember { mutableStateOf(false) }
    // Ubuntu 环境段的真实体积（字节）：0 表示还没量出来（弹窗刚开、盘上文件多时要走一两秒）
    var terminalBytes by remember { mutableStateOf(0L) }
    // 每次打开弹窗都重量一次：用户可能在两次导出之间用 apt 装了不少东西
    LaunchedEffect(showExportDialog) {
        if (showExportDialog) {
            terminalBytes = withContext(Dispatchers.IO) { BackupArchive.terminalSectionBytes(context) }
        }
    }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    // 「清理未引用附件」的结果：只在这一层显示，页面关掉就没了
    var orphanStatus by remember { mutableStateOf<String?>(null) }

    // 余额是「查了才有」的临时结果，不落盘：只在模型页显示
    var balance by remember { mutableStateOf<BalanceResult?>(null) }
    var balanceBusy by remember { mutableStateOf(false) }

    // 分叉会话的「瞬灭再加载」：分叉完屏幕内容要闪一下，
    // 否则复制整条会话时画面纹丝不动，看不出已经分叉成功。
    var forkReloading by remember { mutableStateOf(false) }
    val forkReloadAlpha by animateFloatAsState(
        targetValue = if (forkReloading) 1f else 0f,
        animationSpec = tween(durationMillis = if (forkReloading) 70 else 140),
        label = "forkReload"
    )
    // 所有「分叉」入口都走它：遮罩盖住 → 真正切会话 → 淡出，露出重新加载后的内容
    val forkWithReload: (() -> Unit) -> Unit = { action ->
        scope.launch {
            forkReloading = true
            // 等遮罩盖实了再换数据，避免用户看见中间态
            delay(80)
            action()
            delay(110)
            forkReloading = false
        }
    }
    // 「本轮用量」伴随面板：贴着被点的那枚用量按钮弹出，点面板以外任何地方收掉
    var turnUsage by remember { mutableStateOf<TurnUsageTarget?>(null) }
    var selectionTarget by remember { mutableStateOf<SelectionTarget?>(null) }
    var selectionVisible by remember { mutableStateOf(false) }
    // 图片长按小窗：内容与可见性分开存，收起那 140ms 里内容还得留在场上播淡出
    var imageMenuTarget by remember { mutableStateOf<ImageMenuTarget?>(null) }
    var imageMenuVisible by remember { mutableStateOf(false) }
    var rollbackUserIndex by remember { mutableStateOf<Int?>(null) }
    // 清单条点开后的全表弹窗（只读）
    var todoDialogVisible by remember { mutableStateOf(false) }
    // 清单条是否被用户收起：只影响这一屏，不动盘上的清单
    var todoBarHidden by remember { mutableStateOf(false) }

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
    var wasAtBottom by remember { mutableStateOf(true) }
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
                        context.startActivity(Intent(context, com.zmf.agent.TerminalActivity::class.java))
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

        // 子任务面板：透明遮罩铺满整屏先吃掉「点外部」的点击，卡片浮在它上面。
        // 只读 SubAgentRegistry 的快照，不碰 ViewModel 的任何状态。
        if (subAgentPanelOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { subAgentPanelOpen = false }
            )
            SubAgentPanel(
                entries = subAgentEntries,
                onOpen = { id ->
                    subAgentPanelOpen = false
                    subAgentDetailId = id
                    page = AppPage.SUBAGENT
                },
                // 清完不用手动刷新：clearFinished 会 +1 版本号，上面的订阅自己把新快照取回来
                onClearFinished = { SubAgentRegistry.clearFinished() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = with(LocalDensity.current) { topBarHeightPx.toDp() }, end = 12.dp)
            )
        }

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
                // 模型候选跟着当前 provider 走：内置 DeepSeek 才用 ApiConfig.MODELS 的显示名
                models = viewModel.providers
                    .firstOrNull { it.id == viewModel.currentProviderId }
                    ?.models
                    .orEmpty()
                    .map { id ->
                        id to (ApiConfig.MODELS.firstOrNull { it.first == id }?.second ?: id)
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
                            balance = BalanceClient.fetchBalance(context, "", "")
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

            // 「本轮用量」伴随面板：贴着被点的用量按钮弹出，点面板以外任何地方关闭。
            // 不用弹窗（Dialog）：那会把视线从「这条回复花了多少」拉到屏幕中央，也不跟按钮走。
            turnUsage?.let { target ->
                SaltPopover(
                    anchor = target.anchor,
                    // 宽度取「屏宽减两侧留白」的量级：用量面板要装下「提供方 / 模型」这种长值，
                    // 太窄会贴着气泡右缘被看成一截
                    widthDp = 300.dp,
                    onDismiss = { turnUsage = null }
                ) {
                    TurnUsagePanel(usage = target.usage)
                }
            }

            // 长按已发送图片：只有「存这一张」和「回滚到这条提问」两项。
            // 和上面那套文字菜单分开，是因为图片没有可复制的正文；锚点是按住的那一张的坐标。
            AnimatedVisibility(
                visible = imageMenuVisible,
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(140)),
                modifier = Modifier.fillMaxSize()
            ) {
                imageMenuTarget?.let { target ->
                    val imageActions = listOf(
                        MenuAction("保存图片") {
                            imageMenuVisible = false
                            saveImageFromChat(context, scope, imageRoot, target.relPath)
                        },
                        MenuAction("回滚到此处") {
                            rollbackUserIndex = target.userIndex
                            imageMenuVisible = false
                        }
                    )
                    MessageContextMenu(
                        anchor = target.anchor,
                        actions = imageActions,
                        onDismiss = { imageMenuVisible = false }
                    )
                }
            }

            // 选择文本整页：从下方弹出，可下滑关闭
            AnimatedVisibility(
                visible = selectionVisible,
                enter = fadeIn(Motion.fadeIn()) + slideInVertically(Motion.spring()) { it / 4 },
                // 退场由 TextSelectionOverlay 自己播完再回调，这里不能再叠一层淡出
                exit = ExitTransition.None
            ) {
                selectionTarget?.let { target ->
                    TextSelectionOverlay(
                        title = target.title,
                        text = target.text,
                        reasoning = target.reasoning,
                        onDismiss = { selectionVisible = false }
                    )
                }
            }
            // 图片查看器压在所有页面之上：整条消息的图片一起传进去，详情里才能按缩略图顺序左右切
            viewer?.let { target ->
                ImageViewer(
                    paths = target.paths,
                    startIndex = target.startIndex,
                    root = imageRoot,
                    onDismiss = { viewer = null }
                )
            }
            }
            }
        }
        }
    }

    if (showCreateWorkspace) {
        CreateWorkspaceDialog(
            onDismiss = { showCreateWorkspace = false },
            onCreate = { name, rootPath, treeUri ->
                showCreateWorkspace = false
                // createWorkspace 内部已建好一条空会话并切过去，这里只收抽屉、回主页
                val created = viewModel.createWorkspace(name, rootPath, treeUri)
                if (created) {
                    drawerOpen = false
                    page = null
                } else {
                    // 目录不可用（未授权 / 已被删 / 不可写）：说一句，别让人以为建好了
                    Toast.makeText(context, "这个目录不可用，换一个再试", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showExportDialog) {
        MinimalDialog(onDismiss = { showExportDialog = false }) {
            Text(
                text = "导出用户数据",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            BackupToggleRow(
                title = "包含 Ubuntu 环境",
                // 体积跟着盘上装了多少走：量出来之前只说「会久一点」，不写死常量
                hint = if (terminalBytes > 0L) "当前约 " + exportSizeText(terminalBytes) + "，导出会久一点"
                else "导出会久一点",
                checked = includeTerminal,
                onToggle = { includeTerminal = !includeTerminal }
            )
            BackupToggleRow(
                title = "包含日志",
                hint = "排查问题用，体积很小",
                checked = includeLogs,
                onToggle = { includeLogs = !includeLogs }
            )
            BackupToggleRow(
                title = "包含记忆数据",
                hint = "记忆事件与索引，不含内核程序",
                checked = includeMemory,
                onToggle = { includeMemory = !includeMemory }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "会话、工作区文件、模型与权限设置默认打包；记忆数据需单独勾选；API Key 不会写进备份。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = "取消",
                    modifier = Modifier
                        .clickable { showExportDialog = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "导出",
                    modifier = Modifier
                        .clickable {
                            showExportDialog = false
                            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                                .format(java.util.Date())
                            exportLauncher.launch("mingent-backup-" + stamp + ".zip")
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.AccentGreen
                )
            }
        }
    }

    // 导入前的确认：把清单摊开给用户看，避免手滑覆盖
    viewModel.pendingManifest?.let { manifest ->
        MinimalDialog(onDismiss = { viewModel.cancelImport() }) {
            Text(
                text = "确认导入",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "来自 " + manifest.applicationId + " · v" + manifest.versionName,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = "包含内容：" + manifest.sections.joinToString("、"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "导入将覆盖当前数据且不可撤销" +
                    (if (manifest.sections.contains("terminal")) "；备份包含 Ubuntu 环境，请先退出终端页面" else "") +
                    (if (manifest.sections.contains("memory")) "；记忆数据将被整体替换" else "") +
                    "。API 密钥保留本机原有设置，不受覆盖影响。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = "取消",
                    modifier = Modifier
                        .clickable { viewModel.cancelImport() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "确认导入",
                    modifier = Modifier
                        .clickable {
                            val target = importUri
                            if (target != null) viewModel.importData(target, scope)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.AccentGreen
                )
            }
        }
    }

    // 导入结果：成功/跳过/失败逐条列出来，不静默吞错
    viewModel.importReport?.let { report ->
        MinimalDialog(onDismiss = { viewModel.dismissImportReport() }) {
            Text(
                text = "导入完成",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            if (report.restored.isNotEmpty()) {
                Text(
                    text = "以下内容已恢复：" + report.restored.joinToString("、"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            if (report.skipped.isNotEmpty()) {
                Text(
                    text = "以下条目已跳过：" + report.skipped.joinToString("、"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            if (report.failed.isNotEmpty()) {
                Text(
                    text = "以下条目未能恢复：" + report.failed.joinToString("、"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "重启应用后全部生效。",
                fontSize = 12.sp,
                color = Palette.AccentGreen
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = "确定",
                    modifier = Modifier
                        .clickable { viewModel.dismissImportReport() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    color = Palette.AccentGreen
                )
            }
        }
    }

    // 清单全表：只读。清单是模型写的，这里不提供勾选（手改会和下一轮写入打架）
    if (todoDialogVisible) {
        MinimalDialog(onDismiss = { todoDialogVisible = false }) {
            Text(
                text = "任务清单",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                viewModel.todos.forEach { todo ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            text = when (todo.status) {
                                TodoStore.COMPLETED -> "✓"
                                TodoStore.IN_PROGRESS -> "▶"
                                else -> "○"
                            },
                            fontSize = 13.sp,
                            color = if (todo.status == TodoStore.COMPLETED) {
                                Palette.AccentGreen
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = todo.content,
                            fontSize = 13.sp,
                            color = if (todo.status == TodoStore.COMPLETED) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        }
    }

    rollbackUserIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { rollbackUserIndex = null },
            title = { Text("回滚到此处", fontSize = 17.sp) },
            text = {
                Text(
                    text = "这条提问以及它之后的所有对话都会被删除，不能撤销。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rollbackTo(index)
                    rollbackUserIndex = null
                }) { Text("删除并回滚", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { rollbackUserIndex = null }) { Text("取消") }
            }
        )
    }

}
/** 顶部栏：只有 ☰、右上角的子任务胶囊和状态点；终端与设置都在侧边栏里。 */
@Composable
private fun ChatTopBar(
    viewModel: ChatViewModel,
    onMenuClick: () -> Unit,
    /** 子智能体总数：0 时右上角不出现胶囊（跑完也留着，方便回看对话）。 */
    subAgentCount: Int,
    /** 其中正在运行的数量，只用来决定指示灯颜色。 */
    subAgentRunning: Int,
    onSubAgentClick: () -> Unit,
    /** 当前会话已产出的计划：一份都没有时右上角那枚按钮连位置都不留。 */
    plans: List<PlanDoc>,
    onPlanClick: () -> Unit,
    /** 内置浏览器里有没有活着的页面：有才露出那枚网页图标。 */
    browserOpen: Boolean,
    onBrowserClick: () -> Unit
) {
    // 状态点四态：请求中蓝 / 出错红 / 就绪青 / 空闲绿
    val shizukuState by ShizukuBridge.state.collectAsState()
    val dotColor = when {
        viewModel.isStreaming -> Palette.StatusBusy
        !viewModel.networkAvailable -> MaterialTheme.colorScheme.error
        viewModel.lastRequestOk == false -> MaterialTheme.colorScheme.error
        shizukuState == ShizukuBridge.State.READY -> Palette.AccentCyan
        else -> Palette.StatusIdle
    }
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "☰",
            modifier = Modifier.clickable(onClick = onMenuClick).padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        // 中间不再放「Agent」标题：两侧 Spacer 撑着，☰ 在左、状态点在右
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.weight(1f))
        // 子智能体胶囊：只要还有记录就留着，点了展开卡片（状态点保持最右）
        if (subAgentCount > 0) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSubAgentClick() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (subAgentRunning > 0) Palette.StatusBusy else Palette.StatusIdle,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = subAgentCount.toString() + " 个子智能体",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        // 网页图标：只有存在活着的页面时才出现。它出现在「从浏览页返回主页之后」，
        // 语义是「网页还开着，点我回去」，所以画的是窗口而不是链接
        if (browserOpen) {
            BrowserGlyph(onClick = onBrowserClick)
            Spacer(Modifier.width(10.dp))
        }
        // 计划按钮：只有真的存在计划预览时才出现。没有计划还挂一枚淡色图标，
        // 等于在教用户「这里点不开」，不如让位给状态点
        if (plans.isNotEmpty()) {
            PlanGlyph(onClick = onPlanClick)
            Spacer(Modifier.width(10.dp))
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
    }
}

/**
 * 右上角的「计划」按钮：一张手画的清单图标（外框 + 三条横线）。
 *
 * 为什么用 Canvas 而不是 emoji/文字：这一排只有状态点和一个胶囊，混进一个字符图标会显得字号不齐；
 * 手画的和抽屉里那几个图形是同一套语言。
 */
@Composable
private fun PlanGlyph(onClick: () -> Unit) {
    // 只在这一份计划真的存在时画，所以永远是「有内容」的绿，不再有淡色的空态
    val color = Palette.AccentGreen
    val strokeWidth = with(LocalDensity.current) { 1.5.dp.toPx() }
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(17.dp)) {
            val w = size.width
            val h = size.height
            val corner = CornerRadius(2.5.dp.toPx())
            drawRoundRect(
                color = color,
                topLeft = Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
                size = Size(w - 1.dp.toPx(), h - 1.dp.toPx()),
                cornerRadius = corner,
                style = Stroke(width = strokeWidth)
            )
            // 三条横线：间距 3.5dp，长度递减，读起来像一份条目清单
            for (index in 0 until 3) {
                val y = h * 0.32f + index * 3.6.dp.toPx()
                val right = w - if (index == 2) 5.dp.toPx() else 3.5.dp.toPx()
                drawLine(
                    color = color,
                    start = Offset(3.5.dp.toPx(), y),
                    end = Offset(right, y),
                    strokeWidth = strokeWidth
                )
            }
        }
    }
}

/**
 * 右上角的「网页」图标：一个小窗口（外框 + 顶部工具栏 + 一颗小圆点）。
 *
 * 只在浏览器里真有页面时出现。用户从浏览页返回主页之后，这枚图标就是「网页还开着，点我回去」
 * 的唯一入口，所以画成窗口而不是链接符号；和 PlanGlyph 同一套线宽，排在一行里不打架。
 */
@Composable
private fun BrowserGlyph(onClick: () -> Unit) {
    val color = Palette.Neutral
    val strokeWidth = with(LocalDensity.current) { 1.5.dp.toPx() }
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(17.dp)) {
            val w = size.width
            val h = size.height
            val half = 0.5.dp.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(half, half),
                size = Size(w - half * 2, h - half * 2),
                cornerRadius = CornerRadius(2.5.dp.toPx()),
                style = Stroke(width = strokeWidth)
            )
            // 工具栏分隔线：偏上一点，下面留出「网页内容」的面积
            val barY = h * 0.34f
            drawLine(
                color = color,
                start = Offset(half, barY),
                end = Offset(w - half, barY),
                strokeWidth = strokeWidth
            )
            // 工具栏左侧那颗小圆点（标签页的示意），别画多了：这么小的地方只放得下一颗
            drawCircle(color = color, radius = 1.dp.toPx(), center = Offset(w * 0.2f, barY * 0.52f))
        }
    }
}

/** 子任务面板最多显示几条。 */
private const val SUB_AGENT_PANEL_ROWS = 5

/**
 * 右上角「子智能体」下拉卡片：只读 [SubAgentRegistry] 的快照，和 ViewModel 无关。
 *
 * 每条：描述 + 状态；点一条进详情页（对话与续聊都在那里）。
 * 卡片自己也接点击，避免点空白处透到底下「点外部收起」的遮罩上。
 */
@Composable
private fun SubAgentPanel(
    entries: List<SubAgentRegistry.Entry>,
    onOpen: (String) -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 新的在前，最多 5 条
    val shown = entries.takeLast(SUB_AGENT_PANEL_ROWS).asReversed()
    Surface(
        modifier = modifier
            .widthIn(min = 200.dp, max = 280.dp)
            .pointerInput(Unit) { detectTapGestures { } },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, if (isDark) Palette.DarkStroke else Palette.LightStroke)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            if (shown.isEmpty()) {
                Text(
                    text = "暂无子任务",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
            }
            shown.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onOpen(entry.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.description,
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = onSurface.copy(alpha = 0.85f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = subAgentStateText(entry.state),
                        fontSize = 11.sp,
                        color = subAgentStateColor(entry.state)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "›",
                        fontSize = 13.sp,
                        color = onSurface.copy(alpha = 0.35f)
                    )
                }
            }
            // 底部操作：没有已完成的时候位置也留着，只是点不动（选项不跳位置）
            val hasFinished = entries.any { it.state != SubAgentRegistry.RUNNING }
            Text(
                text = "清空已完成",
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (hasFinished) 1f else 0.35f)
                    .clickable(enabled = hasFinished, onClick = onClearFinished)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 11.sp,
                color = onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

/** 子智能体状态的显示文案：运行中 / 完成 / 失败。 */
private fun subAgentStateText(state: String): String = when (state) {
    SubAgentRegistry.RUNNING -> "运行中"
    SubAgentRegistry.DONE -> "完成"
    else -> "失败"
}

/** 面板里的状态颜色，和工具卡口径一致：运行紫 / 完成绿 / 失败红。 */
@Composable
private fun subAgentStateColor(state: String): Color = when (state) {
    SubAgentRegistry.RUNNING -> Palette.AccentPurple
    SubAgentRegistry.DONE -> Palette.AccentGreen
    else -> MaterialTheme.colorScheme.error
}

/** 极简弹窗：没有标题、没有按钮，只有可点的行；点外部或返回键关闭。 */
@Composable
private fun MinimalDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                content = content
            )
        }
    }
}

/** 极简名字输入框（通用）：一行输入 + ✓ 保存 / × 取消，点外部取消。 */
@Composable
private fun NameInputDialog(
    placeholder: String,
    initial: String = "",
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(initial) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    MinimalDialog(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text(placeholder, fontSize = 15.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                maxLines = 1,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(value.text) })
            )
            Text(
                text = "✓",
                modifier = Modifier.clickable { onSave(value.text) }.padding(horizontal = 8.dp),
                fontSize = 20.sp,
                color = Palette.AccentBlue
            )
            Text(
                text = "×",
                modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 6.dp),
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

/**
 * 体积文案：按量级给 KB / MB / GB，MB 以上取整：这里只是让人判断「这包大不大」，
 * 精确到小数点反而读不出差别（同 SentFileCard 的口径，但那一处是单个附件，保留一位小数）。
 */
private fun exportSizeText(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L ->
        "%.1f".format(Locale.US, bytes / 1024.0 / 1024.0 / 1024.0) + "GB"
    bytes >= 1024L * 1024L -> ((bytes + 1024L * 1024L / 2L) / (1024L * 1024L)).toString() + "MB"
    bytes >= 1024L -> ((bytes + 512L) / 1024L).toString() + "KB"
    else -> bytes.toString() + "B"
}

/** 导出选项的一行：左边标题+一句提示，右边 Salt 风开关。 */
@Composable
private fun BackupToggleRow(
    title: String,
    hint: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
        SaltSwitch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** 待发送缩略图统一 64dp 见方：和文件 chip 一样高，同一行里不会一高一矮。 */
private val PendingThumbSide = 64.dp

/** 待发送的一件东西：文件和图片排在同一个行里，所以得有一个统一的表现。 */
private data class PendingEntry(
    val pending: PendingFile,
    val isImage: Boolean,
    /** 在它自己那条队列里的下标：删除走 detachFile / detachImage，按这个口径定位。 */
    val index: Int,
    /** 文件落进工作区的时刻，用来还原「文件与图片谁先加的」。 */
    val stamp: Long
)

/**
 * 把分开存的两条队列拼成一条「按添加顺序」的列表。
 *
 * ViewModel 里 attachments 与 pendingImages 是两个列表，谁先谁后没有记录；
 * 好在两者都是「选完立刻复制/压图进工作区」，所以文件系统的 lastModified 就是添加时刻
 * （图片名里也带这串毫秒，口径一致）。同一时刻（粗粒度文件系统只精确到秒）按「文件在前」兜底，
 * 保证同样的输入每次排出来同一样。
 */
private fun mergePending(
    root: File,
    files: List<PendingFile>,
    images: List<PendingFile>
): List<PendingEntry> {
    val out = ArrayList<PendingEntry>(files.size + images.size)
    files.forEachIndexed { i, p -> out.add(PendingEntry(p, false, i, stampOf(root, p))) }
    images.forEachIndexed { i, p -> out.add(PendingEntry(p, true, i, stampOf(root, p))) }
    out.sortWith(compareBy({ it.stamp }, { if (it.isImage) 1 else 0 }))
    return out
}

/** 取不到（文件已被清掉）就当成最早，至少不会把这一项吞掉。 */
private fun stampOf(root: File, pending: PendingFile): Long =
    runCatching { File(root, pending.relPath).lastModified() }.getOrDefault(0L)

/**
 * 待发送图片的缩略图：固定 64dp 见方、圆角 12dp。
 *
 * 尺寸写死而不是按原图比例：这一行里还混着文件 chip，比例尺寸会让整行高矮不齐；
 * 也不再铺任何白色/半透明白底：之前那层底把手边的文件 chip 盖住，看着像两个层次。
 * 解码只在 bitmaps 为空（文件没了 / 不是图片）时才画一块占位灰，仍能 ✕ 删掉。
 */
@Composable
private fun PendingImageThumb(
    relPath: String,
    root: File,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val bitmap = remember(relPath) {
        runCatching { BitmapFactory.decodeFile(File(root, relPath).absolutePath) }.getOrNull()
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(modifier = Modifier.size(PendingThumbSide)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(12.dp))
                .then(if (bitmap == null) Modifier.background(onSurface.copy(alpha = 0.12f)) else Modifier)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(text = "图片", fontSize = 12.sp, color = onSurface.copy(alpha = 0.5f))
            }
        }
        // 删除键压在右上角：底色只是一层深色半透明，够看清又不抢图片
        Text(
            text = "✕",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onRemove)
                .padding(horizontal = 4.dp, vertical = 1.dp),
            fontSize = 10.sp,
            color = Color.White
        )
    }
}

/** 待发送的文件 chip：名字 + 角上的 ✕，和图片缩略图同一行排。 */
@Composable
private fun PendingFileChip(name: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            modifier = Modifier.widthIn(max = 140.dp),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "✕",
            modifier = Modifier
                .clickable(onClick = onRemove)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

/** 工作区页（一级页面）：未分类 + 自建工作区的列表，可切换 / 新建 / 删除；streaming 时全部点不动。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkSpacePage(
    workspaces: List<WorkSpace>,
    currentId: String?,
    streaming: Boolean,
    onPick: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<WorkSpace?>(null) }

    Surface(
        // 工作区页：铺满不透明，最外层吞点击，避免穿透到下面的聊天界面
        modifier = Modifier
            .fillMaxSize()
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "←",
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "工作区",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .alpha(if (streaming) 0.4f else 1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // 「未分类」不是 WorkSpace，它是 workspaceId == null 的状态：永远排第一，不能删
                WorkSpaceRow(
                    title = "未分类",
                    selected = currentId == null,
                    deletable = false,
                    enabled = !streaming,
                    onPick = { onPick(null) },
                    onAskDelete = {}
                )
                workspaces.forEach { space ->
                    WorkSpaceRow(
                        title = space.name,
                        selected = space.id == currentId,
                        // 默认工作区的目录是全体历史文件的根，不允许删
                        deletable = space.id != WorkSpaceStore.DEFAULT_ID,
                        enabled = !streaming,
                        onPick = { onPick(space.id) },
                        onAskDelete = { pendingDelete = space }
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            HorizontalDivider(color = onSurface.copy(alpha = 0.08f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !streaming) { showCreate = true }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "＋ 新建工作区",
                    fontSize = 15.sp,
                    color = Palette.AccentBlue.copy(alpha = if (streaming) 0.35f else 1f)
                )
            }
        }
    }

    if (showCreate) {
        NameInputDialog(
            placeholder = "工作区名称",
            onDismiss = { showCreate = false },
            onSave = { name ->
                if (name.isNotBlank()) {
                    onCreate(name.trim())
                    showCreate = false
                }
            }
        )
    }

    pendingDelete?.let { space ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除工作区", fontSize = 17.sp) },
            text = {
                Text(
                    text = "删掉后这个工作区里的对话会进入「未分类」，目录会移到回收站（不真删）。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(space.id)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 工作区列表的一行：名字 + 当前高亮；非默认工作区右侧带「×」。 */
@Composable
private fun WorkSpaceRow(
    title: String,
    selected: Boolean,
    deletable: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
    onAskDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onPick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Palette.AccentBlue else MaterialTheme.colorScheme.onSurface
        )
        if (deletable) {
            Text(
                text = "×",
                modifier = Modifier
                    .clickable(enabled = enabled, onClick = onAskDelete)
                    .padding(start = 12.dp, end = 2.dp),
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
        }
    }
}

/**
 * 列表项：用户消息单独一行；一个助手回合 = 到下一条提问之前的全部内容。
 *
 * [UserItem.roundsFromEnd] 是这条提问距离最新一轮的轮次差（最新一条为 0）：
 * 气泡折叠阈值按它分两档，比每次都从后往前数一遍消息省事，也能在列表复用后保持稳定。
 */
private sealed interface ChatItem {
    data class UserItem(
        val index: Int,
        val message: ChatMessage,
        val roundsFromEnd: Int = 0
    ) : ChatItem

    data class AssistantItem(val indices: List<Int>, val userIndex: Int) : ChatItem
}

private fun buildChatItems(all: List<ChatMessage>): List<ChatItem> {
    val out = ArrayList<ChatItem>()
    var i = 0
    var lastUserIndex = -1
    // 先数一遍总轮数：轮次差 = 总数 - 当前序号 - 1，单次遍历即可，不必每条消息再扫一次
    val totalRounds = all.count { it.role == "user" }
    var roundOrdinal = 0
    while (i < all.size) {
        when (all[i].role) {
            "user" -> {
                lastUserIndex = i
                out.add(ChatItem.UserItem(i, all[i], totalRounds - roundOrdinal - 1))
                roundOrdinal++
                i++
            }

            "assistant" -> {
                val indices = ArrayList<Int>()
                while (i < all.size && all[i].role != "user") {
                    if (all[i].role == "assistant") indices.add(i)
                    i++
                }
                if (indices.isNotEmpty()) out.add(ChatItem.AssistantItem(indices, lastUserIndex))
            }

            else -> i++
        }
    }
    return out
}

/** 已发送图片缩略图的边长：恒定值，不管原图多大、长宽比多少，发出去都占同样大的一块。 */
private val SentThumbSide = 96.dp

/** 缩略图与文件卡共用的圆角。 */
private val SentCardShape = RoundedCornerShape(14.dp)

/** 文件卡的固定宽度：名字再长也不撑宽，超出用省略号。 */
private val SentFileCardWidth = 168.dp

/**
 * 一条用户消息 = 竖排两块：上面是附件卡行（文件卡 + 图片缩略图，超出横滑），下面是文字气泡。
 *
 * 卡片在前、文字在后，是照 DeepSeek 手机端的顺序：图片和文件是「随这条消息递过去的东西」，
 * 不是气泡里的内容。没有字就整块不画气泡：纯图片消息不再留一个空白气泡。
 */
@Composable
private fun UserRow(
    message: ChatMessage,
    // 距离最新一轮的轮次差：0 = 最新一条提问。折叠阈值按它分两档
    roundsFromEnd: Int,
    imageRoot: File,
    onCopy: (String) -> Unit,
    onSelectText: (String) -> Unit,
    onRollback: () -> Unit,
    onImageClick: (List<String>, Int) -> Unit,
    onImageLongPress: (String, Offset) -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bubbleColor = if (isDark) Palette.BubbleUserDark else Palette.BubbleUserLight
    val text = userBubbleText(message)
    // 折叠口径：近三轮完全铺开，刚发出去的消息正是用户在读的那条，不该被收起来；
    // 更早的历史按屏幕 45% 折。折叠点落在整段末尾（由 MarkdownText 按块取整），
    // 不会出现「最后一行只剩半个字」。展开态只活在这一屏，列表滚走再回来回到折叠态。
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val foldAt = if (roundsFromEnd < 3) Dp.Unspecified else screenHeight * 0.45f
    // 折叠后最多约九行（15sp × 1.5 行高）：够看清开头，也不至于只剩一条缝
    val foldedHeight = with(LocalDensity.current) { (15f * 1.5f * 9f).sp.toDp() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // 附件从正文那段说明反向解析：发送时「附件在前、图片在后」拼进 prompt，这里保持同一顺序
    val files = remember(message.content) { attachmentPaths(message) }
    val images = message.images
    val bubbleShape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 6.dp
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier.widthIn(max = 340.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (files.isNotEmpty() || images.isNotEmpty()) {
                // 同待发那一行：拦截器和横向行共用一份 state，到边之后的余量就地吃掉
                val rowScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .blockHorizontalAtEdge(rowScroll)
                        .horizontalScroll(rowScroll)
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    files.forEach { rel ->
                        SentFileCard(relPath = rel, root = imageRoot)
                    }
                    images.forEachIndexed { index, rel ->
                        SentImageThumb(
                            relPath = rel,
                            root = imageRoot,
                            onTap = { onImageClick(images, index) },
                            onLongPress = { anchor -> onImageLongPress(rel, anchor) }
                        )
                    }
                }
            }
            if (text.isNotEmpty()) {
                if (files.isNotEmpty() || images.isNotEmpty()) Spacer(Modifier.height(8.dp))
                // 动作按钮摆在气泡尾巴上，不再靠长按弹窗：
                // 用户看到那一行图标就知道能做什么，不用先猜「可以长按」
                // 不用 Surface：它的 shape 会挂一层裁剪图层（graphicsLayer + clip），
                // 气泡展开到十几屏高时这层图层的尺寸也涨到几万像素，那之后的子节点
                // 连点击都收不到（27187px 的气泡实测：按钮看得见、点不动）。
                // background(color, shape) 画出来的圆角与 Surface 一致，且不建图层。
                Box(
                    // 展开 / 收起只动气泡高度：正文按整份排版好再逐步露出，不会重新折行
                    modifier = Modifier
                        .animateContentSize(
                        animationSpec = tween(180),
                        alignment = Alignment.TopStart
                    ).background(bubbleColor, bubbleShape)
                ) {
                    // 不在气泡里开竖向滚动：文字正常铺开，阅读交给整页列表。
                    // 超过 foldAt 时才折叠，折叠点取整段末尾（见 MarkdownText 的按块折叠）。
                    MarkdownText(
                        text = text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        // 用户自己按的换行算他的排版：空行要看得见（发送时已压成一个）
                        keepBlankLines = true,
                        foldAt = foldAt,
                        foldedHeight = foldedHeight,
                        expanded = expanded,
                        foldColor = bubbleColor,
                        foldToggle = {
                            FoldToggle(expanded = expanded, onClick = { expanded = !expanded })
                        }
                    )
                }
            }
            // 尾部动作行：右对齐贴着自己那条气泡，时间在最右
            MessageTailRow(
                at = clockText(message.createdAt),
                alignEnd = true,
                icons = listOf(TailIcon.COPY, TailIcon.SELECT_TEXT, TailIcon.ROLLBACK),
                onAction = { kind ->
                    when (kind) {
                        TailIcon.COPY -> onCopy(text)
                        TailIcon.SELECT_TEXT -> onSelectText(text)
                        TailIcon.ROLLBACK -> onRollback()
                        else -> Unit
                    }
                },
                usageAnchor = {}
            )
        }
    }
}

/**
 * user 气泡里要显示的文字：把「附件 / 图片已上传」那两段说明（标记 + 路径行 + 尾巴提示）裁掉。
 *
 * 这两段只写给模型看（模型靠这些相对路径 read_file 读文件），界面用卡片与缩略图表达。
 * 用户自己写的正文永远在两段说明之前，所以只能按标记各切一刀：
 * 从第一个标记一把切到末尾，会把夹在中间的正文一起吞掉：agent 收到了那句话，界面上却一个字没有。
 */
private fun userBubbleText(message: ChatMessage): String {
    var text = message.content
    // 图片段总在附件段之后，先切它不会打乱附件段的起点
    val imageCut = text.indexOf(IMAGE_PATHS_MARKER)
    if (imageCut >= 0) text = text.substring(0, imageCut)
    val fileCut = text.indexOf(ATTACHMENT_PATHS_MARKER)
    if (fileCut >= 0) text = text.substring(0, fileCut)
    // 批准执行时拼进去的计划正文：只给模型看，气泡里留一句「同意执行了哪一份」就够
    val planCut = text.indexOf(PLAN_DETAIL_MARKER)
    if (planCut >= 0) text = text.substring(0, planCut)
    return text.trimEnd()
}

/**
 * 气泡右下角的展开 / 收起按钮：圆角小条 + 一枚细箭头，底色取气泡本身的一层淡色。
 *
 * 不做圆片也不加外圈：那圈环状阴影和气泡的平面语言不搭，拆成圆角矩形后像气泡自己的一个角标。
 *
 * 悬浮小窗复用同一枚（所以是 internal 而不是 private）：两处的折叠观感必须是同一个东西。
 */
@Composable
internal fun FoldToggle(expanded: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(onSurface.copy(alpha = 0.06f))
            .clickable(onClickLabel = if (expanded) "收起" else "展开", onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(14.dp)) {
            val path = Path().apply {
                if (expanded) {
                    moveTo(size.width * 0.18f, size.height * 0.64f)
                    lineTo(size.width * 0.5f, size.height * 0.34f)
                    lineTo(size.width * 0.82f, size.height * 0.64f)
                } else {
                    moveTo(size.width * 0.18f, size.height * 0.36f)
                    lineTo(size.width * 0.5f, size.height * 0.66f)
                    lineTo(size.width * 0.82f, size.height * 0.36f)
                }
            }
            drawPath(
                path = path,
                color = onSurface.copy(alpha = 0.55f),
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/**
 * 这条消息带上的普通附件（工作区相对路径）。
 *
 * 正文里只留了「标记 + 一连串 - 路径」这段说明，界面要按它画文件卡，所以反向解析回来；
 * 解析口径与 ChatViewModel.attachmentPathsIn 一致（同一个标记、同一种行格式）。
 */
private fun attachmentPaths(message: ChatMessage): List<String> {
    val start = message.content.indexOf(ATTACHMENT_PATHS_MARKER)
    if (start < 0) return emptyList()
    return message.content.substring(start + ATTACHMENT_PATHS_MARKER.length)
        .lines()
        .dropWhile { it.isBlank() }
        .takeWhile { it.startsWith("- ") }
        .map { it.removePrefix("- ").trim() }
        .filter { it.isNotEmpty() }
}

/**
 * 已发送的文件卡：固定宽度，图标 + 文件名单行省略 + 类型/大小副行。
 *
 * 大小取盘上的真实字节数（文件已经在工作区里），读不到就只写类型，不显示「0 B」：
 * 用户清理过附件之后，那张卡还在老会话里，写 0 B 会像「文件坏了」。
 */
@Composable
private fun SentFileCard(relPath: String, root: File) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val name = relPath.substringAfterLast('/')
    val sizeText = remember(relPath, root) {
        val bytes = runCatching { File(root, relPath).length() }.getOrDefault(0L)
        when {
            bytes <= 0L -> ""
            bytes >= 1024L * 1024L -> "%.1f".format(Locale.US, bytes / 1024.0 / 1024.0) + " MB"
            else -> ((bytes + 1023L) / 1024L).toString() + " KB"
        }
    }
    val kind = name.substringAfterLast('.', "").uppercase(Locale.US).ifEmpty { "文件" }
    val subText = if (sizeText.isEmpty()) kind else kind + " · " + sizeText
    Column(
        modifier = Modifier
            .width(SentFileCardWidth)
            .clip(SentCardShape)
            .background(onSurface.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        FileGlyph(tint = onSurface.copy(alpha = 0.55f))
        Spacer(Modifier.height(10.dp))
        Text(
            text = name,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = onSurface.copy(alpha = 0.5f)
        )
    }
}

/** 文件图标：一张纸 + 三行正文。工程里没有 material-icons 依赖，所以手画（同小窗那套做法）。 */
@Composable
private fun FileGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(width = 20.dp, height = 24.dp)) {
        val paperCorner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        drawRoundRect(color = tint.copy(alpha = 0.18f), cornerRadius = paperCorner)
        val lineHeight = 1.6.dp.toPx()
        val left = size.width * 0.24f
        val right = size.width * 0.76f
        listOf(0.32f, 0.52f, 0.72f).forEachIndexed { index, ratio ->
            drawRoundRect(
                color = tint,
                topLeft = Offset(left, size.height * ratio),
                // 最后一行短一截，纸片才有「一页写完」的样子
                size = Size(if (index == 2) (right - left) * 0.62f else right - left, lineHeight),
                cornerRadius = CornerRadius(lineHeight / 2, lineHeight / 2)
            )
        }
    }
}

/**
 * 已发送的图片缩略图：边长恒定（[SentThumbSide]）、crop 填充，点开详情、长按弹菜单。
 *
 * 尺寸恒定是关键：按原图比例铺开时，一条消息里竖图和横图能差出半屏高，看着像两种东西。
 * 手势走同一个 pointerInput（见 [pressOrTap]）：拆成 clickable + 长按两个修饰符时，
 * 长按弹出菜单后手指一松，clickable 还会照常点一次，直接跳进详情页。
 */
@Composable
private fun SentImageThumb(
    relPath: String,
    root: File,
    onTap: () -> Unit,
    onLongPress: (Offset) -> Unit
) {
    val bitmap = remember(relPath) {
        runCatching { BitmapFactory.decodeFile(File(root, relPath).absolutePath) }.getOrNull()
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    // pointerInput(relPath) 不会因为重组而重启，回调必须走 rememberUpdatedState 才是最新的那一个
    val currentTap by rememberUpdatedState(onTap)
    val currentLongPress by rememberUpdatedState(onLongPress)
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .size(SentThumbSide)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(SentCardShape)
            .then(if (bitmap == null) Modifier.background(onSurface.copy(alpha = 0.12f)) else Modifier)
            .pointerInput(relPath) {
                // 锚点算成屏幕坐标（origin + 本节点内的落点），菜单才会弹在被按住的那一张旁边
                pressOrTap(
                    onTap = { currentTap() },
                    onLongPress = { local -> currentLongPress(origin + local) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(text = "图片", fontSize = 12.sp, color = onSurface.copy(alpha = 0.5f))
        }
    }
}

/**
 * 一个节点的「点击 / 长按」手势，两者只在同一个 pointerInput 里判。
 *
 * 长按成立后把这次手势剩下的部分吃掉（直到抬手），所以松手不会再算一次点击；
 * 手指滑出阈值则两边都不触发，把滚动让给列表和横向卡片行。
 */
private suspend fun PointerInputScope.pressOrTap(
    onTap: () -> Unit,
    onLongPress: (Offset) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        val slop = viewConfiguration.touchSlop
        var dragged = false
        // 长按超时内盯着这次手势：抬手、滑走、第二根手指落下都判成「不是长按」
        val expired = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: return@withTimeoutOrNull false
                if (!change.pressed) return@withTimeoutOrNull false
                if (event.changes.count { it.pressed } > 1) return@withTimeoutOrNull false
                if (abs(change.position.x - start.x) > slop ||
                    abs(change.position.y - start.y) > slop
                ) {
                    dragged = true
                    return@withTimeoutOrNull false
                }
            }
            false
        }
        when {
            // 超时那一刻还没抬手 = 长按：吃掉后续事件，抬手不再触发点击
            expired == null -> {
                onLongPress(start)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                }
            }
            // 没滑走就抬手 = 点击
            !dragged -> onTap()
        }
    }
}

/**
 * 一个助手回合：思考窗口、正文气泡、工具卡按「真实到达顺序」混排，轮末折成一行计数。
 *
 * 这一层只负责三件事：把回合摊平（[buildTurnSegments]）、挂长按菜单、处理折叠状态；
 * 具体怎么画全部交给 [TurnStream]。摊平与折叠的口径见 TurnStream.kt 顶部那段说明。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantRow(
    all: List<ChatMessage>,
    indices: List<Int>,
    streaming: Boolean,
    toolOutcomes: Map<String, Boolean>,
    /** 复制本轮全部正文（用户在气泡里能看全的那些文字）。 */
    onCopy: (String) -> Unit,
    /** 选择文本：正文与思考一起交出去，选择页里再分两段。 */
    onSelectText: (String, String) -> Unit,
    /** 以这一轮为终点开一个新会话（分支）。 */
    onFork: (Int) -> Unit,
    /** 打开「本轮用量」伴随面板，第二个参数是那枚按钮在窗口里的位置。 */
    onUsage: (TurnUsageInfo, Offset) -> Unit
) {
    // 一轮按「到达顺序」摊平：每条消息里的思考 → 正文 → 它的工具调用，接着下一条。
    // 旧实现把所有思考拼成一整段提到最上面、把工具卡全塞进同一个气泡，界面顺序跟真实
    // 发生顺序对不上；摊平之后界面顺序＝发生顺序：思考 → 工具 → 思考 → 工具 → 正文。
    val segments = buildTurnSegments(all, indices)
    // 长按菜单要的是「这一轮用户能挑走的文字」，所以正文与思考仍各自整轮拼接
    val wholeText = indices.mapNotNull { all.getOrNull(it)?.content?.takeIf { c -> c.isNotBlank() } }
        .joinToString("\n\n")
    val reasoning = indices.joinToString("") { all.getOrNull(it)?.reasoningContent.orEmpty() }
    val lastIndex = indices.lastOrNull()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bubbleColor = if (isDark) Palette.BubbleAiDark else Palette.BubbleAiLight
    val thinkColor = if (isDark) Palette.BubbleThinkDark else Palette.BubbleThinkLight
    val cardColor = if (isDark) Palette.BubbleCardDark else Palette.BubbleCardLight
    val menuIndex = indices.firstOrNull() ?: 0
    // 本轮用量：只有最后一条 assistant 带数字（ChatViewModel.writeTurnUsage 只写那一条），
    // 所以扫一遍取那个非零的；流式途中还没写，这里就是 null，用量按钮不会闪出来。
    var turnUsage: TurnUsageInfo? = null
    // 时间默认取本轮最后一条消息的时间。以前从 0 起算：定时任务产出的答复没有用量数字，
    // 0 经「HH:mm」格式化正好是东八区的 08:00（纪元零点），看起来像时间停在早上八点。
    var tokensAt = all.getOrNull(indices.lastOrNull() ?: -1)?.createdAt ?: 0L
    for (index in indices) {
        val message = all.getOrNull(index) ?: continue
        if (message.promptTokens > 0 || message.completionTokens > 0) {
            turnUsage = turnUsageOf(message)
            tokensAt = message.createdAt
        }
    }

    // 「最终回答」与「过程」的分界：
    // 最终回答 = 本轮的**最后一条** assistant 消息，且必须同时满足
    //   ① 有回复内容（正文或思考非空）② 不含任何工具调用 ③ 是最后一步。
    // 任一不满足 ⇒ 这一轮**没有最终回答**：整轮都算过程，折叠后只剩那行计数
    // （此时过程起点直接取本轮起点）。
    // 注意这条口径的后果：过程里有没有文字，完全取决于模型自己说不说，
    // 不靠提示词逼它「每步先说一句」。
    val answerIndex = indices.lastOrNull()?.takeIf { index ->
        val message = all.getOrNull(index)
        message != null &&
            message.role == "assistant" &&
            message.toolCalls.isEmpty() &&
            (message.content.isNotBlank() || !message.reasoningContent.isNullOrBlank())
    }
    val finalSegment = if (answerIndex == null) {
        null
    } else {
        all.getOrNull(answerIndex)?.content?.takeIf { it.isNotBlank() }?.let {
            TurnSegment.Body(answerIndex, it)
        }
    }
    // 过程 = 除「最终回答那段正文」以外的全部片段；最终回答那条消息的**思考**留在过程里
    // （折叠收起时藏、展开时看得到），这样展开后仍能看到收口时想了什么。
    val processSegments = if (answerIndex == null) {
        segments
    } else {
        segments.filterNot { it is TurnSegment.Body && it.messageIndex == answerIndex }
    }
    val thinkCount = processSegments.count { it is TurnSegment.Think }
    val callCount = processSegments.count { it is TurnSegment.Call }
    val hasProcess = thinkCount > 0 || callCount > 0
    // 折叠态只在「这一轮已经跑完」之后才成立：流式中途折起来，用户就看不见正在做的事了。
    // 用户手动展开之后保持展开：只有下一轮流式结束（streaming 由 true 变 false）才会重新折回去。
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(streaming) { if (!streaming) expanded = false }
    val collapsed = hasProcess && !streaming && !expanded
    // 折叠时只留最终回答；本轮没有最终回答（模型以工具调用收尾 / 一句话没说）时什么都不留，
    // 只剩上面那行计数。流式途中不折，看得见正在做什么。
    val shown = if (collapsed) listOfNotNull(finalSegment) else segments
    // 展开态：过程装进底板，最终回复留在底板外面
    val showPanel = expanded && hasProcess && !streaming

    // 动作按钮在气泡尾巴上（见下方），这一整轮不再挂长按手势：
    // 长按不消费事件，工具卡照常点击这一点本来就只是为了给弹窗让路
    Column(modifier = Modifier.fillMaxWidth()) {
        if (hasProcess && !streaming) {
            TurnProcessSummary(
                thinkCount = thinkCount,
                callCount = callCount,
                expanded = expanded,
                onToggle = { expanded = !expanded }
            )
        }
        if (showPanel) {
            // 过程区：浅灰底板 + 内缩，读作「打开的一堆底层日志」
            if (processSegments.isNotEmpty()) {
                TurnProcessPanel {
                    TurnStream(
                        segments = processSegments,
                        all = all,
                        streaming = false,
                        lastMessageIndex = lastIndex,
                        toolOutcomes = toolOutcomes,
                        thinkColor = thinkColor,
                        bubbleColor = bubbleColor,
                        cardColor = cardColor,
                        inPanel = true
                    )
                }
            }
            // 最终区：底板之外、之下，正常聊天气泡，视觉权重最高
            if (finalSegment != null) {
                if (processSegments.isNotEmpty()) Spacer(Modifier.height(10.dp))
                TurnStream(
                    segments = listOf(finalSegment),
                    all = all,
                    streaming = false,
                    lastMessageIndex = lastIndex,
                    toolOutcomes = toolOutcomes,
                    thinkColor = thinkColor,
                    bubbleColor = bubbleColor,
                    cardColor = cardColor
                )
            }
        } else {
            TurnStream(
                segments = shown,
                all = all,
                streaming = streaming,
                lastMessageIndex = lastIndex,
                toolOutcomes = toolOutcomes,
                thinkColor = thinkColor,
                bubbleColor = bubbleColor,
                cardColor = cardColor
            )
        }
        // 尾部动作行：复制 / 选择文本 / 分支 / 用量 + 时间，跟着这条回复走。
        // 用量的明细仍然点开才看：数字本身不该占着一行。
        if (!streaming && (turnUsage != null || wholeText.isNotEmpty())) {
            MessageTailRow(
                at = clockText(tokensAt),
                alignEnd = false,
                icons = buildList {
                    add(TailIcon.COPY)
                    add(TailIcon.SELECT_TEXT)
                    add(TailIcon.FORK)
                    if (turnUsage != null) add(TailIcon.USAGE)
                },
                onAction = { kind ->
                    when (kind) {
                        TailIcon.COPY -> onCopy(wholeText)
                        TailIcon.SELECT_TEXT -> onSelectText(wholeText, reasoning)
                        // 分支保留**这一轮整轮**及其以上：边界取本轮最后一条消息的下标 +1。
                        // 以前传的是本轮第一条的下标 +1，正好把这一轮的其余消息（工具调用那几条）
                        // 切掉，看起来就是「分支之后内容不全」（用户 10:06 报的回归）
                        TailIcon.FORK -> onFork((lastIndex ?: menuIndex) + 1)
                        else -> Unit
                    }
                },
                usageAnchor = { anchor ->
                    turnUsage?.let { usage -> onUsage(usage, anchor) }
                }
            )
        }
    }
}

/**
 * 从 Compose 拿到的 Context 往上剥 ContextWrapper，找到真正的 Activity。
 *
 * LocalContext 通常是被包装过的 Context，不能直接 as Activity；拿不到就返回 null，
 * 调用方退回「发 HOME Intent 回桌面」的兜底方案。
 */
private fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 长按图片里的「保存图片」：写盘放 IO，写完回主线程弹一句「已保存」。
 *
 * 保存的是被长按的那一张（[relPath] 由手势回调带上来），不是整条消息里的全部图片。
 */
private fun saveImageFromChat(context: Context, scope: CoroutineScope, root: File, relPath: String) {
    scope.launch {
        val ok = withContext(Dispatchers.IO) { saveImageToDownload(context, root, relPath) }
        Toast.makeText(context, if (ok) "已保存" else "保存失败", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 把工作区里的一张图存进系统「下载」目录，文件名沿用工作区里那一份（也就是用户手机里的原名）。
 *
 * API 29 起走 MediaStore + RELATIVE_PATH：不需要任何存储权限，也不会在相册里多挂一份；
 * 26–28 没有 Downloads 这个集合，只能直接写公共目录（依赖清单里已声明的 WRITE_EXTERNAL_STORAGE），
 * 没有授权 / 目录不可写就返回 false，由调用方提示「保存失败」。
 */
private fun saveImageToDownload(context: Context, root: File, relPath: String): Boolean {
    val source = File(root, relPath)
    if (!source.isFile) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // 29+ 首选 MediaStore（不用存储权限）；本机 targetSdk 还是 28，个别机型在旧存储模式下
        // 会把它拦掉，那就退回直接写公共 Download 目录，别白丢一次保存
        if (saveImageViaMediaStore(context, source)) return true
    }
    return saveImageToPublicDownload(source)
}

/** 29+：先插一条 Downloads 记录再往里写；IS_PENDING 保证别的 App 不会读到写了一半的文件。 */
private fun saveImageViaMediaStore(context: Context, source: File): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
        put(MediaStore.MediaColumns.MIME_TYPE, imageMimeOf(source.name))
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }
        .getOrNull() ?: return false
    return try {
        // 打不开输出流就别留一条空记录在 Download 里
        val out = resolver.openOutputStream(uri) ?: run {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
        out.use { sink -> source.inputStream().use { it.copyTo(sink) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        runCatching { resolver.update(uri, values, null, null) }
        true
    } catch (e: Exception) {
        FileLog.log("save", "保存图片失败：" + (e.message ?: e.javaClass.simpleName))
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}

/** 26–28：直接写公共 Download 目录，重名就加「 (n)」，不覆盖用户已有的文件。 */
@Suppress("DEPRECATION")
private fun saveImageToPublicDownload(source: File): Boolean {
    return runCatching {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val target = uniqueDownloadFile(dir, source.name)
        source.inputStream().use { input -> target.outputStream().use { out -> input.copyTo(out) } }
        true
    }.getOrElse { e ->
        FileLog.log("save", "保存图片失败：" + (e.message ?: e.javaClass.simpleName))
        false
    }
}

/** Download 里已经有同名文件就依次试 name (1).ext、name (2).ext…… */
private fun uniqueDownloadFile(dir: File, name: String): File {
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var target = File(dir, name)
    var index = 1
    while (target.exists()) {
        target = File(dir, base + " (" + index + ")" + ext)
        index++
    }
    return target
}

/** 存进 Download 时要给 MediaStore 一个类型；认不出来一律按压缩图（工作区里的图都是 JPEG）。 */
private fun imageMimeOf(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"
        else -> "image/jpeg"
    }

/** 复制到剪贴板。Android 13 起系统自己会弹「已复制」，不再叠加 Toast。 */
private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("消息", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }
}

/** 文件选择器「上次目录」的有效期：超过就不再用它，免得一次选择把以后每次打开都困在同一层。 */
private const val PICKER_DIR_TTL_MS = 30L * 60 * 1000L

/** 路径记忆存在 app_prefs 里（跟其它 Store 同一个文件），不单开一份 prefs。 */
private const val PICKER_DIR_PREFS = "app_prefs"
private const val KEY_PICKER_DIR = "picker_last_dir"
private const val KEY_PICKER_DIR_AT = "picker_last_dir_at"
private const val KEY_PICKER_DIR_SESSION = "picker_last_dir_session"

private fun pickerDirPrefs(context: Context) =
    context.applicationContext.getSharedPreferences(PICKER_DIR_PREFS, Context.MODE_PRIVATE)

/**
 * 这次文稿页该从哪一层打开；null = 不指定，系统自己回到顶层。
 *
 * 三条都要成立才回上次的目录：同一个会话、记过目录、距上次选择不到 30 分钟。
 * 少任何一条都从顶层开始，否则「十天前在别的会话里点进过的深层目录」会一直粘着。
 */
private fun pickerInitialDir(context: Context, sessionId: String): Uri? {
    val prefs = pickerDirPrefs(context)
    val saved = prefs.getString(KEY_PICKER_DIR, null) ?: return null
    if (prefs.getString(KEY_PICKER_DIR_SESSION, null) != sessionId) return null
    val at = prefs.getLong(KEY_PICKER_DIR_AT, 0L)
    if (System.currentTimeMillis() - at > PICKER_DIR_TTL_MS) return null
    return runCatching { Uri.parse(saved) }.getOrNull()
}

/** 记下这次选的文件所在的目录；推不出目录（非标准 provider）就什么都不记，宁可不记忆也不记一个错的。 */
private fun rememberPickerDir(context: Context, uri: Uri, sessionId: String) {
    val dir = parentDirUri(uri) ?: return
    pickerDirPrefs(context).edit()
        .putString(KEY_PICKER_DIR, dir.toString())
        .putLong(KEY_PICKER_DIR_AT, System.currentTimeMillis())
        .putString(KEY_PICKER_DIR_SESSION, sessionId)
        .apply()
}

/**
 * 文件的父目录 URI。
 *
 * 选择器返回的是**文件** URI，而 EXTRA_INITIAL_URI 要的是**目录**，所以按 documentId
 * （形如 "primary:Download/微信/图.jpg"）去掉最后一段反推父目录；不在标准 provider
 * 口径里的（没有「卷:路径」形状）返回 null，调用方据此退化成「不记忆」。
 */
private fun parentDirUri(uri: Uri): Uri? {
    val authority = uri.authority ?: return null
    val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
    val colon = docId.indexOf(':')
    if (colon < 0) return null
    val path = docId.substring(colon + 1)
    // 已经在卷根上：没有「上一层目录」可记
    if (!path.contains('/')) return null
    val parentId = docId.substring(0, colon + 1) + path.substringBeforeLast('/')
    return runCatching { DocumentsContract.buildDocumentUri(authority, parentId) }.getOrNull()
}

/** 视口是否已经贴在列表底部（底部 200dp 容差由调用方给）：决定键盘弹出时要不要跟着抬高。 */
private fun LazyListState.isNearBottom(tolerancePx: Int): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    if (last.index < info.totalItemsCount - 1) return false
    return last.offset + last.size - info.viewportEndOffset <= tolerancePx
}

/** 自动贴底：最后一条消息变高时，把它底部对齐到视口底部。 */
private suspend fun followBottom(state: LazyListState, lastIndex: Int) {
    if (lastIndex < 0) return
    val info = state.layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex }
    if (info == null) {
        state.scrollToItem(lastIndex)
        // scrollToItem 只把这一项的「顶部」对齐视口顶部。历史最后一条往往是一整轮助手回复
        // （思考 + 工具卡 + 正文，比屏幕高得多），所以进 App 会停在「思考了 N 秒」那一行。
        // 这里再补一次：把它底部对齐视口底部。
        val landed = state.layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex }
        if (landed != null) {
            val bottomOverflow = landed.offset + landed.size - state.layoutInfo.viewportEndOffset
            if (bottomOverflow > 0) state.scroll { scrollBy(bottomOverflow.toFloat()) }
        }
        return
    }
    val overflow = info.offset + info.size - state.layoutInfo.viewportEndOffset
    if (overflow > 0) state.scroll { scrollBy(overflow.toFloat()) }
}

/**
 * 键盘把视口顶矮之后，把最后一条消息的底部对齐视口底部（跟 [followBottom] 同一口径，只是走动画）。
 *
 * 全程只有一段动画：先量出「底部还差多少」，再一次性滚过去。
 * 原来的 animateScrollToItem(顶部对齐) + animateScrollBy(补差) 是两段，
 * 中间那一帧会停在最近一轮回复气泡的头部（「思考了 N 秒」那一行），
 * 用户看到的就是「先翻到思考、再翻回底部」。
 */
private suspend fun settleToBottom(state: LazyListState, lastIndex: Int) {
    if (lastIndex < 0) return
    val info = state.layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex }
    if (info == null) {
        // 最后一条整个掉出视口（视口一下矮了半屏以上）：只能先把它带进来，
        // 这种情形本来也没有「气泡头部闪一下」可看
        state.animateScrollToItem(lastIndex)
        return
    }
    val overflow = info.offset + info.size - state.layoutInfo.viewportEndOffset
    if (overflow > 0) state.animateScrollBy(overflow.toFloat(), Motion.spring())
}

/** 气泡右下角的时间只到分钟。复用一个实例：流式输出时每条气泡都在重组，别每次都 new。 */
private val usageClock = SimpleDateFormat("HH:mm", Locale.US)

private fun clockText(at: Long): String = usageClock.format(Date(at))

/**
 * 一条消息的用量 → 「本轮用量」面板要的形状。
 *
 * 兼容三种落盘：①本次改造之后（未缓存 / 缓存写入 / 推理都已经存下）；②只有「输入 / 输出 / 缓存命中」
 * 的旧版；③连缓存都没有的更老会话。后两种按「没报缓存 = 整段输入都算未缓存」补，
 * 面板不至于给出一行「未缓存输入 0 tok」这种明显错的数。
 */
private fun turnUsageOf(message: ChatMessage): TurnUsageInfo {
    val reported = message.usageCacheReported
    val uncached = when {
        message.uncachedInputTokens > 0 -> message.uncachedInputTokens
        reported -> (message.promptTokens - message.cachedTokens).coerceAtLeast(0)
        else -> message.promptTokens
    }
    return TurnUsageInfo(
        uncachedInputTokens = uncached,
        cacheReadTokens = if (reported) message.cachedTokens else 0,
        cacheReadReported = reported,
        // 服务商没有「缓存写入」这一项（含所有 v0.9.5 之前的会话）时写 0，与参考图一致
        cacheWriteTokens = message.cacheWriteTokens ?: 0,
        completionTokens = message.completionTokens,
        reasoningTokens = message.reasoningTokens,
        model = message.usageModel
    )
}
