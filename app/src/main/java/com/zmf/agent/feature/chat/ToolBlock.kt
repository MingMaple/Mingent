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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.zmf.agent.uikit.common.Chevron
import com.zmf.agent.uikit.common.ChevronKind
import com.zmf.agent.uikit.common.ChevronSize
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.theme.Palette
import kotlinx.coroutines.delay
import com.zmf.agent.uikit.markdown.MarkdownCodeBlock
import com.zmf.agent.uikit.markdown.copyCode
import com.zmf.agent.uikit.components.CopyActionButton

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
 *       output: String? = null,     // 结果；null = 仍在执行（标题行显示「执行中…」）
 *       ok: Boolean? = null,        // 结果成败；null = 未知 / 仍在执行
 *       code: String? = null,       // 可选的代码/命令区（供终端类工具使用，无内容时不占位）
 *       codeLang: String = "",      // 代码区的语言标签（空则显示「代码」）
 *       containerColor: Color = …,  // 卡片底色，默认用 onSurface 的低透明度
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
 *   行为：整卡默认折叠为标题行，点击展开参数 / 代码 / 输出；参数区与输出区各为 240dp 内滚区。
 *
 * 三、轨迹页
 *
 *   轨迹页用 [TraceToolCard]（从这里派生的另一份，默认展开）；参数同名同义，
 *   TraceLine 的 args / result 原文直接传进来就行。
 *
 * 四、位置约定
 *   - 参数区：随整卡展开后 240dp 内滚；
 *   - 代码区：等宽 + 行号，默认单行横滑，点「换行」换成完整折行，随整页滚动；
 *   - 输出区：240dp 内滚，滚到边界后继续滑不带动外层页面；
 *   - 复制按钮：标题行右端的图标按钮，常显（见 [CopyActionButton]）。
 * ========================================================================== */

/** 「已复制」提示的停留时间。 */
private const val COPIED_TIP_MS = 1200L

/** 参数区 / 输出区的内部滚动上限：更长就在块内竖滑。 */
private val BlockBodyMaxHeight = 240.dp

/**
 * 标题行的固定行高。
 *
 * 复制按钮 30dp 高于 12sp 标题（约 16dp），若按内容撑开，行高会随按钮出现而变化，
 * 标题文字随之上下移动。取 36dp：与「无按钮时的内距 10dp + 文字 16dp」等价，
 * 按钮在行内居中，上下各留 3dp。
 */
private val ToolHeaderHeight = 36.dp

/**
 * 通用工具块（主页聊天气泡 / 轨迹页共用）。
 *
 * 结构：标题行（工具名 + 状态 + 复制）→ 参数区（折叠/展开）→ 代码区（可选）→ 输出区。
 *
 * 复制按钮为标题行右端的图标按钮，常显：位置与宽度固定，折叠 / 展开都不移动其他元素。
 * 点击后 1.2 秒内图标改为对勾，反馈由图形承担（见 [CopyActionButton]）。
 * 卡片内容区不再挂载「触摸后浮现」的判定：该机制已随图标化一并废止。
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
    copyText: String? = null
) {
    val context = LocalContext.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 参数 / 代码 / 输出共用同一份「代码块底色」，比卡片本身再浅一档
    val bodyBackground = onSurface.copy(alpha = 0.06f)

    // 卡片默认折叠为一行（工具名 + 状态）：参数 / 代码 / 输出需点击展开。
    // 一轮中常连续七八次工具调用，全部展开会将本轮回答挤出可视区域。
    var open by rememberSaveable { mutableStateOf(false) }
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
    val codeBody = code?.takeIf { it.isNotBlank() }
    val fullText = remember(copyText, toolName, arguments, code, output) {
        copyText ?: buildToolCopyText(toolName, arguments, code, output)
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        // 内边距下放至各块内容：标题行需顶至卡片边缘，波纹方可铺满整行
        // （反馈不设计为行内小矩形）。
        Column {
            /* -------------------------- 标题行 -------------------------- */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .saltRowClickable(if (open) RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp) else RoundedCornerShape(14.dp)) {
                        open = !open
                    }
                    // 固定行高：复制按钮只在展开时出现，行高不能随之变化，否则标题会上下跳
                    .height(ToolHeaderHeight)
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
                // 复制为常显的图标按钮：只在展开时出现（折叠态看不到内容，不提供复制入口）；
                // 宽度固定，右侧「完成 + 箭头」不因它出现或消失而位移。
                // 不写 indication = null：涟漪必须保留，按钮的按压反馈靠它。
                if (open && fullText.isNotBlank()) {
                    Spacer(Modifier.width(2.dp))
                    CopyActionButton(
                        copied = copied,
                        ink = onSurface,
                        onClick = {
                            copyCode(context, fullText, "工具调用")
                            copied = true
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (statusText.isNotEmpty()) {
                    Text(text = statusText, fontSize = 11.sp, color = statusColor)
                }
                Spacer(Modifier.width(6.dp))
                // 折叠时显示朝右的小箭头，用户方可得知该行可点击展开
                Chevron(
                    expanded = open,
                    size = ChevronSize.SMALL,
                    kind = ChevronKind.RIGHT_DOWN,
                    tint = onSurface.copy(alpha = 0.35f)
                )
            }

            if (open) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
            /* -------------------------- 参数区 -------------------------- */
            // 直接展示，无二级「展开 / 收起」：主页仅需「参数 / 结果」两段
            // （简化杂乱内容，仅展示参数 / 结果或代码 / 输出）。参数为空时整块不渲染。
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
