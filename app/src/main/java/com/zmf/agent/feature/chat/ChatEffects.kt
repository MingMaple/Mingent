package com.zmf.agent.feature.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.infra.backup.BackupArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.SoftwareKeyboardController
import com.zmf.agent.capability.runtime.ChatBridge
import com.zmf.agent.uikit.markdown.LinkRouter


/**
 * 聊天页的副作用集合：通知进会话、前后台落盘、小窗桥、抽屉/二级页收键盘、计划自动跳转、
 * 链接路由。它们只读写状态、不产出布局，所以能整体从 [ChatScreen] 搬走。
 *
 * 搬家时一条逻辑都没改：每条 effect 的 key、`return@` 的让位条件、注释里的取舍理由原样保留。
 */
@Composable
internal fun ChatAppEffects(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    context: Context,
    lifecycleOwner: LifecycleOwner,
    focusManager: FocusManager,
    keyboard: SoftwareKeyboardController?,
    initialSessionId: String?,
    subAgentEntries: List<SubAgentRegistry.Entry>
) {
    var page by ui.page
    var drawerOpen by ui.drawerOpen
    var subAgentPanelOpen by ui.subAgentPanelOpen
    var showExportDialog by ui.showExportDialog
    var terminalBytes by ui.terminalBytes

    // 记录被清空（或全部被清掉）就把面板收起：胶囊已经消失，卡片不该继续挂着
    LaunchedEffect(subAgentEntries.isEmpty()) {
        if (subAgentEntries.isEmpty()) {
            subAgentPanelOpen = false
        }
    }

    // 每次打开导出弹窗都重量一次：用户可能在两次导出之间用 apt 装了不少东西
    LaunchedEffect(showExportDialog) {
        if (showExportDialog) {
            terminalBytes = withContext(Dispatchers.IO) { BackupArchive.terminalSectionBytes(context) }
        }
    }

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

    // 悬浮小窗要读到当前会话：把 ViewModel 的窄口实现挂到桥上，离开页面时摘掉。
    // 注销只在「桥上还是自己」时才清空，避免把别处后注册的实例顶掉。
    val chatPort = remember(viewModel) { ChatPortAdapter(viewModel) }
    DisposableEffect(chatPort) {
        ChatBridge.port = chatPort
        onDispose {
            if (ChatBridge.port === chatPort) ChatBridge.port = null
        }
    }

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
}

/**
 * 返回键拦截链：按「最具体」到「最宽」的顺序注册，最后注册的优先级最高。
 *
 * 选择页 > 长按小窗 > 抽屉 > 二级页（回各自上一级）> 设置首页 > 一级页 > 主页退到桌面。
 * 这一串的顺序就是行为本身，所以整块搬、不重排。
 */
@Composable
internal fun ChatBackHandlers(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    context: Context
) {
    var page by ui.page
    var drawerOpen by ui.drawerOpen
    var subAgentPanelOpen by ui.subAgentPanelOpen
    var viewer by ui.viewer
    var turnUsage by ui.turnUsage
    var imageMenuVisible by ui.imageMenuVisible
    var selectionVisible by ui.selectionVisible
    var trashRefreshKey by ui.trashRefreshKey
    var contextHubFromHome by ui.contextHubFromHome

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
    // 通用设置 → 权限、定时任务；数据 → 日志、归档。
    // 每一级只退回到它的上一级，不能跳级。
    BackHandler(enabled = page == AppPage.TRACE) { page = null }
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
}
