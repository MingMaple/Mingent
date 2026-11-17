package com.zmf.agent.capability.tools.file.write_file

import com.zmf.agent.capability.tools.PathAccess
import com.zmf.agent.core.store.workspaceIoForRoot
import com.zmf.agent.core.log.AuditLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.displayName
import com.zmf.agent.capability.tools.file.support.FsTarget
import com.zmf.agent.capability.tools.file.support.displayPathOf
import com.zmf.agent.capability.tools.file.support.targetFor
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** 写文本文件：自动建目录，以「临时文件 + 重命名」原子替换，工作区外需要「完全」权限。 */
class WriteFileTool : Tool {

    override val name = "write_file"

    /** 写文件：只读档与计划模式都必须拒绝。 */
    override val mutates = true

    override val description =
        "把 UTF-8 文本整文件写入路径，不支持追加，父目录不存在时自动创建。" +
            "只改已有文件的一小段时用 edit_file，不要整篇重写。" +
            "不设字符上限；工作区档可用，越界需要「完全」权限。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "file_path": {"type": "string", "description": "要写入的文件路径，相对工作区或绝对路径；文件不存在时新建"},
            "content": {"type": "string", "description": "完整文件内容，会整文件覆盖原内容，不设字符上限"}
          },
          "required": ["file_path", "content"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val rawPath = args.optString("file_path", "").trim().ifEmpty { args.optString("path", "").trim() }
        if (rawPath.isEmpty()) return ToolResult.Error("[E_INVALID] file_path 不能为空：请给出要写入的文件路径")
        if (!args.has("content")) return ToolResult.Error("[E_INVALID] 缺少参数 content：请给出完整文件内容")

        val content = args.optString("content", "")

        val io = workspaceIoForRoot(ctx.context, ctx.workspaceRoot)
        io.problem?.let { return ToolResult.Error(it) }
        val file = runCatching { PathGate.resolve(ctx.workspaceRoot, rawPath) }
            .getOrElse { return ToolResult.Error("[E_INVALID] 路径不合法：" + (it.message ?: "无法解析") + "；请检查 file_path 后重试") }
        val target = targetFor(io, file)
            ?: return ToolResult.Error(
                "[E_PATH_OUTSIDE] 这个工作区是系统授权的目录（SAF），只能读写它自己的目录，请用相对路径（如 notes/a.txt）：" + rawPath
            )
        val inWorkspace = target.inWorkspace

        // 绝对禁区：App 核心数据（shared_prefs / databases / appdata / mingmem / 审计日志…）任何档位都不许直写。
        // 这些数据只能走各自的结构化接口（mem / 设置 / 会话存储），绕过接口直接改文件是
        // 「能力由数据流持有」的反面。
        if (!inWorkspace) {
            HighRiskGate.protectedWriteReason(target.local)?.let { reason ->
                AuditLog.record(
                    ctx.context,
                    "deny",
                    "tool" to "write_file",
                    "op" to "write",
                    "session" to ctx.sessionId,
                    "permission" to ctx.permission.displayName(),
                    "target" to target.local.absolutePath,
                    "rule" to "protected-write",
                    "status" to "non-overridable"
                )
                return ToolResult.Error(reason)
            }
        }

        // 权限口径：工作区里只读档不能写；工作区外必须「完全」档
        val canWrite = if (inWorkspace) {
            ctx.permission != PermissionLevel.READ_ONLY
        } else {
            PathAccess.canWrite(ctx, target.local)
        }
        if (!canWrite) {
            return ToolResult.Error(
                "当前权限档是「" + ctx.permission.displayName() + "」，不能写入这个路径：" + rawPath +
                    "。只读档不能写任何文件；工作区档只能写工作区内；越界需要切到「完全」权限。"
            )
        }
        if (target.isDirectory()) return ToolResult.Error("[E_PATH_NOT_FILE] 目标是目录，不能当文件写入：" + target.display + "；请换一个文件路径")

        val existed = target.exists()
        val failure = runCatching { writeAtomically(target, content) }.exceptionOrNull()
        if (failure != null) {
            return ToolResult.Error("[E_WRITE] 写入失败：" + (failure.message ?: failure.javaClass.simpleName) + "；请检查目标目录是否可写")
        }

        val displayPath = displayPathOf(target)
        return ToolResult.Ok(
            "<path>" + displayPath + "</path>\n" +
                "<type>file</type>\n" +
                "<content>\n" +
                (if (existed) "已覆盖文件" else "已新建文件") + "\n" +
                "</content>"
        )
    }
}

/**
 * 原子写：先写同目录临时文件再重命名；SAF 目录没有 rename 接口，只能截断重写。
 */
private fun writeAtomically(target: FsTarget, text: String) {
    if (target.isSaf) {
        target.writeText(text)
        return
    }
    val file = target.local
    val dir = file.parentFile ?: throw IOException("目标没有父目录：" + file.absolutePath)
    if (!dir.exists() && !dir.mkdirs()) throw IOException("建不了父目录：" + dir.absolutePath)
    val temp = File.createTempFile("write-", ".tmp", dir)
    try {
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(file)) throw IOException("无法用临时文件替换目标：" + file.absolutePath)
    } finally {
        if (temp.exists()) temp.delete()
    }
}
