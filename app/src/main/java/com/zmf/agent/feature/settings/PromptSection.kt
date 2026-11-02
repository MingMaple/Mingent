package com.zmf.agent.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.capability.prompt.PromptBuilder
import com.zmf.agent.capability.prompt.PromptInput
import com.zmf.agent.uikit.common.BoundedScrollColumn
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors

/**
 * 「提示词」折叠卡：追加指令 / 整份覆盖 / 生效预览。
 *
 * 独立成一个 composable（而不是并进某一页），是因为它既是上下文配置页的第一段，
 * 也可能被其它入口复用；页面只负责传当前值、收回保存结果，读写落盘交给调用方。
 *
 * 草稿状态以传入值作为 remember key：父页面换值（重新读盘 / 恢复默认）时草稿必须跟着重置。
 */
@Composable
fun PromptSection(
    extraPrompt: String,
    overridePrompt: String,
    onSaveExtra: (String) -> Unit,
    onSaveOverride: (String) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    var extraOpen by remember { mutableStateOf(false) }
    var overrideOpen by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }

    var extraDraft by remember(extraPrompt) { mutableStateOf(extraPrompt) }
    var overrideDraft by remember(overridePrompt) { mutableStateOf(overridePrompt) }

    // 预览始终基于草稿而非已保存值，输入后即可看到拼接的实际结果
    val preview = remember(extraDraft, overrideDraft) {
        PromptBuilder.build(
            PromptInput(extraInstructions = extraDraft, systemOverride = overrideDraft)
        ).orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 卡片自带页面边距：调用方不额外设置横向内距，避免两个页面拼接时宽度不一致
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp)
    ) {
        PromptCard(title = "提示词") {
            PromptEntryHeader(
                label = "追加提示词",
                expanded = extraOpen,
                onToggle = { extraOpen = !extraOpen }
            )
            AnimatedVisibility(
                visible = extraOpen,
                enter = expandVertically(Motion.spring()) + fadeIn(Motion.fadeIn()),
                exit = shrinkVertically(Motion.spring()) + fadeOut(Motion.fadeOut())
            ) {
                PromptEditor(
                    value = extraDraft,
                    onValueChange = { extraDraft = it },
                    placeholder = "例如：回答一律用中文",
                    fieldHeight = 120.dp,
                    onSave = {
                        onSaveExtra(extraDraft)
                        extraOpen = false
                    }
                )
            }

            PromptEntryHeader(
                label = "覆盖提示词",
                expanded = overrideOpen,
                onToggle = { overrideOpen = !overrideOpen }
            )
            AnimatedVisibility(
                visible = overrideOpen,
                enter = expandVertically(Motion.spring()) + fadeIn(Motion.fadeIn()),
                exit = shrinkVertically(Motion.spring()) + fadeOut(Motion.fadeOut())
            ) {
                PromptEditor(
                    value = overrideDraft,
                    onValueChange = { overrideDraft = it },
                    placeholder = "留空则用内置默认提示词",
                    fieldHeight = 140.dp,
                    onSave = {
                        onSaveOverride(overrideDraft)
                        overrideOpen = false
                    }
                )
            }

            PromptEntryHeader(
                label = "预览",
                expanded = previewOpen,
                // 这一行在折叠时就是卡片的末行：底部内距自己带，波纹才能贴到下圆弧
                bottomPadding = 12.dp,
                onToggle = { previewOpen = !previewOpen }
            )
            AnimatedVisibility(
                visible = previewOpen,
                enter = expandVertically(Motion.spring()) + fadeIn(Motion.fadeIn()),
                exit = shrinkVertically(Motion.spring()) + fadeOut(Motion.fadeOut())
            ) {
                // 用 BoundedScrollColumn 而不是 heightIn + 裸 verticalScroll：
                // 预览滚到最底部后不能再把余量交给外层页面（否则整页会跟着上滑）。
                // 装得下时（短提示词）它又把滑动让给页面，和普通静态块一样。
                BoundedScrollColumn(
                    maxHeight = 260.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(onSurface.copy(alpha = 0.04f))
                ) {
                    Text(
                        text = preview,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

/** 卡片：Salt 的圆角大块，底色 surface，不做描边。 */
@Composable
private fun PromptCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        // 卡片自身裁剪：内部行的波纹为矩形，不裁剪会在 16dp 圆角处露出方角
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        // 卡片 Column 不带横向内距：横向 16dp 由每一行自己带，
        // 「追加提示词／覆盖提示词／预览」这三行才能通栏、波纹被卡片圆角裁出圆弧。
        // 为什么内距要放进每一行，见 MemoryPage 卡片 Column 处的同一段说明。
        // 行内自带内距再配 saltRowClickable 显式圆角，波纹会缩进一圈、四角自成圆角。
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            // 末尾不设固定 Spacer：卡片内距全部分配给最后一行，
            // 折叠时「预览」这一行才能贴到卡片下缘、波纹被下圆弧裁成半圆。
            content()
        }
    }
}

/** 提示词条目头：一行 label + 右侧「▾」，展开时箭头弹簧翻转 180°。 */
@Composable
private fun PromptEntryHeader(
    label: String,
    expanded: Boolean,
    bottomPadding: Dp = 10.dp,
    onToggle: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.spring(),
        label = "promptEntryArrow"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 波纹通栏，形状由卡片的 16dp 圆角裁剪；行内不单独定义圆角。
            .saltCardRow { onToggle() }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface
        )
        Text(
            text = "▾",
            modifier = Modifier.rotate(turn),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
    }
}

/**
 * 提示词输入区：左侧输入框 + 右侧一列 44dp 的对勾。
 *
 * 对勾放在输入框外面单独一列，且那一列跟输入框等高、内容居中，
 * 于是对勾永远落在输入框高度的一半处，输入框变高也不用改偏移。
 */
@Composable
private fun PromptEditor(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    fieldHeight: Dp,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        PromptField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            fieldHeight = fieldHeight,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(fieldHeight),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✓",
                modifier = Modifier
                    .saltRowClickable(RoundedCornerShape(50)) { onSave() }
                    .padding(10.dp),
                fontSize = 18.sp,
                color = Palette.Neutral
            )
        }
    }
}

/**
 * 自绘输入框：不使用 OutlinedTextField 的默认焦点边框（Material 蓝），
 * 这里聚焦态描 Salt 绿，失焦态是一条极淡的中性描边。
 */
@Composable
private fun PromptField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    fieldHeight: Dp,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    // 输入框的排版口径与下面的 textStyle 一致：量「超没超出」时用的是同一份样式
    // （MaterialTheme.typography 是 @Composable 读取，必须先取出来再交给 remember）
    val baseStyle = MaterialTheme.typography.bodyMedium
    val textStyle = remember(baseStyle, onSurface) {
        baseStyle.copy(fontSize = 13.sp, color = onSurface)
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.height(fieldHeight)) {
        // 内容区 = 输入框去掉 decorationBox 里的内距（左右 12dp、上下 10dp）
        val textWidthPx = with(density) { (maxWidth - 24.dp).toPx() }.toInt().coerceAtLeast(1)
        val textHeightPx = with(density) { (maxHeight - 20.dp).toPx() }
        // 文本是否超出可视区域：仅在超出时，输入框滚到边界后的剩余位移才交由输入框消耗。
        // 若未超出仍消耗剩余位移，在短输入框上滑动时整页将无法滚动（与 BoundedScroll 口径一致）。
        val overflows = remember(value, textWidthPx, textHeightPx, textStyle) {
            measurer.measure(
                text = AnnotatedString(value),
                style = textStyle,
                constraints = Constraints(maxWidth = textWidthPx)
            ).size.height > textHeightPx
        }
        // BasicTextField 的滚动在它内部，nestedScroll 必须挂在它外层才收得到边界后的余量
        val eater = remember(overflows) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource
                ): Offset = if (overflows) available else Offset.Zero
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(eater)
                .onFocusChanged { focused = it.isFocused },
            textStyle = textStyle,
            cursorBrush = SolidColor(UiColors.accentFg),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Default
            ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(onSurface.copy(alpha = 0.04f), shape)
                        .border(
                            width = 1.dp,
                            color = if (focused) UiColors.accentFg else onSurface.copy(alpha = 0.12f),
                            shape = shape
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 13.sp,
                            color = onSurface.copy(alpha = 0.35f)
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}
