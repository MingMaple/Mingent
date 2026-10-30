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
 * 「本轮用量」伴随面板的入参：[anchor] 是那枚用量按钮在窗口里的位置，[usage] 是这一轮的账。
 *
 * 两者必须一起传：面板上的数字与「贴着哪一枚按钮」是同一次点击的产物，分开存会错位。
 */
internal data class TurnUsageTarget(val usage: TurnUsageInfo, val anchor: Offset)

/**
 * 长按已发送图片时的小窗：只这两件事：存下这一张、回到这条提问。
 * 带上 relPath 是因为「长按哪张就保存哪张」，不能拿整条的图片列表糊过去。
 */
internal data class ImageMenuTarget(
    val relPath: String,
    /** 这条用户消息在整个列表里的下标，回滚按它定位。 */
    val userIndex: Int,
    val anchor: Offset
)

/**
 * 图片详情的入参：这条消息的全部图片（缩略图顺序 = 选择/发送顺序）+ 被点开的那张下标。
 *
 * 一起传而不是只传一张：详情里要能左右切换查看，只给一张就切不了。
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
    PERMISSION_TIER,
    MODEL, PERMISSION, PACKAGE, LOGS, SCHEDULE, SUBAGENT, ARCHIVE, TRASH,
    // 设置首页（SETTINGS）→ 通用设置（GENERAL）/ 模型（MODEL）/ 长期记忆（MEMORY）/
    // 上下文洞察（CONTEXT_HUB）/ 上下文配置（CONTEXT_CONFIG）/ 包管理（PACKAGE）/ 数据（SETTINGS_DATA）；
    // 轨迹是由主页左滑进入的全屏页，不属于设置层。
    // 统计 / 提示词 / 思考三页已并进「上下文洞察」与「模型」：同一件事在两处入口只会让人怀疑哪个是真的。
    GENERAL, MEMORY, CONTEXT_HUB, CONTEXT_CONFIG, TRACE,
    // 关于是只读页（版本 / 开源地址 / 第三方组件），从设置首页最底部的卡片进
    ABOUT,
    // 计划是主页右上角那枚按钮进的独立页（不挂在设置层下），返回即回主页。
    // 目标没有预览页：/goal 走命令解析，状态只看输入框右侧那枚只读图标
    PLAN,
    // 内置浏览器：回复里的链接点开进这里。返回（侧滑/返回键）只回主页，**页面不关**：
    // 顶栏会多出一枚网页图标，点回来接着用（页面实例留在 BrowserHolder 里，登录态不丢）。
    BROWSER
}

/**
 * 聊天页的全部界面状态（瞬时态，不落盘）。
 *
 * 存在的理由：原 [ChatScreen] 是 1,800 行的一个函数，二十多个浮层各自 `remember` 在自己那一层，
 * 想抽成独立 composable 就得把这几十个状态一个个从参数表里递下去。改成集中持有后，子组合只管
 * 用 `var page by ui.page` 取一个委托，读写直接落到同一份状态上——拆分因此是纯搬动，不改行为。
 *
 * 字段名与原来的局部变量**逐字一致**，这是故意的：搬块时正文一个字都不用改。
 * 需要按 key 复位的状态（`pendingSession` / `planIdsKnown` 等）不放在这里，它们跟着各自的
 * `remember(key)` 留在组合里。
 */
internal class ChatUiState(context: Context) {
    /** 当前所在的二级页；null = 主页。 */
    val page: MutableState<AppPage?> = mutableStateOf(null)

    /** 回收站页返回时自增：数据页一直挂在这层下面，不换 key 那行统计就不会刷新。 */
    val trashRefreshKey: MutableState<Int> = mutableStateOf(0)

    val drawerOpen: MutableState<Boolean> = mutableStateOf(false)

    /** 上下文洞察页是从哪进来的：主页输入框那条路要退回主页，设置首页那条要退回设置。 */
    val contextHubFromHome: MutableState<Boolean> = mutableStateOf(false)

    /** 抽屉里的「＋ 新建工作区」：抽屉没有输入框，弹一个极简输入框。 */
    val showCreateWorkspace: MutableState<Boolean> = mutableStateOf(false)

    /** 包管理 / 会话列表这两页的内容区是 HorizontalPager，只有停在第一页时抽屉才敢抢向右的位移。 */
    val pagerAtFirstPage: MutableState<Boolean> = mutableStateOf(true)

    val extraPrompt: MutableState<String> = mutableStateOf(PromptStore.loadExtra(context))
    val overridePrompt: MutableState<String> =
        mutableStateOf(PromptStore.loadOverride(context).orEmpty())

    /** 输入栏实测高度：附件面板要浮在它上面，写死 dp 会跟字号档位对不上。 */
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

    /** Ubuntu 环境段的真实体积（字节）：0 表示还没量出来。 */
    val terminalBytes: MutableState<Long> = mutableStateOf(0L)
    val importUri: MutableState<Uri?> = mutableStateOf(null)

    /** 「清理未引用附件」的结果：只在这一层显示，页面关掉就没了。 */
    val orphanStatus: MutableState<String?> = mutableStateOf(null)

    /** 余额是「查了才有」的临时结果，不落盘：只在模型页显示。 */
    val balance: MutableState<BalanceResult?> = mutableStateOf(null)
    val balanceBusy: MutableState<Boolean> = mutableStateOf(false)

    /** 分叉会话的「瞬灭再加载」：分叉完屏幕内容要闪一下。 */
    val forkReloading: MutableState<Boolean> = mutableStateOf(false)

    /** 「本轮用量」伴随面板。 */
    val turnUsage: MutableState<TurnUsageTarget?> = mutableStateOf(null)
    val selectionTarget: MutableState<SelectionTarget?> = mutableStateOf(null)
    val selectionVisible: MutableState<Boolean> = mutableStateOf(false)

    /** 图片长按小窗：内容与可见性分开存，收起那 140ms 里内容还得留在场上播淡出。 */
    val imageMenuTarget: MutableState<ImageMenuTarget?> = mutableStateOf(null)
    val imageMenuVisible: MutableState<Boolean> = mutableStateOf(false)
    val rollbackUserIndex: MutableState<Int?> = mutableStateOf(null)

    /** 清单条点开后的全表弹窗（只读）。 */
    val todoDialogVisible: MutableState<Boolean> = mutableStateOf(false)

    /** 清单条是否被用户收起：只影响这一屏，不动盘上的清单。 */
    val todoBarHidden: MutableState<Boolean> = mutableStateOf(false)

    /** 列表当前是否贴着底：只在这里记，因为键盘弹起那一刻再问就晚了。 */
    val wasAtBottom: MutableState<Boolean> = mutableStateOf(true)

    /** 输入框弹出面板的「开着没 / 关掉」把手。面板状态仍归 Composer，父层只借它铺遮罩。 */
    val composerPanel = ComposerPanelHost()

    /** 进上下文洞察页；[fromHome] 决定返回落到哪一层（主页 / 设置首页）。 */
    fun openContextHub(fromHome: Boolean) {
        contextHubFromHome.value = fromHome
        drawerOpen.value = false
        page.value = AppPage.CONTEXT_HUB
    }

    /**
     * 所有「分叉」入口都走它：遮罩盖住 → 真正切会话 → 淡出，露出重新加载后的内容。
     */
    fun forkWithReload(scope: CoroutineScope, action: () -> Unit) {
        scope.launch {
            forkReloading.value = true
            // 等遮罩盖实了再换数据，避免用户看见中间态
            delay(80)
            action()
            delay(110)
            forkReloading.value = false
        }
    }
}
