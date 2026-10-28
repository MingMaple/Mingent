package com.zmf.agent.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.ui.common.BoundedScrollColumn
import com.zmf.agent.ui.common.Chevron
import com.zmf.agent.ui.common.ChevronKind
import com.zmf.agent.ui.common.ChevronSize
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.theme.Palette
import kotlinx.coroutines.delay
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/* ============================== 文件级说明 ==============================
 *
 * 轨迹页专用的工具卡。参数、分区与「复制按钮」的入场方式与 [ToolBlock] 同源，
 * 这里只记与它的差异（为什么另起一份见下面 [TraceToolCard] 的 KDoc）：
 *
 * 一、与主页那份的差异
 *   - 默认展开：轨迹页是「点进来看详情」，不需要「折成一行」这个动作；
 *   - 多三个参数：stepLabel（标题行右侧「第 N 轮 · 第 M 步」）、imageRoot（把输出里的
 *     相对路径解成工作区里的真文件）、onImageClick（点附件卡打开主页那一个查看器）；
 *   - 输出区下面多一段图片附件（[ToolAttachmentRow]），没有图就整段不出。
 *
 * 二、位置约定
 *   - 参数区：240dp 内滚，参数为空时整块不渲染；
 *   - 代码区：等宽 + 行号，默认单行横滑，点「缩进」换成完整换行，随整页滚动；
 *   - 输出区：240dp 内滚，滚到边界后继续滑不带动外层页面；
 *   - 复制按钮：块内任意手势后出现在右上角，口径与 [ToolBlock] 相同。
 * ========================================================================== */

/** 「复制」按钮的固定占位宽度：始终存在，按钮出现时只填进去，不挤动旁边任何东西。 */
private val CopySlot = 46.dp


/** 参数摘要的字符上限：路径 / 命令这类长串都截到这个长度。 */
private const val ARG_SUMMARY_LIMIT = 40

/** 「已复制」提示的停留时间。 */
private const val COPIED_TIP_MS = 1200L

/** 参数区 / 输出区的内部滚动上限：更长就在块内竖滑。 */
private val BlockBodyMaxHeight = 240.dp

/** 摘要优先取这些字段：一眼能读懂的「在干什么」。 */
private val ARG_SUMMARY_KEYS = listOf(
    "path", "file_path", "filePath", "command", "cmd", "query", "url",
    "description", "prompt", "goal", "pattern", "name"
)

/**
 * 轨迹页专用的工具卡（从主页那份派生，但默认展开、只服务详情页）。
 *
 * 结构：标题行（工具名 + 状态 + 复制）→ 参数区（折叠/展开）→ 代码区（可选）→ 输出区 → 图片附件。
 *
 * 复制按钮的显隐与 [ToolBlock] 同口径：默认不显示，块内任意手势后出现、4 秒无新手势自动收起，
 * 父层递增 [outsideTapKey] 还能立刻收回；不做「全局点击代理」的理由也是同一个（见该处说明）。
 *
 * 为什么另起一份而不是复用主页的 ToolBlock：两页的「工具卡」只是长得像，
 * 语义不同：主页那张是**过程**，默认折成一行、点开才看；轨迹页这张是**详情**，是专门点进来
 * 查这一步的，所以默认展开，也不需要「折叠成一行」这个动作；放在同一函数里加参数会互相牵制。
 */
@Composable
fun TraceToolCard(
    toolName: String,
    arguments: String,
    modifier: Modifier = Modifier,
    output: String? = null,
    ok: Boolean? = null,
    code: String? = null,
    codeLang: String = "",
    containerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
    outsideTapKey: Any? = null,
    copyText: String? = null,
    /** 标题行右侧的「第 N 轮 · 第 M 步」；空串不显示（主页没有这一步的概念）。 */
    stepLabel: String = "",
    /** 工作区根：用来判断输出里的图片是否真实存在、以及拼缩略图。 */
    imageRoot: File? = null,
    /** 点附件卡打开查看器（沿用主页那一个）。 */
    onImageClick: ((List<String>, Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 参数 / 代码 / 输出共用同一份「代码块底色」，比卡片本身再浅一档
    val bodyBackground = onSurface.copy(alpha = 0.06f)

    // 卡片默认折叠成一行（工具名 + 状态）：参数 / 代码 / 输出要点开才看。
    // 一轮里常常连着七八次工具调用，全摊开会把这一轮的回答挤到看不见。
    // 轨迹页是「点进来看详情」，进来就该是展开的
    var open by rememberSaveable { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    // 「复制」的全局唯一身份：展开 + 摸过这块才显示，摸别的块就挪过去
    val copyKey = rememberCopyBlockKey()
    val copyVisible = open && CopyRevealHost.owner === copyKey

    // 父层接了「点块外」的话，计数一变就立刻收回；不接也照样能用
    LaunchedEffect(outsideTapKey) {
        if (outsideTapKey != null) CopyRevealHost.release(copyKey)
    }
    // 「已复制」1.2 秒后复位
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_TIP_MS)
            copied = false
        }
    }

    val running = output == null
    // 状态只留一个词：拿到结果就写「完成」，没拿到就先空着。
    // 不再区分成功 / 失败 / 成败未知：调用结束就是结束，具体的错在输出里能看到，
    // 卡片上多一个红字反而更吵。
    val statusColor = Palette.AccentGreen
    val statusText = if (running) "" else "完成"
    // 一句话总结（「读取 test.txt」「运行命令 tail -c 20 …」）：标题行给用户看这条工具干了什么
    val headline = remember(toolName, arguments) { toolSummary(toolName, arguments) }
    // 参数等于没有（空串 / 空对象）时整块不渲染：避免「参数 缩进」下面只剩一个空花括号
    val argsBlank = remember(arguments) { toolArgIsBlank(arguments) }
    // 输出里真实存在的图片 → 附件卡（缩略图 + 文件名 + 类型/大小/尺寸）
    val attachments = remember(output, imageRoot) { toolAttachments(output, imageRoot) }
    val codeBody = code?.takeIf { it.isNotBlank() }
    val fullText = remember(copyText, toolName, arguments, code, output) {
        copyText ?: buildToolCopyText(toolName, arguments, code, output)
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            // 复制归属挂在内容区（见下面那个 Column），不挂在整卡上：
            // 以前点标题行那一下也算「摸过」，于是「点一下展开没有按钮、再点一下收起反而出现」。
    ) {
        // 内边距下放到每一块内容上：标题行要顶到卡片边缘，波纹才铺满整行
        // （反馈不做成行内一个小矩形）。
        Column {
            /* -------------------------- 标题行 -------------------------- */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .saltRowClickable(if (open) RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp) else RoundedCornerShape(14.dp)) {
                        open = !open
                        if (!open) CopyRevealHost.release(copyKey)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = DshIcons.Terminal,
                    contentDescription = null,
                    tint = Palette.Neutral,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(5.dp))
                // 标题行只放「这条工具干了什么」：工具名那行字删掉、简介语句移到前面，
                // 图标保留。工具名仍进复制文本（buildToolCopyText），信息不丢。
                // 12sp：这句是标题，不是附属小字。
                Text(
                    text = headline,
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = onSurface.copy(alpha = 0.55f)
                )
                if (stepLabel.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stepLabel,
                        fontSize = 10.sp,
                        maxLines = 1,
                        color = onSurface.copy(alpha = 0.4f)
                    )
                }
                // 「复制」占一块固定宽度：出现 / 消失都不挪动任何别的元素（口径见 [ToolBlock] 的同段说明）。
                Box(
                    modifier = Modifier
                        .width(CopySlot)
                        .then(
                            if (copyVisible && fullText.isNotBlank()) {
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        copyCode(context, fullText, "工具调用")
                                        copied = true
                                    }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (copyVisible && fullText.isNotBlank()) {
                        Text(
                            text = if (copied) "已复制" else "复制",
                            maxLines = 1,
                            fontSize = 11.sp,
                            color = onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (statusText.isNotEmpty()) {
                    Text(text = statusText, fontSize = 11.sp, color = statusColor)
                }
                Spacer(Modifier.width(6.dp))
                // 折着的时候给个朝右的小箭头，用户才知道这行能点开
                Chevron(
                    expanded = open,
                    size = ChevronSize.SMALL,
                    kind = ChevronKind.RIGHT_DOWN,
                    tint = onSurface.copy(alpha = 0.35f)
                )
            }

            if (open) {
            Column(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                    // 展开后内容区任意位置按下 / 滑动都叫出复制（Initial 阶段，不消费事件）
                    .claimCopyOnGesture(copyKey),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
            /* -------------------------- 参数区 -------------------------- */
            // 直接展示，不再有二级「展开 / 收起」：卡片本身已经是「点开才看」的那一层，
            // 里面再折一层会让收起动效叠两段（表现为「收完又跳一下」）。
            // 参数等于没有时整块不渲染（空对象不该画出一个空块）。
            if (!argsBlank) {
                BoundedScrollColumn(
                    maxHeight = BlockBodyMaxHeight,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MarkdownCodeBlock(
                        code = arguments,
                        lang = "参数",
                        fontSize = 13.sp,
                        color = onSurface,
                        codeBackground = bodyBackground,
                        showCopy = false,
                        showLineNumbers = false,
                        blockHorizontalAtEdge = true
                    )
                }
            }

            /* -------------------------- 代码区（可选） -------------------------- */
            // 终端类工具的命令、write_file 的片段走这里；没有就不占位。
            // 高度同样由 MarkdownCodeBlock 自己限高（240dp 内滚，超出才吃滑动）：命令和 diff 片段
            // 一样可能很长，读的时候不该把整条会话撑开一屏多。
            if (codeBody != null) {
                MarkdownCodeBlock(
                    code = codeBody,
                    lang = codeLang,
                    fontSize = 13.sp,
                    color = onSurface,
                    codeBackground = bodyBackground,
                    modifier = Modifier.fillMaxWidth(),
                    showCopy = false,
                    showLineNumbers = true,
                    blockHorizontalAtEdge = true
                )
            }

            /* -------------------------- 输出区 -------------------------- */
            if (output != null) {
                // 只有上面还有参数/代码时才加「输出」小标题，否则整块就是输出，不必标注
                if (!argsBlank || codeBody != null) {
                    Text(
                        text = "输出",
                        fontSize = 10.sp,
                        color = onSurface.copy(alpha = 0.4f)
                    )
                }
                BoundedScrollColumn(
                    maxHeight = BlockBodyMaxHeight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bodyBackground, RoundedCornerShape(10.dp))
                ) {
                    Text(
                        text = output.ifBlank { "（无输出）" },
                        // 内边距给在正文上：让它跟着内容一起滚动，不占滚动区
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = onSurface.copy(alpha = 0.72f)
                    )
                }
            }

            /* -------------------------- 图片附件 -------------------------- */
            // 输出里真实存在的图片单独排成附件卡（缩略图 + 文件名 + 类型/大小/尺寸），
            // 点开交给主页同一套查看器。没有图就整段不出。
            if (attachments.isNotEmpty()) {
                ToolAttachmentRow(
                    attachments = attachments,
                    root = imageRoot,
                    onOpen = onImageClick
                )
            }
            }
            }
        }
    }
}

/* ------------------------------ 内部实现 ------------------------------ */

/**
 * 横向行滑到头之后，把剩下那点横向位移就地吃掉，不让外层抽屉 / 轨迹页接手。
 *
 * 为什么不用 nestedScroll：horizontalScroll 到边界时既不再消费、也不把余量交给
 * onPostScroll，所以 onPostScroll 根本收不到；再往外推就是「把抽屉拉出来」。
 *
 * 为什么走 Initial 阶段逐帧判：到边界之后子节点本身已经不再消费，等 Main 阶段看到时
 * 外层可能已经接走；这里只问「贴没贴到这一侧 + 方向是不是往外」，贴住了就 consume，
 * 行内还有余量时一律不碰（滑动仍归 horizontalScroll），竖向为主的位移也不碰，
 * 所以竖着滑消息列表不受影响。
 *
 * 实现在本文件（从 ChatScreen 的 private 实现里挪出）；同包另有 [blockHorizontalAtEdge] 供代码块与 TabStrip 复用。
 */
private fun Modifier.traceCardHorizontalAtEdge(state: ScrollState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // 累计位移判方向：只看单帧的话，慢速拖动一帧不过几像素，永远判不出横向
        var dx = 0f
        var dy = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            dx += change.position.x - change.previousPosition.x
            dy += change.position.y - change.previousPosition.y
            val atStart = state.value == 0 && dx > 0
            val atEnd = state.value >= state.maxValue && dx < 0
            if (change.pressed && (atStart || atEnd) && abs(dx) > abs(dy)) change.consume()
            if (!change.pressed) break
        }
    }
}

/**
 * 参数摘要（与 ToolBlock.kt 里那份实现同口径）：取 path / command 这类字段，
 * 取不到就压成一行，统一截到 [ARG_SUMMARY_LIMIT]。
 */
private fun toolArgSummary(arguments: String): String {
    if (arguments.isBlank()) return ""
    val json = runCatching { JSONObject(arguments) }.getOrNull()
    val picked = json?.let { obj ->
        ARG_SUMMARY_KEYS.firstNotNullOfOrNull { key ->
            val value = obj.opt(key) ?: return@firstNotNullOfOrNull null
            if (value === JSONObject.NULL || value is JSONObject || value is JSONArray) {
                return@firstNotNullOfOrNull null
            }
            value.toString().takeIf { it.isNotBlank() }
        }
    }
    val flat = (picked ?: arguments)
        .replace('\n', ' ')
        .replace('\r', ' ')
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    return if (flat.length > ARG_SUMMARY_LIMIT) flat.take(ARG_SUMMARY_LIMIT) + "…" else flat
}

/** 默认的复制内容：工具名 + 有哪段带哪段，按「标签换行正文」拼，粘到外面还能读。 */
private fun buildToolCopyText(
    toolName: String,
    arguments: String,
    code: String?,
    output: String?
): String = buildString {
    append("🔧 ").append(toolName)
    if (arguments.isNotBlank()) append("\n\n参数\n").append(arguments)
    if (code != null && code.isNotBlank()) append("\n\n代码\n").append(code)
    if (output != null) append("\n\n输出\n").append(output)
}