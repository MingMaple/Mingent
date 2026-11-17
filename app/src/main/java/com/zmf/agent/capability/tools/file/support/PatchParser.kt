package com.zmf.agent.capability.tools.file.support

/** 补丁文件动作。 */
enum class PatchFileKind { UPDATE, ADD, DELETE }

/**
 * 一个补丁块。
 *
 * [oldLines] 必须按出现顺序与文件内容逐行精确匹配；[newLines] 用于替换匹配到的片段。
 * 上下文行同时进入两个列表，因此替换后该行保持不变。
 */
data class Hunk(val oldLines: List<String>, val newLines: List<String>)

/** 一个文件的改动计划；[path] 为补丁中给出的路径原文。 */
data class PatchFile(val path: String, val kind: PatchFileKind, val hunks: List<Hunk>)

/** 一整批改动。 */
data class PatchPlan(val files: List<PatchFile>)

/** 补丁格式错误；[message] 必须指出行号与原因。 */
class PatchFormatException(message: String) : Exception(message)

/**
 * `*** Begin Patch` 格式的纯 Kotlin 解析器，不依赖 Android。
 *
 * 支持的指令：
 * ```
 * *** Begin Patch
 * *** Update File: src/a.kt
 * @@
 * -old line
 * +new line
 * *** Add File: notes/b.md
 * +content
 * *** Delete File: old/c.txt
 * *** End Patch
 * ```
 *
 * 每个以 `@@` 开头的行开启一个新块；块内以 `-` 开头的行属于旧内容，以 `+` 开头的行属于新内容，
 * 以空格开头的行是上下文（同时属于两者），空行按空上下文处理。
 * 格式不合规时抛出 [PatchFormatException]，其消息包含从 1 开始的行号。
 */
object PatchParser {

    fun parse(text: String): PatchPlan {
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val cursor = Cursor(0)
        while (cursor.index < lines.size && lines[cursor.index].isBlank()) cursor.index++
        if (cursor.index >= lines.size) {
            throw PatchFormatException("第 1 行：补丁为空，必须以 *** Begin Patch 开始")
        }
        if (lines[cursor.index].trim() != BEGIN) {
            throw PatchFormatException("第 ${cursor.index + 1} 行：必须以 *** Begin Patch 开始")
        }
        cursor.index++

        val files = ArrayList<PatchFile>()
        val paths = HashSet<String>()
        while (true) {
            while (cursor.index < lines.size && lines[cursor.index].isBlank()) cursor.index++
            if (cursor.index >= lines.size) {
                throw PatchFormatException("第 ${lines.size + 1} 行：缺少 *** End Patch")
            }
            if (lines[cursor.index].trim() == END) {
                cursor.index++
                break
            }
            val headerLine = cursor.index + 1
            val header = HEADER.matchEntire(lines[cursor.index].trim())
                ?: throw PatchFormatException("第 $headerLine 行：无法识别的指令：${lines[cursor.index].trim()}")
            val kind = when (header.groupValues[1]) {
                "Update" -> PatchFileKind.UPDATE
                "Add" -> PatchFileKind.ADD
                else -> PatchFileKind.DELETE
            }
            val path = header.groupValues[2]
            if (path.isEmpty()) throw PatchFormatException("第 $headerLine 行：文件路径为空")
            if (!paths.add(path)) {
                throw PatchFormatException("第 $headerLine 行：路径在补丁中重复出现：$path")
            }
            cursor.index++
            val hunks = when (kind) {
                PatchFileKind.UPDATE -> readUpdate(lines, cursor, headerLine)
                PatchFileKind.ADD -> readAdd(lines, cursor)
                PatchFileKind.DELETE -> readDelete(lines, cursor)
            }
            files.add(PatchFile(path, kind, hunks))
        }

        while (cursor.index < lines.size) {
            if (lines[cursor.index].isNotBlank()) {
                throw PatchFormatException("第 ${cursor.index + 1} 行：*** End Patch 之后不得出现内容")
            }
            cursor.index++
        }
        return PatchPlan(files)
    }

    private fun readUpdate(lines: List<String>, cursor: Cursor, headerLine: Int): List<Hunk> {
        val hunks = ArrayList<Hunk>()
        var oldLines = ArrayList<String>()
        var newLines = ArrayList<String>()
        var inHunk = false

        fun commit() {
            if (!inHunk) return
            hunks.add(Hunk(oldLines.toList(), newLines.toList()))
            oldLines = ArrayList()
            newLines = ArrayList()
        }

        while (cursor.index < lines.size && !isDirective(lines[cursor.index])) {
            val raw = lines[cursor.index]
            if (raw.startsWith("@@")) {
                commit()
                inHunk = true
                cursor.index++
                continue
            }
            if (raw.isEmpty()) {
                // 段末尾的空行只是分隔符，不得算作空上下文行
                if (boundaryAhead(lines, cursor.index + 1)) {
                    cursor.index++
                    continue
                }
                if (!inHunk) {
                    throw PatchFormatException("第 ${cursor.index + 1} 行：@@ 之前不得出现内容行")
                }
                oldLines.add("")
                newLines.add("")
                cursor.index++
                continue
            }
            if (!inHunk) {
                throw PatchFormatException("第 ${cursor.index + 1} 行：Update File 段在 @@ 之前出现了内容行")
            }
            when (raw[0]) {
                '+' -> newLines.add(raw.substring(1))
                '-' -> oldLines.add(raw.substring(1))
                ' ' -> {
                    oldLines.add(raw.substring(1))
                    newLines.add(raw.substring(1))
                }

                else -> throw PatchFormatException("第 ${cursor.index + 1} 行：内容行必须以 +、- 或空格开头")
            }
            cursor.index++
        }
        commit()
        if (hunks.isEmpty()) {
            throw PatchFormatException("第 $headerLine 行：Update File 段缺少 @@ 块")
        }
        return hunks
    }

    private fun readAdd(lines: List<String>, cursor: Cursor): List<Hunk> {
        val content = ArrayList<String>()
        while (cursor.index < lines.size && !isDirective(lines[cursor.index])) {
            val raw = lines[cursor.index]
            when {
                raw.isEmpty() -> {
                    if (boundaryAhead(lines, cursor.index + 1)) {
                        cursor.index++
                        continue
                    }
                    content.add("")
                }

                raw.startsWith("+") -> content.add(raw.substring(1))

                else -> throw PatchFormatException("第 ${cursor.index + 1} 行：Add File 段的内容行必须以 + 开头")
            }
            cursor.index++
        }
        return listOf(Hunk(emptyList(), content))
    }

    private fun readDelete(lines: List<String>, cursor: Cursor): List<Hunk> {
        while (cursor.index < lines.size && !isDirective(lines[cursor.index])) {
            if (lines[cursor.index].isNotEmpty()) {
                throw PatchFormatException("第 ${cursor.index + 1} 行：Delete File 段不得包含内容行")
            }
            cursor.index++
        }
        return emptyList()
    }

    /** 该行是否为下一段或结束标记。 */
    private fun isDirective(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed == END || HEADER.matchEntire(trimmed) != null
    }

    /** 从 [from] 起跳过连续的段末尾空行后，是否已到达下一段或文件末尾。 */
    private fun boundaryAhead(lines: List<String>, from: Int): Boolean {
        var index = from
        while (index < lines.size && lines[index].isEmpty()) index++
        return index >= lines.size || isDirective(lines[index])
    }

    private class Cursor(var index: Int)

    private const val BEGIN = "*** Begin Patch"
    private const val END = "*** End Patch"
    private val HEADER = Regex("""^\*\*\* (Update|Add|Delete) File:\s*(.+?)\s*$""")
}
