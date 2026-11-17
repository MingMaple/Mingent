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
import com.zmf.agent.capability.subagent.SubAgentNotices
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.capability.subagent.SubAgentRunner
import com.zmf.agent.infra.backup.BackupArchive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.SoftwareKeyboardController
import com.zmf.agent.capability.runtime.ChatBridge
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.SharePayload
import com.zmf.agent.core.prefs.ShareTargetPrefs
import com.zmf.agent.core.prefs.UiStateStore
import com.zmf.agent.uikit.common.NoticeCenter
import com.zmf.agent.uikit.markdown.LinkRouter


/**
 * 聊天页的副作用集合：通知进会话、系统分享受理、前后台落盘、小窗桥、抽屉/二级页收键盘、
 * 计划自动跳转、链接路由。其仅读写状态、不产出布局，因此可整体从 [ChatScreen] 拆出。
 *
 * 迁移时未改动任何逻辑：每条 effect 的 key、`return@` 的让位条件及取舍理由均原样保留。
 */
@Composable
internal fun ChatAppEffects(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    context: Context,
    scope: CoroutineScope,
    lifecycleOwner: LifecycleOwner,
    focusManager: FocusManager,
    keyboard: SoftwareKeyboardController?,
    initialSessionId: String?,
    initialShare: SharePayload?,
    onShareConsumed: () -> Unit,
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

    // 系统分享：受理一次（去重、暂存），随后进入**分享模式**。
    //
    // 顺序是「先切会话，再导入」：导入要解码压缩多张图片，先导入再切换会让用户在原会话里停留数秒、
    // 之后整块跳转。目标规则：1 小时内分享过且那条会话还在 → 回到它（用户 2026-11-15 定），
    // 否则默认工作区新建会话。生成回复期间会话层会拦下切换（流式结果写的是当前会话），
    // 此时留在当前会话，发送按「繁忙时发送行为」执行。
    var pendingShare by remember(initialShare) { mutableStateOf(initialShare) }
    LaunchedEffect(pendingShare, viewModel.startupReady) {
        val payload = pendingShare ?: return@LaunchedEffect
        if (!viewModel.startupReady) return@LaunchedEffect
        // 只受理一次：先失效载荷再受理，受理期间的重组不会再次发起
        pendingShare = null
        onShareConsumed()
        // 受理动作放进 composition 级作用域：本 effect 的 key 含 pendingShare，置空会重启它，
        // 若直接在 effect 里落盘，IO 返回时可能已被取消——表现为「分享进来没有反应」。
        scope.launch {
            // 分享进来先回主页：用户可能正停留在设置 / 轨迹等子页；各类浮层也一并收起
            page = null
            drawerOpen = false
            subAgentPanelOpen = false
            ui.composerPanel.dismiss()
            ui.viewer.value = null
            ui.imageMenuVisible.value = false
            ui.selectionVisible.value = false
            ui.turnUsage.value = null

            // 目标：同一小时窗口内回到上一次分享的会话；该会话已删除或已归档时按新分享处理
            val recent = ShareTargetPrefs.recentSession(context)
            val recentExists = recent != null &&
                viewModel.sessionList.any { it.id == recent && !it.archived }
            if (recentExists) {
                viewModel.switchSession(recent)
            } else {
                // 与「手动新建会话」完全同一套动作：切到默认工作区并新建空会话，会话区就是普通聊天界面
                val defaultSpace = viewModel.workspaceList.firstOrNull { it.isDefault }?.id
                if (defaultSpace != null && viewModel.currentWorkspaceId != defaultSpace) {
                    viewModel.switchWorkspace(defaultSpace)
                }
                viewModel.newSession()
            }
            // 分享输入框立刻占位；导入完成前用「正在导入…」占着底部槽位，发送键不可用
            ui.shareDraft.value = null
            ui.shareSent.value = false
            ui.sharePreparing.value = true
            ui.shareVisible.value = true
            // 记下本次落地会话：切换被生成中的回合拦下时，实际用的仍是当前会话，按实际记
            ShareTargetPrefs.remember(context, viewModel.currentSessionId)

            // 图片与长文先落进与任何工作区无关的中转目录：发送时才按目标会话落盘，
            // 用户随后在面板或抽屉里改目标，不会留下挂在别的工作区的文件
            val draft = withContext(Dispatchers.IO) {
                runCatching { ShareIntake.stage(context, payload) }
                    .onFailure { FileLog.log("share", "落盘失败：" + (it.message ?: it.javaClass.simpleName)) }
                    .getOrNull()
            }
            // 导入期间用户可能已经取消：此时不再把内容塞回界面，整批中转文件直接丢弃
            if (!ui.shareVisible.value) {
                ui.sharePreparing.value = false
                draft?.let { ShareIntake.discard(it) }
                return@launch
            }
            if (draft == null) {
                // 全部图片读取失败且没有正文时没有任何可发送的内容，说明一句即可，退出分享模式
                ui.sharePreparing.value = false
                ui.shareVisible.value = false
                NoticeCenter.show("未读到可发送的分享内容")
                return@launch
            }
            ui.shareDraft.value = draft
            ui.sharePreparing.value = false
        }
    }

    // 面板退场结束后再处理本次内容：退场动画期间面板仍要画出附件与正文。
    // 已发出的批次整批删除（附件已复制进目标工作区）；未发出的把附件移交到输入区待发项后再删，
    // 用户关掉面板不等于丢掉刚分享进来的文件。
    // 关闭分享不改变当前会话：用户停在目标会话里，不回退到分享前的那一条。
    val shareVisibleNow by ui.shareVisible
    LaunchedEffect(shareVisibleNow) {
        if (shareVisibleNow) return@LaunchedEffect
        delay(320)
        if (ui.shareVisible.value) return@LaunchedEffect
        ui.sharePreparing.value = false
        val draft = ui.shareDraft.value
        // 「已发出」必须在清空草稿之前读取：清空会让父层重组并复位这个标记
        val sent = ui.shareSent.value
        ui.shareSent.value = false
        ui.shareDraft.value = null
        if (draft != null) viewModel.releaseShareDraft(draft, sent = sent)
    }

    // 网页随会话切换：切换会话时将标签挂起并分别挂回各自会话。
    // 切回时仍存在（WebView 不销毁），进程被终止后自然清空，顶栏网页图标随之消失。
    LaunchedEffect(viewModel.currentSessionId) {
        BrowserState.switchTo(viewModel.currentSessionId)
    }

    // 预览标签同样随会话切换：各自挂起、各自挂回；新会话首次进入时从磁盘读回上次的清单
    LaunchedEffect(viewModel.currentSessionId) {
        PreviewTabs.switchTo(context, viewModel.currentSessionId)
    }

    // ── 界面格局持久化（D3，用户 2026-11-16）──
    // 结构随改随存、比对旧值：停在哪一页（限 PLAN / PREVIEW / BROWSER）、选中哪份计划、
    // 预览停在哪条标签、浏览页开着哪些地址。滚动位置与窗口几何一律不存（不追像素级恢复）。
    // 以下四个读取都在组合里完成：它们的任何变化都会重启这条 effect，也就是「值变了才写」。
    val layoutSessionId = viewModel.currentSessionId
    val layoutPage = page
    val layoutPlanId = viewModel.selectedPlanId
    val layoutPreviewPath = PreviewTabs.activePath
    val layoutBrowserUrls = BrowserState.urlsFor(layoutSessionId)

    /** 落盘或清键：主页 / 设置层不留键，防止陈旧记录在下次启动时误恢复。 */
    fun persistLayout(
        id: String,
        current: AppPage?,
        planId: String?,
        previewPath: String?,
        urls: List<String>
    ) {
        if (id.isEmpty()) return
        val stored = current
            ?.takeIf { it == AppPage.PLAN || it == AppPage.PREVIEW || it == AppPage.BROWSER }
        if (stored == null) {
            UiStateStore.forget(context, id)
            return
        }
        UiStateStore.save(
            context = context,
            sessionId = id,
            page = stored.name,
            planId = if (stored == AppPage.PLAN) planId else null,
            previewPath = if (stored == AppPage.PREVIEW) previewPath else null,
            browserUrls = if (stored == AppPage.BROWSER) urls else emptyList()
        )
    }

    // 值变即同步落盘：apply 的异步写在 am force-stop 时可能整段丢失（D3 实测），
    // 而 force-stop 不投递 ON_STOP，不能把可靠性寄托在退后台那一刻。commit 放到 IO 线程执行。
    LaunchedEffect(layoutSessionId, layoutPage, layoutPlanId, layoutPreviewPath, layoutBrowserUrls) {
        if (!viewModel.startupReady) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            persistLayout(layoutSessionId, layoutPage, layoutPlanId, layoutPreviewPath, layoutBrowserUrls)
        }
    }

    // 启动后首次进入某条会话时，恢复它上次留下的格局；失效（计划已删光、标签已清空、
    // 地址一个都打不开）就当作没有记录，留在主页并顺带清掉键。
    // 本进程里进过的会话不再读盘：内存里那份页面表才是最新的。
    var layoutRestored by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(viewModel.startupReady, viewModel.currentSessionId) {
        if (!viewModel.startupReady) return@LaunchedEffect
        val id = viewModel.currentSessionId
        if (id.isEmpty() || id in layoutRestored) return@LaunchedEffect
        layoutRestored = layoutRestored + id
        val saved = UiStateStore.load(context, id) ?: return@LaunchedEffect
        var restored = saved.page?.let { runCatching { AppPage.valueOf(it) }.getOrNull() }
        when (restored) {
            AppPage.PLAN -> {
                // 计划已全部删除：计划页没有可看的内容
                if (viewModel.plans.isEmpty()) {
                    restored = null
                } else if (saved.planId != null && viewModel.plans.any { it.id == saved.planId }) {
                    viewModel.selectPlan(saved.planId)
                }
            }
            AppPage.PREVIEW -> {
                // 标签清单已读回（上文的 switchTo 先执行）：回到上次打开的标签
                if (PreviewTabs.count == 0) {
                    restored = null
                } else if (saved.previewPath != null) {
                    PreviewTabs.activate(saved.previewPath)
                }
            }
            AppPage.BROWSER -> {
                // WebView 实例不落盘：按留下的地址重开标签；一个都开不出来就当作没有记录
                saved.browserUrls.forEach { url -> BrowserHolder.open(context, url) }
                if (BrowserState.tabs.isEmpty()) restored = null
            }
            else -> {}
        }
        ui.setPage(id, restored)
    }

    // 会话从列表里消失（归档页删除 / session_delete 工具）：内存里它名下的运行态一并丢弃，
    // 避免 id 悬留或复用后读到别人的页面、计划与标签。磁盘上的格局键由各删除点自行清理。
    var knownSessionIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(viewModel.sessionList) {
        val ids = viewModel.sessionList.map { it.id }.toSet()
        val known = knownSessionIds
        knownSessionIds = ids
        if (known == null) return@LaunchedEffect
        for (gone in known - ids) {
            ui.forgetSession(gone)
            viewModel.forgetPlanState(gone)
            PreviewTabs.forgetSession(context, gone)
            BrowserState.forgetSession(gone)
            // 子智能体：清掉该会话的并发计数与未转交的结论通知（正在跑的协程不受影响）
            SubAgentRunner.forgetSession(gone)
            SubAgentNotices.forgetSession(gone)
        }
    }

    // 返回前台时从磁盘重新读取会话；输入法有意不在此收起，切后台再返回时其应仍在输入框上。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // 启动时这次 ON_RESUME 早于读盘完成：loadInitial 本身就会读盘，跳过以免重复整表解析
                Lifecycle.Event.ON_RESUME -> if (viewModel.startupReady) viewModel.reloadSessionsFromDisk()
                // 退至后台时落盘一次：产品约定为「按返回仅进入后台、永不终止后台」，
                // 但系统仍可能在后台回收进程，因此需在此时写入会话。
                Lifecycle.Event.ON_STOP -> {
                    viewModel.persistForBackground("on-stop")
                    // 界面格局再同步刷盘一次：值变时已写过，此处只兜住「状态变了但 effect 尚未执行」的窗口
                    persistLayout(
                        viewModel.currentSessionId,
                        ui.page.value,
                        viewModel.selectedPlanId,
                        PreviewTabs.activePath,
                        BrowserState.urlsFor(viewModel.currentSessionId)
                    )
                }
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
            // 只放行 http/https：javascript:/file:/intent: 等 scheme 一律不加载，记一行日志后忽略
            if (!BrowserHolder.isHttpUrl(url)) {
                FileLog.log("browser", "忽略非 http/https 链接: " + url)
                false
            } else {
                BrowserHolder.open(context, url)
                // 从预览页里的链接进入网页：记住来路，返回时回到刚才那一页（阅读位置原样保留）；
                // 从主页进入则没有来路，返回仍回主页
                ui.browserReturnPage.value = ui.page.value.takeIf { it == AppPage.PREVIEW }
                page = AppPage.BROWSER
                true
            }
        }
        onDispose { LinkRouter.onOpen = null }
    }
}

/**
 * 返回键拦截链：按「最具体」到「最宽」的顺序注册，最后注册的优先级最高。
 *
 * 分享面板 > 选择页 > 长按小窗 > 抽屉 > 二级页（回各自上一级）> 设置首页 > 一级页 > 主页退到桌面。
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
    var shareVisible by ui.shareVisible
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

    // 分享输入框只是主页输入区槽位的替换，不是覆盖层：抽屉、二级页、各种浮层都盖在它上面。
    // 因此它紧随「退到桌面」注册（优先级仅高于主页退出）：上面还有任何一层打开时，
    // 返回先关那一层；全都收起后才关闭分享，此时正常输入框出现，且**不改当前会话**。
    BackHandler(enabled = shareVisible) { shareVisible = false }

    // 返回键按「最具体」到「最宽」的顺序拦截，最后注册的优先级最高：
    // 选择页 > 长按小窗 > 抽屉 > 二级页（回各自上一级）> 设置首页 > 一级页 > 分享输入框 > 主页时退到桌面
    // 浏览页的返回先走网页历史，退到头才回主页（网页实例留着，返回不关闭网页）。
    // 该功能原由顶栏的「‹」承担，但其无历史时成为不可点击的箭头，看似故障，
    // 已从顶栏移除，改由系统返回统一处理。
    BackHandler(enabled = page == AppPage.BROWSER) {
        if (!BrowserHolder.goBack()) page = ui.browserExitTarget()
    }
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
    // 预览页可叠层：md 正文里的文件链接在预览页内部逐层打开。
    // 系统返回先退回上一层预览，回到第一层后才回主页；标签本身不关闭
    BackHandler(enabled = page == AppPage.PREVIEW) {
        if (!PreviewTabs.back()) page = null
    }
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
