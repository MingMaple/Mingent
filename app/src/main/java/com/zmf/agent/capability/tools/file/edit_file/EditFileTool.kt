package com.zmf.agent.capability.tools.file.edit_file

import com.zmf.agent.capability.tools.PathAccess
import com.zmf.agent.core.store.workspaceIoForRoot
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

/**
 * 按原文精确替换已有文件中的一段内容。
 *
 * 为什么需要它：write_file 只能整文件覆盖，改一行也要把全文重新生成一遍，
 * 大文件既费上下文，也容易在重写时误改无关内容。本工具按原文匹配，
 * 只把命中的片段换掉，其余字节原样保留（UTF-8、换行符与 BOM 都不归一化）。
 *
 * 为什么不用行号定位：行号会随前文的任何改动漂移；原文片段本身就是内容指纹。
 */
class EditFileTool : Tool {

    override val name = "edit_file"

    /** 编辑会写盘：只读档与计划模式都必须拒绝。 */
    override val mutates = true

    override val description =
        "按字面量精确替换已有文件中的一段内容，保留原换行符与 BOM。" +
            "改前先用 read_file 读取原文；新建文件或整篇重写改用 write_file。" +
            "old_string 须与原文逐字一致，replace_all=false 时要求恰好命中 1 处；工作区档可用，越界需要「完全」权限。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "file_path": {"type": "string", "description": "文件路径，相对工作区或绝对路径；文件必须已存在"},
            "old_string": {"type": "string", "description": "要替换的原文片段，必须与文件内容逐字一致，含缩进与换行，不能为空"},
            "new_string": {"type": "string", "description": "替换后的内容；删除该片段时传空字符串"},
            "replace_all": {"type": "boolean", "description": "为 true 时替换全部匹配，为 false 时要求恰好匹配 1 处；默认 false"}
          },
          "required": ["file_path", "old_string", "new_string"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val rawPath = args.optString("file_path", "").trim().ifEmpty { args.optString("path", "").trim() }
        if (rawPath.isEmpty()) return ToolResult.Error("[E_INVALID] file_path 不能为空：请给出要编辑的文件路径")
        if (!args.has("old_string") || args.isNull("old_string")) {
            return ToolResult.Error("[E_INVALID] 缺少参数 old_string：请给出要替换的原文片段")
        }
        if (!args.has("new_string") || args.isNull("new_string")) {
            return ToolResult.Error("[E_INVALID] 缺少参数 new_string：删除该片段时传空字符串")
        }
        val oldString = args.optString("old_string", "")
        val newString = args.optString("new_string", "")
        if (oldString.isEmpty()) {
            return ToolResult.Error("[E_INVALID] old_string 不能为空：请给出要替换的原文片段")
        }
        if (oldString == newString) {
            return ToolResult.Error("[E_INVALID] old_string 与 new_string 不能相同：请给出实际改动")
        }
        val replaceAll = args.optBoolean("replace_all", false)

        val io = workspaceIoForRoot(ctx.context, ctx.workspaceRoot)
        io.problem?.let { return ToolResult.Error(it) }
        val file = runCatching { PathGate.resolve(ctx.workspaceRoot, rawPath) }
            .getOrElse { return ToolResult.Error("[E_INVALID] 路径不合法：" + (it.message ?: "无法解析") + "；请检查 file_path 后重试") }
        val target = targetFor(io, file)
            ?: return ToolResult.Error(
                "[E_PATH_OUTSIDE] 这个工作区是系统授权的目录（SAF），只能读写它自己的目录，请用相对路径（如 notes/a.txt）：" + rawPath
            )
        val displayPath = displayPathOf(target)
        if (!target.exists()) {
            return ToolResult.Error("[E_NOT_FOUND] 文件不存在：" + displayPath + "；新建文件请用 write_file")
        }
        if (target.isDirectory()) {
            return ToolResult.Error("[E_PATH_NOT_FILE] 目标不是普通文件：" + displayPath + "；请改用具体文件")
        }
        if (!target.isRegularFile()) {
            return ToolResult.Error("[E_PATH_NOT_FILE] 目标不是普通文件：" + displayPath + "；请改用具体文件")
        }

        // 保护区：工作区内外一律判定，不沿用 write_file 只在越界时判定的旧写法。
        editProtectedReason(target.local)?.let { return ToolResult.Error(it) }
        if (!PathAccess.canWrite(ctx, target.local)) {
            return ToolResult.Error("当前权限档是「" + ctx.permission.displayName() + "」，不能修改这个路径：" + rawPath + "；只读档不能改任何文件，越界需要切到「完全」权限。")
        }

        val size = target.size()
        if (size > MAX_EDIT_BYTES) {
            return ToolResult.Error(
                "[E_TOO_LARGE] 文件 " + size + " 字节超过 " + MAX_EDIT_BYTES +
                    " 字节上限；请缩小文件，或在 terminal 中用 sed 等工具完成"
            )
        }
        val original = runCatching { target.readText() }.getOrElse {
            return ToolResult.Error("[E_WRITE] 读取失败：" + displayPath + "：" + (it.message ?: it.javaClass.simpleName) + "；请确认文件可读")
        }

        val edited = when (val outcome = applyTextEdit(original, oldString, newString, replaceAll)) {
            TextEditResult.EmptyOld -> return ToolResult.Error("[E_INVALID] old_string 不能为空：请给出要替换的原文片段")
            TextEditResult.NotFound -> return ToolResult.Error(
                "[E_PATCH_CONTEXT] " + displayPath + "：old_string 未匹配；先用 read_file 读取文件，" +
                    "再用其中实际存在的片段重试（必须逐字一致，含缩进与换行）"
            )
            is TextEditResult.Ambiguous -> return ToolResult.Error(
                "[E_PATCH_CONTEXT] " + displayPath + "：old_string 命中 " + outcome.matches +
                    " 处，需恰好 1 处；请提供更长的唯一片段，或置 replace_all=true"
            )
            is TextEditResult.Ok -> outcome
        }

        val failure = runCatching { writeEdited(target, edited.text) }.exceptionOrNull()
        if (failure != null) {
            return ToolResult.Error("[E_WRITE] 写入失败：" + (failure.message ?: failure.javaClass.simpleName) + "；请检查目标目录是否可写")
        }

        return ToolResult.Ok(
            if (replaceAll) {
                "已更新文件，全部匹配均已替换。"
            } else {
                "已更新文件。"
            }
        )
    }

    /** 先写同目录临时文件再 rename；SAF 目录没有 rename 接口，只能截断重写。 */
    private fun writeEdited(target: FsTarget, text: String) {
        if (target.isSaf) {
            target.writeText(text)
            return
        }
        val file = target.local
        val dir = file.parentFile ?: throw IOException("目标没有父目录：" + file.absolutePath)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("建不了父目录：" + dir.absolutePath)
        val temp = File.createTempFile("edit-", ".tmp", dir)
        try {
            temp.writeText(text, Charsets.UTF_8)
            if (!temp.renameTo(file)) throw IOException("无法用临时文件替换目标：" + file.absolutePath)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private companion object {
        /** 编辑要整份读入内存：上限 8 MB，更大的文件交给 terminal。 */
        const val MAX_EDIT_BYTES = 8L * 1024L * 1024L
    }
}

/**
 * 编辑前的保护区判定：工作区内外的目标都必须过这一关。
 *
 * write_file 的工作区分支目前不判保护区（工作区可能被映射到 App 私有目录），
 * 编辑工具不沿用该写法，两条分支一律判定。
 */
internal fun editProtectedReason(target: File): String? = HighRiskGate.protectedWriteReason(target)

/** [applyTextEdit] 的结论：要么给出替换后的全文，要么给出可判定的失败原因。 */
internal sealed interface TextEditResult {

    data class Ok(
        val text: String,
        val replacements: Int,
        val firstLine: Int,
        val oldLines: Int,
        val newLines: Int
    ) : TextEditResult

    data object EmptyOld : TextEditResult

    data object NotFound : TextEditResult

    data class Ambiguous(val matches: Int) : TextEditResult
}

/**
 * 纯文本替换：不做行尾归一化，也不动编码与 BOM。
 *
 * [replaceAll] 为 false 时要求全文恰好匹配 1 处：0 处与多处都不可判定模型想改哪一处。
 */
internal fun applyTextEdit(
    text: String,
    oldString: String,
    newString: String,
    replaceAll: Boolean
): TextEditResult {
    if (oldString.isEmpty()) return TextEditResult.EmptyOld
    val indexes = ArrayList<Int>()
    var from = 0
    while (true) {
        val found = text.indexOf(oldString, from)
        if (found < 0) break
        indexes.add(found)
        from = found + oldString.length
    }
    if (indexes.isEmpty()) return TextEditResult.NotFound
    if (!replaceAll && indexes.size > 1) return TextEditResult.Ambiguous(indexes.size)

    val builder = StringBuilder(text.length)
    var cursor = 0
    for (index in indexes) {
        builder.append(text, cursor, index)
        builder.append(newString)
        cursor = index + oldString.length
    }
    builder.append(text, cursor, text.length)
    val newText = builder.toString()
    return TextEditResult.Ok(
        text = newText,
        replacements = indexes.size,
        firstLine = text.substring(0, indexes.first()).count { it == '\n' } + 1,
        oldLines = lineCount(text),
        newLines = lineCount(newText)
    )
}

/** 与 write_file 同一口径：结尾换行不算多一行。 */
internal fun lineCount(text: String): Int {
    val trimmed = text.trimEnd('\n')
    return if (trimmed.isEmpty()) 0 else trimmed.count { it == '\n' } + 1
}
