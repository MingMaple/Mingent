package com.zmf.agent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.ui.common.GlyphIcon
import com.zmf.agent.ui.common.PanelGlyph
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.common.saltLongPress
import com.zmf.agent.ui.theme.Motion
import com.zmf.agent.ui.theme.Palette

/**
 * 「＋」浮窗：附件 / 指令 / 上下文 / 命令 / 技能，每行「图标 + 名字 + 英文 + 右侧说明」。
 *
 * 说明放右边而不是第二行：用户是来挑一个动作，不是来读文档；38dp 的行高让键盘弹起时整块还能落在可视区。
 * [filter] 给斜杠指令复用：以 "/" 开头的输入直接过滤这张面板，命中的行才留，空组连灰标题都不画
 * —— 画一个空标题等于告诉用户「这里本来有东西，只是没匹配上」，那是噪音。
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
 * 斜杠模板：点了只把 [insert] 塞进输入框，真正的解析在 ViewModel。
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
            .clickable(onClick = onClick)
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

/** 技能行：只放技能名——技能名本身就是它能干什么的描述。 */
@Composable
private fun SkillRow(name: String, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable(onClick = onClick)
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
 * 不做成「展开就是主页那一页」是因为切权限是输入前的顺手动作，跳页会打断输入。
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
    val onSurface = MaterialTheme.colorScheme.onSurface
    val window = if (usage.window > 0) usage.window else 1
    val fraction = (usage.used.toFloat() / window).coerceIn(0f, 1f)
    // 用了但不足 1% 时仍然显示 1%，不然进度条有颜色、数字却是 0% 会看着像坏了
    val percent = if (usage.used <= 0) 0 else ((usage.used * 100 + window / 2) / window).coerceAtLeast(1)

    PanelCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .saltLongPress { onOpenDetail() }
                .padding(horizontal = 14.dp)
                .padding(top = 8.dp, bottom = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenDetail)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "上下文已用 " + percent + "%", fontSize = 15.sp, color = onSurface)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "~" + composerTokenText(usage.used) + " / " + composerTokenText(usage.window),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(onSurface.copy(alpha = 0.1f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Palette.AccentGreen)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            UsageRow(
                color = onSurface.copy(alpha = 0.35f),
                title = "系统提示词",
                tokens = usage.systemTokens
            )
            UsageRow(
                color = Palette.AccentPurple,
                title = "工具定义",
                tokens = usage.toolTokens
            )
            UsageRow(
                color = Palette.AccentCyan,
                title = "对话消息",
                tokens = usage.messageTokens
            )
        }
    }
}

/**
 * 卡片本体：20dp 圆角 + surface 底，**不投影、不描边**。
 *
 * 这两样都试过、都撤了：Compose 的 elevation 阴影是按节点的矩形包围盒落影的，
 * 圆角弧线之外会露出一圈直角阴影，外加一条沿着包围盒的浅灰「包边」，
 * 四角看起来就是「圆弧贴在一张方卡上」；再叠 1dp 发丝边只会让这圈包边更明显。
 * 这套界面本来就是扁平的，靠 surface 底色与页面底色的差别区分层次就够了。
 */
@Composable
private fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        // 卡片自己吃掉点击：不然点卡片空白处会落到下面的输入框上，键盘会被拉起来
        modifier = modifier
            .fillMaxWidth()
            .blockTapsOnly()
    ) {
        // 上下留白收到 3dp：弹着键盘时多行 + 分组标题已经很接近屏幕顶，
        // 再宽一点就会有按钮被顶出可视区
        Column(modifier = Modifier.padding(vertical = 3.dp), content = content)
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
            // 38dp：弹着键盘时九行也要能完整落在可视区内
            .height(38.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PanelGlyphIcon(kind = glyph, tint = onSurface.copy(alpha = 0.85f))
        Spacer(modifier = Modifier.width(10.dp))
        if (dot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Palette.AccentGreen, RoundedCornerShape(3.dp))
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
            .clickable(onClick = onClick)
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
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(9.dp))
        Text(text = title, fontSize = 13.sp, color = onSurface.copy(alpha = 0.8f))
        Spacer(modifier = Modifier.weight(1f))
        Text(text = "~" + composerTokenText(tokens), fontSize = 12.sp, color = onSurface.copy(alpha = 0.45f))
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
