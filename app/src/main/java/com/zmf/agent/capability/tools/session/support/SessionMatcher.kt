package com.zmf.agent.capability.tools.session.support

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpace
import java.util.Locale

/** 检索范围。 */
internal enum class SearchScope {
    ALL,
    TITLE,
    CONTENT;

    companion object {
        /** 解析取值；无法识别时按 [ALL] 处理，避免因可选参数拼写错误直接失败。 */
        fun of(raw: String): SearchScope = when (raw.trim().lowercase(Locale.ROOT)) {
            "title" -> TITLE
            "content" -> CONTENT
            else -> ALL
        }
    }
}

/** 索引中的一条消息。 */
internal class IndexedMessage(val role: String, val text: String) {
    /** 小写文本按需生成：索引建立阶段不做无谓的全量转换。 */
    val lower: String by lazy { text.lowercase(Locale.ROOT) }
}

/** 索引中的一条会话；空白会话不入索引。 */
internal class IndexedSession(
    val id: String,
    val title: String,
    val workspaceId: String?,
    val workspaceName: String,
    val archived: Boolean,
    val updatedAt: Long,
    val messages: List<IndexedMessage>
) {
    val titleLower: String by lazy { title.lowercase(Locale.ROOT) }
    val workspaceLower: String by lazy { workspaceName.lowercase(Locale.ROOT) }
}

/** 一次命中的结果。 */
internal class SearchHit(
    val session: IndexedSession,
    val score: Int,
    val role: String,
    val snippet: String
)

/**
 * 会话索引的内存缓存。
 *
 * 以 sessions.json 的最后修改时刻与字节长度为缓存键：键不变即复用上一次的索引，
 * 会话写入（消息追加、改名、归档）都会改变这两个值。索引本身只保存原文，
 * 小写文本在首次检索时按需生成，避免构建阶段复制整份会话内容。
 */
internal object SessionIndexCache {

    private const val FILE_NAME = "sessions.json"

    @Volatile
    private var cachedKey: String = ""

    @Volatile
    private var cached: List<IndexedSession> = emptyList()

    /**
     * 取得索引；文件存在但解析结果为空时视为索引失败，由调用方报告 E_INDEX_FAILED。
     */
    @Synchronized
    fun load(context: Context, spaces: List<WorkSpace>): List<IndexedSession> {
        val file = AppPaths.dataFile(context, FILE_NAME)
        val key = file.lastModified().toString() + ":" + file.length()
        if (key == cachedKey) return cached
        val sessions = SessionStore.load(context)
        // 会话存储读取失败时返回空表而非抛异常：此时无法区分「确实没有会话」与「文件损坏」，
        // 只能以文件非空作为判据，避免把损坏文件静默当作空表。
        if (file.exists() && file.length() > 0L && sessions.isEmpty()) {
            throw IllegalStateException("sessions.json 无法解析")
        }
        cached = build(sessions, spaces)
        cachedKey = key
        return cached
    }

    private fun build(sessions: List<ChatSession>, spaces: List<WorkSpace>): List<IndexedSession> {
        val names = spaces.associate { it.id to it.name }
        val result = ArrayList<IndexedSession>(sessions.size)
        for (session in sessions) {
            if (session.blank) continue
            val messages = ArrayList<IndexedMessage>(session.messages.size)
            for (message in session.messages) {
                if (message.role != "user" && message.role != "assistant") continue
                if (message.content.isBlank()) continue
                messages.add(IndexedMessage(message.role, message.content))
            }
            if (messages.isEmpty() && session.title.isBlank()) continue
            result.add(
                IndexedSession(
                    id = session.id,
                    title = session.title,
                    workspaceId = session.workspaceId,
                    workspaceName = session.workspaceId?.let { names[it] } ?: "未分类",
                    archived = session.archived,
                    updatedAt = session.updatedAt,
                    messages = messages
                )
            )
        }
        return result
    }
}

/**
 * 会话匹配：全部检索词命中才算命中，标题、正文、工作区名按 3 / 2 / 1 计分。
 *
 * 拉丁词按整词小写匹配；中文按双字组切分，各组之间为「与」关系，
 * 这样检索「定时任务」不会命中仅含「定时」的无关会话。
 */
internal object SessionMatcher {

    private const val TITLE_WEIGHT = 3
    private const val CONTENT_WEIGHT = 2
    private const val WORKSPACE_WEIGHT = 1

    private val WHITESPACE = Regex("\\s+")

    /** 把检索词拆成若干组：同一空白词的各部分之间为「与」关系。 */
    fun splitTerms(query: String): List<List<String>> =
        query.trim().split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .map { splitTerm(it) }
            .filter { it.isNotEmpty() }

    /**
     * 匹配一条会话；不命中时返回 null。
     *
     * [snippetChars] 为片段的最大字符数，片段取自首个命中消息。
     */
    fun match(
        session: IndexedSession,
        terms: List<List<String>>,
        scope: SearchScope,
        snippetChars: Int
    ): SearchHit? {
        var score = 0
        var role = ""
        var snippet = ""
        var snippetReady = false
        for (term in terms) {
            var best = 0
            if (scope != SearchScope.CONTENT && indexOfAll(session.titleLower, term) >= 0) {
                best = TITLE_WEIGHT
            }
            var hitMessage: IndexedMessage? = null
            var hitAt = -1
            if (scope != SearchScope.TITLE) {
                for (message in session.messages) {
                    val at = indexOfAll(message.lower, term)
                    if (at >= 0) {
                        hitMessage = message
                        hitAt = at
                        break
                    }
                }
                if (hitMessage != null) best = maxOf(best, CONTENT_WEIGHT)
            }
            if (scope == SearchScope.ALL && indexOfAll(session.workspaceLower, term) >= 0) {
                best = maxOf(best, WORKSPACE_WEIGHT)
            }
            if (best == 0) return null
            score += best
            if (!snippetReady) {
                val message = hitMessage
                if (message != null) {
                    role = message.role
                    snippet = snippetOf(message.text, hitAt, snippetChars)
                    snippetReady = true
                } else if (session.title.isNotEmpty()) {
                    role = ""
                    snippet = oneLine(session.title)
                    snippetReady = true
                }
            }
        }
        return SearchHit(session = session, score = score, role = role, snippet = snippet)
    }

    /** 全部部分在文本中出现时返回最小起始下标；任一缺失返回 -1。 */
    private fun indexOfAll(text: String, parts: List<String>): Int {
        var min = Int.MAX_VALUE
        for (part in parts) {
            val at = text.indexOf(part)
            if (at < 0) return -1
            if (at < min) min = at
        }
        return if (min == Int.MAX_VALUE) 0 else min
    }

    /** 以命中位置为中心截取片段，并压成一行。 */
    private fun snippetOf(text: String, anchor: Int, chars: Int): String {
        val limit = chars.coerceAtLeast(1)
        if (text.length <= limit) return oneLine(text)
        val half = limit / 2
        var start = (anchor - half).coerceAtLeast(0)
        var end = (start + limit).coerceAtMost(text.length)
        if (end - start < limit) start = (end - limit).coerceAtLeast(0)
        // 截断后重新计算结束位置，保证按字符数取足
        end = (start + limit).coerceAtMost(text.length)
        val body = oneLine(text.substring(start, end))
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + body + suffix
    }

    /** 把多行文本压成一行。 */
    private fun oneLine(text: String): String = text.replace(WHITESPACE, " ").trim()

    /**
     * 拆分单个检索词：拉丁与数字按整词，中文按双字组。
     *
     * 同一词内的各部分为「与」关系；单字中文保持单字匹配。
     */
    private fun splitTerm(raw: String): List<String> {
        val lower = raw.lowercase(Locale.ROOT)
        val parts = ArrayList<String>()
        val latin = StringBuilder()
        val cjk = StringBuilder()

        fun flushLatin() {
            if (latin.isNotEmpty()) {
                parts.add(latin.toString())
                latin.clear()
            }
        }

        fun flushCjk() {
            if (cjk.isEmpty()) return
            val text = cjk.toString()
            cjk.clear()
            if (text.length <= 2) {
                parts.add(text)
            } else {
                for (i in 0..text.length - 2) parts.add(text.substring(i, i + 2))
            }
        }

        for (ch in lower) {
            when {
                isCjk(ch) -> {
                    flushLatin()
                    cjk.append(ch)
                }
                ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' -> {
                    flushCjk()
                    latin.append(ch)
                }
                else -> {
                    flushLatin()
                    flushCjk()
                }
            }
        }
        flushLatin()
        flushCjk()
        return parts.distinct()
    }

    /** 是否为中日韩统一表意文字。 */
    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
    }
}
