package com.zmf.agent.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.agent.session.SessionStore
import com.zmf.agent.tools.file.TrashStore
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 「回收站」页（设置 → 数据 → 回收站）。
 *
 * 为什么只留这一个入口：回收站的全部操作必须由**用户本人**发起（主体分离），
 * 所以它不挂任何 Agent 工具，也不出现在工作区里；用户能点到的只有这一页。
 *
 * 页面只做三件事：看（原路径 / 大小 / 时间）、恢复、永久删除（单条或清空）。
 * 淘汰（7 天 / 单池 512 MB）不在这里做，那是 App 启动时的后台动作，
 * 让用户手动点「清理」只会把一件自动化的事变成负担。
 */
@Composable
fun TrashPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<TrashStore.Entry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmPurge by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TrashStore.Entry?>(null) }
    // 每次恢复/删除后自增，让下面的 LaunchedEffect 重新读盘（不引 ViewModel：
    // 这一页的数据源是文件系统本身，重读一次比维护一份内存副本更不容易走样）
    var generation by remember { mutableStateOf(0) }

    LaunchedEffect(generation) {
        entries = withContext(Dispatchers.IO) { TrashStore.entries(context) }
        loaded = true
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val totalBytes = entries.sumOf { it.bytes }

    Surface(
        // 铺满且不透明；最外层吞掉点击，避免穿透点到下面的页面
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

            Text(
                text = if (!loaded) {
                    "正在统计…"
                } else if (entries.isEmpty()) {
                    "保留 7 天，超期自动清理"
                } else {
                    entries.size.toString() + " 项 · " + trashSizeText(totalBytes) + " · 保留 7 天"
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                fontSize = 12.sp,
                color = onSurface.copy(alpha = 0.45f)
            )

            if (loaded && entries.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "回收站是空的",
                        fontSize = 13.sp,
                        color = onSurface.copy(alpha = 0.45f)
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(entries, key = { it.dir.absolutePath }) { entry ->
                        TrashRow(
                            entry = entry,
                            onRestore = {
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) {
                                        TrashStore.restore(context, entry)
                                    }
                                    notice = outcome.message
                                    generation++
                                }
                            },
                            onDelete = { pendingDelete = entry }
                        )
                    }
                }
            }

            notice?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    color = Palette.Neutral
                )
            }
            Text(
                text = "删除的文件只在这里保留；Agent 无法读取、写入或清空回收站。",
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
                fontSize = 11.sp,
                color = onSurface.copy(alpha = 0.35f)
            )
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("永久删除") },
            text = {
                Text(
                    text = "「" + entryName(entry) + "」将被立即删除，无法恢复。",
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { TrashStore.deleteEntry(context, entry) }
                        notice = if (ok) "已永久删除「" + entryName(entry) + "」" else "删除失败"
                        generation++
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text("清空回收站") },
            text = {
                Text(
                    text = "将永久删除 " + entries.size + " 项（约 " + trashSizeText(totalBytes) +
                        "），无法恢复。",
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPurge = false
                    scope.launch {
                        val (count, bytes) = withContext(Dispatchers.IO) { TrashStore.purge(context) }
                        notice = "已清空 " + count + " 项，释放 " + trashSizeText(bytes)
                        generation++
                    }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurge = false }) { Text("取消") }
            }
        )
    }
}

/** 一条回收站记录：文件名 + 时间 / 大小，右侧「恢复」「删除」；点行 = 恢复，长按 = 删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashRow(
    entry: TrashStore.Entry,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // SAF 目录推不出本地路径时这条只能看、不能恢复：把按钮置灰，点了也不会假装成功
    val canRestore = !entry.localPath.isNullOrBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { if (canRestore) onRestore() }, onLongClick = onDelete)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entryName(entry),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface
            )
            Text(
                text = entryDetail(entry),
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.45f)
            )
        }
        Text(
            text = "恢复",
            modifier = Modifier
                .saltRowClickable(
                    RoundedCornerShape(8.dp),
                    enabled = canRestore,
                    onClick = onRestore
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 13.sp,
            color = if (canRestore) Palette.Neutral else onSurface.copy(alpha = 0.25f)
        )
        Text(
            text = "删除",
            modifier = Modifier
                .saltRowClickable(RoundedCornerShape(8.dp), onClick = onDelete)
                .padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/** 条目名：用原路径的最后一段；根目录被删时退化成完整原路径。 */
private fun entryName(entry: TrashStore.Entry): String {
    val name = File(entry.originalPath).name
    return name.ifBlank { entry.originalPath.ifBlank { "未命名" } }
}

/** 一行小字：相对时间 · 大小 · 文件/目录数；被截断的统计标「以上」。 */
private fun entryDetail(entry: TrashStore.Entry): String {
    val parts = ArrayList<String>(4)
    parts += if (entry.time > 0) SessionStore.relativeTime(entry.time) else "—"
    parts += trashSizeText(entry.bytes)
    if (entry.dirs > 0) {
        parts += entry.files.toString() + " 文件 / " + entry.dirs + " 目录"
    } else if (entry.files > 0) {
        parts += entry.files.toString() + " 文件"
    }
    parts += if (entry.localPath.isNullOrBlank()) "系统授权目录" else "本机"
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
