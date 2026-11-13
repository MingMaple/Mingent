package com.zmf.agent.app.navigation

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.remember
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.feature.chat.AppPage
import com.zmf.agent.feature.chat.BrowserState
import com.zmf.agent.feature.chat.ChatComposerArea
import com.zmf.agent.feature.chat.ChatItem
import com.zmf.agent.feature.chat.ChatMessageList
import com.zmf.agent.feature.chat.ChatOverlays
import com.zmf.agent.feature.chat.ChatSubAgentPanel
import com.zmf.agent.feature.chat.ChatTopBar
import com.zmf.agent.feature.chat.ChatUiState
import com.zmf.agent.feature.chat.ChatViewModel
import com.zmf.agent.feature.chat.ShareArea
import com.zmf.agent.feature.chat.ShareIntake
import com.zmf.agent.feature.chat.PreviewTabs
import com.zmf.agent.feature.chat.TracePage
import com.zmf.agent.uikit.components.salt.SaltFullPane
import com.zmf.agent.uikit.layout.LocalUiWindow
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * 抽屉以内的全部内容：聊天列、轨迹页、二级页层与浮层。
 *
 * 这一层只决定「轨迹页用整屏滑入还是右侧辅窗格」，以及聊天列的限宽与居中；
 * 具体的页面、浮层与业务仍由各自模块组合。把它从 [ChatShell] 拆出来，
 * 是为了让外壳只负责抽屉形态，内容只负责窗格形态，两层不互相掺入。
 */
@Composable
internal fun ChatMainContent(
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
    uiScale: Float,
    onUiScaleChange: (Float) -> Unit,
    onPickFile: () -> Unit,
    onLogLaunch: (String) -> Unit,
    onImportLaunch: () -> Unit,
    /** 内容宽度充足时，轨迹改为右侧辅窗格，聊天区保持可见可输入。 */
    splitTrace: Boolean
) {
    var page by ui.page
    val window = LocalUiWindow.current

    // 轨迹辅窗格打开时，聊天列实际可用宽度会再窄一截：气泡与内容列按压缩后的宽度计算
    val traceVisible = splitTrace && page == AppPage.TRACE
    val chatPaneWidth = if (traceVisible) {
        (window.paneWidthDp - window.tracePaneWidth - 1.dp).coerceAtLeast(200.dp)
    } else {
        window.paneWidthDp
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(
                    LocalUiWindow provides window.withinPane(chatPaneWidth, window.paneHeightDp)
                ) {
                    if (splitTrace) {
                        ChatColumn(
                            viewModel = viewModel,
                            ui = ui,
                            context = context,
                            listState = listState,
                            items = items,
                            imageRoot = imageRoot,
                            statusRounds = statusRounds,
                            statusSteps = statusSteps,
                            subAgentEntries = subAgentEntries,
                            imePad = imePad,
                            imeVisible = imeVisible,
                            onPickFile = onPickFile,
                            onLogLaunch = onLogLaunch
                        )
                    } else {
                        // 轨迹页与聊天页为一对连体平移面板：向左滑动将聊天页推出左边界、
                        // 轨迹页从右侧补入。它必须仍位于内容子树内，抽屉手势与轨迹手势才互不冲突。
                        SaltFullPane(
                            open = page == AppPage.TRACE,
                            onOpenChange = { open -> page = if (open) AppPage.TRACE else null },
                            gestureEnabled = page == null || page == AppPage.TRACE,
                            panel = {
                                TracePage(
                                    sessionTitle = viewModel.currentTitle(),
                                    messages = viewModel.messages,
                                    imageRoot = imageRoot,
                                    onBack = { page = null }
                                )
                            }
                        ) {
                            ChatColumn(
                                viewModel = viewModel,
                                ui = ui,
                                context = context,
                                listState = listState,
                                items = items,
                                imageRoot = imageRoot,
                                statusRounds = statusRounds,
                                statusSteps = statusSteps,
                                subAgentEntries = subAgentEntries,
                                imePad = imePad,
                                imeVisible = imeVisible,
                                onPickFile = onPickFile,
                                onLogLaunch = onLogLaunch
                            )
                        }
                    }
                }
            }

            if (traceVisible) {
                // 分隔线沿用既有 stroke 色，不新建配色
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                )
                Box(
                    modifier = Modifier
                        .width(window.tracePaneWidth)
                        .fillMaxHeight()
                ) {
                    CompositionLocalProvider(
                        LocalUiWindow provides window.withinPane(window.tracePaneWidth, window.paneHeightDp)
                    ) {
                        TracePage(
                            sessionTitle = viewModel.currentTitle(),
                            messages = viewModel.messages,
                            imageRoot = imageRoot,
                            onBack = { page = null },
                            closeButton = true
                        )
                    }
                }
            }
        }

        // 以下三层仍作为整个内容区的浮层：在常驻抽屉与辅窗格模式下同样只覆盖内容区，不盖住抽屉
        ChatSubAgentPanel(
            ui = ui,
            viewModel = viewModel,
            subAgentEntries = subAgentEntries
        )

        ChatPageLayers(
            viewModel = viewModel,
            ui = ui,
            uiScale = uiScale,
            onUiScaleChange = onUiScaleChange,
            subAgentEntries = subAgentEntries,
            onImportLaunch = onImportLaunch
        )

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

/** 聊天列：顶栏、消息流、输入区，按内容列上限居中。 */
@Composable
private fun ChatColumn(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    context: Context,
    listState: LazyListState,
    items: List<ChatItem>,
    imageRoot: File,
    statusRounds: Int,
    statusSteps: Int,
    subAgentEntries: List<SubAgentRegistry.Entry>,
    imePad: Dp,
    imeVisible: Boolean,
    onPickFile: () -> Unit,
    onLogLaunch: (String) -> Unit
) {
    var page by ui.page
    var drawerOpen by ui.drawerOpen
    var topBarHeightPx by ui.topBarHeightPx
    var subAgentPanelOpen by ui.subAgentPanelOpen
    val subAgentRunning = subAgentEntries.count { it.state == SubAgentRegistry.RUNNING }
    val window = LocalUiWindow.current
    val scope = rememberCoroutineScope()
    // 已打开的预览标签数：为 0 时顶栏不出现入口（与计划、网页同一口径）。
    // 读快照状态列表的 size 即建立订阅，标签增删会自动重组，无需额外状态。
    val previewCount = PreviewTabs.count
    // 取 16dp 而非 24dp：模糊后仍需辨认下方文字轮廓，24dp 会使整屏变为纯色
    val blurRadiusPx = with(LocalDensity.current) { 16.dp.toPx() }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            // 虚化半径跟随查看器的出入场进度（两者同一条动画）。半径在图层阶段读取，
            // 只重绘这一层，不触发整个聊天列重组，滑动与缩放期间不会因此掉帧
            .graphicsLayer {
                val progress = ui.viewerProgress.floatValue
                renderEffect = if (progress > 0.01f) {
                    BlurEffect(blurRadiusPx * progress, blurRadiusPx * progress, TileMode.Clamp)
                } else {
                    null
                }
            },
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = window.chatMaxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    // 输入法占位仅此一处：上方已按动画值计算，重复调用 imePadding 会叠加两份
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
                        // 常驻抽屉时菜单按钮没有意义，隐藏后把宽度留给标题与入口
                        menuVisible = !window.permanentDrawer,
                        compact = window.compactChrome,
                        traceAvailable = window.traceSplitPane,
                        onTraceClick = { page = AppPage.TRACE },
                        subAgentCount = subAgentEntries.size,
                        subAgentRunning = subAgentRunning,
                        onSubAgentClick = { subAgentPanelOpen = !subAgentPanelOpen },
                        plans = viewModel.plans,
                        onPlanClick = {
                            viewModel.reloadPlans()
                            page = AppPage.PLAN
                        },
                        browserOpen = BrowserState.alive,
                        // 从主页进入浏览页没有来路：返回即回主页（若上次是从预览页进来的，来路已作废）
                        onBrowserClick = {
                            ui.browserReturnPage.value = null
                            page = AppPage.BROWSER
                        },
                        previewCount = previewCount,
                        onPreviewClick = { page = AppPage.PREVIEW }
                    )
                }

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

                // 底部槽位：常规输入区与分享面板共用同一位置，一次只画一块。
                // 分享进入时面板自下而上顶替常规输入区（附件行在面板之上）；
                // 发送或取消时面板整块下滑出屏，下面的常规输入区原样露出——两段位移都发生在
                // 同一槽位，消息列表不会先塌后撑。高度变化交给 AnimatedContent 的 SizeTransform
                // 平滑过渡，clip=false 让下滑的那块能越过动画中的高度继续往下走。
                val shareDraft by ui.shareDraft
                val shareVisible by ui.shareVisible
                var shareSending by remember { mutableStateOf(false) }
                var shareFailure by remember { mutableStateOf<String?>(null) }
                // 新一批内容进来时清掉上一批的进度与错误，避免失败文案带到下一次分享
                LaunchedEffect(shareDraft) {
                    shareSending = false
                    shareFailure = null
                }
                AnimatedContent(
                    targetState = shareVisible && shareDraft != null,
                    transitionSpec = {
                        if (targetState) {
                            (slideInVertically(tween(220)) { it } + fadeIn(tween(160))) togetherWith
                                fadeOut(tween(120))
                        } else {
                            fadeIn(tween(160)) togetherWith
                                (slideOutVertically(tween(220)) { it } + fadeOut(tween(160)))
                        }.using(SizeTransform(clip = false))
                    },
                    label = "shareSlot"
                ) { sharing ->
                    val draftNow = shareDraft
                    if (sharing && draftNow != null) {
                        ShareArea(
                            draft = draftNow,
                            workspaces = viewModel.workspaceList,
                            sessions = viewModel.sessionList,
                            // 目标 = 当前会话：面板里选目标即真的切换，抽屉里切换后目标键也随之变化
                            currentSessionId = viewModel.currentSessionId,
                            currentWorkspaceId = viewModel.currentWorkspaceId,
                            sending = shareSending,
                            failure = shareFailure,
                            // 删除某个分享文件（长文附件或普通文件）：中转文件同步删除，只保留用户写的那句话
                            onRemoveFile = { rel ->
                                ShareIntake.discardFile(draftNow, rel)
                                ui.shareDraft.value = if (rel == draftNow.longTextRelPath) {
                                    draftNow.copy(longTextRelPath = null, longTextChars = 0)
                                } else {
                                    draftNow.copy(fileRelPaths = draftNow.fileRelPaths - rel)
                                }
                            },
                            onRemoveImage = { rel ->
                                ShareIntake.discardFile(draftNow, rel)
                                ui.shareDraft.value = draftNow.copy(
                                    imageRelPaths = draftNow.imageRelPaths - rel
                                )
                            },
                            // 发送已提交时不再受理放弃：否则面板消失但消息仍会发出
                            onCancel = { if (!shareSending) ui.shareVisible.value = false },
                            onTargetPicked = { picked ->
                                shareFailure = null
                                // 生成期间会话层不允许切换（流式结果写的是当前会话的消息表），
                                // 此时保持原会话不变；选中的就是当前会话本身则不算切换，不提示。
                                if (viewModel.isStreaming) {
                                    if (!viewModel.isShareTargetCurrent(picked)) {
                                        shareFailure = "正在生成回复，本轮结束后再切换目标"
                                    }
                                } else if (!viewModel.selectShareTarget(picked)) {
                                    shareFailure = "目标会话或工作区已不存在，请重新选择"
                                }
                            },
                            onSend = { body ->
                                shareFailure = null
                                shareSending = true
                                viewModel.sendShare(body, draftNow, scope) { failure ->
                                    shareSending = false
                                    if (failure != null) {
                                        shareFailure = failure
                                    } else {
                                        // 发送成功：面板退场，常规输入区露出，消息就在眼前的这个会话里
                                        ui.wasAtBottom.value = true
                                        ui.shareVisible.value = false
                                    }
                                }
                            }
                        )
                    } else {
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
            }
        }
    }
}
