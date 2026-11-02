package com.zmf.agent.feature.chat

import com.zmf.agent.uikit.components.FoldToggle
import com.zmf.agent.uikit.common.blockTapsOnly
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zmf.agent.core.store.PlanDoc
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.infra.system.ShizukuBridge
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.uikit.components.salt.SaltSwitch
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors
import com.zmf.agent.uikit.gesture.blockHorizontalAtEdge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import com.zmf.agent.uikit.markdown.MarkdownText

@Composable
internal fun ChatTopBar(
    viewModel: ChatViewModel,
    onMenuClick: () -> Unit,
    /** 子智能体总数：0 时右上角不出现胶囊（跑完也留着，方便回看对话）。 */
    subAgentCount: Int,
    /** 其中正在运行的数量，只用来决定指示灯颜色。 */
    subAgentRunning: Int,
    onSubAgentClick: () -> Unit,
    /** 当前会话已产出的计划：一份都没有时右上角那枚按钮连位置都不留。 */
    plans: List<PlanDoc>,
    onPlanClick: () -> Unit,
    /** 内置浏览器里有没有活着的页面：有才露出那枚网页图标。 */
    browserOpen: Boolean,
    onBrowserClick: () -> Unit
) {
    // 状态点四态：请求中蓝 / 出错红 / 就绪青 / 空闲绿
    val shizukuState by ShizukuBridge.state.collectAsState()
    val dotColor = when {
        viewModel.isStreaming -> UiColors.accentFg
        !viewModel.networkAvailable -> MaterialTheme.colorScheme.error
        viewModel.lastRequestOk == false -> MaterialTheme.colorScheme.error
        shizukuState == ShizukuBridge.State.READY -> UiColors.success
        else -> UiColors.success
    }
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "☰",
            modifier = Modifier.clickable(onClick = onMenuClick).padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        // 中间不再放「Agent」标题：两侧 Spacer 撑着，☰ 在左、状态点在右
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.weight(1f))
        // 子智能体胶囊：只要还有记录就留着，点了展开卡片（状态点保持最右）
        if (subAgentCount > 0) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSubAgentClick() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (subAgentRunning > 0) UiColors.accentFg else UiColors.success,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = subAgentCount.toString() + " 个子智能体",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        // 网页图标：只有存在活着的页面时才出现。它出现在「从浏览页返回主页之后」，
        // 语义是「网页还开着，点我回去」，所以画的是窗口而不是链接
        if (browserOpen) {
            BrowserGlyph(onClick = onBrowserClick)
            Spacer(Modifier.width(10.dp))
        }
        // 计划按钮：只有真的存在计划预览时才出现。没有计划还挂一枚淡色图标，
        // 等于在教用户「这里点不开」，不如让位给状态点
        if (plans.isNotEmpty()) {
            PlanGlyph(onClick = onPlanClick)
            Spacer(Modifier.width(10.dp))
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
    }
}

/**
 * 右上角的「计划」按钮：一张手画的清单图标（外框 + 三条横线）。
 *
 * 为什么用 Canvas 而不是 emoji/文字：这一排只有状态点和一个胶囊，混进一个字符图标会显得字号不齐；
 * 手画的和抽屉里那几个图形是同一套语言。
 */
@Composable
internal fun PlanGlyph(onClick: () -> Unit) {
    // 只在这一份计划真的存在时画，所以永远是「有内容」的绿，不再有淡色的空态
    val color = UiColors.success
    val strokeWidth = with(LocalDensity.current) { 1.5.dp.toPx() }
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(17.dp)) {
            val w = size.width
            val h = size.height
            val corner = CornerRadius(2.5.dp.toPx())
            drawRoundRect(
                color = color,
                topLeft = Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
                size = Size(w - 1.dp.toPx(), h - 1.dp.toPx()),
                cornerRadius = corner,
                style = Stroke(width = strokeWidth)
            )
            // 三条横线：间距 3.5dp，长度递减，读起来像一份条目清单
            for (index in 0 until 3) {
                val y = h * 0.32f + index * 3.6.dp.toPx()
                val right = w - if (index == 2) 5.dp.toPx() else 3.5.dp.toPx()
                drawLine(
                    color = color,
                    start = Offset(3.5.dp.toPx(), y),
                    end = Offset(right, y),
                    strokeWidth = strokeWidth
                )
            }
        }
    }
}

/**
 * 右上角的「网页」图标：一个小窗口（外框 + 顶部工具栏 + 一颗小圆点）。
 *
 * 只在浏览器里真有页面时出现。用户从浏览页返回主页之后，这枚图标就是「网页还开着，点我回去」
 * 的唯一入口，所以画成窗口而不是链接符号；和 PlanGlyph 同一套线宽，排在一行里不打架。
 */
@Composable
internal fun BrowserGlyph(onClick: () -> Unit) {
    val color = Palette.Neutral
    val strokeWidth = with(LocalDensity.current) { 1.5.dp.toPx() }
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(17.dp)) {
            val w = size.width
            val h = size.height
            val half = 0.5.dp.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(half, half),
                size = Size(w - half * 2, h - half * 2),
                cornerRadius = CornerRadius(2.5.dp.toPx()),
                style = Stroke(width = strokeWidth)
            )
            // 工具栏分隔线：偏上一点，下面留出「网页内容」的面积
            val barY = h * 0.34f
            drawLine(
                color = color,
                start = Offset(half, barY),
                end = Offset(w - half, barY),
                strokeWidth = strokeWidth
            )
            // 工具栏左侧那颗小圆点（标签页的示意），别画多了：这么小的地方只放得下一颗
            drawCircle(color = color, radius = 1.dp.toPx(), center = Offset(w * 0.2f, barY * 0.52f))
        }
    }
}

/** 子任务面板最多显示几条。 */
private const val SUB_AGENT_PANEL_ROWS = 5

/**
 * 右上角「子智能体」下拉卡片：只读 [SubAgentRegistry] 的快照，和 ViewModel 无关。
 *
 * 每条：描述 + 状态；点一条进详情页（对话与续聊都在那里）。
 * 卡片自己也接点击，避免点空白处透到底下「点外部收起」的遮罩上。
 */
@Composable
internal fun SubAgentPanel(
    entries: List<SubAgentRegistry.Entry>,
    onOpen: (String) -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 新的在前，最多 5 条
    val shown = entries.takeLast(SUB_AGENT_PANEL_ROWS).asReversed()
    Surface(
        modifier = modifier
            .widthIn(min = 200.dp, max = 280.dp)
            .pointerInput(Unit) { detectTapGestures { } },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, if (isDark) Palette.DarkStroke else Palette.LightStroke)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            if (shown.isEmpty()) {
                Text(
                    text = "暂无子任务",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
            }
            shown.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onOpen(entry.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.description,
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = onSurface.copy(alpha = 0.85f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = subAgentStateText(entry.state),
                        fontSize = 11.sp,
                        color = subAgentStateColor(entry.state)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "›",
                        fontSize = 13.sp,
                        color = onSurface.copy(alpha = 0.35f)
                    )
                }
            }
            // 底部操作：没有已完成的时候位置也留着，只是点不动（选项不跳位置）
            val hasFinished = entries.any { it.state != SubAgentRegistry.RUNNING }
            Text(
                text = "清空已完成",
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (hasFinished) 1f else 0.35f)
                    .clickable(enabled = hasFinished, onClick = onClearFinished)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 11.sp,
                color = onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

/** 子智能体状态的显示文案：运行中 / 完成 / 失败。 */
internal fun subAgentStateText(state: String): String = when (state) {
    SubAgentRegistry.RUNNING -> "运行中"
    SubAgentRegistry.DONE -> "完成"
    else -> "失败"
}

/**
 * 面板里的状态文字颜色：运行蓝 / 完成中性 / 失败红。
 *
 * 只有「异常」和「进行中」值得上色；「完成」是常态，给中性即可——
 * 绿色留给 6–7dp 的状态点，11sp 的字配绿色读不清。
 */
@Composable
internal fun subAgentStateColor(state: String): Color = when (state) {
    SubAgentRegistry.RUNNING -> UiColors.accentFg
    SubAgentRegistry.DONE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    else -> MaterialTheme.colorScheme.error
}

/** 极简弹窗：没有标题、没有按钮，只有可点的行；点外部或返回键关闭。 */
@Composable
internal fun MinimalDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                content = content
            )
        }
    }
}

/** 极简名字输入框（通用）：一行输入 + ✓ 保存 / × 取消，点外部取消。 */
@Composable
internal fun NameInputDialog(
    placeholder: String,
    initial: String = "",
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(initial) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    MinimalDialog(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text(placeholder, fontSize = 15.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                maxLines = 1,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(value.text) })
            )
            Text(
                text = "✓",
                modifier = Modifier.clickable { onSave(value.text) }.padding(horizontal = 8.dp),
                fontSize = 20.sp,
                color = UiColors.accentFg
            )
            Text(
                text = "×",
                modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 6.dp),
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

/**
 * 体积文案：按量级给 KB / MB / GB，MB 以上取整：这里只是让人判断「这包大不大」，
 * 精确到小数点反而读不出差别（同 SentFileCard 的口径，但那一处是单个附件，保留一位小数）。
 */
internal fun exportSizeText(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L ->
        "%.1f".format(Locale.US, bytes / 1024.0 / 1024.0 / 1024.0) + "GB"
    bytes >= 1024L * 1024L -> ((bytes + 1024L * 1024L / 2L) / (1024L * 1024L)).toString() + "MB"
    bytes >= 1024L -> ((bytes + 512L) / 1024L).toString() + "KB"
    else -> bytes.toString() + "B"
}

/** 导出选项的一行：左边标题+一句提示，右边 Salt 风开关。 */
@Composable
internal fun BackupToggleRow(
    title: String,
    hint: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
        SaltSwitch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** 待发送缩略图统一 64dp 见方：和文件 chip 一样高，同一行里不会一高一矮。 */
internal val PendingThumbSide = 64.dp

/** 待发送的一件东西：文件和图片排在同一个行里，所以得有一个统一的表现。 */
internal data class PendingEntry(
    val pending: PendingFile,
    val isImage: Boolean,
    /** 在它自己那条队列里的下标：删除走 detachFile / detachImage，按这个口径定位。 */
    val index: Int,
    /** 文件落进工作区的时刻，用来还原「文件与图片谁先加的」。 */
    val stamp: Long
)

/**
 * 把分开存的两条队列拼成一条「按添加顺序」的列表。
 *
 * ViewModel 里 attachments 与 pendingImages 是两个列表，谁先谁后没有记录；
 * 好在两者都是「选完立刻复制/压图进工作区」，所以文件系统的 lastModified 就是添加时刻
 * （图片名里也带这串毫秒，口径一致）。同一时刻（粗粒度文件系统只精确到秒）按「文件在前」兜底，
 * 保证同样的输入每次排出来同一样。
 */
internal fun mergePending(
    root: File,
    files: List<PendingFile>,
    images: List<PendingFile>
): List<PendingEntry> {
    val out = ArrayList<PendingEntry>(files.size + images.size)
    files.forEachIndexed { i, p -> out.add(PendingEntry(p, false, i, stampOf(root, p))) }
    images.forEachIndexed { i, p -> out.add(PendingEntry(p, true, i, stampOf(root, p))) }
    out.sortWith(compareBy({ it.stamp }, { if (it.isImage) 1 else 0 }))
    return out
}

/** 取不到（文件已被清掉）就当成最早，至少不会把这一项吞掉。 */
internal fun stampOf(root: File, pending: PendingFile): Long =
    runCatching { File(root, pending.relPath).lastModified() }.getOrDefault(0L)

/**
 * 待发送图片的缩略图：固定 64dp 见方、圆角 12dp。
 *
 * 尺寸写死而不是按原图比例：这一行里还混着文件 chip，比例尺寸会让整行高矮不齐；
 * 也不再铺任何白色/半透明白底：之前那层底把手边的文件 chip 盖住，看着像两个层次。
 * 解码只在 bitmaps 为空（文件没了 / 不是图片）时才画一块占位灰，仍能 ✕ 删掉。
 */
@Composable
internal fun PendingImageThumb(
    relPath: String,
    root: File,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val bitmap = remember(relPath) {
        runCatching { BitmapFactory.decodeFile(File(root, relPath).absolutePath) }.getOrNull()
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(modifier = Modifier.size(PendingThumbSide)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(12.dp))
                .then(if (bitmap == null) Modifier.background(onSurface.copy(alpha = 0.12f)) else Modifier)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(text = "图片", fontSize = 12.sp, color = onSurface.copy(alpha = 0.5f))
            }
        }
        // 删除键压在右上角：底色只是一层深色半透明，够看清又不抢图片
        Text(
            text = "✕",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onRemove)
                .padding(horizontal = 4.dp, vertical = 1.dp),
            fontSize = 10.sp,
            color = Palette.OnAccent
        )
    }
}

/** 待发送的文件 chip：名字 + 角上的 ✕，和图片缩略图同一行排。 */
@Composable
internal fun PendingFileChip(name: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            modifier = Modifier.widthIn(max = 140.dp),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "✕",
            modifier = Modifier
                .clickable(onClick = onRemove)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

/** 工作区页（一级页面）：未分类 + 自建工作区的列表，可切换 / 新建 / 删除；streaming 时全部点不动。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkSpacePage(
    workspaces: List<WorkSpace>,
    currentId: String?,
    streaming: Boolean,
    onPick: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<WorkSpace?>(null) }

    Surface(
        // 工作区页：铺满不透明，最外层吞点击，避免穿透到下面的聊天界面
        modifier = Modifier
            .fillMaxSize()
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "←",
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "工作区",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .alpha(if (streaming) 0.4f else 1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // 「未分类」不是 WorkSpace，它是 workspaceId == null 的状态：永远排第一，不能删
                WorkSpaceRow(
                    title = "未分类",
                    selected = currentId == null,
                    deletable = false,
                    enabled = !streaming,
                    onPick = { onPick(null) },
                    onAskDelete = {}
                )
                workspaces.forEach { space ->
                    WorkSpaceRow(
                        title = space.name,
                        selected = space.id == currentId,
                        // 默认工作区的目录是全体历史文件的根，不允许删
                        deletable = space.id != WorkSpaceStore.DEFAULT_ID,
                        enabled = !streaming,
                        onPick = { onPick(space.id) },
                        onAskDelete = { pendingDelete = space }
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            HorizontalDivider(color = onSurface.copy(alpha = 0.08f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !streaming) { showCreate = true }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "＋ 新建工作区",
                    fontSize = 15.sp,
                    color = UiColors.accentFg.copy(alpha = if (streaming) 0.35f else 1f)
                )
            }
        }
    }

    if (showCreate) {
        NameInputDialog(
            placeholder = "工作区名称",
            onDismiss = { showCreate = false },
            onSave = { name ->
                if (name.isNotBlank()) {
                    onCreate(name.trim())
                    showCreate = false
                }
            }
        )
    }

    pendingDelete?.let { space ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除工作区", fontSize = 17.sp) },
            text = {
                Text(
                    text = "删掉后这个工作区里的对话会进入「未分类」，目录会移到回收站（不真删）。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(space.id)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 工作区列表的一行：名字 + 当前高亮；非默认工作区右侧带「×」。 */
@Composable
internal fun WorkSpaceRow(
    title: String,
    selected: Boolean,
    deletable: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
    onAskDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onPick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (deletable) {
            Text(
                text = "×",
                modifier = Modifier
                    .clickable(enabled = enabled, onClick = onAskDelete)
                    .padding(start = 12.dp, end = 2.dp),
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
        }
    }
}

/**
 * 列表项：用户消息单独一行；一个助手回合 = 到下一条提问之前的全部内容。
 *
 * [UserItem.roundsFromEnd] 是这条提问距离最新一轮的轮次差（最新一条为 0）：
 * 气泡折叠阈值按它分两档，比每次都从后往前数一遍消息省事，也能在列表复用后保持稳定。
 */
internal sealed interface ChatItem {
    data class UserItem(
        val index: Int,
        val message: ChatMessage,
        val roundsFromEnd: Int = 0
    ) : ChatItem

    data class AssistantItem(val indices: List<Int>, val userIndex: Int) : ChatItem
}

internal fun buildChatItems(all: List<ChatMessage>): List<ChatItem> {
    val out = ArrayList<ChatItem>()
    var i = 0
    var lastUserIndex = -1
    // 先数一遍总轮数：轮次差 = 总数 - 当前序号 - 1，单次遍历即可，不必每条消息再扫一次
    val totalRounds = all.count { it.role == "user" }
    var roundOrdinal = 0
    while (i < all.size) {
        when (all[i].role) {
            "user" -> {
                lastUserIndex = i
                out.add(ChatItem.UserItem(i, all[i], totalRounds - roundOrdinal - 1))
                roundOrdinal++
                i++
            }

            "assistant" -> {
                val indices = ArrayList<Int>()
                while (i < all.size && all[i].role != "user") {
                    if (all[i].role == "assistant") indices.add(i)
                    i++
                }
                if (indices.isNotEmpty()) out.add(ChatItem.AssistantItem(indices, lastUserIndex))
            }

            else -> i++
        }
    }
    return out
}

/** 已发送图片缩略图的边长：恒定值，不管原图多大、长宽比多少，发出去都占同样大的一块。 */
internal val SentThumbSide = 96.dp

/** 缩略图与文件卡共用的圆角。 */
internal val SentCardShape = RoundedCornerShape(14.dp)

/** 文件卡的固定宽度：名字再长也不撑宽，超出用省略号。 */
internal val SentFileCardWidth = 168.dp

/**
 * 一条用户消息 = 竖排两块：上面是附件卡行（文件卡 + 图片缩略图，超出横滑），下面是文字气泡。
 *
 * 卡片在前、文字在后，是照 DeepSeek 手机端的顺序：图片和文件是「随这条消息递过去的东西」，
 * 不是气泡里的内容。没有字就整块不画气泡：纯图片消息不再留一个空白气泡。
 */
@Composable
internal fun UserRow(
    message: ChatMessage,
    // 距离最新一轮的轮次差：0 = 最新一条提问。折叠阈值按它分两档
    roundsFromEnd: Int,
    imageRoot: File,
    onCopy: (String) -> Unit,
    onSelectText: (String) -> Unit,
    onRollback: () -> Unit,
    onImageClick: (List<String>, Int) -> Unit,
    onImageLongPress: (String, Offset) -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bubbleColor = if (isDark) Palette.DarkSurfaceHigh else Palette.LightSurfaceHigh
    val text = userBubbleText(message)
    // 折叠口径：近三轮完全铺开，刚发出去的消息正是用户在读的那条，不该被收起来；
    // 更早的历史按屏幕 45% 折。折叠点落在整段末尾（由 MarkdownText 按块取整），
    // 不会出现「最后一行只剩半个字」。展开态只活在这一屏，列表滚走再回来回到折叠态。
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val foldAt = if (roundsFromEnd < 3) Dp.Unspecified else screenHeight * 0.45f
    // 折叠后最多约九行（15sp × 1.5 行高）：够看清开头，也不至于只剩一条缝
    val foldedHeight = with(LocalDensity.current) { (15f * 1.5f * 9f).sp.toDp() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // 附件从正文那段说明反向解析：发送时「附件在前、图片在后」拼进 prompt，这里保持同一顺序
    val files = remember(message.content) { attachmentPaths(message) }
    val images = message.images
    val bubbleShape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 6.dp
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier.widthIn(max = 340.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (files.isNotEmpty() || images.isNotEmpty()) {
                // 同待发那一行：拦截器和横向行共用一份 state，到边之后的余量就地吃掉
                val rowScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .blockHorizontalAtEdge(rowScroll)
                        .horizontalScroll(rowScroll)
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    files.forEach { rel ->
                        SentFileCard(relPath = rel, root = imageRoot)
                    }
                    images.forEachIndexed { index, rel ->
                        SentImageThumb(
                            relPath = rel,
                            root = imageRoot,
                            onTap = { onImageClick(images, index) },
                            onLongPress = { anchor -> onImageLongPress(rel, anchor) }
                        )
                    }
                }
            }
            if (text.isNotEmpty()) {
                if (files.isNotEmpty() || images.isNotEmpty()) Spacer(Modifier.height(8.dp))
                // 动作按钮摆在气泡尾巴上，不再靠长按弹窗：
                // 用户看到那一行图标就知道能做什么，不用先猜「可以长按」
                // 不用 Surface：它的 shape 会挂一层裁剪图层（graphicsLayer + clip），
                // 气泡展开到十几屏高时这层图层的尺寸也涨到几万像素，那之后的子节点
                // 连点击都收不到（27187px 的气泡实测：按钮看得见、点不动）。
                // background(color, shape) 画出来的圆角与 Surface 一致，且不建图层。
                Box(
                    // 展开 / 收起只动气泡高度：正文按整份排版好再逐步露出，不会重新折行
                    modifier = Modifier
                        .animateContentSize(
                        animationSpec = tween(180),
                        alignment = Alignment.TopStart
                    ).background(bubbleColor, bubbleShape)
                ) {
                    // 不在气泡里开竖向滚动：文字正常铺开，阅读交给整页列表。
                    // 超过 foldAt 时才折叠，折叠点取整段末尾（见 MarkdownText 的按块折叠）。
                    MarkdownText(
                        text = text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        // 用户自己按的换行算他的排版：空行要看得见（发送时已压成一个）
                        keepBlankLines = true,
                        foldAt = foldAt,
                        foldedHeight = foldedHeight,
                        expanded = expanded,
                        foldColor = bubbleColor,
                        foldToggle = {
                            FoldToggle(expanded = expanded, onClick = { expanded = !expanded })
                        }
                    )
                }
            }
            // 尾部动作行：右对齐贴着自己那条气泡，时间在最右
            MessageTailRow(
                at = clockText(message.createdAt),
                alignEnd = true,
                icons = listOf(TailIcon.COPY, TailIcon.SELECT_TEXT, TailIcon.ROLLBACK),
                onAction = { kind ->
                    when (kind) {
                        TailIcon.COPY -> onCopy(text)
                        TailIcon.SELECT_TEXT -> onSelectText(text)
                        TailIcon.ROLLBACK -> onRollback()
                        else -> Unit
                    }
                },
                usageAnchor = {}
            )
        }
    }
}

/**
 * user 气泡里要显示的文字：把「附件 / 图片已上传」那两段说明（标记 + 路径行 + 尾巴提示）裁掉。
 *
 * 这两段只写给模型看（模型靠这些相对路径 read_file 读文件），界面用卡片与缩略图表达。
 * 用户自己写的正文永远在两段说明之前，所以只能按标记各切一刀：
 * 从第一个标记一把切到末尾，会把夹在中间的正文一起吞掉：agent 收到了那句话，界面上却一个字没有。
 */
internal fun userBubbleText(message: ChatMessage): String {
    var text = message.content
    // 图片段总在附件段之后，先切它不会打乱附件段的起点
    val imageCut = text.indexOf(IMAGE_PATHS_MARKER)
    if (imageCut >= 0) text = text.substring(0, imageCut)
    val fileCut = text.indexOf(ATTACHMENT_PATHS_MARKER)
    if (fileCut >= 0) text = text.substring(0, fileCut)
    // 批准执行时拼进去的计划正文：只给模型看，气泡里留一句「同意执行了哪一份」就够
    val planCut = text.indexOf(PLAN_DETAIL_MARKER)
    if (planCut >= 0) text = text.substring(0, planCut)
    return text.trimEnd()
}


/**
 * 这条消息带上的普通附件（工作区相对路径）。
 *
 * 正文里只留了「标记 + 一连串 - 路径」这段说明，界面要按它画文件卡，所以反向解析回来；
 * 解析口径与 ChatViewModel.attachmentPathsIn 一致（同一个标记、同一种行格式）。
 */
internal fun attachmentPaths(message: ChatMessage): List<String> {
    val start = message.content.indexOf(ATTACHMENT_PATHS_MARKER)
    if (start < 0) return emptyList()
    return message.content.substring(start + ATTACHMENT_PATHS_MARKER.length)
        .lines()
        .dropWhile { it.isBlank() }
        .takeWhile { it.startsWith("- ") }
        .map { it.removePrefix("- ").trim() }
        .filter { it.isNotEmpty() }
}

/**
 * 已发送的文件卡：固定宽度，图标 + 文件名单行省略 + 类型/大小副行。
 *
 * 大小取盘上的真实字节数（文件已经在工作区里），读不到就只写类型，不显示「0 B」：
 * 用户清理过附件之后，那张卡还在老会话里，写 0 B 会像「文件坏了」。
 */
@Composable
internal fun SentFileCard(relPath: String, root: File) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val name = relPath.substringAfterLast('/')
    val sizeText = remember(relPath, root) {
        val bytes = runCatching { File(root, relPath).length() }.getOrDefault(0L)
        when {
            bytes <= 0L -> ""
            bytes >= 1024L * 1024L -> "%.1f".format(Locale.US, bytes / 1024.0 / 1024.0) + " MB"
            else -> ((bytes + 1023L) / 1024L).toString() + " KB"
        }
    }
    val kind = name.substringAfterLast('.', "").uppercase(Locale.US).ifEmpty { "文件" }
    val subText = if (sizeText.isEmpty()) kind else kind + " · " + sizeText
    Column(
        modifier = Modifier
            .width(SentFileCardWidth)
            .clip(SentCardShape)
            .background(onSurface.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        FileGlyph(tint = onSurface.copy(alpha = 0.55f))
        Spacer(Modifier.height(10.dp))
        Text(
            text = name,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = onSurface.copy(alpha = 0.5f)
        )
    }
}

/** 文件图标：一张纸 + 三行正文。工程里没有 material-icons 依赖，所以手画（同小窗那套做法）。 */
@Composable
internal fun FileGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(width = 20.dp, height = 24.dp)) {
        val paperCorner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        drawRoundRect(color = tint.copy(alpha = 0.18f), cornerRadius = paperCorner)
        val lineHeight = 1.6.dp.toPx()
        val left = size.width * 0.24f
        val right = size.width * 0.76f
        listOf(0.32f, 0.52f, 0.72f).forEachIndexed { index, ratio ->
            drawRoundRect(
                color = tint,
                topLeft = Offset(left, size.height * ratio),
                // 最后一行短一截，纸片才有「一页写完」的样子
                size = Size(if (index == 2) (right - left) * 0.62f else right - left, lineHeight),
                cornerRadius = CornerRadius(lineHeight / 2, lineHeight / 2)
            )
        }
    }
}

/**
 * 已发送的图片缩略图：边长恒定（[SentThumbSide]）、crop 填充，点开详情、长按弹菜单。
 *
 * 尺寸恒定是关键：按原图比例铺开时，一条消息里竖图和横图能差出半屏高，看着像两种东西。
 * 手势走同一个 pointerInput（见 [pressOrTap]）：拆成 clickable + 长按两个修饰符时，
 * 长按弹出菜单后手指一松，clickable 还会照常点一次，直接跳进详情页。
 */
@Composable
internal fun SentImageThumb(
    relPath: String,
    root: File,
    onTap: () -> Unit,
    onLongPress: (Offset) -> Unit
) {
    val bitmap = remember(relPath) {
        runCatching { BitmapFactory.decodeFile(File(root, relPath).absolutePath) }.getOrNull()
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    // pointerInput(relPath) 不会因为重组而重启，回调必须走 rememberUpdatedState 才是最新的那一个
    val currentTap by rememberUpdatedState(onTap)
    val currentLongPress by rememberUpdatedState(onLongPress)
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .size(SentThumbSide)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(SentCardShape)
            .then(if (bitmap == null) Modifier.background(onSurface.copy(alpha = 0.12f)) else Modifier)
            .pointerInput(relPath) {
                // 锚点算成屏幕坐标（origin + 本节点内的落点），菜单才会弹在被按住的那一张旁边
                pressOrTap(
                    onTap = { currentTap() },
                    onLongPress = { local -> currentLongPress(origin + local) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(text = "图片", fontSize = 12.sp, color = onSurface.copy(alpha = 0.5f))
        }
    }
}

/**
 * 一个节点的「点击 / 长按」手势，两者只在同一个 pointerInput 里判。
 *
 * 长按成立后把这次手势剩下的部分吃掉（直到抬手），所以松手不会再算一次点击；
 * 手指滑出阈值则两边都不触发，把滚动让给列表和横向卡片行。
 */
internal suspend fun PointerInputScope.pressOrTap(
    onTap: () -> Unit,
    onLongPress: (Offset) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        val slop = viewConfiguration.touchSlop
        var dragged = false
        // 长按超时内盯着这次手势：抬手、滑走、第二根手指落下都判成「不是长按」
        val expired = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: return@withTimeoutOrNull false
                if (!change.pressed) return@withTimeoutOrNull false
                if (event.changes.count { it.pressed } > 1) return@withTimeoutOrNull false
                if (abs(change.position.x - start.x) > slop ||
                    abs(change.position.y - start.y) > slop
                ) {
                    dragged = true
                    return@withTimeoutOrNull false
                }
            }
            false
        }
        when {
            // 超时那一刻还没抬手 = 长按：吃掉后续事件，抬手不再触发点击
            expired == null -> {
                onLongPress(start)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                }
            }
            // 没滑走就抬手 = 点击
            !dragged -> onTap()
        }
    }
}

/**
 * 一个助手回合：思考窗口、正文气泡、工具卡按「真实到达顺序」混排，轮末折成一行计数。
 *
 * 这一层只负责三件事：把回合摊平（[buildTurnSegments]）、挂长按菜单、处理折叠状态；
 * 具体怎么画全部交给 [TurnStream]。摊平与折叠的口径见 TurnStream.kt 顶部那段说明。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantRow(
    all: List<ChatMessage>,
    indices: List<Int>,
    streaming: Boolean,
    toolOutcomes: Map<String, Boolean>,
    /** 复制本轮全部正文（用户在气泡里能看全的那些文字）。 */
    onCopy: (String) -> Unit,
    /** 选择文本：正文与思考一起交出去，选择页里再分两段。 */
    onSelectText: (String, String) -> Unit,
    /** 以这一轮为终点开一个新会话（分支）。 */
    onFork: (Int) -> Unit,
    /** 打开「本轮用量」伴随面板，第二个参数是那枚按钮在窗口里的位置。 */
    onUsage: (TurnUsageInfo, Offset) -> Unit
) {
    // 一轮按「到达顺序」摊平：每条消息里的思考 → 正文 → 它的工具调用，接着下一条。
    // 旧实现把所有思考拼成一整段提到最上面、把工具卡全塞进同一个气泡，界面顺序跟真实
    // 发生顺序对不上；摊平之后界面顺序＝发生顺序：思考 → 工具 → 思考 → 工具 → 正文。
    val segments = buildTurnSegments(all, indices)
    // 长按菜单要的是「这一轮用户能挑走的文字」，所以正文与思考仍各自整轮拼接
    val wholeText = indices.mapNotNull { all.getOrNull(it)?.content?.takeIf { c -> c.isNotBlank() } }
        .joinToString("\n\n")
    val reasoning = indices.joinToString("") { all.getOrNull(it)?.reasoningContent.orEmpty() }
    val lastIndex = indices.lastOrNull()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bubbleColor = if (isDark) Palette.DarkSurface else Palette.LightSurface
    val thinkColor = if (isDark) Palette.BubbleThinkDark else Palette.BubbleThinkLight
    val cardColor = if (isDark) Palette.DarkSurfaceHigh else Palette.LightSurfaceHigh
    val menuIndex = indices.firstOrNull() ?: 0
    // 本轮用量：只有最后一条 assistant 带数字（ChatViewModel.writeTurnUsage 只写那一条），
    // 所以扫一遍取那个非零的；流式途中还没写，这里就是 null，用量按钮不会闪出来。
    var turnUsage: TurnUsageInfo? = null
    // 时间默认取本轮最后一条消息的时间。以前从 0 起算：定时任务产出的答复没有用量数字，
    // 0 经「HH:mm」格式化正好是东八区的 08:00（纪元零点），看起来像时间停在早上八点。
    var tokensAt = all.getOrNull(indices.lastOrNull() ?: -1)?.createdAt ?: 0L
    for (index in indices) {
        val message = all.getOrNull(index) ?: continue
        if (message.promptTokens > 0 || message.completionTokens > 0) {
            turnUsage = turnUsageOf(message)
            tokensAt = message.createdAt
        }
    }

    // 「最终回答」与「过程」的分界：
    // 最终回答 = 本轮的**最后一条** assistant 消息，且必须同时满足
    //   ① 有回复内容（正文或思考非空）② 不含任何工具调用 ③ 是最后一步。
    // 任一不满足 ⇒ 这一轮**没有最终回答**：整轮都算过程，折叠后只剩那行计数
    // （此时过程起点直接取本轮起点）。
    // 注意这条口径的后果：过程里有没有文字，完全取决于模型自己说不说，
    // 不靠提示词逼它「每步先说一句」。
    val answerIndex = indices.lastOrNull()?.takeIf { index ->
        val message = all.getOrNull(index)
        message != null &&
            message.role == "assistant" &&
            message.toolCalls.isEmpty() &&
            (message.content.isNotBlank() || !message.reasoningContent.isNullOrBlank())
    }
    val finalSegment = if (answerIndex == null) {
        null
    } else {
        all.getOrNull(answerIndex)?.content?.takeIf { it.isNotBlank() }?.let {
            TurnSegment.Body(answerIndex, it)
        }
    }
    // 过程 = 除「最终回答那段正文」以外的全部片段；最终回答那条消息的**思考**留在过程里
    // （折叠收起时藏、展开时看得到），这样展开后仍能看到收口时想了什么。
    val processSegments = if (answerIndex == null) {
        segments
    } else {
        segments.filterNot { it is TurnSegment.Body && it.messageIndex == answerIndex }
    }
    val thinkCount = processSegments.count { it is TurnSegment.Think }
    val callCount = processSegments.count { it is TurnSegment.Call }
    val hasProcess = thinkCount > 0 || callCount > 0
    // 折叠态只在「这一轮真的跑完」之后才成立：流式中途折起来，用户就看不见正在做的事了。
    //
    // 判据是上游算好的 [streaming]（= 这一轮还在流式，或这一轮结束后紧接着还要续跑）。
    // 为什么不能只看「是否正在流式」：一轮结束到下一段开始之间有一个真实的空档——
    // 续跑（目标模式）是在 finally 里落盘、刷新状态、发通知之后才起的，中间几百毫秒。
    // 若那一瞬就折起来，下一段内容一到又展开，用户看到的就是「思考/工具中偶发折叠又被打开」。
    // 那个空档由 SendController 的 chainPending 如实报出来，不靠猜时间。
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(streaming) { if (!streaming) expanded = false }
    val collapsed = hasProcess && !streaming && !expanded
    // 折叠时只留最终回答；本轮没有最终回答（模型以工具调用收尾 / 一句话没说）时什么都不留，
    // 只剩上面那行计数。流式途中（含续跑交接的空档）不折，看得见正在做什么。
    val shown = if (collapsed) listOfNotNull(finalSegment) else segments
    // 展开态：过程装进底板，最终回复留在底板外面
    val showPanel = expanded && hasProcess && !streaming

    // 动作按钮在气泡尾巴上（见下方），这一整轮不再挂长按手势：
    // 长按不消费事件，工具卡照常点击这一点本来就只是为了给弹窗让路
    Column(modifier = Modifier.fillMaxWidth()) {
        if (hasProcess && !streaming) {
            TurnProcessSummary(
                thinkCount = thinkCount,
                callCount = callCount,
                expanded = expanded,
                onToggle = { expanded = !expanded }
            )
        }
        if (showPanel) {
            // 过程区：浅灰底板 + 内缩，读作「打开的一堆底层日志」
            if (processSegments.isNotEmpty()) {
                TurnProcessPanel {
                    TurnStream(
                        segments = processSegments,
                        all = all,
                        streaming = false,
                        lastMessageIndex = lastIndex,
                        toolOutcomes = toolOutcomes,
                        thinkColor = thinkColor,
                        bubbleColor = bubbleColor,
                        cardColor = cardColor,
                        inPanel = true
                    )
                }
            }
            // 最终区：底板之外、之下，正常聊天气泡，视觉权重最高
            if (finalSegment != null) {
                if (processSegments.isNotEmpty()) Spacer(Modifier.height(10.dp))
                TurnStream(
                    segments = listOf(finalSegment),
                    all = all,
                    streaming = false,
                    lastMessageIndex = lastIndex,
                    toolOutcomes = toolOutcomes,
                    thinkColor = thinkColor,
                    bubbleColor = bubbleColor,
                    cardColor = cardColor
                )
            }
        } else {
            TurnStream(
                segments = shown,
                all = all,
                streaming = streaming,
                lastMessageIndex = lastIndex,
                toolOutcomes = toolOutcomes,
                thinkColor = thinkColor,
                bubbleColor = bubbleColor,
                cardColor = cardColor
            )
        }
        // 尾部动作行：复制 / 选择文本 / 分支 / 用量 + 时间，跟着这条回复走。
        // 用量的明细仍然点开才看：数字本身不该占着一行。
        if (!streaming && (turnUsage != null || wholeText.isNotEmpty())) {
            MessageTailRow(
                at = clockText(tokensAt),
                alignEnd = false,
                icons = buildList {
                    add(TailIcon.COPY)
                    add(TailIcon.SELECT_TEXT)
                    add(TailIcon.FORK)
                    if (turnUsage != null) add(TailIcon.USAGE)
                },
                onAction = { kind ->
                    when (kind) {
                        TailIcon.COPY -> onCopy(wholeText)
                        TailIcon.SELECT_TEXT -> onSelectText(wholeText, reasoning)
                        // 分支保留**这一轮整轮**及其以上：边界取本轮最后一条消息的下标 +1。
                        // 以前传的是本轮第一条的下标 +1，正好把这一轮的其余消息（工具调用那几条）
                        // 切掉，看起来就是「分支之后内容不全」（用户 10:06 报的回归）
                        TailIcon.FORK -> onFork((lastIndex ?: menuIndex) + 1)
                        else -> Unit
                    }
                },
                usageAnchor = { anchor ->
                    turnUsage?.let { usage -> onUsage(usage, anchor) }
                }
            )
        }
    }
}

/**
 * 从 Compose 拿到的 Context 往上剥 ContextWrapper，找到真正的 Activity。
 *
 * LocalContext 通常是被包装过的 Context，不能直接 as Activity；拿不到就返回 null，
 * 调用方退回「发 HOME Intent 回桌面」的兜底方案。
 */
internal fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 长按图片里的「保存图片」：写盘放 IO，写完回主线程弹一句「已保存」。
 *
 * 保存的是被长按的那一张（[relPath] 由手势回调带上来），不是整条消息里的全部图片。
 */
internal fun saveImageFromChat(context: Context, scope: CoroutineScope, root: File, relPath: String) {
    scope.launch {
        val ok = withContext(Dispatchers.IO) { saveImageToDownload(context, root, relPath) }
        Toast.makeText(context, if (ok) "已保存" else "保存失败", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 把工作区里的一张图存进系统「下载」目录，文件名沿用工作区里那一份（也就是用户手机里的原名）。
 *
 * API 29 起走 MediaStore + RELATIVE_PATH：不需要任何存储权限，也不会在相册里多挂一份；
 * 26–28 没有 Downloads 这个集合，只能直接写公共目录（依赖清单里已声明的 WRITE_EXTERNAL_STORAGE），
 * 没有授权 / 目录不可写就返回 false，由调用方提示「保存失败」。
 */
internal fun saveImageToDownload(context: Context, root: File, relPath: String): Boolean {
    val source = File(root, relPath)
    if (!source.isFile) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // 29+ 首选 MediaStore（不用存储权限）；本机 targetSdk 还是 28，个别机型在旧存储模式下
        // 会把它拦掉，那就退回直接写公共 Download 目录，别白丢一次保存
        if (saveImageViaMediaStore(context, source)) return true
    }
    return saveImageToPublicDownload(source)
}

/** 29+：先插一条 Downloads 记录再往里写；IS_PENDING 保证别的 App 不会读到写了一半的文件。 */
internal fun saveImageViaMediaStore(context: Context, source: File): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
        put(MediaStore.MediaColumns.MIME_TYPE, imageMimeOf(source.name))
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }
        .getOrNull() ?: return false
    return try {
        // 打不开输出流就别留一条空记录在 Download 里
        val out = resolver.openOutputStream(uri) ?: run {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
        out.use { sink -> source.inputStream().use { it.copyTo(sink) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        runCatching { resolver.update(uri, values, null, null) }
        true
    } catch (e: Exception) {
        FileLog.log("save", "保存图片失败：" + (e.message ?: e.javaClass.simpleName))
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}

/** 26–28：直接写公共 Download 目录，重名就加「 (n)」，不覆盖用户已有的文件。 */
@Suppress("DEPRECATION")
internal fun saveImageToPublicDownload(source: File): Boolean {
    return runCatching {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val target = uniqueDownloadFile(dir, source.name)
        source.inputStream().use { input -> target.outputStream().use { out -> input.copyTo(out) } }
        true
    }.getOrElse { e ->
        FileLog.log("save", "保存图片失败：" + (e.message ?: e.javaClass.simpleName))
        false
    }
}

/** Download 里已经有同名文件就依次试 name (1).ext、name (2).ext…… */
internal fun uniqueDownloadFile(dir: File, name: String): File {
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var target = File(dir, name)
    var index = 1
    while (target.exists()) {
        target = File(dir, base + " (" + index + ")" + ext)
        index++
    }
    return target
}

/** 存进 Download 时要给 MediaStore 一个类型；认不出来一律按压缩图（工作区里的图都是 JPEG）。 */
internal fun imageMimeOf(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"
        else -> "image/jpeg"
    }

/** 复制到剪贴板。Android 13 起系统自己会弹「已复制」，不再叠加 Toast。 */
internal fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("消息", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }
}

/** 文件选择器「上次目录」的有效期：超过就不再用它，免得一次选择把以后每次打开都困在同一层。 */
private const val PICKER_DIR_TTL_MS = 30L * 60 * 1000L

/** 路径记忆存在 app_prefs 里（跟其它 Store 同一个文件），不单开一份 prefs。 */
private const val PICKER_DIR_PREFS = "app_prefs"
private const val KEY_PICKER_DIR = "picker_last_dir"
private const val KEY_PICKER_DIR_AT = "picker_last_dir_at"
private const val KEY_PICKER_DIR_SESSION = "picker_last_dir_session"

internal fun pickerDirPrefs(context: Context) =
    context.applicationContext.getSharedPreferences(PICKER_DIR_PREFS, Context.MODE_PRIVATE)

/**
 * 这次文稿页该从哪一层打开；null = 不指定，系统自己回到顶层。
 *
 * 三条都要成立才回上次的目录：同一个会话、记过目录、距上次选择不到 30 分钟。
 * 少任何一条都从顶层开始，否则「十天前在别的会话里点进过的深层目录」会一直粘着。
 */
internal fun pickerInitialDir(context: Context, sessionId: String): Uri? {
    val prefs = pickerDirPrefs(context)
    val saved = prefs.getString(KEY_PICKER_DIR, null) ?: return null
    if (prefs.getString(KEY_PICKER_DIR_SESSION, null) != sessionId) return null
    val at = prefs.getLong(KEY_PICKER_DIR_AT, 0L)
    if (System.currentTimeMillis() - at > PICKER_DIR_TTL_MS) return null
    return runCatching { Uri.parse(saved) }.getOrNull()
}

/** 记下这次选的文件所在的目录；推不出目录（非标准 provider）就什么都不记，宁可不记忆也不记一个错的。 */
internal fun rememberPickerDir(context: Context, uri: Uri, sessionId: String) {
    val dir = parentDirUri(uri) ?: return
    pickerDirPrefs(context).edit()
        .putString(KEY_PICKER_DIR, dir.toString())
        .putLong(KEY_PICKER_DIR_AT, System.currentTimeMillis())
        .putString(KEY_PICKER_DIR_SESSION, sessionId)
        .apply()
}

/**
 * 文件的父目录 URI。
 *
 * 选择器返回的是**文件** URI，而 EXTRA_INITIAL_URI 要的是**目录**，所以按 documentId
 * （形如 "primary:Download/微信/图.jpg"）去掉最后一段反推父目录；不在标准 provider
 * 口径里的（没有「卷:路径」形状）返回 null，调用方据此退化成「不记忆」。
 */
internal fun parentDirUri(uri: Uri): Uri? {
    val authority = uri.authority ?: return null
    val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
    val colon = docId.indexOf(':')
    if (colon < 0) return null
    val path = docId.substring(colon + 1)
    // 已经在卷根上：没有「上一层目录」可记
    if (!path.contains('/')) return null
    val parentId = docId.substring(0, colon + 1) + path.substringBeforeLast('/')
    return runCatching { DocumentsContract.buildDocumentUri(authority, parentId) }.getOrNull()
}

/** 视口是否已经贴在列表底部（底部 200dp 容差由调用方给）：决定键盘弹出时要不要跟着抬高。 */
internal fun LazyListState.isNearBottom(tolerancePx: Int): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    if (last.index < info.totalItemsCount - 1) return false
    return last.offset + last.size - info.viewportEndOffset <= tolerancePx
}

/** 自动贴底：最后一条消息变高时，把它底部对齐到视口底部。 */
internal suspend fun followBottom(state: LazyListState, lastIndex: Int) {
    if (lastIndex < 0) return
    val info = state.layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex }
    if (info == null) {
        state.scrollToItem(lastIndex)
        // scrollToItem 只把这一项的「顶部」对齐视口顶部。历史最后一条往往是一整轮助手回复
        // （思考 + 工具卡 + 正文，比屏幕高得多），所以进 App 会停在「思考了 N 秒」那一行。
        // 这里再补一次：把它底部对齐视口底部。
        val landed = state.layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex }
        if (landed != null) {
            val bottomOverflow = landed.offset + landed.size - state.layoutInfo.viewportEndOffset
            if (bottomOverflow > 0) state.scroll { scrollBy(bottomOverflow.toFloat()) }
        }
        return
    }
    val overflow = info.offset + info.size - state.layoutInfo.viewportEndOffset
    if (overflow > 0) state.scroll { scrollBy(overflow.toFloat()) }
}

/**
 * 键盘把视口顶矮之后，把最后一条消息的底部对齐视口底部（跟 [followBottom] 同一口径，只是走动画）。
 *
 * 全程只有一段动画：先量出「底部还差多少」，再一次性滚过去。
 * 原来的 animateScrollToItem(顶部对齐) + animateScrollBy(补差) 是两段，
 * 中间那一帧会停在最近一轮回复气泡的头部（「思考了 N 秒」那一行），
 * 用户看到的就是「先翻到思考、再翻回底部」。
 */
internal suspend fun settleToBottom(state: LazyListState, lastIndex: Int) {
    if (lastIndex < 0) return
    val info = state.layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex }
    if (info == null) {
        // 最后一条整个掉出视口（视口一下矮了半屏以上）：只能先把它带进来，
        // 这种情形本来也没有「气泡头部闪一下」可看
        state.animateScrollToItem(lastIndex)
        return
    }
    val overflow = info.offset + info.size - state.layoutInfo.viewportEndOffset
    if (overflow > 0) state.animateScrollBy(overflow.toFloat(), Motion.spring())
}

/** 气泡右下角的时间只到分钟。复用一个实例：流式输出时每条气泡都在重组，别每次都 new。 */
internal val usageClock = SimpleDateFormat("HH:mm", Locale.US)

internal fun clockText(at: Long): String = usageClock.format(Date(at))

/**
 * 一条消息的用量 → 「本轮用量」面板要的形状。
 *
 * 兼容三种落盘：①本次改造之后（未缓存 / 缓存写入 / 推理都已经存下）；②只有「输入 / 输出 / 缓存命中」
 * 的旧版；③连缓存都没有的更老会话。后两种按「没报缓存 = 整段输入都算未缓存」补，
 * 面板不至于给出一行「未缓存输入 0 tok」这种明显错的数。
 */
internal fun turnUsageOf(message: ChatMessage): TurnUsageInfo {
    val reported = message.usageCacheReported
    val uncached = when {
        message.uncachedInputTokens > 0 -> message.uncachedInputTokens
        reported -> (message.promptTokens - message.cachedTokens).coerceAtLeast(0)
        else -> message.promptTokens
    }
    return TurnUsageInfo(
        uncachedInputTokens = uncached,
        cacheReadTokens = if (reported) message.cachedTokens else 0,
        cacheReadReported = reported,
        // 服务商没有「缓存写入」这一项（含所有 v0.9.5 之前的会话）时写 0，与参考图一致
        cacheWriteTokens = message.cacheWriteTokens ?: 0,
        completionTokens = message.completionTokens,
        reasoningTokens = message.reasoningTokens,
        model = message.usageModel
    )
}
