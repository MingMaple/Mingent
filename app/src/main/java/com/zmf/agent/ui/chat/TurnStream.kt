package com.zmf.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.ui.common.BoundedScrollColumn
import com.zmf.agent.ui.common.Chevron
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.common.ChevronKind
import com.zmf.agent.ui.common.ChevronSize
import com.zmf.agent.ui.theme.Palette
import kotlinx.coroutines.delay

/*
 * 一个助手回合的内部结构：思考、正文、工具卡三者按「真实到达顺序」混排，轮末再折成一行计数。
 *
 * 之所以要摊平：AgentLoop 一轮里可能来回好几趟，想一段、调个工具、看结果、再想、再调，
 * 最后才出正文。旧实现把所有思考拼成一整段提到最上面、把工具卡全塞进同一个气泡，
 * 界面上就变成「先一坨想法、再一堆动作」，时间顺序被打散，读起来像流水账而不是过程。
 *
 * 摊平之后顺序＝发生顺序：思考 → 工具 → 思考 → 工具 → 正文。
 * 折叠发生在轮末（流式中绝不折叠，否则用户看不见正在做的事）：
 * 过程收成一行「思考 N 段 · 调用 M 次工具」，正文照常展开，点一下还原全过程。
 *
 * 展开态分两层，一眼能看出哪是过程、哪是交付：
 * 过程整块装进浅灰底板、向内缩进（[TurnProcessPanel]），里面的思考更淡、旁白更弱、工具卡照旧；
 * 最终回复留在底板**外面、下面**，仍是主气泡，视觉权重最高。
 */

/** 一轮里的一个片段，[messageIndex] 指向它在消息列表里的来源消息。 */
internal sealed interface TurnSegment {
    val messageIndex: Int

    /** 一段思考。[text] 是该消息的 reasoningContent。 */
    data class Think(override val messageIndex: Int, val text: String) : TurnSegment

    /** 一段正文（Markdown）。 */
    data class Body(override val messageIndex: Int, val text: String) : TurnSegment

    /** 一次工具调用：[call] 原样来自消息，参数与结果都不在这里改写。 */
    data class Call(override val messageIndex: Int, val call: ToolCall) : TurnSegment
}

/**
 * 把一轮（[indices] 指向的消息下标）摊成片段序列。
 *
 * 单条消息内部的次序按「模型先想、再说话、最后动手」排：思考 → 正文 → 工具调用。
 * 消息之间保持消息列表顺序，而消息列表本身就是到达顺序。
 */
internal fun buildTurnSegments(all: List<ChatMessage>, indices: List<Int>): List<TurnSegment> {
    val out = ArrayList<TurnSegment>()
    indices.forEach { index ->
        val message = all.getOrNull(index) ?: return@forEach
        message.reasoningContent
            ?.takeIf { it.isNotBlank() }
            ?.let { out.add(TurnSegment.Think(index, it)) }
        message.content
            .takeIf { it.isNotBlank() }
            ?.let { out.add(TurnSegment.Body(index, it)) }
        message.toolCalls.forEach { call -> out.add(TurnSegment.Call(index, call)) }
    }
    return out
}

/** 按 tool_call_id 找结果文本；找不到说明还在跑。 */
internal fun toolResultOf(all: List<ChatMessage>, call: ToolCall): String? =
    all.firstOrNull { it.role == "tool" && it.toolCallId == call.id }?.content

/**
 * 轮末折叠条：一行计数，点一下展开全过程、再点一下收回去。
 *
 * 计数只统计过程（思考段数 + 工具次数），不含正文：正文是这一轮的产出，
 * 折起来用户就看不到回答了，那不是折叠而是藏起来。
 */
@Composable
internal fun TurnProcessSummary(
    thinkCount: Int,
    callCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val label = buildString {
        if (thinkCount > 0) append("思考 ").append(thinkCount).append(" 段")
        if (thinkCount > 0 && callCount > 0) append(" · ")
        if (callCount > 0) append("调用 ").append(callCount).append(" 次工具")
        if (isEmpty()) append("过程")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 整行可点，但波纹要按行自己的圆角裁：裸 clickable 会在卡片里露出一个方块
            .saltRowClickable(RoundedCornerShape(8.dp), onClick = onToggle)
            .padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Chevron(
            expanded = expanded,
            size = ChevronSize.SMALL,
            kind = ChevronKind.RIGHT_DOWN,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
        Spacer(Modifier.weight(1f))
    }
}

/**
 * 按片段顺序渲染一轮。
 *
 * 每个片段都套了 [key]：流式输出时消息会在中间「长出」新的思考或工具卡，
 * 兄弟节点整体后移会让后面所有节点按位置错配、忘掉自己的展开状态，
 * key 之后只新增不挪位，后面卡片的展开状态就不会被顶到别人身上。
 */
@Composable
internal fun TurnStream(
    segments: List<TurnSegment>,
    all: List<ChatMessage>,
    streaming: Boolean,
    lastMessageIndex: Int?,
    toolOutcomes: Map<String, Boolean>,
    thinkColor: Color,
    bubbleColor: Color,
    cardColor: Color,
    // 装进过程底板时为 true：思考与旁白让位给底板，工具卡保持卡片感
    inPanel: Boolean = false
) {
    val gap = if (inPanel) 6.dp else 8.dp
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        segments.forEachIndexed { position, segment ->
            val segmentKey = when (segment) {
                is TurnSegment.Think -> "t" + segment.messageIndex
                is TurnSegment.Body -> "b" + segment.messageIndex
                is TurnSegment.Call -> "c" + segment.call.id
            }
            key(segmentKey) {
                when (segment) {
                    is TurnSegment.Think -> TurnThinkBlock(
                        reasoning = segment.text,
                        thinkingActive = streaming && position == segments.lastIndex,
                        containerColor = thinkColor,
                        inPanel = inPanel
                    )

                    is TurnSegment.Body -> TurnBodyBubble(
                        text = segment.text,
                        // 光标只画在「最后一小段正文」上：流式途中正文可能分几段落下，
                        // 每段都挂光标会像好几处在同时打字
                        showCaret = streaming &&
                            segment.messageIndex == lastMessageIndex &&
                            position == segments.lastIndex,
                        bubbleColor = bubbleColor,
                        inPanel = inPanel
                    )

                    is TurnSegment.Call -> ToolBlock(
                        toolName = segment.call.name,
                        arguments = segment.call.arguments,
                        output = toolResultOf(all, segment.call),
                        ok = toolOutcomes[segment.call.id],
                        containerColor = cardColor
                    )
                }
            }
        }
    }
}

/**
 * 展开态的过程底板。
 *
 * 展开态要像一堆摊开的底层执行日志：一眼就知道它被打开了。
 * 所以这里不是给每个子块再套一层框，而是整块换一层浅灰底板 + 左侧一道细竖线 + 内缩，
 * 让「过程」在视觉上成为一个被包起来的整体；最终回复留在底板外面，自然就分出了层级。
 *
 * 底板与竖线都用 [BoxScope.matchParentSize] 画：它们不参与撑高，高度由内容决定，
 * 不会因为 fillMaxHeight 在可滚动列表里被拉到整屏。
 */
@Composable
internal fun TurnProcessPanel(content: @Composable () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val plate = onSurface.copy(alpha = 0.045f)
    val rule = onSurface.copy(alpha = 0.16f)
    val shape = RoundedCornerShape(14.dp)
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(plate)
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .drawBehind {
                    // 竖线只画到上下留白之内，别顶到圆角上
                    val inset = 10.dp.toPx()
                    val width = 2.dp.toPx()
                    val radius = CornerRadius(width / 2f, width / 2f)
                    val height = (size.height - inset * 2f).coerceAtLeast(0f)
                    if (height > 0f) {
                        drawRoundRect(
                            color = rule,
                            topLeft = Offset(width / 2f + 1.dp.toPx(), inset),
                            size = Size(width, height),
                            cornerRadius = radius
                        )
                    }
                }
        )
        // 内缩比竖线再进一档：正文的「左边缘」与底板明显错开，读的时候不会混淆层级
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
        ) {
            content()
        }
    }
}

/** 正文气泡：形状与圆角沿用原来那条 AI 气泡，宽高上限不动。 */
@Composable
private fun TurnBodyBubble(
    text: String,
    showCaret: Boolean,
    bubbleColor: Color,
    inPanel: Boolean = false
) {
    // 过程区里的旁白不配气泡：直接铺在底板上、字号降一档、颜色退一档，
    // 让它明显弱于下面那条最终回复：要区分的正是这两者。
    if (inPanel) {
        MarkdownText(
            text = text,
            fontSize = 13.5.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
        )
        return
    }
    // 不用 Surface：它的 shape 会给节点挂一层 graphicsLayer + clip 裁剪图层。
    // AI 气泡高度不设上限（长回复里的整段代码块能把气泡撑到十几屏），这层图层的尺寸
    // 随之涨到几万像素，图层内的子节点（代码块的横滑 /「复制」/「缩进」）就收不到指点事件
    // 这时 AI 消息里的代码块滑不动、复制到用户气泡里却滑得动。
    // （15651px / 27187px 两种气泡高度上都实测过）。
    // background(color, shape) 圆角与 Surface 视觉一致，但不建图层。
    Box(
        modifier = Modifier
            .widthIn(max = 350.dp)
            .background(
                bubbleColor,
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
            )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            MarkdownText(
                text = if (showCaret) text + "▍" else text,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 思考窗口：默认折叠；展开后灰字，正文限高 180dp 内部竖滚。 */
@Composable
private fun TurnThinkBlock(
    reasoning: String,
    thinkingActive: Boolean,
    containerColor: Color,
    inPanel: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // 「复制」的全局唯一身份：摸别的块时按钮会从这块身上挪走
    val copyKey = rememberCopyBlockKey()
    val copyVisible = expanded && CopyRevealHost.owner === copyKey
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    // 标题行要顶到卡片边缘，波纹才铺满整行（复制反馈不做成行内小方块），
    // 所以内边距从 Column 挪到行内的内容上：可点区域 = 整张卡片的第一行。
    val headerShape = if (expanded) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    } else {
        RoundedCornerShape(16.dp)
    }
    // 在底板里就不再自带底色：两层灰叠在一起会脏，思考靠「更淡 + 斜体」自己退到后面
    Surface(
        color = if (inPanel) Color.Transparent else containerColor,
        shape = RoundedCornerShape(16.dp)
    ) {
        // 展开着才参与「谁显示复制」的竞争；折叠时摸一下也不该冒按钮出来
        Column(
            modifier = Modifier
                .then(if (expanded) Modifier.claimCopyOnGesture(copyKey) else Modifier)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .saltRowClickable(headerShape) {
                        expanded = !expanded
                        // 收起来就立刻把「复制」还掉：下一帧它就该消失
                        if (!expanded) CopyRevealHost.release(copyKey)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (thinkingActive) {
                        "思考中…"
                    } else {
                        "思考过程（" + reasoning.length + " 字）"
                    },
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                // 「复制」占一块固定宽度：露出来 / 收回去都不改变任何别的东西的位置，
                // 整块不会因为按钮出现而跳一下。
                // 这块 46dp 就是**虚拟区域**（不显示）：「复制 / 已复制」都在里面居中，
                // 涟漪整块铺满、和字同心：与代码块、工具卡、小窗同一套口径。
                Box(
                    modifier = Modifier
                        .width(CopySlot)
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (copyVisible) {
                                Modifier.clickable {
                                    copyCode(context, reasoning, "思考过程")
                                    copied = true
                                }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (copyVisible) {
                        Text(
                            text = if (copied) "已复制" else "复制",
                            maxLines = 1,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (expanded) "收起" else "展开",
                    fontSize = 12.sp,
                    color = Palette.AccentGreen
                )
            }
            if (expanded) {
                // 正文：装得下时滑动照常交给整页；超过 180dp 才在块内滚、滚到边界也不带动列表
                BoundedScrollColumn(
                    maxHeight = ThinkBodyMaxHeight,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    Text(
                        text = reasoning,
                        fontSize = 12.sp,
                        fontStyle = if (inPanel) FontStyle.Italic else FontStyle.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                            .copy(alpha = if (inPanel) 0.62f else 0.7f)
                    )
                }
            }
        }
    }
}

/** 思考正文的限高：240dp 时几乎占满一屏，收到 180dp。 */
private val ThinkBodyMaxHeight = 180.dp

/** 思考块「复制」按钮的固定占位宽度：先占住这块地方，按钮出现时只往里填字，不挤动邻居。 */
private val CopySlot = 46.dp