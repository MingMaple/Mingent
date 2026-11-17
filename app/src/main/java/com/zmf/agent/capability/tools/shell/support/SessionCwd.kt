package com.zmf.agent.capability.tools.shell.support

import android.content.Context
import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import java.io.File

/**
 * 终端默认工作目录的解析（工作区级）。
 *
 * 默认目录直接由当前会话的工作区根派生：工作区根能映射进 PRoot 时落在工作区根，
 * 映射不了（物理 SD 卡、SAF 未映射位置等）时回落 `/root`。
 * 只有 terminal 与 job_start 使用；文件工具跟随工作区，不经过这里。
 *
 * 解析优先级只有两级：显式 `workdir` > 派生值。派生值失效时静默回落并记一行日志，
 * 不向模型提示——每次执行都提示会稀释上下文中的任务信息。
 */
object SessionCwd {

    private const val TAG = "terminal"

    /** 工作区不可映射或已失效时的 guest 工作目录。 */
    const val DEFAULT_GUEST = "/root"

    /** 显式目录参数越界的统一回执。 */
    const val ERR_OUTSIDE = "[E_CWD_OUTSIDE] 工作目录必须在当前工作区内"

    /** 已记过回落日志的工作区路径：同一工作区只留一行，避免每条命令重复刷日志。 */
    @Volatile
    private var lastUnmappedRoot: String? = null

    /**
     * 执行期显式 `workdir`／`cwd` 参数的越界判据：任何一段为 `..` 即越界。
     *
     * 相对路径（`../../..`、`a/../../b`）与 guest 绝对路径（`/../../..`）同一判据：
     * 绝对路径先按 `/` 分段再判断，`/tmp` 这类合法 guest 路径不受影响。
     */
    fun isOutsideExplicitWorkdir(param: String): Boolean =
        param.split('/').any { it == ".." }

    /**
     * 执行期解析（terminal / job_start 共用）。返回 null 表示显式目录参数越界，调用方须拒绝执行。
     *
     * [explicit] 是模型本调用给的 `workdir`（guest 路径）：非空时先判越界，含 `..` 段一律拒绝
     * （`../../..` 会让 `cd` 落到 PRoot 根，303 轮已实测）；非「完全」权限档下再按 bind 表映射回
     * 宿主，要求落在工作区内，否则同样拒绝——否则 `/sdcard` 这类 guest 绝对路径可绕过工作区边界。
     * 完全档不做映射，路径原样返回。未传时取工作区派生的终端目录。
     */
    fun resolveGuest(ctx: ToolContext, explicit: String): String? =
        resolveGuest(
            HighRiskGate.SandboxEnv.of(ctx.context),
            ctx.permission,
            ctx.workspaceRoot,
            ctx.sessionId,
            explicit
        )

    /** [resolveGuest] 的纯函数重载：判定只依赖给定的环境快照，供单测使用。 */
    fun resolveGuest(
        env: HighRiskGate.SandboxEnv,
        permission: PermissionLevel,
        workspaceRoot: File,
        sessionId: String,
        explicit: String
    ): String? {
        val param = explicit.trim()
        if (param.isNotEmpty()) {
            if (isOutsideExplicitWorkdir(param)) {
                FileLog.log(
                    TAG,
                    "工作目录参数越界，已拒绝执行：" + param + "（会话 " + sessionId + "）"
                )
                return null
            }
            if (permission != PermissionLevel.FULL) {
                val host = HighRiskGate.mapGuestToHost(env, param)
                if (!PathGate.isInside(workspaceRoot, host)) {
                    FileLog.log(
                        TAG,
                        "工作目录参数不在工作区内，已拒绝执行：" + param + "（会话 " + sessionId + "）"
                    )
                    return null
                }
            }
            return param
        }
        return workspaceTerminalGuest(env, workspaceRoot, sessionId)
    }

    /** 工作区根对应的 guest 终端目录；不可映射或已失效时返回 [DEFAULT_GUEST]。 */
    fun workspaceTerminalGuest(context: Context, workspaceRoot: File): String =
        workspaceTerminalGuest(HighRiskGate.SandboxEnv.of(context), workspaceRoot, "")

    /**
     * [workspaceTerminalGuest] 的纯函数重载。
     *
     * 工作区可能在工作期间被移动或删除，派生值必须每次重新核对：
     * 目录不存在或不在 bind 表内即静默回落，不信任任何缓存。
     */
    fun workspaceTerminalGuest(
        env: HighRiskGate.SandboxEnv,
        workspaceRoot: File,
        sessionId: String
    ): String {
        if (!workspaceRoot.isDirectory) {
            logFallback(workspaceRoot, "工作区目录不存在", sessionId)
            return DEFAULT_GUEST
        }
        val guest = HighRiskGate.hostToGuest(env, workspaceRoot)
        if (guest == null) {
            logFallback(workspaceRoot, "工作区不在终端映射表内", sessionId)
            return DEFAULT_GUEST
        }
        return guest
    }

    /** 同一工作区只记一行回落日志，避免每条命令重复写入。 */
    private fun logFallback(workspaceRoot: File, reason: String, sessionId: String) {
        val key = workspaceRoot.path
        if (lastUnmappedRoot == key) return
        lastUnmappedRoot = key
        FileLog.log(
            TAG,
            reason + "，回落 " + DEFAULT_GUEST + "：" + key + "（会话 " + sessionId + "）"
        )
    }
}
