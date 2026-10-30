package com.zmf.agent.capability.tools

import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.core.model.PermissionLevel
import java.io.File

/**
 * 三档权限在文件路径上的落地判定。
 *
 * 为什么不在 [PathGate] 里：那是 core 的纯路径工具（解析、越界、规范化），
 * 不认识权限档，也不该认识 [ToolContext] 与 [HighRiskGate]——那是工具层的业务规则。
 * 放在这里，依赖方向保持 tools → core 单向。
 */
object PathAccess {

    /** 能否读：回收站永不放行；工作区（含 tmp）内放行；其余只有「完全」档。 */
    fun canRead(ctx: ToolContext, file: File): Boolean {
        // 回收站是 Agent 的黑区（主体分离）：读也不行，否则「删完看一眼再清空」就成了闭环
        if (HighRiskGate.isTrashPath(file.path, ctx.context.packageName)) return false
        if (PathGate.isInsideWorkspaceOrTemp(ctx.workspaceRoot, file)) return true
        return ctx.permission == PermissionLevel.FULL
    }

    /** 能否写：回收站与只读档永不放行；工作区（含 tmp）内放行；其余只有「完全」档。 */
    fun canWrite(ctx: ToolContext, file: File): Boolean {
        if (HighRiskGate.isTrashPath(file.path, ctx.context.packageName)) return false
        if (ctx.permission == PermissionLevel.READ_ONLY) return false
        if (PathGate.isInsideWorkspaceOrTemp(ctx.workspaceRoot, file)) return true
        return ctx.permission == PermissionLevel.FULL
    }
}
