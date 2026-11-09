package com.zmf.agent.feature.chat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.infra.backup.BackupArchive
import com.zmf.agent.infra.backup.BackupManifest
import com.zmf.agent.infra.backup.BackupOptions
import com.zmf.agent.infra.backup.ImportReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 数据备份与恢复：导出、读清单、导入，以及「导入后必须重启」这道闸。
 *
 * 从 [ChatViewModel] 拆出，行为不变：状态字段名与原成员逐字一致，方法体原样保留；
 * 协程域仍由调用方传入（导出与导入本就由界面触发）。
 */
class BackupController(private val appContext: Context) {

    /** 备份正在跑：导出/导入按钮一起禁用。 */
    var backupBusy by mutableStateOf(false)
        private set

    /** 备份进度文案（「导出中 37%」）；空闲为 null。 */
    var backupStatus by mutableStateOf<String?>(null)
        private set

    /**
     * 上一次导出 / 导入的结果，非空时在**数据页**里显示一行。
     *
     * 为什么不挂到主页状态行：导出由数据页触发，进度也在该页显示；
     * 结果若显示在主页，而用户仍停留在数据页，则无法看到。
     */
    var backupNotice by mutableStateOf<String?>(null)
        private set

    /** 导入前的清单摘要；非空时界面弹确认框。 */
    var pendingManifest by mutableStateOf<BackupManifest?>(null)
        private set

    /** 导入结果；非空时界面弹报告。 */
    var importReport by mutableStateOf<ImportReport?>(null)
        private set

    /**
     * 刚导入完、还没重启。
     *
     * 磁盘上的数据已经是备份里的那一份，但内存里还是旧列表：这时任何一次落盘
     * 都会覆盖刚导入的数据。因此自导入成功起：发送直接拒绝、落盘全部跳过，
     * 等用户重启进程（内存从磁盘重新读）才放开。
     */
    var needsRestart by mutableStateOf(false)
        private set

    /** 导出到系统里选的 zip 目标。 */
    fun exportData(uri: Uri, options: BackupOptions, scope: CoroutineScope) {
        if (backupBusy) return
        backupBusy = true
        backupStatus = "准备导出…"
        backupNotice = null
        scope.launch {
            val result = BackupArchive.export(appContext, uri, options) { percent, stage ->
                backupStatus = stage + " " + percent + "%"
            }
            backupBusy = false
            backupStatus = null
            result.fold(
                onSuccess = { outcome ->
                    val size = "%.1f".format(outcome.bytes / 1024.0 / 1024.0) + " MB"
                    backupNotice = buildString {
                        append("导出成功：")
                        append(displayNameOf(uri) ?: "备份文件")
                        append("（").append(size).append("）")
                        // 终端环境被跳过时说明原因，避免误认为整包已完整
                        outcome.note?.let { append("\n").append(it) }
                    }
                    FileLog.log(
                        "backup",
                        "导出完成：" + outcome.bytes + " 字节" + (outcome.note?.let { "，$it" } ?: "")
                    )
                },
                onFailure = { e ->
                    backupNotice = "导出失败，半截文件已删除。请确认目标位置可写、空间足够后重新导出一次。"
                    FileLog.log("backup", "导出失败：" + e)
                }
            )
        }
    }

    /** 系统文件选择器里那个目标文件叫什么（数据页那句「导出到 xxx」用）。 */
    private fun displayNameOf(uri: Uri): String? = try {
        appContext.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        null
    }

    /** 只读备份清单：先给用户看清楚要导入什么，再决定。 */
    fun loadBackupManifest(uri: Uri, scope: CoroutineScope) {
        if (backupBusy) return
        backupBusy = true
        backupStatus = "读取备份信息…"
        scope.launch {
            val result = BackupArchive.peekManifest(appContext, uri)
            backupBusy = false
            backupStatus = null
            result.fold(
                onSuccess = { manifest -> pendingManifest = manifest },
                onFailure = { e ->
                    backupNotice = "无法识别这个备份。请确认文件是本应用导出的备份包，或换一个文件再试。"
                    FileLog.log("backup", "识别备份失败：" + e)
                }
            )
        }
    }

    /** 放弃已解析但还没确认导入的备份：只清待确认状态，不动磁盘数据。 */
    fun cancelImport() {
        pendingManifest = null
    }

    /** 关掉导入结果报告；报告本身是界面状态，关掉不影响已导入的数据。 */
    fun dismissImportReport() {
        importReport = null
    }

    /** 开始导入备份文件；[backupBusy] 期间重复调用会被忽略。 */
    fun importData(uri: Uri, scope: CoroutineScope) {
        if (backupBusy) return
        pendingManifest = null
        backupBusy = true
        backupStatus = "准备导入…"
        scope.launch {
            val result = BackupArchive.import(appContext, uri) { percent, stage ->
                backupStatus = stage + " " + percent + "%"
            }
            backupBusy = false
            backupStatus = null
            result.fold(
                onSuccess = { report ->
                    importReport = report
                    needsRestart = true
                    FileLog.log(
                        "backup",
                        "导入完成：成功 " + report.restored.size + " 项、跳过 " + report.skipped.size +
                            " 项、失败 " + report.failed.size + " 项"
                    )
                },
                onFailure = { e ->
                    // 导入失败也显示在数据页那一行（报告对话框之外仍可看到前一句）
                    backupNotice = "导入失败，请重试；如仍失败，请换一份备份文件。"
                    FileLog.log("backup", "导入失败：" + e)
                }
            )
        }
    }
}
