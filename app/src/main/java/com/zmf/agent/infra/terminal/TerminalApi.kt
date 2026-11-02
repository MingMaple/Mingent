package com.zmf.agent.infra.terminal

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiUtils
import java.io.File
import java.io.RandomAccessFile

/**
 * 终端执行结果，本模块自定义类型。
 *
 * 未直接使用上游的 `HiddenExecResult`：它是 vendored 模块的内部类型，
 * 字段名与 State 枚举可能随上游升级而变化。隔离在 `infra` 后，升级时仅需修改本文件，
 * `capability` 与 `tools` 无需改动。
 */
sealed interface ShellResult {

    /** 命令执行结束（退出码非 0 亦视为结束，exitCode 由调用方判断）。 */
    data class Ok(val output: String, val exitCode: Int) : ShellResult

    /** Ubuntu 尚未初始化完成：需要用户先在侧边栏打开一次「终端」。 */
    data object ShellNotReady : ShellResult

    /**
     * 启动失败 / 超时 / 其它异常。
     * [reason] 是上游 State 的名字（如 TIMEOUT），[detail] 是上游给的错误说明，
     * [rawPreview] 是最近一段原始输出（含 ANSI），用于排查启动失败原因。
     */
    data class Failed(val reason: String, val detail: String, val rawPreview: String) : ShellResult
}

/**
 * 隐藏进程句柄：上层仅负责持有它（防止进程被回收）、写入一行、查询存活状态与关闭，
 * 不需要认识上游的 `HiddenHandle`。
 */
class HiddenProcess internal constructor(
    private val isAliveImpl: () -> Boolean,
    private val writeLineImpl: (String) -> Boolean,
    private val onClose: () -> Unit
) {
    val isAlive: Boolean get() = runCatching { isAliveImpl() }.getOrDefault(false)

    /** 向子进程写入一行；返回是否实际写入（进程已终止或流已关闭时为 false）。 */
    fun writeLine(line: String): Boolean = runCatching { writeLineImpl(line) }.getOrDefault(false)

    fun close() {
        runCatching { onClose() }
    }
}

/**
 * 终端能力的唯一入口：全工程只有这里可以 import `com.ai.assistance.operit.*`。
 *
 * 用法：`TerminalApi.exec(context, command, key, timeoutMs)`。上游实现内部会阻塞等待，
 * 调用方必须置于 `withContext(Dispatchers.IO)`，不得直接在主线程调用。
 */
object TerminalApi {

    suspend fun exec(context: Context, command: String, executorKey: String, timeoutMs: Long): ShellResult {
        val raw = TerminalManager.getInstance(context)
            .executeHiddenCommand(command, executorKey, timeoutMs)
        return when (raw.state) {
            HiddenExecResult.State.OK -> ShellResult.Ok(raw.output, raw.exitCode)
            HiddenExecResult.State.SHELL_NOT_READY -> ShellResult.ShellNotReady
            else -> ShellResult.Failed(raw.state.name, raw.error, raw.rawOutputPreview)
        }
    }

    /**
     * 在隐藏终端里起一条常驻子进程（MCP stdio 用）。
     *
     * [onLine] 是上游读线程回调，调用方须保证线程安全；返回 null 表示命令为空或未能启动。
     */
    fun openProcess(
        context: Context,
        commandLine: String,
        env: Map<String, String>,
        cwd: String,
        onLine: (String) -> Unit,
        onExit: (Int?) -> Unit
    ): HiddenProcess? {
        val handle = TerminalManager.getInstance(context).openHiddenProcess(
            commandLine = commandLine,
            onLine = onLine,
            env = env,
            cwd = cwd,
            onExit = onExit
        ) ?: return null
        return HiddenProcess(
            isAliveImpl = { handle.isAlive },
            writeLineImpl = { handle.writeLine(it) },
            onClose = { handle.close() }
        )
    }

    /**
     * 将一条命令写成可执行的 Ubuntu 脚本，并返回其在 Ubuntu 中的路径；无法写入时返回 null。
     *
     * 命令由 Java 侧原样写入文件，不再经过 shell 的引号与变量展开；
     * 因此命令中的单引号、美元符号与管道均不会被改写。脚本开头两行固定执行两项操作：
     * 记下自己的进程组号（`$$`，供 [killJob] 用），切到工作目录。
     */
    fun writeJobScript(context: Context, jobId: String, command: String, cwd: String): String? {
        if (command.isBlank()) return null
        val dir = jobDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val body = buildString {
            append("#!/bin/bash\n")
            append("# Mingent 后台任务 ").append(jobId).append('\n')
            append("echo \"\$\$\" > \"").append(ubuntuJobFile(jobId, "pid")).append("\"\n")
            append("cd ").append(shellQuote(cwd.ifBlank { DEFAULT_CWD })).append(" 2>/dev/null || cd /root\n")
            append(command).append('\n')
        }
        val file = File(dir, "$jobId.sh")
        return runCatching { file.writeText(body, Charsets.UTF_8) }
            .map { ubuntuJobFile(jobId, "sh") }
            .getOrNull()
    }

    /**
     * 前台跑一个任务的脚本，直到它结束；返回这次执行的终端结果。
     *
     * 采用前台执行的原因：在本机 proot 中，将命令放入后台后无法获得结束信号，
     * 会话仍被该命令占用，工具调用会持续挂起。改为每个任务独占一条执行通道、脚本在前台运行，
     * 结束状态由本次返回值给出，且**输出仍可实时获取**：脚本的 stdout 直接重定向到日志文件，
     * 调用方按字节偏移读取该文件（[readJobLog]），无需等待命令结束。
     *
     * 每个任务用自己的执行通道（`job-<id>`），因此互不排队；[timeoutMs] 是通道级兜底，
     * 到点上游会关掉这条通道，任务随之结束。
     */
    suspend fun runJob(
        context: Context,
        jobId: String,
        scriptPath: String,
        timeoutMs: Long
    ): ShellResult {
        // setsid 使脚本自成进程组：脚本中的 $$ 因此等于组号，[killJob] 向负组号发送信号
        // 即可终止整个进程树（脚本 fork 出的子进程一并终止），不残留孤儿进程。
        val line = "setsid --wait /bin/bash --noprofile --norc " + shellQuote(scriptPath) +
            " > " + shellQuote(ubuntuJobFile(jobId, "log")) +
            " 2>&1 < /dev/null"
        return exec(context, line, jobExecutorKey(jobId), timeoutMs)
    }

    /**
     * 归还任务占用的执行通道。
     *
     * 每个任务独占一条通道（前台运行，不能与其他任务共用），任务结束后必须关闭：
     * 通道背后是一个常驻的 Ubuntu 子进程，不释放会随任务数量持续累积。
     */
    fun releaseJobChannel(context: Context, jobId: String) {
        TerminalManager.getInstance(context).releaseHiddenExecutor(jobExecutorKey(jobId))
    }

    /**
     * 预检：这条终端通道能不能执行命令。
     *
     * `job_start` 先用它确认环境就绪，再向模型回报「已启动」，避免先返回成功而后失败。
     */
    suspend fun ensureReady(context: Context): Boolean =
        exec(context, "true", READY_EXECUTOR_KEY, READY_TIMEOUT_MS) is ShellResult.Ok

    /**
     * 从上次读到的字节位置继续读任务日志。
     *
     * 返回「新增内容」与「现在的字节位置」；文件还没建、或没有新增内容时返回空串。
     * 直接读文件而不走 shell：`/dev/shm/mingent-jobs` 与 App 的 `files/tmp/mingent-jobs` 是同一个目录，
     * 单次 read 为微秒级。
     */
    fun readJobLog(context: Context, jobId: String, fromOffset: Long): Pair<String, Long> {
        val file = jobFile(context, jobId, "log")
        if (!file.isFile) return "" to fromOffset
        return runCatching {
            val length = file.length()
            if (length <= fromOffset) return "" to fromOffset
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(fromOffset)
                val size = (length - fromOffset).coerceAtMost(MAX_READ_BYTES).toInt()
                val buffer = ByteArray(size)
                val read = raf.read(buffer)
                if (read <= 0) {
                    "" to fromOffset
                } else {
                    // 末尾可能恰好截断在多字节字符中间：回退至该字符的起始字节，下一帧继续读取
                    val usable = completeUtf8Length(buffer, read)
                    if (usable <= 0) "" to fromOffset
                    else String(buffer, 0, usable, Charsets.UTF_8) to (fromOffset + usable)
                }
            }
        }.getOrDefault("" to fromOffset)
    }

    /**
     * 这一段字节里「完整 UTF-8 字符」结束于哪个下标。
     *
     * 日志按字节偏移增量读取，切点落在多字节字符中间会产生半个字符。仅会延迟本帧末尾若干字节，
     * 下一帧从同一位置重读，内容不会丢失。
     */
    private fun completeUtf8Length(bytes: ByteArray, length: Int): Int {
        var i = length - 1
        var trailing = 0
        while (i >= 0 && trailing < 3 && (bytes[i].toInt() and 0xC0) == 0x80) {
            i--
            trailing++
        }
        if (i < 0) return length
        val lead = bytes[i].toInt() and 0xFF
        val need = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        return if (length - i >= need) length else i
    }

    /**
     * 终止任务：对进程组先 `SIGTERM`，一秒后 `SIGKILL` 兜底。
     *
     * 组号来自任务脚本开头写的 `<id>.pid`（脚本自己就是进程组长，`$$` 即组号）。
     * 返回是否实际发出信号；任务是否停止由 [runJob] 的返回值给出。
     */
    suspend fun killJob(context: Context, jobId: String): Boolean {
        val pidFile = jobFile(context, jobId, "pid")
        val pid = runCatching { pidFile.readText().trim().toLongOrNull() }.getOrNull() ?: return false
        if (pid <= 0L) return false
        val group = -pid.toInt()
        val sent = runCatching { Os.kill(group, OsConstants.SIGTERM) }.isSuccess
        kotlinx.coroutines.delay(KILL_GRACE_MS)
        runCatching { Os.kill(group, OsConstants.SIGKILL) }
        return sent
    }

    /** 任务的一个伴随文件（Ubuntu 侧 `/dev/shm/mingent-jobs/<id>.<ext>` ⇒ Java 侧 `<files>/tmp/mingent-jobs`）。 */
    private fun jobFile(context: Context, jobId: String, ext: String): File =
        File(jobDir(context), "$jobId.$ext")

    /** 同上，只是给 shell 用的 Ubuntu 侧路径。 */
    private fun ubuntuJobFile(jobId: String, ext: String): String =
        UBUNTU_JOB_DIR + "/" + jobId + "." + ext

    /** 任务的伴随文件目录（Java 侧）。与 Ubuntu 内的 `/dev/shm/mingent-jobs` 是同一个目录。 */
    private fun jobDir(context: Context): File =
        File(File(context.applicationContext.filesDir, "tmp"), "mingent-jobs")

    /** 每个任务独占一条执行通道：任务之间不排队，一条通道被长任务占用也不影响其他工具。 */
    private fun jobExecutorKey(jobId: String): String = "job-" + jobId

    /** Ubuntu 侧的任务目录；它绑定到 App 的 `files/tmp`，两侧看到的是同一批文件。 */
    private const val UBUNTU_JOB_DIR = "/dev/shm/mingent-jobs"

    /** 预检用的执行通道。与 `terminal` 工具分开，避免预检被其他长命令阻塞。 */
    private const val READY_EXECUTOR_KEY = "job-ready"

    private const val DEFAULT_CWD = "/root"

    /** 预检只跑一条 `true`，首次要拉起终端环境，给 20 秒。 */
    private const val READY_TIMEOUT_MS = 20_000L

    /** 终止信号等待上限：SIGTERM 后固定等 1 秒再补 SIGKILL，故留余量在调用方。 */
    private const val KILL_GRACE_MS = 1_000L

    /** 单次增量读取上限：单帧读取过多并无必要，剩余部分在下一帧继续读取。 */
    private const val MAX_READ_BYTES = 256L * 1024L

    /** 单引号包裹；值里的 ' 变成 '\'' —— 路径带空格或引号都不会被外层 shell 拆开。 */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * 去掉 ANSI 控制序列。
     * 上游实现抛出异常时退回原文：宁可保留少量转义字符，也不因格式化失败丢弃整段输出。
     */
    fun stripAnsi(raw: String): String = runCatching { AnsiUtils.stripAnsi(raw) }.getOrElse { raw }
}
