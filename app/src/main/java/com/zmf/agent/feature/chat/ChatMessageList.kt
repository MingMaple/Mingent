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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.io.File

/**
 * 消息流与其上的两层浮层：面板遮罩、分叉时的「瞬灭」盖板。
 *
 * 是 Column 里吃剩余高度的那一格，所以必须留着 `weight(1f)`——这也是它声明成 ColumnScope
 * 扩展的原因：`Modifier.weight` 只有在这个作用域里拿得到。
 */
@Composable
internal fun ColumnScope.ChatMessageList(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    context: Context,
    imageRoot: File,
    items: List<ChatItem>,
    listState: LazyListState,
    /** 输入法占住的高度（由外层算好传进来，整屏只有这一份口径）。 */
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

    // 长按小窗只在一个前提下弹：列表此刻没在滑。
    fun longPressAllowed(): Boolean = !listState.isScrollInProgress

    val density = LocalDensity.current
    val bottomTolerancePx = with(density) { 200.dp.toPx() }.toInt()
    // 新消息 / 工具返回 / 流式正文各变一项：四项合成跟随的触发键，只有它变才滚
    val followKey = listOf(
        items.size,
        viewModel.messages.size,
        viewModel.messages.lastOrNull()?.content?.length ?: 0,
        viewModel.messages.lastOrNull()?.reasoningContent?.length ?: 0
    )

    // 流式期间的「最后一条助手消息」下标。
    // 工具结果是以 role="tool" 追加进 messages 的，工具执行那几秒里 messages 末尾不是 assistant：
    // 若拿 messages.lastIndex 当判据，本回合会被判成「已经结束」，过程区折起来，
    // 等下一轮 assistant 消息到达又自动展开（2026-10-30 用户报的偶发折叠）。
    // 角色序列只在追加/回滚时变化，都与条数绑在一起，所以按条数记忆，token 级重组不必重扫。
    val lastAssistantIndex = remember(viewModel.currentSessionId, viewModel.messages.size) {
        viewModel.messages.indexOfLast { it.role == "assistant" }
    }

    // 键盘弹起时视口会先变矮：那一刻再问「是不是停在底部」，原来贴着底的位置已经被顶开了，
    // 问出来的永远是「不在底部」，列表就一条都不动。所以趁键盘收着的时候一直记着这个判断，
    // 真正弹起时用的是「弹起之前」的那一份。
    var wasAtBottom by ui.wasAtBottom
    LaunchedEffect(imeVisible) {
        if (imeVisible) return@LaunchedEffect
        snapshotFlow { listState.isNearBottom(bottomTolerancePx) }.collect { wasAtBottom = it }
    }

    // 新消息 / 工具返回 / 流式正文都会让 followKey 变。只有用户本来就贴在底部时才跟随，
    // 上翻看历史的时候不再被拽回底部（原来每来一次工具调用就弹回底）。
    LaunchedEffect(followKey) {
        if (!wasAtBottom) return@LaunchedEffect
        followBottom(listState, items.lastIndex)
    }

    // 冷启动 / 切会话的第一次落位：必须直接落在最后一条，而不是列表顶部。
    // 为什么不能只靠上面那条跟随逻辑：它要问 wasAtBottom，而 wasAtBottom 是由下面那条
    // 「是否贴底」采样写进去的：重开 App 那一刻列表还在顶部，采样先跑出 false，
    // 跟随逻辑就被自己让位掉了，于是停在最顶上。
    // 这里单独一条一次性锚定：不管当下停在哪，先落到最后一条并恢复「贴底」这个前提。
    var anchoredInitialPosition by remember(viewModel.currentSessionId) { mutableStateOf(false) }
    LaunchedEffect(items.size, viewModel.currentSessionId) {
        if (anchoredInitialPosition || items.isEmpty()) return@LaunchedEffect
        anchoredInitialPosition = true
        wasAtBottom = true
        followBottom(listState, items.lastIndex)
    }

    // 键盘动画期间逐帧跟住底部：视口每矮 Δ，列表就同步滚 Δ，最后一条消息在屏幕上原地不动。
    // 效果就是「输入框与列表同一段运动」。只在本来就贴着底部时跟，翻着历史看的时候不打断。
    //
    // 只跟「视口变矮」这一侧。键盘收起时视口是变高的，那时候再按 Δ 滚（Δ 为负）等于把内容
    // 往下推，最后一条连同气泡底部的用量胶囊就被推到输入框和待办条底下去了：遮挡就是这么来的。
    var appliedImePadPx by remember { mutableStateOf(0f) }
    LaunchedEffect(listState, imePad) {
        val targetPx = with(density) { imePad.toPx() }
        val delta = targetPx - appliedImePadPx
        // 先记「已经跟到哪」再决定动不动：中途让位（不在底部）时，下一次的差量才不会算错
        appliedImePadPx = targetPx
        if (delta <= 0f) return@LaunchedEffect
        if (!wasAtBottom) return@LaunchedEffect
        listState.scroll { scrollBy(delta) }
    }

    // 消息区的可见高度：键盘开合、待办条（n/n 完成）/ 待发附件行 / 排队行冒出或收起都会改它。
    // 这些兄弟节点是从下面把列表顶掉一截的，视口一变，原来贴着底的那条就可能落到视口外，
    // 所以「贴底」这件事不能只在键盘动画里做一次，得跟着视口的变化重算。
    // 视口落定后再对一次底部（键盘开合、待办条、待发附件行都会改视口）。
    // 这里绝不能把 imePad / 视口值放进 effect 的 key：键盘动画期间它们逐帧变化，
    // effect 会被取消并重启几十次，等于每帧都排一次「等一会儿再吸附」：这就是掉帧主因。
    // 用 collectLatest 只在视口安静 120ms 之后执行一次。
    val lastItemIndex = rememberUpdatedState(items.lastIndex)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportEndOffset }.collectLatest {
            delay(120)
            if (!wasAtBottom) return@collectLatest
            settleToBottom(listState, lastItemIndex.value)
        }
    }

                    // 消息区 + 挂在上方的面板：面板是浮层，不吃列表高度（列表不会重排）
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        // 内容不满一屏时贴底（Bottom）：新消息应该从底部往上长，而不是浮在半空。
                        // 内容超过一屏后 Bottom 对齐不再起作用，滚动行为与原来的 spacedBy 完全一致。
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom)
                    ) {
                        if (items.isEmpty()) {
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
                        // 必须给稳定 key：items(count = ...) 默认拿下标当 key，而流式输出时
                        // 消息条数一直在变，同一下标会指向「另一个回合」，LazyColumn 就把新内容
                        // 复用进旧槽位，槽位里残留的展开/点击状态跟着错位。
                        // UserItem 用消息下标；AssistantItem 用这一轮第一条消息的下标（一轮内不变）。
                        items(
                            count = items.size,
                            key = { position ->
                                when (val item = items[position]) {
                                    is ChatItem.UserItem -> "u" + item.index
                                    is ChatItem.AssistantItem -> "a" + item.indices.first()
                                }
                            },
                            // contentType 分开：用户气泡与助手回合结构不同，不分开会让列表
                            // 把这两种行当同一类型复用，重组时更容易带错状态。
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
                                    onImageClick = { paths, index -> viewer = ViewerTarget(paths, index) },
                                    onImageLongPress = { rel, anchor ->
                                        // 同 openMenu：惯性还没停就按住的那一下不算长按
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
                                    // 「这一轮还没完」= 正在流式，或本轮结束后已经排上了下一回合（续跑）。
                                    // 只看 isStreaming 的话，收尾落盘那几百毫秒里过程区会先折起来、
                                    // 下一段内容一到又展开，用户看到的就是「偶发折叠」。
                                    streaming = (viewModel.isStreaming || viewModel.chainPending) &&
                                        item.indices.lastOrNull() == lastAssistantIndex,
                                    toolOutcomes = viewModel.toolOutcomes,
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

                    // 面板打开时铺一层透明遮罩：点/划「输入条以外的区域」就收面板。
                    // 必须是消息区 Box 的最后一个孩子位：输入条与面板浮层都在它上层，点面板不会走到这里。
                    // 早先误放进「待办条可见」的 if 分支里，没有待办的会话整个就没有遮罩：面板开着仍能
                    // 滚列表、还能点开折叠条，面板赖着不走。
                    ComposerScrim(
                        visible = composerPanel.open,
                        onDismiss = { composerPanel.dismiss() }
                    )

                    // 分叉时的「瞬灭再加载」：用页面底色盖住消息区再淡出。
                    // 放在这一层最后：要盖住列表与上面的透明遮罩；不挂手势，闪完就透明。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = forkReloadAlpha }
                            .background(MaterialTheme.colorScheme.background)
                    )
                    }
}
