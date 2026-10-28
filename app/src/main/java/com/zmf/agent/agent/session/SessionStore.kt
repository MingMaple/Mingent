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
     * 提示词缓存的累计命中 / 未命中输入 tokens（服务端 usage 给的）。
     *
     * 两个都为 0 表示「服务商没返回这项」或「本会话确实没缓存」，界面按「—」显示而不是 0%：
     * 编一个 0% 出来比不显示更误导。
     */
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    /**
     * 首 token 等待的累计毫秒与次数（TTFT 平均用），以及纯生成时长与对应输出 tokens（TPS 用）。
     * 旧文件读不到时全是 0，界面显示「—」。
     */
    val ttftMillis: Long = 0,
    val ttftCount: Int = 0,
    val genMillis: Long = 0,
    val genTokens: Int = 0,
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

    /**
     * 崩在工具执行中途时补进去的工具结果。
     *
     * 措辞有意写成「先核实再决定」：这条消息的唯一目的是**防止重复执行有副作用的操作**，
     * 所以不能让模型读成「失败了，重试一次」。
     */
    private const val DANGLING_TOOL_RESULT =
        "工具结果未知：App 在执行这个工具时退出了，没有拿到结果。" +
            "先核实它是否已经生效（文件是否已改、命令是否有副作用），再决定要不要重试；不要直接重跑有副作用的操作。"

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
    private const val KEY_CACHE_HIT_TOKENS = "cacheHitTokens"
    private const val KEY_CACHE_MISS_TOKENS = "cacheMissTokens"
    private const val KEY_TTFT_MILLIS = "ttftMillis"
    private const val KEY_TTFT_COUNT = "ttftCount"
    private const val KEY_GEN_MILLIS = "genMillis"
    private const val KEY_GEN_TOKENS = "genTokens"
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
    // 本轮用量面板要的三项：缓存命中量、服务端是否报过、当时用的「提供方/模型」
    private const val KEY_MSG_CACHED = "msgCachedTokens"
    private const val KEY_MSG_CACHE_REPORTED = "msgCacheReported"
    private const val KEY_MSG_USAGE_MODEL = "msgUsageModel"
    // 换别家模型也要记全的三项：未缓存输入、缓存写入、推理 tokens（后两项「没报」时不落 key）
    private const val KEY_MSG_UNCACHED = "msgUncachedInputTokens"
    private const val KEY_MSG_CACHE_WRITE = "msgCacheWriteTokens"
    private const val KEY_MSG_REASONING_TOKENS = "msgReasoningTokens"
    // 这条 user 消息发出时附带的运行时快照：不渲染，只为了请求前缀逐字节可复现（见 ChatMessage 注释）
    private const val KEY_MSG_SNAPSHOT = "runtimeSnapshot"
    // 这条 user 消息是不是 App 代发的：高危确认只认真实用户输入（见 ChatMessage.programmatic）
    private const val KEY_MSG_PROGRAMMATIC = "programmatic"

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

    /**
     * 从盘上真的删掉一条会话。
     *
     * 只从内存列表里移掉是不够的：[saveMerged] 会把「磁盘有、内存没有」的会话当成别人新增的保留下来，
     * 于是刚删的会话在回到主页时又原样冒出来。
     */
    @Synchronized
    fun removeSession(context: Context, id: String) {
        val all = load(context)
        val kept = all.filterNot { it.id == id }
        if (kept.size != all.size) save(context, kept)
    }

    /**
     * 往指定会话尾部追加消息（定时任务用）；会话已不在返回 false。
     *
     * 与 [saveMerged] 的区别：这里只碰目标那一条，不参与整表合并，
     * 后台追加的是「新消息」，不存在与界面抢同一份快照的问题。
     */
    @Synchronized
    fun appendMessages(context: Context, sessionId: String, added: List<ChatMessage>): Boolean {
        val all = load(context)
        val index = all.indexOfFirst { it.id == sessionId }
        if (index < 0) return false
        val old = all[index]
        val merged = all.toMutableList()
        merged[index] = old.copy(
            messages = old.messages + added,
            updatedAt = System.currentTimeMillis()
        )
        save(context, merged)
        return true
    }

    /**
     * 与磁盘合并后落盘：**只增不丢**。
     *
     * 会话是整份 JSON 覆盖写，界面与后台（定时任务）各持一份内存快照，谁后写谁抹掉对方，
     * 实测后果：定时任务的追问被界面写回旧快照盖掉，迭代计数从「四」退回「一」，
     * 新建/删除别的会话也会连累目标会话。
     *
     * 合并规则：
     * 1. 磁盘有、内存没有 → 保留磁盘（后台新建的会话不能被界面抹掉）；
     * 2. 一边是另一边的**前缀** → 取更长的那份（后追加的消息不丢）；
     * 3. 两边都改过（分叉） → 取消息多的，一样多取 updatedAt 新的。
     */
    @Synchronized
    fun saveMerged(context: Context, mine: List<ChatSession>) {
        val disk = load(context).associateBy { it.id }
        val result = LinkedHashMap<String, ChatSession>()
        for (session in mine) {
            val other = disk[session.id]
            result[session.id] = if (other == null) session else pickLonger(session, other)
        }
        for ((id, session) in disk) {
            if (!result.containsKey(id)) result[id] = session
        }
        save(context, result.values.toList())
    }

    /** 两条同 id 会话取哪条：[a] 是内存版，[b] 是磁盘版。 */
    private fun pickLonger(a: ChatSession, b: ChatSession): ChatSession {
        val winner = when {
            b.messages.size > a.messages.size && isPrefix(a.messages, b.messages) -> b
            a.messages.size > b.messages.size && isPrefix(b.messages, a.messages) -> a
            a.updatedAt >= b.updatedAt -> a
            else -> b
        }
        if (winner !== a && winner !== b) return winner
        // 标题 / 工作区这类元信息谁有值用谁的：别在合并时把后台起的标题洗成空
        val other = if (winner === a) b else a
        return winner.copy(
            title = winner.title.ifBlank { other.title },
            workspaceId = winner.workspaceId ?: other.workspaceId
        )
    }

    /** [shorter] 是不是 [longer] 的开头那段（逐条 == 比较；ChatMessage 是 data class）。 */
    private fun isPrefix(shorter: List<ChatMessage>, longer: List<ChatMessage>): Boolean {
        if (shorter.size > longer.size) return false
        for (i in shorter.indices) if (shorter[i] != longer[i]) return false
        return true
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
     * 请求视图会从错误的位置开始发原文。用量计数同样从 0 起算：分支是一个新会话，
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
        // 缓存与速度的四个累计值同样「有数才写」：没用过缓存的会话不该多出六个 key
        if (session.cacheHitTokens != 0) obj.put(KEY_CACHE_HIT_TOKENS, session.cacheHitTokens)
        if (session.cacheMissTokens != 0) obj.put(KEY_CACHE_MISS_TOKENS, session.cacheMissTokens)
        if (session.ttftMillis != 0L) obj.put(KEY_TTFT_MILLIS, session.ttftMillis)
        if (session.ttftCount != 0) obj.put(KEY_TTFT_COUNT, session.ttftCount)
        if (session.genMillis != 0L) obj.put(KEY_GEN_MILLIS, session.genMillis)
        if (session.genTokens != 0) obj.put(KEY_GEN_TOKENS, session.genTokens)
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
        // 缓存三项同样「有值才写」：老会话读回来是 0 / false / 空串，面板按「没有这项数据」显示
        if (message.cachedTokens != 0) obj.put(KEY_MSG_CACHED, message.cachedTokens)
        if (message.usageCacheReported) obj.put(KEY_MSG_CACHE_REPORTED, true)
        if (message.uncachedInputTokens != 0) obj.put(KEY_MSG_UNCACHED, message.uncachedInputTokens)
        message.cacheWriteTokens?.let { obj.put(KEY_MSG_CACHE_WRITE, it) }
        message.reasoningTokens?.let { obj.put(KEY_MSG_REASONING_TOKENS, it) }
        if (message.usageModel.isNotEmpty()) obj.put(KEY_MSG_USAGE_MODEL, message.usageModel)
        if (message.runtimeSnapshot.isNotEmpty()) obj.put(KEY_MSG_SNAPSHOT, message.runtimeSnapshot)
        // 只写 true：老版本没有这个键，读回来 false 就是「用户亲手输入」
        if (message.programmatic) obj.put(KEY_MSG_PROGRAMMATIC, true)
        obj.put(KEY_CREATED_AT, message.createdAt)
        return obj
    }

    /**
     * 补上「已派发但没有结果」的工具结果。
     *
     * 为什么必须有：我们的落盘检查点会在**执行有副作用的工具之前**写一份会话，所以崩在工具里时
     * 盘上就有「assistant 带 tool_calls、后面没有对应 tool 消息」的记录。不补的话：
     * ① 界面会留一个没有结果的工具气泡；② 请求视图的 [sanitized] 会把**整轮**丢掉，
     * 模型于是完全不知道这条调用发生过：它可能以为没做（重复做一次）或以为做完了（不再验证）。
     *
     * 补的文案的语义是**不自动重试**：要求先核实副作用是否已经发生。
     * 只在读盘时补（不写回磁盘），所以每次启动看到的是同一条历史，不会越补越多。
     */
    private fun repairDanglingToolCalls(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.none { it.role == "assistant" && it.toolCalls.isNotEmpty() }) return messages
        var changed = false
        val out = ArrayList<ChatMessage>(messages.size + 2)
        var i = 0
        while (i < messages.size) {
            val message = messages[i]
            out.add(message)
            i++
            if (message.role != "assistant" || message.toolCalls.isEmpty()) continue
            // 紧随其后的 tool 结果（与请求视图的 sanitized() 同口径：只认紧邻的一段）
            val got = HashSet<String>()
            while (i < messages.size && messages[i].role == "tool") {
                val result = messages[i]
                result.toolCallId?.let { got.add(it) }
                out.add(result)
                i++
            }
            for (call in message.toolCalls) {
                if (got.contains(call.id)) continue
                changed = true
                out.add(
                    ChatMessage(
                        role = "tool",
                        content = DANGLING_TOOL_RESULT,
                        toolCallId = call.id,
                        toolName = call.name
                    )
                )
            }
        }
        if (!changed) return messages
        FileLog.log(TAG, "读盘补齐了未返回结果的工具调用（结果未知，不自动重试）")
        return out
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
            // 读盘时修一次「有调用没结果」：进程在工具执行中途被杀就会留下这种记录（见 repairDanglingToolCalls）
            val repaired = repairDanglingToolCalls(messages)
            sessions.add(
                ChatSession(
                    id = id,
                    title = obj.optString(KEY_TITLE, ""),
                    messages = repaired,
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
                    // 缓存与速度：旧文件没有这些 key，读不到就是 0（= 没数据，界面显示「—」）
                    cacheHitTokens = obj.optInt(KEY_CACHE_HIT_TOKENS, 0),
                    cacheMissTokens = obj.optInt(KEY_CACHE_MISS_TOKENS, 0),
                    ttftMillis = obj.optLong(KEY_TTFT_MILLIS, 0L),
                    ttftCount = obj.optInt(KEY_TTFT_COUNT, 0),
                    genMillis = obj.optLong(KEY_GEN_MILLIS, 0L),
                    genTokens = obj.optInt(KEY_GEN_TOKENS, 0),
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
            cachedTokens = obj.optInt(KEY_MSG_CACHED, 0),
            usageCacheReported = obj.optBoolean(KEY_MSG_CACHE_REPORTED, false),
            uncachedInputTokens = obj.optInt(KEY_MSG_UNCACHED, 0),
            // 「没报」用「键不存在」表达：旧会话读回来是 null，面板据此不显示那一行
            cacheWriteTokens = if (obj.has(KEY_MSG_CACHE_WRITE)) obj.optInt(KEY_MSG_CACHE_WRITE, 0) else null,
            reasoningTokens = if (obj.has(KEY_MSG_REASONING_TOKENS)) {
                obj.optInt(KEY_MSG_REASONING_TOKENS, 0)
            } else {
                null
            },
            usageModel = obj.optString(KEY_MSG_USAGE_MODEL, ""),
            // 老会话没有这个键：读不到就是空串，请求里不拼，行为与改造前一致
            runtimeSnapshot = obj.optString(KEY_MSG_SNAPSHOT, ""),
            // 老会话没有这个键：false = 用户亲手输入
            programmatic = obj.optBoolean(KEY_MSG_PROGRAMMATIC, false),
            createdAt = obj.optLong(KEY_CREATED_AT, System.currentTimeMillis())
        )
    }

    /**
     * 读字符串数组：key 不存在（旧文件没有图片字段）/ 不是数组，一律当空列表。
     * 元素做跟 [stringOrNull] 一样的过滤：空串和 "null"（org.json 会把 JSON null 读成这个字符串）都丢掉。
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
