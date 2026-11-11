package com.zmf.agent.feature.chat

import android.graphics.BitmapFactory
import com.zmf.agent.capability.tools.LEGACY_ACTION_SUMMARY_PARAM
import com.zmf.agent.capability.tools.STEP_DESCRIPTION_PARAM
import org.json.JSONObject
import java.io.File

/*
 * 工具卡的两项纯数据处理：一句话总结、输出中的图片附件。
 *
 * 为什么单独一个文件：主页卡（ToolBlock.kt）与轨迹卡（TraceToolCard.kt）有意分为两份 UI
 * （两页语义不同，见各自 KDoc），但其对「这条工具执行了什么」「输出中是否有图」的判断
 * 必须一致。两项均为纯函数，抽出共用不违反该划分，且可减少一处不一致的可能。
 */

/** 一句话总结的字符上限：标题行只有一行，再长就省略号。 */
private const val SUMMARY_LIMIT = 44

/** 参数中最可能充当宾语的字段，按优先级取第一个非空值。 */
private val SUMMARY_ARG_KEYS = listOf(
    "path", "file_path", "filePath", "command", "cmd", "query", "url",
    "description", "prompt", "title", "goal", "pattern", "name", "session_id"
)

/** 每个工具对应的动词：无需识别工具名即可读出「正在执行什么」。 */
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
 * 这条工具执行了什么，一句话说明。
 *
 * 规则：动词 + 参数中的宾语（路径 / 命令 / 查询词 …），截断至 SUMMARY_LIMIT。
 * 无法识别的工具退化为「工具名 · 首参数」，至少不为空。
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
 * 工具卡与轨迹行的标题。
 *
 * 取值顺序与 DSH 的 `bash` 工具一致：**先取模型写的 `description`**（这一步在做什么），
 * 缺失时退回按工具名与参数推断的 [toolSummary]（读取 xxx、列出 xxx）。
 * 两处显示必须由同一个函数决定，否则同一调用在主页与轨迹页会说成两句话。
 */
internal fun toolHeadline(name: String, arguments: String): String =
    stepDescription(arguments) ?: toolSummary(name, arguments)

/**
 * 模型写入的这一步说明；缺失、非字符串或空白时返回 null。
 *
 * 新调用只写 [STEP_DESCRIPTION_PARAM]；[LEGACY_ACTION_SUMMARY_PARAM] 是为 2026-11-10 之前
 * 的会话保留的读取路径，两者同义，读的时候都认。
 */
internal fun stepDescription(arguments: String): String? {
    if (arguments.isBlank()) return null
    val obj = runCatching { JSONObject(arguments) }.getOrNull() ?: return null
    // 只认字符串：缺键、null 与数组 / 对象都不当作说明，避免标题行出现「null」
    val raw = (obj.opt(STEP_DESCRIPTION_PARAM) as? String)
        ?: (obj.opt(LEGACY_ACTION_SUMMARY_PARAM) as? String)
        ?: return null
    return raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim().ifEmpty { null }
}

/**
 * 参数是否「等于空」。
 *
 * 空串、全空白、花括号空对象、仅含 null / 空串 / 空数组的对象，均视为空：此时参数区
 * 不应渲染，否则「参数」标题下仅剩一个空花括号。
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

/** 参数中最可能充当宾语的值；取不到时将整串压为一行。 */
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

/** 一条输出最多绘制几张附件卡：再多则成为「翻阅相册」而非「查看结果」。 */
private const val MAX_ATTACHMENTS = 6

/**
 * 从工具输出中筛选真实存在的图片，生成附件卡数据。
 *
 * 仅认可**存在**的文件；无法解析的路径直接忽略，绝不抛出异常：
 * 输出为模型生成的文本，不可假设其格式正确。
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