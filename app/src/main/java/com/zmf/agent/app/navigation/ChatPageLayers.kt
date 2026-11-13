package com.zmf.agent.app.navigation
import com.zmf.agent.core.llm.ApiDefaults

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.zmf.agent.capability.config.ApiConfig
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.infra.api.BalanceClient
import com.zmf.agent.feature.settings.AboutPage
import com.zmf.agent.feature.settings.LogsPage
import com.zmf.agent.feature.settings.ArchivePage
import com.zmf.agent.feature.settings.TrashPage
import com.zmf.agent.feature.settings.PackagePage
import com.zmf.agent.feature.settings.PermissionPage
import com.zmf.agent.feature.schedule.SchedulePage
import com.zmf.agent.feature.settings.ContextConfigPage
import com.zmf.agent.feature.settings.ContextInsightPage
import com.zmf.agent.feature.settings.MemoryPage
import com.zmf.agent.feature.settings.DataSettingsPage
import com.zmf.agent.feature.settings.GeneralSettingsPage
import com.zmf.agent.feature.settings.ModelSettingsPage
import com.zmf.agent.feature.settings.SettingsHomePage
import com.zmf.agent.core.store.UsageLedger
import com.zmf.agent.capability.prompt.PromptStore
import com.zmf.agent.uikit.layout.LocalUiWindow
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.core.prefs.UiScaleStore
import kotlinx.coroutines.launch
import com.zmf.agent.feature.chat.BrowserPage
import com.zmf.agent.feature.chat.WorkSpacePage
import com.zmf.agent.feature.chat.AppPage
import com.zmf.agent.feature.chat.ChatUiState
import com.zmf.agent.feature.chat.ChatViewModel
import com.zmf.agent.feature.chat.PlanPage
import com.zmf.agent.feature.chat.PreviewPage
import com.zmf.agent.feature.chat.SubAgentPage
import com.zmf.agent.uikit.common.NoticeCenter
import com.zmf.agent.uikit.common.NoticeTone

/**
 * 二级页的统一入场：横向滑入 + 内容列限宽并居中。
 *
 * 背景由外层铺满，页面内容限制在 640dp 以内：平板竖屏与横屏宽窗口下，
 * 卡片与行长仍保持与手机近似的比例，而不是被拉伸到整屏宽。
 * 背景与页面同属一个动画节点，滑入时整块一起移动，不会露出下层主页。
 */
@Composable
private fun PageSlide(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(Motion.spring()) { it },
        exit = slideOutHorizontally(tween(220)) { it },
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = LocalUiWindow.current.contentMaxWidth)
                    .fillMaxHeight()
            ) {
                content()
            }
        }
    }
}

/**
 * 聊天页之上的二十层全屏页面：设置首页 / 数据 / 通用设置 / 模型 / 权限 / 包管理 / 日志 /
 * 定时任务 / 记忆 / 上下文洞察 / 上下文配置 / 关于 / 归档 / 回收站 / 工作区 / 会话列表 /
 * 计划 / 浏览器 / 子智能体详情 …
 *
 * 每页各一层 [AnimatedVisibility]，下层页面保留原位，中间不会闪现主页画面。全部为同一个 Box 的
 * 直接子节点，按代码顺序叠放；因此必须在同一组合内一次性声明，拆分为多次调用会改变层序，
 * 滑入动画时会露出下层页面。
 */
@Composable
internal fun ChatPageLayers(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    uiScale: Float,
    onUiScaleChange: (Float) -> Unit,
    subAgentEntries: List<SubAgentRegistry.Entry>,
    /** 导入位置由调用方选择（SAF），本层仅负责通知「需要弹出选择器」。 */
    onImportLaunch: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by ui.page
    var trashRefreshKey by ui.trashRefreshKey
    var contextHubFromHome by ui.contextHubFromHome
    var extraPrompt by ui.extraPrompt
    var overridePrompt by ui.overridePrompt
    var pagerAtFirstPage by ui.pagerAtFirstPage
    var subAgentDetailId by ui.subAgentDetailId
    var balance by ui.balance
    var balanceBusy by ui.balanceBusy
    var orphanStatus by ui.orphanStatus
    var showExportDialog by ui.showExportDialog
    val openContextHub: (Boolean) -> Unit = ui::openContextHub
    val forkWithReload: (() -> Unit) -> Unit = { action -> ui.forkWithReload(scope, action) }
    // 上下文页所需的全局总账快照：进入页面时读取一次磁盘（UsageLedger.load 自带「只读一次」机制）
    val lastLedger = remember(page) {
        UsageLedger.load(context)
        UsageLedger.snapshot()
    }
        // 设置、工作区与设置下的所有页面均为独立全屏页，每页各一层 AnimatedVisibility：
        // 下层页面保留原位，中间不会闪现主页画面；页面自身不透明，因此仅执行横向滑动。
        PageSlide(
            // 所有设置层页面也需计入该层：滑入时设置首页必须保留原位，否则下方会露出主页
            visible = page == AppPage.SETTINGS || page == AppPage.SETTINGS_DATA ||
                page == AppPage.MODEL ||
                page == AppPage.PERMISSION || page == AppPage.PACKAGE || page == AppPage.LOGS ||
                page == AppPage.SCHEDULE ||
                page == AppPage.GENERAL || page == AppPage.MEMORY ||
                page == AppPage.CONTEXT_HUB || page == AppPage.CONTEXT_CONFIG ||
                page == AppPage.ABOUT || page == AppPage.ARCHIVE
        ) {
            SettingsHomePage(
                onBack = { page = null },
                onGeneral = { page = AppPage.GENERAL },
                onModel = { page = AppPage.MODEL },
                onMemory = { page = AppPage.MEMORY },
                onContextInsight = { openContextHub(false) },
                onContextConfig = { page = AppPage.CONTEXT_CONFIG },
                onPackage = { page = AppPage.PACKAGE },
                onData = { page = AppPage.SETTINGS_DATA },
                onAbout = { page = AppPage.ABOUT }
            )
        }

        // 数据层需保留至其三级页（日志、归档）之上：若该层随 SETTINGS_DATA 一同滑出，
        // 三级页滑入时会将下方的「数据」页一并带走，中途露出主页。
        PageSlide(
            visible = page == AppPage.SETTINGS_DATA || page == AppPage.ARCHIVE ||
                page == AppPage.LOGS || page == AppPage.TRASH
        ) {
            DataSettingsPage(
                onBack = { page = AppPage.SETTINGS },
                onExport = { showExportDialog = true },
                onImport = { onImportLaunch() },
                onLogs = { page = AppPage.LOGS },
                onArchive = { page = AppPage.ARCHIVE },
                onTrash = { page = AppPage.TRASH },
                trashRefreshKey = trashRefreshKey,
                archivedCount = viewModel.sessionList.count { it.archived },
                attachmentUsage = viewModel.attachmentUsageText,
                backupStatus = viewModel.backupStatus,
                // 导出与导入的结果显示在数据页，不附加到主页状态行
                backupNotice = viewModel.backupNotice,
                orphanStatus = orphanStatus,
                onRefreshAttachment = { viewModel.refreshAttachmentUsage() },
                onCleanOrphans = { viewModel.cleanOrphanImages { message -> orphanStatus = message } }
            )
        }

        PageSlide(visible = page == AppPage.ARCHIVE) {
            ArchivePage(
                workspaces = viewModel.workspaceList,
                archived = viewModel.sessionList.filter { it.archived },
                onBack = { page = AppPage.SETTINGS_DATA },
                onRestore = { id ->
                    // 原工作区已删除时会话归入「未分类」：这是用户看不到的降级，必须说明
                    val demoted = viewModel.unarchiveSession(id)
                    if (demoted) {
                        NoticeCenter.show("已恢复，归入「未分类」", NoticeTone.Success)
                    } else {
                        NoticeCenter.success("会话已恢复")
                    }
                },
                onDelete = { id ->
                    viewModel.deleteSession(id)
                    NoticeCenter.success("会话已删除")
                },
                deleteNote = { id -> viewModel.deleteSessionExtraNote(id) }
            )
        }

        // 回收站仅对用户开放（D3 主体分离）：没有对应的 Agent 工具，恢复与清空均在此页完成
        PageSlide(visible = page == AppPage.TRASH) {
            TrashPage(
                onBack = {
                    trashRefreshKey++
                    page = AppPage.SETTINGS_DATA
                }
            )
        }

        // 通用设置层同理：权限与定时任务两个三级页滑入时，该层必须保留原位
        PageSlide(
            visible = page == AppPage.GENERAL || page == AppPage.PERMISSION ||
                page == AppPage.SCHEDULE
        ) {
            GeneralSettingsPage(
                onBack = { page = AppPage.SETTINGS },
                onPermission = { page = AppPage.PERMISSION },
                uiScale = uiScale,
                uiScaleLabel = UiScaleStore.label(uiScale),
                onUiScale = { onUiScaleChange(it) },
                busyMode = viewModel.busyMode,
                onBusyModeChange = { mode -> viewModel.applyBusyMode(mode) },
                onSchedule = { page = AppPage.SCHEDULE }
            )
        }

        // 长期记忆：进入页面时刷新一次快照。记忆数据从磁盘读取，页面不应在主线程上访问文件
        PageSlide(visible = page == AppPage.MEMORY) {
            // 页面进入时自行调用 onRefresh，该层不额外刷新（记忆刷新需运行内核，重复一次会产生双倍开销）
            MemoryPage(
                onBack = { page = AppPage.SETTINGS },
                state = viewModel.memoryState,
                onRefresh = viewModel::refreshMemory,
                onSearch = viewModel::searchMemory,
                onRunCycle = viewModel::runMemoryCycle,
                onExport = { path -> viewModel.exportMemory(path) },
                vectorEnabled = viewModel.memoryVectorEnabled,
                vectorBusy = viewModel.memoryVectorBusy,
                vectorStatus = viewModel.memoryVectorStatus,
                vectorFailed = viewModel.memoryVectorFailed,
                onVectorToggle = viewModel::toggleMemoryVector,
                onVectorPanelOpened = viewModel::clearMemoryVectorStatus
            )
        }

        // 上下文洞察：纯只读页面。刷新由页面进入时自行发起（onRefresh），该层不重复调用。
        PageSlide(visible = page == AppPage.CONTEXT_HUB) {
            ContextInsightPage(
                initialSessionId = viewModel.currentSessionId,
                currentSessionId = viewModel.currentSessionId,
                workspaces = viewModel.workspaceList.map { it.id to it.name },
                onBack = { page = if (contextHubFromHome) null else AppPage.SETTINGS },
                loadSession = { viewModel.insightSession(it) },
                loadGlobal = { viewModel.globalInsight(it) }
            )
        }

        PageSlide(visible = page == AppPage.ABOUT) {
            AboutPage(onBack = { page = AppPage.SETTINGS })
        }

        PageSlide(visible = page == AppPage.WORKSPACE) {
            WorkSpacePage(
                workspaces = viewModel.workspaceList,
                currentId = viewModel.currentWorkspaceId,
                streaming = viewModel.isStreaming,
                onPick = { id ->
                    viewModel.switchWorkspace(id)
                    page = null
                },
                onCreate = { name -> viewModel.createWorkspace(name) },
                onDelete = { id -> viewModel.deleteWorkspace(id) },
                onBack = { page = null }
            )
        }

        PageSlide(visible = page == AppPage.MODEL) {
            ModelSettingsPage(
                onBack = { page = AppPage.SETTINGS },
                providers = viewModel.providers,
                currentProviderId = viewModel.currentProviderId,
                onSelectProvider = { id -> viewModel.selectProvider(id) },
                onSaveProvider = { cfg -> viewModel.saveProvider(cfg) },
                onDeleteProvider = { id -> viewModel.deleteProvider(id) },
                // 模型候选跟随当前 provider：仅内置 DeepSeek 使用 ApiDefaults.MODELS 的显示名
                models = viewModel.providers
                    .firstOrNull { it.id == viewModel.currentProviderId }
                    ?.models
                    .orEmpty()
                    .map { id ->
                        id to (ApiDefaults.MODELS.firstOrNull { it.first == id }?.second ?: id)
                    },
                // 思考开关与强度均合并至模型页的行内展开：配置集中一处，修改开关无需再进入下一层
                thinkingEnabled = viewModel.thinkingEnabled,
                thinkingEffort = viewModel.thinkingEffort,
                // ViewModel 仅暴露翻转方法；页面提供目标值，仅当与目标不一致时翻转，避免同一帧内翻转两次
                onThinkingToggle = { on ->
                    if (on != viewModel.thinkingEnabled) viewModel.toggleThinking()
                },
                onPickEffort = { effort -> viewModel.chooseThinkingEffort(effort) },
                // 执行权限与输入区浮层共用 viewModel.permissionLevel 状态，自然双向同步
                permission = viewModel.permissionLevel,
                onPickPermission = { level -> viewModel.setPermission(level) },
                balance = balance,
                balanceBusy = balanceBusy,
                onBalance = {
                    if (!balanceBusy) {
                        balanceBusy = true
                        scope.launch {
                            balance = BalanceClient.fetchBalance(
                                ApiConfig.loadApiKey(context),
                                ApiConfig.loadBaseUrl(context)
                            )
                            balanceBusy = false
                        }
                    }
                }
            )
        }

        // 权限管理：同为设置下的二级页，单独一层，滑入时设置页保留原位
        PageSlide(visible = page == AppPage.PERMISSION) {
            PermissionPage(onBack = { page = AppPage.GENERAL })
        }

        // 上下文配置：提示词两段、压缩策略与摘要，在一页内完成修改。保存即持久化，页面不直接访问存储
        PageSlide(visible = page == AppPage.CONTEXT_CONFIG) {
            ContextConfigPage(
                onBack = { page = AppPage.SETTINGS },
                extraPrompt = extraPrompt,
                overridePrompt = overridePrompt,
                onSaveExtra = { text ->
                    extraPrompt = text
                    PromptStore.saveExtra(context, text)
                    viewModel.refreshContextBreakdown()
                },
                onSaveOverride = { text ->
                    overridePrompt = text
                    PromptStore.saveOverride(context, text)
                    viewModel.refreshContextBreakdown()
                },
                policy = viewModel.contextPolicy,
                snapshot = viewModel.contextSnapshot,
                summary = viewModel.contextSummary,
                onApply = { p -> viewModel.applyContextPolicy(p) },
                onSummarize = { done -> viewModel.summarizeNow(done) },
                onClearSummary = { viewModel.clearContextSummary() },
                onRefresh = { viewModel.refreshContext() },
                // 压缩与裁剪产生的操作记录（操作者、时间与内容）：只读列表，页面自行滚动展示
                events = viewModel.contextEventList
            )
        }

        // 计划：由模型通过 write_plan 写入。存在多份时通过标签栏切换查看，全部删除后自动返回主页
        PageSlide(visible = page == AppPage.PLAN) {
            PlanPage(
                plans = viewModel.plans,
                selectedId = viewModel.selectedPlanId,
                onSelect = { id -> viewModel.selectPlan(id) },
                onDelete = { id -> viewModel.deletePlan(id) },
                // 「继续修改」仅返回主页：代替用户发送语句会将「再看看」变为一次实际发送。
                // 携带 id 进入时，该计划会被标记为「已处理」，按钮随之变灰
                onContinueEdit = { id ->
                    viewModel.continuePlanEdit(id)
                    page = null
                },
                // 「同意执行」仅对当前这一份生效；计划预览不清除，之后仍需逐条回看
                onApprove = { id ->
                    viewModel.approvePlan(id)
                    page = null
                },
                onBack = { page = null }
            )
        }

        // 产物预览：与计划、浏览同层次的一页。卡片、正文里的产物路径、顶栏图标都进这里。
        // 从预览页点网页链接时本层保持可见（浏览页在其上方），返回即回到原来的阅读位置
        PageSlide(
            visible = page == AppPage.PREVIEW ||
                (page == AppPage.BROWSER && ui.browserReturnPage.value == AppPage.PREVIEW)
        ) {
            PreviewPage(
                io = viewModel.currentStorageIo(),
                onBack = { page = null }
            )
        }

        // 内置浏览器：点击回复中的链接即滑入。返回先退网页历史，退到头才回落点：
        // 从预览页进来的回预览页，从主页进来的回主页（页面实例不销毁）
        AnimatedVisibility(
            visible = page == AppPage.BROWSER,
            enter = slideInHorizontally(Motion.spring()) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            BrowserPage(onBack = { page = ui.browserExitTarget() })
        }

        // 包管理：MCP 服务与 Skill 两个标签页（设置下的二级页）
        PageSlide(visible = page == AppPage.PACKAGE) {
            PackagePage(
                registry = viewModel.toolRegistry,
                onBack = { page = AppPage.SETTINGS },
                onChanged = { viewModel.refreshSkillCatalog() },
                // 仅在第一页时抽屉才接管向右位移（第二页需能正常向右翻回第一页）
                onFirstPage = { pagerAtFirstPage = it }
            )
        }

        // 日志：设置下的二级页，单独一层（LogsPage 内部在 IO 线程读取日志，不阻塞该层滑入动画）
        PageSlide(visible = page == AppPage.LOGS) {
            LogsPage(onBack = { page = AppPage.SETTINGS_DATA })
        }

        // 定时任务：一级入口在设置里，单独一层
        PageSlide(visible = page == AppPage.SCHEDULE) {
            SchedulePage(onBack = { page = AppPage.SETTINGS })
        }

        // 子智能体详情：由右上角胶囊卡片进入，可继续发送消息
        PageSlide(visible = page == AppPage.SUBAGENT) {
            SubAgentPage(
                entry = subAgentEntries.firstOrNull { it.id == subAgentDetailId },
                onBack = { page = null },
                onSend = { text ->
                    viewModel.continueSubAgent(subAgentDetailId ?: "", text, scope)
                }
            )
        }

}
