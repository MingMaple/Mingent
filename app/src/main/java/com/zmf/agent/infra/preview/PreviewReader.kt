package com.zmf.agent.infra.preview

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 预览用的只读文件访问：分页读文本、限量读字节、抽 docx 纯文本；失败一律返回 null，不向界面抛。 */
object PreviewReader {

    /** 一页文本：从 [nextLine] 行（0 起算）继续读。 */
    data class TextPage(val lines: List<String>, val nextLine: Int, val eof: Boolean)

    /** 单页行数：400 行在手机上约十屏，够滑一段又不至于一次铺满内存。 */
    const val PAGE_LINES = 400

    /** 单行字符上限：模型偶尔把整段压成一行，不截断会把一屏撑成一条。 */
    const val MAX_LINE_CHARS = 2000

    /** 单页字符上限：行再短也可能有 400 行超长内容，按字符数兜第二道。 */
    private const val PAGE_MAX_CHARS = 512 * 1024

    private const val DOCX_MAX_CHARS = 300_000

    /** document.xml 解压后的读取上限：正常文档远小于此值，超出视为异常文件，避免压缩炸弹撑爆内存。 */
    private const val DOCX_MAX_XML_BYTES = 16 * 1024 * 1024

    fun lengthOf(file: File): Long = runCatching { file.length() }.getOrDefault(0L)

    /**
     * 读一页 UTF-8 文本。
     *
     * 返回 null 表示「不是文本」或读失败：含 NUL 的文件（二进制）在这里就被挡住，
     * 预览页据此显示「该格式无法预览」，而不是把乱码铺出来。
     */
    fun readTextPage(open: () -> InputStream, fromLine: Int, pageLines: Int = PAGE_LINES): TextPage? {
        return runCatching {
            open().bufferedReader(Charsets.UTF_8).use { reader ->
                var line = 0
                while (line < fromLine) {
                    val skipped = reader.readLine() ?: return@use TextPage(emptyList(), fromLine, true)
                    if (skipped.indexOf('\u0000') >= 0) return@use null
                    line++
                }
                val out = ArrayList<String>(pageLines)
                var bytes = 0
                while (out.size < pageLines && bytes < PAGE_MAX_CHARS) {
                    val text = reader.readLine() ?: break
                    if (text.indexOf('\u0000') >= 0) return@use null
                    bytes += text.length + 1
                    out.add(if (text.length > MAX_LINE_CHARS) text.take(MAX_LINE_CHARS) + " …" else text)
                }
                // 只多读一个字符就能判断有没有下一页：读完即停，不影响已取到的行
                val eof = out.size < pageLines || reader.read() == -1
                TextPage(out, fromLine + out.size, eof)
            }
        }.getOrNull()
    }

    /** 读整份字节，超过 [maxBytes] 返回 null：半份 PDF 只会渲染失败，不如直接说不支持。 */
    fun readBytes(open: () -> InputStream, maxBytes: Long): ByteArray? {
        return runCatching {
            open().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > maxBytes) return@use null
                }
                out.toByteArray()
            }
        }.getOrNull()
    }

    /**
     * 抽 docx 的纯文本：只取 <w:t> 的字面内容，段落边界换行。
     *
     * 不保留排版、表格与图片——要保真必须把文档转成 PDF，那属于另一条链路（宿主机转换）。
     * 用 ZipInputStream 而不是 ZipFile：SAF 工作区的文件没有可直接打开的本地路径。
     */
    fun docxText(open: () -> InputStream): String? {
        return runCatching {
            var xml: String? = null
            ZipInputStream(open().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        xml = readCapped(zip, DOCX_MAX_XML_BYTES)?.toString(Charsets.UTF_8)
                            ?: return@runCatching null
                        break
                    }
                }
            }
            val source = xml ?: return@runCatching null
            val out = StringBuilder()
            var index = 0
            while (index < source.length && out.length < DOCX_MAX_CHARS) {
                val nextText = source.indexOf("<w:t", index)
                val nextPara = source.indexOf("</w:p>", index)
                val textFirst = nextText >= 0 && (nextPara < 0 || nextText < nextPara)
                if (textFirst) {
                    val tagEnd = source.indexOf('>', nextText)
                    if (tagEnd < 0) break
                    val close = source.indexOf("</w:t>", tagEnd)
                    if (close < 0) break
                    out.append(unescapeXml(source.substring(tagEnd + 1, close)))
                    index = close + 6
                } else if (nextPara >= 0) {
                    out.append('\n')
                    index = nextPara + 6
                } else {
                    break
                }
            }
            out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
        }.getOrNull()
    }

    /** 读取至多 [maxBytes] 字节；超出返回 null（调用方按「不支持」处理）。 */
    private fun readCapped(input: InputStream, maxBytes: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > maxBytes) return null
        }
        return out.toByteArray()
    }

    private fun unescapeXml(text: String): String {
        if (text.indexOf('&') < 0) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch != '&') {
                out.append(ch)
                index++
                continue
            }
            val semi = text.indexOf(';', index)
            if (semi < 0 || semi - index > 10) {
                out.append(ch)
                index++
                continue
            }
            val body = text.substring(index + 1, semi)
            val decoded = when {
                body == "amp" -> "&"
                body == "lt" -> "<"
                body == "gt" -> ">"
                body == "quot" -> "\""
                body == "apos" -> "'"
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> null
            }
            if (decoded == null) {
                out.append(ch)
                index++
            } else {
                out.append(decoded)
                index = semi + 1
            }
        }
        return out.toString()
    }
}
