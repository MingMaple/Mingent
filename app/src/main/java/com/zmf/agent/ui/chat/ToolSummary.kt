package com.zmf.agent.ui.chat

import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/*
 * 工具卡的两件「纯数据」活：一句话总结、输出里的图片附件。
 *
 * 为什么单独一个文件：主页卡（ToolBlock.kt）与轨迹卡（TraceToolCard.kt）刻意是两份 UI
 * （两页语义不同，见各自的 KDoc），但它们对「这条工具干了什么」「输出里有没有图」的判断
 * 必须一模一样。这两件事都是纯函数，抽出来共用不违反那条划分，反而少一处漂移的可能。
 */

/** 一句话总结的字符上限：标题行只有一行，再长就省略号。 */
private const val SUMMARY_LIMIT = 44

/** 参数里最像「宾语」的字段，按优先级取第一个非空值。 */
private val SUMMARY_ARG_KEYS = listOf(
    "path", "file_path", "filePath", "command", "cmd", "query", "url",
    "description", "prompt", "title", "goal", "pattern", "name", "session_id"
)

/** 每个工具自己的动词：一眼能读出「在干什么」，不需要用户认工具名。 */
private val TOOL_VERBS = mapOf(
    "read_file" to "读取",
    "write_file" to "写入",
    "list_files" to "列出",
    "terminal" to "运行命令",
    "web_fetch" to "抓取网页",
    "web_search" to "联网检索",
    "skill" to "加载 Skill",
    "shizuku" to "系统级操作",
    "todo_write" to "更新待办",
    "subagent" to "委派子智能体",
    "send_message" to "给子智能体发消息",
    "mem" to "记忆操作",
    "write_plan" to "提交计划",
    "goal" to "更新目标"
)

/**
 * 这条工具干了什么，一句话。
 *
 * 规则：动词 + 参数里的「宾语」（路径 / 命令 / 查询词 …），截断到 SUMMARY_LIMIT。
 * 认不出的工具退化成「工具名 · 首参数」，至少不空着。
 */
internal fun toolSummary(name: String, arguments: String): String {
    val verb = TOOL_VERBS[name]
    val arg = pickArg(arguments)
    val head = verb ?: name
    val text = when {
        arg.isBlank() -> head
        verb == null -> head + " · " + arg
        else -> verb + " " + arg
    }
    return if (text.length > SUMMARY_LIMIT) text.take(SUMMARY_LIMIT - 1) + "…" else text
}

/**
 * 参数是不是「等于没有」。
 *
 * 空串、全空白、花括号空对象、只含 null / 空串 / 空数组的对象，一律算空：这种时候整块参数区
 * 都不该渲染，否则「参数」标题下面只会剩一个空花括号的空块。
 */
internal fun toolArgIsBlank(arguments: String): Boolean {
    if (arguments.isBlank()) return true
    val trimmed = arguments.trim()
    if (trimmed == "{}" || trimmed == "null") return true
    val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return false
    if (obj.length() == 0) return true
    val keys = obj.keys()
    while (keys.hasNext()) {
        val value = obj.opt(keys.next()) ?: continue
        if (value === JSONObject.NULL) continue
        when (value) {
            is String -> if (value.isBlank()) continue
            is org.json.JSONArray -> if (value.length() == 0) continue
            is JSONObject -> if (value.length() == 0) continue
        }
        return false
    }
    return true
}

/** 参数里最像「宾语」的那一个值；取不到就整串压成一行。 */
private fun pickArg(arguments: String): String {
    if (arguments.isBlank()) return ""
    val flat = arguments.trim().let { raw ->
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        if (obj == null) {
            raw
        } else {
            SUMMARY_ARG_KEYS.firstNotNullOfOrNull { key ->
                val value = obj.opt(key) ?: return@firstNotNullOfOrNull null
                if (value === JSONObject.NULL || value is JSONObject || value is org.json.JSONArray) {
                    return@firstNotNullOfOrNull null
                }
                value.toString().takeIf { it.isNotBlank() }
            } ?: ""
        }
    }.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
    return flat.trim()
}

/**
 * 输出里的一张图片附件：缩略图 + 文件名 + 一行元信息。
 *
 * relPath 交给查看器（相对工作区）；meta 是「image/webp · 58KB · 1307 × 1616」。
 */
internal data class ToolAttachment(
    val relPath: String,
    val fileName: String,
    val meta: String
)

private val IMAGE_SUFFIXES = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif")

/** 一条输出里最多画几张附件卡：再多就不是「看结果」而是「翻相册」了。 */
private const val MAX_ATTACHMENTS = 6

/**
 * 从工具输出里挑出真实存在的图片，做成附件卡的数据。
 *
 * 只认**存在**的文件；解析不出来的路径直接忽略，绝不抛异常：
 * 输出是模型写的文本，不能假设它格式正确。
 */
internal fun toolAttachments(output: String?, root: File?): List<ToolAttachment> {
    if (output.isNullOrBlank()) return emptyList()
    val found = ArrayList<ToolAttachment>(2)
    val seen = HashSet<String>()
    val regex = Regex("[^\\s\"'`()<>\\[\\]]+\\.(?:png|jpg|jpeg|webp|gif)", RegexOption.IGNORE_CASE)
    for (match in regex.findAll(output)) {
        val raw = match.value.trimEnd('.', ',', ';', ':')
        if (raw.isEmpty()) continue
        val candidate = when {
            raw.startsWith("/") -> File(raw)
            root != null -> File(root, raw)
            else -> continue
        }
        if (!candidate.isFile) continue
        if (!seen.add(candidate.absolutePath)) continue
        val name = candidate.name
        val suffix = IMAGE_SUFFIXES.firstOrNull { name.endsWith(it, ignoreCase = true) } ?: continue
        val mime = when (suffix) {
            ".png" -> "image/png"
            ".webp" -> "image/webp"
            ".gif" -> "image/gif"
            else -> "image/jpeg"
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(candidate.absolutePath, bounds) }
        val dims = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            " · " + bounds.outWidth + " × " + bounds.outHeight
        } else {
            ""
        }
        val meta = mime + " · " + sizeText(candidate.length()) + dims
        val rel = if (root != null) candidate.relativeTo(root).path.replace('\\', '/') else candidate.absolutePath
        found.add(ToolAttachment(relPath = rel, fileName = name, meta = meta))
        if (found.size >= MAX_ATTACHMENTS) break
    }
    return found
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.0fKB", bytes / 1024.0)
    else -> bytes.toString() + "B"
}