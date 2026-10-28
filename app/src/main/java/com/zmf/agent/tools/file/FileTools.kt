package com.zmf.agent.tools.file

import com.zmf.agent.agent.workspace.WorkspaceEntry
import com.zmf.agent.agent.workspace.WorkspaceIo
import com.zmf.agent.agent.workspace.workspaceIoForRoot
import com.zmf.agent.core.log.AuditLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.HighRiskGate
import com.zmf.agent.tools.PathGate
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolResult
import com.zmf.agent.tools.displayName
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File

/**
 * 这一组工具都通过 [WorkspaceIo] 读写：工作区的根是普通目录时就是 java.io.File 的老行为，
 * 根是 SAF 授权目录（用户用系统选择器选的文件夹）时走 DocumentsContract，
 * 所以「用户选来的目录根本写不进去」这个坑在工具层已经不存在了。
 *
 * 权限口径没变：工作区里随便读写；工作区外的绝对路径仍然要「完全」权限（那条路只有 File 能走）。
 */

/**
 * 读文本文件。
 *
 * 参数兼容两套名字：offset / limit 为准，startLine / maxLines 保留给旧会话。
 * 默认一次最多 400 行，上限 2000 行 / 64000 字符；超出就截断并告诉模型用 offset 继续，
 * 不然后面的长文件会把上下文撑爆。
 *
 * 「共 N 行」里的 N 要真的数出来：文件 5MB 以内才数，再大就报 unknown，
 * 免得为了一个数字把大文件再读一遍。
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
        // 回收站对 Agent 是全黑区，且**与档位无关**（完全档也一样）：判定必须放在档位检查之前，
        // 否则「完全档能读工作区外」这条会顺手把回收站也放行。走 canonical，软链接绕不过去
        // （只看字面路径会被软链接绕开，也不能被 `canRead` 短路在完全档之外）。
        if (HighRiskGate.isTrashPathDeep(file, ctx.context.packageName)) {
            return ToolResult.Error(HighRiskGate.trashReadText())
        }
        if (!target.inWorkspace && !PathGate.canRead(ctx, file)) {
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

        // 文件不大才数总行数：大文件为了一个「共 N 行」多读一整遍不划算。
        // 大小未知（SAF provider 不给）就当小文件：照样数行，输出另有字符上限兜着。
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
                append("(Showing lines ").append(startLine).append("-").append(endLine).append(" of ").append(total)
                append(". Use offset=").append(endLine + 1).append(" to continue.)")
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
            PathGate.canWrite(ctx, target.local)
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
        // 覆盖前先量一遍原来的行数：写成功后在回执里给出「原 N 行 → 新 M 行」，
        // 模型多数情况下就不用再 read_file 确认一遍（提示词里本来就要求它别为确认重读）。
        val oldLines = if (existed) {
            runCatching {
                target.openReader().use { reader -> reader.readText() }
                    .trimEnd('\n')
                    .let { text -> if (text.isEmpty()) 0 else text.count { c -> c == '\n' } + 1 }
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
    }
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
            if (!resolved.inWorkspace && !PathGate.canRead(ctx, file)) {
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

    /** 整段覆盖写：File 分支顺手建父目录，SAF 分支直接交给 provider 的写接口。 */
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
