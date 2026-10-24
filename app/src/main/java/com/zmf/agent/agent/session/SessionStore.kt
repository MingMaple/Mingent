package com.zmf.agent.agent.session

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条会话：多会话功能的落盘单位。
 *
 * title 为空表示「用户还没说过话」，界面显示成「新会话」，第一次发消息时自动取前 20 字当标题。
 */
data class ChatSession(
    val id: String,
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 已归档的会话在侧边栏折叠起来，不删数据；旧版本落盘里没有这个 key，默认 false。 */
    val archived: Boolean = false,
    /** 置顶的会话在侧边栏排在最前、不被归档折叠挡住；旧版本落盘里没有这个 key，默认 false。 */
    val pinned: Boolean = false,
    /**
     * 所属工作区 id；null = 「未分类」（不属于任何工作区的会话）。
     * 旧版本落盘里没有这个 key，读到 null，历史会话一律落在未分类。
     */
    val workspaceId: String? = null,
    /**
     * 本会话累计用量（切会话不丢、回来能接上）。
     * 旧版本落盘里没有这几个 key，读到 0；为 0 时不写回文件，旧文件不会被立刻撑大。
     */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** 收到 usage 的次数（一次成功的模型请求算一次）。 */
    val requests: Int = 0,
    /** 该会话历史累计步数（每次回复结束时把本次步数加进来一次）。 */
    val steps: Int = 0,
    /**
     * 本会话累计的模型耗时 / 工具耗时（毫秒），每次回复结束时各加一次。
     * 旧版本落盘里没有这两个 key，读到 0；为 0 时不写回，旧文件不会被撑大。
     */
    val modelMillis: Long = 0,
    val toolMillis: Long = 0,
    /**
     * **上一轮**回复的模型耗时与输出 tokens（单独记一份）。
     *
     * 为什么不能拿累计值算速度：累计耗时在旧会话里是残缺的（升级前那些回合没记时间），
     * 累计输出除以「一次回复的耗时」会算出上万 tok/s。旧文件读不到时是 0，界面显示「—」。
     */
    val lastTurnModelMillis: Long = 0,
    val lastTurnCompletionTokens: Int = 0,
    /**
     * 本会话的滚动摘要（空串 = 没有摘要，请求视图发全量）。
     * 旧版本落盘里没有这几个 key，读到默认值；为默认值时也不写回，旧文件不会被撑大。
     */
    val contextSummary: String = "",
    /** 摘要覆盖到第几条消息（**user 消息边界**，从此下标起发原文；0 = 没有摘要）。 */
    val summaryUpTo: Int = 0,
    /** 最近一次摘要成功的时间戳（0 = 从没摘要过）。 */
    val lastSummaryAt: Long = 0L
)

/**
 * 会话落盘（M6）。
 *
 * 只用一个 JSON 文件整体覆盖写：会话数量是「人手工点出来的」，几十条量级，
 * 不需要数据库，也避免引入新依赖。写失败只记日志不抛，绝不能让存盘把对话搞崩。
 */
object SessionStore {

    private const val FILE_NAME = "sessions.json"
    private const val TAG = "session"

    private const val KEY_SESSIONS = "sessions"
    private const val KEY_ID = "id"
    private const val KEY_TITLE = "title"
    private const val KEY_UPDATED_AT = "updatedAt"
    private const val KEY_ARCHIVED = "archived"
    private const val KEY_PINNED = "pinned"
    private const val KEY_WORKSPACE_ID = "workspaceId"
    private const val KEY_PROMPT_TOKENS = "promptTokens"
    private const val KEY_COMPLETION_TOKENS = "completionTokens"
    private const val KEY_REQUESTS = "requests"
    private const val KEY_STEPS = "steps"
    private const val KEY_MODEL_MILLIS = "modelMillis"
    private const val KEY_TOOL_MILLIS = "toolMillis"
    private const val KEY_LAST_TURN_MODEL_MILLIS = "lastTurnModelMillis"
    private const val KEY_LAST_TURN_COMPLETION = "lastTurnCompletionTokens"
    private const val KEY_CONTEXT_SUMMARY = "contextSummary"
    private const val KEY_SUMMARY_UP_TO = "summaryUpTo"
    private const val KEY_LAST_SUMMARY_AT = "lastSummaryAt"
    private const val KEY_MESSAGES = "messages"
    private const val KEY_ROLE = "role"
    private const val KEY_CONTENT = "content"
    private const val KEY_REASONING = "reasoning"
    private const val KEY_TOOL_CALLS = "toolCalls"
    private const val KEY_TOOL_CALL_ID = "toolCallId"
    private const val KEY_TOOL_NAME = "toolName"
    private const val KEY_IMAGES = "images"
    private const val KEY_CREATED_AT = "createdAt"
    // 单条消息自己的用量（气泡里那颗「用量 X tok」）：和会话级的 promptTokens 不是一回事，
    // 键名故意分开，免得以后有人把它们当同一个东西读
    private const val KEY_MSG_PROMPT = "msgPromptTokens"
    private const val KEY_MSG_COMPLETION = "msgCompletionTokens"

    private const val MINUTE = 60_000L
    private const val HOUR = 60L * MINUTE
    private const val DAY = 24L * HOUR

    /** 上一次发出的 id，用来保证同一毫秒内连点两次「新会话」也不会撞 id。 */
    @Volatile
    private var lastIssuedId: Long = 0L

    /** 落盘位置：filesDir/appdata/sessions.json（首次读时由 [AppPaths] 从旧位置迁过来）。 */
    private fun storeFile(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    // ---- 对外 ----

    /** 读全部会话；文件不存在、内容为空、JSON 坏掉，一律返回空列表，绝不抛。 */
    @Synchronized
    fun load(context: Context): List<ChatSession> {
        val file = storeFile(context)
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) emptyList() else parse(text)
        } catch (e: Exception) {
            FileLog.log(TAG, "读取会话失败，从空列表开始：" + e)
            emptyList()
        }
    }

    /** 整体覆盖写；失败只记日志。先写 .tmp 再 rename，避免写到一半被杀留下坏文件。 */
    @Synchronized
    fun save(context: Context, sessions: List<ChatSession>) {
        try {
            val array = JSONArray()
            for (session in sessions) array.put(sessionToJson(session))
            val root = JSONObject()
            root.put(KEY_SESSIONS, array)
            val json = root.toString()

            val file = storeFile(context)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, FILE_NAME + ".tmp")
            temp.writeText(json, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                // rename 失败（个别文件系统）时退化成直接覆盖，保证内容落地
                file.writeText(json, Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: Exception) {
            FileLog.log(TAG, "保存会话失败：" + e)
        }
    }

    /** 新建一条空会话。id 用毫秒时间戳（同一毫秒内连点会自增，避免撞 id）。 */
    fun newSession(): ChatSession {
        val now = System.currentTimeMillis()
        val id = if (now > lastIssuedId) now else lastIssuedId + 1
        lastIssuedId = id
        return ChatSession(
            id = id.toString(),
            title = "",
            messages = emptyList(),
            updatedAt = now
        )
    }

    /**
     * 从 [base] 分叉出一条新会话：新 id、标题「分支 · 原标题」、沿用工作区，
     * 只带 [messages] 这一段历史；父会话原样不动。
     *
     * 摘要三件套必须清零：summaryUpTo 记的是父会话里的消息下标，照搬到被截断的分支上，
     * 请求视图会从错误的位置开始发原文。用量计数同样从 0 起算——分支是一个新会话，
     * 父会话已经付过的 token 不能在两条会话里各算一次。
     */
    fun forked(base: ChatSession, messages: List<ChatMessage>): ChatSession =
        newSession().copy(
            title = "分支 · " + base.title.ifBlank { "新会话" },
            messages = messages,
            workspaceId = base.workspaceId
        )

    /** 侧边栏用的相对时间：刚刚 / N 分钟前 / N 小时前 / MM-dd。 */
    fun relativeTime(updatedAt: Long): String {
        val diff = System.currentTimeMillis() - updatedAt
        return when {
            // 负数（时钟回拨）也走「刚刚」，不出现「-1 分钟前」
            diff < MINUTE -> "刚刚"
            diff < HOUR -> (diff / MINUTE).toString() + " 分钟前"
            diff < DAY -> (diff / HOUR).toString() + " 小时前"
            else -> SimpleDateFormat("MM-dd", Locale.US).format(Date(updatedAt))
        }
    }

    // ---- 序列化 ----

    private fun sessionToJson(session: ChatSession): JSONObject {
        val obj = JSONObject()
        obj.put(KEY_ID, session.id)
        obj.put(KEY_TITLE, session.title)
        obj.put(KEY_UPDATED_AT, session.updatedAt)
        // 没归档 / 没置顶就不写这个 key：旧版本 App 读到未知 key 也不会炸，文件也更小
        if (session.archived) obj.put(KEY_ARCHIVED, true)
        if (session.pinned) obj.put(KEY_PINNED, true)
        // 未分类（null）不写这个 key：读回来 has() 为 false 就是 null，文件也更小
        session.workspaceId?.takeIf { it.isNotEmpty() }?.let { obj.put(KEY_WORKSPACE_ID, it) }
        // 计数为 0 就不写：跟 archived 一样，只在真有数时落 key，旧文件不会因为升级被撑大
        if (session.promptTokens != 0) obj.put(KEY_PROMPT_TOKENS, session.promptTokens)
        if (session.completionTokens != 0) obj.put(KEY_COMPLETION_TOKENS, session.completionTokens)
        if (session.requests != 0) obj.put(KEY_REQUESTS, session.requests)
        if (session.steps != 0) obj.put(KEY_STEPS, session.steps)
        // 耗时同理：没跑过就一个字节都不多写
        if (session.modelMillis != 0L) obj.put(KEY_MODEL_MILLIS, session.modelMillis)
        if (session.toolMillis != 0L) obj.put(KEY_TOOL_MILLIS, session.toolMillis)
        if (session.lastTurnModelMillis != 0L) {
            obj.put(KEY_LAST_TURN_MODEL_MILLIS, session.lastTurnModelMillis)
        }
        if (session.lastTurnCompletionTokens != 0) {
            obj.put(KEY_LAST_TURN_COMPLETION, session.lastTurnCompletionTokens)
        }
        // 摘要三件套同上一批计数：默认值不落 key，旧文件不因升级被撑大
        if (session.contextSummary.isNotEmpty()) obj.put(KEY_CONTEXT_SUMMARY, session.contextSummary)
        if (session.summaryUpTo != 0) obj.put(KEY_SUMMARY_UP_TO, session.summaryUpTo)
        if (session.lastSummaryAt != 0L) obj.put(KEY_LAST_SUMMARY_AT, session.lastSummaryAt)
        val array = JSONArray()
        for (message in session.messages) array.put(messageToJson(message))
        obj.put(KEY_MESSAGES, array)
        return obj
    }

    /**
     * 可空字段（reasoning / toolCallId / toolName）为空时**不写这个 key**：
     * org.json 的 optString 遇到 JSON null 会返回字符串 "null"，干脆不写，读的时候用 has() 判。
     */
    private fun messageToJson(message: ChatMessage): JSONObject {
        val obj = JSONObject()
        obj.put(KEY_ROLE, message.role)
        obj.put(KEY_CONTENT, message.content)
        val reasoning = message.reasoningContent
        if (!reasoning.isNullOrEmpty()) obj.put(KEY_REASONING, reasoning)
        if (message.toolCalls.isNotEmpty()) {
            val calls = JSONArray()
            for (call in message.toolCalls) {
                val item = JSONObject()
                item.put(KEY_ID, call.id)
                item.put("name", call.name)
                item.put("arguments", call.arguments)
                calls.put(item)
            }
            obj.put(KEY_TOOL_CALLS, calls)
        }
        val callId = message.toolCallId
        if (!callId.isNullOrEmpty()) obj.put(KEY_TOOL_CALL_ID, callId)
        val callName = message.toolName
        if (!callName.isNullOrEmpty()) obj.put(KEY_TOOL_NAME, callName)
        // 图片只写工作区相对路径；imageDataUrls 是请求期临时数据，一律不落盘
        if (message.images.isNotEmpty()) {
            val images = JSONArray()
            for (path in message.images) images.put(path)
            obj.put(KEY_IMAGES, images)
        }
        // 只在有值时才写：老版本落盘里没有这两个键，读的时候 0 就是「没统计」
        if (message.promptTokens != 0) obj.put(KEY_MSG_PROMPT, message.promptTokens)
        if (message.completionTokens != 0) obj.put(KEY_MSG_COMPLETION, message.completionTokens)
        obj.put(KEY_CREATED_AT, message.createdAt)
        return obj
    }

    private fun parse(text: String): List<ChatSession> {
        val root = JSONObject(text)
        val array = root.optJSONArray(KEY_SESSIONS) ?: return emptyList()
        val sessions = ArrayList<ChatSession>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString(KEY_ID, "")
            // id 是会话的唯一标识，缺了这条就没法切换/删除，直接丢
            if (id.isEmpty()) continue
            val messages = ArrayList<ChatMessage>()
            val messageArray = obj.optJSONArray(KEY_MESSAGES)
            if (messageArray != null) {
                for (j in 0 until messageArray.length()) {
                    val item = messageArray.optJSONObject(j) ?: continue
                    messageFromJson(item)?.let { messages.add(it) }
                }
            }
            sessions.add(
                ChatSession(
                    id = id,
                    title = obj.optString(KEY_TITLE, ""),
                    messages = messages,
                    updatedAt = obj.optLong(KEY_UPDATED_AT, 0L),
                    // 旧文件没有 archived / pinned，optBoolean 的默认值兜住，不会崩
                    archived = obj.optBoolean(KEY_ARCHIVED, false),
                    pinned = obj.optBoolean(KEY_PINNED, false),
                    // 旧文件没有 workspaceId，没有这个 key 就是 null（未分类）
                    workspaceId = obj.stringOrNull(KEY_WORKSPACE_ID),
                    // 旧文件没有这几个计数，optInt 的默认值兜住，读不到就是 0
                    promptTokens = obj.optInt(KEY_PROMPT_TOKENS, 0),
                    completionTokens = obj.optInt(KEY_COMPLETION_TOKENS, 0),
                    requests = obj.optInt(KEY_REQUESTS, 0),
                    steps = obj.optInt(KEY_STEPS, 0),
                    // 旧文件没有耗时，optLong 的默认值兜住：读不到就是 0
                    modelMillis = obj.optLong(KEY_MODEL_MILLIS, 0L),
                    toolMillis = obj.optLong(KEY_TOOL_MILLIS, 0L),
                    lastTurnModelMillis = obj.optLong(KEY_LAST_TURN_MODEL_MILLIS, 0L),
                    lastTurnCompletionTokens = obj.optInt(KEY_LAST_TURN_COMPLETION, 0),
                    // 旧文件没有摘要三件套，optString/optInt/optLong 的默认值兜住：没有摘要就是空串 + 0
                    contextSummary = obj.optString(KEY_CONTEXT_SUMMARY, ""),
                    summaryUpTo = obj.optInt(KEY_SUMMARY_UP_TO, 0),
                    lastSummaryAt = obj.optLong(KEY_LAST_SUMMARY_AT, 0L)
                )
            )
        }
        return sessions
    }

    private fun messageFromJson(obj: JSONObject): ChatMessage? {
        val role = obj.optString(KEY_ROLE, "")
        if (role.isEmpty()) return null
        val calls = ArrayList<ToolCall>()
        val callArray = obj.optJSONArray(KEY_TOOL_CALLS)
        if (callArray != null) {
            for (i in 0 until callArray.length()) {
                val item = callArray.optJSONObject(i) ?: continue
                calls.add(
                    ToolCall(
                        id = item.optString(KEY_ID, ""),
                        name = item.optString("name", ""),
                        arguments = item.optString("arguments", "")
                    )
                )
            }
        }
        return ChatMessage(
            role = role,
            content = obj.optString(KEY_CONTENT, ""),
            reasoningContent = obj.stringOrNull(KEY_REASONING),
            toolCalls = calls,
            toolCallId = obj.stringOrNull(KEY_TOOL_CALL_ID),
            toolName = obj.stringOrNull(KEY_TOOL_NAME),
            // 旧版本落盘里没有 images，stringList 返回空列表，旧会话照常打开
            images = obj.stringList(KEY_IMAGES),
            promptTokens = obj.optInt(KEY_MSG_PROMPT, 0),
            completionTokens = obj.optInt(KEY_MSG_COMPLETION, 0),
            createdAt = obj.optLong(KEY_CREATED_AT, System.currentTimeMillis())
        )
    }

    /**
     * 读字符串数组：key 不存在（旧文件没有图片字段）/ 不是数组，一律当空列表。
     * 元素做跟 [stringOrNull] 一样的过滤——空串和 "null"（org.json 会把 JSON null 读成这个字符串）都丢掉。
     */
    private fun JSONObject.stringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        val result = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i, "")
            if (value.isNotEmpty() && value != "null") result.add(value)
        }
        return result
    }

    /** 读可空字符串：key 不存在 / 是 JSON null / 空串，都当 null（顺便防住 "null" 这个坑）。 */
    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key, "")
        return if (value.isEmpty() || value == "null") null else value
    }
}
