package com.zmf.agent.capability.tools
import com.zmf.agent.core.fs.PathGate

import com.zmf.agent.core.model.PermissionLevel
import java.io.File

/**
 * 工具执行前的确定性闸门：工具真正动手之前必须过这一关。
 *
 * 只负责两件事，不在此处加入业务逻辑：
 * 1. **终端命令闸**：工作区档下不许碰外部挂载点（/sdcard、/storage、/mnt/media…），
 *    因为这些路径一旦可写，便等同于「工作区档可修改整台设备」，与档位承诺相矛盾；
 * 2. **危险命令黑名单**：完全档以外的档位一律拒绝会毁数据/毁系统的命令。
 *
 * 这是「确定性拦截」，而非沙箱：命令仍可通过变量、管道或脚本绕过，因此提示词与错误文案中
 * 均明确说明「这是闸门而非沙箱」。最终保障来自 Android 自身的权限机制。
 *
 * 拒绝文案用固定的机器可读前缀（`[sandbox: file access denied under workspace mode]` /
 * `[sandbox: command denied under workspace mode]`）：Agent 侧与日志靠这个固定前缀区分
 * 「被闸门拦住」和「工具自己执行出错」，前缀之后才是给人看的中文原因与提权提示。
 * 前缀里的 mode 就是档位（read-only / workspace / danger-full-access），不另造一套词。
 *
 * 注意：Kotlin 原始字符串里不能直接写美元符，所以正则里的「行尾」一律用 \z 表示。
 */
object ToolPolicy {

    /** 外部挂载点：出现即视为越出 App 私有域（工作区档拒绝）。 */
    private val EXTERNAL_MOUNTS = listOf(
        "/sdcard", "/storage/", "/mnt/media", "/mnt/user", "/mnt/runtime", "/data/media"
    )

    /** 危险命令：命中即拒绝（完全档放行）。规则适当放宽，宁可误拦一条，也不放过 rm -rf /。 */
    private val DANGEROUS = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*\s+)*-[a-zA-Z]*[rR][a-zA-Z]*\s+(/|/\*|~|/system|/data|/storage)(?:\s|;|\z)"""),
        Regex("""\brm\s+-[a-zA-Z]*f[a-zA-Z]*\s+/(?:\s|;|\z)"""),
        Regex("""\bdd\b[^|;&]*\bof=/dev/block"""),
        Regex("""\bmkfs(\.\w+)?\b"""),
        Regex("""\bmke2fs\b"""),
        Regex("""\bchmod\s+-R\s+[0-7]*7[0-7][0-7]\s+/(?:\s|;|\z)"""),
        Regex("""\bchown\s+-R\s+\S+\s+/(?:\s|;|\z)"""),
        Regex(""">\s*/dev/block"""),
        Regex("""\bmount\b[^|;&]*\bremount\b"""),
        Regex("""\b(shutdown|reboot|halt|poweroff|wipefs)\b"""),
        Regex(""":\(\)\s*\{.*\};:""")
    )

    /** 路径类拒绝的固定前缀；mode 补齐后必须以 ` mode]` 收尾，机器可读格式不能加字。 */
    private const val SANDBOX_FILE = "[sandbox: file access denied under "

    /** 命令类拒绝的固定前缀。 */
    private const val SANDBOX_COMMAND = "[sandbox: command denied under "

    /** 路径类的提权提示：要么切档位，要么退回工作区（含 tmp/）。 */
    private const val ESCALATE_FILE =
        "。要这么做请把权限切到「完全」（设置 → 权限），或改到工作区内（含 tmp/）的路径操作。"

    /** 命令类的提权提示：命令没法「换个路径」，只能切档位或收缩命令范围。 */
    private const val ESCALATE_COMMAND =
        "。要这么做请把权限切到「完全」（设置 → 权限），或把命令限制在工作区内执行。"

    /** 闸门结论：[allowed] 为 false 时 [reason] 是完整拒绝文案（含机器可读前缀与提权提示）。 */
    data class Decision(val allowed: Boolean, val reason: String? = null) {
        companion object {
            val ALLOW = Decision(true)
        }
    }

    /** 终端命令闸：在 TerminalTool 真正执行前调用。 */
    fun checkTerminal(permission: PermissionLevel, command: String): Decision {
        val raw = command.trim()
        if (raw.isEmpty()) return Decision(false, "命令为空")
        if (permission == PermissionLevel.FULL) return Decision.ALLOW

        DANGEROUS.firstOrNull { it.containsMatchIn(raw) }?.let { hit ->
            return Decision(
                false,
                deniedCommand(permission, "这条命令命中危险操作拦截（规则：" + hit.pattern.take(48) + "）")
            )
        }

        val outside = EXTERNAL_MOUNTS.firstOrNull { raw.contains(it) }
        if (outside != null) {
            return Decision(
                false,
                deniedCommand(
                    permission,
                    "命令里出现了 App 私有域之外的位置 " + outside +
                        "；工作区档只允许在 Ubuntu 根文件系统与工作区内操作"
                )
            )
        }
        return Decision.ALLOW
    }

    /**
     * 文件路径闸：把「三档」落到具体路径上，档位名与 mode 前缀一一对应。
     *
     * - 只读：写入一律拒绝（只读档的承诺是不修改存储）；
     * - 工作区：写入只能落在工作区根或 `<workspace>/tmp` 内（canonical 比较）；
     * - 完全：写入全部放行。
     *
     * 本函数只判定写入边界，读取范围由 [PathAccess] 判定。
     * 越界只拒绝 + 给提权提示，**不弹审批**：选择权留在设置的档位上，闸门保持确定性。
     */
    fun checkPath(permission: PermissionLevel, root: File, path: String, write: Boolean): Decision =
        checkPathCore(permission, root, path, write)

    private fun checkPathCore(
        permission: PermissionLevel,
        root: File?,
        path: String,
        write: Boolean
    ): Decision {
        val raw = path.trim()
        if (raw.isEmpty()) return Decision(false, deniedFile(permission, "路径为空"))
        // 读不设域：三档都放行，不需要工作区根
        if (!write) return Decision.ALLOW

        // 绝对禁区先于档位：App 核心数据目录任何档位都不许直写。
        // 放在完全档提前返回之前，否则「完全档」会使该检查被整体跳过。
        val guarded = root?.let { runCatching { PathGate.resolve(it, raw) }.getOrNull() }
            ?: if (raw.startsWith("/")) File(raw) else null
        guarded?.let { file ->
            HighRiskGate.protectedWriteReason(file)?.let { reason ->
                return Decision(false, reason)
            }
        }

        if (permission == PermissionLevel.FULL) return Decision.ALLOW
        if (permission == PermissionLevel.READ_ONLY) {
            return Decision(false, deniedFile(permission, "只读档不允许写文件：" + raw))
        }

        val base = root
            ?: return Decision(false, deniedFile(permission, "拿不到当前工作区根，无法判断是否越界：" + raw))
        val target = runCatching { PathGate.resolve(base, raw) }.getOrElse {
            return Decision(false, deniedFile(permission, "路径不合法：" + (it.message ?: "未知原因")))
        }
        if (!PathGate.isInsideWorkspaceOrTemp(base, target)) {
            return Decision(false, deniedFile(permission, "只能写工作区内或工作区 tmp/ 里的路径：" + raw))
        }
        return Decision.ALLOW
    }

    // ---- 统一拒绝文案 ----

    /** 路径类拒绝：[deniedFile] 的固定格式 + 中文原因 + 提权提示。 */
    fun deniedFile(permission: PermissionLevel, reason: String): String =
        deny(SANDBOX_FILE, permission, reason, ESCALATE_FILE)

    /** 命令类拒绝。 */
    fun deniedCommand(permission: PermissionLevel, reason: String): String =
        deny(SANDBOX_COMMAND, permission, reason, ESCALATE_COMMAND)

    /** 旧签名的兼容层：历史调用点均用于命令类拒绝，语义等同 [deniedCommand]。 */
    fun deniedText(permission: PermissionLevel, reason: String): String = deniedCommand(permission, reason)

    /**
     * 拼一条拒绝文案：`[sandbox: <类别> denied under <mode> mode] 档位「中文」：<原因><提权提示>`。
     *
     * 档位写两遍（mode 给机器看、中文给人看）是有意的：前缀必须严格可解析，
     * 而用户/模型看到的正文仍是全中文，不用去猜 workspace 是哪一档。
     */
    private fun deny(
        prefix: String,
        permission: PermissionLevel,
        reason: String,
        escalate: String
    ): String = prefix + modeOf(permission) + " mode] 档位「" + permission.displayName() + "」：" + reason + escalate

    /** 档位 → 前缀里的 mode 名（read-only / workspace / danger-full-access），与提示词里的叫法保持同一套。 */
    private fun modeOf(permission: PermissionLevel): String = when (permission) {
        PermissionLevel.READ_ONLY -> "read-only"
        PermissionLevel.WORKSPACE -> "workspace"
        PermissionLevel.FULL -> "danger-full-access"
    }
}
