package com.zmf.agent.feature.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
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
 * 链接路由。其仅读写状态、不产出布局，因此可整体从 [ChatScreen] 拆出。
 *
 * 迁移时未改动任何逻辑：每条 effect 的 key、`return@` 的让位条件及取舍理由均原样保留。
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

    // 记录被清空（或全部清除）时收起面板：胶囊已消失，卡片不应继续显示
    LaunchedEffect(subAgentEntries.isEmpty()) {
        if (subAgentEntries.isEmpty()) {
            subAgentPanelOpen = false
        }
    }

    // 每次打开导出弹窗均重新统计：用户可能在两次导出之间安装了大量软件包
    LaunchedEffect(showExportDialog) {
        if (showExportDialog) {
            terminalBytes = withContext(Dispatchers.IO) { BackupArchive.terminalSectionBytes(context) }
        }
    }

    // 从通知进入：切换一次后清除 id，界面重组时避免重复切换
    // （switchSession 对「已是当前会话」本为空操作，此处仅避免每帧调用一次）
    var pendingSession by remember(initialSessionId) { mutableStateOf(initialSessionId) }
    // startupReady 作为 key：读盘完成前会话表为空，此时 switchSession 找不到目标会直接丢弃这次切换
    LaunchedEffect(pendingSession, viewModel.startupReady) {
        if (!viewModel.startupReady) return@LaunchedEffect
        val id = pendingSession
        if (id != null) {
            // 点击通知需先返回主页：用户可能正停留在设置 / 轨迹 / 浏览器等子页，
            // 仅切换会话而不换页，看到的仍为原页面
            page = null
            drawerOpen = false
            viewModel.switchSession(id)
            pendingSession = null
        }
    }

    // 网页随会话切换：切换会话时将标签挂起并分别挂回各自会话。
    // 切回时仍存在（WebView 不销毁），进程被终止后自然清空，顶栏网页图标随之消失。
    LaunchedEffect(viewModel.currentSessionId) {
        BrowserState.switchTo(viewModel.currentSessionId)
    }

    // 返回前台时从磁盘重新读取会话；输入法有意不在此收起，切后台再返回时其应仍在输入框上。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // 启动时这次 ON_RESUME 早于读盘完成：loadInitial 本身就会读盘，跳过以免重复整表解析
                Lifecycle.Event.ON_RESUME -> if (viewModel.startupReady) viewModel.reloadSessionsFromDisk()
                // 退至后台时落盘一次：产品约定为「按返回仅进入后台、永不终止后台」，
                // 但系统仍可能在后台回收进程，因此需在此时写入会话。
                Lifecycle.Event.ON_STOP -> viewModel.persistForBackground("on-stop")
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 悬浮小窗需读取当前会话：将 ViewModel 的窄口实现挂到桥上，离开页面时移除。
    // 注销仅在「桥上仍为自身」时清空，避免覆盖其他位置后注册的实例。
    val chatPort = remember(viewModel) { ChatPortAdapter(viewModel) }
    DisposableEffect(chatPort) {
        ChatBridge.port = chatPort
        onDispose {
            if (ChatBridge.port === chatPort) ChatBridge.port = null
        }
    }

    // 抽屉与任何二级页均不应保留输入法：进入前先清除焦点并收起键盘
    LaunchedEffect(drawerOpen, page) {
        if (drawerOpen || page != null) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    // 计划全部删除后自动返回主页：页面已无任何可查看内容，保留空白层易被误认为无响应
    LaunchedEffect(page, viewModel.plans.isEmpty()) {
        if (page == AppPage.PLAN && viewModel.plans.isEmpty()) page = null
    }

    // 模型刚提交新计划：本轮结束后直接进入计划页，无需用户再次点击右上角。
    // 两条 effect 分工：planIdsKnown 负责识别「本次确实新增了计划」，
    // pendingPlanJump 将跳转延后至本轮结束：工具调用中途跳页会中断仍在流式的回复。
    var planIdsKnown by remember(viewModel.currentSessionId) { mutableStateOf<Set<String>?>(null) }
    var pendingPlanJump by remember(viewModel.currentSessionId) { mutableStateOf(false) }
    LaunchedEffect(viewModel.plans, viewModel.currentSessionId) {
        val ids = viewModel.plans.map { it.id }.toSet()
        val known = planIdsKnown
        planIdsKnown = ids
        // 首次（冷启动 / 切换会话）仅记录现有计划，避免将历史计划误判为「刚生成」而弹出页面
        if (known == null) return@LaunchedEffect
        val freshPending = (ids - known).any { id ->
            viewModel.plans.any { it.id == id && it.actionable }
        }
        if (freshPending) pendingPlanJump = true
    }
    LaunchedEffect(viewModel.isStreaming) {
        if (viewModel.isStreaming || !pendingPlanJump) return@LaunchedEffect
        pendingPlanJump = false
        // 仅从主页跳转：用户正停留在设置 / 终端时，不应被新计划强制切换页面
        if (page == null) page = AppPage.PLAN
    }

    // 链接的落点：注册期间，回复中（含用户气泡、子智能体页）所有 http/https 链接
    // 均进入内置浏览页。未注册时 LinkRouter.open 返回 false，不产生任何行为：
    // 不可退回弹出系统浏览器，否则等同于将用户移出 App。
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
 * 该顺序即为行为本身，因此整体迁移、不重新排序。
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
    var imageMenuVisible by ui.imageMenuVisible
    var selectionVisible by ui.selectionVisible
    var trashRefreshKey by ui.trashRefreshKey
    var contextHubFromHome by ui.contextHubFromHome

    // 主页按返回 = **仅退出至桌面（进入后台），绝不结束 Activity**。ChatViewModel 为 remember 于 Compose 中的普通对象，Activity 销毁后即消失，尚未落盘的消息随之丢失，这正是此前「按返回键丢失尾部消息」的根本原因。
    // 注册位置：该条在**最前**注册 = 优先级最低；更具体的返回（抽屉 / 二级页 / 浮层）仍先拦截，均不拦截时才由该条处理。
    BackHandler(enabled = true) {
        viewModel.persistForBackground("back-to-home")
        val activity = findActivity(context)
        if (activity != null) {
            activity.moveTaskToBack(true)
        } else {
            // 无法获取 Activity（极端情况）时发送 HOME intent，效果相同：返回桌面、保留进程
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
    // 浏览页的返回先走网页历史，退到头才回主页（网页实例留着，返回不关闭网页）。
    // 该功能原由顶栏的「‹」承担，但其无历史时成为不可点击的箭头，看似故障，
    // 已从顶栏移除，改由系统返回统一处理。
    BackHandler(enabled = page == AppPage.BROWSER) {
        if (!BrowserHolder.goBack()) page = null
    }
    BackHandler(enabled = page == AppPage.WORKSPACE) { page = null }
    BackHandler(enabled = page == AppPage.SUBAGENT) { page = null }
    // 结构：设置首页 → 通用设置 / 模型 / 长期记忆 / 上下文洞察 / 上下文配置 / 包管理 / 数据，
    // 通用设置 → 权限、定时任务；数据 → 日志、归档。
    // 每一级仅退回其上一级，不可跳级。
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
    // 洞察页的返回需回到进入时的层级：自主页输入框进入则退回主页，自设置进入则退回设置
    BackHandler(enabled = page == AppPage.CONTEXT_HUB) {
        page = if (contextHubFromHome) null else AppPage.SETTINGS
    }
    BackHandler(enabled = page == AppPage.CONTEXT_CONFIG) { page = AppPage.SETTINGS }
    BackHandler(enabled = page == AppPage.ABOUT) { page = AppPage.SETTINGS }
    BackHandler(enabled = page == AppPage.SETTINGS_DATA) { page = AppPage.SETTINGS }
    BackHandler(enabled = page == AppPage.SETTINGS) { page = null }
    BackHandler(enabled = subAgentPanelOpen) { subAgentPanelOpen = false }
    // 图片查看器的返回键由 ImageViewer 自身接管：此处置空会跳过「缩回缩略图」的退场动画
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    // 「本轮用量」的返回键由 SaltPopover 自行接管：置空会让面板瞬间消失，跳过退场动画
    BackHandler(enabled = imageMenuVisible) { imageMenuVisible = false }
    BackHandler(enabled = selectionVisible) { selectionVisible = false }
}
