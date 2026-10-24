package com.zmf.agent.tools.spill

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 超大工具输出的「落盘 + 摘要」。
 *
 * 有些工具一次能吐几十万字符（读大文件、终端 cat、web_fetch 抓长页面），直接塞回模型
 * 会把上下文瞬间吃光。这里定一条内联上限：超了就把全文写到
 * filesDir/appdata/spill/<sessionId>/<时间戳>-<工具名>.txt，
 * 只把「头 + 尾 + 一行通告」交还给模型，真要看细节再让模型用 read_file 的 offset/limit 分段读。
 *
 * 全部 IO 都兜住：落盘失败就退回原文（宁可撑上下文，也不丢工具输出），只记一条日志。
 */
object SpillStore {

    /** 单条工具输出允许内联的 token 上限；超过就落盘只回摘要。 */
    const val MAX_INLINE_TOKENS = 12500

    /** 摘要里保留的头部字符数。 */
    private const val HEAD_CHARS = 4000

    /** 摘要里保留的尾部字符数。 */
    private const val TAIL_CHARS = 2000

    /** sessionId 缺省值：调用方没给就落到这个公共目录。 */
    private const val DEFAULT_SESSION = "default"

    /** 工具名安全化后的最长长度（防止文件名过长写不进去）。 */
    private const val MAX_NAME_CHARS = 60

    private const val TAG = "spill"

    /** 落盘结果。[path] 只在 [spilled] 为 true 时有值。 */
    data class Result(val text: String, val spilled: Boolean, val path: String? = null)

    /**
     * 粗估 token 数：汉字按 1 个 token，其他字符约 4 个字符算 1 个 token。
     * 只是用来卡内联阈值的，不追求和真实分词器一致；宁可高估，别低估撑爆上下文。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var han = 0
        var other = 0
        for (ch in text) {
            // 代理对（emoji 等）会被拆成两个 Char，按普通字符算；估算略偏高不影响判断
            if (isHan(ch.code)) han++ else other++
        }
        // 其他字符 4 个算一个 token，向上取整
        return han + (other + 3) / 4
    }

    /** 汉字判定：基本区 + 扩展 A + 兼容区（都在 BMP 内，一个 Char 够用）。 */
    private fun isHan(code: Int): Boolean =
        code in 0x4E00..0x9FFF ||   // CJK 统一汉字
            code in 0x3400..0x4DBF ||   // 扩展 A
            code in 0xF900..0xFAFF      // 兼容汉字

    /**
     * 处理一条工具输出：没超阈值原样返回；超了就落盘并返回摘要。
     *
     * @param sessionId 会话 id，用来分目录；空串按 [DEFAULT_SESSION]
     */
    fun process(context: Context, sessionId: String, toolName: String, text: String): Result {
        if (estimateTokens(text) <= MAX_INLINE_TOKENS) return Result(text, false)
        return try {
            val dir = sessionDir(context, sessionId)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IllegalStateException("建不了 spill 目录：" + dir.absolutePath)
            }
            val file = nextFile(dir, toolName)
            file.writeText(text, Charsets.UTF_8)
            // 顺手刷新目录时间，cleanup 按目录 mtime 判断「最近有没有人在用」
            dir.setLastModified(System.currentTimeMillis())
            FileLog.log(
                TAG,
                "输出落盘：" + file.absolutePath + "（" + text.length + " 字符 / 约 " + estimateTokens(text) + " token）"
            )
            Result(summarize(text, file.absolutePath), true, file.absolutePath)
        } catch (e: Exception) {
            // 落盘失败不让工具调用失败：退回原文，记一条日志
            FileLog.log(TAG, "落盘失败，保留原文：" + (e.message ?: e.javaClass.simpleName))
            Result(text, false)
        }
    }

    /** 删掉 appdata/spill 下修改时间超过 [keepDays] 天的会话目录。 */
    fun cleanup(context: Context, keepDays: Int = 7) {
        try {
            val root = spillRoot(context)
            if (!root.isDirectory) return
            val deadline = System.currentTimeMillis() - keepDays.toLong() * 24L * 60L * 60L * 1000L
            var removed = 0
            root.listFiles()?.forEach { child ->
                if (!child.isDirectory) return@forEach
                if (child.lastModified() >= deadline) return@forEach
                if (child.deleteRecursively()) removed++ else FileLog.log(TAG, "清理失败：" + child.absolutePath)
            }
            if (removed > 0) FileLog.log(TAG, "清理 " + removed + " 个超过 " + keepDays + " 天的 spill 目录")
        } catch (e: Exception) {
            FileLog.log(TAG, "清理失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 落盘根目录：filesDir/appdata/spill。 */
    private fun spillRoot(context: Context): File = File(AppPaths.dataDir(context), "spill")

    /** 某会话的目录；不存在就建。 */
    private fun sessionDir(context: Context, sessionId: String): File {
        val dir = File(spillRoot(context), safeSessionId(sessionId))
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 会话 id 安全化：去掉路径分隔符等，防止跳目录；空值落到 default。 */
    private fun safeSessionId(sessionId: String): String {
        val trimmed = sessionId.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return DEFAULT_SESSION
        val safe = buildString(trimmed.length) {
            for (ch in trimmed) {
                append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '_')
            }
        }
        return if (safe.isBlank()) DEFAULT_SESSION else safe.take(MAX_NAME_CHARS)
    }

    /** 时间戳-工具名.txt，同一毫秒撞了就加序号。 */
    private fun nextFile(dir: File, toolName: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val name = safeToolName(toolName)
        var file = File(dir, stamp + "-" + name + ".txt")
        var index = 1
        while (file.exists() && index < 100) {
            file = File(dir, stamp + "-" + name + "-" + index + ".txt")
            index++
        }
        return file
    }

    /** 工具名安全化：只留字母数字和 - _。 */
    private fun safeToolName(toolName: String): String {
        val trimmed = toolName.trim()
        val safe = buildString(trimmed.length) {
            for (ch in trimmed) {
                append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_')
            }
        }
        return (if (safe.isBlank()) "tool" else safe).take(MAX_NAME_CHARS)
    }

    /** 头 4000 + 省略标记 + 尾 2000 + 一行通告（通告里必须是绝对路径，模型才能直接拿去 read_file）。 */
    private fun summarize(text: String, path: String): String {
        val head = text.take(HEAD_CHARS)
        val tail = text.drop(head.length).takeLast(TAIL_CHARS)
        val omitted = text.length - head.length - tail.length
        return buildString {
            append(head)
            append('\n')
            append("[ ... 中间省略 ").append(omitted).append(" 字符 ... ]")
            append('\n')
            append(tail)
            append('\n')
            append("完整内容在 ").append(path).append("，用 read_file 配 offset/limit 分段读，或用 terminal 的 grep 搜。")
        }
    }
}
