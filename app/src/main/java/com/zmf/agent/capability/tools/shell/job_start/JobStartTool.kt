package com.zmf.agent.capability.tools.shell.job_start

import android.content.Context
import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolPolicy
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.shell.support.SessionCwd
import com.zmf.agent.infra.terminal.ShellResult
import com.zmf.agent.infra.terminal.TerminalApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * job_start：在后台起一个长任务，立刻返回任务 id，不阻塞当前回合。
 *
 * 第一个（也是目前唯一一个）kind 是**终端命令**：命令由 [TerminalApi.writeJobScript] 落成脚本，
 * 在一条**独占的执行通道**里前台执行，stdout 直接重定向到日志文件；一条监视协程每 [POLL_MS]
 * 把日志新增的字节写入注册表，命令结束即按退出码结算。
 *
 * 任务本体必须「前台」执行：在本机 proot 中把命令转入后台后，会话仍被该命令占用，
 * 无法取得结束信号，工具调用将持续挂起。独占通道加前台执行可换得确定的结果返回值；
 * **实时输出**由读取日志文件实现，无需等待命令结束，执行期间仍可读取增量。
 *
 * 时限：默认**不设**上限（编译、下载、启动常驻服务通常耗时较长），需要上限时传 `timeout_ms`，
 * 到点先结算失败、再终止进程组。
 *
 * 权限档 WORKSPACE：起进程是有副作用的动作，且终端命令本身需要工作区档。
 */
class JobStartTool : Tool {

    override val name = "job_start"

    override val mutates = true

    override val description =
        "在后台启动一个长任务并立刻返回任务 id，不阻塞当前回合。" +
            "适用于编译、下载、常驻服务等耗时数分钟以上的任务；数秒内完成的命令改用 terminal。" +
            "只支持 kind=terminal，单会话最多 10 条同时运行；工作区档可用，可不设时限。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "kind": {"type": "string", "description": "任务类型，目前只支持 terminal，默认 terminal"},
            "command": {"type": "string", "description": "要在后台执行的 shell 命令，例如 make 或 python train.py"},
            "cwd": {"type": "string", "description": "工作目录（guest 绝对路径）；未传时用工作区终端目录（工作区不可映射时为 /root）"},
            "label": {"type": "string", "description": "可选：一句话说明这个任务在做什么，job_list 里会显示；默认取命令前 60 字"},
            "timeout_ms": {"type": "number", "description": "可选：最长运行时间（毫秒），默认 0 表示不设上限，最大 21600000；到点先结算失败再终止进程"}
          },
          "required": ["kind", "command"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val kind = args.optString("kind", "").trim().ifEmpty { "terminal" }
        if (kind != "terminal") {
            return ToolResult.Error("[E_INVALID] 目前只支持 kind=terminal 的后台任务，收到的是：" + kind)
        }
        val command = args.optString("command", "").trim()
        if (command.isEmpty()) return ToolResult.Error("[E_INVALID] 缺少 command：请给出要在后台执行的命令")
        // 解析优先级：cwd 参数 > 工作区派生的终端目录（不可映射时为 /root），与 terminal 同链；
        // 显式目录参数含 `..` 段即越界，与 terminal 同一回执、同样不启动命令
        val cwd = SessionCwd.resolveGuest(ctx, args.optString("cwd", ""))
            ?: return ToolResult.Error(SessionCwd.ERR_OUTSIDE)
        val label = args.optString("label", "").trim()

        // 终端命令的高危与越界判定与 TerminalTool 一致：后台执行的命令同样要过闸，不得因「后台」而绕过
        val risk = HighRiskGate.checkTerminal(ctx, command, cwd)
        if (!risk.allowed) {
            return ToolResult.Error(risk.message ?: "[E_PROTECTED] 终端命令被高危闸门拒绝；请改写命令后重试")
        }
        val decision = ToolPolicy.checkTerminal(ctx.permission, command)
        if (!decision.allowed) {
            return ToolResult.Error(decision.reason ?: ToolPolicy.deniedText(ctx.permission, "终端"))
        }

        // 开启 SSH 后命令会发往远端，与「App 内 Ubuntu」语义不符，与 TerminalTool 一致直接拒绝
        val sshEnabled = ctx.context
            .getSharedPreferences("ssh_config", android.content.Context.MODE_PRIVATE)
            .getBoolean("ssh_enabled", false)
        if (sshEnabled) {
            return ToolResult.Error("[E_UNSUPPORTED] 当前开启了 SSH 远程终端，命令会发往远端；请先在终端设置里关闭 SSH 后重试")
        }

        val appContext = ctx.context.applicationContext
        if (!TerminalApi.ensureReady(appContext)) {
            return ToolResult.Error(
                "[E_SERVICE_UNAVAILABLE] 后台任务未能启动：终端环境尚未就绪；请先在侧边栏打开一次「终端」完成 Ubuntu 初始化后重试。"
            )
        }

        val id = JobRegistry.begin(ctx.sessionId, kind, label.ifBlank { command.take(60) })
            ?: return ToolResult.Error(
                "[E_BUSY] 这个会话的后台任务已经排满（最多 10 条同时运行）。先用 job_list 看现有任务，" +
                    "结束或停掉不需要的再启动；其他会话的任务不受影响。"
            )
        val scriptPath = TerminalApi.writeJobScript(appContext, id, command, cwd)
        if (scriptPath == null) {
            JobRegistry.fail(id, "启动失败：任务脚本写不出来")
            return ToolResult.Error("[E_WRITE] 后台任务未能启动：任务脚本写不出来（App 私有目录不可写）")
        }

        val timeoutMs = args.optLong("timeout_ms", 0L).coerceIn(0L, MAX_TIMEOUT_MS)
        JobRegistry.scope.launch { supervise(appContext, id, scriptPath, timeoutMs) }

        FileLog.log(TAG, "起后台任务 " + id + "：cd " + cwd + " && " + command.take(120))
        return ToolResult.Ok(
            "已在后台启动任务 " + id + "；用 job_output 读实时输出，job_list 看状态，job_kill 停止，完成时会收到通知。"
        )
    }

    /**
     * 监视一个任务直到结束：一边搬日志增量，一边等执行通道返回。
     *
     * 两件事必须并行：命令耗时无法预知，而输出随时可能被读取。
     * 执行结果用 [CompletableDeferred] 交给本协程，循环每 [POLL_MS] 顺便看一眼是否已结束。
     */
    private suspend fun supervise(context: Context, id: String, scriptPath: String, timeoutMs: Long) {
        val result = CompletableDeferred<ShellResult?>()
        // 通道级兜底：给到「不设上限」时的最大值，真正的时限由下面的看门狗负责
        val channelTimeout = if (timeoutMs > 0) timeoutMs else MAX_TIMEOUT_MS
        JobRegistry.scope.launch {
            val r = runCatching { TerminalApi.runJob(context, id, scriptPath, channelTimeout) }.getOrNull()
            result.complete(r)
        }

        var offset = 0L
        var settled = false
        val deadline = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else 0L
        while (currentCoroutineContext().isActive) {
            val (chunk, next) = TerminalApi.readJobLog(context, id, offset)
            offset = next
            if (chunk.isNotEmpty()) JobRegistry.append(id, chunk)

            val done = withTimeoutOrNull(POLL_MS) { result.await() }
            if (done != null || result.isCompleted) {
                // 收尾：将最后一段输出完整读取后再结算，避免结算时遗漏结尾若干行
                val (tail, _) = TerminalApi.readJobLog(context, id, offset)
                if (tail.isNotEmpty()) JobRegistry.append(id, tail)
                settle(id, result.await())
                settled = true
                break
            }

            if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                JobRegistry.fail(id, "超过 " + (timeoutMs / 60_000L) + " 分钟仍未结束，已终止")
                TerminalApi.killJob(context, id)
                FileLog.log(TAG, "任务 " + id + " 超时终止")
                settled = true
                break
            }
        }
        // 任务结束即归还独占通道：否则每启动一个任务都会多留一个常驻 Ubuntu 子进程
        TerminalApi.releaseJobChannel(context, id)
        if (!settled) {
            // 协程被取消（例如进程要退出了）：不结算，但把已知状态留在日志里
            FileLog.log(TAG, "任务 " + id + " 监视结束（未结算）")
        }
    }

    /**
     * 按退出码结算。
     *
     * 非 0 一律记为失败并写明退出码：常见的是 127（命令不存在）与 137（被终止），
     * 模型据此区分命令写错与被终止。
     */
    private fun settle(id: String, result: ShellResult?) {
        when (result) {
            is ShellResult.Ok -> {
                if (result.exitCode == 0) JobRegistry.complete(id, "exit=0")
                else JobRegistry.fail(id, "exit=" + result.exitCode)
                FileLog.log(TAG, "任务 " + id + " 结算 exit=" + result.exitCode)
            }
            ShellResult.ShellNotReady -> {
                JobRegistry.fail(id, "终端环境未初始化")
                FileLog.log(TAG, "任务 " + id + " 结算：终端环境未初始化")
            }
            is ShellResult.Failed -> {
                JobRegistry.fail(id, "执行失败：" + result.reason)
                FileLog.log(TAG, "任务 " + id + " 结算：" + result.reason)
            }
            null -> {
                JobRegistry.fail(id, "执行通道异常退出，未取得退出码")
                FileLog.log(TAG, "任务 " + id + " 结算：无结果")
            }
        }
    }

    private companion object {
        const val TAG = "jobs"

        /** 不设上限时给执行通道的兜底时限，也是单任务允许的最长时间（6 小时）。 */
        const val MAX_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        /** 监视间隔：日志为普通文件读取，成本极低；400 毫秒即可满足「实时输出」的时效要求。 */
        const val POLL_MS = 400L
    }
}
