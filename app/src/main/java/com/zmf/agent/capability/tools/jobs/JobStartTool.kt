package com.zmf.agent.capability.tools.jobs

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
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * job_start：在后台起一个长任务，立刻返回任务 id，不阻塞当前回合。
 *
 * 第一个（也是目前唯一一个）kind 是**终端命令**：在 [JobRegistry.scope] 里异步跑
 * [TerminalApi.exec]（与 terminal 工具同一条执行路径），跑完把输出一次性写进注册表并结算。
 * 模型起完就能先去做别的，完成后再用 job_output 把结果收回来。
 *
 * 为什么用「协程里异步跑 exec」而不是「在 exec 里 sleep 等它跑完」：前者起完就返回 id，
 * 当前回合不卡死；后者等于把「后台」做成了「前台干等」。
 *
 * 已知边界：exec 是原子的（一条命令一次执行、跑完才返回），所以输出是「跑完后一次性给」，
 * 不是流式增量；job_kill 第一版也只标记取消、不真正中断已在跑的命令（终端执行不可中途打断）。
 *
 * 权限档 WORKSPACE：起进程是有副作用的动作，且终端命令本身需要工作区档。
 */
class JobStartTool : Tool {

    override val name = "job_start"

    override val mutates = true

    override val description =
        "在后台启动一个长任务并立刻返回任务 id，不阻塞当前回合。目前支持终端命令（在 App 内 Ubuntu 里跑）。" +
            "起完后可以先去做别的，用 job_output 读它的输出（可 wait）、job_list 看状态、job_kill 取消。" +
            "适合编译、下载、长时间跑的服务这类需要几分钟才出结果的事；很快就能跑完的命令不要用后台任务。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "kind": {"type": "string", "description": "任务类型，目前只支持 terminal"},
            "command": {"type": "string", "description": "要在后台执行的 shell 命令，例如 make 或 python train.py"},
            "cwd": {"type": "string", "description": "工作目录，执行前先 cd 进去，默认 /root"},
            "label": {"type": "string", "description": "可选：一句话说明这个任务在做什么，job_list 里会显示"}
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

        val full = "cd " + cwd + " && " + command
        val timeoutMs = args.optLong("timeout_ms", DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val id = JobRegistry.begin(ctx.sessionId, kind, label.ifBlank { command.take(60) })

        // 在注册表自己的 scope 里异步跑：工具返回后这条协程继续，命令跑完再结算
        JobRegistry.scope.launch {
            val result = runCatching { TerminalApi.exec(ctx.context, full, "job", timeoutMs) }
            val (output, statusNote) = when {
                result.isFailure -> "" to ("执行异常：" + (result.exceptionOrNull()?.message ?: "未知"))
                else -> {
                    val r = result.getOrThrow()
                    when (r) {
                        is ShellResult.Ok -> (cleanOutput(r.output) to ("exit=" + r.exitCode))
                        is ShellResult.ShellNotReady -> "" to "Ubuntu 环境未初始化"
                        is ShellResult.Failed -> cleanOutput(r.rawPreview) to (r.reason + "：" + r.detail)
                    }
                }
            }
            if (output.isNotEmpty()) JobRegistry.append(id, output)
            if (result.isSuccess && result.getOrNull() is ShellResult.Ok && (result.getOrNull() as ShellResult.Ok).exitCode == 0) {
                JobRegistry.complete(id, statusNote)
            } else {
                JobRegistry.fail(id, statusNote)
            }
            FileLog.log(TAG, "任务 " + id + " 结算 " + statusNote)
        }

        FileLog.log(TAG, "起后台任务 " + id + "：cd " + cwd + " && " + command.take(120))
        return ToolResult.Ok(
            "已在后台启动任务 " + id + "。可以先做别的事；用 job_output(job_id=\"" + id + "\") 读它的输出，" +
                "job_list 看状态，job_kill 取消。任务完成时会通知你。"
        )
    }

    /** 去 ANSI、去首尾空白，再按「前 4000 + 省略标注 + 后 8000」截断，和 terminal 工具同一口径。 */
    private fun cleanOutput(raw: String): String {
        val clean = runCatching { TerminalApi.stripAnsi(raw) }.getOrElse { raw }.trim()
        if (clean.length <= MAX_OUTPUT_CHARS) return clean
        val head = clean.take(HEAD_CHARS)
        val tail = clean.takeLast(TAIL_CHARS)
        val omitted = clean.length - head.length - tail.length
        return head + "\n…（已省略 " + omitted + " 字符）…\n" + tail
    }

    private companion object {
        const val TAG = "jobs"
        const val DEFAULT_CWD = "/root"
        const val DEFAULT_TIMEOUT_MS = 600_000L
        const val MIN_TIMEOUT_MS = 5_000L
        const val MAX_TIMEOUT_MS = 600_000L
        const val MAX_OUTPUT_CHARS = 12_000
        const val HEAD_CHARS = 4_000
        const val TAIL_CHARS = 8_000
    }
}
