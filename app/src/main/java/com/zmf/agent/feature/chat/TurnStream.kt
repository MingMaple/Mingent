package com.zmf.agent.feature.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.capability.loop.ModelRetryInfo
import com.zmf.agent.capability.tools.ToolCategory
import com.zmf.agent.capability.tools.toolCategoryOf
import com.zmf.agent.capability.tools.toolHeadline
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.uikit.common.BoundedScrollColumn
import com.zmf.agent.uikit.common.Chevron
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.common.ChevronKind
import com.zmf.agent.uikit.common.ChevronSize
import com.zmf.agent.uikit.layout.LocalUiWindow
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.delay
import com.zmf.agent.uikit.markdown.MarkdownText
import com.zmf.agent.uikit.markdown.copyCode
import com.zmf.agent.uikit.components.CopyActionButton

/*
 * 一个助手回合的内部结构：思考、正文、工具卡按「真实到达顺序」混排，轮末折为一行计数。
 *
 * 需要摊平的原因：AgentLoop 一轮中可能多次往返（生成、调用工具、查看结果、再次生成）；旧实现将思考拼接为一段置于顶部、工具卡放入同一气泡，界面顺序被打散，阅读体验近似流水账。
 *
 * 摊平后顺序＝发生顺序：思考 → 工具 → 思考 → 工具 → 正文。折叠从运行中就开始（对齐 DSH：`useDisclosure` 初值恒为收起），
 * 过程组收为一行组头，组头本行给出「正在做什么 · 最新一句预览」并带流光；轮末统一收成「思考 N 段 · 调用 M 次工具」，正文始终照常展开。
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

/** 按 tool_call_id 找结果消息；找不到表示仍在执行。 */
internal fun toolResultMessageOf(all: List<ChatMessage>, call: ToolCall): ChatMessage? =
    all.firstOrNull { it.role == "tool" && it.toolCallId == call.id }

/** 按 tool_call_id 查找结果文本；找不到表示仍在执行。 */
internal fun toolResultOf(all: List<ChatMessage>, call: ToolCall): String? =
    toolResultMessageOf(all, call)?.content

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
 * 过程组：连续的一段思考与工具调用。
 *
 * 分界照 DSH 的规则：出现组外正文即封闭当前组，本段结束（或整轮结束）封闭最后一组。
 * 组在界面上的形态是「一行组头 + 可展开的成员」，与 DSH 的 process group 一致。
 */
internal data class ProcessGroup(
    /** 组首成员的片段键：组头的展开状态按它记忆，流式新增成员不会错位。 */
    val key: String,
    val members: List<TurnSegment>
)

/** 运行中的渲染块：一组过程，或一段组外正文。 */
internal sealed interface TurnBlock {
    data class Group(val group: ProcessGroup) : TurnBlock
    data class Text(val body: TurnSegment.Body) : TurnBlock
}

/** 片段在列表里的稳定键：流式新增片段时，后续节点的展开状态不会错配。 */
internal fun segmentKey(segment: TurnSegment): String = when (segment) {
    is TurnSegment.Think -> "t" + segment.messageIndex
    is TurnSegment.Body -> "b" + segment.messageIndex
    is TurnSegment.Call -> "c" + segment.call.id
}

/**
 * 把一轮的过程片段切成「组 / 组外正文」交替的序列。
 *
 * 只作用于运行中与整轮展开态：整轮折叠时全部收成一行汇总，不按组显示。
 */
internal fun buildTurnBlocks(segments: List<TurnSegment>): List<TurnBlock> {
    val out = ArrayList<TurnBlock>()
    val members = ArrayList<TurnSegment>()
    fun flush() {
        if (members.isEmpty()) return
        out.add(TurnBlock.Group(ProcessGroup(segmentKey(members.first()), members.toList())))
        members.clear()
    }
    for (segment in segments) {
        if (segment is TurnSegment.Body) {
            flush()
            out.add(TurnBlock.Text(segment))
        } else {
            members.add(segment)
        }
    }
    flush()
    return out
}

/** 工具分类对应的「正在做什么」：运行中的组头用这一句代替计数。 */
private fun runningLabel(category: ToolCategory): String =
    when (category) {
        ToolCategory.COMMAND -> "正在运行命令"
        ToolCategory.FILES -> "正在读写文件"
        ToolCategory.SEARCH -> "正在搜索"
        ToolCategory.WEB -> "正在访问网页"
        ToolCategory.BROWSER -> "正在浏览网页"
        ToolCategory.SESSION -> "正在处理会话"
        ToolCategory.WORKSPACE -> "正在处理工作区"
        ToolCategory.SCHEDULE -> "正在处理定时任务"
        ToolCategory.AGENT -> "正在协调子智能体"
        ToolCategory.INTERACTION -> "正在与用户交互"
        ToolCategory.MEDIA -> "正在处理图像"
        ToolCategory.CONTEXT -> "正在查看上下文"
        ToolCategory.CAPTURE -> "正在截取屏幕"
        ToolCategory.OTHER -> "正在调用工具"
    }

/** 运行中组头预览的字数上限：组头只有一行，再长也读不完。 */
private const val LIVE_DETAIL_LIMIT = 60

/** 段落分隔：空行（可含行尾空白与 CRLF）。文件级复用，避免每次预览都重新编译正则。 */
private val PARAGRAPH_SEPARATOR = Regex("\\r?\\n(?:[\\t ]*\\r?\\n)+")

/**
 * 流式预览文案：取「最近一个已完成段落的首行」。
 *
 * 口径照 DSH 的 `latestCompletedParagraphFirstLine`：段落以空行分段，段内首行即摘要；
 * 按段落粒度推进而不是逐字推进，行内才不会左右跳动。还没有完整段落时（刚开始流式）
 * 退回当前正在写的那一行，保证组头上立刻有字可看。
 */
internal fun livePreview(text: String): String {
    val trimmed = text.trimEnd()
    if (trimmed.isEmpty()) return ""
    var summary = ""
    var paragraphStart = 0
    while (true) {
        val next = PARAGRAPH_SEPARATOR.find(trimmed, paragraphStart)
        val paragraphEnd = next?.range?.first ?: trimmed.length
        val newline = trimmed.indexOf('\n', paragraphStart)
        if (newline != -1 && newline <= paragraphEnd) {
            val candidate = trimmed.substring(paragraphStart, newline).trim()
            if (candidate.isNotEmpty()) summary = candidate
        }
        if (next == null) break
        paragraphStart = next.range.last + 1
    }
    if (summary.isEmpty()) summary = trimmed.substringAfterLast('\n').trim()
    // 强调标记不该出现在摘要里：这里是一行纯文本，不是 Markdown
    val flat = summary.replace("**", "").replace('\n', ' ').trim()
    return if (flat.length > LIVE_DETAIL_LIMIT) flat.take(LIVE_DETAIL_LIMIT - 1) + "…" else flat
}

/**
 * 运行中组头的一行文案：[label] 说明正在做什么，[detail] 给出最新一句。
 *
 * 两段都必须落在**同一行**上：折叠态只剩这一行，工具调用与流式文本若只出现其一，
 * 用户就看不到另一半在发生什么（对齐 DSH 的 `processActivity` + `runningDetail`）。
 */
private data class LiveHeader(val label: String, val detail: String)

/** 分析档的组头标题：DSH 在没有运行中的工具时给出的就是这一句（`stepProcess.thinking`）。 */
private const val LIVE_ANALYZING = "正在分析请求"

/** 缺省档的细节里，最近一次调用的标题只留一小截：同一行还要放「已调用 N 次工具」。 */
private const val LIVE_RECENT_LIMIT = 26

/**
 * 运行中组头：活动（哪个工具在跑）+ 细节（工具调用本身或最新流式文本）。
 *
 * 三种情况依次判定，与 DSH 的 `processActivity` 同序：
 * 1. 有未返回的调用 → 活动取该调用的类别动词，细节取它的 [toolHeadline]（工具名与参数，即「工具调用」本身）；
 * 2. 没有未返回的调用、最后一段是思考 → 活动回到分析档，细节取最新完成段落的首行（流式文本）；
 * 3. 工具已返回、下一段还没到 → 活动仍是分析档，细节报出已调用次数与最近一次调用的标题；
 *    这一档原来写作「正在准备下一步…」，一句话里既没有工具也没有文本，等于整行没有信息。
 */
private fun liveHeader(group: ProcessGroup, all: List<ChatMessage>): LiveHeader {
    val calls = group.members.filterIsInstance<TurnSegment.Call>()
    val pending = calls.lastOrNull { toolResultOf(all, it.call) == null }
    if (pending != null) {
        return LiveHeader(
            runningLabel(toolCategoryOf(pending.call.name)),
            toolHeadline(pending.call.name, pending.call.arguments)
        )
    }
    val tail = group.members.lastOrNull()
    if (tail is TurnSegment.Think) return LiveHeader(LIVE_ANALYZING, livePreview(tail.text))
    val last = calls.lastOrNull() ?: return LiveHeader(LIVE_ANALYZING, "")
    val recent = toolHeadline(last.call.name, last.call.arguments, LIVE_RECENT_LIMIT)
    val detail = buildString {
        append("已调用 ").append(calls.size).append(" 次工具")
        if (recent.isNotBlank()) append(" · ").append(recent)
    }
    return LiveHeader(LIVE_ANALYZING, detail)
}

/** 组头文案：运行中说明正在做什么；已封闭的组按类别给出计数。 */
private fun groupLabel(group: ProcessGroup, running: Boolean, all: List<ChatMessage>): String {
    if (running) return liveHeader(group, all).label
    val parts = ArrayList<String>(2)
    val think = group.members.count { it is TurnSegment.Think }
    if (think > 0) parts += "思考 " + think + " 段"
    val counts = LinkedHashMap<ToolCategory, Int>()
    group.members.filterIsInstance<TurnSegment.Call>().forEach { call ->
        val category = toolCategoryOf(call.call.name)
        counts[category] = (counts[category] ?: 0) + 1
    }
    for ((category, count) in counts) parts += category.label + " " + count
    return if (parts.isEmpty()) "过程" else parts.joinToString(" · ")
}

/**
 * 运行中组头的那一行字：淡底 + 一条高光自左向右扫过，对齐 DSH 的 `TextShimmer`。
 *
 * 扫过的行程取固定像素而不去测量文本宽度：组头恒为单行 12sp，最长也不会超过两屏，
 * 固定行程足够覆盖；不测量就不会在流式逐字变化时引发额外布局。
 */
@Composable
internal fun StreamShimmerText(text: String) {
    val base = UiColors.accentFg.copy(alpha = 0.55f)
    val bright = UiColors.accentFg
    val transition = rememberInfiniteTransition(label = "processShimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "processShimmerPhase"
    )
    val sweep = 320f
    val start = phase * 2200f - sweep
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            fontSize = 12.sp,
            brush = Brush.linearGradient(
                colors = listOf(base, bright, base),
                start = Offset(start, 0f),
                end = Offset(start + sweep, 0f)
            )
        )
    )
}

/**
 * 自动重试节点：独立一行，落在消息流末尾（对齐 DSH 的 `model-retry` 节点）。
 *
 * 只在对可重试失败做退避等待时出现，收起于「下一次尝试开始 / 回合收尾」；
 * 倒计时按秒递减，等待结束后只留状态文字，等下一次尝试接上。
 */
@Composable
internal fun ModelRetryRow(info: ModelRetryInfo) {
    // 以本次排期为键重置倒计时：第 n 次重试是新的等待，不能沿用上一轮的秒数
    var remainingSec by remember(info.retry, info.delayMs) {
        mutableStateOf(((info.delayMs + 999) / 1000).toInt())
    }
    LaunchedEffect(info.retry, info.delayMs) {
        while (remainingSec > 0) {
            delay(1000)
            remainingSec -= 1
        }
    }
    val label = "正在重试模型请求（" + info.retry + "/" + info.maxRetries + "）"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RetrySpinner()
        Spacer(Modifier.width(8.dp))
        StreamShimmerText(if (remainingSec > 0) label + " · " + remainingSec + "s" else label)
        Spacer(Modifier.weight(1f))
    }
}

/** 重试行左侧的小转圈：淡底轨道 + 一段亮度弧，与组头流光同色。 */
@Composable
private fun RetrySpinner() {
    val transition = rememberInfiniteTransition(label = "retrySpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "retrySpinnerAngle"
    )
    val track = UiColors.accentFg.copy(alpha = 0.22f)
    val arc = UiColors.accentFg
    Canvas(Modifier.size(12.dp)) {
        val stroke = 1.6.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(stroke)
        )
        drawArc(
            color = arc,
            startAngle = angle,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

/** 过程组头：运行中给出「正在做什么 · 实时预览」，已封闭给一行计数；点击展开或收起该组。 */
@Composable
internal fun ProcessGroupHeader(
    group: ProcessGroup,
    running: Boolean,
    expanded: Boolean,
    all: List<ChatMessage>,
    onToggle: () -> Unit
) {
    // 运行中的组头只算一次 liveHeader：label 与 detail 都取自同一份结果，避免重复扫描成员
    val live = if (running) liveHeader(group, all) else null
    val label = live?.label ?: groupLabel(group, running, all)
    // 细节只在运行中给：封闭后的组头是一行计数，不带预览
    val detail = live?.detail.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
        if (running) {
            StreamShimmerText(if (detail.isEmpty()) label else label + " · " + detail)
        } else {
            Text(
                text = label,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/** 运行中的一组：组头 + 展开时的成员片段。封闭后自动收起（用户手动展开的除外）。 */
@Composable
private fun ProcessGroupBlock(
    group: ProcessGroup,
    running: Boolean,
    all: List<ChatMessage>,
    lastMessageIndex: Int?,
    toolOutcomes: Map<String, Boolean>,
    thinkColor: Color,
    bubbleColor: Color,
    cardColor: Color
) {
    // 默认收起，运行中也一样（DSH 的 disclosure 初值恒为收起）：过程不该在生成期间占满屏幕，
    // 实时进展由组头那行「正在做什么 · 最新一句」承担。
    var expanded by rememberSaveable(group.key) { mutableStateOf(false) }
    // 组一封闭就收起：这正是「正文出现后工具折叠起来」的那一步
    LaunchedEffect(running) { if (!running) expanded = false }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProcessGroupHeader(group, running, expanded, all) { expanded = !expanded }
        if (expanded) {
            TurnStream(
                segments = group.members,
                all = all,
                streaming = running,
                lastMessageIndex = lastMessageIndex,
                toolOutcomes = toolOutcomes,
                thinkColor = thinkColor,
                bubbleColor = bubbleColor,
                cardColor = cardColor
            )
        }
    }
}

/**
 * 运行中的一轮：过程组同样默认为收起，组外正文照常显示。
 *
 * 与整轮折叠的分工：这里只在流式期间使用，组头承担实时进展（「正在做什么 · 最新一句」+ 流光），
 * 成员要用户点开才看；本轮一结束，整轮统一收成一行汇总，只留最终回复。
 */
@Composable
internal fun TurnLiveStream(
    blocks: List<TurnBlock>,
    all: List<ChatMessage>,
    lastMessageIndex: Int?,
    toolOutcomes: Map<String, Boolean>,
    thinkColor: Color,
    bubbleColor: Color,
    cardColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is TurnBlock.Group -> key(block.group.key) {
                    ProcessGroupBlock(
                        group = block.group,
                        // 只有最后一组可能在跑：后续正文一出现，前一组即应收起
                        running = index == blocks.lastIndex,
                        all = all,
                        lastMessageIndex = lastMessageIndex,
                        toolOutcomes = toolOutcomes,
                        thinkColor = thinkColor,
                        bubbleColor = bubbleColor,
                        cardColor = cardColor
                    )
                }

                is TurnBlock.Text -> key("b" + block.body.messageIndex) {
                    TurnStream(
                        segments = listOf(block.body),
                        all = all,
                        streaming = true,
                        lastMessageIndex = lastMessageIndex,
                        toolOutcomes = toolOutcomes,
                        thinkColor = thinkColor,
                        bubbleColor = bubbleColor,
                        cardColor = cardColor
                    )
                }
            }
        }
    }
}

/**
 * 触发行：这一轮不是用户发言触发，而是后台任务结算等通知，只说明来源。
 *
 * 与 DSH 一致：通知不画成用户气泡，回合上方留一行小字（「后台任务状态更新 · 这条通知触发了本轮回复」）。
 */
@Composable
internal fun TurnTriggerLine(trigger: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = trigger + " · 这条通知触发了本轮回复",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
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

                    is TurnSegment.Call -> {
                        val result = toolResultMessageOf(all, segment.call)
                        val output = result?.content
                        // 提问条目：展开后只给一块可读正文（题面 / 选项 / 答复 / 状态），
                        // 不再分「参数」「输出」两段；标题、行高、折叠箭头与其他工具条目同源。
                        val askDetail = if (segment.call.name == ASK_TOOL_NAME) {
                            askDetailText(segment.call.arguments, output)
                        } else {
                            null
                        }
                        ToolBlock(
                            toolName = segment.call.name,
                            arguments = segment.call.arguments,
                            output = output,
                            ok = toolOutcomes[segment.call.id],
                            containerColor = cardColor,
                            detailText = askDetail,
                            copyText = askDetail,
                            // 中止后补写的合成结果：状态词显示「已中止」，不再误报「完成」
                            aborted = result?.toolAborted == true
                        )
                    }
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
    // 随之增至数万像素，图层内的子节点（代码块的横滑、复制、换行按钮）将无法接收触摸事件
    // 此时 AI 消息中的代码块无法滑动，而复制到用户气泡后却可滑动。
    // （15651px / 27187px 两种气泡高度上都实测过）。
    // background(color, shape) 圆角与 Surface 视觉一致，但不建图层。
    Box(
        modifier = Modifier
            .widthIn(max = LocalUiWindow.current.assistantBubbleMaxWidth)
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
        // 复制按钮只在展开时出现：折叠态只留标题行，不给「复制看不见的内容」的入口
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .saltRowClickable(headerShape) {
                        expanded = !expanded
                    }
                    // 固定行高：复制按钮只在展开时出现，行高不能随之变化，否则标题会上下跳
                    .height(ThinkHeaderHeight)
                    .padding(horizontal = 12.dp),
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
                // 复制为常显的图标按钮：只在展开时出现，位置与宽度固定，右侧「展开 / 收起」不位移
                if (expanded && reasoning.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    CopyActionButton(
                        copied = copied,
                        ink = MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            copyCode(context, reasoning, "思考过程")
                            copied = true
                        }
                    )
                }
                Spacer(Modifier.width(4.dp))
                // 展开 / 收起改用箭头：文字标签随状态换词，箭头本身即表示方向，
                // 与过程组头、工具卡使用同一套折叠箭头（Chevron）
                Chevron(
                    expanded = expanded,
                    size = ChevronSize.SMALL,
                    kind = ChevronKind.RIGHT_DOWN,
                    tint = UiColors.accentFg
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

/**
 * 标题行的固定行高。
 *
 * 复制按钮 30dp 高于 12sp 标题（约 16dp），若按内容撑开，展开时行高会从 36dp 涨到 50dp，
 * 标题随之被推下约 7dp。取 36dp 与「内距 10dp + 文字 16dp」等价，按钮居中、上下各留 3dp，
 * 展开与收起两种状态下标题位置完全一致。
 */
private val ThinkHeaderHeight = 36.dp
