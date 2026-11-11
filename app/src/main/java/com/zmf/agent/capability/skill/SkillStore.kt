package com.zmf.agent.capability.skill

import android.content.Context
import android.net.Uri
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/**
 * 一个 Skill：目录形态是 <root>/<id>/SKILL.md，也接受单文件 <root>/<id>.md。
 *
 * 格式：YAML frontmatter 中必须包含 name / description 两个字段（name 使用 kebab-case），
 * 正文即提供给模型的指令。本实现不引入新格式。
 */
data class SkillEntry(
    val id: String,
    val name: String,
    val description: String,
    val dir: File,
    val skillFile: File,
    /** frontmatter 里的可选 whenToUse，没有就是空串。 */
    val whenToUse: String = ""
)

/**
 * Skill 的发现 / 加载 / 导入。
 *
 * 加载策略：**目录不写入系统提示词**，仅将「名称 + 一句话描述」作为 available_skills
 * 列表注入；正文由模型通过 skill 工具按需读取，避免大量技能占用上下文。
 */
object SkillStore {

    private const val TAG = "skill"
    private const val SKILL_FILE = "SKILL.md"
    private const val SKILL_FILE_LOWER = "skill.md"
    private const val MAX_SKILL_BYTES = 256 * 1024
    private const val MAX_ZIP_ENTRIES = 2000

    /** 受管字段：写回时整行重写，其余 frontmatter 字段原样保留。 */
    private val MANAGED_KEYS = setOf("name", "description", "whenToUse", "when_to_use")

    /** YAML 多行标量的起始记号。 */
    private val MULTILINE_MARKS = setOf("|", ">", "|-", ">-", "|+", ">+")

    /** 内容版本号：任何写操作后自增，供上层判断缓存的目录是否过期。 */
    private val revisionCounter = AtomicInteger(0)

    /** 当前内容版本；与缓存时记录的值不同即表示需要重扫。 */
    val revision: Int get() = revisionCounter.get()

    private fun bumpRevision() {
        revisionCounter.incrementAndGet()
    }

    /** 技能根目录：filesDir/appdata/skills。 */
    fun root(context: Context): File {
        val dir = File(AppPaths.dataDir(context), "skills")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 列出全部可用 skill（按名字排序；解析失败的目录跳过并记日志）。 */
    fun list(context: Context): List<SkillEntry> {
        val root = root(context)
        val out = ArrayList<SkillEntry>()
        root.listFiles()?.forEach { child ->
            runCatching {
                if (child.isDirectory) {
                    val file = File(child, SKILL_FILE).takeIf { it.isFile }
                        ?: File(child, SKILL_FILE_LOWER).takeIf { it.isFile }
                        ?: return@runCatching
                    parse(child.name, child, file)?.let { out.add(it) }
                } else if (child.isFile && child.name.endsWith(".md", ignoreCase = true)) {
                    parse(child.nameWithoutExtension, child.parentFile ?: root, child)?.let { out.add(it) }
                }
            }.onFailure { FileLog.log(TAG, "解析 " + child.name + " 失败：" + it.message) }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * 只给提示词用的轻量目录：名字 + 描述（截 500 字符）。
     *
     * 有 whenToUse 时接在描述后面：它比描述更直接地回答「什么情况下该用」，
     * 模型据此判断要不要加载正文，比只看描述更准。
     */
    fun catalog(context: Context): List<Pair<String, String>> =
        list(context).map { entry ->
            val desc = entry.description.take(500)
            val whenText = entry.whenToUse.trim()
            entry.name to if (whenText.isEmpty()) {
                desc
            } else {
                desc + "（适用：" + whenText.take(120) + "）"
            }
        }

    /** 名称或 id 都能查到。 */
    fun find(context: Context, key: String): SkillEntry? {
        val list = list(context)
        return list.firstOrNull { it.id == key } ?: list.firstOrNull { it.name == key }
    }

    /**
     * 读正文（含 frontmatter 之后的所有内容）。
     * 返回 null 表示找不到；超过 256KB 时按 UTF-8 字节截断，避免超长技能文本占满上下文。
     */
    fun load(context: Context, key: String): String? {
        val entry = find(context, key) ?: return null
        val text = runCatching { entry.skillFile.readText() }.getOrNull() ?: return null
        val body = splitFrontMatter(text).second
        return if (body.toByteArray(Charsets.UTF_8).size > MAX_SKILL_BYTES) {
            truncateUtf8(body, MAX_SKILL_BYTES) + "\n\n（内容过长已截断）"
        } else {
            body
        }
    }

    /** 按 UTF-8 字节截断，且不把多字节字符切成半个。 */
    private fun truncateUtf8(input: String, maxBytes: Int): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return input
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    /**
     * 从 zip 导入：zip 中可以包含一个技能目录（含 SKILL.md），也可以直接为 SKILL.md。
     * 所有条目均执行 ZipSlip 校验，越界时整包失败；失败路径会删除临时目录。
     */
    fun importZip(context: Context, uri: Uri): Result<Int> {
        val staging = File(root(context), ".import_" + System.currentTimeMillis())
        return runCatching { importInto(context, uri, staging) }.onFailure {
            staging.deleteRecursively()
            FileLog.log(TAG, "导入失败：" + (it.message ?: it.javaClass.simpleName))
        }
    }

    /** [importZip] 的实现：条目解到 [staging]，确认 SKILL.md 后再移入 skills/。 */
    private fun importInto(context: Context, uri: Uri, staging: File): Int {
        val root = staging.parentFile ?: error("无法定位技能目录，请重新导入")
        if (!staging.mkdirs()) error("无法创建临时目录，请检查存储空间后重试")
        var entries = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (++entries > MAX_ZIP_ENTRIES) error("压缩包内文件过多，已取消导入")
                    val rawName = entry.name.replace('\\', '/')
                    if (rawName.startsWith("__MACOSX/") || rawName.contains("..")) {
                        zip.closeEntry(); entry = zip.nextEntry; continue
                    }
                    val target = File(staging, rawName)
                    val stagingPath = staging.canonicalPath
                    val targetPath = target.canonicalPath
                    if (targetPath != stagingPath && !targetPath.startsWith(stagingPath + File.separator)) {
                        error("压缩包含越界路径，已取消导入")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> zip.copyTo(output) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("无法读取所选文件，请重新选择")
        // 定位 SKILL.md，将其所在目录整体移入 skills/
        val skillFile = staging.walkTopDown().firstOrNull {
            it.isFile && it.name.equals(SKILL_FILE, true)
        } ?: error("压缩包里没有 SKILL.md，请把包含 SKILL.md 的技能目录重新打包")
        val skillDir = skillFile.parentFile ?: staging
        // 根级 SKILL.md（压缩包没套目录）时，目录名是临时目录，不能拿来当 id：
        // 优先用 frontmatter 里的 name，再退回时间戳，避免落成 .import_xxx 这类隐藏目录。
        val dirName = if (skillDir == staging) {
            frontMatter(runCatching { skillFile.readText() }.getOrDefault("")).meta["name"].orEmpty()
                .ifBlank { "imported-" + System.currentTimeMillis() }
        } else {
            skillDir.name
        }
        val id = sanitizeId(dirName).ifEmpty { "imported-" + System.currentTimeMillis() }
        val dest = File(root, id)
        if (dest.exists()) dest.deleteRecursively()
        if (skillDir == staging) {
            // zip 根目录本身即技能目录
            if (!staging.renameTo(dest)) {
                staging.copyRecursively(dest, overwrite = true)
                staging.deleteRecursively()
            }
        } else {
            skillDir.copyRecursively(dest, overwrite = true)
            staging.deleteRecursively()
        }
        FileLog.log(TAG, "导入技能 " + id + "（" + entries + " 个条目）")
        bumpRevision()
        return list(context).size
    }

    /**
     * 创建或更新一个技能。
     *
     * - [key] 为空表示新建：id 由 [name] 生成；同名目录已存在时要求 [overwrite]，
     *   避免用户/模型在不知情的情况下覆盖别人的技能；
     * - [key] 非空表示更新：沿用原 id 与原文件（含单文件形态 <id>.md），
     *   未识别的 frontmatter 字段（license / allowed-tools / metadata 等）原样保留。
     *
     * 名称、描述、正文三者均不得为空：描述是模型判断「什么时候该用」的唯一依据，
     * 正文为空则技能没有任何可执行内容。
     */
    fun save(
        context: Context,
        key: String?,
        name: String,
        description: String,
        body: String,
        whenToUse: String = "",
        overwrite: Boolean = false
    ): Result<SkillEntry> = runCatching {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "名称不能为空" }
        val desc = description.trim()
        require(desc.isNotEmpty()) { "描述不能为空：模型据此判断何时使用该技能" }
        val text = body.trim()
        require(text.isNotEmpty()) { "正文不能为空：技能缺少可执行的步骤" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_SKILL_BYTES) { "正文超过 256KB，请拆分后再保存" }

        val existing = key?.takeIf { it.isNotBlank() }?.let { find(context, it) }
        val id = existing?.id ?: sanitizeId(trimmedName)
        require(id.isNotEmpty()) { "名称里至少要有一个字母或数字" }
        // 更新时允许改名：新名字若已被另一个技能占用，不能悄悄制造两个同名技能
        if (existing != null && !overwrite) {
            val clash = find(context, trimmedName)
            if (clash != null && clash.id != existing.id) {
                error("已有同名技能「" + clash.name + "」，请改用其他名称")
            }
        }
        // 更新时沿用原 id；新建时同名目录已存在则视为冲突
        val conflict = existing ?: find(context, id)
        if (conflict == null && File(root(context), id).exists() && !overwrite) {
            error("已有同名技能「" + id + "」，请改用其他名称或确认覆盖")
        }
        val file = conflict?.skillFile ?: File(File(root(context), id), SKILL_FILE)
        val preserved = conflict
            ?.let { runCatching { frontMatter(it.skillFile.readText()).rawHeader }.getOrNull() }
        file.parentFile?.mkdirs()
        val markdown = buildMarkdown(trimmedName, desc, whenToUse, text, preserved)
        // 先写 .tmp 再改名：写入中断时不会留下半截 SKILL.md
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(markdown)
        if (!temp.renameTo(file)) {
            file.writeText(markdown)
            temp.delete()
        }
        bumpRevision()
        FileLog.log(TAG, (if (conflict == null) "创建技能 " else "更新技能 ") + id)
        parse(id, file.parentFile ?: root(context), file) ?: error("保存后解析失败")
    }.onFailure { FileLog.log(TAG, "保存失败：" + (it.message ?: it.javaClass.simpleName)) }

    /** 读原始全文（含 frontmatter），供 UI 编辑时回填。 */
    fun readRaw(context: Context, key: String): String? =
        find(context, key)?.let { runCatching { it.skillFile.readText() }.getOrNull() }

    /**
     * 预读 zip，判断它会落成哪个 id（只读条目名，不解压）。
     *
     * 用于导入前的覆盖确认；根级 SKILL.md（压缩包没套目录）的 id 依赖 frontmatter，
     * 此处返回空串表示「未知」，由调用方跳过预检。读不出时返回 null。
     */
    fun peekZipId(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    val lower = name.lowercase()
                    if (lower == SKILL_FILE_LOWER) {
                        // 根级 SKILL.md 没有目录名可依，改读 frontmatter 里的 name
                        val text = runCatching { zip.readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
                        return@use sanitizeId(frontMatter(text).meta["name"].orEmpty())
                    }
                    if (lower.endsWith("/" + SKILL_FILE_LOWER)) {
                        return@use sanitizeId(name.substringBeforeLast('/').substringAfterLast('/'))
                    }
                    entry = zip.nextEntry
                }
                null
            }
        }
    }.getOrNull()

    /** 按名称或 id 删除一个 skill（目录形态整目录递归移除）；找不到返回 false。 */
    fun delete(context: Context, key: String): Boolean {
        val entry = find(context, key) ?: return false
        // 单文件形态（<id>.md）的 dir 就是技能根目录，递归删除会清空整个技能库
        val ownDir = entry.dir.isDirectory && entry.dir != root(context)
        val ok = if (ownDir) entry.dir.deleteRecursively() else entry.skillFile.delete()
        if (ok) bumpRevision()
        FileLog.log(TAG, "删除技能 " + entry.id + "：" + ok)
        return ok
    }

    // ---- 解析 ----

    private fun parse(id: String, dir: File, file: File): SkillEntry? {
        if (file.length() > MAX_SKILL_BYTES * 4L) {
            FileLog.log(TAG, id + " 过大，跳过")
            return null
        }
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val parsed = frontMatter(text)
        val meta = parsed.meta
        val name = meta["name"]?.ifBlank { null } ?: id
        val description = meta["description"].orEmpty()
        return SkillEntry(
            id = id,
            name = name,
            description = description,
            dir = dir,
            skillFile = file,
            // 两种写法都认：规范里是 whenToUse，部分技能沿用下划线写法
            whenToUse = meta["whenToUse"].orEmpty().ifBlank { meta["when_to_use"].orEmpty() }
        )
    }

    /** frontmatter 的解析结果：键值表 + 正文 + 原始头部（回写时保留未识别字段）。 */
    data class Parsed(val meta: Map<String, String>, val body: String, val rawHeader: String?)

    /**
     * 拆 frontmatter：只有文件以 --- 开头才算。
     *
     * 只覆盖 Agent Skills 常见的 YAML 子集：`key: value`、引号字符串、
     * `|` / `>` 多行标量与 `- item` 列表。不做完整 YAML 解析——未知结构原样保留在
     * [Parsed.rawHeader] 里，写回时逐行照抄，因此不会被破坏。
     */
    fun frontMatter(text: String): Parsed {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---")) return Parsed(emptyMap(), normalized, null)
        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return Parsed(emptyMap(), normalized, null)
        val header = normalized.substring(3, end)
        val body = normalized.substring(end + 4).trimStart('\n')
        return Parsed(parseHeader(header), body, header)
    }

    /** 兼容旧调用：只要键值表与正文。 */
    fun splitFrontMatter(text: String): Pair<Map<String, String>, String> {
        val parsed = frontMatter(text)
        return parsed.meta to parsed.body
    }

    private fun parseHeader(header: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val lines = header.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                i++
                continue
            }
            val idx = line.indexOf(':')
            if (idx <= 0) {
                i++
                continue
            }
            val key = line.substring(0, idx).trim()
            val rawValue = line.substring(idx + 1).trim()
            if (key.isEmpty()) {
                i++
                continue
            }
            // 多行标量：| 保留换行，> 折叠为空格；后续缩进行都属于这个值
            if (rawValue in MULTILINE_MARKS) {
                val folded = rawValue.startsWith(">")
                val collected = StringBuilder()
                i++
                while (i < lines.size) {
                    val next = lines[i]
                    if (next.isNotBlank() && !next.startsWith(" ") && !next.startsWith("\t")) break
                    collected.append(if (folded) next.trim() else next).append('\n')
                    i++
                }
                map[key] = if (folded) {
                    collected.toString().replace("\n", " ").trim()
                } else {
                    collected.toString().trimEnd('\n')
                }
                continue
            }
            // 列表：值为空且后面跟若干「- item」
            if (rawValue.isEmpty()) {
                val items = ArrayList<String>()
                var j = i + 1
                while (j < lines.size && lines[j].trimStart().startsWith("- ")) {
                    items.add(lines[j].trimStart().removePrefix("- ").trim())
                    j++
                }
                if (items.isNotEmpty()) {
                    map[key] = items.joinToString(", ")
                    i = j
                    continue
                }
            }
            map[key] = unquote(rawValue)
            i++
        }
        return map
    }

    /** 双引号字符串的单遍反转义：`\\`、`\"`、`\n`、`\r`、`\t`，其余转义按字面保留。 */
    private fun unquote(raw: String): String {
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            val inner = raw.substring(1, raw.length - 1)
            val out = StringBuilder(inner.length)
            var i = 0
            while (i < inner.length) {
                val c = inner[i]
                if (c == '\\' && i + 1 < inner.length) {
                    when (val next = inner[i + 1]) {
                        '\\' -> out.append('\\')
                        '"' -> out.append('"')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        else -> out.append(next)
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
        if (raw.length >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
            return raw.substring(1, raw.length - 1).replace("''", "'")
        }
        return raw
    }

    /**
     * 拼一份带 YAML frontmatter 的 SKILL.md 文本。
     *
     * [preservedHeader] 非空时先照抄其中未识别的字段（license / allowed-tools / metadata 等），
     * 只重写受管字段，保证「编辑一个导入的技能」不会把它原有的元数据抹掉。
     */
    fun buildMarkdown(
        name: String,
        description: String,
        whenToUse: String = "",
        body: String,
        preservedHeader: String? = null
    ): String {
        val head = StringBuilder("---\n")
        if (preservedHeader != null) {
            val lines = preservedHeader.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val idx = line.indexOf(':')
                val key = if (idx > 0) line.substring(0, idx).trim() else ""
                val topLevel = !line.startsWith(" ") && !line.startsWith("\t")
                if (key in MANAGED_KEYS && topLevel) {
                    // 受管字段整块丢弃：连带它的多行续行 / 列表项
                    i++
                    while (i < lines.size && (lines[i].startsWith(" ") || lines[i].startsWith("\t"))) i++
                    continue
                }
                if (line.isNotBlank()) head.append(line).append('\n')
                i++
            }
        }
        head.append("name: ").append(yamlScalar(name)).append('\n')
        head.append("description: ").append(yamlScalar(description)).append('\n')
        if (whenToUse.isNotBlank()) head.append("whenToUse: ").append(yamlScalar(whenToUse)).append('\n')
        head.append("---\n\n").append(body.trim()).append('\n')
        return head.toString()
    }

    /** 需要转义时加双引号：含冒号、井号、换行或首字符是 YAML 指示符。 */
    private fun yamlScalar(value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return "\"\""
        val needsQuote = v.contains(':') || v.contains('#') || v.contains('\n') || v.contains('\r') ||
            v.contains('\t') || v.startsWith("-") || v.startsWith("&") || v.startsWith("*") ||
            v.startsWith("[") || v.startsWith("{") || v.startsWith("!") || v.startsWith("?") ||
            v.startsWith(">") || v.startsWith("|") || v.startsWith("%") || v.startsWith("@") ||
            v.startsWith("`") || v.startsWith("'") || v.startsWith("\"") || v.contains("  ")
        return if (needsQuote) {
            "\"" + v
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\""
        } else {
            v
        }
    }

    private fun sanitizeId(raw: String): String {
        val cleaned = raw.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
        return if (cleaned == "." || cleaned == "..") "" else cleaned.take(64)
    }
}
