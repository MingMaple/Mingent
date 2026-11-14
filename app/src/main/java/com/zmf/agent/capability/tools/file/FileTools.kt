package com.zmf.agent.capability.tools.file
import com.zmf.agent.capability.tools.PathAccess

import com.zmf.agent.core.store.WorkspaceEntry
import com.zmf.agent.core.store.WorkspaceIo
import com.zmf.agent.core.store.workspaceIoForRoot
import com.zmf.agent.core.log.AuditLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.displayName
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException

/**
 * 这一组工具均通过 [WorkspaceIo] 读写：工作区根为普通目录时沿用 java.io.File 的原有行为，
 * 根为 SAF 授权目录（用户通过系统选择器选择的文件夹）时使用 DocumentsContract，
 * 因此「用户选择的目录无法写入」这一问题在工具层已不存在。
 *
 * 权限规则未变：工作区内可读写；工作区外的绝对路径仍需「完全」权限（该路径仅 File 支持）。
 */

/**
 * 读文本文件。
 *
 * 参数兼容两套名称：以 offset / limit 为准，startLine / maxLines 保留给旧会话。
 * 默认一次最多 400 行，上限 2000 行 / 64000 字符；超出时截断并提示模型使用 offset 继续读取，
 * 避免长文件使上下文过度膨胀。
 *
 * 「共 N 行」中的 N 需实际统计：文件不超过 5MB 时才统计，更大时返回 unknown，
 * 以免仅为取得一个数字而重新读取大文件。
 */
class ReadFileTool : Tool {

    override val name = "read_file"

    override val description =
        "读取工作区里的文本文件，返回带行号的文本。路径可以是绝对路径，也可以是相对工作区的相对路径。" +
            "offset 指定从第几行开始读（从 1 起，默认 1），limit 指定最多返回多少行（默认 400）；" +
            "一次最多返回 2000 行或 64000 字符，超出会截断并提示用 offset 接着读。" +
            "旧参数 startLine / maxLines 仍然可用，但 offset / limit 优先。" +
            "本对话里已经读过、且期间没有别人改过的文件不要为「确认」再读一遍；内容已被压缩成摘要时才需要重读。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "文件路径，相对工作区或绝对路径"},
            "offset": {"type": "integer", "description": "从第几行开始读，从 1 开始，默认 1（等同旧的 startLine）"},
            "limit": {"type": "integer", "description": "最多读多少行，默认 400，上限 2000（等同旧的 maxLines）"},
            "startLine": {"type": "integer", "description": "旧参数，等同 offset；两个都给时以 offset 为准"},
            "maxLines": {"type": "integer", "description": "旧参数，等同 limit；两个都给时以 limit 为准"}
          },
          "required": ["path"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val rawPath = args.optString("path", "").trim()
        if (rawPath.isEmpty()) return ToolResult.Error("缺少参数 path")

        val io = workspaceIoForRoot(ctx.context, ctx.workspaceRoot)
        io.problem?.let { return ToolResult.Error(it) }
        val file = runCatching { PathGate.resolve(ctx.workspaceRoot, rawPath) }
            .getOrElse { return ToolResult.Error("路径不合法：" + (it.message ?: "未知原因")) }
        val target = targetFor(io, file)
            ?: return ToolResult.Error("这个工作区是系统授权的目录（SAF），只能读写它自己的目录，请用相对路径（如 notes/a.txt）：" + rawPath)
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
        if (!target.exists()) {
            return ToolResult.Error("文件不存在：" + target.display + "（可用 list_files 看工作区里有哪些文件）")
        }
        if (target.isDirectory()) {
            return ToolResult.Error("这是个目录，不是文件：" + target.display + "，请用 list_files")
        }
        if (!target.isRegularFile()) return ToolResult.Error("不是普通文件：" + target.display)

        // offset / limit 优先，没有就退回 startLine / maxLines
        val startLine = pickInt(args, "offset", "startLine", 1).coerceAtLeast(1)
        val maxLines = pickInt(args, "limit", "maxLines", DEFAULT_MAX_LINES).coerceIn(1, MAX_LINES)

        // 仅在文件较小时统计总行数：为大文件统计「共 N 行」需额外完整读取一遍，成本不划算。
        // 大小未知（SAF provider 不提供）时按小文件处理：仍统计行数，输出另有字符上限约束。
        val sizeBytes = target.size()
        val countAll = sizeBytes < 0 || sizeBytes <= MAX_COUNT_BYTES

        val builder = StringBuilder()
        var lineNo = 0
        var returned = 0
        var chars = 0
        var truncated = false

        runCatching {
            target.openReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNo++
                    if (lineNo < startLine) continue
                    if (returned >= maxLines) {
                        // 超出预算的行不再返回；小文件继续读到结尾只为数总行数
                        truncated = true
                        if (!countAll) break
                        continue
                    }
                    val safe = if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) + "…（本行已截断）" else line
                    if (chars + safe.length > MAX_TOTAL_CHARS) {
                        truncated = true
                        if (!countAll) break
                        continue
                    }
                    builder.append(lineNo).append("  ").append(safe).append('\n')
                    chars += safe.length
                    returned++
                }
            }
        }.onFailure { return ToolResult.Error("读取失败：" + (it.message ?: it.javaClass.simpleName)) }

        // 读到文件末尾（没截断）就一定知道总行数；截断时只有数过行才知道，否则报 unknown
        val total = if (!truncated || countAll) lineNo.toString() else "unknown"
        val endLine = startLine + returned - 1

        if (returned == 0) {
            return ToolResult.Ok(
                "文件：" + target.display +
                    "\n第 " + startLine + " 行起没有内容（文件是空的，或 offset 已超过文件末尾），共 " + total + " 行。"
            )
        }

        val header = buildString {
            append("文件：").append(target.display).append('\n')
            append("第 ").append(startLine).append("-").append(endLine).append(" 行 / 共 ").append(total).append(" 行\n")
            if (truncated) {
                append("（还有后续内容：用 offset=").append(endLine + 1).append(" 继续读取）")
            } else {
                append("(已到文件末尾)")
            }
            append('\n')
        }
        return ToolResult.Ok(header + builder.toString())
    }

    /** 取整型参数：优先新名字 [primary]，没有（或是 null）就退回旧名字 [alias]。 */
    private fun pickInt(args: JSONObject, primary: String, alias: String, fallback: Int): Int {
        if (!args.has(primary) || args.isNull(primary)) return args.optInt(alias, fallback)
        return args.optInt(primary, fallback)
    }

    private companion object {
        const val DEFAULT_MAX_LINES = 400
        const val MAX_LINES = 2000
        const val MAX_LINE_CHARS = 2000
        const val MAX_TOTAL_CHARS = 64000

        /** 超过这个大小就不数总行数了（报 unknown）。 */
        const val MAX_COUNT_BYTES = 5L * 1024L * 1024L
    }
}

/** 写文本文件：自动建目录，覆盖前不备份（工具层保持单文件直写），工作区外需要「完全」权限。 */
class WriteFileTool : Tool {

    override val name = "write_file"

    /** 写文件：只读档与计划模式都必须拒绝。 */
    override val mutates = true

    override val description =
        "把文本写入文件（UTF-8），父目录不存在会自动创建。整文件覆盖写，不支持追加。" +
            "相对路径相对工作区；工作区就是 Agent 可以自由改动的目录。" +
            "只改已有大文件的一小段时不要整文件覆盖：先用 read_file 看原文，改动很小就整体写回并在回复里说明改了哪几行。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "要写的文件路径，相对工作区或绝对路径"},
            "content": {"type": "string", "description": "完整文件内容，会覆盖原文件"}
          },
          "required": ["path", "content"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val rawPath = args.optString("path", "").trim()
        if (rawPath.isEmpty()) return ToolResult.Error("缺少参数 path")
        if (!args.has("content")) return ToolResult.Error("缺少参数 content")

        val content = args.optString("content", "")
        if (content.length > MAX_FILE_CHARS) {
            return ToolResult.Error("内容太长（" + content.length + " 字符），单次写入上限 " + MAX_FILE_CHARS + " 字符，请拆成多次写")
        }

        val io = workspaceIoForRoot(ctx.context, ctx.workspaceRoot)
        io.problem?.let { return ToolResult.Error(it) }
        val file = runCatching { PathGate.resolve(ctx.workspaceRoot, rawPath) }
            .getOrElse { return ToolResult.Error("路径不合法：" + (it.message ?: "未知原因")) }
        val target = targetFor(io, file)
            ?: return ToolResult.Error("这个工作区是系统授权的目录（SAF），只能读写它自己的目录，请用相对路径（如 notes/a.txt）：" + rawPath)
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
        if (target.isDirectory()) return ToolResult.Error("这是个目录，不能当文件写：" + target.display)

        val existed = target.exists()
        val oldSize = if (existed) target.size() else 0L
        // 覆盖前先统计原有行数：写入成功后在回执中给出「原 N 行 → 新 M 行」，
        // 多数情况下模型无需再调用 read_file 确认（提示词本身已要求不为确认而重读）。
        // 流式计数，避免把整份旧文件读进内存：覆盖的目标可能远大于新内容。
        val oldLines = if (existed) {
            runCatching {
                // 旧文件过大时不再为了一句「原 N 行」把它整份读一遍，回执里省略行数
                if (oldSize > MAX_LINE_COUNT_BYTES) return@runCatching -1
                target.openReader().use { reader ->
                    val buffer = CharArray(8192)
                    var chars = 0L
                    var newlines = 0L
                    var trailingNewlines = 0L
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        for (i in 0 until n) {
                            chars++
                            if (buffer[i] == '\n') {
                                newlines++
                                trailingNewlines++
                            } else {
                                trailingNewlines = 0
                            }
                        }
                    }
                    // 与 read_file 的口径一致：结尾换行不算多一行，只有换行的文件算 0 行
                    if (chars == trailingNewlines) 0 else (newlines - trailingNewlines + 1).toInt()
                }
            }.getOrDefault(-1)
        } else {
            0
        }

        runCatching { target.writeText(content) }
            .onFailure { return ToolResult.Error("写入失败：" + (it.message ?: it.javaClass.simpleName)) }

        // 行数和 read_file 的口径对齐：结尾的换行不算多出一行
        val trimmedForCount = content.trimEnd('\n')
        val lines = if (trimmedForCount.isEmpty()) 0 else trimmedForCount.count { it == '\n' } + 1
        val firstLine = content.trimStart('\n').lineSequence().firstOrNull()?.take(80).orEmpty()
        return ToolResult.Ok(
            (if (existed) "已覆盖 " else "已新建 ") + target.display +
                "（" + (if (existed && oldLines >= 0) "原 " + oldLines + " 行 → 新 " else "") + lines + " 行 / " +
                content.length + " 字符" +
                (if (existed) "，原大小 " + oldSize + " 字节" else "") + "）" +
                (if (firstLine.isNotEmpty()) "\n首行：" + firstLine else "")
        )
    }

    private companion object {
        const val MAX_FILE_CHARS = 1_000_000
        /** 统计旧文件行数的体积上限：超过就省略「原 N 行」，不为此整份读取。 */
        const val MAX_LINE_COUNT_BYTES = 32L * 1024L * 1024L
    }
}

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
        "按原文精确替换已有文件中的一段内容（UTF-8，保留原换行符与 BOM）。" +
            "old_string 必须与文件内容逐字一致（含缩进与换行）；replace_all=false 时要求恰好匹配 1 处，0 处或多处都会报错。" +
            "修改已有文件优先用它，不要整文件重写；新建文件或需要整篇重写时用 write_file。" +
            "它不创建文件、不改变编码；修改前先用 read_file 读取目标内容。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "文件路径，相对工作区或绝对路径"},
            "old_string": {"type": "string", "description": "要替换的原文片段，必须与文件内容逐字一致，含缩进与换行"},
            "new_string": {"type": "string", "description": "替换后的内容；删除该片段时传空字符串"},
            "replace_all": {"type": "boolean", "description": "是否替换全部匹配，默认 false；false 时要求恰好匹配 1 处"}
          },
          "required": ["path", "old_string", "new_string"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val rawPath = args.optString("path", "").trim()
        if (rawPath.isEmpty()) return ToolResult.Error("缺少参数 path")
        if (!args.has("old_string") || args.isNull("old_string")) {
            return ToolResult.Error("缺少参数 old_string：要替换的原文片段")
        }
        if (!args.has("new_string") || args.isNull("new_string")) {
            return ToolResult.Error("缺少参数 new_string：替换后的内容，删除该段时传空字符串")
        }
        val oldString = args.optString("old_string", "")
        val newString = args.optString("new_string", "")
        if (oldString.isEmpty()) {
            return ToolResult.Error("old_string 不能为空：请给出文件中实际存在的一段原文")
        }
        val replaceAll = args.optBoolean("replace_all", false)

        val io = workspaceIoForRoot(ctx.context, ctx.workspaceRoot)
        io.problem?.let { return ToolResult.Error(it) }
        val file = runCatching { PathGate.resolve(ctx.workspaceRoot, rawPath) }
            .getOrElse { return ToolResult.Error("路径不合法：" + (it.message ?: "未知原因")) }
        val target = targetFor(io, file)
            ?: return ToolResult.Error("这个工作区是系统授权的目录（SAF），只能读写它自己的目录，请用相对路径（如 notes/a.txt）：" + rawPath)
        if (!target.exists()) {
            return ToolResult.Error("文件不存在：" + target.display + "（新建文件请用 write_file）")
        }
        if (target.isDirectory()) {
            return ToolResult.Error("这是个目录，不是文件：" + target.display)
        }
        if (!target.isRegularFile()) return ToolResult.Error("不是普通文件：" + target.display)

        // 保护区：工作区内外一律判定，不沿用 write_file 只在越界时判定的旧写法。
        editProtectedReason(target.local)?.let { return ToolResult.Error(it) }
        if (!PathAccess.canWrite(ctx, target.local)) {
            return ToolResult.Error("当前权限档是「" + ctx.permission.displayName() + "」，不能修改这个路径：" + rawPath)
        }

        val size = target.size()
        if (size > MAX_EDIT_BYTES) {
            return ToolResult.Error(
                "文件太大（" + size + " 字节，上限 " + MAX_EDIT_BYTES + "）：精确修改这么大的文件请在 terminal 中用 sed 等工具完成"
            )
        }
        val original = runCatching { target.readText() }.getOrElse {
            return ToolResult.Error("读取失败：" + (it.message ?: it.javaClass.simpleName))
        }

        val edited = when (val outcome = applyTextEdit(original, oldString, newString, replaceAll)) {
            TextEditResult.EmptyOld ->
                return ToolResult.Error("old_string 不能为空：请给出文件中实际存在的一段原文")
            TextEditResult.NotFound ->
                return ToolResult.Error(
                    "未找到原文：" + target.display +
                        "。先用 read_file 读取文件，再用其中实际存在的片段重试；old_string 必须与原文逐字一致（含缩进与换行）"
                )
            is TextEditResult.Ambiguous ->
                return ToolResult.Error(
                    "匹配到 " + outcome.matches + " 处，无法确定替换位置：请提供更长的唯一片段，或置 replace_all=true"
                )
            is TextEditResult.Ok -> outcome
        }

        val failure = runCatching { writeEdited(target, edited.text) }.exceptionOrNull()
        if (failure != null) {
            return ToolResult.Error("写入失败：" + (failure.message ?: failure.javaClass.simpleName))
        }

        val delta = edited.newLines - edited.oldLines
        val deltaText = if (delta == 0) {
            "行数不变"
        } else {
            "行数 " + edited.oldLines + " → " + edited.newLines + "（" + (if (delta > 0) "+" else "") + delta + "）"
        }
        return ToolResult.Ok(
            "已编辑：" + target.display + "\n替换 " + edited.replacements + " 处，首处第 " + edited.firstLine + " 行；" + deltaText
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

/** 列目录：给出文件名、类型、大小，方便模型自己找文件。 */
class ListFilesTool : Tool {

    override val name = "list_files"

    override val description = "列出目录里的条目（目录在前），不传 path 就列工作区根目录。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "目录路径，默认工作区根目录"}
          }
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val rawPath = args.optString("path", "").trim()

        val io = workspaceIoForRoot(ctx.context, ctx.workspaceRoot)
        io.problem?.let { return ToolResult.Error(it) }
        // 不传路径 = 工作区根：不走路径解析，直接按根目录处理
        val target = if (rawPath.isEmpty()) {
            FsTarget.ofWorkspace(io, "")
        } else {
            val file = runCatching { PathGate.resolve(ctx.workspaceRoot, rawPath) }
                .getOrElse { return ToolResult.Error("路径不合法：" + (it.message ?: "未知原因")) }
            val resolved = targetFor(io, file)
                ?: return ToolResult.Error("这个工作区是系统授权的目录（SAF），只能读写它自己的目录，请用相对路径（如 notes/a.txt）：" + rawPath)
            // 同 read_file：回收站先于档位拦，且走 canonical（软链接绕不过）
            if (HighRiskGate.isTrashPathDeep(file, ctx.context.packageName)) {
                return ToolResult.Error(HighRiskGate.trashReadText())
            }
            if (!resolved.inWorkspace && !PathAccess.canRead(ctx, file)) {
                return ToolResult.Error("当前权限档是「" + ctx.permission.displayName() + "」，不能读取工作区外的路径：" + rawPath)
            }
            resolved
        }
        if (!target.exists()) return ToolResult.Error("目录不存在：" + target.display)
        if (!target.isDirectory()) return ToolResult.Error("不是目录：" + target.display)

        val entries = runCatching { target.listEntries() }
            .getOrElse { return ToolResult.Error("列目录失败：" + (it.message ?: it.javaClass.simpleName)) }

        val dirs = entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files = entries.filter { !it.isDirectory }.sortedBy { it.name.lowercase() }
        val all = dirs + files
        val shown = all.take(300)

        val body = shown.joinToString("\n") { entry ->
            if (entry.isDirectory) {
                "d  " + entry.name + "/"
            } else {
                "f  " + entry.name + "  " + (if (entry.size >= 0) entry.size.toString() + "B" else "?B")
            }
        }
        val more = if (all.size > shown.size) "\n（还有 " + (all.size - shown.size) + " 项未显示）" else ""
        return ToolResult.Ok(
            "目录：" + target.display + "\n" + dirs.size + " 个目录 / " + files.size + " 个文件\n" + body + more
        )
    }
}

/**
 * 工具这次真正要动的东西。
 *
 * - 工作区里（含 SAF 授权目录）：一律走 [WorkspaceIo]，SAF 目录也能读能写；
 * - 工作区外的绝对路径：只有「完全」权限会走到这儿，仍用 java.io.File 的老路。
 *
 * 两种情形的对外表现（显示路径、大小、列目录）尽量对齐，工具的正文不用各写一套。
 */
private class FsTarget(
    /** 非 null = 走 WorkspaceIo */
    private val io: WorkspaceIo?,
    /** 走 WorkspaceIo 时的相对路径 */
    private val rel: String?,
    /** 不走 WorkspaceIo 时（或 SAF 推不出相对路径时）的 File；只用于 inWorkspace=false 的分支 */
    val local: File
) {

    /** 这个目标在不在工作区里（决定权限口径）。 */
    val inWorkspace: Boolean get() = io != null && rel != null

    /** 是否走系统授权目录：这种目录没有 rename 接口，写入只能截断重写。 */
    val isSaf: Boolean get() = io?.isSaf == true

    val display: String
        get() {
            val adapter = io
            val path = rel
            return if (adapter != null && path != null) adapter.displayPath(path) else local.absolutePath
        }

    /** 目标是否存在：SAF 分支问 provider，File 分支问文件系统，两边口径一致。 */
    fun exists(): Boolean {
        val adapter = io
        val path = rel
        return if (adapter != null && path != null) adapter.exists(path) else local.exists()
    }

    /** 目标是不是目录；SAF provider 给不出类型时按「不是目录」算。 */
    fun isDirectory(): Boolean {
        val adapter = io
        val path = rel
        return if (adapter != null && path != null) adapter.isDirectory(path) else local.isDirectory
    }

    /** 普通文件判断：File 分支问文件系统；SAF 分支只能按「不是目录」算（provider 不给类型）。 */
    fun isRegularFile(): Boolean {
        val adapter = io
        val path = rel
        if (adapter != null && path != null) return adapter.exists(path) && !adapter.isDirectory(path)
        return local.isFile
    }

    /** 字节数；目录上的取值没有意义，调用方只在文件分支用它。 */
    fun size(): Long {
        val adapter = io
        val path = rel
        return if (adapter != null && path != null) adapter.size(path) else local.length()
    }

    /** 列一层子项（不递归）；目录读不了时返回空列表，不抛异常。 */
    fun listEntries(): List<WorkspaceEntry> {
        val adapter = io
        val path = rel
        if (adapter != null && path != null) return adapter.list(path)
        val children = local.listFiles() ?: return emptyList()
        return children.map { child ->
            WorkspaceEntry(
                name = child.name,
                isDirectory = child.isDirectory,
                size = if (child.isDirectory) -1L else child.length()
            )
        }
    }

    /** 取一个 UTF-8 文本读流，关闭由调用方负责。 */
    fun openReader(): BufferedReader {
        val adapter = io
        val path = rel
        return if (adapter != null && path != null) {
            adapter.openReader(path)
        } else {
            local.bufferedReader(Charsets.UTF_8)
        }
    }

    /** 整份读成文本（UTF-8）；保留原换行符与 BOM，供精确编辑使用。 */
    fun readText(): String = openReader().use { it.readText() }

    /** 整段覆盖写：File 分支同时创建父目录，SAF 分支直接交给 provider 的写接口。 */
    fun writeText(content: String) {
        val adapter = io
        val path = rel
        if (adapter != null && path != null) {
            adapter.writeText(path, content)
            return
        }
        local.parentFile?.let { if (!it.exists()) it.mkdirs() }
        local.writeText(content, Charsets.UTF_8)
    }

    companion object {
        /** 直接按「工作区里的某个相对路径」构造（列工作区根时用）。 */
        fun ofWorkspace(io: WorkspaceIo, rel: String): FsTarget =
            FsTarget(io, rel, File(io.displayRoot))
    }
}

/**
 * 把参数路径解析成 [FsTarget]。返回 null 只有一种情形：
 * SAF 工作区里出现「推不出相对路径」的绝对路径，那种路径在 File 这套上不成立，只能拒绝。
 *
 * 普通工作区外的绝对路径不算解析失败：原样交给 File，权限由调用方按 PathGate 判。
 */
private fun targetFor(io: WorkspaceIo, file: File): FsTarget? {
    val rel = io.relativePathOf(file)
    if (rel != null) return FsTarget(io, rel, file)
    if (io.isSaf) return null
    return FsTarget(null, null, file)
}
