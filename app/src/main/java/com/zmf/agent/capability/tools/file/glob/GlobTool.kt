package com.zmf.agent.capability.tools.file.glob

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

/**
 * 按通配模式发现工作区内的文件。
 *
 * 语义与 DSH 的 glob 一致：`*`、`?` 不跨目录分隔符，`**` 可跨任意层级，`{a,b}` 表示择一；
 * 不含 `/` 的模式匹配任意深度的文件名，因此 `*` 与 `*.kt` 都会遍历整棵树。
 * 隐藏文件与版本控制忽略的文件同样纳入结果，只排除版本控制元数据与 App 私有数据目录。
 * 结果按修改时间升序（旧的在前），单次最多内联 100 条。
 *
 * 边界：只读取目录结构，不读取文件内容，不跟随符号链接。
 */
class GlobTool : Tool {

    override val name = "glob"

    override val description =
        "按通配模式查找工作区内的文件，只读取目录结构，不读取文件内容。" +
            "需要文件内容时用 read_file，按内容检索用 grep，不要用 shell 的 find。" +
            "默认从工作区根目录开始，按修改时间升序（旧的在前），单次最多返回 100 条、最多访问 20000 个条目，只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "pattern": {"type": "string", "description": "通配模式，支持 *、**、?、{a,b}，最长 500 字符；不含 / 时匹配任意深度的文件名，如 *、*.kt、src/**/*.test.js"},
            "path": {"type": "string", "description": "搜索起点，相对工作区或绝对路径；默认为工作区根目录"}
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
        if (pattern.trim().isEmpty()) {
            return ToolResult.Error("[E_BAD_PATTERN] pattern 不能为空：请给出通配模式，例如 *.kt")
        }
        if (pattern.length > MAX_PATTERN_CHARS) {
            return ToolResult.Error(
                "[E_BAD_PATTERN] pattern 长度 " + pattern.length + " 超过上限 " + MAX_PATTERN_CHARS + " 字符；请缩短后重试"
            )
        }
        val regex = try {
            PathGlob.toRegex(pattern)
        } catch (e: IllegalArgumentException) {
            return ToolResult.Error("[E_BAD_PATTERN] 模式不合法：" + (e.message ?: "无法解析") + "；请检查通配语法后重试")
        }
        // 不含分隔符的模式按「任意深度的文件名」匹配，这是官方 glob 的核心语义。
        val basenameOnly = !pattern.contains('/')

        val rawPath = args.optString("path", "").trim()
        val base = if (rawPath.isEmpty()) {
            ctx.workspaceRoot
        } else {
            runCatching { PathGate.resolve(ctx.workspaceRoot, rawPath) }
                .getOrElse { return ToolResult.Error("[E_PATH_OUTSIDE] 路径不合法：" + (it.message ?: "无法解析") + "；请检查 path 后重试") }
        }
        if (!PathGate.isInsideWorkspaceOrTemp(ctx.workspaceRoot, base)) {
            return ToolResult.Error("[E_PATH_OUTSIDE] 匹配路径越出工作区：" + rawPath.ifEmpty { "工作区根" } + "；请改用工作区内的路径")
        }
        if (!base.exists()) {
            return ToolResult.Error("[E_NOT_FOUND] 匹配路径不存在：" + rawPath.ifEmpty { "工作区根" } + "；请确认路径，或改用工作区根作为起点")
        }
        if (!base.isDirectory) {
            return ToolResult.Error("[E_NOT_FOUND] 匹配起点不是目录：" + rawPath.ifEmpty { "工作区根" } + "；path 请指向目录")
        }

        val baseRel = workspaceRelative(ctx.workspaceRoot, base)
        val results = ArrayList<GlobEntry>()
        var entries = 0
        var truncated = false

        val queue = ArrayDeque<GlobNode>()
        queue.addLast(GlobNode(base, ""))
        traversal@ while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val children = runCatching { node.dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (PathGate.isSymlink(child)) continue
                entries++
                if (entries > MAX_ENTRIES) {
                    truncated = true
                    break@traversal
                }
                val name = child.name
                val relative = if (node.relative.isEmpty()) name else node.relative + "/" + name
                if (child.isDirectory) {
                    if (IGNORED_DIRS.contains(name)) continue
                    queue.addLast(GlobNode(child, relative))
                    continue
                }
                if (!child.isFile) continue
                val matched = if (basenameOnly) regex.matches(name) else regex.matches(relative)
                if (!matched) continue
                val display = if (baseRel.isEmpty()) relative else baseRel + "/" + relative
                results.add(GlobEntry(display, child.lastModified()))
            }
        }

        if (results.isEmpty()) return ToolResult.Ok("No files found")
        results.sortWith(compareBy<GlobEntry> { it.modified }.thenBy { it.path })
        val body = results.take(MAX_RESULTS).joinToString("\n") { it.path }
        if (results.size > MAX_RESULTS) {
            return ToolResult.Ok(
                body + "\n\n(Showing " + MAX_RESULTS + " of " + results.size +
                    " paths. The complete result could not be saved; narrow pattern or path to see more.)"
            )
        }
        if (truncated) {
            return ToolResult.Ok(
                body + "\n\n(Result truncated after scanning " + MAX_ENTRIES +
                    " entries; narrow pattern or path to see more.)"
            )
        }
        return ToolResult.Ok(body)
    }

    private companion object {
        const val MAX_PATTERN_CHARS = 500
        const val MAX_RESULTS = 100

        /** 访问条目上限：达到后必须停止遍历并标注截断。 */
        const val MAX_ENTRIES = 20_000

        /**
         * 不进入的目录：版本控制元数据与 App 私有数据目录。
         *
         * 后者是 Android 侧的禁区，与 DSH 的「只排除版本控制元数据」不同，属于平台差异。
         */
        val IGNORED_DIRS = setOf(
            ".git", ".svn", ".hg", ".bzr", ".jj", ".sl",
            "appdata", "shared_prefs", "databases", ".trash"
        )
    }
}

/** 遍历中的一个目录节点；[relative] 为相对匹配起点的路径，使用 `/` 分隔。 */
private data class GlobNode(val dir: File, val relative: String)

/** 一条匹配结果；[path] 为相对工作区根的路径，使用 `/` 分隔。 */
private data class GlobEntry(val path: String, val modified: Long)
