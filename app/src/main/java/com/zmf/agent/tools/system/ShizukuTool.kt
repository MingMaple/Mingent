package com.zmf.agent.tools.system

import com.zmf.agent.core.capability.ShizukuBridge
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.HighRiskGate
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolResult
import org.json.JSONObject

/**
 * 用 Shizuku（Android shell / uid 2000）执行一条系统命令。
 *
 * 为什么必须单独做工具：terminal 里跑的是 App 自己 fork 出来的 PRoot Ubuntu，
 * SELinux 域钉死在 untrusted_app，看不到 /system/bin，也碰不到 Shizuku 的 binder。
 * 系统级命令（pm / am / settings / appops / dumpsys）只能由 App 的 Java 层通过 Shizuku 发出去。
 *
 * 权限档给「完全」：这层能力等于 shell，不该在更低的档位被模型随手用。
 */
class ShizukuTool : Tool {

    override val name = "shizuku"

    /** 系统级操作（pm/am/settings）：只读档与计划模式都必须拒绝。 */
    override val mutates = true

    override val description =
        "用 Shizuku 的 Android shell 执行一条系统命令（pm / am / settings / appops / dumpsys 等），" +
            "返回合并后的 stdout+stderr 与退出码。这条通道是 App 的 Java 层，不是 PRoot 里的 Ubuntu；" +
            "需要用户在 Shizuku 里给本 App 授权且服务正在运行。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "command": {"type": "string", "description": "要执行的系统命令，例如 pm list packages 或 appops get io.github.mingmaple.mingent"},
            "timeout_ms": {"type": "number", "description": "超时毫秒数，默认 20000，范围 2000-120000"}
          },
          "required": ["command"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.FULL

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val command = args.optString("command", "").trim()
        if (command.isEmpty()) return ToolResult.Error("command 不能为空")
        if (!ShizukuBridge.isReady()) {
            return ToolResult.Error(
                "Shizuku 现在不可用（" + ShizukuBridge.state.value.name + "）。" +
                    "请到「设置 → 权限管理 → Shizuku」授权，并确认 Shizuku 服务正在运行；" +
                    "如果只是想在 Ubuntu 里跑命令，用 terminal 工具。"
            )
        }
        val timeout = args.optLong("timeout_ms", 20_000L).coerceIn(2_000L, 120_000L)
        // 高危闸门：这条通道的 uid 是 shell，权限比 App 大；不拦的话它就是「绕过删除闸门」的后门。
        // 路径按宿主视角判（/sdcard 是真的共享存储），并额外覆盖 pm clear / pm uninstall。
        val risk = HighRiskGate.checkHostCommand(ctx, command)
        if (!risk.allowed) {
            return ToolResult.Error(risk.message ?: "这条系统命令被高危闸门拒绝")
        }
        val result = ShizukuBridge.exec(command, timeout)
        return if (result.ok) {
            ToolResult.Ok("exit=" + result.exitCode + "\n" + result.output.ifEmpty { "(无输出)" })
        } else {
            ToolResult.Error("exit=" + result.exitCode + "\n" + result.output.ifEmpty { "(无输出)" })
        }
    }
}
