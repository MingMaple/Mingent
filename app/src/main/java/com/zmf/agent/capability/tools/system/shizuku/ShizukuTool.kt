package com.zmf.agent.capability.tools.system.shizuku

import com.zmf.agent.infra.system.ShizukuBridge
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import org.json.JSONObject

/**
 * 用 Shizuku（Android shell / uid 2000）执行一条系统命令。
 *
 * 为什么必须单独实现工具：terminal 中运行的是 App 自身 fork 的 PRoot Ubuntu，
 * SELinux 域固定为 untrusted_app，无法访问 /system/bin，也无法访问 Shizuku 的 binder。
 * 系统级命令（pm / am / settings / appops / dumpsys）只能由 App 的 Java 层通过 Shizuku 发送。
 *
 * 权限档要求「完全」：该能力等同于 shell，不应在更低档位开放给模型。
 */
class ShizukuTool : Tool {

    override val name = "shizuku"

    /** 系统级操作（pm/am/settings）：只读档与计划模式都必须拒绝。 */
    override val mutates = true

    override val description =
        "通过 Shizuku 的 Android shell 执行一条系统命令，返回合并后的输出与退出码。" +
            "只用于 pm、am、settings、appops、dumpsys 等系统命令；Ubuntu 命令改用 terminal。" +
            "需已安装并授权 Shizuku 且服务运行；timeout_ms 默认 20000、范围 2000–120000；仅「完全」权限档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "command": {"type": "string", "description": "要执行的系统命令，例如 pm list packages 或 appops get io.github.mingmaple.mingent；必填，无默认值"},
            "timeout_ms": {"type": "number", "description": "超时毫秒数，范围 2000–120000，默认 20000"}
          },
          "required": ["command"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.FULL

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val command = args.optString("command", "").trim()
        if (command.isEmpty()) return ToolResult.Error("[E_INVALID] command 不能为空：请给出要执行的系统命令。")
        if (!ShizukuBridge.isReady()) {
            return ToolResult.Error(
                "[E_SERVICE_UNAVAILABLE] Shizuku 当前不可用（" + ShizukuBridge.state.value.name + "）；" +
                    "请到「设置 → 权限管理 → Shizuku」授权并确认服务运行，此项不能通过调整参数解决；" +
                    "只跑 Ubuntu 命令请改用 terminal。"
            )
        }
        val timeout = args.optLong("timeout_ms", 20_000L).coerceIn(2_000L, 120_000L)
        // 高危闸门：该通道以 shell uid 执行，权限高于 App；若不拦截，会成为绕过删除闸门的途径。
        // 路径按宿主视角判定（/sdcard 为真实的共享存储），并额外覆盖 pm clear / pm uninstall。
        val risk = HighRiskGate.checkHostCommand(ctx, command)
        if (!risk.allowed) {
            return ToolResult.Error(
                "[E_INVALID] " + (risk.message ?: "这条系统命令被高危闸门拒绝") +
                    "；请改用不涉及删除或卸载的命令。"
            )
        }
        val result = ShizukuBridge.exec(command, timeout)
        return if (result.ok) {
            ToolResult.Ok("exit=" + result.exitCode + "\n" + result.output.ifEmpty { "(无输出)" })
        } else {
            // 失败结果不会被 SpillStore 落盘，必须在这里截断，否则一次 dumpsys 报错就能占满上下文
            val output = if (result.output.length > MAX_ERROR_CHARS) {
                result.output.take(MAX_ERROR_CHARS) +
                    "\n…（已截断，原输出 " + result.output.length + " 字符）"
            } else {
                result.output
            }
            ToolResult.Error(
                "[E_INVALID] 命令执行失败（exit=" + result.exitCode + "）：\n" + output.ifEmpty { "(无输出)" } +
                    "\n请按输出修正命令后重试。"
            )
        }
    }

    private companion object {
        /** 失败输出回给模型的上限：保留开头足以定位原因，超出部分直接省略。 */
        const val MAX_ERROR_CHARS = 12_000
    }
}
