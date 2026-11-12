package com.zmf.agent.app.navigation

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.core.prefs.ThemeMode
import com.zmf.agent.feature.chat.AppPage
import com.zmf.agent.feature.chat.ChatItem
import com.zmf.agent.feature.chat.ChatUiState
import com.zmf.agent.feature.chat.ChatViewModel
import com.zmf.agent.uikit.components.salt.SaltDrawer
import com.zmf.agent.uikit.layout.LocalUiWindow
import com.zmf.agent.uikit.layout.UiWindowProvider
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * 聊天页的外壳：底色 → 横向安全区 → 窗口度量 → 抽屉形态（覆盖式 / 横屏常驻）→ [ChatMainContent]。
 *
 * 这一层只负责「抽屉以什么形态出现」，不包含聊天列、轨迹页、页面层与浮层；
 * 内容侧的窗格切换见 [ChatMainContent]。两层拆分后，竖屏覆盖式与横屏常驻式
 * 只在这里分流，内容侧完全复用同一份实现。
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

    // 根底色：窗口为 edgeToEdge，缺少该层时页面滑动的余量会透出系统窗口底色，
    // 二级页透明处也会露出下方重绘内容。
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 横屏刘海、挖孔与侧边手势条：只消费水平安全区，纵向仍由各页面自己的 inset 处理
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                )
        ) {
            UiWindowProvider { window ->
                if (window.permanentDrawer) {
                    // 横屏宽窗口：抽屉常驻在左，不遮罩、不位移；内容区扣除抽屉宽度后独立排版
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.width(window.drawerWidth).fillMaxHeight()) {
                            ChatDrawerPane(
                                viewModel = viewModel,
                                ui = ui,
                                context = context,
                                themeMode = themeMode,
                                onThemeModeChange = onThemeModeChange
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            CompositionLocalProvider(
                                LocalUiWindow provides window.withinPane(
                                    window.contentWidth,
                                    window.paneHeightDp
                                )
                            ) {
                                ChatMainContent(
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
                                    uiScale = uiScale,
                                    onUiScaleChange = onUiScaleChange,
                                    onPickFile = onPickFile,
                                    onLogLaunch = onLogLaunch,
                                    onImportLaunch = onImportLaunch,
                                    splitTrace = window.traceSplitPane
                                )
                            }
                        }
                    }
                } else {
                    SaltDrawer(
                        open = drawerOpen,
                        onOpenChange = { drawerOpen = it },
                        // 面板宽度按窗口收敛：320dp 窗口为 260dp，393dp 及以上为现行的 304dp
                        panelWidth = window.drawerWidthCover,
                        // 抽屉在所有一级与二级页均可拉出；计划、浏览、预览三个独立页例外——
                        // 它们自带横滑内容或整屏正文，右滑不该把抽屉拉出来。包管理的 pager 通过 nestedScroll 衔接抽屉进度。
                        gestureEnabled = drawerOpen || page == null ||
                            (page != AppPage.PLAN && page != AppPage.BROWSER && page != AppPage.PREVIEW),
                        onSwipeLeft = null,
                        interceptRightSwipe = page == AppPage.PACKAGE && pagerAtFirstPage,
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
                        ChatMainContent(
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
                            uiScale = uiScale,
                            onUiScaleChange = onUiScaleChange,
                            onPickFile = onPickFile,
                            onLogLaunch = onLogLaunch,
                            onImportLaunch = onImportLaunch,
                            splitTrace = false
                        )
                    }
                }
            }
        }
    }
}
