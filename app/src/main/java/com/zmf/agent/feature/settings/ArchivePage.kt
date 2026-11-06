package com.zmf.agent.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.uikit.common.stableStatusBarPadding
import com.zmf.agent.uikit.layout.LocalUiWindow
import com.zmf.agent.uikit.common.SaltConfirmDialog
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.icons.DshIcons
import com.zmf.agent.uikit.theme.Palette
import java.util.Locale

/** 分组表的键：null（未分类）用空串占位，工作区 id 不会是空串。 */
private const val NO_WORKSPACE_KEY = ""

/**
 * 已归档会话页：按工作区分组，只列存在归档会话的组。
 *
 * 行尾并列「恢复」与「删除」；动作达三项时改为行尾菜单，见回收站页。
 */
@Composable
fun ArchivePage(
    workspaces: List<WorkSpace>,
    archived: List<ChatSession>,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
    /** 删除确认的追加段：仅当该会话是某内置目录最后一个引用时非空。 */
    deleteNote: (String) -> String? = { null }
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 320dp 级窄窗口把页面内距收到 12dp
    val pad = if (LocalUiWindow.current.paneWidthDp < 360.dp) 12.dp else 16.dp
    // 删除一律经确认：这一页是唯一能永久删除会话的地方，误触没有撤销余地
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }

    // 仅在数据变化时重算分组；工作区已删除的会话归入未分类
    val known = remember(workspaces) { workspaces.map { it.id }.toSet() }
    val groups = remember(workspaces, archived) {
        val buckets = archived.groupBy { it.workspaceId?.takeIf { id -> id in known } }
        val list = ArrayList<Pair<WorkSpace?, List<ChatSession>>>(workspaces.size + 1)
        for (space in workspaces) {
            list.add(space to buckets[space.id].orEmpty().sortedByDescending { it.updatedAt })
        }
        list.add(null to buckets[null].orEmpty().sortedByDescending { it.updatedAt })
        list.filter { it.second.isNotEmpty() }
    }

    Surface(
        // 铺满且不透明；最外层拦截点击，避免穿透到下层聊天界面
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
                    text = "已归档会话",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (groups.isEmpty()) {
                ArchiveEmptyState(Modifier.weight(1f))
            } else {
                Text(
                    text = "共 " + archived.size + " 个会话 · 分属 " + groups.size + " 个分组",
                    modifier = Modifier.padding(start = pad, end = pad, bottom = 4.dp),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    groups.forEach { (space, sessions) ->
                        val groupId = space?.id ?: NO_WORKSPACE_KEY
                        item(key = "header-" + groupId) {
                            Row(
                                modifier = Modifier.padding(
                                    start = pad,
                                    end = pad,
                                    top = 12.dp,
                                    bottom = 6.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = space?.name ?: "未分类",
                                    fontSize = 12.sp,
                                    color = onSurface.copy(alpha = 0.45f)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = sessions.size.toString(),
                                    fontSize = 12.sp,
                                    color = onSurface.copy(alpha = 0.28f)
                                )
                            }
                        }
                        item(key = "card-" + groupId) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = pad),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Column {
                                    sessions.forEachIndexed { index, session ->
                                        ArchiveRow(
                                            session = session,
                                            onRestore = { onRestore(session.id) },
                                            onDelete = { pendingDelete = session }
                                        )
                                        if (index != sessions.lastIndex) HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除确认：文案与设置里的删除口径一致，命中内置目录最后引用时追加一段
    pendingDelete?.let { session ->
        val extra = deleteNote(session.id)
        SaltConfirmDialog(
            title = "删除会话",
            message = buildString {
                append("「").append(session.title.ifBlank { "新会话" }).append("」将被永久清除，")
                append("包括消息、计划、目标与上下文记录。此操作不可逆。")
                if (extra != null) append("\n\n").append(extra)
            },
            confirmLabel = "删除",
            destructive = true,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                onDelete(session.id)
            }
        )
    }
}

/**
 * 归档行：点击恢复，行尾为恢复与删除。
 *
 * 长按必须给空的 onLongClick：clickable 无长按分支，按住松手会被当作点击。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveRow(
    session: ChatSession,
    modifier: Modifier = Modifier,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onRestore, onLongClick = {})
            .padding(start = 14.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标底座：与回收站同一套（34dp 方块 + 18dp 线稿），两页观感一致
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(onSurface.copy(alpha = 0.05f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = DshIcons.Archive,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = onSurface.copy(alpha = 0.55f)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title.ifBlank { "新会话" },
                fontSize = 14.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface
            )
            Text(
                text = sessionSummary(session),
                modifier = Modifier.padding(top = 3.dp),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.45f)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "恢复",
            modifier = Modifier
                .saltRowClickable(RoundedCornerShape(8.dp), onClick = onRestore)
                .padding(horizontal = 9.dp, vertical = 6.dp),
            fontSize = 13.sp,
            color = Palette.Neutral
        )
        Text(
            text = "删除",
            modifier = Modifier
                .saltRowClickable(RoundedCornerShape(8.dp), onClick = onDelete)
                .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/** 空状态。 */
@Composable
private fun ArchiveEmptyState(modifier: Modifier = Modifier) {
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
                    imageVector = DshIcons.Archive,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = onSurface.copy(alpha = 0.4f)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(text = "没有归档的会话", fontSize = 14.sp, color = onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            Text(
                text = "归档只是把会话移出侧边栏，数据不会删除\n随时可以在这里恢复",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = onSurface.copy(alpha = 0.38f)
            )
        }
    }
}

/** 明细行：轮次 · token · 最后活动时间。 */
private fun sessionSummary(session: ChatSession): String {
    val parts = ArrayList<String>(4)
    val rounds = session.messages.count { it.countsAsRound }
    if (rounds > 0) parts += rounds.toString() + " 轮"
    val tokens = session.promptTokens + session.completionTokens
    if (tokens > 0) parts += tokenText(tokens) + " token"
    parts += SessionStore.relativeTime(session.updatedAt)
    return parts.joinToString(" · ")
}

/** token 的短写法。 */
private fun tokenText(tokens: Int): String = when {
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 10_000 -> String.format(Locale.US, "%.0fk", tokens / 1000.0)
    tokens >= 1000 -> String.format(Locale.US, "%.1fk", tokens / 1000.0)
    else -> tokens.toString()
}
