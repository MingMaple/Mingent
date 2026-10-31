package com.zmf.agent.capability.tools.jobs

import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolPolicy
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.infra.terminal.TerminalApi
import org.json.JSONObject

/**
 * job_start：在后台起一个长任务，立刻返回任务 id，不阻塞当前回合。
 *
 * 第一个（也是目前唯一一个）kind 是**终端命令**：用 [TerminalApi.openProcess] 起一条常驻子进程，
 * 输出逐行写进 [JobRegistry] 的有界环，进程退出时结算为 completed / failed。模型起完就能先去做别的，
 * 完成后再用 job_output 把结果收回来。
 *
 * 为什么不是「在 exec 里 sleep 等它跑完」：那样当前回合就卡死了，模型的「先干别的」根本无从谈起。
 * 起后台进程、把句柄挂进注册表、立即返回 id，才是「后台任务」的本意。
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
        val id = JobRegistry.begin(ctx.sessionId, kind, label.ifBlank { command.take(60) })

        // 逐行回调把输出写进注册表；onExit 结算。这两个回调在终端读线程里跑，JobRegistry 自己加锁，线程安全。
        val handle = TerminalApi.openProcess(
            context = ctx.context,
            commandLine = full,
            env = emptyMap(),
            cwd = cwd,
            onLine = { line -> JobRegistry.append(id, TerminalApi.stripAnsi(line) + "\n") },
            onExit = { code ->
                JobRegistry.detachHandle(id)
                if (code == null) {
                    JobRegistry.fail(id, "进程异常退出（拿不到退出码）")
                } else if (code == 0) {
                    JobRegistry.complete(id, "exit=" + code)
                } else {
                    JobRegistry.fail(id, "exit=" + code)
                }
                FileLog.log(TAG, "任务 " + id + " 结算 exit=" + (code?.toString() ?: "无"))
            }
        )
        if (handle == null) {
            JobRegistry.fail(id, "没能起后台进程（Ubuntu 环境可能还没初始化）")
            return ToolResult.Error("没能启动后台任务：Ubuntu 环境还没起来，先在侧边栏打开一次「终端」完成初始化再重试。")
        }
        JobRegistry.attachHandle(id, JobRegistry.JobHandle { handle.close(); true })

        FileLog.log(TAG, "起后台任务 " + id + "：cd " + cwd + " && " + command.take(120))
        return ToolResult.Ok(
            "已在后台启动任务 " + id + "。可以先做别的事；用 job_output(job_id=\"" + id + "\") 读它的输出，" +
                "job_list 看状态，job_kill 取消。任务完成时会通知你。"
        )
    }

    private companion object {
        const val TAG = "jobs"
        const val DEFAULT_CWD = "/root"
    }
}
