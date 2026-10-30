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
 * 从 [ChatViewModel] 搬出，行为不变：状态字段名与原成员逐字一致，方法体原样照搬；
 * 协程域与当前工作区目录由构造参数注入，不反向攥住 ViewModel。
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

    /** 向量模型开关（记忆页「向量模型」按钮）：只影响内核是否启用稠密通道，默认关。 */
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
     * 把 logs 目录下的日志打成 zip 交给用户选的位置。
     *
     * 状态文本落在 [logExportStatus]（成功/失败各一句），同时记一条 FileLog：
     * 导出的意义就是拿去排查，成败都要能在日志里回溯。
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
        // 只认 *.log：目录里可能还有别的临时文件，打包进去既没用又容易带出半截内容
        val logs = dir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedBy { it.name }
            .orEmpty()
        val raw = appContext.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("打不开所选位置")
        // ZipOutputStream 关闭时会 flush 并关掉底层流，所以只包这一层，不再套 use
        ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
            for (log in logs) {
                zip.putNextEntry(ZipEntry(log.name))
                log.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return logs.size
    }

    /** 重扫当前工作区的记忆库，结果写回 [memoryState]（长动作，统一走 runMemoryAction 的超时兜底）。 */
    fun refreshMemory() {
        runMemoryAction { MemoryAdmin.refresh(appContext, workspaceRoot()) }
    }

    /** 检索记忆；空查询直接忽略（内核会以退出码 2 报「--query 必填」，白等一次往返）。 */
    fun searchMemory(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        runMemoryAction { MemoryAdmin.search(appContext, workspaceRoot(), query) }
    }

    /** 跑一次维护扫描（遗忘扫描 + 报出待巩固批次），结果由内核决定，这里不解释。 */
    fun runMemoryCycle() {
        runMemoryAction { MemoryAdmin.cycle(appContext, workspaceRoot()) }
    }

    /** 导出给人看的记忆视图。 */
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
     * 记忆动作统一入口：进 IO 跑内核，回来整份替换 [memoryState]。
     *
     * 先置 busy 是因为一次动作要跑两三个 CLI，页面上得先有反馈；MemoryAdmin 返回的 state
     * 自带 busy=false，所以回来直接替换就是「结束」状态。
     * 整段包 runCatching：铺库/跑内核涉及外部存储与 python3，抛异常是常态，
     * 那种情况该退回「无记忆」继续聊，不能让一次记忆故障把对话带崩。
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
            // 超时与异常都必须把 busy 收回来。此前没有任何兜底：一次不返回，
            // 「处理中…」与「扫描中…」就永久留在页面上，且日志里连失败都看不到。
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
