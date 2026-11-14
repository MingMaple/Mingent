package com.zmf.agent.feature.chat
import com.zmf.agent.uikit.icons.DshIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.common.BoundedScrollColumn
import com.zmf.agent.uikit.theme.Palette
import kotlinx.coroutines.delay
import java.io.File
import com.zmf.agent.uikit.markdown.MarkdownCodeBlock
import com.zmf.agent.uikit.markdown.copyCode
import com.zmf.agent.uikit.components.CopyActionButton

/* ============================== 文件级说明 ==============================
 *
 * 轨迹页专用的工具卡。参数、分区与「复制按钮」的口径与 [ToolBlock] 同源，
 * 这里只记与它的差异（为什么另起一份见下面 [TraceToolCard] 的 KDoc）：
 *
 * 一、与主页那份的差异
 *   - 没有折叠态：卡片由时间线上方那一行控制进出，卡内不再有「折叠成一行」的动作；
 *   - 多三个参数：stepLabel（标题行右侧「第 N 轮 · 第 M 步」）、imageRoot（把输出里的
 *     相对路径解成工作区里的真文件）、onImageClick（点附件卡打开主页那一个查看器）；
 *   - 输出区下面多一段图片附件（[ToolAttachmentRow]），没有图就整段不出。
 *
 * 二、位置约定
 *   - 参数区：240dp 内滚，参数为空时整块不渲染；
 *   - 代码区：等宽 + 行号，默认单行横滑，点「换行」换成完整折行，随整页滚动；
 *   - 输出区：240dp 内滚，滚到边界后继续滑不带动外层页面；
 *   - 复制按钮：标题行最右端（原先折叠箭头的位置），「完成」在其左侧（见 [CopyActionButton]）。
 * ========================================================================== */

/** 「已复制」提示的停留时间。 */
private const val COPIED_TIP_MS = 1200L

/** 参数区 / 输出区的内部滚动上限：更长就在块内竖滑。 */
private val BlockBodyMaxHeight = 240.dp

/**
 * 标题行的固定行高。
 *
 * 复制按钮 30dp 高于 12sp 标题（约 16dp），若按内容撑开，行高会随按钮出现而变化，
 * 标题文字随之上下移动。固定 36dp：与「无按钮时的内距 10dp + 文字 16dp」等价，
 * 按钮在行内居中，上下各留 3dp；内容超出时由内部文字省略。
 */
private val TraceHeaderHeight = 36.dp

/**
 * 轨迹页专用的工具卡（从主页那份派生，只服务详情页，卡内不再折叠）。
 *
 * 结构：标题行（工具图标 + 说明 + 步骤 + 完成 + 复制）→ 参数区 → 代码区（可选）→ 输出区 → 图片附件。
 *
 * 复制按钮与 [ToolBlock] 同为常显的图标按钮：位置与宽度固定，不随触摸出现或收起。
 *
 * 为什么另起一份而不是复用主页的 ToolBlock：两页的「工具卡」只是长得像，
 * 语义不同：主页那张是**过程**，默认折成一行、点开才看；轨迹页这张是**详情**，是专门点进来
 * 查这一步的，因此没有折叠态；放在同一函数里加参数会互相牵制。
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

    // 详情卡没有折叠态：由时间线上方那一行控制进出，卡内始终展示完整内容。
    var copied by remember { mutableStateOf(false) }
    // 「已复制」1.2 秒后复位
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_TIP_MS)
            copied = false
        }
    }

    val running = output == null
    // 状态仅保留一个词：取得结果即写「完成」，未取得则暂空。
    // 不区分成功 / 失败 / 成败未知：调用结束即结束，具体错误可在输出中查看，
    // 卡片上额外的红字反而干扰阅读。
    val statusColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val statusText = if (running) "" else "完成"
    // 标题（「对比本地与远端插件版本」）：优先取模型写入的 description，旧数据回退 action_summary；
    // 模型遗漏时退回按工具名与参数拼出的句子（[toolSummary]），标题行不会留空。
    val headline = remember(toolName, arguments) { toolHeadline(toolName, arguments) }
    // 参数为空（空串 / 空对象）时整块不渲染：避免「参数 + 换行按钮」下仅剩一个空花括号
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
        modifier = modifier.fillMaxWidth()
    ) {
        // 内边距下放至各块内容：标题行贴卡片边缘，其余区块各自留白
        Column {
            /* -------------------------- 标题行 -------------------------- */
            // 固定行高：复制按钮常在，行高不随它变化，标题文字不会因此上下移动。
            // 该行不再可点：卡片的进出由时间线上方那一行控制，卡内不再提供折叠动作。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TraceHeaderHeight)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = DshIcons.Terminal,
                    contentDescription = null,
                    tint = Palette.Neutral,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(5.dp))
                // 标题行仅显示「这条工具执行了什么」：工具名文字移除、简介语句前置，
                // 图标保留。工具名仍进入复制文本（buildToolCopyText），信息不丢失。
                // 12sp：该句为标题，非附属小字。
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
                Spacer(Modifier.width(8.dp))
                // 右侧顺序：完成 → 复制。复制固定在行尾，接替原先折叠箭头的位置，
                // 与状态字之间留 6dp，两者都在固定行高内居中。
                if (statusText.isNotEmpty()) {
                    Text(text = statusText, fontSize = 11.sp, color = statusColor)
                }
                if (fullText.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    CopyActionButton(
                        copied = copied,
                        ink = onSurface,
                        onClick = {
                            copyCode(context, fullText, "工具调用")
                            copied = true
                        }
                    )
                }
            }

            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
            /* -------------------------- 参数区 -------------------------- */
            // 直接展示，无二级「展开 / 收起」：卡片本身已是「点击才看」的层级，
            // 内部再折叠会使收起动效叠加两段（表现为「收起后再次跳动」）。
            // 参数为空时整块不渲染（空对象不应绘制空块）。
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
            // 终端类工具的命令、write_file 的片段由此渲染；无内容时不占位。
            // 高度同样由 MarkdownCodeBlock 自行限高（240dp 内滚，超出才消费滑动）：命令与 diff 片段
            // 同样可能较长，阅读时不应使整条会话扩展超过一屏。
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
                // 仅当上方存在参数/代码时才添加「输出」小标题，否则整块即为输出，无需标注
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
                        // 内边距设置于正文上：随内容一同滚动，不占滚动区
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = onSurface.copy(alpha = 0.72f)
                    )
                }
            }

            /* -------------------------- 图片附件 -------------------------- */
            // 输出中真实存在的图片单独排列为附件卡（缩略图 + 文件名 + 类型/大小/尺寸），
            // 点击交由主页同一套查看器。无图片则整段不显示。
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

/* ------------------------------ 内部实现 ------------------------------ */

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
