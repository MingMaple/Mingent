package com.zmf.agent.feature.chat

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.layout.LocalUiWindow
import com.zmf.agent.uikit.markdown.FileLinkRouter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.io.File

/**
 * 消息流与其上的两层浮层：面板遮罩、分叉时的「瞬灭」盖板。
 *
 * 为 Column 中占用剩余高度的一格，因此必须保留 `weight(1f)`——这也是其声明为 ColumnScope
 * 扩展的原因：`Modifier.weight` 仅在该作用域内可用。
 */
@Composable
internal fun ColumnScope.ChatMessageList(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    context: Context,
    imageRoot: File,
    items: List<ChatItem>,
    listState: LazyListState,
    /** 输入法占用的高度（由外层计算后传入，全屏仅此一份来源）。 */
    imePad: Dp,
    imeVisible: Boolean
) {
    val scope = rememberCoroutineScope()
    var viewer by ui.viewer
    var selectionTarget by ui.selectionTarget
    var selectionVisible by ui.selectionVisible
    var imageMenuTarget by ui.imageMenuTarget
    var imageMenuVisible by ui.imageMenuVisible
    var rollbackUserIndex by ui.rollbackUserIndex
    var turnUsage by ui.turnUsage
    var forkReloading by ui.forkReloading
    val composerPanel = ui.composerPanel
    val forkReloadAlpha by animateFloatAsState(
        targetValue = if (forkReloading) 1f else 0f,
        animationSpec = tween(durationMillis = if (forkReloading) 70 else 140),
        label = "forkReload"
    )
    val forkWithReload: (() -> Unit) -> Unit = { action -> ui.forkWithReload(scope, action) }

    // 本会话产出的文件：卡片、正文里的产物链接、预览页列表三处共用同一份推导结果。
    // 不套 remember：messages 是就地变异的快照列表，拿它当键永远不会失效，结果会停在首次组合时的空表。
    val producedFiles = ProducedFiles.of(viewModel.messages, imageRoot)
    // 行内代码里的产物路径按这一份集合解析；换会话或新写出文件时重挂一次
    DisposableEffect(producedFiles) {
        FileLinkRouter.attach { token -> ProducedFiles.match(producedFiles, token)?.path }
        FileLinkRouter.open = { path ->
            // 在预览页里点开文件链接＝进入下一层：记下来路，系统返回时逐层退回
            if (ui.page.value == AppPage.PREVIEW) {
                PreviewTabs.openChild(path)
            } else {
                PreviewTabs.open(path)
            }
            ui.page.value = AppPage.PREVIEW
            true
        }
        onDispose { FileLinkRouter.attach(null) }
    }
    val openPreview: (String) -> Unit = { path ->
        PreviewTabs.open(path)
        ui.page.value = AppPage.PREVIEW
    }

    // 长按小窗仅在列表未滑动时弹出。
    fun longPressAllowed(): Boolean = !listState.isScrollInProgress

    val density = LocalDensity.current
    val window = LocalUiWindow.current
    val bottomTolerancePx = with(density) { 200.dp.toPx() }.toInt()
    // 列表底部内容内距：视口下沿与「最后一条真正贴底时的下沿」相差这一块，
    // 贴底计算与 contentPadding 必须共用同一份取值，否则底部会固定留出可上滑的空区。
    val listBottomInset = if (window.compactChrome) 8.dp else 16.dp
    val listBottomInsetPx = with(density) { listBottomInset.toPx() }.toInt()
    // 新消息 / 工具返回 / 流式正文各变化一项：四项合成跟随的触发键，仅在键变化时滚动
    val followKey = listOf(
        items.size,
        viewModel.messages.size,
        viewModel.messages.lastOrNull()?.content?.length ?: 0,
        viewModel.messages.lastOrNull()?.reasoningContent?.length ?: 0
    )

    // 流式期间的「最后一条助手消息」下标。
    // 工具结果以 role="tool" 追加至 messages，工具执行的数秒内 messages 末尾不是 assistant：
    // 若以 messages.lastIndex 作为判据，本回合会被判断为「已经结束」，过程区折叠，
    // 待下一轮 assistant 消息到达后又自动展开（2026-10-30 用户反馈的偶发折叠）。
    // 角色序列仅在追加或回滚时变化，均与条数绑定，因此按条数记忆，token 级重组无需重扫。
    val lastAssistantIndex = remember(viewModel.currentSessionId, viewModel.messages.size) {
        viewModel.messages.indexOfLast { it.role == "assistant" }
    }

    // 最后一条「用户亲手发送」的提问下标。
    //
    // 插话会立刻在末尾追加一条 user 消息：被它插在后面的那一轮，从用户视角已经结束
    // （没有最终输出，只有过程），过程区应当立刻折叠成计数行，而不是把一整排工具卡摊在表面。
    // 只认非代发消息：后台任务结算注入的是 programmatic = true，那不算用户开口，
    // 它注入时本轮仍在继续，不触发折叠。
    val lastUserIndex = remember(viewModel.currentSessionId, viewModel.messages.size) {
        viewModel.messages.indexOfLast { it.role == "user" && !it.programmatic }
    }

    // 键盘弹起时视口会先变矮：此时再判断「是否停在底部」，原本贴底的位置已被顶开，
    // 判断结果始终为「不在底部」，列表不会移动。因此在键盘收起期间持续记录该判断，
    // 真正弹起时使用「弹起前」的记录。
    var wasAtBottom by ui.wasAtBottom
    LaunchedEffect(imeVisible) {
        if (imeVisible) return@LaunchedEffect
        snapshotFlow { listState.isNearBottom(bottomTolerancePx) }.collect { wasAtBottom = it }
    }

    // 新消息 / 工具返回 / 流式正文均会使 followKey 变化。仅在用户原本贴底时跟随，
    // 向上翻阅历史时不被拉回底部（此前每次工具调用都会弹回底部）。
    LaunchedEffect(followKey) {
        if (!wasAtBottom) return@LaunchedEffect
        followBottom(listState, items.lastIndex, listBottomInsetPx)
    }

    // 冷启动 / 切换会话的首次落位：必须直接落在最后一条，而非列表顶部。
    // 为什么不能仅依赖上述跟随逻辑：该逻辑需读取 wasAtBottom，而 wasAtBottom 由下方
    // 「是否贴底」采样写入：重新打开 App 时列表仍在顶部，采样先行得出 false，
    // 跟随逻辑因此被抵消，最终停留在顶部。
    // 此处单独设置一次性锚定：无论当前位置如何，先落至最后一条并恢复「贴底」前提。
    var anchoredInitialPosition by remember(viewModel.currentSessionId) { mutableStateOf(false) }
    LaunchedEffect(items.size, viewModel.currentSessionId) {
        if (anchoredInitialPosition || items.isEmpty()) return@LaunchedEffect
        wasAtBottom = true
        // 先完成滚动，再放开绘制：两次状态写入位于同一协程，中间不会插入新的一帧。
        // 若在滚动前放开绘制，读盘完成后会先呈现会话顶部一帧，随后再跳到底部。
        try {
            followBottom(listState, items.lastIndex, listBottomInsetPx)
        } finally {
            anchoredInitialPosition = true
        }
    }

    // 键盘动画期间逐帧跟随底部：视口每减少 Δ，列表同步滚动 Δ，最后一条消息在屏幕上保持不动。
    // 效果为「输入框与列表同步运动」。仅在原本贴底时跟随，翻阅历史时不打断。
    //
    // 仅跟随「视口变矮」方向。键盘收起时视口变高，此时按 Δ 滚动（Δ 为负）等于将内容
    // 向下推移，最后一条连同气泡底部的用量胶囊会被推至输入框与待办条下方：遮挡由此产生。
    var appliedImePadPx by remember { mutableStateOf(0f) }
    LaunchedEffect(listState, imePad) {
        val targetPx = with(density) { imePad.toPx() }
        val delta = targetPx - appliedImePadPx
        // 先记录「已跟至何处」再决定是否移动：中途让位（不在底部）时，下一次差值才不会计算错误
        appliedImePadPx = targetPx
        if (delta <= 0f) return@LaunchedEffect
        if (!wasAtBottom) return@LaunchedEffect
        listState.scroll { scrollBy(delta) }
    }

    // 消息区的可见高度：键盘开合、待办条、待发附件行、排队行的出现或收起均会改变该值。这些兄弟节点从下方占用列表空间，视口变化后原本贴底的条目可能落到视口外，因此「贴底」需随视口变化重新计算。
    // 视口稳定后再次校准底部。不得将 imePad / 视口值放入 effect 的 key：键盘动画期间其逐帧变化，effect 会被取消并重启数十次，相当于每帧安排一次延迟吸附，这是掉帧的主要原因。
    // 使用 collectLatest 仅在视口稳定 120ms 后执行一次。
    //
    // 同时监听「最后一条的高度」：消息正文、工具卡与图片的最终高度常在首次测量之后才落定，
    // 只监听视口会漏掉这一类变化，那时底部就会留下固定的一截可上滑余量。
    val lastItemIndex = rememberUpdatedState(items.lastIndex)
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.viewportEndOffset to (if (last?.index == lastItemIndex.value) last.size else 0)
        }.collectLatest {
            delay(120)
            if (!wasAtBottom) return@collectLatest
            settleToBottom(listState, lastItemIndex.value, listBottomInsetPx)
        }
    }

                    // 消息区 + 挂载于其上方的面板：面板为浮层，不占用列表高度（列表不会重排）
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        // 首次落位完成前不绘制消息：列表仍参与布局，最后一条可被量出并滚动到位，
                        // 但用户不会先看到会话顶部、再看到跳到底部。
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (anchoredInitialPosition || items.isEmpty()) 1f else 0f),
                        // 横屏高度紧凑时只收纵向内距：左右保持 16dp，气泡宽度不受影响
                        contentPadding = if (window.compactChrome) {
                            PaddingValues(horizontal = 16.dp, vertical = listBottomInset)
                        } else {
                            PaddingValues(listBottomInset)
                        },
                        // 内容不足一屏时贴底（Bottom）：新消息应从底部向上增长，而非悬于半空。
                        // 内容超过一屏后 Bottom 对齐无法生效，滚动行为与原先的 spacedBy 完全一致。
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom)
                    ) {
                        // 启动读盘完成前不显示占位文案：此时 messages 仍为空，
                        // 先显示「输入消息开始对话」再被历史替换，会短暂呈现空会话状态
                        if (items.isEmpty() && viewModel.startupReady) {
                            item {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "输入消息开始对话",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    )
                                }
                            }
                        }
                        // 必须提供稳定 key：items(count = ...) 默认以索引作为 key，而流式输出时
                        // 消息条数持续变化，同一索引会指向「另一个回合」，LazyColumn 会将新内容
                        // 复用至旧槽位，槽位中残留的展开/点击状态随之错位。
                        // UserItem 使用消息索引；AssistantItem 使用该轮第一条消息的索引（轮内不变）。
                        items(
                            count = items.size,
                            key = { position ->
                                when (val item = items[position]) {
                                    is ChatItem.UserItem -> "u" + item.index
                                    is ChatItem.AssistantItem -> "a" + item.indices.first()
                                }
                            },
                            // 区分 contentType：用户气泡与助手回合结构不同，不区分会使列表
                            // 将两种行视为同一类型复用，重组时更易携带错误状态。
                            contentType = { position ->
                                when (items[position]) {
                                    is ChatItem.UserItem -> "user"
                                    is ChatItem.AssistantItem -> "assistant"
                                }
                            }
                        ) { position ->
                            when (val item = items[position]) {
                                is ChatItem.UserItem -> UserRow(
                                    message = item.message,
                                    roundsFromEnd = item.roundsFromEnd,
                                    imageRoot = imageRoot,
                                    onCopy = { text -> copyToClipboard(context, text) },
                                    onSelectText = { text ->
                                        selectionTarget = SelectionTarget(
                                            title = "选择文本",
                                            text = text,
                                            reasoning = ""
                                        )
                                        selectionVisible = true
                                    },
                                    onRollback = { rollbackUserIndex = item.index },
                                    onImageClick = { paths, index, origin ->
                                        viewer = ViewerTarget(paths, index, origin)
                                    },
                                    onImageLongPress = { rel, anchor ->
                                        // 与 openMenu 相同：惯性尚未停止时的按住操作不视为长按
                                        if (longPressAllowed()) {
                                            imageMenuTarget = ImageMenuTarget(
                                                relPath = rel,
                                                userIndex = item.index,
                                                anchor = anchor
                                            )
                                            imageMenuVisible = true
                                        }
                                    }
                                )

                                is ChatItem.AssistantItem -> AssistantRow(
                                    all = viewModel.messages,
                                    indices = item.indices,
                                    // 后台任务结算通知触发的回合：只留一行来源说明，不画用户气泡
                                    trigger = item.trigger,
                                    // 「本轮尚未结束」= 正在流式（或本轮结束后已排入续跑），且本条就是最后一条助手消息，
                                    // 并且它之后没有出现新的用户提问。
                                    // 前两条照旧：若仅判断 isStreaming，收尾落盘的数百毫秒内过程区会先折叠、
                                    // 下一段内容到达后又展开，用户观察到的即为「偶发折叠」。
                                    // 第三条专为插话：插话另起一轮后，被截断的那一轮已经没有最终输出，
                                    // 立刻折叠，只留计数行（展开仍可查看整段过程）。
                                    streaming = (viewModel.isStreaming || viewModel.chainPending) &&
                                        item.indices.lastOrNull() == lastAssistantIndex &&
                                        (item.indices.lastOrNull() ?: -1) > lastUserIndex,
                                    toolOutcomes = viewModel.toolOutcomes,
                                    root = imageRoot,
                                    onOpenFile = openPreview,
                                    onCopy = { text -> copyToClipboard(context, text) },
                                    onSelectText = { text, reasoning ->
                                        selectionTarget = SelectionTarget(
                                            title = "选择文本",
                                            text = text,
                                            reasoning = reasoning
                                        )
                                        selectionVisible = true
                                    },
                                    onFork = { boundary -> forkWithReload { viewModel.forkSession(boundary) } },
                                    onUsage = { usage, anchor ->
                                        turnUsage = TurnUsageTarget(usage = usage, anchor = anchor)
                                    }
                                )
                            }
                        }
                    }

                    // 面板打开时铺设透明遮罩：点击或滑动「输入条以外的区域」即收起面板。
                    // 必须为消息区 Box 的最后一个子节点：输入条与面板浮层均位于其上层，点击面板不会进入此处。
                    // 此前误置于「待办条可见」的 if 分支中，无待办的会话完全没有遮罩：面板打开时仍可
                    // 滚动列表并点开折叠条，面板不会关闭。
                    ComposerScrim(
                        visible = composerPanel.open,
                        onDismiss = { composerPanel.dismiss() }
                    )

                    // 分叉时的「瞬灭再加载」：以页面底色覆盖消息区后淡出。
                    // 置于该层最后：需覆盖列表与上方透明遮罩；不挂载手势，闪烁结束后即透明。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = forkReloadAlpha }
                            .background(MaterialTheme.colorScheme.background)
                    )
                    }
}
