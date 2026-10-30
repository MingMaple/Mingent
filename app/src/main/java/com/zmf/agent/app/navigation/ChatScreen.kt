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
import com.zmf.agent.feature.chat.ChatAppEffects
import com.zmf.agent.feature.chat.ChatBackHandlers
import com.zmf.agent.feature.chat.buildChatItems
import com.zmf.agent.feature.chat.pickerInitialDir
import com.zmf.agent.feature.chat.rememberPickerDir
import com.zmf.agent.feature.chat.AppPage
import com.zmf.agent.feature.chat.ChatUiState
import com.zmf.agent.feature.chat.ChatViewModel


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
    // 全部瞬时界面状态集中在这里：子组合用 `var x by ui.x` 拿到同一份状态
    val ui = remember { ChatUiState(context) }
    val viewModel = remember { ChatViewModel(context) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyboard = LocalSoftwareKeyboardController.current

    // 子任务面板：Registry 是内存 object（子智能体在 IO 协程里写）。
    // 以前这里是 300ms 轮询：空闲、一个子智能体都没有时也在每 300ms 醒一次并分配一份快照。
    // 现在改成订阅版本号：没有子智能体在跑时，那条 Flow 一条都不会发，主线程一次都不会被叫醒。
    var subAgentVersion by remember { mutableStateOf(0) }
    val versionTick by SubAgentRegistry.version.collectAsState()
    subAgentVersion = versionTick
    // 只在版本真的变了时重取快照（首帧也走这条 remember，等于开页面先取一次）
    val subAgentEntries = remember(subAgentVersion) { SubAgentRegistry.snapshot() }

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
    var includeTerminal by ui.includeTerminal
    var includeLogs by ui.includeLogs
    var includeMemory by ui.includeMemory
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
    var importUri by ui.importUri
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
    val imeVisible = WindowInsets.isImeVisible

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

    // 全部副作用：通知进会话、前后台落盘、小窗桥、抽屉收键盘、计划跳转、链接路由
    ChatAppEffects(
        viewModel = viewModel,
        ui = ui,
        context = context,
        lifecycleOwner = lifecycleOwner,
        focusManager = focusManager,
        keyboard = keyboard,
        initialSessionId = initialSessionId,
        subAgentEntries = subAgentEntries
    )

    // 返回键：注册顺序即优先级，逐条与拆分前一致
    ChatBackHandlers(viewModel = viewModel, ui = ui, context = context)

    // 骨架：抽屉 / 轨迹页 / 顶栏 / 消息流 / 输入区 / 页面层 / 浮层
    ChatShell(
        viewModel = viewModel,
        ui = ui,
        context = context,
        scope = scope,
        listState = listState,
        items = items,
        imageRoot = imageRoot,
        statusRounds = statusRounds,
        statusSteps = statusSteps,
        subAgentEntries = subAgentEntries,
        imePad = imePad,
        imeVisible = imeVisible,
        themeMode = themeMode,
        onThemeModeChange = onThemeModeChange,
        uiScale = uiScale,
        onUiScaleChange = onUiScaleChange,
        onPickFile = { openFilePicker() },
        onLogLaunch = { name -> logLauncher.launch(name) },
        onImportLaunch = {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
    )

    // 六个模态框：新建工作区 / 导出选项 / 导入确认 / 导入结果 / 清单全表 / 回滚确认
    ChatDialogs(
        viewModel = viewModel,
        ui = ui,
        context = context,
        scope = scope,
        onExportLaunch = { name -> exportLauncher.launch(name) }
    )
}
