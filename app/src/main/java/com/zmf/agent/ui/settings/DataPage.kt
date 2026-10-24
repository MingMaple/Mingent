package com.zmf.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.terminal.TerminalEnv
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「数据」页（二级）：导入导出、附件占用与清理、日志、归档会话。
 *
 * 原来是分类页里的一页，现在入口挪到设置首页的那一行；行序、文案、可用条件全部照搬，
 * 免得用户按老位置点空。附件占用要数目录，进页面先刷一次，真正读盘交给 ViewModel 在 IO 里做。
 */
@Composable
fun DataSettingsPage(
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onLogs: () -> Unit,
    onArchive: () -> Unit,
    archivedCount: Int,
    attachmentUsage: String?,
    backupStatus: String?,
    /** 上一次导出/导入的结果：显示在这一页，不再往主页那条状态行上挂。 */
    backupNotice: String? = null,
    orphanStatus: String?,
    onRefreshAttachment: () -> Unit,
    onCleanOrphans: () -> Unit
) {
    LaunchedEffect(Unit) { onRefreshAttachment() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 终端环境是否装好：「不在」时把那句提示直接摆在页面上，不用等用户去终端里撞黑屏
    var terminalInstalled by remember { mutableStateOf(TerminalEnv.isInstalled(context)) }
    var confirmReinstall by remember { mutableStateOf(false) }
    var reinstallNote by remember { mutableStateOf<String?>(null) }

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
                .verticalScroll(rememberScrollState())
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
                    text = "数据",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DataCard {
                    DataRow(title = "导出用户数据", enabled = backupStatus == null, onClick = onExport)
                    DataDivider()
                    DataRow(title = "导入用户数据", enabled = backupStatus == null, onClick = onImport)
                    DataDivider()
                    DataRow(title = "附件占用", value = attachmentUsage ?: "统计中…")
                    DataDivider()
                    DataRow(
                        title = "清理未引用附件",
                        enabled = backupStatus == null,
                        onClick = onCleanOrphans
                    )
                }
                DataCard {
                    DataRow(
                        title = "终端环境（Ubuntu）",
                        value = if (terminalInstalled) "已安装" else "异常"
                    )
                    DataDivider()
                    DataRow(
                        title = "重装 Ubuntu 环境",
                        enabled = backupStatus == null,
                        onClick = { confirmReinstall = true }
                    )
                }
                DataCard {
                    DataRow(title = "日志", onClick = onLogs)
                    DataDivider()
                    DataRow(
                        title = "已归档的会话",
                        value = archivedCount.toString() + " 个",
                        onClick = onArchive
                    )
                }
                orphanStatus?.let { message -> DataNote(message = message) }
                // 导出/导入的进度与结果就落在底部这行小字里（2026-10-25 用户：不要单独开一个模块）
                backupStatus?.let { status -> DataNote(message = status) }
                backupNotice?.let { notice -> DataNote(message = notice) }
                reinstallNote?.let { message -> DataNote(message = message) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmReinstall) {
        AlertDialog(
            onDismissRequest = { confirmReinstall = false },
            title = { Text("重装 Ubuntu 环境") },
            text = {
                Text(
                    text = "此操作将删除终端内的 Ubuntu 环境，包括通过 apt 安装的软件包" +
                        "及 /root 目录下的文件；随后在下次进入终端时，依据应用内置镜像重新解压" +
                        "（约需 1 分钟，需 0.5 GB 可用空间）。会话记录、计划与记忆数据均不受影响。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReinstall = false
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) { TerminalEnv.reinstall(context) }
                        terminalInstalled = TerminalEnv.isInstalled(context)
                        reinstallNote = if (freed > 0L) {
                            "旧环境已清除（释放 " + (freed / 1024 / 1024) + " MB）。打开终端后将自动重新解压。"
                        } else {
                            "未找到可清除的根文件系统。若终端仍无法启动，请截图本页并反馈。"
                        }
                    }
                }) { Text("重装", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReinstall = false }) { Text("取消") }
            }
        )
    }
}

/** 分组卡片：Salt 的圆角 22dp，卡片底走 colorScheme.surface，不描边。 */
@Composable
private fun DataCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
    ) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            content()
        }
    }
}

/** 卡片内的分隔线：从文字左边起，不横穿整张卡片。 */
@Composable
private fun DataDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    )
}

/** 一行入口；onClick 为 null 表示只读信息行（不画「›」）。 */
@Composable
private fun DataRow(
    title: String,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 只读行（value 那几行）根本不挂点击，也就没有按压反馈
            .then(
                if (onClick != null) {
                    Modifier.saltRowClickable(RoundedCornerShape(0.dp), enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface.copy(alpha = if (enabled) 1f else 0.4f)
        )
        if (value != null) {
            Text(
                text = value,
                modifier = Modifier.widthIn(max = 180.dp),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.45f)
            )
        }
        if (onClick != null) {
            Text(
                text = "›",
                modifier = Modifier.padding(start = 6.dp),
                fontSize = 16.sp,
                color = onSurface.copy(alpha = 0.35f)
            )
        }
    }
}

/** 一行说明小字（备份进度、清理结果）。 */
@Composable
private fun DataNote(message: String) {
    Text(
        text = message,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        fontSize = 12.sp,
        color = Palette.Neutral
    )
}
