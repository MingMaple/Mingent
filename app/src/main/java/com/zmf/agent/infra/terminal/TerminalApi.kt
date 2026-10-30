package com.zmf.agent.infra.terminal

import android.content.Context
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiUtils

/**
 * 终端执行结果——我们自己的类型。
 *
 * 为什么不在外面直接用上游的 `HiddenExecResult`：它是 vendored 模块的内部类型，
 * 字段名、State 枚举随时可能随上游升级而变。挡在 `infra` 里，升级时只改这一个文件，
 * `capability` 与 `tools` 一行都不用动。
 */
sealed interface ShellResult {

    /** 命令跑完了（退出码非 0 也算跑完，exitCode 由调用方判断）。 */
    data class Ok(val output: String, val exitCode: Int) : ShellResult

    /** Ubuntu 还没初始化完：需要用户先在侧边栏打开一次「终端」。 */
    data object ShellNotReady : ShellResult

    /**
     * 启动失败 / 超时 / 其它异常。
     * [reason] 是上游 State 的名字（如 TIMEOUT），[detail] 是上游给的错误说明，
     * [rawPreview] 是最近一段原始输出（含 ANSI），用于排查「为什么没跑起来」。
     */
    data class Failed(val reason: String, val detail: String, val rawPreview: String) : ShellResult
}

/**
 * 终端能力的唯一入口：全工程只有这里可以 import `com.ai.assistance.operit.*`。
 *
 * 用法：`TerminalApi.exec(context, command, key, timeoutMs)`。上游实现内部会阻塞等待，
 * 调用方必须放在 `withContext(Dispatchers.IO)` 里，不要直接挂在主线程上。
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
     * 去掉 ANSI 控制序列。
     * 上游实现抛异常时退回原文：宁可留几个转义字符，也不能因为格式化失败把整段输出丢掉。
     */
    fun stripAnsi(raw: String): String = runCatching { AnsiUtils.stripAnsi(raw) }.getOrElse { raw }
}
