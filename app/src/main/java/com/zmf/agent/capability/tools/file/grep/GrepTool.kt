package com.zmf.agent.capability.tools.file.grep

import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.file.support.PathGlob
import com.zmf.agent.capability.tools.file.support.workspaceRelative
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.core.model.PermissionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.regex.PatternSyntaxException

/**
 * 在工作区内按正则检索文本行。
 *
 * 规格与 DSH 的 grep 一致：pattern 为区分大小写的正则，include 是单个正向 glob 过滤器；
 * 输出按文件分组，每个命中行为 `Line N: <preview>`，单行预览上限 2000 字节。
 * 单次最多内联 250 处命中，超出时在结果中说明。
 *
 * 边界：只读取工作区（含 tmp）内的普通文件，不跟随符号链接，不执行外部命令。
 * 预算：单次检索最多扫描 [MAX_FILES] 个文件、[MAX_SCAN_BYTES] 字节，并受时间预算约束。
 */
class GrepTool : Tool {

    override val name = "grep"

    override val description =
        "按 Java 正则检索工作区内的文本行，返回文件路径、行号与行内容。" +
            "需要命中行的上下文时用 read_file 读取该文件，不要用 shell 的 grep 或 rg。" +
            "默认区分大小写，单次最多 250 处命中、30 秒预算，只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "pattern": {"type": "string", "description": "要检索的 Java 正则表达式，区分大小写，最长 2000 字符"},
            "path": {"type": "string", "description": "检索范围，可以是文件或目录，默认工作区根目录；相对路径相对工作区解析"},
            "include": {"type": "string", "description": "单个正向 glob 过滤器，例如 *.ts、*.{js,jsx}；省略时不按文件名过滤，不支持列表与取反"}
          },
          "required": ["pattern"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) { run(args, ctx) }

    private fun run(args: JSONObject, ctx: ToolContext): ToolResult {
        val pattern = args.optString("pattern", "")
        if (pattern.isEmpty()) {
            return ToolResult.Error("[E_BAD_REGEX] pattern 不能为空：请给出要检索的正则表达式")
        }
        if (pattern.length > MAX_PATTERN_CHARS) {
            return ToolResult.Error(
                "[E_BAD_REGEX] pattern 长度 " + pattern.length + " 超过上限 " + MAX_PATTERN_CHARS + " 字符；请缩短后重试"
            )
        }
        // 官方旧参数 case_sensitive 已不在声明中，仍接受：只有显式传 false 才忽略大小写。
        val caseSensitive = if (args.has("case_sensitive") && !args.isNull("case_sensitive")) {
            args.optBoolean("case_sensitive", true)
        } else {
            true
        }
        val regex = try {
            if (caseSensitive) Regex(pattern) else Regex(pattern, setOf(RegexOption.IGNORE_CASE))
        } catch (e: PatternSyntaxException) {
            return ToolResult.Error(
                "[E_BAD_REGEX] 正则不合法：" + (e.description ?: "语法错误") + "（位置 " + e.index + "）；请修正语法后重试"
            )
        } catch (e: Exception) {
            return ToolResult.Error("[E_BAD_REGEX] 正则不合法：" + (e.message ?: e.javaClass.simpleName) + "；请修正语法后重试")
        }

        val rawPath = args.optString("path", "").trim()
        val base = if (rawPath.isEmpty()) {
            ctx.workspaceRoot
        } else {
            runCatching { PathGate.resolve(ctx.workspaceRoot, rawPath) }
                .getOrElse { return ToolResult.Error("[E_PATH_OUTSIDE] 路径不合法：" + (it.message ?: "无法解析") + "；请检查 path 后重试") }
        }
        if (!PathGate.isInsideWorkspaceOrTemp(ctx.workspaceRoot, base)) {
            return ToolResult.Error("[E_PATH_OUTSIDE] 检索路径越出工作区：" + rawPath.ifEmpty { "工作区根" } + "；请改用工作区内的路径")
        }
        if (!base.exists()) {
            return ToolResult.Error("[E_NOT_FOUND] 检索路径不存在：" + rawPath.ifEmpty { "工作区根" } + "；请确认路径或改用 glob 查找")
        }
        if (!base.isDirectory && !base.isFile) {
            return ToolResult.Error("[E_NOT_FOUND] 检索路径既不是文件也不是目录：" + rawPath.ifEmpty { "工作区根" })
        }

        // include 为单个正向 glob；空字符串等同省略，取反与逗号列表在检索前拒绝。
        val includeRaw = args.optString("include", "").ifEmpty { args.optString("glob", "") }.trim()
        if (includeRaw.isNotEmpty()) {
            if (includeRaw.startsWith("!")) {
                return ToolResult.Error("[E_INVALID] include 只接受正向 glob，不支持取反（以 ! 开头）")
            }
            if (includeRaw.contains(',') && !includeRaw.contains('{')) {
                return ToolResult.Error("[E_INVALID] include 只接受一个 glob；多个后缀请写成 {a,b}")
            }
        }
        val includeRegex = if (includeRaw.isEmpty()) {
            null
        } else {
            try {
                PathGlob.toRegex(includeRaw)
            } catch (e: IllegalArgumentException) {
                return ToolResult.Error("[E_BAD_PATTERN] include 不合法：" + (e.message ?: "通配模式无法解析") + "；请检查通配语法")
            }
        }
        // 与 glob 同一语义：不含分隔符时按任意深度的文件名匹配。
        val includeBasenameOnly = !includeRaw.contains('/')

        val maxFileBytes = args.optLong("max_file_bytes", DEFAULT_MAX_FILE_BYTES)
            .coerceIn(MIN_FILE_BYTES, MAX_FILE_BYTES)
        val baseRel = workspaceRelative(ctx.workspaceRoot, base)

        val deadline = System.nanoTime() + TIME_BUDGET_NS
        val groups = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
        var filesScanned = 0
        var bytesRead = 0L
        var matches = 0
        var truncated = false
        var timedOut = false
        var stop = false

        fun scanFile(file: File, displayRel: String) {
            val size = file.length()
            if (size > maxFileBytes) return
            if (bytesRead + size > MAX_SCAN_BYTES) {
                truncated = true
                stop = true
                return
            }
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
            bytesRead += bytes.size
            if (hasNulInHead(bytes)) return
            val text = String(bytes, Charsets.UTF_8)
            if (text.isEmpty()) return
            val lines = text.lineSequence().toList()
            for (index in lines.indices) {
                if ((index and TIME_CHECK_MASK) == 0 && System.nanoTime() > deadline) {
                    timedOut = true
                    truncated = true
                    stop = true
                    break
                }
                if (!regex.containsMatchIn(lines[index])) continue
                groups.getOrPut(displayRel) { ArrayList() }
                    .add((index + 1) to truncateUtf8(lines[index], MAX_LINE_BYTES))
                matches++
                if (matches >= MAX_MATCHES) {
                    truncated = true
                    stop = true
                    break
                }
            }
        }

        if (base.isFile) {
            filesScanned = 1
            val displayRel = if (baseRel.isEmpty()) base.name else baseRel
            scanFile(base, displayRel)
        } else {
            val stack = ArrayDeque<Pair<File, String>>()
            stack.addLast(base to baseRel)
            while (stack.isNotEmpty() && !stop) {
                if (System.nanoTime() > deadline) {
                    timedOut = true
                    truncated = true
                    break
                }
                val (current, currentRel) = stack.removeLast()
                val children = runCatching { current.listFiles() }.getOrNull() ?: continue
                for (child in children) {
                    if (stop) break
                    if (PathGate.isSymlink(child)) continue
                    val name = child.name
                    val childRel = if (currentRel.isEmpty()) name else currentRel + "/" + name
                    if (child.isDirectory) {
                        if (IGNORED_DIRS.contains(name)) continue
                        stack.addLast(child to childRel)
                        continue
                    }
                    if (!child.isFile) continue
                    val patternRel = if (baseRel.isEmpty()) childRel else childRel.removePrefix(baseRel + "/")
                    if (includeRegex != null) {
                        val matched = if (includeBasenameOnly) includeRegex.matches(name) else includeRegex.matches(patternRel)
                        if (!matched) continue
                    }
                    if (filesScanned >= MAX_FILES) {
                        truncated = true
                        stop = true
                        break
                    }
                    filesScanned++
                    scanFile(child, childRel)
                }
            }
        }

        if (timedOut && matches == 0) {
            return ToolResult.Error(
                "[E_TIMEOUT] 检索超过 " + (TIME_BUDGET_MS / 1000) + " 秒仍未取得结果，已中止；" +
                    "请缩小 path 范围或收窄 pattern"
            )
        }
        if (matches == 0) return ToolResult.Ok("No matches found")

        val header = "Found " + matches + (if (matches == 1) " match" else " matches")
        val body = groups.entries.joinToString("\n\n") { (path, hits) ->
            path + "\n" + hits.joinToString("\n") { (number, text) -> "Line " + number + ": " + text }
        }
        val footer = if (truncated) {
            "\n\n(Showing the first " + MAX_MATCHES +
                " matches. The complete result could not be saved; narrow pattern, path, or include to see more.)"
        } else {
            ""
        }
        return ToolResult.Ok(header + "\n\n" + body + footer)
    }

    /** 仅检查文件头部：按约定，前 [BINARY_PROBE_BYTES] 字节含 NUL 即判为二进制。 */
    private fun hasNulInHead(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, BINARY_PROBE_BYTES)
        for (index in 0 until limit) {
            if (bytes[index] == 0.toByte()) return true
        }
        return false
    }

    /** 按 UTF-8 字节上限截断单行预览，保留字符边界。 */
    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val width = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + width > maxBytes) break
            bytes += width
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end)
    }

    private companion object {
        const val MAX_PATTERN_CHARS = 2000

        /** 单次内联保留的最大命中行数。 */
        const val MAX_MATCHES = 250

        /** 每条命中行预览的字节上限。 */
        const val MAX_LINE_BYTES = 2000

        const val MIN_FILE_BYTES = 1024L
        const val DEFAULT_MAX_FILE_BYTES = 2L * 1024L * 1024L
        const val MAX_FILE_BYTES = 8L * 1024L * 1024L

        /** 单次检索的扫描上限。 */
        const val MAX_FILES = 5000
        const val MAX_SCAN_BYTES = 64L * 1024L * 1024L

        /** 时间预算：到期后必须停止遍历并标注截断。 */
        const val TIME_BUDGET_MS = 30_000L
        const val TIME_BUDGET_NS = TIME_BUDGET_MS * 1_000_000L

        /** 每 256 行检查一次时间预算，避免逐行读取系统时钟。 */
        const val TIME_CHECK_MASK = 0xFF

        const val BINARY_PROBE_BYTES = 8192

        /** 不进入的目录名：版本控制元数据与 App 私有数据目录。 */
        val IGNORED_DIRS = setOf(
            ".git", ".svn", ".hg", ".bzr", ".jj", ".sl",
            "appdata", "shared_prefs", "databases", ".trash"
        )
    }
}
