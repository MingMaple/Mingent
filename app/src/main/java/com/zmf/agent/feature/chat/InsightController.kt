package com.zmf.agent.feature.chat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zmf.agent.capability.memory.MemoryAdmin
import com.zmf.agent.capability.memory.MemoryKernel
import com.zmf.agent.capability.memory.MemoryState
import com.zmf.agent.core.log.FileLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 记忆动作的总超时：正常一次存储动作约 1.5 秒（真机实测 status 0.98–1.54 秒），留足余量后兜底。 */
private const val MEMORY_ACTION_TIMEOUT_MS = 120_000L

/**
 * 日志导出与记忆库：导出 zip、扫库/检索/维护/导出视图、向量模型开关。
 *
 * 从 [ChatViewModel] 拆出，行为不变：状态字段名与原成员逐字一致，方法体原样保留；
 * 协程域与当前工作区目录由构造参数注入，不反向持有 ViewModel。
 */
class InsightController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val workspaceRoot: () -> File
) {

    /** 日志导出的状态文本（成功/失败各一句）；null = 没导过。 */
    var logExportStatus by mutableStateOf<String?>(null)
        private set

    /** 记忆内核对外的状态（字段由 MemoryAdmin 定义）；由 [refreshMemory] 刷新。 */
    var memoryState by mutableStateOf(MemoryState())
        private set

    /** 向量模型开关（记忆页「向量模型」按钮）：仅影响内核是否启用稠密通道，默认关闭。 */
    var memoryVectorEnabled by mutableStateOf(MemoryKernel.isVectorEnabled(appContext))
        private set

    /** 向量模型开关下方的临时状态：检测、安装、结果；null = 显示默认注释。 */
    var memoryVectorStatus by mutableStateOf<String?>(null)
        private set

    /** 状态文案是否为失败结果，界面据此换错误色。 */
    var memoryVectorFailed by mutableStateOf(false)
        private set

    /** 依赖检测或安装正在进行。 */
    var memoryVectorBusy by mutableStateOf(false)
        private set

    /** 向量依赖检测/安装任务；关闭开关时取消，避免旧结果回写。 */
    private var vectorJob: Job? = null
    private var vectorGeneration = 0

    /**
     * 将 logs 目录下的日志打包为 zip，交至用户选择的位置。
     *
     * 状态文本落在 [logExportStatus]（成功/失败各一句），同时记一条 FileLog：
     * 导出的目的在于排查问题，成败均需可在日志中回溯。
     */
    fun downloadLogs(uri: Uri) {
        logExportStatus = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { zipLogs(uri) } }
            result.fold(
                onSuccess = { count ->
                    logExportStatus = "已导出 " + count + " 个日志文件"
                    FileLog.log("log", "日志导出成功：" + count + " 个文件 → " + uri)
                },
                onFailure = { e ->
                    logExportStatus = "导出日志失败：" + (e.message ?: e.javaClass.simpleName)
                    FileLog.log("log", "日志导出失败：" + e)
                }
            )
        }
    }

    /** 打包动作本体（IO 线程调）：返回打进去的文件数。 */
    private fun zipLogs(uri: Uri): Int {
        val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "logs")
        // 仅认可 *.log：目录中可能存在其他临时文件，打包既无用途，也易引入不完整内容
        val logs = dir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedBy { it.name }
            .orEmpty()
        val raw = appContext.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("打不开所选位置")
        // ZipOutputStream 关闭时会 flush 并关闭底层流，因此仅包装该层，无需套用 use
        ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
            for (log in logs) {
                zip.putNextEntry(ZipEntry(log.name))
                log.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return logs.size
    }

    /** 重新扫描当前工作区的记忆库，结果写回 [memoryState]（长动作，统一由 runMemoryAction 的超时兜底）。 */
    fun refreshMemory() {
        runMemoryAction { MemoryAdmin.refresh(appContext, workspaceRoot()) }
    }

    /** 检索记忆；空查询直接忽略（内核会以退出码 2 报「--query 必填」，避免一次无效往返）。 */
    fun searchMemory(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        runMemoryAction { MemoryAdmin.search(appContext, workspaceRoot(), query) }
    }

    /** 执行一次维护扫描（遗忘扫描 + 报出待巩固批次），结果由内核决定，此处不解释。 */
    fun runMemoryCycle() {
        runMemoryAction { MemoryAdmin.cycle(appContext, workspaceRoot()) }
    }

    /** 导出可供人阅读的记忆视图。 */
    fun exportMemory(outPath: String = "") {
        runMemoryAction { MemoryAdmin.export(appContext, workspaceRoot(), outPath) }
    }

    /**
     * 记忆页「向量模型」开关。
     *
     * 开启时先快速检测运行依赖，缺失则立即安装；关闭时只写偏好并清空临时状态。
     */
    fun toggleMemoryVector(enabled: Boolean) {
        MemoryKernel.setVectorEnabled(appContext, enabled)
        memoryVectorEnabled = enabled
        vectorGeneration++
        vectorJob?.cancel()
        vectorJob = null
        memoryVectorBusy = false
        memoryVectorStatus = null
        memoryVectorFailed = false
        if (enabled) ensureVectorRuntime(vectorGeneration)
    }

    /** 重启记忆页时清掉上一次的临时状态；安装进行中则保留进度。 */
    fun clearMemoryVectorStatus() {
        if (!memoryVectorBusy) {
            memoryVectorStatus = null
            memoryVectorFailed = false
        }
    }

    /** 检测稠密运行依赖，缺失时安装并复检。 */
    private fun ensureVectorRuntime(generation: Int) {
        vectorJob = scope.launch {
            memoryVectorBusy = true
            memoryVectorStatus = "正在检测运行依赖…"
            memoryVectorFailed = false
            try {
                val check = withContext(Dispatchers.IO) { MemoryKernel.checkDenseRuntime(appContext) }
                if (generation != vectorGeneration) return@launch
                val runtime = check.getOrNull()
                if (runtime == null) {
                    memoryVectorStatus = "依赖检测失败：" + (check.exceptionOrNull()?.message ?: "未知原因")
                    memoryVectorFailed = true
                    return@launch
                }
                if (runtime.ready) {
                    memoryVectorStatus = "依赖正常：" + runtime.detail
                    return@launch
                }
                memoryVectorStatus = "缺少运行依赖，正在安装…"
                val install = withContext(Dispatchers.IO) { MemoryKernel.installDenseRuntime(appContext) }
                if (generation != vectorGeneration) return@launch
                if (install.isFailure) {
                    memoryVectorStatus = install.exceptionOrNull()?.message ?: "依赖安装失败"
                    memoryVectorFailed = true
                    return@launch
                }
                val recheck = withContext(Dispatchers.IO) { MemoryKernel.checkDenseRuntime(appContext) }
                if (generation != vectorGeneration) return@launch
                val ready = recheck.getOrNull()
                if (ready?.ready == true) {
                    memoryVectorStatus = "依赖正常：" + ready.detail
                    memoryVectorFailed = false
                } else {
                    memoryVectorStatus = "依赖安装未通过检测：" +
                        (ready?.detail ?: recheck.exceptionOrNull()?.message ?: "未知原因")
                    memoryVectorFailed = true
                }
            } finally {
                if (generation == vectorGeneration) memoryVectorBusy = false
            }
        }
    }

    /**
     * 记忆动作统一入口：进入 IO 运行内核，返回后整份替换 [memoryState]。
     *
     * 先置 busy 是因为一次动作需运行两三个 CLI，页面需先有反馈；MemoryAdmin 返回的 state
     * 自带 busy=false，因此返回后直接替换即为「结束」状态。
     * 整段包裹 runCatching：铺库/运行内核涉及外部存储与 python3，异常属于常态，
     * 此时应退回「无记忆」继续对话，不可因一次记忆故障导致对话崩溃。
     */
    private fun runMemoryAction(action: suspend () -> MemoryState) {
        memoryState = memoryState.copy(busy = true)
        scope.launch {
            val startedAt = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching { withTimeoutOrNull(MEMORY_ACTION_TIMEOUT_MS) { action() } }
            }
            val elapsed = System.currentTimeMillis() - startedAt
            val done = result.getOrNull()
            if (done != null) {
                FileLog.log("memory", "记忆操作完成：耗时 " + elapsed + " 毫秒")
                memoryState = done
                return@launch
            }
            // 超时与异常均必须恢复 busy。此前没有任何兜底：一次不返回时，
            // 「处理中…」与「扫描中…」将永久留在页面上，且日志中无失败记录。
            val timedOut = result.isSuccess
            val reason = result.exceptionOrNull()
            val msg = if (timedOut) {
                "记忆操作超时（" + (MEMORY_ACTION_TIMEOUT_MS / 1000) + " 秒未返回），已恢复可用，可重试一次"
            } else {
                "记忆操作失败：" + (reason?.message ?: reason?.javaClass?.simpleName ?: "未知原因")
            }
            FileLog.log("memory", msg + "（耗时 " + elapsed + " 毫秒）")
            memoryState = memoryState.copy(busy = false, error = msg)
        }
    }
}
