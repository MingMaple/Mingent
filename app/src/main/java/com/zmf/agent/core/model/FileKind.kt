package com.zmf.agent.core.model

/** 预览页认的格式类别：只看文件名后缀，不读内容。 */
enum class FileKind {
    MARKDOWN, TEXT, CODE, PDF, IMAGE, DOCX, OFFICE_OTHER, ARCHIVE, OTHER;

    /** 能否在本应用内渲染；false 的类别只显示元信息与「用其他应用打开」。 */
    val previewable: Boolean
        get() = this == MARKDOWN || this == TEXT || this == CODE || this == PDF ||
            this == IMAGE || this == DOCX
}

/** 后缀到 [FileKind] 的唯一映射表；新增格式只改这里。 */
object FileKinds {

    private val markdown = setOf("md", "markdown", "mdx")
    private val text = setOf(
        "txt", "log", "csv", "tsv", "json", "jsonl", "yaml", "yml", "toml", "ini",
        "conf", "cfg", "properties", "env", "xml", "svg", "diff", "patch", "srt", "vtt"
    )
    private val code = setOf(
        "kt", "kts", "java", "js", "mjs", "cjs", "ts", "jsx", "tsx", "py", "go", "rs",
        "c", "cc", "cpp", "h", "hpp", "cs", "rb", "php", "swift", "m", "mm", "sh",
        "bash", "zsh", "ps1", "bat", "gradle", "sql", "html", "htm", "css", "scss",
        "less", "vue", "lua", "dart", "proto", "asm", "s"
    )
    private val image = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "heic", "heif")
    private val office = setOf("doc", "ppt", "pptx", "xls", "xlsx", "xlsm")
    private val archive = setOf(
        "zip", "gz", "tgz", "tar", "bz2", "xz", "zst", "7z", "rar", "jar", "apk",
        "aar", "whl", "deb", "rpm", "iso"
    )

    /** 后缀（小写、不含点）；无后缀返回空串。 */
    fun suffixOf(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        // 以点开头的隐藏文件（.gitignore）不算有后缀：整串当后缀会误判
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /** 文件名（去掉目录）。 */
    fun nameOf(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    fun of(path: String): FileKind {
        return when (val suffix = suffixOf(path)) {
            in markdown -> FileKind.MARKDOWN
            in text -> FileKind.TEXT
            in code -> FileKind.CODE
            in image -> FileKind.IMAGE
            in office -> FileKind.OFFICE_OTHER
            in archive -> FileKind.ARCHIVE
            "pdf" -> FileKind.PDF
            "docx" -> FileKind.DOCX
            else -> if (suffix.isEmpty()) FileKind.TEXT else FileKind.OTHER
        }
    }

    /** 界面上显示的类型名：顶栏与卡片共用一处，避免两处各写一套。 */
    fun label(kind: FileKind): String = when (kind) {
        FileKind.MARKDOWN -> "Markdown"
        FileKind.TEXT -> "文本"
        FileKind.CODE -> "代码"
        FileKind.PDF -> "PDF"
        FileKind.IMAGE -> "图片"
        FileKind.DOCX -> "Word 文档"
        FileKind.OFFICE_OTHER -> "Office 文档"
        FileKind.ARCHIVE -> "压缩包"
        FileKind.OTHER -> "文件"
    }
}
