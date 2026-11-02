package com.zmf.agent.feature.chat

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
import com.zmf.agent.uikit.common.BoundedScrollColumn
import com.zmf.agent.uikit.common.Chevron
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.common.ChevronKind
import com.zmf.agent.uikit.common.ChevronSize
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.delay
import com.zmf.agent.uikit.markdown.MarkdownText
import com.zmf.agent.uikit.markdown.copyCode
import com.zmf.agent.uikit.components.CopyRevealHost
import com.zmf.agent.uikit.components.claimCopyOnGesture
import com.zmf.agent.uikit.components.rememberCopyBlockKey

/*
 * 一个助手回合的内部结构：思考、正文、工具卡按「真实到达顺序」混排，轮末折为一行计数。
 *
 * 需要摊平的原因：AgentLoop 一轮中可能多次往返（生成、调用工具、查看结果、再次生成）；旧实现将思考拼接为一段置于顶部、工具卡放入同一气泡，界面顺序被打散，阅读体验近似流水账。
 *
 * 摊平后顺序＝发生顺序：思考 → 工具 → 思考 → 工具 → 正文。折叠发生于轮末（流式中绝不折叠），过程收为一行「思考 N 段 · 调用 M 次工具」，正文照常展开，点击还原全过程。
 *
 * 展开态分为两层：过程置于浅灰底板并向内缩进（[TurnProcessPanel]），最终回复保留在底板**之外、下方**，视觉权重最高。
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
 * 单条消息内部的次序按「模型先思考、再输出正文、最后调用工具」排列：思考 → 正文 → 工具调用。
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

/** 按 tool_call_id 查找结果文本；找不到表示仍在执行。 */
internal fun toolResultOf(all: List<ChatMessage>, call: ToolCall): String? =
    all.firstOrNull { it.role == "tool" && it.toolCallId == call.id }?.content

/**
 * 轮末折叠条：一行计数，点击展开全过程，再次点击收起。
 *
 * 计数仅统计过程（思考段数 + 工具次数），不含正文：正文为本轮产出，
 * 折叠后用户将无法看到回答，这属于隐藏而非折叠。
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
            // 整行可点击，但波纹需按行自身圆角裁剪：裸 clickable 会在卡片中露出方形
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
 * 每个片段均设置 [key]：流式输出时消息中部会新增思考或工具卡，
 * 兄弟节点整体后移会使后续所有节点按位置错配、丢失自身展开状态，
 * 设置 key 后仅新增不挪位，后续卡片的展开状态不会被错误归属。
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
    // 置入过程底板时为 true：思考与旁白让位于底板，工具卡保持卡片形态
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
                        // 每段均挂光标会显得多处同时输入
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
 * 展开态应呈现为铺开的底层执行日志：一眼即可辨认其已展开。
 * 因此此处不为每个子块再套一层框，而是整块更换浅灰底板 + 左侧细竖线 + 内缩，
 * 使「过程」在视觉上成为完整整体；最终回复保留在底板之外，层级因此自然区分。
 *
 * 底板与竖线都用 [BoxScope.matchParentSize] 画：它们不参与撑高，高度由内容决定，
 * 不会因为 fillMaxHeight 在可滚动列表中被拉伸至整屏。
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
                    // 竖线仅绘制至上下留白范围内，不触及圆角
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
        // 内缩比竖线再进一档：正文「左边缘」与底板明显错开，阅读时不会混淆层级
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
        ) {
            content()
        }
    }
}

/** 正文气泡：形状与圆角沿用原 AI 气泡，宽高上限不变。 */
@Composable
private fun TurnBodyBubble(
    text: String,
    showCaret: Boolean,
    bubbleColor: Color,
    inPanel: Boolean = false
) {
    // 过程区中的旁白不使用气泡：直接铺于底板上、字号降低一档、颜色退一档，
    // 使其明显弱于下方最终回复：需要区分的正是这两者。
    if (inPanel) {
        MarkdownText(
            text = text,
            fontSize = 13.5.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
        )
        return
    }
    // 不使用 Surface：其 shape 会为节点挂载一层 graphicsLayer + clip 裁剪图层。
    // AI 气泡高度不设上限（长回复中的整段代码块可将气泡撑至十几屏），该图层尺寸
    // 随之增至数万像素，图层内的子节点（代码块的横滑 /「复制」/「缩进」）将无法接收触摸事件
    // 此时 AI 消息中的代码块无法滑动，而复制到用户气泡后却可滑动。
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

/** 思考窗口：默认折叠；展开后显示灰字，正文限高 180dp 并在内部纵向滚动。 */
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
    // 「复制」的全局唯一标识：操作其他块时按钮会从该块迁移
    val copyKey = rememberCopyBlockKey()
    val copyVisible = expanded && CopyRevealHost.owner === copyKey
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    // 标题行需顶至卡片边缘，波纹方可铺满整行（复制反馈不设计为行内小方块），
    // 因此内边距从 Column 移至行内内容上：可点击区域 = 整张卡片的第一行。
    val headerShape = if (expanded) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    } else {
        RoundedCornerShape(16.dp)
    }
    // 位于底板中时不带底色：两层灰色叠加会显得浑浊，思考内容以「更淡 + 斜体」退至次要层级
    Surface(
        color = if (inPanel) Color.Transparent else containerColor,
        shape = RoundedCornerShape(16.dp)
    ) {
        // 仅展开时参与「谁显示复制」的判定；折叠时操作不应使按钮出现
        Column(
            modifier = Modifier
                .then(if (expanded) Modifier.claimCopyOnGesture(copyKey) else Modifier)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .saltRowClickable(headerShape) {
                        expanded = !expanded
                        // 收起后立即释放「复制」：下一帧其应消失
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
                // 「复制」占用固定宽度：出现或收回均不改变其他元素的位置，
                // 整块不会因按钮出现而跳动。
                // 此 46dp 为**虚拟区域**（不显示）：「复制 / 已复制」均在其中居中，
                // 涟漪铺满整块并与文字同心：与代码块、工具卡、小窗保持同一约定。
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
                    color = UiColors.accentFg
                )
            }
            if (expanded) {
                // 正文：内容可容纳时滑动照常交由整页；超过 180dp 才在块内滚动，滚至边界也不带动列表
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

/** 思考正文的限高：240dp 时几乎占满一屏，故调整为 180dp。 */
private val ThinkBodyMaxHeight = 180.dp

/** 思考块「复制」按钮的固定占位宽度：预先占用该区域，按钮出现时仅在其中填充文字，不挤动相邻元素。 */
private val CopySlot = 46.dp
