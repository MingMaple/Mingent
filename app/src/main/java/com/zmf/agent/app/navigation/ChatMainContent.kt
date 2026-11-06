package com.zmf.agent.app.navigation

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.onGloballyPositioned
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
    var viewer by ui.viewer
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
                            onLogLaunch = onLogLaunch,
                            blurred = viewer != null
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
                                onLogLaunch = onLogLaunch,
                                blurred = viewer != null
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
    onLogLaunch: (String) -> Unit,
    blurred: Boolean
) {
    var page by ui.page
    var drawerOpen by ui.drawerOpen
    var topBarHeightPx by ui.topBarHeightPx
    var subAgentPanelOpen by ui.subAgentPanelOpen
    val subAgentRunning = subAgentEntries.count { it.state == SubAgentRegistry.RUNNING }
    val window = LocalUiWindow.current

    Surface(
        modifier = Modifier
            .fillMaxSize()
            // 取 16dp 而非 24dp：模糊后仍需辨认下方文字轮廓，24dp 会使整屏变为纯色
            .then(if (blurred) Modifier.blur(16.dp) else Modifier),
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
                        onBrowserClick = { page = AppPage.BROWSER }
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
