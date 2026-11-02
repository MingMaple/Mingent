package com.zmf.agent.capability.tools.terminal
import com.zmf.agent.infra.terminal.ShellResult
import com.zmf.agent.infra.terminal.TerminalApi

import android.content.Context
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolPolicy
import com.zmf.agent.capability.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 在 App 内的 Ubuntu 24 (PRoot) 中执行一条 shell 命令。
 *
 * 底层使用 TerminalManager.executeHiddenCommand()（后台常驻 bash 管道、无 PTY、无回显、无提示符），
 * 可获得真实退出码，stdout/stderr 已合并。每条命令均为新的子进程，
 * 因此工作目录必须在命令中显式拼接 "cd <cwd> && <cmd>"，不能依赖跨命令保留。
 *
 * 隐藏执行内部包含 Thread.sleep，必须置于 Dispatchers.IO，否则会阻塞主线程。
 */
class TerminalTool : Tool {

    override val name = "terminal"

    /** 执行命令：只读档与计划模式都必须拒绝。 */
    override val mutates = true

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

        val cwd = args.optString("cwd", "").trim().ifEmpty { DEFAULT_CWD }

        // 第一道：高危闸门（删除/截断语义），**先于档位判定**：完全档只豁免越界，不豁免高危。
        // 命令文本中的路径会按 PRoot 的 bind mount 表映射回宿主后再判定（/sdcard、/data/data/<pkg> 等），
        // 仅依据文本推测「这是 Ubuntu 内部路径」会遗漏真实落点。
        val risk = HighRiskGate.checkTerminal(ctx, raw, cwd)
        if (!risk.allowed) {
            return ToolResult.Error(risk.message ?: "终端命令被高危闸门拒绝")
        }

        // 第二道：越界与危险命令黑名单（仅使用用户或模型提供的原始 command 判定，不得拼接 cwd 后再判定；
        //（cwd 属于执行细节，拼接后会使「cd /sdcard」这类写法被当作命令本身越界而误拦）
        val decision = ToolPolicy.checkTerminal(ctx.permission, raw)
        if (!decision.allowed) {
            return ToolResult.Error(decision.reason ?: ToolPolicy.deniedText(ctx.permission, "终端"))
        }

        // 开启 SSH 后 executeHiddenCommand 会走远端，与「App 内 Ubuntu」的语义不符，因此直接拒绝并要求用户先关闭。
        val sshEnabled = ctx.context
            .getSharedPreferences("ssh_config", Context.MODE_PRIVATE)
            .getBoolean("ssh_enabled", false)
        if (sshEnabled) {
            return ToolResult.Error("当前开启了 SSH 远程终端，命令会被发到远端，请先在终端设置里关闭 SSH")
        }

        val timeoutMs = args.optLong("timeout_ms", DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        // proot 中每条命令均为新的子进程，cwd 不跨命令保留，只能拼接在命令最前面
        val full = "cd " + cwd + " && " + raw

        return try {
            val result = withContext(Dispatchers.IO) {
                TerminalApi.exec(ctx.context, full, "agent", timeoutMs)
            }
            render(result)
        } catch (e: CancellationException) {
            // 用户点了「停止」：必须原样抛出，否则取消无法传播
            throw e
        } catch (e: Exception) {
            ToolResult.Error("终端执行异常：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun render(result: ShellResult): ToolResult = when (result) {
        is ShellResult.Ok -> {
            val body = cleanOutput(result.output)
            ToolResult.Ok("exit=" + result.exitCode + "\n" + (body.ifEmpty { "(无输出)" }))
        }

        is ShellResult.ShellNotReady -> ToolResult.Error(
            "Ubuntu 环境还没起来：先在侧边栏打开一次「终端」完成初始化，再重试。"
        )

        is ShellResult.Failed -> {
            val detail = result.detail.ifBlank { "(没有更多错误信息)" }
            val preview = cleanOutput(result.rawPreview)
            val suffix = if (preview.isEmpty()) "" else "\n原始输出片段：\n" + preview
            ToolResult.Error("执行失败（" + result.reason + "）：" + detail + suffix)
        }
    }

    /** 去除 ANSI 控制符与首尾空白，再按「前 4000 + 省略标注 + 后 8000」截断，避免占满上下文。 */
    private fun cleanOutput(raw: String): String {
        val clean = runCatching { TerminalApi.stripAnsi(raw) }.getOrElse { raw }.trim()
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

        /** 返回给模型的输出上限：超出时保留头尾，并在中间标注省略的字符数。 */
        const val MAX_OUTPUT_CHARS = 12_000
        const val HEAD_CHARS = 4_000
        const val TAIL_CHARS = 8_000
    }
}
