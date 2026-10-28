package com.zmf.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import org.json.JSONArray
import org.json.JSONObject

/* ============================== 文件级 API 说明 ==============================
 *
 * 通用工具块：主页聊天气泡里的一次工具调用。它不依赖 ToolCall / TraceLine 任何一方的数据类，
 * 只吃纯参数；轨迹页那张是从这里派生的另一份（[TraceToolCard]），参数与分区口径相同。
 *
 * 一、入口
 *
 *   @Composable fun ToolBlock(
 *       toolName: String,           // 工具名（标题行，等宽字体）
 *       arguments: String,          // 参数原文（JSON 或纯文本）
 *       modifier: Modifier = Modifier,
 *       output: String? = null,     // 结果；null = 还在跑（标题行显示「执行中…」）
 *       ok: Boolean? = null,        // 结果成败；null = 不知道 / 还在跑
 *       code: String? = null,       // 可选的代码/命令区（给终端类工具用，没有就不占位）
 *       codeLang: String = "",      // 代码区的语言标签（空则显示「代码」）
 *       containerColor: Color = …,  // 卡片底色，默认用 onSurface 的低透明度
 *       outsideTapKey: Any? = null, // 父层「点了别处」的计数；值一变就收起复制按钮
 *       copyText: String? = null    // 自定义复制内容；null = 自动拼工具名/参数/代码/输出
 *   )
 *
 * 二、主页聊天气泡里的调用方式
 *
 *   ToolBlock(
 *       toolName = call.name,
 *       arguments = call.arguments,
 *       output = toolResultOf(all, call),
 *       ok = toolOutcomes[call.id],
 *       containerColor = cardColor
 *   )
 *   数据来自一次工具调用：call.name → toolName，call.arguments → arguments，结果 → output。
 *   行为：参数默认折叠成一行摘要（点摘要行展开），结果是常显的 240dp 内滚区，
 *   整卡不折叠；如果仍想要「整卡折叠」，在外面套一层自己的开关即可。
 *
 * 三、轨迹页
 *
 *   轨迹页用 [TraceToolCard]（从这里派生的另一份，默认展开）；参数同名同义，
 *   TraceLine 的 args / result 原文直接传进来就行。
 *
 * 四、位置约定
 *   - 参数区：折叠时一行摘要（[ARG_SUMMARY_LIMIT] 字截断），展开后 240dp 内滚；
 *   - 代码区：等宽 + 行号，默认单行横滑，点「缩进」换成完整换行，随整页滚动；
 *   - 输出区：240dp 内滚，滚到边界后继续滑不带动外层页面；
 *   - 复制按钮：默认不显示，块内任意手势后出现在右上角，见 [ToolBlock] 的注释。
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
 * 通用工具块（主页聊天气泡 / 轨迹页共用）。
 *
 * 结构：标题行（工具名 + 状态 + 复制）→ 参数区（折叠/展开）→ 代码区（可选）→ 输出区。
 *
 * 复制按钮的显隐（最终采用「计时」方案，不是全局点击代理）：
 *  - 默认不显示；
 *  - 块内**任意**手势（点击、长按、滑动，甚至只是按下）都会让它出现，并重置 4 秒计时；
 *  - 4 秒内没有新手势就自动收起；
 *  - 另外留了 [outsideTapKey] 钩子：父页面要是本来就有「点空白处」的事件，
 *    把它递增一下就能立刻收起（父层不接也完全可用）。
 *  为什么不做「全局点击代理」：那要求父层把根节点的点击事件按块分发下来，
 *  聊天页的根节点已经被长按选文、抽屉横滑占用，硬塞进去容易互相吃掉事件，
 *  而这个按钮本身就是「临时工具」，4 秒自动收起已经够用。
 */
@Composable
fun ToolBlock(
    toolName: String,
    arguments: String,
    modifier: Modifier = Modifier,
    output: String? = null,
    ok: Boolean? = null,
    code: String? = null,
    codeLang: String = "",
    containerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
    outsideTapKey: Any? = null,
    copyText: String? = null
) {
    val context = LocalContext.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 参数 / 代码 / 输出共用同一份「代码块底色」，比卡片本身再浅一档
    val bodyBackground = onSurface.copy(alpha = 0.06f)

    // 卡片默认折叠成一行（工具名 + 状态）：参数 / 代码 / 输出要点开才看。
    // 一轮里常常连着七八次工具调用，全摊开会把这一轮的回答挤到看不见。
    var open by rememberSaveable { mutableStateOf(false) }
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
    // 一句话总结（「读取 test.txt」「运行命令 tail -c 20 …」）：标题行告诉用户这条工具干了什么
    val headline = remember(toolName, arguments) { toolSummary(toolName, arguments) }
    // 参数等于没有（空串 / 空对象）时整块不渲染：避免「参数 缩进」下面只剩一个空花括号
    val argsBlank = remember(arguments) { toolArgIsBlank(arguments) }
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
                // 「复制」占一块固定宽度：出现 / 消失都不挪动任何别的元素，右侧
                // 「完成 + 箭头」的位置永远是死的（复制浮现时整块不能突变）。
                // 这块 46dp 就是**虚拟区域**（界面上看不见）：「复制 / 已复制」都在这块里居中，
                // 涟漪铺满整块、和字同心（别管「已」多出来的宽度，只调这块区域）。
                // 别把这里改成 indication = null：那样就没有涟漪了，必须走默认涟漪。
                Box(
                    modifier = Modifier
                        .width(CopySlot)
                        .clip(RoundedCornerShape(6.dp))
                        .then(
                            if (copyVisible && fullText.isNotBlank()) {
                                Modifier.clickable {
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
            // 直接展示，不再有二级「展开 / 收起」：主页只要「参数 / 结果」两段
            // （把杂乱内容砍掉，只展示参数 / 结果或代码 / 输出）。参数为空时整块不渲染。
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
            }
            }
        }
    }
}

/* ------------------------------ 内部实现 ------------------------------ */

/**
 * 参数摘要：优先取 path / command 这类一眼能读懂的字段，取不到就把整串压成一行。
 * 统一截到 [ARG_SUMMARY_LIMIT] 字：路径和命令都太长，一行放不下。
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