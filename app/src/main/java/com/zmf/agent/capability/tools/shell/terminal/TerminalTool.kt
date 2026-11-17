package com.zmf.agent.capability.tools.shell.terminal
import com.zmf.agent.infra.terminal.ShellResult
import com.zmf.agent.infra.terminal.TerminalApi

import android.content.Context
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolPolicy
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.shell.support.SessionCwd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 在 App 内的 Ubuntu 24 (PRoot) 中执行一条 shell 命令。
 *
 * 经 `TerminalApi.exec` 在 PRoot 中隐藏执行（后台常驻 bash 管道、无 PTY、无回显、无提示符），
 * 可获得真实退出码，stdout/stderr 已合并。每条命令均为新的子进程，
 * 因此工作目录不能依赖跨命令保留，需在命令前显式 `cd`（见 `workdir` 参数）。
 *
 * 隐藏执行内部包含 Thread.sleep，必须置于 Dispatchers.IO，否则会阻塞主线程。
 */
class TerminalTool : Tool {

    override val name = "terminal"

    /** 执行命令：只读档与计划模式都必须拒绝。 */
    override val mutates = true

    override val description =
        "在 App 内的 Ubuntu 24 (PRoot) 里执行一条 shell 命令，返回合并后的输出与退出标记。" +
            "没有 TTY，apt 等命令须显式加 -y；退出码非 0 不等同工具失败，应先读输出再判断；长任务改用 job_start。" +
            "每条命令都是新进程，进程内 cd 不跨命令保留；默认在工作区终端目录执行，" +
            "工作区不可映射时为 /root；超时上限 600000 毫秒，工作区档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "command": {"type": "string", "description": "要执行的 shell 命令，例如 ls -la /root 或 apt install -y curl"},
            "workdir": {"type": "string", "description": "本次命令的工作目录（guest 绝对路径）；未传时用工作区终端目录（工作区不可映射时为 /root）"},
            "timeoutMs": {"type": "number", "description": "超时毫秒数，默认 60000，范围 5000-600000；长任务（apt、编译）应调大"}
          },
          "required": ["command"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    /**
     * 单次调用的时间上界：取本工具自己的最长时限。
     *
     * 工具内部按参数 timeoutMs 计时（默认 60 秒、最长 10 分钟），并会关掉本会话的通道；
     * 执行入口的总闸在本值之外另加余量，因此总是本工具先行结束，两层超时不会互相干扰。
     */
    override val timeoutMs: Long = MAX_TIMEOUT_MS

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val raw = args.optString("command").trim()
        if (raw.isEmpty()) return ToolResult.Error("[E_INVALID] command 不能为空：请给出要执行的 shell 命令")

        // 解析优先级：workdir 参数 > 工作区派生的终端目录（不可映射时为 /root）。
        // 显式目录参数含 `..` 段即越界，直接拒绝、不执行（否则 cd ../../.. 会落到 PRoot 根）。
        val cwd = SessionCwd.resolveGuest(
            ctx,
            args.optString("workdir", "").trim().ifEmpty { args.optString("cwd", "").trim() }
        ) ?: return ToolResult.Error(SessionCwd.ERR_OUTSIDE)

        // 第一道：高危闸门（删除/截断语义），**先于档位判定**：完全档只豁免越界，不豁免高危。
        // 命令文本中的路径会按 PRoot 的 bind mount 表映射回宿主后再判定（/sdcard、/data/data/<pkg> 等），
        // 仅依据文本推测「这是 Ubuntu 内部路径」会遗漏真实落点。
        val risk = HighRiskGate.checkTerminal(ctx, raw, cwd)
        if (!risk.allowed) {
            return ToolResult.Error(risk.message ?: "[E_PROTECTED] 终端命令被高危闸门拒绝；请改写命令后重试")
        }

        // 第二道：越界与危险命令黑名单（仅使用用户或模型提供的原始 command 判定，不得拼接 cwd 后再判定；
        // cwd 属于执行细节，拼接后会使「cd /sdcard」这类写法被当作命令本身越界而误拦）
        val decision = ToolPolicy.checkTerminal(ctx.permission, raw)
        if (!decision.allowed) {
            return ToolResult.Error(decision.reason ?: ToolPolicy.deniedText(ctx.permission, "终端"))
        }

        // 开启 SSH 后 executeHiddenCommand 会走远端，与「App 内 Ubuntu」的语义不符，因此直接拒绝并要求用户先关闭。
        val sshEnabled = ctx.context
            .getSharedPreferences("ssh_config", Context.MODE_PRIVATE)
            .getBoolean("ssh_enabled", false)
        if (sshEnabled) {
            return ToolResult.Error("[E_UNSUPPORTED] 当前开启了 SSH 远程终端，命令会发往远端；请先在终端设置里关闭 SSH 后重试")
        }

        val timeoutMs = readTimeout(args).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        // 通道按执行者分（对齐 DSH「每个 owner 一个 shell」）：主会话一条，每个子智能体各一条。
        // 同一执行者的命令仍在这条通道里串行，不同执行者互不排队；无会话的调用退回共用的 "agent"
        val executorKey = executorKeyOf(ctx)
        // proot 中每条命令均为新的子进程，cwd 不跨命令保留，只能前置拼接到命令起始处。
        // cwd 由单引号包裹并转义内部单引号，防止分号、换行等被解释为命令分隔符绕过上方两道闸门。
        val quotedCwd = "'" + cwd.replace("'", "'\\''") + "'"
        val full = "cd " + quotedCwd + " && " + raw

        return try {
            val result = withContext(Dispatchers.IO) {
                TerminalApi.exec(ctx.context, full, executorKey, timeoutMs)
            }
            render(result)
        } catch (e: CancellationException) {
            // 用户点了「停止」：关闭**本会话**的通道后再原样抛出（超时由执行层按同一键关闭，
            // 两条路径都只影响这一条通道）。不关闭时，该通道会残留中途终止的 shell 并被下一条命令复用
            TerminalApi.releaseChannel(ctx.context, executorKey)
            throw e
        } catch (e: Exception) {
            // 关闭通道会让阻塞中的读写抛异常；先把取消语义还原出来，避免把「停止」报成终端故障
            currentCoroutineContext().ensureActive()
            ToolResult.Error("[E_SERVICE_UNAVAILABLE] 终端执行异常：" + (e.message ?: e.javaClass.simpleName) + "；调整参数无法解决，可先在侧边栏确认终端环境可用")
        }
    }

    /**
     * 本执行者的通道键：主会话 `agent-<sessionId>`，子智能体 `agent-<sessionId>-sub-<子智能体 id>`。
     *
     * 与会话 + 执行者一一对应（对齐 DSH「每个 owner 一个 shell」）：主会话与同会话的多个子智能体
     * 各用一条 shell，长命令互不排队；同一子智能体内部的命令仍串行。超时或停止只关自己这一条。
     * 子智能体结束时其通道由 [releaseSubAgentChannel] 立即释放；其余通道空闲 10 分钟后由
     * `TerminalApi` 的巡查回收。
     */
    private fun executorKeyOf(ctx: ToolContext): String = keyOf(ctx.sessionId, ctx.agentId)

    private fun render(result: ShellResult): ToolResult = when (result) {
        is ShellResult.Ok -> {
            val body = cleanOutput(result.output).ifEmpty { "（无输出）" }
            ToolResult.Ok(body + "\n[exit code: " + result.exitCode + "]")
        }

        is ShellResult.ShellNotReady -> ToolResult.Error(
            "[E_SERVICE_UNAVAILABLE] Ubuntu 环境尚未就绪：请先在侧边栏打开一次「终端」完成初始化后重试，调整参数无法解决。"
        )

        is ShellResult.Failed -> {
            val detail = result.detail.ifBlank { "(没有更多错误信息)" }
            val preview = cleanOutput(result.rawPreview)
            val suffix = if (preview.isEmpty()) "" else "\n原始输出片段：\n" + preview
            ToolResult.Error("[E_SERVICE_UNAVAILABLE] 执行失败（" + result.reason + "）：" + detail + suffix)
        }
    }

    /** 读取超时：新名称 timeoutMs 优先，旧名称 timeout_ms 兜底。 */
    private fun readTimeout(args: JSONObject): Long {
        val key = when {
            args.has("timeoutMs") && !args.isNull("timeoutMs") -> "timeoutMs"
            args.has("timeout_ms") && !args.isNull("timeout_ms") -> "timeout_ms"
            else -> return DEFAULT_TIMEOUT_MS
        }
        return args.optLong(key, DEFAULT_TIMEOUT_MS)
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

    companion object {

        /**
         * 释放某个子智能体的终端通道（子智能体结束时调用）。
         *
         * 与 DSH「owner 拆除即终止 shell」同口径：子智能体执行完毕就不再保留它的常驻 shell，
         * 避免同一会话反复派发子智能体时累积空闲通道。从未用过终端的子智能体不产生日志行。
         */
        fun releaseSubAgentChannel(context: Context, sessionId: String, agentId: String) {
            if (agentId.isBlank()) return
            TerminalApi.releaseChannel(context, keyOf(sessionId, agentId))
        }

        /** 通道键的唯一拼法：主会话 `agent-<sessionId>`，子智能体追加 `-sub-<子智能体 id>`。 */
        internal fun keyOf(sessionId: String, agentId: String): String = when {
            sessionId.isBlank() -> LEGACY_EXECUTOR_KEY
            agentId.isBlank() -> EXECUTOR_PREFIX + sessionId
            else -> EXECUTOR_PREFIX + sessionId + SUB_PREFIX + agentId
        }

        private const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val MIN_TIMEOUT_MS = 5_000L
        private const val MAX_TIMEOUT_MS = 600_000L

        /** 执行通道键前缀：`agent-<会话 id>`，一条会话的主会话 shell。 */
        private const val EXECUTOR_PREFIX = "agent-"

        /** 子智能体通道键的中间段：`agent-<会话 id>-sub-<子智能体 id>`。 */
        private const val SUB_PREFIX = "-sub-"

        /** 无会话 id 时的通道键：少数不带会话的调用退回共用通道，与旧行为一致。 */
        private const val LEGACY_EXECUTOR_KEY = "agent"

        /** 返回给模型的输出上限：超出时保留头尾，并在中间标注省略的字符数。 */
        private const val MAX_OUTPUT_CHARS = 12_000
        private const val HEAD_CHARS = 4_000
        private const val TAIL_CHARS = 8_000
    }
}
