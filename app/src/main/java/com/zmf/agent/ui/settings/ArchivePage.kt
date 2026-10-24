package com.zmf.agent.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.agent.session.ChatSession
import com.zmf.agent.agent.session.SessionStore
import com.zmf.agent.agent.workspace.WorkSpace
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.theme.Palette

/** 分组表的键：null（未分类）用空串占位，工作区 id 不会是空串。 */
private const val NO_WORKSPACE_KEY = ""

/**
 * 「已归档」页（设置 → 数据 下的二级页）：按工作区分组，只列有归档会话的组。
 */
@Composable
fun ArchivePage(
    workspaces: List<WorkSpace>,
    archived: List<ChatSession>,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    // 分组只在数据变化时重算：组内按 updatedAt 倒序，工作区已被删掉的会话归进未分类
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
        // 铺满且不透明；最外层吞掉点击，避免穿透点到下面的聊天界面
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
                    text = "已归档",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "没有归档的会话",
                        fontSize = 13.sp,
                        color = onSurface.copy(alpha = 0.45f)
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    groups.forEach { (space, sessions) ->
                        item(key = "header-" + (space?.id ?: NO_WORKSPACE_KEY)) {
                            Text(
                                text = space?.name ?: "未分类",
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                                fontSize = 12.sp,
                                color = onSurface.copy(alpha = 0.45f)
                            )
                        }
                        items(sessions, key = { it.id }) { session ->
                            ArchiveRow(
                                session = session,
                                onRestore = { onRestore(session.id) },
                                onDelete = { onDelete(session.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一行归档会话：标题 + 相对时间，右侧「恢复」「删除」；点行 = 恢复，长按 = 删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveRow(
    session: ChatSession,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onRestore, onLongClick = onDelete)
            .padding(start = 16.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title.ifBlank { "新会话" },
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface
            )
            Text(
                text = SessionStore.relativeTime(session.updatedAt),
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 11.sp,
                color = onSurface.copy(alpha = 0.45f)
            )
        }
        Text(
            text = "恢复",
            modifier = Modifier
                .saltRowClickable(RoundedCornerShape(8.dp), onClick = onRestore)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 13.sp,
            color = Palette.Neutral
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
