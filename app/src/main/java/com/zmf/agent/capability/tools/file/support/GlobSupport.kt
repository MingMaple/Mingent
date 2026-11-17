package com.zmf.agent.capability.tools.file.support

import java.io.File

/**
 * 通配模式到正则的转换。
 *
 * 只支持 `*`、`**`、`?` 与 `{a,b}`，其余字符按字面处理；不支持的构造（如未闭合或嵌套的花括号）
 * 一律抛出 [IllegalArgumentException]，由调用方折算成错误码，不得静默忽略。
 */
internal object PathGlob {

    fun toRegex(pattern: String): Regex {
        val builder = StringBuilder("^")
        translate(pattern, builder)
        builder.append('$')
        return Regex(builder.toString())
    }

    private fun translate(pattern: String, builder: StringBuilder) {
        var index = 0
        while (index < pattern.length) {
            val char = pattern[index]
            when (char) {
                '*' -> {
                    if (index + 1 < pattern.length && pattern[index + 1] == '*') {
                        index++
                        if (index + 1 < pattern.length && pattern[index + 1] == '/') {
                            index++
                            builder.append("(?:.*/)?")
                        } else {
                            builder.append(".*")
                        }
                    } else {
                        builder.append("[^/]*")
                    }
                }

                '?' -> builder.append("[^/]")

                '{' -> {
                    val close = pattern.indexOf('}', index + 1)
                    if (close < 0) throw IllegalArgumentException("花括号未闭合")
                    val body = pattern.substring(index + 1, close)
                    if (body.contains('{') || body.contains('}')) {
                        throw IllegalArgumentException("不支持嵌套花括号")
                    }
                    val alternatives = body.split(',')
                    if (alternatives.size < 2) {
                        throw IllegalArgumentException("花括号内必须以逗号分隔至少两项")
                    }
                    builder.append("(?:")
                    for (alternativeIndex in alternatives.indices) {
                        if (alternativeIndex > 0) builder.append('|')
                        translate(alternatives[alternativeIndex], builder)
                    }
                    builder.append(')')
                    index = close
                }

                '}' -> throw IllegalArgumentException("多余的右花括号")

                else -> appendLiteral(char, builder)
            }
            index++
        }
    }

    private fun appendLiteral(char: Char, builder: StringBuilder) {
        if (char.isLetterOrDigit() || char == '/' || char == '_' || char == '-') {
            builder.append(char)
            return
        }
        when (char) {
            '.', '\\', '+', '(', ')', '[', ']', '^', '$', '|' -> builder.append('\\').append(char)
            else -> builder.append(char)
        }
    }
}

/**
 * [file] 相对 [root] 的路径，使用 `/` 分隔；[file] 等于 [root] 时返回空串。
 *
 * 不在 [root] 之下时返回其绝对路径：调用方在该情形下已按越界拒绝，此处只保证输出可读。
 */
internal fun workspaceRelative(root: File, file: File): String {
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: root.absoluteFile
    val target = runCatching { file.canonicalFile }.getOrNull() ?: file.absoluteFile
    if (target.path == canonicalRoot.path) return ""
    val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
    val relative = if (target.path.startsWith(prefix)) {
        target.path.removePrefix(prefix)
    } else {
        target.path
    }
    return relative.replace(File.separatorChar, '/')
}
