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
import com.zmf.agent.feature.chat.ChatItem
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
import com.zmf.agent.feature.chat.BrowserState
import com.zmf.agent.feature.chat.ChatComposerArea
import com.zmf.agent.feature.chat.ChatMessageList
import com.zmf.agent.feature.chat.ChatSubAgentPanel
import com.zmf.agent.feature.chat.ChatOverlays
import com.zmf.agent.feature.chat.ChatTopBar
import com.zmf.agent.feature.chat.AppPage
import com.zmf.agent.feature.chat.ChatUiState
import com.zmf.agent.feature.chat.ChatViewModel

/**
 * 聊天页的骨架：底色 → 抽屉 → 轨迹页连体平移 → 主内容（顶栏 / 消息流 / 输入区）→ 页面层 → 浮层。
 *
 * 这一层只负责「谁套在谁里面」和层序，不含业务判断；参数里带着 ChatScreen 已经算好的派生值和
 * 三个外部落点（选文件 / 下载日志 / 导入），自己不碰 SAF。层序即行为：抽屉在 content 子树里、
 * 页面层在同一个 Box 里按顺序叠放，这两条决定了横滑与滑入动画的表现，搬迁时一处没动。
 */
@Composable
internal fun ChatShell(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    context: Context,
    scope: CoroutineScope,
    listState: LazyListState,
    items: List<ChatItem>,
    imageRoot: File,
    statusRounds: Int,
    statusSteps: Int,
    subAgentEntries: List<SubAgentRegistry.Entry>,
    imePad: Dp,
    imeVisible: Boolean,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    uiScale: Float,
    onUiScaleChange: (Float) -> Unit,
    onPickFile: () -> Unit,
    onLogLaunch: (String) -> Unit,
    onImportLaunch: () -> Unit
) {
    var page by ui.page
    var drawerOpen by ui.drawerOpen
    var pagerAtFirstPage by ui.pagerAtFirstPage
    var topBarHeightPx by ui.topBarHeightPx
    var viewer by ui.viewer
    var subAgentPanelOpen by ui.subAgentPanelOpen
    val subAgentRunning = subAgentEntries.count { it.state == SubAgentRegistry.RUNNING }

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
                        listState = listState,
                        imePad = imePad,
                        imeVisible = imeVisible
                    )

                    // 输入区上的一摞条 + 输入框
                    ChatComposerArea(
                        viewModel = viewModel,
                        ui = ui,
                        context = context,
                        imageRoot = imageRoot,
                        statusRounds = statusRounds,
                        statusSteps = statusSteps,
                        onPickFile = onPickFile,
                        onLogLaunch = onLogLaunch
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
            onImportLaunch = onImportLaunch
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
}
