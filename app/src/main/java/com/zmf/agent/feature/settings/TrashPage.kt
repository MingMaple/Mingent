package com.zmf.agent.feature.settings

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.capability.storage.StorageReclaimer
import com.zmf.agent.capability.tools.file.TrashStore
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.uikit.common.stableStatusBarPadding
import com.zmf.agent.uikit.layout.LocalUiWindow
import com.zmf.agent.uikit.common.NoticeCenter
import com.zmf.agent.uikit.common.noticeName
import com.zmf.agent.uikit.common.SaltConfirmDialog
import com.zmf.agent.uikit.common.SaltMenuPopover
import com.zmf.agent.uikit.common.SaltMenuRow
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.icons.DshIcons
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 回收站页。
 *
 * 回收站的操作只由用户发起，因此不挂 Agent 工具，入口仅此一处。
 * 布局为概览卡 + 两组卡片；行内三项信息，动作收进行尾菜单。
 */
@Composable
fun TrashPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 320dp 级窄窗口把页面内距收到 12dp，避免卡片与行文字贴边
    val window = LocalUiWindow.current
    val pad = if (window.paneWidthDp < 360.dp) 12.dp else 16.dp
    // 横屏矮窗口（高度 <480dp）用单行概览，给列表留出空间
    val compact = window.heightDp < 480.dp
    var entries by remember { mutableStateOf<List<TrashStore.Entry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TrashStore.Entry?>(null) }
    // 菜单、导出、删除都要用同一条记录
    var menuEntry by remember { mutableStateOf<TrashStore.Entry?>(null) }
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    var exportEntry by remember { mutableStateOf<TrashStore.Entry?>(null) }
    // 每次恢复/删除后自增，让下面的 LaunchedEffect 重新读盘（不引 ViewModel：
    // 这一页的数据源是文件系统本身，重读一次比维护一份内存副本更不易产生偏差）
    var generation by remember { mutableStateOf(0) }

    LaunchedEffect(generation) {
        entries = withContext(Dispatchers.IO) { TrashStore.entries(context) }
        loaded = true
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val totalBytes = entries.sumOf { it.bytes }
    val workspaceEntries = entries.filter { it.isWorkspace }
    val fileEntries = entries.filter { !it.isWorkspace }

    /** 恢复一条：内置目录走「重建工作区」，普通条目回到原路径。 */
    fun restore(entry: TrashStore.Entry) {
        if (!entry.isWorkspace && entry.localPath.isNullOrBlank()) {
            NoticeCenter.error("该记录无本地路径，无法恢复")
            return
        }
        scope.launch {
            val (ok, message) = withContext(Dispatchers.IO) {
                if (entry.isWorkspace) {
                    val outcome = StorageReclaimer.restoreWorkspace(context, entry)
                    (outcome.workspace != null) to outcome.message
                } else {
                    val outcome = TrashStore.restore(context, entry)
                    outcome.ok to outcome.message
                }
            }
            // 成功时自建短文案：恢复结果里的完整路径与全名对提示条都没有意义
            if (ok) {
                val name = noticeName(entryName(entry))
                NoticeCenter.success(
                    if (entry.isWorkspace) "已恢复工作区「" + name + "」" else "已恢复「" + name + "」"
                )
            } else {
                NoticeCenter.error(message)
            }
            generation++
        }
    }

    Surface(
        // 铺满且不透明；最外层拦截点击，避免事件穿透到下方的页面
        modifier = Modifier
            .fillMaxSize()
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .stableStatusBarPadding()
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "←",
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(50)) { onBack() }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "回收站",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.weight(1f))
                if (entries.isNotEmpty()) {
                    Text(
                        text = "清空",
                        modifier = Modifier
                            .saltRowClickable(RoundedCornerShape(8.dp)) { confirmPurge = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            when {
                !loaded -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "正在统计…",
                        fontSize = 12.sp,
                        color = onSurface.copy(alpha = 0.4f)
                    )
                }

                entries.isEmpty() -> TrashEmptyState(Modifier.weight(1f))

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    item(key = "summary") {
                        if (compact) {
                            CompactSummaryRow(
                                entries = entries,
                                totalBytes = totalBytes,
                                modifier = Modifier.padding(start = pad, end = pad, top = 4.dp, bottom = 2.dp)
                            )
                        } else {
                            TrashSummaryCard(
                                entries = entries,
                                totalBytes = totalBytes,
                                modifier = Modifier.padding(start = pad, end = pad, top = 4.dp, bottom = 6.dp)
                            )
                        }
                    }
                    if (workspaceEntries.isNotEmpty()) {
                        item(key = "head-workspace") {
                            GroupHeader(text = "内置目录", count = workspaceEntries.size, pad = pad)
                        }
                        item(key = "card-workspace") {
                            EntryCard(
                                entries = workspaceEntries,
                                pad = pad,
                                onRestore = { entry -> restore(entry) },
                                onMenu = { entry, anchor ->
                                    menuAnchor = anchor
                                    menuEntry = entry
                                }
                            )
                        }
                    }
                    if (fileEntries.isNotEmpty()) {
                        item(key = "head-file") {
                            GroupHeader(text = "文件与目录", count = fileEntries.size, pad = pad)
                        }
                        item(key = "card-file") {
                            EntryCard(
                                entries = fileEntries,
                                pad = pad,
                                onRestore = { entry -> restore(entry) },
                                onMenu = { entry, anchor ->
                                    menuAnchor = anchor
                                    menuEntry = entry
                                }
                            )
                        }
                    }
                    item(key = "footer") {
                        Text(
                            text = "删除的内容只在这里保留，Agent 无法读取、写入或清空回收站。",
                            modifier = Modifier.padding(start = pad + 4.dp, end = pad + 4.dp, top = 14.dp, bottom = 4.dp),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = onSurface.copy(alpha = 0.32f)
                        )
                    }
                }
            }
        }
    }

    // 条目菜单：三类动作统一两字，行内不再并列多个文字按钮
    menuEntry?.let { entry ->
        SaltMenuPopover(anchor = menuAnchor, onDismiss = { menuEntry = null }) {
            SaltMenuRow(text = "恢复") {
                menuEntry = null
                restore(entry)
            }
            SaltMenuRow(text = "导出") {
                menuEntry = null
                exportEntry = entry
            }
            SaltMenuRow(text = "删除", color = MaterialTheme.colorScheme.error) {
                menuEntry = null
                pendingDelete = entry
            }
        }
    }

    exportEntry?.let { entry ->
        ExportDialog(
            entry = entry,
            onDismiss = { exportEntry = null },
            onConfirm = { target ->
                exportEntry = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { TrashStore.exportTo(entry, File(target)) }
                    if (ok) {
                        NoticeCenter.success("已导出")
                    } else {
                        NoticeCenter.error("导出失败，目标路径不可写或已有同名文件；请更换目录后重试")
                    }
                    generation++
                }
            }
        )
    }

    pendingDelete?.let { entry ->
        SaltConfirmDialog(
            title = if (entry.isWorkspace) "永久删除内置目录" else "永久删除",
            message = if (entry.isWorkspace) {
                "「" + entryName(entry) + "」及其中的全部文件将被立即删除，无法恢复。"
            } else {
                "「" + entryName(entry) + "」将被立即删除，无法恢复。"
            },
            confirmLabel = "删除",
            destructive = true,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { TrashStore.deleteEntry(context, entry) }
                    if (ok) {
                        NoticeCenter.success("已永久删除「" + noticeName(entryName(entry)) + "」")
                    } else {
                        NoticeCenter.error("删除失败，请稍后重试")
                    }
                    generation++
                }
            }
        )
    }

    if (confirmPurge) {
        SaltConfirmDialog(
            title = "清空回收站",
            message = buildString {
                append("将永久删除 ").append(entries.size).append(" 项（约 ")
                append(trashSizeText(totalBytes)).append("），无法恢复。")
                if (workspaceEntries.isNotEmpty()) {
                    append("其中包含 ").append(workspaceEntries.size)
                    append(" 个内置工作区目录，没有其他副本。")
                }
            },
            confirmLabel = "清空",
            destructive = true,
            onDismiss = { confirmPurge = false },
            onConfirm = {
                confirmPurge = false
                scope.launch {
                    val (count, bytes) = withContext(Dispatchers.IO) { TrashStore.purge(context) }
                    NoticeCenter.success("已清空 " + count + " 项，释放 " + trashSizeText(bytes))
                    generation++
                }
            }
        )
    }
}

/** 概览卡：体积、最近到期、两类条目数量。 */
@Composable
private fun TrashSummaryCard(
    entries: List<TrashStore.Entry>,
    totalBytes: Long,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val workspaceCount = entries.count { it.isWorkspace }
    val fileCount = entries.size - workspaceCount
    // 最早到期的一条决定「最迟什么时候必须回来处理」
    val nearest = entries.minByOrNull { it.remainingDays() }?.remainingDays() ?: 0
    val urgent = nearest <= 2

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "已占用",
                        fontSize = 11.sp,
                        color = onSurface.copy(alpha = 0.42f)
                    )
                    Text(
                        text = trashSizeText(totalBytes),
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Medium,
                        color = onSurface
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "最近到期",
                        fontSize = 11.sp,
                        color = onSurface.copy(alpha = 0.42f)
                    )
                    Text(
                        text = if (nearest <= 0) "即将清除" else nearest.toString() + " 天后",
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (urgent) UiColors.warning else onSurface
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountPill(text = "普通条目", count = fileCount)
                if (workspaceCount > 0) CountPill(text = "内置目录", count = workspaceCount)
            }
        }
    }
}

/**
 * 单行概览：横屏矮窗口用。
 *
 * 竖屏那张概览卡占约 120dp，411dp 高的横屏里会把列表挤到只剩一行；
 * 这里压成一行，信息不减。
 */
@Composable
private fun CompactSummaryRow(
    entries: List<TrashStore.Entry>,
    totalBytes: Long,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val workspaceCount = entries.count { it.isWorkspace }
    val fileCount = entries.size - workspaceCount
    val nearest = entries.minByOrNull { it.remainingDays() }?.remainingDays() ?: 0

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = trashSizeText(totalBytes),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = onSurface
            )
            CountPill(text = "普通", count = fileCount)
            if (workspaceCount > 0) CountPill(text = "内置", count = workspaceCount)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (nearest <= 0) "即将清除" else nearest.toString() + " 天后到期",
                fontSize = 11.sp,
                color = if (nearest <= 2) UiColors.warning else onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

/** 类型计数：小胶囊，数字加粗、类型名退到 45%。 */
@Composable
private fun CountPill(text: String, count: Int) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .background(onSurface.copy(alpha = 0.05f), RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, fontSize = 11.sp, color = onSurface.copy(alpha = 0.45f))
        Spacer(Modifier.width(5.dp))
        Text(
            text = count.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = onSurface.copy(alpha = 0.75f)
        )
    }
}

/** 分组标题：类型名 + 数量，12sp、45%，与下方卡片的 16dp 左内距对齐。 */
@Composable
private fun GroupHeader(text: String, count: Int, pad: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier.padding(start = pad, end = pad, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = count.toString(),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
        )
    }
}

/** 一组条目：一张卡片，行间通栏分割线。 */
@Composable
private fun EntryCard(
    entries: List<TrashStore.Entry>,
    pad: androidx.compose.ui.unit.Dp,
    onRestore: (TrashStore.Entry) -> Unit,
    onMenu: (TrashStore.Entry, Rect) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = pad),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            entries.forEachIndexed { index, entry ->
                TrashRow(
                    entry = entry,
                    onRestore = { onRestore(entry) },
                    onMenu = { anchor -> onMenu(entry, anchor) }
                )
                if (index != entries.lastIndex) HorizontalDivider()
            }
        }
    }
}

/** 一条记录：名称、体积与删除时间、原位置。 */
@Composable
private fun TrashRow(
    entry: TrashStore.Entry,
    onRestore: () -> Unit,
    onMenu: (Rect) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltCardRow { onRestore() }
            .padding(start = 14.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(
            icon = when {
                entry.isWorkspace -> DshIcons.Folder
                entry.total > 1 -> DshIcons.Folder
                else -> DshIcons.File
            }
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // 保留期标签跟标题同一行：标题通常短，右端这点空间本来也是空的；
            // 放到第二行右侧会把「体积 · 数量 · 删除时间」挤成省略号
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entryName(entry),
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 14.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = onSurface
                )
                Spacer(Modifier.width(8.dp))
                RetentionBadge(entry.remainingDays())
            }
            Text(
                text = entryDetail(entry),
                modifier = Modifier.padding(top = 3.dp),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.45f)
            )
            Text(
                text = "原位置 " + friendlyPath(entry),
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.3f)
            )
        }
        // 「⋯」热区 30dp；面板贴着它右对齐弹出
        var anchor by remember { mutableStateOf(Rect.Zero) }
        Box(
            modifier = Modifier
                .size(30.dp)
                .onGloballyPositioned { anchor = it.boundsInWindow() }
                .saltRowClickable(RoundedCornerShape(8.dp)) { onMenu(anchor) },
            contentAlignment = Alignment.Center
        ) {
            Text(text = "⋯", fontSize = 16.sp, color = Palette.Neutral)
        }
    }
}

/** 保留期标签：剩 2 天以内转警告色，其余用中性灰。 */
@Composable
private fun RetentionBadge(days: Int) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val urgent = days <= 2
    val color = if (urgent) UiColors.warning else onSurface.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .background(
                if (urgent) UiColors.warning.copy(alpha = 0.12f) else onSurface.copy(alpha = 0.05f),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(text = "剩 " + days + " 天", fontSize = 10.sp, color = color)
    }
}

/** 条目图标底座：34dp 圆角方块 + 18dp 线稿图标，颜色统一走中性灰。 */
@Composable
private fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(onSurface.copy(alpha = 0.05f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = onSurface.copy(alpha = 0.55f)
        )
    }
}

/** 空状态：一枚图标、一句结论、一句「东西会从哪里来」。 */
@Composable
private fun TrashEmptyState(modifier: Modifier = Modifier) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(onSurface.copy(alpha = 0.05f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = DshIcons.Trash,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = onSurface.copy(alpha = 0.4f)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(text = "回收站是空的", fontSize = 14.sp, color = onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            Text(
                text = "会话里被删除的文件会先到这里\n普通条目保留 7 天，内置目录保留 30 天",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = onSurface.copy(alpha = 0.38f)
            )
        }
    }
}

/** 导出目标：默认系统下载目录，允许就地修改。 */
@Composable
private fun ExportDialog(
    entry: TrashStore.Entry,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val stamp = remember {
        java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(java.util.Date())
    }
    val defaultRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        .absolutePath
    val defaultPath = defaultRoot.trimEnd('/') + "/" + entryName(entry) + "-" + stamp
    var path by remember { mutableStateOf(defaultPath) }
    val onSurface = MaterialTheme.colorScheme.onSurface

    SaltConfirmDialog(
        title = "导出",
        message = "内容将复制到下面的路径，回收站中的原条目保持不变。",
        confirmLabel = "导出",
        onDismiss = onDismiss,
        onConfirm = { onConfirm(path.trim()) }
    ) {
        BasicTextField(
            value = path,
            onValueChange = { path = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, color = onSurface),
            cursorBrush = SolidColor(UiColors.accentFg),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .saltRowClickable(RoundedCornerShape(10.dp)) { }
                        .background(onSurface.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 9.dp)
                ) { inner() }
            }
        )
    }
}

/** 内置目录条目用原工作区名称；普通条目用原路径最后一段。 */
private fun entryName(entry: TrashStore.Entry): String {
    if (entry.isWorkspace) {
        return entry.name.ifBlank { File(entry.originalPath).name.ifBlank { "内置目录" } }
    }
    val name = File(entry.originalPath).name
    return name.ifBlank { entry.originalPath.ifBlank { "未命名" } }
}

/** 原位置只保留工作区内的相对部分，避免系统绝对路径占满一行。 */
private fun friendlyPath(entry: TrashStore.Entry): String {
    val path = entry.originalPath
    if (path.isBlank()) return "—"
    // 内置目录条目：它本身就是那个工作区目录，原位置指它的上一级
    if (entry.isWorkspace) return "工作区列表"
    val marker = "/workspaces/"
    val at = path.indexOf(marker)
    if (at < 0) {
        val files = "/files/"
        val cut = path.indexOf(files)
        return if (cut >= 0) path.substring(cut + files.length) else path
    }
    val rest = path.substring(at + marker.length)
    val parts = rest.split('/').filter { it.isNotEmpty() }
    // parts[0] 是工作区目录名，最后一段是条目自己的名字（标题已显示）
    val dirs = parts.drop(1).dropLast(1)
    return if (dirs.isEmpty()) "工作区根目录" else "工作区 / " + dirs.joinToString("/")
}

/** 明细行：体积 · 条目数 · 删除时间。 */
private fun entryDetail(entry: TrashStore.Entry): String {
    val parts = ArrayList<String>(3)
    parts += trashSizeText(entry.bytes)
    parts += entry.total.toString() + " 项"
    parts += "删除于 " + SessionStore.relativeTime(entry.time)
    return parts.joinToString(" · ")
}

/** 字节数的短写法：只用于界面展示，四舍五入到一个合理精度。 */
internal fun trashSizeText(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L ->
        String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L ->
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}
