package com.zmf.agent.feature.chat

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.uikit.common.saltCursorBrush
import com.zmf.agent.uikit.icons.ArrowLeftIcon
import com.zmf.agent.uikit.icons.ChevronDownIcon
import com.zmf.agent.uikit.icons.CloseIcon
import com.zmf.agent.uikit.icons.UnitGlyph
import com.zmf.agent.uikit.icons.unitStroke
import com.zmf.agent.uikit.layout.LocalUiWindow
import com.zmf.agent.uikit.markdown.MarkdownText
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.delay

/** 选项行圆角（与 DSH 的 option 一致）。 */
private val QuestionOptionShape = RoundedCornerShape(10.dp)

/** 按钮圆角。 */
private val QuestionButtonShape = RoundedCornerShape(9.dp)

/** 弹出时从 0.90 撑到 1：与「＋」等伴随面板完全同一套动效。 */
private const val QUESTION_POP_FROM = 0.90f

/** 倒计时刷新间隔：按秒显示，半秒刷新可避免跳秒。 */
private const val COUNTDOWN_TICK_MS = 500L

/** 剩余时间进入警告色的阈值。 */
private const val WARNING_WINDOW_MS = 10_000L

/** 正文区最多占窗口高度的比例（照 DSH 的 `max-height: min(60vh, 520px)` 收一档）。 */
private const val BODY_MAX_RATIO = 0.42f

/** 正文区最大高度的绝对上限。 */
private val BODY_MAX_HEIGHT = 420.dp

/**
 * 卡片总高的保底值：键盘弹出后停靠线上方的空间可能很小，压到这个高度以下就不如让正文滚动了。
 * 仍在极矮的窗口（横屏 + 键盘）里可能露不全，但那种情形本来也没有可用的竖向空间。
 */
private val CARD_MIN_HEIGHT = 200.dp

/** 「推荐」标记：模型在选项文字尾部写 `（推荐）`／`(recommended)`，展示时剥掉并改成徽标。 */
private val RECOMMENDED_SUFFIX = Regex("\\s*[(（](?:推荐|recommended)[)）]\\s*$", RegexOption.IGNORE_CASE)

private const val CUSTOM_PLACEHOLDER = "输入你的答案"
private const val SUBMIT_LABEL = "提交"
private const val NEXT_LABEL = "下一题"
private const val SKIP_LABEL = "跳过"
private const val RECOMMENDED_LABEL = "推荐"

/**
 * 提问面板：与普通输入区「＋」等按钮弹出的伴随面板同一形态、同一套弹出动效，
 * 挂在**输入条上方**（不顶替输入条）。
 *
 * 内容与交互逐项照 DSH 的提问卡（`dsh-client-ui-user-questions/lib/client.js`）：
 * 头部＝短标题 + 右侧「收起/展开」与「叉号」（**不含**扩大窗口按钮，用户明确剔除）；
 * 正文＝问题（16sp/22sp Medium）+ 选项（序号块 + 可换行标签 +「推荐」徽标 + 灰描述）
 * + 自定义答案行（图标块 + 内联输入框，占位「输入你的答案」）；正文整体区内滚动；
 * 底部＝左侧翻页器 `‹ x / N ›` + 右侧「跳过」与「下一题/提交」（末题才是提交）。
 *
 * 与 DSH 的差别只有两处，都是本工程的既有约定：面板外壳用 `PanelCard`、动效用跟随面板那套弹出。
 */
@Composable
internal fun QuestionPanel(
    ask: QuestionCenter.ActiveAsk,
    onAnswer: (selected: List<String>, custom: String?) -> Unit,
    onClose: () -> Unit,
    onGoto: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scale = remember(ask.token) { Animatable(QUESTION_POP_FROM) }
    LaunchedEffect(ask.token) {
        scale.snapTo(QUESTION_POP_FROM)
        scale.animateTo(1f, Motion.softSpring())
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                // 与伴随面板同一起点：围绕底边向上弹出
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
    ) {
        PanelCard {
            QuestionPanelBody(
                ask = ask,
                onAnswer = onAnswer,
                onClose = onClose,
                onGoto = onGoto
            )
        }
    }
}

@Composable
private fun QuestionPanelBody(
    ask: QuestionCenter.ActiveAsk,
    onAnswer: (selected: List<String>, custom: String?) -> Unit,
    onClose: () -> Unit,
    onGoto: (Int) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    // 倒计时按固定节拍读取当前时刻；截止时刻取自请求，重进组合后仍与原请求一致。
    val deadline = ask.deadline
    var now by remember(ask.token) { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(ask.token, deadline) {
        if (deadline == null) return@LaunchedEffect
        while (true) {
            now = SystemClock.elapsedRealtime()
            if (deadline - now <= 0L) break
            delay(COUNTDOWN_TICK_MS)
        }
    }
    val remaining = deadline?.minus(now)?.coerceAtLeast(0L)
    val expired = remaining != null && remaining <= 0L

    // 每题一份草稿：翻页器来回切时勾选与自由文本按题各自保留（DSH 同样按题保留草稿）
    val pickedMap = remember(ask.token) { mutableStateMapOf<Int, Set<String>>() }
    val customMap = remember(ask.token) { mutableStateMapOf<Int, String>() }
    val picked = pickedMap[ask.index] ?: emptySet()
    val custom = customMap[ask.index].orEmpty()
    val multi = ask.question.multiSelect
    val lastQuestion = ask.index >= ask.total - 1
    var collapsed by remember(ask.token) { mutableStateOf(false) }

    // 首个带「推荐」标记的选项只作为草稿预选：用户仍要显式提交（DSH 的 recommendedFirstOption）
    LaunchedEffect(ask.token, ask.index) {
        if (pickedMap.containsKey(ask.index)) return@LaunchedEffect
        val recommended = ask.question.options.firstOrNull()?.label
            ?.takeIf { RECOMMENDED_SUFFIX.containsMatchIn(it) }
        if (recommended != null) pickedMap[ask.index] = setOf(recommended)
    }

    fun choose(label: String) {
        if (expired) return
        if (multi) {
            pickedMap[ask.index] = if (label in picked) picked - label else picked + label
        } else {
            pickedMap[ask.index] = if (picked == setOf(label)) emptySet() else setOf(label)
        }
    }

    val answered = picked.isNotEmpty() || custom.isNotBlank()

    fun commit() {
        if (expired || !answered) return
        val customValue = custom.trim().takeIf { it.isNotEmpty() }
        // 非末题：记下本题答案并前进；末题：整批提交。两种都由 QuestionCenter.answer 承担
        onAnswer(picked.toList(), customValue)
    }

    val window = LocalUiWindow.current
    val bodyMax = minOf(BODY_MAX_HEIGHT, (window.heightDp * BODY_MAX_RATIO))
    // 卡片总高上限：取停靠层量到的「输入条上方实际空间」。键盘弹出时窗口高度不变、
    // 停靠线上移，只按窗口比例算会把头部的标题与叉号顶出屏幕顶端。
    val cardMax = maxOf(LocalComposerPanelRoom.current ?: window.heightDp, CARD_MIN_HEIGHT)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 头部与底部固定、正文让位：总高超过这段空间时先压缩正文
            .heightIn(max = cardMax)
            .alpha(if (expired) 0.4f else 1f)
            // 头部与底部固定，中间的正文区自己滚
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp)
    ) {
        // ---- 头部：短标题（+ 倒计时）与右侧两枚 24dp 图标 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ask.title,
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = UiColors.subText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (remaining != null) {
                Text(
                    text = countdownLabel(remaining),
                    modifier = Modifier.padding(end = 4.dp),
                    fontSize = 11.sp,
                    color = if (remaining <= WARNING_WINDOW_MS) UiColors.warning else UiColors.subText,
                    maxLines = 1
                )
            }
            QuestionIconButton(onClick = { collapsed = !collapsed }) {
                Icon(
                    imageVector = ChevronDownIcon,
                    contentDescription = if (collapsed) "展开提问" else "收起提问",
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = if (collapsed) 180f else 0f },
                    tint = UiColors.subText
                )
            }
            QuestionIconButton(onClick = onClose) {
                Icon(
                    imageVector = CloseIcon,
                    contentDescription = "关闭提问",
                    modifier = Modifier.size(16.dp),
                    tint = UiColors.subText
                )
            }
        }

        // ---- 正文区：题目 + 选项 + 自定义答案行，整体可滚动 ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                // 头部与底部先量、正文最后分剩余高度：空间不够时压缩正文而不是把卡片顶出屏幕
                .weight(1f, fill = false)
                .heightIn(max = bodyMax)
                .verticalScroll(rememberScrollState())
        ) {
            if (collapsed) {
                // 收起态：只留一行题干（与参考图的向下箭头状态同构）
                Text(
                    text = ask.question.question.replace('\n', ' '),
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = onSurface.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                MarkdownText(
                    text = ask.question.question,
                    fontSize = 16.sp,
                    color = onSurface
                )
            }

            if (!collapsed) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    ask.question.options.forEachIndexed { index, option ->
                        val recommended = RECOMMENDED_SUFFIX.containsMatchIn(option.label)
                        QuestionOptionRow(
                            number = if (multi) null else index + 1,
                            label = RECOMMENDED_SUFFIX.replace(option.label, ""),
                            description = option.description,
                            selected = option.label in picked,
                            multi = multi,
                            enabled = !expired,
                            recommended = recommended,
                            onClick = { choose(option.label) }
                        )
                    }
                    // 自定义答案行：左侧图标块（多选时是勾选框）+ 内联输入框，占位「输入你的答案」
                    QuestionCustomRow(
                        value = custom,
                        enabled = !expired,
                        multi = multi,
                        // 单选：自由作答与选中选项**互斥**，任何时候只有一个条目呈选中态
                        // （用户 2026-11-15：先选选项再打字，会出现两块同时被框住）
                        selected = custom.isNotBlank() && (multi || picked.isEmpty()),
                        onValueChange = { text ->
                            customMap[ask.index] = text
                            // 单选时一开始打字就撤掉选项，保证只有自填那一行是选中的
                            if (!multi && text.isNotBlank()) pickedMap[ask.index] = emptySet()
                        }
                    )
                }
            }
        }

        // ---- 底部：翻页器 + 跳过 + 下一题／提交 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (ask.total > 1) {
                PagerArrow(enabled = !expired && ask.index > 0, previous = true) {
                    onGoto(ask.index - 1)
                }
                Text(
                    text = (ask.index + 1).toString() + " / " + ask.total,
                    modifier = Modifier.padding(horizontal = 6.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = UiColors.subText,
                    maxLines = 1
                )
                PagerArrow(enabled = !expired && ask.index < ask.total - 1, previous = false) {
                    onGoto(ask.index + 1)
                }
            }
            Spacer(Modifier.weight(1f))
            QuestionSecondaryButton(label = SKIP_LABEL, enabled = !expired) {
                onAnswer(emptyList(), null)
            }
            Spacer(Modifier.width(8.dp))
            QuestionPrimaryButton(
                label = if (lastQuestion) SUBMIT_LABEL else NEXT_LABEL,
                enabled = !expired && answered,
                onClick = { commit() }
            )
        }
    }
}

/** 头部 24dp 图标按钮：圆形热区、无底色。 */
@Composable
private fun QuestionIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 翻页器箭头：24dp 圆形热区，禁用时压到 35%。 */
@Composable
private fun PagerArrow(enabled: Boolean, previous: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = ArrowLeftIcon,
            contentDescription = if (previous) "上一题" else "下一题",
            // 同一枚左箭头，向右那侧水平翻转
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer { if (!previous) scaleX = -1f },
            tint = UiColors.subText
        )
    }
}

/** 「跳过」：描边按钮（DSH 的 outline 变体）。 */
@Composable
private fun QuestionSecondaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(QuestionButtonShape)
            .border(1.dp, onSurface.copy(alpha = 0.15f), QuestionButtonShape)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, fontSize = 13.sp, color = onSurface, maxLines = 1)
    }
}

/**
 * 主按钮「下一题 / 提交」：照 DSH 的 primary 变体 —— 底色用**文字色**（浅色主题是深墨、深色主题是浅白），
 * 字用页面底色；禁用时整块压到灰（不改色相）。
 */
@Composable
private fun QuestionPrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val background = if (enabled) onSurface else onSurface.copy(alpha = 0.12f)
    val textColor = if (enabled) {
        MaterialTheme.colorScheme.background
    } else {
        onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .widthIn(min = 92.dp)
            .clip(QuestionButtonShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1
        )
    }
}

/**
 * 单个选项行：序号块（多选题为勾选框）+ 可换行标签 +「推荐」徽标 + 灰描述。
 *
 * 选中态照 DSH：行底色 + 一圈发丝边 + 字重加一级，**不画单选圆点**。
 */
@Composable
private fun QuestionOptionRow(
    number: Int?,
    label: String,
    description: String,
    selected: Boolean,
    multi: Boolean,
    enabled: Boolean,
    recommended: Boolean,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val stroke = if (selected) onSurface.copy(alpha = 0.18f) else Color.Transparent
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(QuestionOptionShape)
            .background(if (selected) UiColors.rowSelected else Color.Transparent)
            .border(1.dp, stroke, QuestionOptionShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick
            )
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        QuestionMarker(
            selected = selected,
            multi = multi,
            number = number,
            enabled = enabled,
            tint = if (selected) UiColors.accentFg else UiColors.subText
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 14.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = onSurface.copy(alpha = if (enabled) 1f else 0.5f)
                )
                if (recommended) {
                    Spacer(Modifier.width(6.dp))
                    RecommendedBadge()
                }
            }
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = UiColors.subText
                )
            }
        }
    }
}

/** 自定义答案行：左侧图标块 + 内联输入框（占位「输入你的答案」）。 */
@Composable
private fun QuestionCustomRow(
    value: String,
    enabled: Boolean,
    multi: Boolean,
    selected: Boolean,
    onValueChange: (String) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(QuestionOptionShape)
            .background(if (selected) UiColors.rowSelected else Color.Transparent)
            .border(
                1.dp,
                if (selected) onSurface.copy(alpha = 0.18f) else Color.Transparent,
                QuestionOptionShape
            )
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        QuestionMarker(
            selected = selected,
            multi = multi,
            number = null,
            enabled = enabled,
            tint = if (selected) UiColors.accentFg else UiColors.subText
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f).heightIn(min = 24.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 24.sp,
                    color = onSurface
                ),
                cursorBrush = saltCursorBrush(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = CUSTOM_PLACEHOLDER,
                                fontSize = 14.sp,
                                lineHeight = 24.sp,
                                color = UiColors.subText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        inner()
                    }
                }
            )
        }
    }
}

/** 行首标记：单选/自定义行是 20dp 方块（数字或铅笔图标），多选题是 18dp 勾选框。 */
@Composable
private fun QuestionMarker(
    selected: Boolean,
    multi: Boolean,
    number: Int?,
    enabled: Boolean,
    tint: Color
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val checkColor = MaterialTheme.colorScheme.background
    if (multi) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (selected) onSurface else Color.Transparent)
                .border(1.dp, if (selected) onSurface else onSurface.copy(alpha = 0.35f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                UnitGlyph(iconSize = 12.dp) {
                    val stroke = unitStroke(2.2f)
                    drawLine(checkColor, Offset(3.6f, 9.2f), Offset(7.2f, 12.6f), stroke.width, StrokeCap.Round)
                    drawLine(checkColor, Offset(7.2f, 12.6f), Offset(14.2f, 5.4f), stroke.width, StrokeCap.Round)
                }
            }
        }
        return
    }
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(onSurface.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center
    ) {
        if (number != null) {
            Text(
                text = number.toString(),
                modifier = Modifier.fillMaxWidth(),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = tint,
                maxLines = 1
            )
        } else {
            // 自定义答案行：一支铅笔（DSH 用 IconEditOutlineRegular）
            UnitGlyph(iconSize = 12.dp) {
                val stroke = unitStroke(1.6f)
                drawLine(tint, Offset(3.4f, 14.6f), Offset(4.6f, 10.6f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(4.6f, 10.6f), Offset(12.4f, 2.8f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(12.4f, 2.8f), Offset(15.2f, 5.6f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(15.2f, 5.6f), Offset(7.4f, 13.4f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(7.4f, 13.4f), Offset(3.4f, 14.6f), stroke.width, StrokeCap.Round)
                drawCircle(tint, radius = 0.9f, center = Offset(5.6f, 12.4f), style = Stroke(width = 1f))
            }
        }
    }
}

/** 「推荐」徽标：DSH 用强调蓝的低饱和底 + 蓝字，两套主题都用本工程的 `accentFg`／`accentFill` 取透明度。 */
@Composable
private fun RecommendedBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(UiColors.accentFill.copy(alpha = 0.16f))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = RECOMMENDED_LABEL,
            fontSize = 11.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = UiColors.accentFg,
            maxLines = 1
        )
    }
}

/** 倒计时文案：`剩余 m:ss`；剩余不足一秒时取 0。 */
private fun countdownLabel(remainingMs: Long): String {
    val totalSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = (totalSeconds % 60L).toString().padStart(2, '0')
    return "剩余 " + minutes + ":" + seconds
}
