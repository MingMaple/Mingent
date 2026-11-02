package com.zmf.agent.feature.chat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import com.zmf.agent.capability.prompt.PromptStore
import com.zmf.agent.infra.api.BalanceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「本轮用量」伴随面板的入参：[anchor] 为用量按钮在窗口中的位置，[usage] 为本轮的用量记录。
 *
 * 两者必须一并传入：面板数字与「贴附哪一枚按钮」源自同一次点击，分开存储会错位。
 */
internal data class TurnUsageTarget(val usage: TurnUsageInfo, val anchor: Offset)

/**
 * 长按已发送图片时的小窗：仅两项功能：保存当前图片、回到这条提问。
 * 携带 relPath 是因为「长按哪张就保存哪张」，不可用整条消息的图片列表替代。
 */
internal data class ImageMenuTarget(
    val relPath: String,
    /** 这条用户消息在整个列表里的下标，回滚按它定位。 */
    val userIndex: Int,
    val anchor: Offset
)

/**
 * 图片详情的入参：这条消息的全部图片（缩略图顺序 = 选择/发送顺序）与被点开图片的下标。
 *
 * 一并传入而非仅传一张：详情中需支持左右切换查看，仅给一张则无法切换。
 */
internal data class ViewerTarget(val paths: List<String>, val startIndex: Int)

/** 选择文本页要展示的内容。 */
internal data class SelectionTarget(
    val title: String,
    val text: String,
    val reasoning: String
)

/**
 * 独立全屏页面：抽屉直接进的一级页（设置 / 工作区 / 会话列表），以及设置首页下的各功能页。
 */
internal enum class AppPage {
    SETTINGS, WORKSPACE, SETTINGS_DATA, SESSIONS,
    MODEL, PERMISSION, PACKAGE, LOGS, SCHEDULE, SUBAGENT, ARCHIVE, TRASH,
    // 设置首页（SETTINGS）→ 通用设置（GENERAL）/ 模型（MODEL）/ 长期记忆（MEMORY）/
    // 上下文洞察（CONTEXT_HUB）/ 上下文配置（CONTEXT_CONFIG）/ 包管理（PACKAGE）/ 数据（SETTINGS_DATA）；
    // 轨迹为由主页左滑进入的全屏页，不属于设置层。
    // 统计、提示词、思考三页已并入「上下文洞察」与「模型」：同一功能在两处入口易引起对真实来源的疑问。
    GENERAL, MEMORY, CONTEXT_HUB, CONTEXT_CONFIG, TRACE,
    // 关于为只读页（版本 / 开源地址 / 第三方组件），从设置首页最底部的卡片进入
    ABOUT,
    // 计划页由主页右上角按钮进入的独立页（不挂在设置层下），返回即回主页。
    // 目标没有预览页：/goal 走命令解析，状态仅显示为输入框右侧的只读图标
    PLAN,
    // 内置浏览器：回复中的链接点击后进入此页。返回（侧滑/返回键）仅回主页，**页面不关闭**：
    // 顶栏会多出一枚网页图标，点击可返回继续使用（页面实例保留在 BrowserHolder 中，登录态不丢失）。
    BROWSER
}

/**
 * 聊天页的全部界面状态（瞬时态，不落盘）。
 *
 * 存在的理由：原 [ChatScreen] 是 1,800 行的单一函数，二十多个浮层各自 `remember` 于所在层级，
 * 抽成独立 composable 需将这几十个状态逐一从参数表传递。改为集中持有后，子组合仅需
 * 以 `var page by ui.page` 取一个委托，读写直接落到同一份状态上——拆分因此仅为迁移，不改变行为。
 *
 * 字段名与原局部变量**逐字一致**，这是有意为之：迁移过程中正文无需改动。
 * 需要按 key 复位的状态（`pendingSession` / `planIdsKnown` 等）不放在这里，它们跟着各自的
 * `remember(key)` 留在组合里。
 */
internal class ChatUiState(context: Context) {
    /** 当前所在的二级页；null = 主页。 */
    val page: MutableState<AppPage?> = mutableStateOf(null)

    /** 回收站页返回时自增：数据页始终挂在该层之下，不更换 key 则该行统计不会刷新。 */
    val trashRefreshKey: MutableState<Int> = mutableStateOf(0)

    val drawerOpen: MutableState<Boolean> = mutableStateOf(false)

    /** 上下文洞察页的来源：自主页输入框进入则返回主页，自设置首页进入则返回设置。 */
    val contextHubFromHome: MutableState<Boolean> = mutableStateOf(false)

    /** 抽屉里的「＋ 新建工作区」：抽屉没有输入框，弹一个极简输入框。 */
    val showCreateWorkspace: MutableState<Boolean> = mutableStateOf(false)

    /** 包管理与会话列表两页的内容区为 HorizontalPager，仅停在第一页时抽屉方可拦截向右的位移。 */
    val pagerAtFirstPage: MutableState<Boolean> = mutableStateOf(true)

    val extraPrompt: MutableState<String> = mutableStateOf(PromptStore.loadExtra(context))
    val overridePrompt: MutableState<String> =
        mutableStateOf(PromptStore.loadOverride(context).orEmpty())

    /** 输入栏实测高度：附件面板需浮于其上，固定 dp 会与字号档位不一致。 */
    val inputBarHeightPx: MutableState<Int> = mutableStateOf(0)
    val topBarHeightPx: MutableState<Int> = mutableStateOf(0)

    /** 图片详情：这条消息的全部图片 + 起点下标。 */
    val viewer: MutableState<ViewerTarget?> = mutableStateOf(null)

    val subAgentPanelOpen: MutableState<Boolean> = mutableStateOf(false)
    val subAgentDetailId: MutableState<String?> = mutableStateOf(null)

    // 数据备份：导出选项 / 导入确认 / 导入报告
    val showExportDialog: MutableState<Boolean> = mutableStateOf(false)
    val includeTerminal: MutableState<Boolean> = mutableStateOf(false)
    val includeLogs: MutableState<Boolean> = mutableStateOf(false)
    val includeMemory: MutableState<Boolean> = mutableStateOf(false)

    /** Ubuntu 环境段的真实体积（字节）：0 表示尚未测量。 */
    val terminalBytes: MutableState<Long> = mutableStateOf(0L)
    val importUri: MutableState<Uri?> = mutableStateOf(null)

    /** 「清理未引用附件」的结果：只在这一层显示，页面关掉就没了。 */
    val orphanStatus: MutableState<String?> = mutableStateOf(null)

    /** 余额为「查询后才有」的临时结果，不落盘：仅在模型页显示。 */
    val balance: MutableState<BalanceResult?> = mutableStateOf(null)
    val balanceBusy: MutableState<Boolean> = mutableStateOf(false)

    /** 分叉会话的「短暂空白后重新加载」：分叉完成后屏幕内容闪烁一次。 */
    val forkReloading: MutableState<Boolean> = mutableStateOf(false)

    /** 「本轮用量」伴随面板。 */
    val turnUsage: MutableState<TurnUsageTarget?> = mutableStateOf(null)
    val selectionTarget: MutableState<SelectionTarget?> = mutableStateOf(null)
    val selectionVisible: MutableState<Boolean> = mutableStateOf(false)

    /** 图片长按小窗：内容与可见性分开存储，收起后的 140ms 内内容仍需保留以播放淡出。 */
    val imageMenuTarget: MutableState<ImageMenuTarget?> = mutableStateOf(null)
    val imageMenuVisible: MutableState<Boolean> = mutableStateOf(false)
    val rollbackUserIndex: MutableState<Int?> = mutableStateOf(null)

    /** 清单条点开后的全表弹窗（只读）。 */
    val todoDialogVisible: MutableState<Boolean> = mutableStateOf(false)

    /** 清单条是否被用户收起：仅影响当前界面，不修改磁盘上的清单。 */
    val todoBarHidden: MutableState<Boolean> = mutableStateOf(false)

    /** 列表当前是否贴底：仅在此处记录，键盘弹起时再查询已过晚。 */
    val wasAtBottom: MutableState<Boolean> = mutableStateOf(true)

    /** 输入框弹出面板的开关入口。面板状态仍归 Composer，父层仅借此铺设遮罩。 */
    val composerPanel = ComposerPanelHost()

    /** 进上下文洞察页；[fromHome] 决定返回落到哪一层（主页 / 设置首页）。 */
    fun openContextHub(fromHome: Boolean) {
        contextHubFromHome.value = fromHome
        drawerOpen.value = false
        page.value = AppPage.CONTEXT_HUB
    }

    /**
     * 所有「分叉」入口均走此路径：遮罩覆盖 → 实际切换会话 → 淡出，露出重新加载后的内容。
     */
    fun forkWithReload(scope: CoroutineScope, action: () -> Unit) {
        scope.launch {
            forkReloading.value = true
            // 等待遮罩完全覆盖后再替换数据，避免显示中间状态
            delay(80)
            action()
            delay(110)
            forkReloading.value = false
        }
    }
}
