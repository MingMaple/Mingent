package com.zmf.agent.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.ui.common.BoundedScrollColumn
import com.zmf.agent.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * 轻量 Markdown 渲染：纯 Compose 手写解析，不依赖任何第三方库。
 *
 * 支持的语法（只做这些）：
 *  - 围栏代码块 ``` / ~~~（未闭合也当代码块渲染，流式输出常见）
 *  - 行内代码 `code`
 *  - 粗体 **text** / __text__
 *  - 斜体 *text* / _text_
 *  - 标题 # / ## / ###
 *  - 无序列表 "- " "* "（渲染成圆点前缀 + 缩进）
 *  - 有序列表 "1. "（保留原序号）
 *  - 分隔线 --- / *** / ___
 *  - 其余按普通段落
 *
 * 流式容错：任何未配对的标记（半个 **、单个 `）一律按字面输出，
 * 不抛异常，也不吞掉任何字符。
 *
 * 在 MessageRow 里的用法：
 *   MarkdownText(text = content, modifier = Modifier.padding(...))
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 15.sp,
    color: Color = MaterialTheme.colorScheme.onSurface,
    codeBackground: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
    /**
     * 是否把空行渲染成一段留白。
     *
     * 默认 false（AI 气泡、工具卡）：空行只作块间分隔，连续空行一律收起；
     * 用户气泡传 true：用户在输入框里按下的换行是他自己排版的一部分，
     * 吞掉就会变成「只剩几个标点」。发送时已把 3 个以上连续换行压成一个空行，
     * 所以这里最多只会多出一段留白，不会出现整屏空白。
     */
    keepBlankLines: Boolean = false,
    /**
     * 折叠阈值：正文完整高度超过它才折叠。默认 [Dp.Unspecified] = 不折叠（AI 气泡、工具卡走这条）。
     *
     * 折叠按**整块**算：只显示完整放得下的块（段落 / 列表项 / 代码块），绝不在一行中间截断，
     * 所以折叠态的结尾一定是某一段的末尾。放不下一块时至少保留第一块。
     */
    foldAt: Dp = Dp.Unspecified,
    /** 折叠态最多占的高度；实际显示高度是「不超过它、且以整块结尾」的那一段。 */
    foldedHeight: Dp = Dp.Unspecified,
    /** true = 展开（整段铺开）；false = 折叠。 */
    expanded: Boolean = true,
    /** 折叠态底部渐变的落点色，一般传气泡底色。 */
    foldColor: Color = Color.Unspecified,
    /** 气泡右下角的展开 / 收起按钮；由调用方提供，这里只负责摆位置。 */
    foldToggle: (@Composable () -> Unit)? = null,
    /**
     * 折叠按钮的落点基准 = **调用方气泡自己的内边距**（右 / 下）。
     *
     * 按钮要压在气泡右下角、离气泡边 6dp，所以它得落在正文外框之外那层内边距里。
     * 主页气泡是 16/12dp；悬浮小窗更窄更矮，内边距是 10/7dp，得跟着换，
     * 否则按钮会被推到气泡外面去。
     */
    foldToggleInsetX: Dp = 16.dp,
    foldToggleInsetY: Dp = 12.dp
) {
    // 解析结果按原文缓存，避免每帧重解析；text 变化（流式增量）时才会重算。
    // 这里再套一层进程级 LRU：列表滑出屏幕后 item 会被回收，滑回来 remember 已经没了，
    // 只靠 remember 的话每次滑回来都要把整段 Markdown 重新切一遍（长对话滚动卡顿的主因）。
    val blocks = remember(text, keepBlankLines) { parseMarkdownCached(text, keepBlankLines) }
    if (foldAt == Dp.Unspecified || foldedHeight == Dp.Unspecified) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (block in blocks) MarkdownBlock(block, fontSize, color, codeBackground)
        }
        return
    }
    val density = LocalDensity.current
    val gapPx = with(density) { 6.dp.roundToPx() }
    val foldAtPx = with(density) { foldAt.roundToPx() }
    val foldedPx = with(density) { foldedHeight.roundToPx() }
    val fadeHeight = 56.dp
    val blockCount = blocks.size
    Layout(
        modifier = modifier,
        content = {
            for (block in blocks) MarkdownBlock(block, fontSize, color, codeBackground)
            // 末尾两个不是正文：遮罩（盖住折叠处最后一行，暗示「下面还有」）与展开 / 收起按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(fadeHeight)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, foldColor)))
            )
            foldToggle?.invoke()
        }
    ) { measurables, constraints ->
        // 正文按不限高测量：完整高度是「折到第几块」的唯一依据。
        // 宽度仍按父级给的范围走，代码块才能照常横滑。
        val childConstraints = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = (0 until blockCount).map { measurables[it].measure(childConstraints) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
        else placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val total = placeables.sumOf { it.height } + gapPx * (blockCount - 1).coerceAtLeast(0)
        val foldable = total > foldAtPx
        // 折叠态只放到整块为止：一块放不下一行也不切，宁可只留一块
        var visible = blockCount
        if (foldable && !expanded) {
            var used = 0
            var count = 0
            for (index in 0 until blockCount) {
                val next = if (count == 0) placeables[index].height
                else used + gapPx + placeables[index].height
                if (next > foldedPx) break
                used = next
                count++
            }
            visible = count.coerceAtLeast(1)
        }
        val bodyHeight = placeables.take(visible).sumOf { it.height } + gapPx * (visible - 1).coerceAtLeast(0)
        val toggle = measurables[blockCount + 1].measure(Constraints())
        val edge = 6.dp.roundToPx()
        val height = bodyHeight
        layout(width, height) {
            var y = 0
            for (index in 0 until visible) {
                placeables[index].place(0, y)
                y += placeables[index].height + gapPx
            }
            if (!foldable) return@layout
            // 遮罩只盖折叠态的截断处：展开后整段可见，渐变反而会压住正文
            if (!expanded) {
                val fade = measurables[blockCount].measure(Constraints.fixed(width, fadeHeight.roundToPx()))
                fade.place(0, height - fade.height)
            }
            // 按钮始终压在气泡自己的右下角：折叠态是截断处，展开态是整段末尾。
            // 不跟着视线走，也不换角：按钮固定落在气泡右下角那一条上。
            // 压进内边距里（内边距由调用方给：主页 16/12dp、小窗 10/7dp），离气泡边 6dp。
            val toggleX = width + foldToggleInsetX.roundToPx() - edge - toggle.width
            val toggleY = height + foldToggleInsetY.roundToPx() - edge - toggle.height
            toggle.place(toggleX, toggleY)
        }
    }
}

/** 一个块的最小渲染单元：段落 / 标题 / 列表项 / 代码块 / 空行 / 分隔线。 */
@Composable
private fun MarkdownBlock(
    block: MdBlock,
    fontSize: TextUnit,
    color: Color,
    codeBackground: Color
) {
    when (block) {
        is MdBlock.Paragraph -> MarkdownParagraph(block.content, fontSize, color, codeBackground)
        is MdBlock.Heading -> MarkdownHeading(block.level, block.content, fontSize, color, codeBackground)
        is MdBlock.ListItem -> MarkdownListItem(
            marker = block.marker,
            content = block.content,
            indent = block.indent,
            fontSize = fontSize,
            color = color,
            codeBackground = codeBackground
        )
        is MdBlock.Code -> MarkdownCodeBlock(
            code = block.code,
            lang = block.lang,
            fontSize = fontSize,
            color = color,
            codeBackground = codeBackground,
            // 气泡里的代码块不设限高、不开块内竖滚：整段随消息一起铺开，
            // 快速滑动经过它时手势仍然归外层列表，不会被块内滚动截走
            boundedBody = false,
            // 行内滑到头之后那点余量就地吃掉：继续推不该把抽屉拉出来，也不该进轨迹页
            // （滑到尽头后就该毫无响应，不能改由父层接力）
            blockHorizontalAtEdge = true
        )
        // 空行：只在用户气泡里出现（见 keepBlankLines），一段留白＝一口换行
        MdBlock.Blank -> Spacer(Modifier.height(8.dp))
        MdBlock.Divider -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .height(1.dp)
                .background(color.copy(alpha = 0.15f))
        )
    }
}

/* ------------------------------- 渲染单元 ------------------------------- */

@Composable
private fun MarkdownParagraph(
    content: String,
    fontSize: TextUnit,
    color: Color,
    codeBackground: Color
) {
    val annotated = remember(content, fontSize, codeBackground) {
        inlineAnnotatedCached(content, fontSize, codeBackground)
    }
    Text(
        text = annotated,
        fontSize = fontSize,
        color = color,
        lineHeight = fontSize * 1.45f
    )
}

@Composable
private fun MarkdownHeading(
    level: Int,
    content: String,
    fontSize: TextUnit,
    color: Color,
    codeBackground: Color
) {
    // 层级越大字号越大：比例基于当前正文字号，便于调用方整体缩放。
    val scale = when (level) {
        1 -> 1.5f
        2 -> 1.32f
        else -> 1.16f
    }
    val headingSize = fontSize * scale
    val annotated = remember(content, headingSize, codeBackground) {
        inlineAnnotatedCached(content, headingSize, codeBackground)
    }
    Text(
        text = annotated,
        fontSize = headingSize,
        fontWeight = FontWeight.SemiBold,
        color = color,
        lineHeight = headingSize * 1.32f,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun MarkdownListItem(
    marker: String,
    content: String,
    indent: Int,
    fontSize: TextUnit,
    color: Color,
    codeBackground: Color
) {
    val annotated = remember(content, fontSize, codeBackground) {
        inlineAnnotatedCached(content, fontSize, codeBackground)
    }
    val lineHeight = fontSize * 1.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indent * 12).dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = marker, fontSize = fontSize, color = color, lineHeight = lineHeight)
        Text(
            text = annotated,
            modifier = Modifier.weight(1f),
            fontSize = fontSize,
            color = color,
            lineHeight = lineHeight
        )
    }
}

/**
 * 围栏代码块：顶部一条工具条（语言 /「缩进」/「复制」），下面是行号列 + 代码体。
 *
 * 默认**单行横滑**（不折行，长行靠横滑读），点「缩进」改成完整换行；
 * 行号等宽右对齐、半透明灰，宽度按总行数取 2–3 位。
 *
 * 「复制」跟「缩进」并列在工具条右端，但只有**摸过这一块**才出现（全局唯一，摸别的块会挪走）。
 * 单行横滑的正文保持原样，横向不受限高影响。
 *
 * 正文是否限高由 [boundedBody] 决定：
 *  - true（工具卡）：限高 240dp（[BoundedScrollColumn]），装得下随整页滚，真超了才在块内竖滚；
 *  - false（聊天气泡）：不设上限、不开块内竖滚，整段随消息铺开，快速滑动不被截走。
 * 调用方明确给出 [maxBodyHeight] 时按它的值。
 *
 * 「横滑到边界不传给外层」用同包的 [blockHorizontalAtEdge]：气泡里的代码块同样要拦住，
 * 否则滑到行末继续推会去拉抽屉或进轨迹页。
 *
 * ToolBlock 会复用本函数：参数区 / 命令区不要行号、不要复制按钮。
 */
@Composable
internal fun MarkdownCodeBlock(
    code: String,
    lang: String,
    fontSize: TextUnit,
    color: Color,
    codeBackground: Color,
    modifier: Modifier = Modifier,
    showCopy: Boolean = true,
    showLineNumbers: Boolean = true,
    maxBodyHeight: Dp = Dp.Unspecified,
    blockHorizontalAtEdge: Boolean = false,
    boundedBody: Boolean = true
) {
    // 代码块字号按正文字号等比缩小，默认 15sp -> 约 13sp。
    val codeFontSize = fontSize * 0.88f
    val codeLineHeight = codeFontSize * 1.5f
    val context = LocalContext.current
    // 每块独立：默认关（单行横滑），开启「缩进」后完整换行。
    // 不带 key：流式输出一段段变长时不要把用户选的状态弹回去。
    var wrapMode by remember { mutableStateOf(false) }
    var copied by remember(code) { mutableStateOf(false) }
    val copyKey = rememberCopyBlockKey()
    val hScroll = rememberScrollState()
    val langLabel = if (lang.isBlank()) "代码" else lang
    // 行号列：宽度按总行数取 2–3 位。等宽字宽约 0.6 em，再乘 fontScale，
    // 否则系统把字体放大后行号会被右对齐裁掉。
    val lines = remember(code) { code.split('\n') }
    val gutter = remember(lines) { lines.indices.joinToString("\n") { (it + 1).toString() } }
    val gutterDigits = lines.size.toString().length.coerceIn(2, 3)
    val fontScale = LocalDensity.current.fontScale
    val gutterWidth = (gutterDigits * 0.62f * codeFontSize.value * fontScale).dp + 2.dp
    val lineNumberColor = color.copy(alpha = 0.32f)
    // 正文限高：装得下就随整页滚，真超了才在块内竖滚（横滑不受影响）。
    // 调用方给了明确高度就用它，没给按 240dp。
    val bodyMaxHeight = if (maxBodyHeight != Dp.Unspecified) maxBodyHeight else 240.dp
    // 点「复制」后短暂显示「已复制」，1.2 秒复位
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    val toolbarColor = color.copy(alpha = 0.5f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 只有自己带复制按钮时才参与「谁显示复制」的竞争：
            // 参数块 / 命令区是 showCopy = false，以前它们也登记成持有者，
            // 结果摸到这些位置时整张卡的复制按钮反而不会出现（表现为「有些位置摸不出来」）。
            .then(if (showCopy) Modifier.claimCopyOnGesture(copyKey) else Modifier)
            .background(codeBackground, RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp)
    ) {
        // 工具条要跟着**可用宽度**走，而不是跟着某个固定尺寸：
        // 主页气泡宽 350dp、工具卡更宽，小窗却能被拖到很窄又随时拉宽。
        // 窄于 [ToolbarCompactWidth] 时先丢掉「复制」，保住「缩进」还贴右端、点得到。
        BoxWithConstraints {
            val compact = maxWidth < ToolbarCompactWidth
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左：语言标识（或「代码」「参数」这类标签）。
                // 用 weight(1f) 占满剩余宽度，把「缩进」和「复制」一起推到右端：
                // 标签是「下面这段是什么」的名称，缩进是「换个看法」的操作，
                // 两者紧挨着会被读成同一类东西；左右分开之后，标签在左、操作在右。
                Text(
                    text = langLabel,
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = color.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                // 「复制」「缩进」各占一块**同宽的固定槽**：两块紧邻、中间不留 Spacer，
                // 字在槽内居中，于是两枚的涟漪尺寸完全相同，按压时不会「一宽一窄」。
                // 槽宽收到 38dp 时两字的视觉间距约 15dp（56dp 槽 + 4dp Spacer 时约 25dp，太散）。
                // 槽宽下限 38dp（容得下「已复制」三个字）；系统字体放大时按字号同比放宽，
                // 免得「已复制」被裁掉。
                val actionSlot = if (compact) {
                    CodeActionSlotCompact
                } else {
                    maxOf(CodeActionSlot, with(LocalDensity.current) { 34.sp.toDp() } + 3.dp)
                }
                if (showCopy && !compact) {
                    val revealed = CopyRevealHost.owner === copyKey
                    Box(
                        modifier = Modifier
                            .width(actionSlot)
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (revealed) {
                                    Modifier.clickable {
                                        copyCode(context, code, langLabel)
                                        copied = true
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            // 竖向内距留在区域内部：涟漪跟着区域一起长，不会溢到相邻行
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (revealed) {
                            Text(
                                text = if (copied) "已复制" else "复制",
                                fontSize = 11.sp,
                                color = toolbarColor,
                                // 状态字绝不允许折行：折了第一行只剩「已复」，读起来像坏掉
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .width(actionSlot)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { wrapMode = !wrapMode }
                        // 命中区按手指给：11sp 的字本身才 15dp 高，竖向内距让涟漪与「复制」同高
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "缩进",
                        fontSize = 11.sp,
                        // 开启态用同色提高不透明度表示，不刷成 AccentBlue（#0078FF）：
                        // 主题是单色的，饱和蓝在整屏里是唯一的杂色；「已开启」的强调与
                        // 「完成」「已复制」这类状态字统一用同色浓淡区分。
                        color = if (wrapMode) color.copy(alpha = 0.92f) else toolbarColor
                    )
                }
            }
        }
        // 正文按调用方口径决定是否限高：气泡里不限高、不内滚（滑动始终归外层列表），
        // 工具卡仍保留限高与「滚到边界就地截住」的行为
        val body: @Composable () -> Unit = {
            if (wrapMode) {
                // 折行模式：每个逻辑行单独一个 Row，行号才能和「折成多行的那一行」逐行对齐。
                // 只有用户主动点开「缩进」才走这条分支（长代码建节点会多一些），
                // 平时走下面那个只有两个 Text 的两列布局。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp)
                ) {
                    lines.forEachIndexed { index, line ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            if (showLineNumbers) {
                                CodeLineNumber(
                                    text = (index + 1).toString(),
                                    width = gutterWidth,
                                    fontSize = codeFontSize,
                                    lineHeight = codeLineHeight,
                                    color = lineNumberColor
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                // 空行写成空格：Text("") 在 Compose 里量不出行高，整行会塌掉
                                text = line.ifEmpty { " " },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = codeFontSize,
                                lineHeight = codeLineHeight,
                                softWrap = true,
                                color = color
                            )
                        }
                    }
                }
            } else {
                // 单行横滑模式：行号列钉在左边不跟着滑，代码体自己横向滚动。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp)
                ) {
                    if (showLineNumbers) {
                        CodeLineNumber(
                            text = gutter,
                            width = gutterWidth,
                            fontSize = codeFontSize,
                            lineHeight = codeLineHeight,
                            color = lineNumberColor
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            // 拦截器排在 horizontalScroll 之前（= 外层），才看得见行内还剩没剩位移
                            .then(
                                if (blockHorizontalAtEdge) {
                                    Modifier.blockHorizontalAtEdge(hScroll)
                                } else {
                                    Modifier
                                }
                            )
                            .horizontalScroll(hScroll)
                    ) {
                        Text(
                            text = code,
                            modifier = Modifier.padding(end = 12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = codeFontSize,
                            lineHeight = codeLineHeight,
                            softWrap = false,
                            color = color
                        )
                    }
                }
            }
        }
        if (boundedBody) {
            BoundedScrollColumn(maxHeight = bodyMaxHeight) { body() }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) { body() }
        }
    }
}

/**
 * 代码块的行号：等宽、右对齐、半透明灰，宽度按总行数固定成 2–3 位。
 * 和代码正文用同一个 fontSize / lineHeight，逐行才对得上。
 */
@Composable
private fun CodeLineNumber(
    text: String,
    width: Dp,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color
) {
    Text(
        text = text,
        modifier = Modifier.width(width),
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = color,
        textAlign = TextAlign.End,
        // 行号自己绝不折行：一折就会把后面的代码行整体推歪
        softWrap = false,
        maxLines = Int.MAX_VALUE
    )
}

/** 代码块工具条上「复制」「缩进」共用的固定槽宽下限：两枚涟漪同尺寸，按压不突兀。 */
private val CodeActionSlot = 38.dp

/** 窄版（小窗被拖窄）时只留「缩进」，槽宽收到这个值，免得把行撑出块外。 */
private val CodeActionSlotCompact = 32.dp

/**
 * 工具条进入「窄版」的宽度阈值：小窗可以被拖到很窄（服务端最小 48dp），
 * 此时标签 + 复制槽 + 缩进会撑出块外。窄于这个宽度就先丢掉「复制」，
 * 保住「缩进」还贴右端、点得到（复制在极窄窗口里本来也读不清）。
 */
private val ToolbarCompactWidth = 180.dp

/**
 * 把代码块正文放进系统剪贴板；[label] 是剪贴板条目的显示名（「代码」「思考过程」等）。
 *
 * 空串直接忽略；API 33 起系统自带复制提示，所以只在更低版本手动弹「已复制」。
 */
internal fun copyCode(context: Context, code: String, label: String = "代码") {
    if (code.isEmpty()) return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText(label, code))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }
}

/* ------------------------------- 行内解析 ------------------------------- */

/** 还没落到 AnnotatedString 上的一段样式。 */
private class PendingSpan(val style: SpanStyle, val start: Int, val end: Int)

/** 还没落到 AnnotatedString 上的一段链接：文字已经躺在 sb 里，只等 addLink。 */
private class PendingLink(val url: String, val start: Int, val end: Int)

/** 行内嵌套（粗体套代码等）的最大递归层数，防止病态输入。 */
private const val MAX_INLINE_DEPTH = 4

/** 一段正文里最多认这么多个链接：病态输入（整屏 URL）不至于建几万个节点。 */
private const val MAX_LINKS = 64

/** 链接色用全 App 的强调蓝；下划线是「这行字能点」的唯一直白提示。 */
private fun linkStyles(): TextLinkStyles = TextLinkStyles(
    style = SpanStyle(color = Palette.AccentBlue, textDecoration = TextDecoration.Underline)
)

/**
 * 点链接之后去哪儿：交给 [LinkRouter]（聊天界面在场时注册成「进内置浏览页」）。
 *
 * 做成单例而不是每次 new 一个：AnnotatedString 是按原文缓存的，listener 每次都换新的话
 * LinkAnnotation 的 equals 永远不成立，进程级 LRU 等于白建。
 */
private object LinkOpenListener : LinkInteractionListener {
    override fun onClick(link: LinkAnnotation) {
        val url = (link as? LinkAnnotation.Clickable)?.tag ?: return
        LinkRouter.open(url)
    }
}

private fun inlineAnnotated(raw: String, fontSize: TextUnit, codeBackground: Color): AnnotatedString {
    if (raw.isEmpty()) return AnnotatedString("")
    val sb = StringBuilder(raw.length)
    val spans = ArrayList<PendingSpan>()
    val links = ArrayList<PendingLink>()
    // 行内代码里出现的网址不能当链接：`https://…` 是给用户抄的，点它没有意义
    val codeRanges = ArrayList<IntArray>()
    collectInline(
        s = raw,
        base = SpanStyle(),
        codeFontSize = fontSize * 0.92f,
        codeBackground = codeBackground,
        depth = 0,
        sb = sb,
        spans = spans,
        links = links,
        codeRanges = codeRanges
    )
    // markdown 链接之外，模型最常干的事是把 URL 直接写进正文：这里补上裸链接识别
    autoLink(sb.toString(), links, codeRanges)
    return buildAnnotatedString {
        append(sb.toString())
        for (span in spans) {
            addStyle(span.style, span.start, span.end)
        }
        for (link in links) {
            addLink(LinkAnnotation.Clickable(link.url, linkStyles(), LinkOpenListener), link.start, link.end)
        }
    }
}

/**
 * 正文里的裸链接。字符白名单只放 URL 里真会出现的符号：
 * 这样「见 https://a.com/x。这是正文」会在中文句号处自然断开，不会把后面整句吞成链接。
 */
private val BARE_URL_REGEX = Regex(
    "https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
    RegexOption.IGNORE_CASE
)

/** 去掉地址尾巴上的标点（句末的句号、括号、问号不属于 URL）。 */
private fun trimUrlTail(raw: String): String {
    var end = raw.length
    while (end > 0) {
        val c = raw[end - 1]
        if (c in ".,;:!?'\"") {
            end--
            continue
        }
        // 右括号只有「比左括号多」时才剥：维基百科那种 Foo_(bar) 是地址本身的一部分
        if (c == ')' || c == ']') {
            val open = if (c == ')') '(' else '['
            val slice = raw.substring(0, end)
            if (slice.count { it == c } > slice.count { it == open }) {
                end--
                continue
            }
        }
        break
    }
    return raw.substring(0, end)
}

private fun autoLink(text: String, links: MutableList<PendingLink>, codeRanges: List<IntArray>) {
    if (text.isEmpty()) return
    for (match in BARE_URL_REGEX.findAll(text)) {
        val trimmed = trimUrlTail(match.value)
        if (trimmed.length <= "https://".length || trimmed.endsWith("//")) continue
        val start = match.range.first
        val end = start + trimmed.length
        // 行内代码里的不连线；已经被 [文字](地址) 占住的不重复挂
        if (codeRanges.any { start < it[1] && it[0] < end }) continue
        if (links.any { start < it.end && it.start < end }) continue
        links.add(PendingLink(trimmed, start, end))
        if (links.size >= MAX_LINKS) return
    }
}

/** [文字](地址) 的命中结果：label 要再走一遍行内解析，end 是右括号之后的位置。 */
private class MarkdownLink(val label: String, val url: String, val end: Int)

private fun matchMarkdownLink(s: String, from: Int): MarkdownLink? {
    val closeBracket = s.indexOf(']', from + 1)
    if (closeBracket <= from + 1) return null
    if (closeBracket + 1 >= s.length || s[closeBracket + 1] != '(') return null
    val closeParen = s.indexOf(')', closeBracket + 2)
    if (closeParen <= closeBracket + 2) return null
    val url = normalizeLinkUrl(s.substring(closeBracket + 2, closeParen).trim()) ?: return null
    return MarkdownLink(s.substring(from + 1, closeBracket), url, closeParen + 1)
}

/** 只认 http/https（www. 开头补 https）；相对路径、mailto:、锚点一律不当链接。 */
private fun normalizeLinkUrl(raw: String): String? {
    if (raw.isEmpty()) return null
    val lower = raw.lowercase()
    return when {
        lower.startsWith("http://") || lower.startsWith("https://") -> raw
        lower.startsWith("www.") -> "https://" + raw
        else -> null
    }
}

/**
 * 逐字符扫描，把纯文本和样式区间分别收集出来。
 * 任何找不到配对分隔符的标记都原样 append，保证不丢字、不抛异常。
 */
private fun collectInline(
    s: String,
    base: SpanStyle,
    codeFontSize: TextUnit,
    codeBackground: Color,
    depth: Int,
    sb: StringBuilder,
    spans: MutableList<PendingSpan>,
    links: MutableList<PendingLink>,
    codeRanges: MutableList<IntArray>
) {
    if (depth > MAX_INLINE_DEPTH) {
        sb.append(s)
        return
    }
    val n = s.length
    var i = 0
    while (i < n) {
        val ch = s[i]

        // 行内代码：只认同一行内成对的反引号
        if (ch == '`') {
            val close = s.indexOf('`', i + 1)
            if (close > i + 1) {
                val start = sb.length
                sb.append(s, i + 1, close)
                val codeStyle = SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = codeFontSize,
                    background = codeBackground
                )
                spans.add(PendingSpan(base.merge(codeStyle), start, sb.length))
                // 记下这段是行内代码：里面的网址是「给人抄的」，后面不连线
                codeRanges.add(intArrayOf(start, sb.length))
                i = close + 1
            } else {
                sb.append(ch)
                i++
            }
            continue
        }

        // markdown 链接 [文字](地址)：方括号与地址都不显示，只留文字并挂上「点了进内置浏览页」
        if (ch == '[') {
            val mdLink = matchMarkdownLink(s, i)
            if (mdLink != null) {
                val start = sb.length
                // 文字部分照样走一遍行内解析：**粗体链接** 这种写法不能在链接里失效
                collectInline(
                    s = mdLink.label,
                    base = base,
                    codeFontSize = codeFontSize,
                    codeBackground = codeBackground,
                    depth = depth + 1,
                    sb = sb,
                    spans = spans,
                    links = links,
                    codeRanges = codeRanges
                )
                // 链接文字为空（[](url)）时什么都不挂，免得出现一个看不见的点击区
                if (sb.length > start) links.add(PendingLink(mdLink.url, start, sb.length))
                i = mdLink.end
                continue
            }
        }

        // 强调：** / __ 为粗体，* / _ 为斜体
        if (ch == '*' || ch == '_') {
            val doubled = i + 1 < n && s[i + 1] == ch
            val delimLen = if (doubled) 2 else 1
            // 单个下划线要求词边界，避免把 snake_case 当成斜体
            val openOk = !(ch == '_' && delimLen == 1) || i == 0 || !s[i - 1].isLetterOrDigit()
            if (openOk) {
                val close = findEmphasisClose(s, i + delimLen, ch, delimLen)
                if (close > i) {
                    val style = if (delimLen == 2) {
                        SpanStyle(fontWeight = FontWeight.Bold)
                    } else {
                        SpanStyle(fontStyle = FontStyle.Italic)
                    }
                    val start = sb.length
                    collectInline(
                        s = s.substring(i + delimLen, close),
                        base = base.merge(style),
                        codeFontSize = codeFontSize,
                        codeBackground = codeBackground,
                        depth = depth + 1,
                        sb = sb,
                        spans = spans,
                        links = links,
                        codeRanges = codeRanges
                    )
                    spans.add(PendingSpan(style, start, sb.length))
                    i = close + delimLen
                    continue
                }
            }
            // 未配对：按字面输出分隔符本身
            sb.append(s, i, i + delimLen)
            i += delimLen
            continue
        }

        sb.append(ch)
        i++
    }
}

/** 从 from 开始找能作为闭合符的位置；找不到返回 -1。 */
private fun findEmphasisClose(s: String, from: Int, ch: Char, delimLen: Int): Int {
    val n = s.length
    var j = from
    while (j <= n - delimLen) {
        val hit = if (delimLen == 2) s[j] == ch && s[j + 1] == ch else s[j] == ch
        if (hit) {
            val after = j + delimLen
            // 包起来的正文非空，且首尾不能是空白
            val innerOk = j > from && !s[from].isWhitespace() && !s[j - 1].isWhitespace()
            // 闭合符后不能紧跟同类字符，避免 *** 这类串味
            val tailOk = after >= n || s[after] != ch
            if (innerOk && tailOk) {
                // 单个下划线还要求词尾边界
                if (ch != '_' || delimLen == 2 || after >= n || !s[after].isLetterOrDigit()) {
                    return j
                }
            }
        }
        j++
    }
    return -1
}

/* ------------------------------- 块级解析 ------------------------------- */

private sealed interface MdBlock {
    data class Paragraph(val content: String) : MdBlock
    data class Heading(val level: Int, val content: String) : MdBlock
    data class ListItem(val marker: String, val content: String, val indent: Int) : MdBlock
    data class Code(val code: String, val lang: String) : MdBlock
    /** 空行留白：只有 [MarkdownText] 的 keepBlankLines 打开时才会产生。 */
    data object Blank : MdBlock
    data object Divider : MdBlock
}

private val BULLET_REGEX = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val ORDERED_REGEX = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
private val HEADING_REGEX = Regex("^(#{1,3})\\s+(.*)$")
private val DIVIDER_REGEX = Regex("^([-*_])\\1{2,}$")

/**
 * 进程级缓存：列表滚出屏幕后 Composable 会被回收，靠 remember 保不住解析结果。
 *
 * 容量按条目数给（长对话一屏十几段，64 条够覆盖来回滚动），key 用原文/样式参数，
 * 命中就直接复用，滑回来不再重新切分与建 AnnotatedString。
 */
private val mdBlocksCache = android.util.LruCache<String, List<MdBlock>>(64)
private val inlineCache = android.util.LruCache<String, AnnotatedString>(128)

private fun parseMarkdownCached(source: String, keepBlankLines: Boolean): List<MdBlock> {
    // 同一段原文在两种口径下解析结果不同，缓存 key 必须带上模式，否则一个模式会把另一个的结果读走
    val key = (if (keepBlankLines) "B:" else "N:") + source
    mdBlocksCache.get(key)?.let { return it }
    val parsed = parseMarkdown(source, keepBlankLines)
    mdBlocksCache.put(key, parsed)
    return parsed
}

private fun inlineAnnotatedCached(content: String, fontSize: TextUnit, codeBackground: Color): AnnotatedString {
    val key = content.length.toString() + "|" + fontSize.value + "|" + codeBackground.value + "|" + content
    inlineCache.get(key)?.let { return it }
    val built = inlineAnnotated(content, fontSize, codeBackground)
    inlineCache.put(key, built)
    return built
}

/**
 * 把整段文本切成块。逐行处理，流式输出时没有任何「未完成状态」需要维护：
 * 未闭合的围栏会把剩下的所有行都收进代码块。
 */
private fun parseMarkdown(source: String, keepBlankLines: Boolean = false): List<MdBlock> {
    if (source.isEmpty()) return emptyList()
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = ArrayList<MdBlock>(lines.size)
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val trimmed = raw.trim()

        // 1. 围栏代码块（含未闭合）
        val fence = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (fence != null) {
            // "```kotlin" 的首个词当语言标识，只用于代码块工具条展示
            val lang = trimmed.removePrefix(fence).trim()
            val body = StringBuilder()
            var closed = false
            var j = i + 1
            while (j < lines.size) {
                val candidate = lines[j].trim()
                if (candidate.startsWith(fence) && candidate.removePrefix(fence).trim().isEmpty()) {
                    closed = true
                    break
                }
                if (body.isNotEmpty()) body.append('\n')
                // 只吃行尾空白：行首前导空格必须原样保留，否则缩进直接消失
                body.append(lines[j].replace("\t", "    ").trimEnd())
                j++
            }
            // 只去首尾空行，保留每行前导空格
            blocks.add(MdBlock.Code(trimBlankEdges(body.toString()), lang))
            i = if (closed) j + 1 else lines.size
            continue
        }

        // 2. 空行：默认只作分隔，不产生块（块间距统一由 Column 控制）；
        //    用户气泡打开 keepBlankLines 时留一段空白，连续多个空行也只留一段
        if (trimmed.isEmpty()) {
            if (keepBlankLines && blocks.isNotEmpty() && blocks.last() !is MdBlock.Blank) {
                blocks.add(MdBlock.Blank)
            }
            i++
            continue
        }

        // 3. 标题
        val heading = HEADING_REGEX.matchEntire(trimmed)
        if (heading != null) {
            blocks.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim()))
            i++
            continue
        }

        // 4. 分隔线（先于列表判断，"---" 优先当分隔线）
        if (DIVIDER_REGEX.matches(trimmed)) {
            blocks.add(MdBlock.Divider)
            i++
            continue
        }

        // 5. 无序列表
        val bullet = BULLET_REGEX.matchEntire(raw)
        if (bullet != null) {
            blocks.add(
                MdBlock.ListItem(
                    marker = "• ",
                    content = bullet.groupValues[2].trim(),
                    indent = indentLevelOf(bullet.groupValues[1])
                )
            )
            i++
            continue
        }

        // 6. 有序列表：保留原序号
        val ordered = ORDERED_REGEX.matchEntire(raw)
        if (ordered != null) {
            blocks.add(
                MdBlock.ListItem(
                    marker = ordered.groupValues[2] + ". ",
                    content = ordered.groupValues[3].trim(),
                    indent = indentLevelOf(ordered.groupValues[1])
                )
            )
            i++
            continue
        }

        // 7. 普通段落
        blocks.add(MdBlock.Paragraph(trimmed))
        i++
    }
    return blocks
}

/** 行首缩进折算层级（2 空格算一层，最多 3 层，避免深层列表跑出屏幕）。 */
private fun indentLevelOf(leading: String): Int {
    val spaces = leading.length
    if (spaces <= 0) return 0
    return (spaces / 2).coerceIn(0, 3)
}

/**
 * 代码块只去掉首尾的空行，每行前导空格（缩进）原样保留。
 * 解析时逐行只 trimEnd，所以这里是唯一会动空白的地方。
 */
private fun trimBlankEdges(code: String): String {
    if (code.isEmpty()) return code
    val lines = code.split('\n')
    var start = 0
    var end = lines.lastIndex
    while (start <= end && lines[start].isBlank()) start++
    while (end >= start && lines[end].isBlank()) end--
    if (start > end) return ""
    return lines.subList(start, end + 1).joinToString("\n")
}
