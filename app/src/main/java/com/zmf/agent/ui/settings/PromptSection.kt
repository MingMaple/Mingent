package com.zmf.agent.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.agent.prompt.PromptBuilder
import com.zmf.agent.agent.prompt.PromptInput
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.theme.Motion
import com.zmf.agent.ui.theme.Palette

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

    // 预览始终跟草稿走（不是跟已保存的值走），刚打完字就能看到拼起来的实际样子
    val preview = remember(extraDraft, overrideDraft) {
        PromptBuilder.build(
            PromptInput(extraInstructions = extraDraft, systemOverride = overrideDraft)
        ).orEmpty()
    }
    val previewScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 卡片自带页面边距：调用方不再套一层横向内距，免得两页拼起来宽度不一致
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
                onToggle = { previewOpen = !previewOpen }
            )
            AnimatedVisibility(
                visible = previewOpen,
                enter = expandVertically(Motion.spring()) + fadeIn(Motion.fadeIn()),
                exit = shrinkVertically(Motion.spring()) + fadeOut(Motion.fadeOut())
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp)
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(onSurface.copy(alpha = 0.04f))
                ) {
                    Text(
                        text = preview,
                        modifier = Modifier
                            .verticalScroll(previewScroll)
                            .padding(12.dp),
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
        // 卡片自己裁一刀：内部行的波纹是矩形，不裁会在 22dp 圆角处露方角
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/** 提示词条目头：一行 label + 右侧「▾」，展开时箭头弹簧翻转 180°。 */
@Composable
private fun PromptEntryHeader(label: String, expanded: Boolean, onToggle: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.spring(),
        label = "promptEntryArrow"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 形状交给卡片裁剪（中间行），行内不再自己定圆角，否则和 22dp 卡片对不齐
            .saltRowClickable(RoundedCornerShape(0.dp)) { onToggle() }
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
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
            .padding(start = 14.dp, bottom = 4.dp),
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
 * 自绘输入框：不能用 OutlinedTextField 的默认 focus 边框（Material 蓝），
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
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(fieldHeight)
            .onFocusChanged { focused = it.isFocused },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = onSurface),
        cursorBrush = SolidColor(Palette.AccentGreen),
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
                        color = if (focused) Palette.AccentGreen else onSurface.copy(alpha = 0.12f),
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
