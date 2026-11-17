package com.zmf.agent.capability.tools.file.read_file

import com.zmf.agent.capability.tools.PathAccess
import com.zmf.agent.core.store.workspaceIoForRoot
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.displayName
import com.zmf.agent.capability.tools.file.support.displayPathOf
import com.zmf.agent.capability.tools.file.support.targetFor
import org.json.JSONObject

/**
 * 读文本文件。
 *
 * 规格与 DSH 的 read 工具一致：file_path 必填，offset 从 1 起（默认 1），limit 默认且最大 2000 行；
 * 单行超过 2000 字符时截断，单次输出超过 50 KiB 时提前结束窗口。
 * 结果使用 `<path>` / `<type>` / `<content>` 信封，末尾给出续读或文件结束页脚，
 * 使模型无需额外说明即可用 offset 接着读。
 *
 * 参数 path / startLine / maxLines 是早期会话使用过的旧名称，仍然接受，但不再写入声明。
 */
class ReadFileTool : Tool {

    override val name = "read_file"

    override val description =
        "读取 UTF-8 文本文件的正文，按行号返回指定区间。" +
            "本对话已读过、且随后未被改动的文件不要为确认再读一遍，内容被压缩成摘要时才重读。" +
            "单次输出上限 50 KiB，limit 上限 2000 行；工作区内只读档可用，越界需要「完全」权限。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "file_path": {"type": "string", "description": "要读取的文件路径，相对工作区或绝对路径"},
            "offset": {"type": "integer", "description": "从第几行开始读取，取值从 1 起，默认 1"},
            "limit": {"type": "integer", "description": "最多返回多少行，默认 2000，上限 2000"}
          },
          "required": ["file_path"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val rawPath = firstText(args, "file_path", "path")
        if (rawPath.isEmpty()) return ToolResult.Error("[E_INVALID] file_path 不能为空：请给出要读取的文件路径")

        val offset = positiveInt(args, "offset", "startLine", 1)
            ?: return ToolResult.Error("[E_INVALID] offset 必须是不小于 1 的整数，默认 1")
        val limit = positiveInt(args, "limit", "maxLines", MAX_LINES)
            ?: return ToolResult.Error("[E_INVALID] limit 必须是不小于 1 的整数，默认 2000")
        if (limit > MAX_LINES) {
            return ToolResult.Error("[E_INVALID] limit 不得超过 " + MAX_LINES + "；请调小后重试")
        }

        val io = workspaceIoForRoot(ctx.context, ctx.workspaceRoot)
        io.problem?.let { return ToolResult.Error(it) }
        val file = runCatching { PathGate.resolve(ctx.workspaceRoot, rawPath) }
            .getOrElse { return ToolResult.Error("[E_INVALID] 路径不合法：" + (it.message ?: "无法解析") + "；请检查 file_path 后重试") }
        val target = targetFor(io, file)
            ?: return ToolResult.Error(
                "[E_PATH_OUTSIDE] 这个工作区是系统授权的目录（SAF），只能读写它自己的目录，请用相对路径（如 notes/a.txt）：" + rawPath
            )
        // 回收站对 Agent 为完全禁入区域，且**与档位无关**（完全档同样适用）：判定必须置于档位检查之前，
        // 否则「完全档可读工作区外」规则会使回收站一并放行。此处使用 canonical 路径，软链接无法绕过
        // （仅比较字面路径可被软链接绕过，也不能被 `canRead` 在完全档之外短路）。
        if (HighRiskGate.isTrashPathDeep(file, ctx.context.packageName)) {
            return ToolResult.Error(HighRiskGate.trashReadText())
        }
        if (!target.inWorkspace && !PathAccess.canRead(ctx, file)) {
            return ToolResult.Error(
                "当前权限档是「" + ctx.permission.displayName() + "」，不能读取工作区外的路径：" + rawPath +
                    "。工作区内的文件用相对路径直接读；越界需要切到「完全」权限。"
            )
        }
        val displayPath = displayPathOf(target)
        if (!target.exists()) {
            return ToolResult.Error("[E_NOT_FOUND] 文件不存在：" + displayPath + "；请确认路径，或用 glob 查找")
        }
        if (target.isDirectory()) {
            return ToolResult.Error("[E_PATH_NOT_FILE] 目标不是普通文件：" + displayPath + "；请改用目录内的具体文件")
        }
        if (!target.isRegularFile()) {
            return ToolResult.Error("[E_PATH_NOT_FILE] 目标不是普通文件：" + displayPath + "；请改用目录内的具体文件")
        }

        val lines = ArrayList<Pair<Int, String>>()
        var totalLines = 0
        var outputBytes = 0
        var truncatedByBytes = false

        runCatching {
            target.openReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    totalLines++
                    if (truncatedByBytes) continue
                    if (totalLines < offset) continue
                    if (lines.size >= limit) continue
                    val safe = if (line.length > MAX_LINE_CHARS) {
                        line.take(MAX_LINE_CHARS) + "... (line truncated to " + MAX_LINE_CHARS + " chars)"
                    } else {
                        line
                    }
                    val bytes = safe.toByteArray(Charsets.UTF_8).size + if (lines.isEmpty()) 0 else 1
                    if (outputBytes + bytes > MAX_OUTPUT_BYTES) {
                        truncatedByBytes = true
                        continue
                    }
                    outputBytes += bytes
                    lines.add(totalLines to safe)
                }
            }
        }.onFailure { return ToolResult.Error("[E_WRITE] 读取失败：" + displayPath + "：" + (it.message ?: it.javaClass.simpleName) + "；请确认文件可读") }

        // 文件为空且请求从第 1 行开始时合法；其余越过末尾的 offset 一律按官方口径拒绝。
        if (!truncatedByBytes && offset > totalLines && !(totalLines == 0 && offset == 1)) {
            return ToolResult.Error(
                "[E_INVALID] offset " + offset + " 超出范围：" + displayPath + " 共 " + totalLines + " 行；请改读文件内的行号"
            )
        }

        val endLine = lines.lastOrNull()?.first ?: maxOf(0, offset - 1)
        val footer = when {
            truncatedByBytes ->
                "(Output capped. Showing lines " + offset + "-" + endLine + ". Use offset=" + (endLine + 1) + " to continue.)"
            endLine < totalLines ->
                "(Showing lines " + offset + "-" + endLine + " of " + totalLines + ". Use offset=" + (endLine + 1) + " to continue.)"
            else -> "(End of file - total " + totalLines + " lines)"
        }
        val body = buildString {
            append("<path>").append(displayPath).append("</path>\n")
            append("<type>file</type>\n")
            append("<content>\n")
            if (lines.isEmpty()) {
                append(footer)
            } else {
                lines.forEachIndexed { index, (number, text) ->
                    if (index > 0) append('\n')
                    append(number).append(": ").append(text)
                }
                append("\n\n").append(footer)
            }
            append("\n</content>")
        }
        return ToolResult.Ok(body)
    }

    /** 取首个非空字符串参数：新名称优先，旧名称兜底。 */
    private fun firstText(args: JSONObject, primary: String, alias: String): String {
        val value = args.optString(primary, "").trim()
        if (value.isNotEmpty()) return value
        return args.optString(alias, "").trim()
    }

    /** 取正整数参数；缺失时用 [fallback]，给出但小于 1 时返回 null。 */
    private fun positiveInt(args: JSONObject, primary: String, alias: String, fallback: Int): Int? {
        val key = when {
            args.has(primary) && !args.isNull(primary) -> primary
            args.has(alias) && !args.isNull(alias) -> alias
            else -> return fallback
        }
        val value = args.optInt(key, fallback)
        return if (value < 1) null else value
    }

    private companion object {
        const val MAX_LINES = 2000
        const val MAX_LINE_CHARS = 2000

        /** 单次读取所选行的 UTF-8 字节上限。 */
        const val MAX_OUTPUT_BYTES = 50 * 1024
    }
}
