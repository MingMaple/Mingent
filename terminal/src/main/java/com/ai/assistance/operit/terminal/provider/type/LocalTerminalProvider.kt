/*
 * 本文件源自 Operit AI 项目（https://github.com/AAswordman/Operit）。
 * Copyright (C) 2023-2024 Operit AI 项目团队
 *
 * 依 GNU Lesser General Public License v3.0 授权使用与分发，
 * 许可证全文见仓库根目录 terminal/LICENSE；Mingent 对其所做的修改
 * 同样以 LGPL-3.0 提供。
 */

package com.ai.assistance.operit.terminal.provider.type

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.ai.assistance.operit.terminal.Pty
import com.ai.assistance.operit.terminal.TerminalSession
import com.ai.assistance.operit.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.LocalFileSystemProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 起壳超时：与取消分开成不同异常，日志与回执才能分辨「等不到进程启动」与「用户主动停止」。
 *
 * 不继承 CancellationException：后者会被协程层当作取消继续上抛，而这里是可重试的失败，
 * 要把可读原因交回工具调用方。
 */
private class HiddenExecShellStartTimeoutException(executorKey: String, timeoutMs: Long) :
    java.io.IOException("隐藏执行 shell 起壳超时（" + timeoutMs + " 毫秒），已释放半成品：" + executorKey)

/**
 * 本地终端提供者
 *
 * 使用 proot + Ubuntu 环境提供本地 Linux 终端。
 * 可见终端继续走 PTY，不可见执行走后台复用 shell。
 */
class LocalTerminalProvider(
    private val context: Context
) : TerminalProvider {

    private data class HiddenExecShell(
        val key: String,
        val process: Process,
        val writer: java.io.BufferedWriter,
        val outputChannel: Channel<String>,
        val readJob: kotlinx.coroutines.Job,
        val mutex: Mutex = Mutex()
    )

    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val binDir: File = File(usrDir, "bin")
    private val nativeLibDir: String = context.applicationInfo.nativeLibraryDir

    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    private val hiddenExecShells = ConcurrentHashMap<String, HiddenExecShell>()
    private val hiddenExecShellMutex = Mutex()
    private val hiddenExecScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fileSystemProvider = LocalFileSystemProvider(context)

    companion object {
        private const val TAG = "LocalTerminalProvider"
        private const val READY_MARKER = "TERMINAL_READY"
        private const val BEGIN_MARKER_PREFIX = "__OPERIT_HIDDEN_BEGIN__:"
        private const val END_MARKER_PREFIX = "__OPERIT_HIDDEN_END__:"
        private const val PID_MARKER_PREFIX = "__OPERIT_HIDDEN_PID__:"
        private const val HIDDEN_EXEC_CANCEL_SETTLE_TIMEOUT_MS = 3_000L

        /** 关闭通道时等待 shell 自行退出的上限；超过就强制结束，避免取消路径被僵死的进程拖住。 */
        private const val HIDDEN_EXEC_DESTROY_GRACE_MS = 2_000L

        /**
         * 起壳（fork proot 子进程）的等待上限。
         *
         * 为什么需要上限：`ProcessBuilder.start()` 是阻塞调用，既不响应协程取消，也不保证返回；
         * 实测在设备负载高时曾悬挂 90 秒以上，把子智能体和它占着的执行通道一起挂住。
         * 15 秒足够覆盖正常启动（就绪标记另由 [awaitHiddenExecReady] 的 30 秒上限管）。
         */
        private const val HIDDEN_EXEC_START_TIMEOUT_MS = 15_000L

        /**
         * 某条通道**首次**起壳的等待上限：冷启动首次挂载 PRoot 根文件系统可能超过 15 秒。
         *
         * 依据：新建会话首条命令曾报「隐藏执行 shell 起壳超时（15000 毫秒）」，模型原样重试即成功
         * （T313-B2A，17:35:34），说明首次挂载耗时落在 15 秒以上、且第二次已命中缓存。
         * 只对「该通道从未成功拉起过 shell」的首次放宽，成功拉起一次后仍走 15 秒，不放大常驻风险。
         */
        private const val HIDDEN_EXEC_FIRST_START_TIMEOUT_MS = 60_000L
    }

    /** 已成功拉起过 shell 的通道键：据此区分「首次起壳」与后续起壳。 */
    private val startedExecKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun isConnected(): Boolean {
        return true
    }

    override suspend fun connect(): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        activeSessions.keys.toList().forEach { sessionId ->
            closeSession(sessionId)
        }
        hiddenExecShells.keys.toList().forEach { key ->
            closeHiddenExecShell(key)
        }
        hiddenExecScope.cancel()
    }

    override suspend fun closeHiddenExecutor(executorKey: String) {
        closeHiddenExecShell(executorKey)
    }

    override suspend fun startSession(sessionId: String): Result<Pair<TerminalSession, Pty>> {
        return withContext(Dispatchers.IO) {
            try {
                val command = buildVisibleSessionCommand()
                val env = buildEnvironment()

                Log.d(TAG, "Starting local terminal session with command: ${command.joinToString(" ")}")
                Log.d(TAG, "Environment: $env")

                val pty = Pty.start(command, env, filesDir)

                val session = TerminalSession(
                    process = pty.process,
                    stdout = pty.stdout,
                    stdin = pty.stdin
                )

                activeSessions[sessionId] = session
                Result.success(Pair(session, pty))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start local terminal session", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun closeSession(sessionId: String) {
        activeSessions[sessionId]?.let { session ->
            session.process.destroy()
            activeSessions.remove(sessionId)
            Log.d(TAG, "Closed local terminal session: $sessionId")
        }
    }

    override suspend fun executeHiddenCommand(
        command: String,
        executorKey: String,
        timeoutMs: Long
    ): HiddenExecResult {
        val shell =
            try {
                getOrCreateHiddenExecShell(executorKey)
            } catch (e: CancellationException) {
                // 取消必须在起壳阶段也原样上抛：折成 SHELL_START_FAILED 会让上层把「被停止」
                // 当成一次普通执行失败而继续下一轮。
                Log.d(TAG, "Hidden exec shell preparation cancelled: $executorKey")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare hidden exec shell: $executorKey", e)
                return HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.SHELL_START_FAILED,
                    error = e.message ?: "Failed to prepare hidden exec shell"
                )
            }

        var commandStarted = false
        // 本条命令的进程组号：从输出里的 pid 标记解析，取消或超时时据此终止整棵进程树
        val commandPid = AtomicLong(0L)
        return try {
            withTimeout(timeoutMs) {
                shell.mutex.withLock {
                    commandStarted = true
                    val token = UUID.randomUUID().toString()
                    Log.d(TAG, "Hidden exec command started: key=$executorKey token=$token timeoutMs=$timeoutMs command=${command.lineSequence().firstOrNull().orEmpty().take(200)}")
                    val wrappedCommand = buildHiddenExecEnvelope(command, token)
                    withContext(Dispatchers.IO) {
                        shell.writer.write(wrappedCommand)
                        shell.writer.flush()
                    }
                    collectHiddenExecResult(shell, token, timeoutMs, commandPid)
                }
            }
        } catch (e: TimeoutCancellationException) {
            if (commandStarted) {
                // 命令已写进这条 shell：先终止进程组再关掉通道，确保超时后没有残留进程
                withContext(NonCancellable) {
                    terminateHiddenExec(executorKey, commandPid.get())
                }
            } else {
                // 只是排队等锁就超时，命令还没写进去：此时关壳会连带杀死同一通道上正在执行的上一条命令，
                // 让它以 PROCESS_EXITED 收场（C5c 并发排查的候选成因）。留着 shell，只回报本次超时。
                Log.w(TAG, "Hidden exec wait timed out before start, shell kept: $executorKey")
            }
            HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.TIMEOUT,
                error = "Hidden exec command timed out after ${timeoutMs}ms"
            )
        } catch (e: CancellationException) {
            // 取消（用户停止回合 / 父级连带中止子智能体）必须原样上抛：
            // 若在这里折成一次普通执行失败，上层会把「被取消」当成工具调用失败而继续下一轮，
            // 迟到的结论还会盖掉已经写下的「已取消」状态。
            // 收尾放在 NonCancellable 里同步做完：命令用 setsid 自成进程组，只关 shell 会留下孤儿进程。
            if (commandStarted) {
                withContext(NonCancellable) {
                    terminateHiddenExec(executorKey, commandPid.get())
                }
            }
            Log.d(TAG, "Hidden exec command cancelled: $executorKey")
            throw e
        } catch (e: Exception) {
            hiddenExecScope.launch {
                closeHiddenExecShell(executorKey)
            }
            Log.e(TAG, "Failed to execute hidden command in shell: $executorKey", e)
            HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.EXECUTION_ERROR,
                error = e.message ?: "Failed to execute hidden command"
            )
        }
    }

    override fun getFileSystemProvider(): FileSystemProvider {
        return fileSystemProvider
    }

    override suspend fun getWorkingDirectory(): String {
        return filesDir.absolutePath
    }

    override fun getEnvironment(): Map<String, String> {
        return buildEnvironment()
    }

    private suspend fun getOrCreateHiddenExecShell(executorKey: String): HiddenExecShell {
        hiddenExecShells[executorKey]?.let { existing ->
            if (existing.process.isAlive) {
                return existing
            }
            closeHiddenExecShell(executorKey)
        }

        return hiddenExecShellMutex.withLock {
            hiddenExecShells[executorKey]?.let { existing ->
                if (existing.process.isAlive) {
                    return@withLock existing
                }
                closeHiddenExecShell(executorKey)
            }

            val created = createHiddenExecShell(executorKey)
            hiddenExecShells[executorKey] = created
            created
        }
    }

    private suspend fun createHiddenExecShell(executorKey: String): HiddenExecShell {
        val process = startHiddenExecProcess(executorKey)
        val outputChannel = Channel<String>(Channel.UNLIMITED)
        val readJob =
            hiddenExecScope.launch {
                val input = process.inputStream
                val buffer = ByteArray(4096)
                try {
                    while (isActive) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        if (count > 0) {
                            outputChannel.send(String(buffer, 0, count, Charsets.UTF_8))
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Hidden exec reader failed for $executorKey", e)
                    }
                } finally {
                    outputChannel.close()
                }
            }

        val shell =
            HiddenExecShell(
                key = executorKey,
                process = process,
                writer = process.outputStream.bufferedWriter(Charsets.UTF_8),
                outputChannel = outputChannel,
                readJob = readJob
            )

        val readyResult = awaitHiddenExecReady(shell)
        if (!readyResult.isOk) {
            closeHiddenExecShell(executorKey)
            throw IllegalStateException(
                readyResult.error.ifBlank { "Hidden exec shell did not become ready" }
            )
        }

        Log.d(TAG, "Created hidden exec shell: $executorKey")
        return shell
    }

    /**
     * 起一条隐藏执行 shell，并给「起壳」这一步加有界等待。
     *
     * 阻塞的 `ProcessBuilder.start()` 放进独立协程执行，本协程只等
     * [HIDDEN_EXEC_START_TIMEOUT_MS]；超时或取消时把交接位上的进程就地销毁，
     * 迟到的进程也在落地时自行销毁，因此不会留下没有读线程的半成品 shell。
     * 常规路径仍是「一次 start + 就绪标记」，没有额外的常驻探针。
     */
    private suspend fun startHiddenExecProcess(executorKey: String): Process {
        val handoff = AtomicReference<Process?>(null)
        val abandoned = AtomicBoolean(false)
        val started = CompletableDeferred<Process>()
        val firstStart = !startedExecKeys.contains(executorKey)
        val startTimeoutMs =
            if (firstStart) HIDDEN_EXEC_FIRST_START_TIMEOUT_MS else HIDDEN_EXEC_START_TIMEOUT_MS
        val startBeganAt = System.currentTimeMillis()

        hiddenExecScope.launch(Dispatchers.IO) {
            try {
                val process =
                    buildProcessBuilder(
                        command = buildHiddenExecStartupCommand(),
                        redirectErrorStream = true
                    ).start()
                handoff.set(process)
                if (abandoned.get()) {
                    discardLateHiddenExecProcess(handoff, executorKey)
                } else {
                    started.complete(process)
                }
            } catch (t: Throwable) {
                started.completeExceptionally(t)
            }
        }

        val process =
            try {
                withTimeoutOrNull(startTimeoutMs) { started.await() }
            } catch (e: CancellationException) {
                // 取消（用户停止回合 / 父级连带中止子智能体）：原样上抛，只顺手把半成品放掉
                abandoned.set(true)
                discardLateHiddenExecProcess(handoff, executorKey)
                Log.d(TAG, "Hidden exec shell start cancelled: $executorKey")
                throw e
            }

        if (process != null) {
            if (firstStart) {
                // 首次起壳放宽上限并留证：实测耗时是判断冷启动是否仍超限的唯一依据
                startedExecKeys.add(executorKey)
                Log.i(
                    TAG,
                    "First hidden exec shell start relaxed limit: key=$executorKey " +
                        "elapsedMs=${System.currentTimeMillis() - startBeganAt} limitMs=$startTimeoutMs"
                )
            }
            return process
        }

        abandoned.set(true)
        discardLateHiddenExecProcess(handoff, executorKey)
        Log.e(
            TAG,
            "Hidden exec shell start timed out after ${startTimeoutMs}ms: $executorKey"
        )
        throw HiddenExecShellStartTimeoutException(executorKey, startTimeoutMs)
    }

    /**
     * 放掉一份「已经没人等」的起壳结果。
     *
     * 用交接位的原子交换保证同一进程只被一个人销毁：等待方放弃与迟到者落地可能同时发生，
     * 谁先取到谁负责 `destroy()`，另一方拿到 null 直接返回，不重复打日志。
     */
    private fun discardLateHiddenExecProcess(
        handoff: AtomicReference<Process?>,
        executorKey: String
    ) {
        handoff.getAndSet(null)?.let { process ->
            runCatching { process.destroy() }
            Log.w(TAG, "Hidden exec shell start abandoned, process destroyed: $executorKey")
        }
    }

    private suspend fun awaitHiddenExecReady(shell: HiddenExecShell): HiddenExecResult {
        return try {
            val rawOutput: String =
                withTimeout(30000L) {
                    val builder = StringBuilder()
                    while (true) {
                        val chunk =
                            shell.outputChannel.receiveCatching().getOrNull()
                                ?: break
                        logHiddenExecChunk(shell.key, "ready", chunk)
                        builder.append(chunk)
                        if (builder.indexOf(READY_MARKER) >= 0) {
                            break
                        }
                    }
                    builder.toString()
                }

            if (rawOutput.contains(READY_MARKER)) {
                HiddenExecResult(output = "", exitCode = 0)
            } else if (!shell.process.isAlive) {
                HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.PROCESS_EXITED,
                    error = "Hidden exec shell exited before ready",
                    rawOutputPreview = rawOutput.takeLast(1200)
                )
            } else {
                HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.SHELL_NOT_READY,
                    error = "Hidden exec shell did not emit ready marker",
                    rawOutputPreview = rawOutput.takeLast(1200)
                )
            }
        } catch (e: TimeoutCancellationException) {
            HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.TIMEOUT,
                error = "Timed out while waiting for hidden exec shell to become ready"
            )
        }
    }

    private suspend fun collectHiddenExecResult(
        shell: HiddenExecShell,
        token: String,
        timeoutMs: Long,
        commandPid: AtomicLong
    ): HiddenExecResult {
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val deadline = System.currentTimeMillis() + timeoutMs
        val builder = StringBuilder()

        while (System.currentTimeMillis() < deadline) {
            val chunk =
                withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1L)) {
                    shell.outputChannel.receiveCatching().getOrNull()
                } ?: break
            logHiddenExecChunk(shell.key, token, chunk)
            builder.append(chunk)
            // pid 标记紧跟在命令启动之后输出；先记下来，取消或超时才有目标可终止
            if (commandPid.get() <= 0L) {
                extractHiddenExecPid(builder.toString(), token)?.takeIf { it > 0L }?.let { commandPid.set(it) }
            }
            if (builder.indexOf(endMarkerPrefix) >= 0) {
                break
            }
        }

        val rawOutput = builder.toString()
        if (rawOutput.indexOf(endMarkerPrefix) >= 0) {
            return parseHiddenExecOutput(rawOutput, token)
        }

        if (!shell.process.isAlive) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.PROCESS_EXITED,
                error = "Hidden exec shell exited while executing command",
                rawOutputPreview = rawOutput.takeLast(1200)
            )
        }

        // 超时：终止命令的进程组（SIGTERM 后 SIGKILL）。通道本身保留复用，下一条命令不必重新起 shell。
        val pid = commandPid.get()
        if (pid > 0L) {
            killHiddenExecProcessGroup(pid)
        } else {
            cancelHiddenExecCommand(rawOutput, token)
        }
        return HiddenExecResult(
            output = extractHiddenExecOutput(rawOutput, token),
            exitCode = -1,
            state = HiddenExecResult.State.TIMEOUT,
            error = "Hidden exec command timed out after ${timeoutMs}ms",
            rawOutputPreview = rawOutput.takeLast(1200)
        )
    }

    private fun parseHiddenExecOutput(
        rawOutput: String,
        token: String
    ): HiddenExecResult {
        val beginMarker = "$BEGIN_MARKER_PREFIX$token"
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val beginIndex = rawOutput.indexOf(beginMarker)
        if (beginIndex < 0) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.MISSING_BEGIN_MARKER,
                error = "Hidden exec output missing begin marker",
                rawOutputPreview = rawOutput.takeLast(1200)
            )
        }

        val payloadStart = beginIndex + beginMarker.length
        val endIndex = rawOutput.indexOf(endMarkerPrefix, payloadStart)
        if (endIndex < 0) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.MISSING_END_MARKER,
                error = "Hidden exec output missing end marker",
                rawOutputPreview = rawOutput.takeLast(1200)
            )
        }

        val commandOutput = extractHiddenExecOutput(rawOutput, token)

        val exitCodeText =
            rawOutput
                .substring(endIndex + endMarkerPrefix.length)
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: return HiddenExecResult(
                    output = commandOutput,
                    exitCode = -1,
                    state = HiddenExecResult.State.INVALID_EXIT_CODE,
                    error = "Hidden exec output missing exit code",
                    rawOutputPreview = rawOutput.takeLast(1200)
                )

        val exitCode =
            exitCodeText.toIntOrNull()
                ?: return HiddenExecResult(
                    output = commandOutput,
                    exitCode = -1,
                    state = HiddenExecResult.State.INVALID_EXIT_CODE,
                    error = "Invalid hidden exec exit code: $exitCodeText",
                    rawOutputPreview = rawOutput.takeLast(1200)
                )

        return HiddenExecResult(
            output = commandOutput,
            exitCode = exitCode
        )
    }

    private suspend fun collectHiddenExecTimeoutOutput(
        shell: HiddenExecShell,
        token: String,
        initialRawOutput: String
    ): String {
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val builder = StringBuilder(initialRawOutput)
        withTimeoutOrNull(HIDDEN_EXEC_CANCEL_SETTLE_TIMEOUT_MS) {
            while (builder.indexOf(endMarkerPrefix) < 0) {
                val chunk =
                    shell.outputChannel.receiveCatching().getOrNull()
                        ?: break
                builder.append(chunk)
            }
        }
        return builder.toString()
    }

    private fun cancelHiddenExecCommand(rawOutput: String, token: String) {
        val pid = extractHiddenExecPid(rawOutput, token) ?: return
        killHiddenExecProcessGroup(pid)
    }

    /**
     * 向命令的进程组发送终止信号：先 `SIGTERM` 让命令自行收尾，短暂等待后再 `SIGKILL` 兜底。
     *
     * 组号来自输出里的 pid 标记（命令以 `setsid` 启动，自身即进程组长），因此只作用于**这一条**命令，
     * 不会波及其他会话或其他通道上正在执行的命令。
     */
    private fun killHiddenExecProcessGroup(pid: Long) {
        if (pid <= 0L) return
        val group = (-pid).toInt()
        runCatching {
            Os.kill(group, OsConstants.SIGTERM)
        }.onFailure { error ->
            Log.w(TAG, "Failed to send SIGTERM to hidden exec process group $pid", error)
        }
        Thread.sleep(100)
        runCatching {
            Os.kill(group, OsConstants.SIGKILL)
        }.onFailure { error ->
            Log.w(TAG, "Failed to send SIGKILL to hidden exec process group $pid", error)
        }
    }

    /**
     * 取消或超时后的收尾：先终止命令的进程组，再关闭这条通道的 shell。
     *
     * 两步都不能省：命令由 `setsid` 放进独立进程组，只关 shell 会留下孤儿进程；
     * 只杀进程组而不关 shell，则这条通道会带着半死的状态留给下一条命令。
     */
    private suspend fun terminateHiddenExec(executorKey: String, pid: Long) {
        killHiddenExecProcessGroup(pid)
        closeHiddenExecShell(executorKey)
    }

    private fun extractHiddenExecPid(rawOutput: String, token: String): Long? {
        val pidMarkerPrefix = "$PID_MARKER_PREFIX$token:"
        val markerIndex = rawOutput.indexOf(pidMarkerPrefix)
        if (markerIndex < 0) {
            return null
        }
        val pidText =
            rawOutput
                .substring(markerIndex + pidMarkerPrefix.length)
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
        return pidText?.toLongOrNull()
    }

    private fun extractHiddenExecOutput(rawOutput: String, token: String): String {
        val beginMarker = "$BEGIN_MARKER_PREFIX$token"
        val beginIndex = rawOutput.indexOf(beginMarker)
        if (beginIndex < 0) {
            return ""
        }
        val payloadStart = beginIndex + beginMarker.length
        val endMarkerPrefix = "$END_MARKER_PREFIX$token:"
        val endIndex = rawOutput.indexOf(endMarkerPrefix, payloadStart).let { index ->
            if (index >= 0) index else rawOutput.length
        }
        return rawOutput
            .substring(payloadStart, endIndex)
            .lineSequence()
            .filterNot { it.startsWith("$PID_MARKER_PREFIX$token:") }
            .joinToString("\n")
            .trimStart('\n', '\r')
            .trimEnd('\n', '\r')
    }

    private suspend fun closeHiddenExecShell(executorKey: String) {
        hiddenExecShells.remove(executorKey)?.let { shell ->
            withContext(Dispatchers.IO) {
                runCatching { shell.writer.close() }
                runCatching { shell.process.destroy() }
                // 先礼后兵：短等之后仍未退出就强制结束，避免取消路径被僵死的 shell 拖住
                runCatching {
                    if (!shell.process.waitFor(HIDDEN_EXEC_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
                        shell.process.destroyForcibly()
                    }
                }
                runCatching { shell.readJob.cancel() }
                runCatching { shell.outputChannel.close() }
            }
            Log.d(TAG, "Closed hidden exec shell: $executorKey")
        }
    }

    private fun buildVisibleSessionCommand(): Array<String> {
        val bash = File(binDir, "bash").absolutePath
        val startScript = "source \$HOME/common.sh && start_shell"
        return arrayOf(bash, "-c", startScript)
    }

    private fun buildHiddenExecStartupCommand(): Array<String> {
        val bash = File(binDir, "bash").absolutePath
        val startScript = "source \$HOME/common.sh && login_ubuntu '/bin/bash --noprofile --norc'"
        return arrayOf(bash, "-c", startScript)
    }

    private fun buildHiddenExecEnvelope(command: String, token: String): String {
        val normalizedCommand = command.replace("\r\n", "\n").replace("\r", "\n")
        val tokenSuffix = token.replace("-", "")
        val heredocMarker = "__OPERIT_HIDDEN_CMD_${tokenSuffix}__"
        return buildString {
            append("printf '%s\\n' '$BEGIN_MARKER_PREFIX$token'\n")
            append("__operit_hidden_script=\"\${TMPDIR:-/tmp}/operit_hidden_$tokenSuffix.sh\"\n")
            append("cat >\"\$__operit_hidden_script\" <<'$heredocMarker'\n")
            append(normalizedCommand)
            if (!normalizedCommand.endsWith("\n")) {
                append('\n')
            }
            append("$heredocMarker\n")
            append("setsid /bin/bash --noprofile --norc \"\$__operit_hidden_script\" </dev/null &\n")
            append("__operit_hidden_pid=\$!\n")
            append("printf '%s:%s\\n' '$PID_MARKER_PREFIX$token' \"\$__operit_hidden_pid\"\n")
            append("wait \"\$__operit_hidden_pid\"\n")
            append("__operit_hidden_rc=\$?\n")
            append("rm -f \"\$__operit_hidden_script\"\n")
            append("printf '%s:%s\\n' '$END_MARKER_PREFIX$token' \"\$__operit_hidden_rc\"\n")
        }
    }

    private fun logHiddenExecChunk(
        executorKey: String,
        token: String,
        chunk: String
    ) {
        chunk.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                line.chunked(3000).forEach { part ->
                    Log.d(TAG, "Hidden exec output [$executorKey][$token]: $part")
                }
            }
    }

    private fun buildProcessBuilder(
        command: Array<String>,
        redirectErrorStream: Boolean
    ): ProcessBuilder {
        val processBuilder = ProcessBuilder(*command)
        processBuilder.directory(filesDir)
        processBuilder.redirectErrorStream(redirectErrorStream)
        val environment = processBuilder.environment()
        environment.clear()
        environment.putAll(buildEnvironment())
        return processBuilder
    }

    private fun buildEnvironment(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["PATH"] = "${binDir.absolutePath}:${System.getenv("PATH")}"
        env["HOME"] = filesDir.absolutePath
        env["PREFIX"] = usrDir.absolutePath
        env["TERMUX_PREFIX"] = usrDir.absolutePath
        env["LD_LIBRARY_PATH"] = "${nativeLibDir}:${binDir.absolutePath}"
        env["PROOT_LOADER"] = File(binDir, "loader").absolutePath
        env["TMPDIR"] = File(filesDir, "tmp").absolutePath
        env["PROOT_TMP_DIR"] = File(filesDir, "tmp").absolutePath
        env["TERM"] = "xterm-256color"
        env["LANG"] = "en_US.UTF-8"
        return env
    }
}
