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
    val openContextHub: (Boolean) -> Unit = ui::openContextHub

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
                ChatDrawerPane(
                    viewModel = viewModel,
                    ui = ui,
                    context = context,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange
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

                    // 消息流：列表 + 面板遮罩 + 分叉盖板
                    ChatMessageList(
                        viewModel = viewModel,
                        ui = ui,
                        context = context,
                        imageRoot = imageRoot,
                        items = items,
                        listState = listState
                    )

                    // 输入区上的一摞条 + 输入框
                    ChatComposerArea(
                        viewModel = viewModel,
                        ui = ui,
                        context = context,
                        imageRoot = imageRoot,
                        statusRounds = statusRounds,
                        statusSteps = statusSteps,
                        onPickFile = { openFilePicker() },
                        onLogLaunch = { name -> logLauncher.launch(name) }
                    )

                }
            }

        // 子任务面板（全屏页之下的一层浮层，层序与拆分前一致）
        ChatSubAgentPanel(
            ui = ui,
            viewModel = viewModel,
            subAgentEntries = subAgentEntries
        )

        // 二十层全屏页面：设置层 / 工作区 / 会话列表 / 计划 / 浏览器 / 子智能体
        ChatPageLayers(
            viewModel = viewModel,
            ui = ui,
            uiScale = uiScale,
            onUiScaleChange = onUiScaleChange,
            subAgentEntries = subAgentEntries,
            onImportLaunch = {
                importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        )


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
