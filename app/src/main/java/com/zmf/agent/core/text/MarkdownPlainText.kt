package com.zmf.agent.core.text

/**
 * Markdown 源码转纯文本与语义 HTML。
 *
 * 面向「复制」与「选择文本页的纯文本视图」两处使用，只依赖 Kotlin 标准库：
 * 不引用 Android、Compose 或 `uikit.markdown` 的渲染实现，避免 `core` 反向依赖界面层。
 *
 * 转换按**块级结构与行内结构**分别处理，不做「把符号整段替换为空」的字符清洗：
 * - 块级：围栏代码块、标题、分隔线、表格、列表（含任务列表与嵌套缩进）、引用、段落；
 * - 行内：行内代码、图片、链接、粗体／斜体／删除线、HTML 标签与实体。
 *
 * 这样段落换行、列表层级与代码缩进都能保留，同时不破坏 `snake_case` 这类含下划线的正文。
 *
 * [toHtml] 与 [toPlainText] 共用同一套块级判定：纯文本用于只认 `text/plain` 的输入框，
 * HTML 作为同一剪贴板项的富文本分支，供 Word、飞书文档、Google Docs 一类接收端取用。
 */
object MarkdownPlainText {

    /**
     * 把 Markdown 源码转成纯文本。
     *
     * 块与块之间保留一个空行；列表项之间保持相邻行；空输入返回空串。
     *
     * @param keepCodeFence 为 true 时，围栏代码块的 ``` 与语言标记原样保留（框选页 TXT 用，
     *   让代码块上下边界可见）；false 时去掉围栏只留代码（复制按钮用）。
     * @param inlineUrlInLink 为 true 时链接输出「文字 (URL)」；false 时只留文字（框选页 TXT 用，
     *   纯文本场景下链接不可点，留 URL 干扰阅读）。
     */
    fun toPlainText(
        source: String,
        keepCodeFence: Boolean = false,
        inlineUrlInLink: Boolean = true
    ): String {
        if (source.isBlank()) return ""
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 1. 围栏代码块：默认去掉围栏与语言标记，正文逐行原样保留；keepCodeFence 时围栏与语言标记一并保留
            val fence = fenceOf(trimmed)
            if (fence != null) {
                val body = ArrayList<String>()
                var closed = false
                var j = i + 1
                while (j < lines.size) {
                    val candidate = lines[j].trim()
                    if (candidate.startsWith(fence) && candidate.removePrefix(fence).trim().isEmpty()) {
                        closed = true
                        break
                    }
                    body.add(lines[j])
                    j++
                }
                val inner = trimBlankEdges(body).joinToString("\n")
                blocks.add(if (keepCodeFence) trimmed + "\n" + inner + "\n" + fence else inner)
                i = if (closed) j + 1 else lines.size
                continue
            }

            // 2. 标题：去掉 # 标记，保留文字
            val heading = HEADING_REGEX.matchEntire(trimmed)
            if (heading != null) {
                blocks.add(inlineToPlain(heading.groupValues[2].trim(), inlineUrlInLink))
                i++
                continue
            }

            // 3. 分隔线：只保留块间空行，不输出符号
            if (DIVIDER_REGEX.matches(trimmed)) {
                i++
                continue
            }

            // 4. 表格：表头行 + 对齐分隔行 + 连续数据行；分隔行不输出。
            // 按列最大显示宽度补空格，等宽环境下表头与数据能对齐；CJK 按两列计。
            if (isTableStart(lines, i)) {
                val header = splitTableRow(raw)
                val rows = ArrayList<List<String>>()
                rows.add(header.map { inlineToPlain(it, inlineUrlInLink) })
                var j = i + 2
                var count = 0
                while (j < lines.size && count < MAX_TABLE_ROWS &&
                    isTableRow(lines[j]) && !isFenceLine(lines[j])
                ) {
                    val cells = splitTableRow(lines[j])
                    rows.add(
                        List(header.size) { index ->
                            inlineToPlain(cells.getOrElse(index) { "" }, inlineUrlInLink)
                        }
                    )
                    count++
                    j++
                }
                blocks.add(renderTableRows(rows))
                i = j
                continue
            }

            // 5. 列表：连续的列表行合成一块；每级缩进两空格，层级按源码缩进判定
            val firstItem = listItemOf(raw)
            if (firstItem != null) {
                val out = StringBuilder()
                var j = i
                while (j < lines.size) {
                    val item = listItemOf(lines[j]) ?: break
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(listIndent(item.level))
                        .append(item.marker)
                        .append(inlineToPlain(item.content, inlineUrlInLink))
                    j++
                }
                blocks.add(out.toString())
                i = j
                continue
            }

            // 6. 引用：连续的 > 行合成一块，去掉标记后整块缩进两空格
            if (trimmed.startsWith(">")) {
                val quoted = ArrayList<String>()
                var j = i
                while (j < lines.size && lines[j].trim().startsWith(">")) {
                    val body = lines[j].trim().removePrefix(">").removePrefix(" ").trimEnd()
                    quoted.add(if (body.isEmpty()) "" else QUOTE_INDENT + inlineToPlain(body, inlineUrlInLink))
                    j++
                }
                blocks.add(quoted.joinToString("\n"))
                i = j
                continue
            }

            // 7. 段落：连续的非块首行合成一段，段内换行原样保留
            val paragraph = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines, i)) {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(inlineToPlain(lines[i].trim(), inlineUrlInLink))
                i++
            }
            blocks.add(paragraph.toString())
        }
        return blocks.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /**
     * 转换失败或结果为空时回退原文，保证复制不会写入空串。
     *
     * 原文本身为空时仍返回原文，交由调用方决定是否写入剪贴板。
     */
    fun toPlainTextOrOriginal(
        source: String,
        keepCodeFence: Boolean = false,
        inlineUrlInLink: Boolean = true
    ): String {
        val plain = runCatching { toPlainText(source, keepCodeFence, inlineUrlInLink) }.getOrNull()
        return if (plain.isNullOrBlank()) source else plain
    }

    // ---- 块级识别 ----

    private const val CELL_SEPARATOR = "  "
    private const val QUOTE_INDENT = "  "
    private const val MAX_TABLE_ROWS = 200

    private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.*)$")
    private val DIVIDER_REGEX = Regex("^([-*_])\\1{2,}$")
    private val TASK_REGEX = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.*)$")
    private val BULLET_REGEX = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ORDERED_REGEX = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")

    private data class ListItem(
        val level: Int,
        val marker: String,
        val content: String,
        val ordered: Boolean = false
    )

    private fun fenceOf(trimmed: String): String? = when {
        trimmed.startsWith("```") -> "```"
        trimmed.startsWith("~~~") -> "~~~"
        else -> null
    }

    private fun isFenceLine(line: String): Boolean {
        val text = line.trim()
        return text.startsWith("```") || text.startsWith("~~~")
    }

    private fun startsBlock(lines: List<String>, index: Int): Boolean {
        val raw = lines[index]
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return true
        if (fenceOf(trimmed) != null) return true
        if (HEADING_REGEX.matches(trimmed)) return true
        if (DIVIDER_REGEX.matches(trimmed)) return true
        if (trimmed.startsWith(">")) return true
        if (TASK_REGEX.matches(raw) || BULLET_REGEX.matches(raw) || ORDERED_REGEX.matches(raw)) return true
        return isTableStart(lines, index)
    }

    private fun listItemOf(raw: String): ListItem? {
        TASK_REGEX.matchEntire(raw)?.let { match ->
            val checked = match.groupValues[2].isNotBlank()
            return ListItem(
                level = indentLevelOf(match.groupValues[1]),
                marker = if (checked) "\u2611 " else "\u25a1 ",
                content = match.groupValues[3].trim()
            )
        }
        BULLET_REGEX.matchEntire(raw)?.let { match ->
            return ListItem(
                level = indentLevelOf(match.groupValues[1]),
                marker = "\u00b7 ",
                content = match.groupValues[2].trim()
            )
        }
        ORDERED_REGEX.matchEntire(raw)?.let { match ->
            return ListItem(
                level = indentLevelOf(match.groupValues[1]),
                marker = match.groupValues[2] + ". ",
                content = match.groupValues[3].trim(),
                ordered = true
            )
        }
        return null
    }

    /** 行首缩进折算层级：两个空格算一级，制表符按四个空格计。 */
    private fun indentLevelOf(leading: String): Int {
        var width = 0
        for (ch in leading) width += if (ch == '\t') 4 else 1
        return width / 2
    }

    private fun listIndent(level: Int): String = "  ".repeat(level.coerceAtLeast(0))

    private fun trimBlankEdges(lines: List<String>): List<String> {
        var start = 0
        var end = lines.lastIndex
        while (start <= end && lines[start].isBlank()) start++
        while (end >= start && lines[end].isBlank()) end--
        if (start > end) return emptyList()
        return lines.subList(start, end + 1)
    }

    // ---- 表格 ----

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (!isTableRow(lines[index]) || index + 1 >= lines.size) return false
        val header = splitTableRow(lines[index])
        val aligns = parseTableAlignStyles(splitTableRow(lines[index + 1])) ?: return false
        return aligns.size == header.size
    }

    /** 该行是否是表格行：跳过转义后仍含至少一个竖线。 */
    private fun isTableRow(line: String): Boolean {
        val text = line.trim()
        if (text.isEmpty()) return false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch == '\\') {
                index += 2
                continue
            }
            if (ch == '|') return true
            index++
        }
        return false
    }

    /** 按未转义的竖线切分单元格；首尾竖线不产生空列，`\|` 还原成字面竖线。 */
    private fun splitTableRow(line: String): List<String> {
        var text = line.trim()
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|") && !text.endsWith("\\|")) text = text.dropLast(1)
        val cells = ArrayList<String>(4)
        val cell = StringBuilder()
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch == '\\' && index + 1 < text.length && text[index + 1] == '|') {
                cell.append('|')
                index += 2
                continue
            }
            if (ch == '|') {
                cells.add(cell.toString().trim())
                cell.setLength(0)
                index++
                continue
            }
            cell.append(ch)
            index++
        }
        cells.add(cell.toString().trim())
        return cells
    }

    /** 解析分隔行：每个单元格只能由冒号与连字符组成，否则返回 null，整块退回段落。 */
    private fun parseTableAlignStyles(cells: List<String>): List<String>? {
        if (cells.isEmpty()) return null
        val result = ArrayList<String>(cells.size)
        for (raw in cells) {
            var text = raw.trim()
            val left = text.startsWith(":")
            val right = text.length > 1 && text.endsWith(":")
            if (left) text = text.substring(1)
            if (right) text = text.dropLast(1)
            if (text.isEmpty() || text.any { it != '-' }) return null
            result.add(
                when {
                    left && right -> "center"
                    right -> "right"
                    else -> "left"
                }
            )
        }
        return result
    }

    /**
     * 表格转纯文本：单元格按列最大显示宽度右补空格，末列不补以免行尾带空白。
     *
     * 宽度按等宽字体估算，CJK 与全角符号计两列；比例字体的接收端仍按字符流显示，
     * 补空格只影响等宽环境下的列对齐，不影响内容。Pandoc 一类转换器的纯文本表格
     * 同样采用按列补空格的做法。
     */
    private fun renderTableRows(rows: List<List<String>>): String {
        val columns = rows.maxOf { it.size }
        val widths = IntArray(columns)
        for (row in rows) {
            for (column in row.indices) {
                widths[column] = maxOf(widths[column], displayWidth(row[column]))
            }
        }
        val out = StringBuilder()
        for ((rowIndex, row) in rows.withIndex()) {
            if (rowIndex > 0) out.append('\n')
            for (column in 0 until columns) {
                if (column > 0) out.append(CELL_SEPARATOR)
                val cell = row.getOrElse(column) { "" }
                out.append(cell)
                if (column < columns - 1) {
                    val padding = widths[column] - displayWidth(cell)
                    if (padding > 0) out.append(" ".repeat(padding))
                }
            }
        }
        return out.toString()
    }

    /** 等宽字体下的显示宽度：CJK、全角符号与常见表情按两列计，其余按一列。 */
    private fun displayWidth(text: String): Int {
        var width = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            width += if (isWide(codePoint)) 2 else 1
            index += Character.charCount(codePoint)
        }
        return width
    }

    private fun isWide(codePoint: Int): Boolean =
        codePoint in 0x1100..0x115F ||
            codePoint in 0x2E80..0x303E ||
            codePoint in 0x3041..0x33FF ||
            codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xA000..0xA4CF ||
            codePoint in 0xAC00..0xD7A3 ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFE10..0xFE19 ||
            codePoint in 0xFE30..0xFE6F ||
            codePoint in 0xFF00..0xFF60 ||
            codePoint in 0xFFE0..0xFFE6 ||
            codePoint in 0x1F300..0x1F64F ||
            codePoint in 0x1F900..0x1F9FF ||
            codePoint in 0x20000..0x3FFFD

    // ---- 行内处理 ----

    /**
     * 行内转换：逐个字符扫描，只剥离成对的结构标记。
     *
     * 未闭合的标记按字面保留，与渲染器的流式容错口径一致；
     * 行内代码与围栏代码一样不参与其它标记的处理。
     */
    private fun inlineToPlain(source: String, inlineUrlInLink: Boolean): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            when {
                ch == '\\' && i + 1 < source.length && isEscapable(source[i + 1]) -> {
                    out.append(source[i + 1])
                    i += 2
                }

                ch == '`' -> {
                    val run = runLength(source, i, '`')
                    val close = findExactRun(source, i + run, '`', run)
                    if (close >= 0) {
                        out.append(source, i + run, close)
                        i = close + run
                    } else {
                        out.append(source, i, i + run)
                        i += run
                    }
                }

                ch == '!' && i + 1 < source.length && source[i + 1] == '[' -> {
                    val link = parseLink(source, i + 1)
                    if (link != null) {
                        out.append(inlineToPlain(link.label, inlineUrlInLink))
                        i = link.end
                    } else {
                        out.append(ch)
                        i++
                    }
                }

                ch == '[' -> {
                    val link = parseLink(source, i)
                    if (link != null) {
                        appendLink(out, link, inlineUrlInLink)
                        i = link.end
                    } else {
                        out.append(ch)
                        i++
                    }
                }

                ch == '<' -> {
                    val end = source.indexOf('>', i + 1)
                    if (end > i) {
                        val inside = source.substring(i + 1, end)
                        when {
                            isAutolink(inside) -> {
                                out.append(inside)
                                i = end + 1
                            }

                            isHtmlTag(inside) -> {
                                i = end + 1
                            }

                            else -> {
                                out.append(ch)
                                i++
                            }
                        }
                    } else {
                        out.append(ch)
                        i++
                    }
                }

                ch == '&' -> {
                    val end = source.indexOf(';', i + 1)
                    val decoded = if (end > i && end - i <= 10) {
                        decodeEntity(source.substring(i + 1, end))
                    } else {
                        null
                    }
                    if (decoded != null) {
                        out.append(decoded)
                        i = end + 1
                    } else {
                        out.append(ch)
                        i++
                    }
                }

                ch == '*' || ch == '_' || ch == '~' -> {
                    i = appendEmphasis(out, source, i, ch, inlineUrlInLink)
                }

                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString()
    }

    private data class Link(val label: String, val url: String, val end: Int)

    /** 解析 `[label](url)`；`start` 指向左方括号，返回时 `end` 指向右圆括号之后。 */
    private fun parseLink(source: String, start: Int): Link? {
        var depth = 0
        var i = start
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        if (i >= source.length || source[i] != ']') return null
        val label = source.substring(start + 1, i)
        if (i + 1 >= source.length || source[i + 1] != '(') return null
        var paren = 1
        var j = i + 2
        while (j < source.length) {
            when (source[j]) {
                '(' -> paren++
                ')' -> {
                    paren--
                    if (paren == 0) break
                }
            }
            j++
        }
        if (j >= source.length) return null
        val url = source.substring(i + 2, j)
            .trim()
            .removeSurrounding("<", ">")
            .substringBefore(' ')
            .substringBefore('\t')
            .trim()
        return Link(label, url, j + 1)
    }

    private fun appendLink(out: StringBuilder, link: Link, inlineUrlInLink: Boolean) {
        val label = inlineToPlain(link.label, inlineUrlInLink).trim()
        when {
            label.isEmpty() -> out.append(link.url)
            !inlineUrlInLink || label == link.url -> out.append(label)
            else -> out.append(label).append(" (").append(link.url).append(')')
        }
    }

    private fun appendEmphasis(
        out: StringBuilder,
        source: String,
        start: Int,
        ch: Char,
        inlineUrlInLink: Boolean
    ): Int {
        val run = runLength(source, start, ch)
        if (ch == '~' && run < 2) {
            out.append(source, start, start + run)
            return start + run
        }
        // `_` 夹在单词中间时不当作强调标记，避免破坏 snake_case 一类正文
        if (ch == '_' && start > 0 && source[start - 1].isLetterOrDigit()) {
            out.append(source, start, start + run)
            return start + run
        }
        val openOk = start + run < source.length && !source[start + run].isWhitespace()
        val close = if (openOk) findExactRun(source, start + run, ch, run) else -1
        val closeOk = close > start + run &&
            !source[close - 1].isWhitespace() &&
            (ch != '_' || close + run >= source.length || !source[close + run].isLetterOrDigit())
        return if (closeOk) {
            out.append(inlineToPlain(source.substring(start + run, close), inlineUrlInLink))
            close + run
        } else {
            out.append(source, start, start + run)
            start + run
        }
    }

    /** 从 [from] 起找长度恰为 [length] 的连续 [ch]，找不到返回 -1。 */
    private fun findExactRun(source: String, from: Int, ch: Char, length: Int): Int {
        var i = from
        while (i < source.length) {
            if (source[i] != ch) {
                i++
                continue
            }
            val run = runLength(source, i, ch)
            if (run == length) return i
            i += run
        }
        return -1
    }

    private fun runLength(source: String, start: Int, ch: Char): Int {
        var i = start
        while (i < source.length && source[i] == ch) i++
        return i - start
    }

    private fun isEscapable(ch: Char): Boolean =
        ch in "\\`*_{}[]()#+-.!|>~"

    private fun isAutolink(inside: String): Boolean =
        inside.startsWith("http://") || inside.startsWith("https://") || inside.startsWith("mailto:")

    private fun isHtmlTag(inside: String): Boolean {
        val body = inside.trim()
        if (body.isEmpty()) return false
        if (body.startsWith("!--")) return true
        if (body.startsWith("!")) return true
        val name = body.removePrefix("/")
        if (name.isEmpty() || !name.first().isLetter()) return false
        return name.none { it == '<' }
    }

    private fun decodeEntity(raw: String): String? {
        val body = raw.trim()
        if (body.isEmpty()) return null
        return when (body.lowercase()) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> when {
                body.startsWith("#x", ignoreCase = true) ->
                    body.drop(2).toIntOrNull(16)?.let { codePoint(it) }

                body.startsWith("#") ->
                    body.drop(1).toIntOrNull()?.let { codePoint(it) }

                else -> null
            }
        }
    }

    private fun codePoint(value: Int): String? =
        if (value <= 0 || value > 0x10FFFF) null else String(Character.toChars(value))

    // ---- HTML 输出 ----

    /** 任务列表的方框标记；HTML 里保留字符本身，不依赖接收端的复选框样式。 */
    private val TASK_MARKERS = setOf("\u2611 ", "\u25a1 ")

    /**
     * 把 Markdown 源码转成语义 HTML 片段。
     *
     * 与 [toPlainText] 的块级判定一致，供剪贴板的 `text/html` 分支使用：
     * 标题、列表、表格、代码块、引用、链接在富文本接收端保留结构；
     * 源码里的 HTML 标签一律丢弃、正文一律转义，不把模型输出当标记直出。
     */
    fun toHtml(source: String): String {
        if (source.isBlank()) return ""
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder(source.length * 2)
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 1. 围栏代码块：保留语言标记作为 class，内容整体转义
            val fence = fenceOf(trimmed)
            if (fence != null) {
                val body = ArrayList<String>()
                var closed = false
                var j = i + 1
                while (j < lines.size) {
                    val candidate = lines[j].trim()
                    if (candidate.startsWith(fence) && candidate.removePrefix(fence).trim().isEmpty()) {
                        closed = true
                        break
                    }
                    body.add(lines[j])
                    j++
                }
                val inner = trimBlankEdges(body).joinToString("\n")
                val language = trimmed.removePrefix(fence).trim()
                out.append("<pre><code")
                if (isSafeLanguage(language)) {
                    out.append(" class=\"language-").append(language).append('"')
                }
                out.append('>').append(escapeHtml(inner)).append("</code></pre>")
                i = if (closed) j + 1 else lines.size
                continue
            }

            // 2. 标题：按 # 数量映射 h1–h6
            val heading = HEADING_REGEX.matchEntire(trimmed)
            if (heading != null) {
                val level = heading.groupValues[1].length
                out.append("<h").append(level).append('>')
                out.append(inlineToHtml(heading.groupValues[2].trim()))
                out.append("</h").append(level).append('>')
                i++
                continue
            }

            // 3. 分隔线
            if (DIVIDER_REGEX.matches(trimmed)) {
                out.append("<hr>")
                i++
                continue
            }

            // 4. 表格：带 thead／tbody，列对齐取自分隔行的冒号
            if (isTableStart(lines, i)) {
                i = appendTableHtml(out, lines, i, splitTableRow(raw))
                continue
            }

            // 5. 列表：连续的列表行合成一组，层级按缩进递归嵌套
            if (listItemOf(raw) != null) {
                val items = ArrayList<ListItem>()
                var j = i
                while (j < lines.size) {
                    val item = listItemOf(lines[j]) ?: break
                    items.add(item)
                    j++
                }
                appendListHtml(out, items, 0, items.first().level)
                i = j
                continue
            }

            // 6. 引用：整块包进 blockquote，行内换行保留
            if (trimmed.startsWith(">")) {
                val quoted = ArrayList<String>()
                var j = i
                while (j < lines.size && lines[j].trim().startsWith(">")) {
                    val body = lines[j].trim().removePrefix(">").removePrefix(" ").trimEnd()
                    quoted.add(inlineToHtml(body))
                    j++
                }
                out.append("<blockquote><p>").append(quoted.joinToString("<br>")).append("</p></blockquote>")
                i = j
                continue
            }

            // 7. 段落：连续的非块首行合成一段，段内换行用 <br>
            val paragraph = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines, i)) {
                paragraph.add(inlineToHtml(lines[i].trim()))
                i++
            }
            out.append("<p>").append(paragraph.joinToString("<br>")).append("</p>")
        }
        return out.toString()
    }

    private fun appendTableHtml(
        out: StringBuilder,
        lines: List<String>,
        start: Int,
        header: List<String>
    ): Int {
        val aligns = parseTableAlignStyles(splitTableRow(lines[start + 1]))
            ?: List(header.size) { "left" }
        out.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"6\" style=\"border-collapse:collapse\">")
        out.append("<thead><tr>")
        for (index in header.indices) {
            out.append("<th style=\"text-align:").append(aligns.getOrElse(index) { "left" }).append("\">")
            out.append(inlineToHtml(header[index])).append("</th>")
        }
        out.append("</tr></thead><tbody>")
        var j = start + 2
        var rows = 0
        while (j < lines.size && rows < MAX_TABLE_ROWS &&
            isTableRow(lines[j]) && !isFenceLine(lines[j])
        ) {
            val cells = splitTableRow(lines[j])
            out.append("<tr>")
            for (index in header.indices) {
                out.append("<td style=\"text-align:").append(aligns.getOrElse(index) { "left" }).append("\">")
                out.append(inlineToHtml(cells.getOrElse(index) { "" })).append("</td>")
            }
            out.append("</tr>")
            rows++
            j++
        }
        out.append("</tbody></table>")
        return j
    }

    /** 递归输出列表：同层同类型合成一个 ul／ol，子层嵌在上一个 li 内。 */
    private fun appendListHtml(out: StringBuilder, items: List<ListItem>, from: Int, level: Int): Int {
        var i = from
        while (i < items.size && items[i].level >= level) {
            if (items[i].level > level) {
                i = appendListHtml(out, items, i, items[i].level)
                continue
            }
            val ordered = items[i].ordered
            out.append(if (ordered) "<ol>" else "<ul>")
            while (i < items.size && items[i].level == level && items[i].ordered == ordered) {
                val item = items[i]
                out.append("<li>")
                if (item.marker in TASK_MARKERS) out.append(item.marker)
                out.append(inlineToHtml(item.content))
                i++
                if (i < items.size && items[i].level > level) {
                    i = appendListHtml(out, items, i, items[i].level)
                }
                out.append("</li>")
            }
            out.append(if (ordered) "</ol>" else "</ul>")
        }
        return i
    }

    /**
     * 行内转 HTML：与 [inlineToPlain] 同一套扫描规则，只是把标记换成标签。
     *
     * 链接的 label 与 URL 分别转义；`javascript:` 一类不可执行协议只保留文字。
     */
    private fun inlineToHtml(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            when {
                ch == '\\' && i + 1 < source.length && isEscapable(source[i + 1]) -> {
                    out.append(escapeHtml(source[i + 1].toString()))
                    i += 2
                }

                ch == '`' -> {
                    val run = runLength(source, i, '`')
                    val close = findExactRun(source, i + run, '`', run)
                    if (close >= 0) {
                        out.append("<code>").append(escapeHtml(source.substring(i + run, close))).append("</code>")
                        i = close + run
                    } else {
                        out.append(escapeHtml(source.substring(i, i + run)))
                        i += run
                    }
                }

                ch == '!' && i + 1 < source.length && source[i + 1] == '[' -> {
                    val link = parseLink(source, i + 1)
                    if (link != null) {
                        out.append(escapeHtml(inlineToPlain(link.label, false)))
                        i = link.end
                    } else {
                        out.append(ch)
                        i++
                    }
                }

                ch == '[' -> {
                    val link = parseLink(source, i)
                    if (link != null) {
                        appendLinkHtml(out, link)
                        i = link.end
                    } else {
                        out.append(ch)
                        i++
                    }
                }

                ch == '<' -> {
                    val end = source.indexOf('>', i + 1)
                    if (end > i) {
                        val inside = source.substring(i + 1, end)
                        when {
                            isAutolink(inside) -> {
                                out.append("<a href=\"").append(escapeHtml(inside)).append("\">")
                                out.append(escapeHtml(inside)).append("</a>")
                                i = end + 1
                            }

                            isHtmlTag(inside) -> i = end + 1

                            else -> {
                                out.append("&lt;")
                                i++
                            }
                        }
                    } else {
                        out.append("&lt;")
                        i++
                    }
                }

                ch == '&' -> {
                    val end = source.indexOf(';', i + 1)
                    val decoded = if (end > i && end - i <= 10) {
                        decodeEntity(source.substring(i + 1, end))
                    } else {
                        null
                    }
                    if (decoded != null) {
                        out.append(escapeHtml(decoded))
                        i = end + 1
                    } else {
                        out.append("&amp;")
                        i++
                    }
                }

                ch == '*' || ch == '_' || ch == '~' -> {
                    i = appendEmphasisHtml(out, source, i, ch)
                }

                ch == '>' || ch == '"' -> {
                    out.append(escapeHtml(ch.toString()))
                    i++
                }

                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun appendLinkHtml(out: StringBuilder, link: Link) {
        val label = inlineToHtml(link.label)
        if (link.url.isEmpty() || link.url.startsWith("javascript:", ignoreCase = true)) {
            out.append(label.ifEmpty { escapeHtml(link.url) })
            return
        }
        out.append("<a href=\"").append(escapeHtml(link.url)).append("\">")
        out.append(label.ifEmpty { escapeHtml(link.url) })
        out.append("</a>")
    }

    private fun appendEmphasisHtml(out: StringBuilder, source: String, start: Int, ch: Char): Int {
        val run = runLength(source, start, ch)
        if (ch == '~' && run < 2) {
            out.append(escapeHtml(source.substring(start, start + run)))
            return start + run
        }
        if (ch == '_' && start > 0 && source[start - 1].isLetterOrDigit()) {
            out.append(escapeHtml(source.substring(start, start + run)))
            return start + run
        }
        val openOk = start + run < source.length && !source[start + run].isWhitespace()
        val close = if (openOk) findExactRun(source, start + run, ch, run) else -1
        val closeOk = close > start + run &&
            !source[close - 1].isWhitespace() &&
            (ch != '_' || close + run >= source.length || !source[close + run].isLetterOrDigit())
        if (!closeOk) {
            out.append(escapeHtml(source.substring(start, start + run)))
            return start + run
        }
        val tag = when {
            ch == '~' -> "del"
            run >= 2 -> "strong"
            else -> "em"
        }
        out.append('<').append(tag).append('>')
        out.append(inlineToHtml(source.substring(start + run, close)))
        out.append("</").append(tag).append('>')
        return close + run
    }

    /** 代码块语言标记只接受字母、数字与常见分隔符，避免把任意文本拼进 class 属性。 */
    private fun isSafeLanguage(language: String): Boolean =
        language.isNotEmpty() && language.length <= 24 && language.all {
            it.isLetterOrDigit() || it == '-' || it == '_' || it == '+' || it == '#'
        }

    private fun escapeHtml(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
