package com.zmf.agent.feature.chat
import com.zmf.agent.uikit.icons.CylinderGlyph
import com.zmf.agent.uikit.icons.UnitGlyph

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.uikit.common.GlyphIcon
import com.zmf.agent.uikit.common.PanelGlyph
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.saltLongPress
import java.util.Locale
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors

/**
 * 「＋」浮窗：附件 / 指令 / 上下文 / 命令 / 技能，每行「图标 + 名字 + 英文 + 右侧说明」。
 *
 * 说明放右边而不是第二行：用户是来挑一个动作，不是来读文档；38dp 的行高让键盘弹起时整块还能落在可视区。
 * [filter] 给斜杠指令复用：以 "/" 开头的输入直接过滤这张面板，命中的行才留，空组连灰标题都不画
 * 画一个空标题等于告诉用户「这里本来有东西，只是没匹配上」，那是噪音。
 * [slashMode] 才亮出 /plan、/goal 这类模板：它们只在斜杠语境下有意义，＋ 菜单里出现会误导。
 */
@Composable
fun ComposerCommandPanel(
    skills: List<String>,
    planMode: Boolean,
    activeGoal: Boolean,
    onPick: (ComposerCommand) -> Unit,
    onPickSkill: (String) -> Unit,
    onPickTemplate: (String) -> Unit = {},
    slashMode: Boolean = false,
    filter: String = "",
    modifier: Modifier = Modifier
) {
    val attach = ATTACH_COMMANDS.filter { it.matches(filter) }
    val directives = DIRECTIVE_COMMANDS.filter { it.matches(filter) }
    val contexts = CONTEXT_COMMANDS.filter { it.matches(filter) }
    val templates = if (slashMode) SLASH_TEMPLATES.filter { it.matches(filter) } else emptyList()
    val skillHits = skills.filter { skillMatches(it, filter) }

    PanelCard(modifier = modifier) {
        if (attach.isNotEmpty()) {
            SectionTitle(text = "附件")
            attach.forEach { entry ->
                CommandRow(
                    glyph = entry.glyph,
                    title = entry.title,
                    english = entry.english,
                    desc = entry.desc,
                    dot = entry.command == ComposerCommand.ADD_PLAN && planMode ||
                        entry.command == ComposerCommand.ADD_GOAL && activeGoal
                ) {
                    onPick(entry.command)
                }
            }
        }
        if (directives.isNotEmpty()) {
            SectionTitle(text = "指令")
            directives.forEach { entry ->
                CommandRow(
                    glyph = entry.glyph,
                    title = entry.title,
                    english = entry.english,
                    desc = entry.desc
                ) {
                    onPick(entry.command)
                }
            }
        }
        if (contexts.isNotEmpty()) {
            SectionTitle(text = "上下文")
            contexts.forEach { entry ->
                CommandRow(
                    glyph = entry.glyph,
                    title = entry.title,
                    english = entry.english,
                    desc = entry.desc
                ) {
                    onPick(entry.command)
                }
            }
        }
        if (templates.isNotEmpty()) {
            SectionTitle(text = "命令")
            templates.forEach { entry ->
                TemplateRow(title = entry.title, desc = entry.desc) {
                    onPickTemplate(entry.insert)
                }
            }
        }
        // 没有任何技能时整组消失：一张技能空面板只会占掉对话框上方的高度
        if (skillHits.isNotEmpty()) {
            SectionTitle(text = "技能")
            skillHits.forEach { name ->
                SkillRow(name = name, onClick = { onPickSkill(name) })
            }
        }
    }
}

/** 面板里的一行：命令 + 图标 + 中文 + 英文 + 右侧说明；斜杠过滤就比这几个字段，不用另建索引。 */
private data class PanelCommand(
    val command: ComposerCommand,
    val glyph: PanelGlyph,
    val title: String,
    val english: String,
    val desc: String
)

private fun PanelCommand.matches(filter: String): Boolean =
    filter.isEmpty() ||
        title.contains(filter, ignoreCase = true) ||
        english.contains(filter, ignoreCase = true) ||
        desc.contains(filter, ignoreCase = true)

private fun skillMatches(name: String, filter: String): Boolean =
    filter.isEmpty() || name.contains(filter, ignoreCase = true)

/**
 * 斜杠模板：点击后仅将 [insert] 填入输入框，实际解析在 ViewModel。
 *
 * 面板不替用户执行，是因为 /plan 后面的那半句、/goal 的子命令都得由用户接着写。
 */
private data class SlashTemplate(val title: String, val desc: String, val insert: String)

private fun SlashTemplate.matches(filter: String): Boolean =
    filter.isEmpty() ||
        title.contains(filter, ignoreCase = true) ||
        desc.contains(filter, ignoreCase = true)

private val SLASH_TEMPLATES = listOf(
    SlashTemplate("/plan", "进入计划模式，可跟一句指令；/plan off 退出", "/plan "),
    SlashTemplate("/goal", "设定跨轮长期目标；/goal pause|resume|edit|clear 控制", "/goal ")
)

private val ATTACH_COMMANDS = listOf(
    PanelCommand(ComposerCommand.ADD_FILE, PanelGlyph.FILE, "文件", "file", "附加文件到当前会话"),
    PanelCommand(ComposerCommand.ADD_GOAL, PanelGlyph.GOAL, "目标", "goal", "设置或查看长期任务目标"),
    PanelCommand(ComposerCommand.ADD_PLAN, PanelGlyph.PLAN, "计划", "plan", "进入或退出计划模式")
)

private val DIRECTIVE_COMMANDS = listOf(
    PanelCommand(ComposerCommand.COMPACT, PanelGlyph.COMPACT, "压缩", "compact", "压缩以上对话内容"),
    PanelCommand(ComposerCommand.DOWNLOAD_LOGS, PanelGlyph.EXPORT, "下载日志", "export", "将当前会话内容导出为 ZIP")
)

private val CONTEXT_COMMANDS = listOf(
    PanelCommand(ComposerCommand.CONTEXT, PanelGlyph.CONTEXT, "上下文", "context", "查看上下文占用与明细")
)

/** 斜杠面板是否还有内容可显示；全空时调用方直接不画，省掉一条空卡片。 */
internal fun composerPanelHasMatch(skills: List<String>, filter: String): Boolean =
    ATTACH_COMMANDS.any { it.matches(filter) } ||
        DIRECTIVE_COMMANDS.any { it.matches(filter) } ||
        CONTEXT_COMMANDS.any { it.matches(filter) } ||
        SLASH_TEMPLATES.any { it.matches(filter) } ||
        skills.any { skillMatches(it, filter) }

/**
 * 斜杠模板行：左边一根斜线 + 命令名，右侧说明它怎么接着写。
 *
 * 点它不会执行任何东西，只是把模板放进输入框，光标停在末尾等用户补完。
 */
@Composable
private fun TemplateRow(title: String, desc: String, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .saltCardRow { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PanelGlyphIcon(kind = PanelGlyph.SLASH, tint = onSurface.copy(alpha = 0.85f))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = title, fontSize = 15.sp, color = onSurface)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = desc,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End
        )
    }
}

/** 技能行：只放技能名，技能名本身就是它能干什么的描述。 */
@Composable
private fun SkillRow(name: String, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .saltCardRow { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PanelGlyphIcon(kind = PanelGlyph.SKILL, tint = onSurface.copy(alpha = 0.85f))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 权限浮窗：三档一字排开，当前档右侧打勾。
 *
 * 不采用「展开即主页那一页」：切换权限属于输入前的辅助操作，跳页会打断输入。
 */
@Composable
fun ComposerPermissionPanel(
    current: PermissionLevel,
    onPick: (PermissionLevel) -> Unit,
    modifier: Modifier = Modifier
) {
    PanelCard(modifier = modifier) {
        ChoiceRow(glyph = PanelGlyph.SHIELD, title = "仅可查看", selected = current == PermissionLevel.READ_ONLY) {
            onPick(PermissionLevel.READ_ONLY)
        }
        ChoiceRow(glyph = PanelGlyph.FOLDER, title = "工作区内修改", selected = current == PermissionLevel.WORKSPACE) {
            onPick(PermissionLevel.WORKSPACE)
        }
        ChoiceRow(glyph = PanelGlyph.WARNING, title = "完全权限", selected = current == PermissionLevel.FULL) {
            onPick(PermissionLevel.FULL)
        }
    }
}

/**
 * 上下文浮窗：顶部百分比 + 右侧「已用 / 窗口」、一条细进度条、三段明细。
 *
 * 点标题行或长按卡片进上下文页：这里只回答「还剩多少」，要看明细才值得跳页。
 */
@Composable
fun ComposerContextPanel(
    usage: ContextUsageInfo,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    val window = if (usage.window > 0) usage.window else 1
    val fraction = (usage.used.toFloat() / window).coerceIn(0f, 1f)
    // 使用但不足 1% 时仍显示 1%，否则进度条有色而数字为 0% 时看似故障。
    // 算法位于 Composer.kt 的 contextPercent 中，输入条的圆环使用同一份
    val percent = contextPercent(usage)
    // 三段构成：与 DSH 构成条一一对应，色值与顺序均照其组件实现
    val parts = listOf(
        Triple(usage.systemTokens, UiColors.meterSystem, "系统提示词"),
        Triple(usage.toolTokens, UiColors.meterTools, "工具定义"),
        Triple(usage.messageTokens, UiColors.meterMessages, "对话消息")
    )
    val partTotal = parts.sumOf { it.first }

    PanelCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .saltLongPress { onOpenDetail() }
                .padding(horizontal = 14.dp)
                .padding(top = 10.dp, bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenDetail)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 标题与百分比分两档着色：标题是说明（三级），百分比是数值（一级）
                Text(
                    text = "上下文已用",
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    color = UiColors.meterTertiary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = percent.toString() + "%",
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = UiColors.meterPrimary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "~" + composerTokenText(usage.used) + " / " + composerTokenText(usage.window),
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    // 等宽数字：数值在两帧之间变化时不会因字宽不同而左右抖动
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    color = UiColors.meterPrimary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            // 构成条：底槽固定，彩色段按各段占比切分，整条的总长仍是占用百分比
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(UiColors.meterTrack)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    if (partTotal <= 0) {
                        // 有占用但三段明细都为零：只画一段整体色，不留一条空底槽
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(UiColors.meterTertiary, RoundedCornerShape(1.dp))
                        )
                    } else {
                        parts.forEach { part ->
                            if (part.first <= 0) return@forEach
                            Box(
                                modifier = Modifier
                                    .weight(part.first.toFloat())
                                    .fillMaxHeight()
                                    .background(part.second, RoundedCornerShape(1.dp))
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            parts.forEach { part ->
                UsageRow(color = part.second, title = part.third, tokens = part.first)
            }
        }
    }
}

/**
 * 会话统计浮窗：本会话的时间账与输出速度。
 *
 * 只放四个数、都能一眼读完：模型用时、工具调用用时、首 token 平均（TTFT）、输出速度（TPS）。
 * 没有样本的项写「—」：新会话刚开还没跑过一轮时，写 0 秒会让人以为「模型没花时间」。
 */
@Composable
fun ComposerStatsPanel(stats: ComposerStatsInfo, modifier: Modifier = Modifier) {
    PanelCard(modifier = modifier) {
        PanelHeader(
            title = "会话统计",
            glyph = { tint -> StepsGlyph(tint = tint, iconSize = 14.dp) }
        )
        DetailRow(title = "模型用时", value = durationText(stats.modelMillis))
        DetailRow(title = "工具调用用时", value = durationText(stats.toolMillis))
        DetailRow(
            title = "首 token 平均（TTFT）",
            value = stats.ttftAverageMillis?.let { durationText(it) } ?: "—"
        )
        DetailRow(
            title = "输出速度（TPS）",
            value = stats.tokensPerSec?.let { it.toString() + " tok/s" } ?: "—"
        )
    }
}

/**
 * Token 用量浮窗：标题行右边就是总用量，下面四行拆分。
 *
 * 合计口径：未缓存输入 + 缓存读取 + 输出。缓存字段服务商没报时，
 * 「缓存命中」写「—」、另两行按 0 显示，但绝不编一个 0% 出来。
 */
@Composable
fun ComposerTokenPanel(stats: ComposerStatsInfo, modifier: Modifier = Modifier) {
    val total = stats.cacheHitTokens + stats.cacheMissTokens + stats.outputTokens
    PanelCard(modifier = modifier) {
        PanelHeader(
            title = "Token 用量",
            glyph = { tint -> CylinderGlyph(tint = tint, iconSize = 14.dp) },
            trailing = countText(total)
        )
        DetailRow(
            title = "缓存命中",
            value = stats.cacheHitPercent?.let { percentText(it) } ?: "—"
        )
        DetailRow(title = "未缓存输入", value = countText(stats.cacheMissTokens))
        DetailRow(title = "缓存读取", value = countText(stats.cacheHitTokens))
        DetailRow(title = "输出", value = countText(stats.outputTokens))
    }
}

/**
 * 一轮的用量账：气泡尾部的用量按钮与它弹出的伴随面板共用这一份数据，数字口径只写一处。
 *
 * 为什么不直接读会话累计值：用户点的是「这一轮花了多少」。会话累计值是另一本账（状态行那两枚胶囊），
 * 两本账混用就会出现「面板里 75,689、气泡上 45.5K」这种自相矛盾。
 *
 * 字段全部按「服务商报没报」区分：缓存读取 / 缓存写入 / 推理三项为「没报」时，面板上整行不显示
 * 字段全部按「服务商报没报」区分：缓存读取 / 缓存写入 / 推理三项为「没报」时，面板上整行不显示，
 */
data class TurnUsageInfo(
    /** 未缓存输入（归一化后的口径，见 UsageBuckets）。 */
    val uncachedInputTokens: Int,
    val cacheReadTokens: Int,
    /** 服务商报没报「缓存读取」这一项。 */
    val cacheReadReported: Boolean,
    /**
     * 缓存写入。DeepSeek / OpenAI 没有这一项，但面板上**固定写 0 tok**：
     * 参考图中该行即为 0 tok，缺少该行会被误认为未统计。
     */
    val cacheWriteTokens: Int,
    val completionTokens: Int,
    /** 推理 tokens：null = 不报（不显示括号里那句）。 */
    val reasoningTokens: Int?,
    val model: String = ""
) {
    /** 输入总量 = 未缓存 + 缓存读取 + 缓存写入；跨服务商同一口径。 */
    val inputTokens: Int get() = uncachedInputTokens + cacheReadTokens + cacheWriteTokens

    /** 面板右上角那个总数：输入总量 + 输出。 */
    val total: Int get() = inputTokens + completionTokens

    /** 缓存命中率：没报就是 null，界面不显示这一行；分母是输入总量。 */
    val cacheHitPercent: Float?
        get() = if (cacheReadReported && inputTokens > 0) {
            cacheReadTokens * 100f / inputTokens
        } else {
            null
        }

    val hasUsage: Boolean get() = inputTokens > 0 || completionTokens > 0
}

/**
 * 「本轮用量」伴随面板：行序与字段固定
 * （提供方/模型、缓存命中、未缓存输入、缓存读取、缓存写入、输出〔其中推理〕），
 * 卡片本体仍用本工程这套 Salt 观感：20dp 圆角、surface 底、不投影不描边、标题下一道细分割线。
 *
 * 条件行的取舍：模型串为空不显示「提供方 / 模型」；缓存读取没报不显示；
 * 缓存写入为 null 不显示；推理为 null 时不加「（其中推理 N tok）」。
 * 这样换一家服务商，面板不会凭空多出几行 0。
 */
@Composable
fun TurnUsagePanel(usage: TurnUsageInfo, modifier: Modifier = Modifier) {
    PanelCard(modifier = modifier) {
        PanelHeader(
            title = "本轮用量",
            glyph = { tint -> CylinderGlyph(tint = tint, iconSize = 14.dp) },
            trailing = countText(usage.total),
            rule = true
        )
        // 六行固定：与参考图一致。无数据的行写「—」，不整行消失：
        // 少一行会被理解为「此项未统计」，实际仅为该服务商或该旧会话未上报。
        DetailRow(
            title = "提供方 / 模型",
            value = usage.model.ifBlank { "—" },
            singleLine = true
        )
        DetailRow(
            title = "缓存命中",
            value = usage.cacheHitPercent?.let { percentText(it) } ?: "—"
        )
        DetailRow(title = "未缓存输入", value = countText(usage.uncachedInputTokens))
        DetailRow(
            title = "缓存读取",
            value = if (usage.cacheReadReported) countText(usage.cacheReadTokens) else "—"
        )
        DetailRow(title = "缓存写入", value = countText(usage.cacheWriteTokens))
        DetailRow(
            title = "输出",
            value = countText(usage.completionTokens),
            suffix = usage.reasoningTokens?.let { "（其中推理 " + countText(it) + "）" },
            suffixStrong = false
        )
    }
}

/** 浮窗标题行：图标 + 标题，右边可以挂一个总值（Token 用量那张挂总 tokens）。 */
@Composable
private fun PanelHeader(
    title: String,
    glyph: @Composable (Color) -> Unit,
    trailing: String? = null,
    /** 标题下面画一道细分割线：用量面板有这道线，输入框那几张浮窗没有。 */
    rule: Boolean = false
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        glyph(onSurface.copy(alpha = 0.7f))
        Spacer(modifier = Modifier.width(7.dp))
        Text(text = title, fontSize = 14.sp, color = onSurface)
        Spacer(modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(text = trailing, fontSize = 13.sp, color = onSurface.copy(alpha = 0.7f))
        }
    }
    if (rule) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp)
                .height(1.dp)
                .background(onSurface.copy(alpha = 0.08f))
        )
    }
}

/**
 * 明细一行：左边名字、右边数值，行高 28dp：四行刚好在对话框中上方的浮窗里排开。
 *
 * [singleLine] 给「提供方 / 模型」这种可能很长又必须原样看全一半的值用：单行截尾加省略号，
 * 不换行也不把行高撑开。默认 false，输入框相关浮窗的排布保持不变。
 */
@Composable
private fun DetailRow(
    title: String,
    value: String,
    singleLine: Boolean = false,
    /** 值后面跟一句小字说明（用量面板的「（其中推理 N tok）」用它）。 */
    suffix: String? = null,
    /** 小字是否跟主值同色：用量面板里它是弱提示，弱一档。 */
    suffixStrong: Boolean = false
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, fontSize = 13.sp, color = onSurface.copy(alpha = 0.6f))
        // 单行值的行**不挂载 weight 占位**：两个 weight(1f) 会平分剩余宽度，
        // 值仅剩一半，较长的「提供方 / 模型」会被截断为 "deepseek/de…"（实测已出现）
        if (singleLine) {
            Text(
                text = value,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
            Text(text = value, fontSize = 13.sp, color = onSurface)
        }
        if (suffix != null) {
            Text(
                text = suffix,
                fontSize = 12.sp,
                color = onSurface.copy(alpha = if (suffixStrong) 1f else 0.5f),
                maxLines = 1
            )
        }
    }
}

/** 毫秒 → 「22 分 4 秒」/「2.4 秒」；0 或负数说明还没数据，界面写「—」。 */
private fun durationText(millis: Long): String {
    if (millis <= 0L) return "—"
    if (millis < 1_000L) return millis.toString() + " 毫秒"
    if (millis < 60_000L) return String.format(Locale.US, "%.1f 秒", millis / 1000.0)
    val minutes = millis / 60_000L
    val seconds = (millis % 60_000L) / 1000L
    return if (seconds == 0L) {
        minutes.toString() + " 分"
    } else {
        minutes.toString() + " 分 " + seconds + " 秒"
    }
}

/** tokens 全量数字带千分位（37,427,252 tok）：浮窗里给准确值，状态行才用 K/M 缩写。 */
private fun countText(count: Int): String = String.format(Locale.US, "%,d", count) + " tok"

/**
 * 卡片本体：20dp 圆角 + surface 底，**不投影、不描边**。
 *
 * 这两样都试过、都撤了：Compose 的 elevation 阴影是按节点的矩形包围盒落影的，
 * 圆角弧线之外会露出一圈直角阴影，外加一条沿着包围盒的浅灰「包边」，
 * 四角看起来就是「圆弧贴在一张方卡上」；再叠 1dp 发丝边只会让这圈包边更明显。
 * 本界面本身为扁平设计，通过 surface 底色与页面底色的差异区分层次即可。
 */
@Composable
private fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 阴影随卡片一同出现：根因是上一层 alpha 淡入，Android 会将带透明度图层中的阴影按 alpha 压暗，alpha 接近 1 前几乎不可见；现由 elevation 从 0 增至 14dp，阴影与面板位移/缩放同步出现。
    var risen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { risen = true }
    // 高度为 8dp：Compose 阴影半径与偏移随高度线性放大，14dp 会扩散至卡片外一大圈浅灰，8dp 则贴合圆角，边界清晰可辨。
    val panelShadow by animateDpAsState(
        targetValue = if (risen) 8.dp else 0.dp,
        animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing),
        label = "panelShadow"
    )
    Surface(
        shape = RoundedCornerShape(24.dp),
        // 面板底色比页面与气泡亮一档，浮出时方可看出边界
        color = if (isDark) Palette.DarkSurfaceHigh else Palette.LightSurfaceHigh,
        // 柔和扩散阴影：使卡片与页面分层，又不至于形成描边。
        // 骨架跟随形状轮廓走圆角，四角不露直角；数值由上方动画从 0 增长。
        shadowElevation = panelShadow,
        // 卡片自身消费点击：否则点击卡片空白处会落到下方输入框，导致键盘弹出
        modifier = modifier
            .fillMaxWidth()
            .blockTapsOnly()
    ) {
        // 卡片内不设上下留白：行自带内距，首/末行的波纹方可贴至 24dp 圆弧并被裁为圆角。
        // 省略这 6dp 亦可减轻「弹出键盘时面板顶出可视区」的压力（分组标题自带 top=5dp）
        Column(content = content)
    }
}

/** 分组标题：一行 12sp 淡色字，不加分割线，靠留白分组。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 5.dp, bottom = 1.dp),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    )
}

/** 一条「＋」菜单项：左图标、中文、英文小字，右侧说明占满剩余宽度并右对齐。 */
@Composable
private fun CommandRow(
    glyph: PanelGlyph,
    title: String,
    english: String,
    desc: String,
    dot: Boolean = false,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 38dp：弹出键盘时九行亦需完整落在可视区内
            .height(38.dp)
            .saltCardRow { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PanelGlyphIcon(kind = glyph, tint = onSurface.copy(alpha = 0.85f))
        Spacer(modifier = Modifier.width(10.dp))
        if (dot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Palette.Neutral, RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = title, fontSize = 15.sp, color = onSurface)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = english, fontSize = 12.sp, color = onSurface.copy(alpha = 0.4f))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = desc,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End
        )
    }
}

/** 权限三档的一行：图标 + 名字，选中的那档右侧打勾。 */
@Composable
private fun ChoiceRow(
    glyph: PanelGlyph,
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .saltCardRow { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PanelGlyphIcon(kind = glyph, tint = onSurface.copy(alpha = 0.85f))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = title, fontSize = 15.sp, color = onSurface)
        Spacer(modifier = Modifier.weight(1f))
        if (selected) {
            UnitGlyph(iconSize = 15.dp) {
                drawPath(
                    path = panelPath {
                        moveTo(4f, 9.4f)
                        lineTo(7.6f, 13f)
                        lineTo(14f, 5.6f)
                    },
                    color = Palette.Neutral,
                    style = panelStroke(1.8f)
                )
            }
        }
    }
}

/** 上下文明细的一行：色块 + 名称 + 右对齐数值，色块跟进度条同色，读起来对得上。 */
@Composable
private fun UsageRow(color: Color, title: String, tokens: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = title, fontSize = 12.sp, lineHeight = 20.sp, color = UiColors.meterSecondary)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "~" + composerTokenText(tokens),
            fontSize = 12.sp,
            lineHeight = 20.sp,
            style = TextStyle(fontFeatureSettings = "tnum"),
            color = UiColors.meterPrimary
        )
    }
}

/**
 * 「＋」菜单与权限档那一套 18×18 线稿已经提到 ui/common 的 [GlyphIcon]，
 * 这里只做转发：调用点写法不变，全 App 的图标口径收在一处。
 */
@Composable
private fun PanelGlyphIcon(kind: PanelGlyph, tint: Color) {
    GlyphIcon(glyph = kind, size = 16.dp, tint = tint)
}

/** 单位坐标系里的描边规格：端点、拐角收圆，跟 DSH 图标的观感一致。 */
private fun panelStroke(width: Float = 1.6f): Stroke =
    Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** 拼 18×18 单位坐标路径的小工具。 */
private fun panelPath(build: Path.() -> Unit): Path = Path().apply(build)
