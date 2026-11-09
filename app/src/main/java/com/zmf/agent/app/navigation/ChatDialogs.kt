package com.zmf.agent.app.navigation

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.store.TodoStore
import com.zmf.agent.feature.settings.CreateWorkspaceDialog
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.CoroutineScope
import com.zmf.agent.feature.chat.MinimalDialog
import com.zmf.agent.feature.chat.exportSizeText
import com.zmf.agent.feature.chat.BackupToggleRow
import com.zmf.agent.feature.chat.ChatUiState
import com.zmf.agent.feature.chat.ChatViewModel
import com.zmf.agent.uikit.common.NoticeCenter
import com.zmf.agent.uikit.common.noticeName

/**
 * 聊天页的全部模态框：新建工作区、导出选项、导入确认、导入结果、清单全表、回滚确认。
 *
 * 它们与主界面布局无关（不占用布局位置、不拦截手势），仅读写 [ChatUiState] 与 [ChatViewModel]，
 * 因此独立于 [ChatScreen] 实现，主函数仅保留一行调用。
 */
@Composable
internal fun ChatDialogs(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    context: Context,
    scope: CoroutineScope,
    /** 导出位置由调用方选择（SAF），本层仅负责传递文件名。 */
    onExportLaunch: (String) -> Unit
) {
    var showCreateWorkspace by ui.showCreateWorkspace
    var movingSessionId by ui.movingSessionId
    var storageWorkspaceId by ui.storageWorkspaceId
    var pendingArchiveId by ui.pendingArchiveId
    var showExportDialog by ui.showExportDialog
    var includeTerminal by ui.includeTerminal
    var includeLogs by ui.includeLogs
    var includeMemory by ui.includeMemory
    var terminalBytes by ui.terminalBytes
    var importUri by ui.importUri
    var todoDialogVisible by ui.todoDialogVisible
    var rollbackUserIndex by ui.rollbackUserIndex
    var drawerOpen by ui.drawerOpen
    var page by ui.page
    if (showCreateWorkspace) {
        CreateWorkspaceDialog(
            onDismiss = { showCreateWorkspace = false },
            onCreate = { name, rootPath, treeUri ->
                showCreateWorkspace = false
                // createWorkspace 内部已创建一条空会话并切换，此处仅收起抽屉并返回主页
                val created = viewModel.createWorkspace(name, rootPath, treeUri)
                if (created) {
                    drawerOpen = false
                    page = null
                } else {
                    // 目录不可用（未授权、已被删除或不可写）：提示用户，避免误认为创建成功
                    NoticeCenter.error("目录不可用，请另选一个")
                }
            }
        )
    }

    // 归档命中特例：多个特例合并成一个弹窗，不连续弹出
    pendingArchiveId?.let { id ->
        val notes = viewModel.archiveNotes(id)
        AlertDialog(
            onDismissRequest = { pendingArchiveId = null },
            title = { Text("归档会话？", fontSize = 17.sp) },
            text = {
                Text(
                    text = notes.joinToString("\n\n"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingArchiveId = null
                    val title = noticeName(
                        viewModel.sessionList.firstOrNull { it.id == id }?.title.orEmpty()
                    )
                    viewModel.archiveSession(id)
                    // 特例路径（绑定任务被停用）同样给一条提示：弹窗关掉后要给用户结果，
                    // 但不再提供「撤销」——任务状态已回不去，撤一半比不撤更糟
                    NoticeCenter.show("已归档「" + title + "」，定时任务已停用")
                }) { Text("归档") }
            },
            dismissButton = {
                TextButton(onClick = { pendingArchiveId = null }) { Text("取消") }
            }
        )
    }

    // 会话「⋯」→「更改工作区」：单层弹窗，新建工作区在同一窗口内展开
    movingSessionId?.let { id ->
        val session = viewModel.sessionList.firstOrNull { it.id == id }
        ChangeWorkspaceDialog(
            sessionTitle = session?.title?.ifBlank { "新会话" } ?: "新会话",
            workspaces = viewModel.workspaceList,
            currentWorkspaceId = session?.workspaceId,
            occupiedBy = { rootPath, treeUri -> viewModel.workspaceUsingDir(rootPath, treeUri) },
            onDismiss = { movingSessionId = null },
            onMove = { workspaceId ->
                movingSessionId = null
                val note = viewModel.moveSession(id, workspaceId)
                if (note == null) NoticeCenter.success("已移动") else NoticeCenter.error(note)
            },
            onCreateAndMove = { name, rootPath, treeUri ->
                movingSessionId = null
                val created = viewModel.createWorkspaceAndMove(id, name, rootPath, treeUri)
                if (created) {
                    NoticeCenter.success("已移动")
                } else {
                    NoticeCenter.error("目录不可用，请另选一个")
                }
            }
        )
    }

    // 工作区菜单 →「更改储存路径」：先说明后果，再选目录
    val storageTarget = storageWorkspaceId?.let { id -> viewModel.workspaceList.firstOrNull { it.id == id } }
    storageTarget?.let { space ->
        ChangeStorageDialog(
            workspaceName = space.name,
            prompt = viewModel.changeStoragePrompt(space.id),
            onDismiss = { storageWorkspaceId = null },
            onConfirm = { rootPath, treeUri ->
                storageWorkspaceId = null
                val ok = viewModel.changeWorkspaceStorage(space.id, rootPath, treeUri)
                if (ok) {
                    NoticeCenter.success("已更改储存路径")
                } else {
                    NoticeCenter.error("目录不可用，或会话正在运行")
                }
            }
        )
    }

    if (showExportDialog) {
        MinimalDialog(onDismiss = { showExportDialog = false }) {
            Text(
                text = "导出用户数据",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            BackupToggleRow(
                title = "包含 Ubuntu 环境",
                // 体积随磁盘实际占用变化：测量完成前仅提示「会久一点」，不使用固定常量
                hint = if (terminalBytes > 0L) "当前约 " + exportSizeText(terminalBytes) + "，导出会久一点"
                else "导出会久一点",
                checked = includeTerminal,
                onToggle = { includeTerminal = !includeTerminal }
            )
            BackupToggleRow(
                title = "包含日志",
                hint = "排查问题用，体积很小",
                checked = includeLogs,
                onToggle = { includeLogs = !includeLogs }
            )
            BackupToggleRow(
                title = "包含记忆数据",
                hint = "记忆事件与索引，不含内核程序",
                checked = includeMemory,
                onToggle = { includeMemory = !includeMemory }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "会话、工作区文件、模型与权限设置默认打包；记忆数据需单独勾选；API Key 不会写进备份。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = "取消",
                    modifier = Modifier
                        .clickable { showExportDialog = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "导出",
                    modifier = Modifier
                        .clickable {
                            showExportDialog = false
                            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                                .format(java.util.Date())
                            onExportLaunch("mingent-backup-" + stamp + ".zip")
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = UiColors.accentFg
                )
            }
        }
    }

    // 导入前确认：向用户展示清单，避免误操作导致覆盖
    viewModel.pendingManifest?.let { manifest ->
        MinimalDialog(onDismiss = { viewModel.cancelImport() }) {
            Text(
                text = "确认导入",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "来自 " + manifest.applicationId + " · v" + manifest.versionName,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = "包含内容：" + manifest.sections.joinToString("、"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "导入将覆盖当前数据且不可撤销" +
                    (if (manifest.sections.contains("terminal")) "；备份包含 Ubuntu 环境，请先退出终端页面" else "") +
                    (if (manifest.sections.contains("memory")) "；记忆数据将被整体替换" else "") +
                    "。API 密钥保留本机原有设置，不受覆盖影响。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = "取消",
                    modifier = Modifier
                        .clickable { viewModel.cancelImport() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "确认导入",
                    modifier = Modifier
                        .clickable {
                            val target = importUri
                            if (target != null) viewModel.importData(target, scope)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = UiColors.accentFg
                )
            }
        }
    }

    // 导入结果：逐条列出成功、跳过与失败项，不静默忽略错误
    viewModel.importReport?.let { report ->
        MinimalDialog(onDismiss = { viewModel.dismissImportReport() }) {
            Text(
                text = "导入完成",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            if (report.restored.isNotEmpty()) {
                Text(
                    text = "以下内容已恢复：" + report.restored.joinToString("、"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            if (report.skipped.isNotEmpty()) {
                Text(
                    text = "以下条目已跳过：" + report.skipped.joinToString("、"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            if (report.failed.isNotEmpty()) {
                Text(
                    text = "以下条目未能恢复：" + report.failed.joinToString("、"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "重启应用后全部生效。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = "确定",
                    modifier = Modifier
                        .clickable { viewModel.dismissImportReport() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    color = UiColors.accentFg
                )
            }
        }
    }

    // 清单全表：只读。清单由模型写入，此处不提供勾选（手动修改会与下一轮写入冲突）
    if (todoDialogVisible) {
        MinimalDialog(onDismiss = { todoDialogVisible = false }) {
            Text(
                text = "任务清单",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                viewModel.todos.forEach { todo ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            text = when (todo.status) {
                                TodoStore.COMPLETED -> "✓"
                                TodoStore.IN_PROGRESS -> "▶"
                                else -> "○"
                            },
                            fontSize = 13.sp,
                            color = if (todo.status == TodoStore.COMPLETED) {
                                UiColors.accentFg
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = todo.content,
                            fontSize = 13.sp,
                            color = if (todo.status == TodoStore.COMPLETED) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        }
    }

    rollbackUserIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { rollbackUserIndex = null },
            title = { Text("回滚到此处", fontSize = 17.sp) },
            text = {
                Text(
                    text = "这条提问以及它之后的所有对话都会被删除，不能撤销。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rollbackTo(index)
                    rollbackUserIndex = null
                }) { Text("删除并回滚", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { rollbackUserIndex = null }) { Text("取消") }
            }
        )
    }
}
