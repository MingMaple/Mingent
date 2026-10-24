package com.zmf.agent.tools.terminal

import android.content.Context
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiUtils
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolPolicy
import com.zmf.agent.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 在 App 内的 Ubuntu 24 (PRoot) 里跑一条 shell 命令。
 *
 * 底层走 TerminalManager.executeHiddenCommand()（后台常驻 bash 管道、无 PTY、无回显、无提示符），
 * 拿到的是真实退出码，stdout/stderr 已合并。每条命令都是新的子进程，
 * 所以工作目录必须显式拼 "cd <cwd> && <cmd>"，不要指望跨命令保留 cwd。
 *
 * 隐藏执行内部有 Thread.sleep，必须放在 Dispatchers.IO 上，否则会卡主线程。
 */
class TerminalTool : Tool {

    override val name = "terminal"

    override val description =
        "在 App 内的 Ubuntu 24 (PRoot) 里执行一条 shell 命令，返回真实退出码和输出（stdout 与 stderr 已合并）。" +
            "每条命令都是新进程，工作目录不跨命令保留：要进某个目录就在命令里自己 cd，或用 cwd 参数。" +
            "这不是交互式终端，没有提示符、看不到 TTY；apt / git 等命令请自己加 -y 之类的非交互参数。" +
            "退出码非 0 也算正常返回，先看输出再决定下一步。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "command": {"type": "string", "description": "要执行的 shell 命令，例如 ls -la /root 或 apt install -y curl"},
            "cwd": {"type": "string", "description": "工作目录，执行前会先 cd 进去，默认 /root"},
            "timeout_ms": {"type": "number", "description": "超时毫秒数，默认 60000，范围 5000-600000；长任务（apt、编译）记得调大"}
          },
          "required": ["command"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val raw = args.optString("command").trim()
        if (raw.isEmpty()) return ToolResult.Error("command 不能为空")

        // 权限闸：只拿用户/模型给的原始 command 判断，绝不把 cwd 拼进来再判
        //（cwd 是执行细节，拼进去会让「cd /sdcard」这种写法被当成命令本身越界误拦）
        val decision = ToolPolicy.checkTerminal(ctx.permission, raw)
        if (!decision.allowed) {
            return ToolResult.Error(decision.reason ?: ToolPolicy.deniedText(ctx.permission, "终端"))
        }

        // 开了 SSH 后 executeHiddenCommand 会走远端，和「App 内 Ubuntu」的语义不符，直接挡住让用户先关。
        val sshEnabled = ctx.context
            .getSharedPreferences("ssh_config", Context.MODE_PRIVATE)
            .getBoolean("ssh_enabled", false)
        if (sshEnabled) {
            return ToolResult.Error("当前开启了 SSH 远程终端，命令会被发到远端，请先在终端设置里关闭 SSH")
        }

        val cwd = args.optString("cwd", "").trim().ifEmpty { DEFAULT_CWD }
        val timeoutMs = args.optLong("timeout_ms", DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        // proot 里每条命令都是新子进程，cwd 不跨命令，只能拼在命令最前面
        val full = "cd " + cwd + " && " + raw

        return try {
            val result = withContext(Dispatchers.IO) {
                TerminalManager.getInstance(ctx.context).executeHiddenCommand(full, "agent", timeoutMs)
            }
            render(result)
        } catch (e: CancellationException) {
            // 用户点了「停止」：必须原样抛出，否则取消渗不下去
            throw e
        } catch (e: Exception) {
            ToolResult.Error("终端执行异常：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun render(result: HiddenExecResult): ToolResult = when (result.state) {
        HiddenExecResult.State.OK -> {
            val body = cleanOutput(result.output)
            ToolResult.Ok("exit=" + result.exitCode + "\n" + (body.ifEmpty { "(无输出)" }))
        }

        HiddenExecResult.State.SHELL_NOT_READY -> ToolResult.Error(
            "Ubuntu 环境还没起来：先在侧边栏打开一次「终端」完成初始化，再重试。"
        )

        else -> {
            val detail = result.error.ifBlank { "(没有更多错误信息)" }
            val preview = cleanOutput(result.rawOutputPreview)
            val suffix = if (preview.isEmpty()) "" else "\n原始输出片段：\n" + preview
            ToolResult.Error("执行失败（" + result.state.name + "）：" + detail + suffix)
        }
    }

    /** 去 ANSI、去首尾空白，再按「前 4000 + 省略标注 + 后 8000」截断，免得把上下文撑爆。 */
    private fun cleanOutput(raw: String): String {
        val clean = runCatching { AnsiUtils.stripAnsi(raw) }.getOrElse { raw }.trim()
        if (clean.length <= MAX_OUTPUT_CHARS) return clean
        val head = clean.take(HEAD_CHARS)
        val tail = clean.takeLast(TAIL_CHARS)
        val omitted = clean.length - head.length - tail.length
        return head + "\n…（已省略 " + omitted + " 字符）…\n" + tail
    }

    private companion object {
        const val DEFAULT_CWD = "/root"
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MIN_TIMEOUT_MS = 5_000L
        const val MAX_TIMEOUT_MS = 600_000L

        /** 返回给模型的输出上限：超出就保留头尾，中间标注省略了多少字符。 */
        const val MAX_OUTPUT_CHARS = 12_000
        const val HEAD_CHARS = 4_000
        const val TAIL_CHARS = 8_000
    }
}
