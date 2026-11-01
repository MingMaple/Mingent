package com.zmf.agent.capability.tools.jobs

import android.content.Context
import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolPolicy
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.infra.terminal.ShellResult
import com.zmf.agent.infra.terminal.TerminalApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * job_start：在后台起一个长任务，立刻返回任务 id，不阻塞当前回合。
 *
 * 第一个（也是目前唯一一个）kind 是**终端命令**：命令由 [TerminalApi.writeJobScript] 落成脚本，
 * 在一条**独占的执行通道**里前台跑，stdout 直接重定向到日志文件；一条监视协程每 [POLL_MS]
 * 把日志新增的字节搬进注册表，命令结束就按退出码结算。
 *
 * 为什么任务本体要「前台」：在这台机器的 proot 里，把命令丢到后台之后，会话仍被它占着，
 * 拿不到结束信号，工具调用会一直挂着。独占通道 + 前台跑，换来的是「结束与否」有明确返回值；
 * 而**实时输出**靠的是读日志文件，不必等命令结束，所以「跑的过程中读增量」照样成立。
 *
 * 时限：默认**不设**上限（编译、下载、起服务本来就可能跑很久），要设就传 `timeout_ms`，
 * 到点先结算失败、再终止进程组。
 *
 * 权限档 WORKSPACE：起进程是有副作用的动作，且终端命令本身需要工作区档。
 */
class JobStartTool : Tool {

    override val name = "job_start"

    override val mutates = true

    override val description =
        "在后台启动一个长任务并立刻返回任务 id，不阻塞当前回合。目前支持终端命令（在 App 内 Ubuntu 里跑）。" +
            "跑的过程中可以用 job_output 读到实时增量（可 wait），job_list 看状态，job_kill 真正停掉它。" +
            "适合编译、下载、长时间跑的服务这类需要几分钟才出结果的事；很快就能跑完的命令不要用后台任务。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "kind": {"type": "string", "description": "任务类型，目前只支持 terminal"},
            "command": {"type": "string", "description": "要在后台执行的 shell 命令，例如 make 或 python train.py"},
            "cwd": {"type": "string", "description": "工作目录，执行前先 cd 进去，默认 /root"},
            "label": {"type": "string", "description": "可选：一句话说明这个任务在做什么，job_list 里会显示"},
            "timeout_ms": {"type": "number", "description": "可选：最长运行时间（毫秒），不传表示不设上限；到点先结算失败再终止进程"}
          },
          "required": ["kind", "command"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val kind = args.optString("kind", "").trim().ifEmpty { "terminal" }
        if (kind != "terminal") {
            return ToolResult.Error("目前只支持 kind=terminal 的后台任务，收到的是：" + kind)
        }
        val command = args.optString("command", "").trim()
        if (command.isEmpty()) return ToolResult.Error("缺少 command：要在后台执行什么命令")
        val cwd = args.optString("cwd", "").trim().ifEmpty { DEFAULT_CWD }
        val label = args.optString("label", "").trim()

        // 终端命令的高危与越界判定照搬 TerminalTool：后台跑的命令一样要过闸，不能因为「后台」就绕过去
        val risk = HighRiskGate.checkTerminal(ctx, command, cwd)
        if (!risk.allowed) {
            return ToolResult.Error(risk.message ?: "终端命令被高危闸门拒绝")
        }
        val decision = ToolPolicy.checkTerminal(ctx.permission, command)
        if (!decision.allowed) {
            return ToolResult.Error(decision.reason ?: ToolPolicy.deniedText(ctx.permission, "终端"))
        }

        // 起了 SSH 后命令会走远端，与「App 内 Ubuntu」语义不符，同 TerminalTool 直接挡
        val sshEnabled = ctx.context
            .getSharedPreferences("ssh_config", android.content.Context.MODE_PRIVATE)
            .getBoolean("ssh_enabled", false)
        if (sshEnabled) {
            return ToolResult.Error("当前开启了 SSH 远程终端，命令会被发到远端，请先在终端设置里关闭 SSH")
        }

        val appContext = ctx.context.applicationContext
        if (!TerminalApi.ensureReady(appContext)) {
            return ToolResult.Error(
                "后台任务没能启动：终端环境尚未就绪。请先在侧边栏打开一次「终端」完成 Ubuntu 初始化，再重试。"
            )
        }

        val id = JobRegistry.begin(ctx.sessionId, kind, label.ifBlank { command.take(60) })
        val scriptPath = TerminalApi.writeJobScript(appContext, id, command, cwd)
        if (scriptPath == null) {
            JobRegistry.fail(id, "启动失败：任务脚本写不出来")
            return ToolResult.Error("后台任务没能启动：任务脚本写不出来（App 私有目录不可写）。")
        }

        val timeoutMs = args.optLong("timeout_ms", 0L).coerceIn(0L, MAX_TIMEOUT_MS)
        JobRegistry.scope.launch { supervise(appContext, id, scriptPath, timeoutMs) }

        FileLog.log(TAG, "起后台任务 " + id + "：cd " + cwd + " && " + command.take(120))
        return ToolResult.Ok(
            "已在后台启动任务 " + id + "。可以先做别的事；用 job_output(job_id=\"" + id + "\") 读实时输出，" +
                "job_list 看状态，job_kill 停掉它。任务完成时会通知你。"
        )
    }

    /**
     * 监视一个任务直到结束：一边搬日志增量，一边等执行通道返回。
     *
     * 两件事必须并行：命令跑多久是不可预知的，输出却是随时可能要读的。
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
                // 收尾：把最后一段输出读干净再结算，免得结算时漏掉结尾几行
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
        // 任务结束即归还独占通道：不还的话每起一个任务就多留一个常驻 Ubuntu 子进程
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
     * 模型看到具体数字才知道是写错了命令，还是被 kill 掉的。
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
        const val DEFAULT_CWD = "/root"

        /** 不设上限时给执行通道的兜底时限，也是单任务允许的最长时间（6 小时）。 */
        const val MAX_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        /** 监视间隔：日志是普通文件读取，成本极低；400 毫秒足够让「实时输出」看起来是实时的。 */
        const val POLL_MS = 400L
    }
}
