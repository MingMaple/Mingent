package com.zmf.agent.app.navigation

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.feature.chat.ChatItem
import com.zmf.agent.feature.chat.TracePage
import com.zmf.agent.uikit.components.salt.SaltDrawer
import com.zmf.agent.uikit.components.salt.SaltFullPane
import com.zmf.agent.core.prefs.ThemeMode
import kotlinx.coroutines.CoroutineScope
import java.io.File
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
 * 这一层只负责嵌套关系与层序，不含业务判断。参数携带 ChatScreen 计算好的派生值，
 * 以及三个外部入口（选择文件、下载日志、导入），层内不直接使用 SAF。
 * 抽屉位于 content 子树、页面层按顺序叠放在同一个 Box 中，这两点决定横滑与滑入动画的表现。
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

    // 根底色：窗口为 edgeToEdge，缺少该层时页面滑动的余量会透出系统窗口底色，
    // 二级页透明处也会露出下方重绘内容。
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(modifier = Modifier.fillMaxSize()) {
        SaltDrawer(
            open = drawerOpen,
            onOpenChange = { drawerOpen = it },
            // 抽屉在所有一级与二级页均可拉出；仅计划页与内置浏览器例外，因其内部包含横滑内容。
            // 包管理与会话列表的 pager 通过 nestedScroll 衔接抽屉进度。
            gestureEnabled = drawerOpen || page == null ||
                (page != AppPage.PLAN && page != AppPage.BROWSER),
            onSwipeLeft = null,
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
            // 轨迹页与主页为一对连体平移面板：向左滑动将主页推出左边界、轨迹页从右侧补入，
            // 向右滑动按原路退回。它必须嵌在抽屉 content 内：SaltFullPane 关闭态仅消费向左位移，
            // 向右位移由外层抽屉处理，两个手势互不冲突。
            SaltFullPane(
                open = page == AppPage.TRACE,
                onOpenChange = { open -> page = if (open) AppPage.TRACE else null },
                gestureEnabled = page == null || page == AppPage.TRACE,
                panel = {
                    TracePage(
                        sessionTitle = viewModel.currentTitle(),
                        messages = viewModel.messages,
                        // 缩略图与主页共用同一图片根：两侧解码同一份文件，缺少根目录时轨迹页会显示灰色占位块
                        imageRoot = imageRoot,
                        onBack = { page = null }
                    )
                }
            ) {
            // 页面层必须位于 content 子树：若与 SaltDrawer 平级，Compose 的指针命中仅传递至最上层兄弟节点，
            // 抽屉根 Box 上的整屏 draggable 无法接收事件，二级页横滑无法拉出抽屉。
            Box(modifier = Modifier.fillMaxSize()) {
        // 查看器打开时将会话内容整体模糊（API 31 及以上生效），查看器仅负责半透明底
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // 取 16dp 而非 24dp：模糊后仍需辨认下方文字轮廓，24dp 会使整屏变为纯色
                .then(if (viewer != null) Modifier.blur(16.dp) else Modifier),
            color = MaterialTheme.colorScheme.background
        ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
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
                            subAgentCount = subAgentEntries.size,
                            subAgentRunning = subAgentRunning,
                            onSubAgentClick = { subAgentPanelOpen = !subAgentPanelOpen },
                            plans = viewModel.plans,
                            // 进入页面前重新读取一次：模型可能刚提交新计划，预览需基于最新一份
                            onPlanClick = {
                                viewModel.reloadPlans()
                                page = AppPage.PLAN
                            },
                            // 仅当存在存活网页时显示入口图标
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

        // 子任务面板：位于全屏页之下的浮层
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
        }
        }
    }
}
