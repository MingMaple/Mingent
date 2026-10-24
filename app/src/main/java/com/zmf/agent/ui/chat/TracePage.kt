package com.zmf.agent.ui.chat

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.ui.common.BoundedScrollColumn
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.theme.Motion
import com.zmf.agent.ui.theme.Palette
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 「轨迹」页：把当前会话的消息还原成一条按时间先后排的扁平时间线。
 *
 * 全部从传入的 messages 现推，不落盘：轨迹只是历史的另一种视图，
 * 多存一份快照就多一处会和真实消息对不上的地方。
 * 但「现推」不等于「每帧重推」—— 只在条数或头尾内容量变了时重推一次，
 * 否则流式输出每来一个 token 都要把整段历史重新拼一遍字符串。
 *
 * 版式照用户的参考截图：每行 = 彩色类型标签 + 一行摘要 + 行尾结果预览。
 * 用标签而不是「缩进 + 说明小字」，是因为一轮里助手 / 思考 / 工具会交替出现，
 * 缩进只能表达层级、表达不了「这一行是什么」，标签扫一眼就能分类。
 */
@Composable
fun TracePage(
    sessionTitle: String,
    messages: List<ChatMessage>,
    onBack: () -> Unit,
    // 图片缩略图的解码根：ChatMessage.images 存的是工作区相对路径，解析口径与主页一致
    // （主页那份 imageRoot 在 ChatScreen 里算好后一起传进来）。传 null 只是不画缩略图，不会崩。
    imageRoot: File? = null
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 不缓存「旧结果」，只缓存「同样的输入」：messages 是原地 append 的快照列表，
    // 拿列表身份当 key 会永远命中第一帧；改看「条数 + 头尾两条的内容量」——
    // 流式时末条在涨、静止时不变，静止状态下展开行、滚动、框选都不再重推一遍字符串。
    // 头一条的对象身份也进 key：换会话时条数与长度都可能撞上，但对象不会是同一个
    val trace = remember(
        messages.size,
        headLength(messages),
        tailWeight(messages),
        System.identityHashCode(messages.firstOrNull())
    ) {
        buildTrace(messages)
    }
    val rounds = trace.rounds
    // 展开状态提到页面级：同时只允许一行展开，换一行就自动收起上一行
    var expandedKey by remember { mutableStateOf<String?>(null) }
    // 概览里选中的块（行 key 集合）；空集 = 没框，整页正常显示
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 点开的图片：与主页同一个 ImageViewer，整条消息的图片一起传，查看器里左右横滑成组翻页
    var viewer by remember { mutableStateOf<TraceViewerTarget?>(null) }
    val listState = rememberLazyListState()
    // 轮、条目、块三张表是同一次遍历的产物，所以一起缓存：分开缓存会各自失效、互相错位
    val entries = trace.entries
    val events = trace.events
    // 展开卡片最多占屏高 55%，剩下的留给时间线本身，不然点开一条就看不见上下文了
    val maxDetailHeight = LocalConfiguration.current.screenHeightDp.dp * DetailMaxScreenRatio

    // 首次组合后直接停在最新一轮：老会话几十轮，从第一轮开始看等于每次都得自己拖到底
    var jumpedToLatest by remember { mutableStateOf(false) }
    LaunchedEffect(entries.size) {
        if (jumpedToLatest || entries.isEmpty()) return@LaunchedEffect
        jumpedToLatest = true
        // 不用动画：进来就该看见最新一轮，不需要看一遍从头滚到尾的过程
        listState.scrollToItem(entries.lastIndex)
    }

    // 松手后滚到选中第一块所在的那一行：不然框在概览上、内容却在屏幕外，联动等于没做。
    // 概览只在松手时下发集合，拖动中这个 key 不变，列表一帧都不会跟着跳
    LaunchedEffect(selection) {
        if (selection.isEmpty()) return@LaunchedEffect
        val target = entries.indexOfFirst { entry ->
            val key = entry.line?.key
            key != null && key in selection
        }
        if (target >= 0) listState.animateScrollToItem(target)
    }

    Surface(
        // 页面铺满且不透明；最外层吞掉点击，避免穿透点到下面的聊天界面
        modifier = Modifier
            .fillMaxSize()
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "轨迹",
                    // 箭头去掉后标题自己撑起这块留白，右缘与下面列表的 16dp 内容边对齐
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = sessionTitle,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp, end = 16.dp),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = onSurface.copy(alpha = 0.45f)
                )
            }

            if (rounds.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "这一轮还没有记录",
                        fontSize = 15.sp,
                        color = onSurface.copy(alpha = 0.35f)
                    )
                }
            } else {
                // 概览固定在时间线上方：框住哪几块，下面就滚到哪，没框的行灰掉
                TraceOverview(
                    events = events,
                    selection = selection,
                    onSelect = { selection = it }
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 0.dp,
                        bottom = 28.dp
                    ),
                    // 到顶 / 到底继续滑不再把整块内容顶起来：长表回弹会让人以为滚错了位置
                    overscrollEffect = null
                ) {
                    items(
                        entries,
                        key = { entry -> entry.line?.key ?: "h" + entry.roundNumber }
                    ) { entry ->
                        val line = entry.line
                        // 灰的是「没选中的行」，轮分隔永远不灰：它只是分隔，不是内容
                        val faded = line != null && selection.isNotEmpty() &&
                            line.key !in selection
                        val dim by animateFloatAsState(
                            targetValue = if (faded) RowDimAlpha else 1f,
                            animationSpec = Motion.spring(),
                            label = "traceRowDim"
                        )
                        Box(Modifier.graphicsLayer { alpha = dim }) {
                            if (line == null) {
                                RoundLabel(entry.roundNumber)
                            } else {
                                TraceRow(
                                    line = line,
                                    expanded = expandedKey == line.key,
                                    maxDetailHeight = maxDetailHeight,
                                    imageRoot = imageRoot,
                                    onImageClick = { paths, start ->
                                        viewer = TraceViewerTarget(paths, start)
                                    },
                                    onToggle = {
                                        // 点的是被压暗的行（框选之外的）：先展开它，再把框选收掉 ——
                                        // 压暗与概览上的高亮都由 selection 推出来，清空 selection 就一起复原。
                                        // 这里只会被 TraceRow 的 clickable 调到：拖动 / 滑动一旦超过 touchSlop
                                        // 就会被滚动或外层手势吃掉，不会走进这个回调。
                                        if (faded) {
                                            expandedKey = line.key
                                            selection = emptySet()
                                        } else {
                                            expandedKey = if (expandedKey == line.key) null else line.key
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 图片查看器是本页的第二个根节点：SaltFullPane 的 panel 槽是 Box，后画的压在最上层。
    // 查看器自己消费横滑（见 ImageViewer），不会漏给外层分页器。没有解码根就整块不画。
    val viewerRoot = imageRoot
    if (viewerRoot != null) {
        viewer?.let { target ->
            ImageViewer(
                paths = target.paths,
                startIndex = target.startIndex,
                root = viewerRoot,
                onDismiss = { viewer = null }
            )
        }
    }
}

/** 点开的那组图片：paths 是整条消息的图片（工作区相对路径），startIndex 是点中的那张。 */
private data class TraceViewerTarget(val paths: List<String>, val startIndex: Int)

/** 行高压到 30dp：同一屏多放三分之一行，扫长轨迹时少滑几次。 */
private val RowHeight = 30.dp

/** 子工具缩进：层级靠这一格表达，比多加一个标签省地方。 */
private val SubRowIndent = 12.dp

/** 标签与展开卡片的圆角：标签 8dp 是截图里的胶囊比例，卡片 18dp 与全站二级窗口一致。 */
private val ChipCorner = 8.dp
private val DetailCorner = 18.dp

/** 思考详情的限高：和主页思考框同一口径（180dp），别让一整屏都是过程。 */
private val ThinkingDetailMaxHeight = 180.dp

/** 「复制」按钮的固定占位宽度：宽度固定，出现/消失不会挤动旁边任何东西。 */
private val TraceCopySlot = 46.dp

/** 展开卡片占屏高的上限。 */
private const val DetailMaxScreenRatio = 0.55f

/** 摘要字数：正文 40 字、结果预览 42 字、工具参数 36 字，都是一行放得下的量。 */
private const val HeadLimit = 40
private const val TailLimit = 42
private const val ArgLimit = 36

/** 工具还在跑时的行尾状态：单独提出来，配色和「→ 结果」区分开。 */
private const val RunningText = "执行中…"

/** 彩色标签上的字统一用近黑：绿 / 橙 / 紫三块实心底都够亮，深字才压得住。 */
private val ChipInk = Color(0xFF14161A)

/** 工具橙与子工具浅橙：截图里子工具那档明显更淡，一眼能分出「谁派出去的」。 */
private val ChipOrange = Color(0xFFF0A93B)
private val ChipOrangeSoft = Color(0xFFF7CC85)

/** 连续空白折成一个空格；放常量里免得每行都新建正则。 */
private val Spaces = Regex("[ \t]{2,}")

/**
 * 时间线里一行的类型，同时也是行首标签的文字。
 *
 * 数据里没有「上下文」这种角色，它是用户消息里那一段图片 / 附件路径 ——
 * 那些是喂给模型的资料、不是人说的话，所以从提问正文里裁出来单独成行，
 * 也和截图里「上下文 图片 ×1 · <path>」那一行对得上。
 */
private enum class TraceKind(val label: String) {
    User("用户"),
    Assistant("助手"),
    Thinking("思考"),
    Tool("工具"),
    SubTool("子工具"),
    Context("上下文")
}

/** 时间线上的一行。所有摘要都在 buildRounds 里算好，行 Composable 只负责画。 */
private data class TraceLine(
    val key: String,
    val kind: TraceKind,
    /** 标签右边那一行摘要。 */
    val headline: String,
    /** 行尾结果预览；空串表示这行没有。 */
    val tail: String,
    /** 工具 / 路径这类内容用等宽，正文用默认字体。 */
    val mono: Boolean,
    /** 工具还没拿到结果：行尾改用「执行中」并换成紫色。 */
    val running: Boolean = false,
    /** 展开卡标题里的名字（工具名）；null 就用类型名。 */
    val name: String? = null,
    /** 非工具行展开卡的正文。 */
    val detail: String = "",
    /** 用户消息带的图片（工作区相对路径）：展开卡里画一排缩略图，点击进同一个查看器。 */
    val images: List<String> = emptyList(),
    /** 工具行展开卡的参数原文（美化推迟到展开时做）。 */
    val args: String? = null,
    /** 工具行展开卡的结果段。 */
    val result: String? = null,
    /** 这条消息用了多久；消息里没有这项数据就留 null，不编一个数字上去。 */
    val durationMs: Long? = null
)

/** 一轮：一条 user 消息开启，到下一条 user 之前都算这一轮。 */
private data class TraceRound(
    val number: Int,
    val lines: List<TraceLine>
)

/**
 * 把消息摊成「轮 → 行」。
 *
 * tool 结果先按 toolCallId 建一次索引，避免每条调用行都去扫全表（长会话下是 O(n²)）；
 * 首个 user 之前的助手 / 工具消息不成轮（没有提问就不存在步骤），直接丢掉。
 */
private fun buildRounds(all: List<ChatMessage>): List<TraceRound> {
    val results = HashMap<String, String>()
    for (message in all) {
        if (message.role != "tool") continue
        val id = message.toolCallId
        if (!id.isNullOrEmpty()) results[id] = message.content
    }

    val rounds = ArrayList<TraceRound>()
    var lines: MutableList<TraceLine>? = null

    all.forEachIndexed { index, message ->
        when (message.role) {
            "user" -> {
                lines?.let { rounds.add(TraceRound(rounds.size + 1, it)) }
                val fresh = ArrayList<TraceLine>()
                val body = askBody(message)
                val head = headLine(body, HeadLimit)
                fresh.add(
                    TraceLine(
                        key = "u" + index,
                        kind = TraceKind.User,
                        headline = head.ifEmpty { "（无正文）" },
                        tail = "",
                        detail = body,
                        images = message.images,
                        mono = false
                    )
                )
                // 图片一行一张、附件合成一行：路径清单在提问正文里已经裁掉了，
                // 裁掉之后还得能看见「这一轮喂了什么」，否则轨迹会显得凭空冒出工具调用
                message.images.forEachIndexed { slot, path ->
                    fresh.add(contextLine("c" + index + "_" + slot, "图片", listOf(path)))
                }
                val files = attachmentPaths(message.content)
                if (files.isNotEmpty()) fresh.add(contextLine("f" + index, "附件", files))
                lines = fresh
            }

            "assistant" -> {
                val target = lines ?: return@forEachIndexed
                val thinking = message.reasoningContent.orEmpty()
                val body = message.content
                // 工具轮之间会留下内容、思考、调用全空的占位助手消息，它没有可看的东西
                if (thinking.isBlank() && body.isBlank() && message.toolCalls.isEmpty()) {
                    return@forEachIndexed
                }
                // 先思考、再正文、最后工具调用：和模型实际的产出顺序一致
                if (thinking.isNotBlank()) {
                    target.add(
                        TraceLine(
                            key = "t" + index,
                            kind = TraceKind.Thinking,
                            headline = headLine(thinking, HeadLimit).ifEmpty { "（思考中）" },
                            tail = "",
                            detail = thinking,
                            mono = false
                        )
                    )
                }
                if (body.isNotBlank()) {
                    val flatBody = flat(body)
                    val head = oneLine(flatBody, HeadLimit)
                    // 结尾预览只在正文真的比首行长时才补，否则就是把首行再抄一遍
                    val tail = if (flatBody.length > head.length + 12) {
                        flatBody.takeLast(TailLimit)
                    } else {
                        ""
                    }
                    target.add(
                        TraceLine(
                            key = "a" + index,
                            kind = TraceKind.Assistant,
                            headline = head,
                            tail = tail,
                            detail = body,
                            mono = false
                        )
                    )
                }
                message.toolCalls.forEachIndexed { slot, call ->
                    target.add(callLine("k" + index + "_" + slot, call, results[call.id]))
                }
            }

            // tool 结果已经并进调用行；system 不进轨迹
            else -> Unit
        }
    }
    lines?.let { rounds.add(TraceRound(rounds.size + 1, it)) }
    return rounds
}

/**
 * 页面一次推导出来的三张表：轮 → 时间线条目 → 概览块。
 *
 * 三者必须是同一次遍历的产物（块的 order 就是条目里的先后），所以合成一个对象整体缓存；
 * 拆成三个 remember 会各自失效、互相错位，缓存反而成了 bug 的来源。
 */
private class TraceModel(
    val rounds: List<TraceRound>,
    val entries: List<TraceEntry>,
    val events: List<TraceEvent>
)

/** 头一条正文的长度：换会话时条数常常撞上，拿它当第二道门。 */
private fun headLength(all: List<ChatMessage>): Int = all.firstOrNull()?.content?.length ?: 0

/** 末条消息的「内容量」：流式时随 token 增长，静止时不变，正好当缓存键。 */
private fun tailWeight(all: List<ChatMessage>): Int {
    val last = all.lastOrNull() ?: return 0
    // 正文、思考、工具调用三项任一变多都说明这一条还在长
    return last.content.length + last.reasoningContent.orEmpty().length + last.toolCalls.size
}

/** 一次遍历摊出三张表：轮分隔和行混排进同一条下标体系，概览的块与行一一对应。 */
private fun buildTrace(all: List<ChatMessage>): TraceModel {
    val rounds = buildRounds(all)
    val entries = ArrayList<TraceEntry>()
    val events = ArrayList<TraceEvent>()
    rounds.forEach { round ->
        entries.add(TraceEntry(round.number, null))
        round.lines.forEach { line ->
            entries.add(TraceEntry(round.number, line))
            events.add(TraceEvent(laneOf(line.kind), line.key, round.number, events.size))
        }
    }
    return TraceModel(rounds, entries, events)
}

/**
 * 工具调用行。
 *
 * 「子工具」靠 mcp__ 前缀认：消息里没有嵌套调用的记录，唯一有父子关系的就是
 * MCP 工具（mcp__服务名__工具名）——它挂在某个服务下面，和截图里 run_code 下挂的子调用是同一层意思。
 */
private fun callLine(key: String, call: ToolCall, result: String?): TraceLine {
    val sub = call.name.startsWith("mcp__")
    val headline = if (sub) {
        call.name + " " + oneLine(flat(call.arguments), ArgLimit)
    } else {
        val summary = argSummary(call.arguments)
        if (summary.isEmpty()) "# " + call.name else "# " + call.name + " " + summary
    }
    return TraceLine(
        key = key,
        kind = if (sub) TraceKind.SubTool else TraceKind.Tool,
        headline = headline,
        tail = if (result == null) RunningText else oneLine(result, TailLimit),
        mono = true,
        running = result == null,
        name = call.name,
        // 参数只存原文：缩进美化要解析 JSON，绝大多数行根本不会展开，留到展开时再算
        args = call.arguments.ifBlank { null },
        result = result ?: RunningText
    )
}

/** 上下文行：摘要里放路径，完整路径留给展开卡片。 */
private fun contextLine(key: String, label: String, paths: List<String>): TraceLine = TraceLine(
    key = key,
    kind = TraceKind.Context,
    headline = label + " ×" + paths.size + " · " + oneLine(paths.joinToString(" "), ArgLimit),
    tail = "",
    detail = paths.joinToString("\n"),
    mono = true
)

/** 轮分隔：一条极淡的细线 + 「第 N 轮」小字，用来锚住时间顺序，不抢标签的位置。 */
@Composable
private fun RoundLabel(number: Int) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "第 " + number + " 轮",
            fontSize = 10.sp,
            color = onSurface.copy(alpha = 0.38f)
        )
        Box(
            Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .height(1.dp)
                .background(onSurface.copy(alpha = 0.06f))
        )
    }
}

/**
 * 时间线的一行：标签 + 摘要 + 结果预览，点一下在正下方坠出详情卡片。
 *
 * 详情做成「行下坠」而不是跳页：截图里的详情在右侧是另一套布局，
 * 手机上没有右边，下坠能把「这一条说了什么」和「它在时间线的哪一格」绑在一起。
 */
@Composable
private fun TraceRow(
    line: TraceLine,
    expanded: Boolean,
    maxDetailHeight: Dp,
    imageRoot: File?,
    onImageClick: (List<String>, Int) -> Unit,
    onToggle: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 子工具比工具暗一档也要小一档：同一层里两种工具，字号是第二个区分手段
    val sub = line.kind == TraceKind.SubTool
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RowHeight)
                .clickable { onToggle() }
                .padding(start = if (sub) SubRowIndent else 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KindChip(line.kind)
            Text(
                text = line.headline,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 7.dp),
                fontSize = if (sub) 11.sp else 12.sp,
                fontFamily = if (line.mono) FontFamily.Monospace else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.9f)
            )
            if (line.tail.isNotEmpty()) {
                Text(
                    // 结果预览统一带箭头前缀，和摘要一眼分得开；「执行中」是状态而不是结果，不加箭头
                    text = if (line.running) line.tail else "→ " + line.tail,
                    modifier = Modifier
                        .weight(0.95f)
                        .padding(start = 8.dp),
                    fontSize = if (sub) 9.sp else 10.sp,
                    fontFamily = if (line.mono) FontFamily.Monospace else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    color = if (line.running) Palette.AccentPurple else onSurface.copy(alpha = 0.42f)
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = Motion.spring()) +
                fadeIn(animationSpec = Motion.spring()),
            exit = shrinkVertically(animationSpec = Motion.spring()) +
                fadeOut(animationSpec = Motion.spring())
        ) {
            DetailCard(
                line = line,
                maxHeight = maxDetailHeight,
                imageRoot = imageRoot,
                onImageClick = onImageClick
            )
        }
    }
}

/** 行首彩色标签：实心底 + 近黑字，颜色就是这一行的类型。 */
@Composable
private fun KindChip(kind: TraceKind) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 思考是「过程」不是「产物」，用中性灰才不跟产物抢注意力
    val quiet = kind == TraceKind.Thinking
    val chipColor = when (kind) {
        TraceKind.Assistant -> Palette.AccentPurple
        TraceKind.Tool -> ChipOrange
        TraceKind.SubTool -> ChipOrangeSoft
        TraceKind.User, TraceKind.Context -> Palette.AccentGreen
        TraceKind.Thinking -> onSurface.copy(alpha = 0.12f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(ChipCorner))
            .background(chipColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = kind.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            color = if (quiet) onSurface.copy(alpha = 0.62f) else ChipInk
        )
    }
}

/**
 * 展开的子窗口。
 *
 * 工具调用整块交给共用 [ToolBlock]：参数折叠成一行摘要、结果 240dp 内滚、复制按钮
 * 都用它那一份实现，轨迹页这边只保留「从哪一行坠出来」的定位与上下留白，
 * 不再自己画「参数 / 结果」两段自绘 UI。
 *
 * 其余行（用户 / 助手 / 思考 / 上下文）仍是原来的浮层：18dp 圆角、6dp 阴影、1dp 发丝边，
 * 标题钉住、正文滚 —— 翻一屏长思考的时候还得知道自己在看哪一行。
 */
@Composable
private fun DetailCard(
    line: TraceLine,
    maxHeight: Dp,
    imageRoot: File?,
    onImageClick: (List<String>, Int) -> Unit
) {
    // 工具 / 子工具：工具名、参数、结果全部取 TraceLine 里已有的字段，直接喂给 ToolBlock
    if (line.kind == TraceKind.Tool || line.kind == TraceKind.SubTool) {
        TraceToolCard(
            toolName = line.name.orEmpty(),
            arguments = line.args.orEmpty(),
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            // 还在跑的时候 result 里存的是占位、不是真结果：传 null，状态就先空着
            output = if (line.running) null else line.result,
            // 消息里没有「这一步成功与否」这一项，拿不到就不编：传 null
            // （ToolBlock 对「结果已返回、成败未知」显示「完成」＝ 调用已返回，不是断言成功）
            ok = null
        )
        return
    }

    // 非工具行：正文浮层，长文在卡片内滚。
    // 思考卡的限高单独收紧到 180dp（和主页的思考框同一口径）：整屏高的思考卡
    // 看着就像正文，用户要的是「一眼扫过过程」。
    val cap = if (line.kind == TraceKind.Thinking) ThinkingDetailMaxHeight else maxHeight
    val onSurface = MaterialTheme.colorScheme.onSurface
    val context = LocalContext.current
    // 「复制」的全局唯一身份：摸别的块时按钮会从这张卡身上挪走（和主页同一口径）
    val copyKey = rememberCopyBlockKey()
    val traceCopyVisible = CopyRevealHost.owner === copyKey
    var traceCopied by remember { mutableStateOf(false) }
    LaunchedEffect(traceCopied) {
        if (traceCopied) {
            delay(1200)
            traceCopied = false
        }
    }
    val shape = RoundedCornerShape(DetailCorner)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp)
            // 卡片内摸一下就显示「复制」
            .claimCopyOnGesture(copyKey)
            .shadow(elevation = 6.dp, shape = shape, clip = false)
            .border(1.dp, onSurface.copy(alpha = 0.06f), shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = line.name ?: line.kind.label,
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = onSurface.copy(alpha = 0.85f)
                )
                // 「复制」只占固定宽度：高度由文字自己决定，竖着才和同行文字对齐
                Box(
                    modifier = Modifier
                        .width(TraceCopySlot)
                        .then(
                            if (traceCopyVisible) {
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        copyCode(context, line.detail, line.kind.label)
                                        traceCopied = true
                                    }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    if (traceCopyVisible) {
                        Text(
                            text = if (traceCopied) "已复制" else "复制",
                            maxLines = 1,
                            fontSize = 11.sp,
                            color = onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 拿不到可靠用时就不写这一格：编个数字比留白更糟
                line.durationMs?.let { ms ->
                    Text(
                        text = durationText(ms),
                        fontSize = 10.sp,
                        color = onSurface.copy(alpha = 0.32f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // 卡片自己滚到底就停：再带动整页会让人以为外面那层也动了。
            // 光把 over-scroll 工厂关掉还不够——那只是去掉回弹光晕，滚到边界之后的位移照样会
            // 冒给外层列表。交给 BoundedScrollColumn：装得下时不挂拦截、超出时才认领边界余量。
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                BoundedScrollColumn(maxHeight = cap) {
                    // 用户消息带图：先画一排缩略图（与主页同边长、同圆角），点击进同一个查看器。
                    // 纯图片消息正文为空，就别再补一句「没有可显示的内容」。
                    val thumbRoot = imageRoot?.takeIf { line.images.isNotEmpty() }
                    if (thumbRoot != null) {
                        TraceThumbRow(
                            paths = line.images,
                            root = thumbRoot,
                            onTap = { index -> onImageClick(line.images, index) }
                        )
                        if (line.detail.isNotBlank()) Spacer(Modifier.height(8.dp))
                    }
                    if (thumbRoot == null || line.detail.isNotBlank()) {
                        DetailText(line.detail.ifEmpty { "（没有可显示的内容）" }, line.mono)
                    }
                }
            }
        }
    }
}

/** 轨迹页缩略图的边长与圆角：与主页已发送图片（SentThumbSide / SentCardShape）同规格。 */
private val TraceThumbSide = 96.dp
private val TraceThumbShape = RoundedCornerShape(14.dp)

/**
 * 展开卡里的一排图片缩略图：横向一行、超出横滑，点击交给页面去开查看器。
 *
 * 这些 composable 之所以没直接复用主页那份：主页的 SentImageThumb 是 file-private 的，
 * 跨文件拿不到；尺寸、圆角、crop 填充与「解码失败退成灰块」都照它抄一份，保证两页一致。
 */
@Composable
private fun TraceThumbRow(paths: List<String>, root: File, onTap: (Int) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        paths.forEachIndexed { index, relPath ->
            TraceImageThumb(relPath = relPath, root = root, onTap = { onTap(index) })
        }
    }
}

/** 一张缩略图：固定边长、圆角裁切、crop 填充；文件读不出来就退成一块底色加「图片」两个字。 */
@Composable
private fun TraceImageThumb(relPath: String, root: File, onTap: () -> Unit) {
    val bitmap = remember(relPath, root) {
        runCatching { BitmapFactory.decodeFile(File(root, relPath).absolutePath) }.getOrNull()
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(TraceThumbSide)
            .clip(TraceThumbShape)
            .then(
                if (bitmap == null) {
                    Modifier.background(onSurface.copy(alpha = 0.12f))
                } else {
                    Modifier
                }
            )
            .clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(text = "图片", fontSize = 12.sp, color = onSurface.copy(alpha = 0.5f))
        }
    }
}

/** 展开卡的正文：等宽段行距放宽一格，长参数才不至于黏成一坨。 */
@Composable
private fun DetailText(text: String, mono: Boolean) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Text(
        text = text,
        fontFamily = if (mono) FontFamily.Monospace else null,
        fontSize = if (mono) 12.sp else 13.sp,
        lineHeight = if (mono) 17.sp else 19.sp,
        color = onSurface.copy(alpha = if (mono) 0.75f else 0.86f)
    )
}

/** 用时的写法：不到 1 秒给毫秒，超过给一位小数的秒；不用 String.format 免掉本地区小数点的差异。 */
private fun durationText(ms: Long): String {
    if (ms < 1000L) return ms.toString() + " ms"
    return (ms / 1000L).toString() + "." + (ms % 1000L / 100L) + " s"
}

/** 压平成一行但不截断：换行与连续空白统一折成一个空格。 */
private fun flat(text: String): String {
    if (text.isEmpty()) return ""
    return text.split('\n', '\r')
        .joinToString(" ") { it.trim() }
        .replace(Spaces, " ")
        .trim()
}

/** 压成单行再截断：一行摘要只占一行。 */
private fun oneLine(text: String, limit: Int): String {
    // 只看开头一小段：工具结果动辄几十 KB，全量 split / join 会在流式输出时每帧都拖慢
    val window = if (text.length > limit * 4) text.substring(0, limit * 4) else text
    val flatText = flat(window)
    return if (flatText.length > limit) flatText.take(limit) + "…" else flatText
}

/** 取首行：思考与正文的摘要按「第一个人能读的句子」来截，比截到半句好读。 */
private fun headLine(text: String, limit: Int): String {
    if (text.isBlank()) return ""
    val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return oneLine(first, limit)
}

/**
 * 工具行右侧那句人话：优先取描述 / 命令这类一眼能读的字段，读不出再退回参数原文。
 * 截图里的工具行是「# run_code 解压并读第五包反馈」这种句子，不是一坨 JSON。
 */
private fun argSummary(raw: String): String {
    if (raw.isBlank()) return ""
    val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return oneLine(raw, ArgLimit)
    val preferred = listOf(
        "description", "command", "path", "file_path", "query", "url", "prompt",
        "text", "content", "name", "id"
    )
    for (key in preferred) {
        val value = obj.optString(key, "")
        if (value.isNotBlank()) return oneLine(value, ArgLimit)
    }
    val keys = obj.keys()
    while (keys.hasNext()) {
        val value = obj.optString(keys.next(), "")
        if (value.isNotBlank()) return oneLine(value, ArgLimit)
    }
    return oneLine(raw, ArgLimit)
}

/**
 * 提问正文：末尾那段「【图片·…】/【附件·…】+ 路径清单」是写给模型读文件的说明，
 * 轨迹里只留人写的那部分（与聊天页气泡的显示口径一致），路径另起「上下文」行。
 */
private fun askBody(message: ChatMessage): String {
    val cut = listOf(
        message.content.indexOf(IMAGE_PATHS_MARKER),
        message.content.indexOf(ATTACHMENT_PATHS_MARKER),
        // 批准执行时粘进去的计划正文同理：轨迹里也只留「同意执行了哪一份」
        message.content.indexOf(PLAN_DETAIL_MARKER)
    ).filter { it >= 0 }.minOrNull() ?: return message.content
    return message.content.substring(0, cut).trimEnd()
}

/** 附件没有结构化字段，只写在正文的「【附件·…】」段里：取那段里的「- 路径」行。 */
private fun attachmentPaths(content: String): List<String> {
    val start = content.indexOf(ATTACHMENT_PATHS_MARKER)
    if (start < 0) return emptyList()
    return content.substring(start + ATTACHMENT_PATHS_MARKER.length)
        .lines()
        .map { it.trim() }
        .filter { it.startsWith("- ") }
        .map { it.substring(2).trim() }
        .filter { it.isNotEmpty() }
}

/* ─────────────────────────── 概览：输入 / 模型 / 工具 ─────────────────────────── */

/** 一个块 3dp 宽、6dp 高：一块 = 一条轨迹事件，密到一屏装得下整段会话的节奏。 */
private val BlockWidth = 3.dp
private val BlockHeight = 6.dp

/** 块与块之间 2dp，lane 与 lane 之间也 2dp：三行摞起来 22dp，比一行正文还矮。 */
private val BlockGap = 2.dp
private val LaneGap = 2.dp

/** 块序列两端的留白，也是框选蓝条向外让出的那半个缝。 */
private val LaneLeading = 4.dp

/** 行首标签的宽度：20dp 刚好放下两个 8sp 的字。 */
private val LaneLabelWidth = 20.dp

/** 框选两侧的竖条：2dp 细线才不挡住色块本身。 */
private val SelectionBarWidth = 2.dp

/** 拖动条本体 4dp；可触摸区抬到 20dp，否则手指根本按不中。 */
private val PanBarHeight = 4.dp
private val PanBarTouch = 20.dp

/** 拖动条最短长度：块再多也得留一段抓得住。 */
private val PanBarMinThumb = 36.dp

/** 框选时手指离左右边缘多近开始自动平移概览；这条带子同时就是「贴边」的判定宽度。 */
private val AutoScrollEdge = 24.dp

/** 自动平移的最快速度（dp/秒）：越贴着边走得越快，离开判定带立刻停。 */
private val AutoScrollSpeed = 760.dp

/** 自动平移的推进节拍：约一帧一次，够顺滑又不至于空转。 */
private const val AutoScrollFrameMs = 16L

/** 选区外的块压到多淡：位置还看得出来，注意力留给框里的那几块。 */
private const val OutsideSelectionAlpha = 0.25f

/** 时间线里没被选中的行灰到多淡。 */
private const val RowDimAlpha = 0.3f

/** 三行 lane 的配色与时间线标签同色：输入绿、模型紫、工具橙。 */
private val LaneInputColor = Palette.AccentGreen
private val LaneModelColor = Palette.AccentPurple
private val LaneToolColor = ChipOrange

/** 时间线摊平后的一条：轮分隔（line 为 null）或一行内容。 */
private data class TraceEntry(
    val roundNumber: Int,
    val line: TraceLine?
)

/** 概览的三条 lane：读轨迹的三个视角，配色与时间线标签同色。 */
private enum class TraceLane(val label: String) {
    Input("输入"),
    Model("模型"),
    Tool("工具")
}

/**
 * 概览上的一块 = 时间线上的一行。
 *
 * [order] 是它在本会话全部事件里的位置，也是它在概览上的槽位：
 * 三行共用同一条时间轴，横着扫一眼就能看出「这一刻模型在说话还是工具在跑」。
 */
private data class TraceEvent(
    val lane: TraceLane,
    val key: String,
    val roundNumber: Int,
    val order: Int
)

/**
 * 概览的像素几何：一块一槽，按事件顺序从左往右铺。
 *
 * 槽位等距，所以「x 落在第几块」「区间盖住哪几块」都能直接除出来，不用扫数组；
 * 全用 px 是因为手势命中和 Canvas 绘制都在 px 空间里。
 */
private class LaneGeometry(
    val block: Float,
    val blockHeight: Float,
    val pitch: Float,
    val leading: Float,
    val contentWidth: Float
) {
    /** 第 [order] 块左缘的内容坐标。 */
    fun xOf(order: Int): Float = leading + order * pitch

    /**
     * 一段内容坐标区间盖住哪些槽位：边界只要落在块内，整块就算选中。
     *
     * 首末槽位各取整一次就够，中间那些天然全在区间里，不必逐块比较。
     */
    fun ordersIn(from: Float, to: Float, lastOrder: Int): IntRange? {
        if (lastOrder < 0 || pitch <= 0f) return null
        val left = ((minOf(from, to) - leading) / pitch).toInt().coerceIn(0, lastOrder)
        val right = ((maxOf(from, to) - leading) / pitch).toInt().coerceIn(0, lastOrder)
        return minOf(left, right)..maxOf(left, right)
    }
}

/**
 * 框选拖动中的两侧边界，单位是内容坐标（不是屏幕坐标）。
 *
 * 起点必须是内容坐标：贴边自动平移时内容会从手指底下走，
 * 记屏幕坐标会让起点跟着一起漂，那就不是「起始位固定」了。
 */
private data class SelectionDraft(val startX: Float, val endX: Float)

/** 一行内容落进哪条 lane：发言归输入，思考与正文归模型，工具调用归工具。 */
private fun laneOf(kind: TraceKind): TraceLane = when (kind) {
    TraceKind.User, TraceKind.Context -> TraceLane.Input
    TraceKind.Thinking, TraceKind.Assistant -> TraceLane.Model
    TraceKind.Tool, TraceKind.SubTool -> TraceLane.Tool
}

/** lane 配色沿用时间线标签：输入绿、模型紫、工具橙，一行一心智模型。 */
private fun laneColor(lane: TraceLane): Color = when (lane) {
    TraceLane.Input -> LaneInputColor
    TraceLane.Model -> LaneModelColor
    TraceLane.Tool -> LaneToolColor
}

/** 区间盖住的块：首末槽位取整到整块，中间整块全算。 */
private fun keysIn(
    geo: LaneGeometry,
    events: List<TraceEvent>,
    from: Float,
    to: Float
): Set<String> {
    val orders = geo.ordersIn(from, to, events.lastIndex) ?: return emptySet()
    val keys = LinkedHashSet<String>()
    for (order in orders) keys.add(events[order].key)
    return keys
}

/** 一组块在时间轴上占的最左 / 最右槽位，蓝条按它收边。 */
private fun orderRangeOf(events: List<TraceEvent>, keys: Set<String>): IntRange? {
    // 没框选时这个函数每帧都会被调（蓝条要判空），先挡掉，省掉一次全表扫描
    if (keys.isEmpty()) return null
    var first = -1
    var last = -1
    events.forEach { event ->
        if (event.key !in keys) return@forEach
        if (first < 0) first = event.order
        last = event.order
    }
    if (first < 0) return null
    return first..last
}

private fun laneGeometry(count: Int, density: Density): LaneGeometry {
    val block = with(density) { BlockWidth.toPx() }
    val height = with(density) { BlockHeight.toPx() }
    val pitch = block + with(density) { BlockGap.toPx() }
    val leading = with(density) { LaneLeading.toPx() }
    // 末尾不补一个缝：最后一块的右缘再加同一个留白，内容正好铺满
    val contentWidth = leading * 2f + if (count == 0) 0f else (count * pitch - (pitch - block))
    return LaneGeometry(block, height, pitch, leading, contentWidth)
}

@Composable
private fun LaneLabel(text: String) {
    Box(
        modifier = Modifier
            .width(LaneLabelWidth)
            .height(BlockHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            // 行高只有 6dp，字比行高：按自然高度居中溢出，否则上下会被裁掉半截
            modifier = Modifier.wrapContentHeight(Alignment.CenterVertically, unbounded = true),
            fontSize = 8.sp,
            lineHeight = 9.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
    }
}

/**
 * 顶部概览：三行 lane 按事件顺序从左往右铺块，下面挂一条拖动条。
 *
 * 一块 = 时间线一行，三行共用同一条时间轴：框选的最小单位因此从「轮」变成「块」，
 * 一轮里那几条工具调用可以只挑一条看，不再被轮的边界绑住。
 * 模块上的手势只干一件事 —— 框选：按下点就是区间起点、拖动点就是终点；
 * 横向平移平时交给下面那条细条，只有手指贴到左右边缘时才由框选自己带一段自动平移。
 */
@Composable
private fun TraceOverview(
    events: List<TraceEvent>,
    selection: Set<String>,
    onSelect: (Set<String>) -> Unit
) {
    val density = LocalDensity.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 几何只认块数：事件列表每帧都是新对象，拿它当 remember key 会让拖动中的手势反复重启
    val geo = remember(events.size, density) { laneGeometry(events.size, density) }
    var viewport by remember { mutableStateOf(0f) }
    var track by remember { mutableStateOf(0f) }
    var pan by remember { mutableStateOf(0f) }
    // 拖动中的草稿边界；非 null = 手指还按在概览上
    var draft by remember { mutableStateOf<SelectionDraft?>(null) }
    // 贴边自动平移的按帧协程挂在这个 scope 上：awaitEachGesture 是受限挂起上下文，
    // 在里面再开 coroutineScope 会连带把 awaitPointerEvent 变成非法调用
    val dragScope = rememberCoroutineScope()
    // 概览里该亮着的块：拖动中先跟着草稿走，松手后才把集合下发给时间线
    val activeKeys = draft?.let { keysIn(geo, events, it.startX, it.endX) } ?: selection
    // 蓝条收边只认选中的首末块；拖动中另用草稿的两端（贴手指，不吸附块边）
    val barOrders = if (draft == null) orderRangeOf(events, selection) else null
    val laneHeight = BlockHeight * 3 + LaneGap * 2
    val minThumb = with(density) { PanBarMinThumb.toPx() }
    // 内容放得下时 maxPan = 0：不许横推，拖动条也就铺满整条
    val maxPan = minOf(0f, viewport - geo.contentWidth)

    // 块变多 / 旋屏改宽后旧位移可能越界，收回 [maxPan, 0]
    LaunchedEffect(maxPan) { pan = pan.coerceIn(maxPan, 0f) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(LaneGap)
            ) {
                LaneLabel(TraceLane.Input.label)
                LaneLabel(TraceLane.Model.label)
                LaneLabel(TraceLane.Tool.label)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
                    .height(laneHeight)
                    .clipToBounds()
                    .onSizeChanged { viewport = it.width.toFloat() }
                    // 按下 = 起点、拖动 = 终点：区间由手指这两个点直接定义，不靠「按下点附近算个范围」
                    .pointerInput(geo, maxPan) {
                        val edge = AutoScrollEdge.toPx()
                        val speed = AutoScrollSpeed.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val slop = viewConfiguration.touchSlop
                            // 起点记内容坐标：自动平移时内容在手指底下走，起点不能跟着漂
                            val startX = down.position.x - pan
                            var fingerX = down.position.x
                            var dragging = false
                            var autoScroll: Job? = null
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    fingerX = change.position.x
                                    if (!dragging && abs(fingerX - down.position.x) > slop) {
                                        dragging = true
                                        // 手指停在边缘不动时 Compose 不再投递 pointer 事件，
                                        // 只靠事件驱动的自动平移会立刻停住，所以另起一个按帧推进的协程
                                        if (maxPan < 0f && viewport > edge * 2f) {
                                            autoScroll = dragScope.launch {
                                                var last = SystemClock.uptimeMillis()
                                                while (isActive) {
                                                    delay(AutoScrollFrameMs)
                                                    val now = SystemClock.uptimeMillis()
                                                    val dt = (now - last).coerceIn(0L, 50L) / 1000f
                                                    last = now
                                                    if (dt <= 0f) continue
                                                    // 越贴边推得越快；离开判定带 push 归零，不会拖泥带水
                                                    val leftPush = (1f - fingerX / edge).coerceIn(0f, 1f)
                                                    val rightPush =
                                                        (1f - (viewport - fingerX) / edge).coerceIn(0f, 1f)
                                                    val push = leftPush - rightPush
                                                    if (push == 0f) continue
                                                    pan = (pan + push * speed * dt).coerceIn(maxPan, 0f)
                                                    draft = SelectionDraft(startX, fingerX - pan)
                                                }
                                            }
                                        }
                                    }
                                    if (dragging) {
                                        change.consume()
                                        draft = SelectionDraft(startX, fingerX - pan)
                                    }
                                    if (!change.pressed) break
                                }
                                // 松手才结算：两点定出块集合（谁前谁后都行）；没拖动过就当轻点清掉
                                if (dragging) {
                                    onSelect(keysIn(geo, events, startX, fingerX - pan))
                                } else {
                                    onSelect(emptySet())
                                }
                            } finally {
                                autoScroll?.cancel()
                                // 手势被打断（换 key / 节点移除）也要抹掉草稿，否则蓝条会卡在半路
                                draft = null
                            }
                        }
                    }
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = pan }
                ) {
                    val corner = CornerRadius(geo.block * 0.5f)
                    val rowStep = geo.blockHeight + LaneGap.toPx()
                    // 没框选时整条亮着；框了就只亮选中的块，其余压暗留个位置感
                    val dimming = activeKeys.isNotEmpty()
                    events.forEach { event ->
                        val alpha = if (dimming && event.key !in activeKeys) {
                            OutsideSelectionAlpha
                        } else {
                            1f
                        }
                        drawRoundRect(
                            color = laneColor(event.lane).copy(alpha = alpha),
                            topLeft = Offset(geo.xOf(event.order), event.lane.ordinal * rowStep),
                            size = Size(geo.block, geo.blockHeight),
                            cornerRadius = corner
                        )
                    }
                }
                // 蓝条单独一层：不跟着 translationX 走，始终压在块上面
                Canvas(Modifier.fillMaxSize()) {
                    val bar = SelectionBarWidth.toPx()
                    val half = LaneLeading.toPx() / 2f
                    val height = BlockHeight.toPx() * 3 + LaneGap.toPx() * 2
                    val radius = CornerRadius(bar / 2f)
                    val moving = draft
                    val left: Float
                    val right: Float
                    if (moving != null) {
                        // 拖动中两侧压在按下点与当前手指上，不吸附块边 —— 点哪算哪
                        left = minOf(moving.startX, moving.endX) + pan
                        right = maxOf(moving.startX, moving.endX) + pan
                    } else {
                        val orders = barOrders ?: return@Canvas
                        left = geo.xOf(orders.first) - half + pan
                        right = geo.xOf(orders.last) + geo.block + half + pan
                    }
                    drawRoundRect(
                        color = Palette.AccentBlue,
                        topLeft = Offset(left, 0f),
                        size = Size(bar, height),
                        cornerRadius = radius
                    )
                    drawRoundRect(
                        color = Palette.AccentBlue,
                        topLeft = Offset(right - bar, 0f),
                        size = Size(bar, height),
                        cornerRadius = radius
                    )
                }
            }
        }

        // 拖动条就放在模块正下方：拇指随块数增多而变短，并始终从左边起算
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PanBarTouch)
                .padding(horizontal = 16.dp)
                .onSizeChanged { track = it.width.toFloat() }
                .pointerInput(maxPan, track, viewport, geo.contentWidth) {
                    var startPan = 0f
                    var travelled = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            startPan = pan
                            travelled = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            travelled += dragAmount
                            val thumb = thumbLength(track, viewport, geo.contentWidth, minThumb)
                            val room = track - thumb
                            if (room > 1f && maxPan < 0f) {
                                // 拇指走满整条轨道 = 内容走满全部可平移量，所以是比例换算，不是 1:1
                                pan = (startPan + travelled * (maxPan / room))
                                    .coerceIn(maxPan, 0f)
                                change.consume()
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxWidth().height(PanBarHeight)) {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(
                    color = onSurface.copy(alpha = 0.07f),
                    cornerRadius = radius
                )
                // 内容没超宽时不画拇指：一条全宽的绿线会变成装饰，反而抢概览的注意力
                if (maxPan >= 0f) return@Canvas
                val thumb = thumbLength(size.width, viewport, geo.contentWidth, minThumb)
                if (thumb <= 0f) return@Canvas
                val room = size.width - thumb
                val fraction = if (room > 0f) pan / maxPan else 0f
                drawRoundRect(
                    color = Palette.AccentGreen.copy(alpha = 0.9f),
                    topLeft = Offset(room * fraction, 0f),
                    size = Size(thumb, size.height),
                    cornerRadius = radius
                )
            }
        }
    }
}

/** 拖动条拇指长度：按「屏宽 / 内容宽」的比例缩，太短就抓不住，所以兜一个下限。 */
private fun thumbLength(track: Float, viewport: Float, content: Float, minThumb: Float): Float {
    if (track <= 0f) return 0f
    if (viewport <= 0f || content <= viewport) return track
    return (track * (viewport / content)).coerceIn(minOf(minThumb, track), track)
}

