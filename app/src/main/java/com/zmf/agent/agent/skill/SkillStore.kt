package com.zmf.agent.agent.skill

import android.content.Context
import android.net.Uri
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 一个 Skill：目录形态是 <root>/<id>/SKILL.md，也接受单文件 <root>/<id>.md。
 *
 * 格式：YAML frontmatter 里必须有两个字段 name / description（name 用 kebab-case），
 * 正文就是给模型看的指令。我们不发明新格式。
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
 * 加载策略：**目录不塞进系统提示词**，只把「名字 + 一句话描述」作为 available_skills
 * 列表注入；正文等模型用 skill 工具取，这样几十个 skill 也不会把上下文吃满。
 */
object SkillStore {

    private const val TAG = "skill"
    private const val SKILL_FILE = "SKILL.md"
    private const val SKILL_FILE_LOWER = "skill.md"
    private const val MAX_SKILL_BYTES = 256 * 1024
    private const val MAX_ZIP_ENTRIES = 2000

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

    /** 只给提示词用的轻量目录：名字 + 描述（截 500 字符）。 */
    fun catalog(context: Context): List<Pair<String, String>> =
        list(context).map { it.name to it.description.take(500) }

    /** 名称或 id 都能查到。 */
    fun find(context: Context, key: String): SkillEntry? {
        val list = list(context)
        return list.firstOrNull { it.id == key } ?: list.firstOrNull { it.name == key }
    }

    /**
     * 读正文（含 frontmatter 之后的所有内容）。
     * 返回 null 表示找不到；超过 256KB 就截断，不让一个巨型 skill 把上下文撑爆。
     */
    fun load(context: Context, key: String): String? {
        val entry = find(context, key) ?: return null
        val text = runCatching { entry.skillFile.readText() }.getOrNull() ?: return null
        val body = splitFrontMatter(text).second
        return if (body.length > MAX_SKILL_BYTES) {
            body.take(MAX_SKILL_BYTES) + "\n\n（内容过长已截断）"
        } else {
            body
        }
    }

    /**
     * 从 zip 导入：zip 里可以有一个技能目录（含 SKILL.md），也可以直接是 SKILL.md。
     * 所有条目都做 ZipSlip 校验，越界直接整包失败（不留半截文件）。
     */
    fun importZip(context: Context, uri: Uri): Result<Int> = runCatching {
        val root = root(context)
        val staging = File(root, ".import_" + System.currentTimeMillis())
        if (!staging.mkdirs()) error("建临时目录失败")
        var entries = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (++entries > MAX_ZIP_ENTRIES) error("zip 条目太多（上限 " + MAX_ZIP_ENTRIES + "）")
                    val rawName = entry.name.replace('\\', '/')
                    if (rawName.startsWith("__MACOSX/") || rawName.contains("..")) {
                        zip.closeEntry(); entry = zip.nextEntry; continue
                    }
                    val target = File(staging, rawName)
                    if (!target.canonicalPath.startsWith(staging.canonicalPath)) {
                        error("zip 里有越界路径：" + rawName)
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
        } ?: error("打不开这个文件")
        // 找到 SKILL.md 的位置，把它所在的目录整体搬进 skills/
        val skillFile = staging.walkTopDown().firstOrNull {
            it.isFile && it.name.equals(SKILL_FILE, true)
        } ?: error("zip 里没有 SKILL.md")
        val skillDir = skillFile.parentFile ?: staging
        val id = sanitizeId(skillDir.name)
        val dest = File(root, id)
        if (dest.exists()) dest.deleteRecursively()
        if (skillDir == staging) {
            // zip 根目录就是技能目录
            if (!staging.renameTo(dest)) {
                staging.copyRecursively(dest, overwrite = true)
                staging.deleteRecursively()
            }
        } else {
            skillDir.copyRecursively(dest, overwrite = true)
            staging.deleteRecursively()
        }
        FileLog.log(TAG, "导入技能 " + id + "（" + entries + " 个条目）")
        list(context).size
    }.onFailure {
        FileLog.log(TAG, "导入失败：" + (it.message ?: it.javaClass.simpleName))
    }

    /** 直接从文本建一个技能（用户在 UI 里粘贴内容时用）。 */
    fun importText(context: Context, name: String, description: String, body: String): Result<SkillEntry> =
        runCatching {
            val id = sanitizeId(name)
            require(id.isNotEmpty()) { "名字不合法" }
            val dir = File(root(context), id)
            dir.mkdirs()
            File(dir, SKILL_FILE).writeText(buildMarkdown(name, description, body))
            FileLog.log(TAG, "由文本创建技能 " + id)
            parse(id, dir, File(dir, SKILL_FILE)) ?: error("解析失败")
        }.onFailure { FileLog.log(TAG, "创建失败：" + (it.message ?: it.javaClass.simpleName)) }

    /** 按名称或 id 删除一个 skill（目录形态整目录递归移除）；找不到返回 false。 */
    fun delete(context: Context, key: String): Boolean {
        val entry = find(context, key) ?: return false
        val ok = if (entry.dir.isDirectory) entry.dir.deleteRecursively() else entry.skillFile.delete()
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
        val (meta, _) = splitFrontMatter(text)
        val name = meta["name"]?.ifBlank { null } ?: id
        val description = meta["description"].orEmpty()
        return SkillEntry(
            id = id,
            name = name,
            description = description,
            dir = dir,
            skillFile = file,
            whenToUse = meta["whenToUse"].orEmpty()
        )
    }

    /** 拆 frontmatter：只有文件以 --- 开头才算；返回「键值表 + 正文」。 */
    fun splitFrontMatter(text: String): Pair<Map<String, String>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---")) return emptyMap<String, String>() to normalized
        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, String>() to normalized
        val header = normalized.substring(3, end)
        val body = normalized.substring(end + 4).trimStart('\n')
        val map = LinkedHashMap<String, String>()
        header.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0 && !line.trimStart().startsWith("#")) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim().trim('"', '\'')
                if (key.isNotEmpty()) map[key] = value
            }
        }
        return map to body
    }

    /** 拼一份带 YAML frontmatter 的 SKILL.md 文本（由文本创建技能时用）。 */
    fun buildMarkdown(name: String, description: String, body: String): String =
        "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body.trim() + "\n"

    private fun sanitizeId(raw: String): String {
        val cleaned = raw.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
        return if (cleaned == "." || cleaned == "..") "" else cleaned.take(64)
    }
}
