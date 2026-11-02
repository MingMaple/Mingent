package com.zmf.agent.core.store

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
 * title 为空表示用户尚未发言，界面显示为「新会话」，首次发送消息时自动取前 20 个字符作为标题。
 */
data class ChatSession(
    val id: String,
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 已归档会话在侧边栏折叠，不删除数据；旧版本落盘无此键，默认 false。 */
    val archived: Boolean = false,
    /** 置顶会话在侧边栏排在最前，不被归档折叠遮蔽；旧版本落盘无此键，默认 false。 */
    val pinned: Boolean = false,
    /**
     * 所属工作区 id；null 表示「未分类」（不属于任何工作区的会话）。
     * 旧版本落盘无此键，读取为 null，历史会话均归入未分类。
     */
    val workspaceId: String? = null,
    /**
     * 本会话累计用量（切换会话不丢失，返回后可继续累计）。
     * 旧版本落盘无这些键，读取为 0；值为 0 时不写回文件，旧文件不会被立即增大。
     */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** 收到 usage 的次数（一次成功的模型请求算一次）。 */
    val requests: Int = 0,
    /** 该会话历史累计步数（每次回复结束时把本次步数加进来一次）。 */
    val steps: Int = 0,
    /**
     * 本会话累计的模型耗时 / 工具耗时（毫秒），每次回复结束时各累加一次。
     * 旧版本落盘无这两个键，读取为 0；值为 0 时不写回，旧文件不会被增大。
     */
    val modelMillis: Long = 0,
    val toolMillis: Long = 0,
    /**
     * **上一轮**回复的模型耗时与输出 tokens（单独记一份）。
     *
     * 不能使用累计值计算速度：旧会话的累计耗时并不完整（早期回合未记录时间），
     * 累计输出除以单次回复耗时会得到上万 tok/s 的结果。旧文件读取为 0，界面显示「—」。
     */
    val lastTurnModelMillis: Long = 0,
    val lastTurnCompletionTokens: Int = 0,
    /**
     * 提示词缓存的累计命中 / 未命中输入 tokens（服务端 usage 给的）。
     *
     * 两项均为 0 表示服务商未返回该项，或本会话确实无缓存；界面显示「—」而非 0%，
     * 虚构 0% 比不显示更具误导性。
     */
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    /**
     * 首 token 等待的累计毫秒与次数（用于计算 TTFT 平均值），以及纯生成时长与对应输出 tokens（用于计算 TPS）。
     * 旧文件读取时均为 0，界面显示「—」。
     */
    val ttftMillis: Long = 0,
    val ttftCount: Int = 0,
    val genMillis: Long = 0,
    val genTokens: Int = 0,
    /**
     * 本会话的滚动摘要（空串表示无摘要，请求视图发送全量内容）。
     * 旧版本落盘无这些键，读取默认值；为默认值时也不写回，旧文件不会被增大。
     */
    val contextSummary: String = "",
    /** 摘要覆盖到第几条消息（**user 消息边界**，从此下标起发原文；0 = 没有摘要）。 */
    val summaryUpTo: Int = 0,
    /** 最近一次摘要成功的时间戳（0 = 从没摘要过）。 */
    val lastSummaryAt: Long = 0L,
    /**
     * 分叉时从父会话继承下来的用量基线；普通会话是全 0 的空值。
     *
     * 分叉即克隆当前会话：状态行的轮数、步数、命中率与右下角用量胶囊均按继承后的累计值显示；
     * 上下文洞察页仅统计自分支起的真实用量，需依靠该基线做减法（见 [InheritedUsage]）。
     */
    val inheritedUsage: InheritedUsage = InheritedUsage(),
    /**
     * 本会话的累计计费构成（每次请求按估算占比折算后累加）。
     *
     * 旧文件无此键时为空值（界面显示「—」，不回填历史）。
     */
    val composition: SessionComposition = SessionComposition(),
    /** 生成阶段细分：模型思考 / 正文 / 工具参数三段的累计毫秒（旧文件读取为 0）。 */
    val reasoningMillis: Long = 0,
    val textMillis: Long = 0,
    val toolArgMillis: Long = 0
)

/**
 * 会话累计的计费构成：八段（七段上下文 + 输出）。
 *
 * 口径：每次模型请求按「当前请求视图的估算占比 × 真实 prompt tokens」折算，
 * 输出直接取 completionTokens；所以八段合计 = 会话真实输入 + 输出。
 */
data class SessionComposition(
    val system: Int = 0,
    val skills: Int = 0,
    val tools: Int = 0,
    val user: Int = 0,
    val inject: Int = 0,
    val assistant: Int = 0,
    val toolResults: Int = 0,
    val output: Int = 0
) {
    /** 全 0 表示该会话无构成明细（旧会话或尚未发起请求）。 */
    val isEmpty: Boolean
        get() = system == 0 && skills == 0 && tools == 0 && user == 0 &&
            inject == 0 && assistant == 0 && toolResults == 0 && output == 0

    /** 八段合计（计费总额）。 */
    val total: Int
        get() = system + skills + tools + user + inject + assistant + toolResults + output

    operator fun plus(other: SessionComposition): SessionComposition = SessionComposition(
        system = system + other.system,
        skills = skills + other.skills,
        tools = tools + other.tools,
        user = user + other.user,
        inject = inject + other.inject,
        assistant = assistant + other.assistant,
        toolResults = toolResults + other.toolResults,
        output = output + other.output
    )
}

/**
 * 分叉时继承的用量快照。
 *
 * 未采用事后查询父会话的方式：父会话在分叉后仍会继续增长，事后无法计算克隆时的数值，
 * 因此只能在创建分支时保存一份计数快照。
 *
 * [messages] 是克隆过来的消息条数（分叉边界）：洞察页的提示词趋势据此排除继承部分。
 */
data class InheritedUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val requests: Int = 0,
    val steps: Int = 0,
    val modelMillis: Long = 0,
    val toolMillis: Long = 0,
    val lastTurnModelMillis: Long = 0,
    val lastTurnCompletionTokens: Int = 0,
    /** 输出侧细分随克隆一并继承：洞察页需扣除，状态行与浮窗保持克隆后的显示效果。 */
    val ttftMillis: Long = 0,
    val ttftCount: Int = 0,
    val genMillis: Long = 0,
    val genTokens: Int = 0,
    val reasoningMillis: Long = 0,
    val textMillis: Long = 0,
    val toolArgMillis: Long = 0,
    val messages: Int = 0
) {
    /** 全 0 表示并非从其他会话分叉而来：落盘时不写入整个对象。 */
    val isEmpty: Boolean
        get() = promptTokens == 0 && completionTokens == 0 && requests == 0 && steps == 0 &&
            modelMillis == 0L && toolMillis == 0L && lastTurnModelMillis == 0L &&
            lastTurnCompletionTokens == 0 && ttftMillis == 0L && ttftCount == 0 &&
            genMillis == 0L && genTokens == 0 && reasoningMillis == 0L &&
            textMillis == 0L && toolArgMillis == 0L && messages == 0
}

/**
 * 会话落盘。
 *
 * 仅使用一个 JSON 文件整体覆盖写：会话数量由用户手动创建，量级为几十条，
 * 无需数据库，也避免引入新依赖。写失败只记日志，不抛出异常，存盘不得导致对话中断。
 */
object SessionStore {

    private const val FILE_NAME = "sessions.json"
    private const val TAG = "session"

    /**
     * 工具执行中途崩溃时补入的工具结果。
     *
     * 措辞有意采用「先核实再决定」：该消息的唯一目的是**防止重复执行有副作用的操作**，
     * 不得使模型理解为「失败后重试一次」。
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
    private const val KEY_COMPOSITION = "composition"
    private const val KEY_REASONING_MILLIS = "reasoningMillis"
    private const val KEY_TEXT_MILLIS = "textMillis"
    private const val KEY_TOOL_ARG_MILLIS = "toolArgMillis"
    private const val KEY_INHERITED_USAGE = "inheritedUsage"
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
    // 单条消息自身的用量（气泡中的「用量 X tok」）：与会话级 promptTokens 含义不同，
    // 键名有意区分，避免后续将其按同一字段读取
    private const val KEY_MSG_PROMPT = "msgPromptTokens"
    private const val KEY_MSG_COMPLETION = "msgCompletionTokens"
    // 本轮用量面板所需的三项：缓存命中量、服务端是否上报、当次使用的「提供方/模型」
    private const val KEY_MSG_CACHED = "msgCachedTokens"
    private const val KEY_MSG_CACHE_REPORTED = "msgCacheReported"
    private const val KEY_MSG_USAGE_MODEL = "msgUsageModel"
    // 切换其他模型时也需完整记录的三项：未缓存输入、缓存写入、推理 tokens（后两项未上报时不写入键）
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

    /** 上一次发出的 id，确保同一毫秒内连续两次新建会话不会产生重复 id。 */
    @Volatile
    private var lastIssuedId: Long = 0L

    /** 落盘位置：filesDir/appdata/sessions.json（首次读时由 [AppPaths] 从旧位置迁过来）。 */
    private fun storeFile(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    // ---- 对外 ----

    /** 读取全部会话；文件不存在、内容为空或 JSON 损坏时一律返回空列表，不抛出异常。 */
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

    /** 整体覆盖写；失败只记日志。先写 .tmp 再 rename，避免写入中断后残留损坏文件。 */
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
                // rename 失败（个别文件系统）时退化为直接覆盖，确保内容写入
                file.writeText(json, Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: Exception) {
            FileLog.log(TAG, "保存会话失败：" + e)
        }
    }

    /**
     * 从磁盘真正删除一条会话。
     *
     * 仅从内存列表移除不够：[saveMerged] 会将「磁盘存在、内存不存在」的会话视为其他来源新增而保留，
     * 导致已删除的会话在返回主页后重新出现。
     */
    @Synchronized
    fun removeSession(context: Context, id: String) {
        val all = load(context)
        val kept = all.filterNot { it.id == id }
        if (kept.size != all.size) save(context, kept)
    }

    /**
     * 向指定会话末尾追加消息（定时任务使用）；会话不存在时返回 false。
     *
     * 与 [saveMerged] 的区别：此处仅修改目标会话，不参与整表合并；
     * 后台追加的是新消息，不存在与界面争用同一份快照的问题。
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
     * 会话采用整份 JSON 覆盖写，界面与后台（定时任务）各自持有内存快照，后写入者会覆盖对方数据；
     * 实际影响包括：定时任务的追问被界面写回的旧快照覆盖，迭代计数发生回退，
     * 新建或删除其他会话也会影响目标会话。
     *
     * 合并规则：
     * 1. 磁盘存在、内存不存在 → 保留磁盘数据（后台新建的会话不得被界面覆盖）；
     * 2. 一方为另一方的**前缀** → 取较长者（后追加的消息不丢失）；
     * 3. 双方均有修改（分叉） → 取消息较多者，数量相同时取 updatedAt 较新者。
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

    /** 同 id 两条会话的选取规则：[a] 为内存版本，[b] 为磁盘版本。 */
    private fun pickLonger(a: ChatSession, b: ChatSession): ChatSession {
        val winner = when {
            b.messages.size > a.messages.size && isPrefix(a.messages, b.messages) -> b
            a.messages.size > b.messages.size && isPrefix(b.messages, a.messages) -> a
            a.updatedAt >= b.updatedAt -> a
            else -> b
        }
        if (winner !== a && winner !== b) return winner
        // 标题、工作区等元信息取有值的一方：避免合并时将后台生成的标题置为空值
        val other = if (winner === a) b else a
        return winner.copy(
            title = winner.title.ifBlank { other.title },
            workspaceId = winner.workspaceId ?: other.workspaceId
        )
    }

    /** [shorter] 是否为 [longer] 的前缀（逐条 == 比较；ChatMessage 为 data class）。 */
    private fun isPrefix(shorter: List<ChatMessage>, longer: List<ChatMessage>): Boolean {
        if (shorter.size > longer.size) return false
        for (i in shorter.indices) if (shorter[i] != longer[i]) return false
        return true
    }

    /** 新建一条空会话。id 使用毫秒时间戳（同一毫秒内连续创建时自增，避免重复）。 */
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
     * 从 [base] 分叉出一条新会话：使用新 id、标题为「分支 · 原标题」、沿用工作区，
     * 仅携带 [messages] 这一段历史；父会话保持不变。
     *
     * 分叉即克隆：历史一致，用量记录一并整份继承（状态行的轮数、步数、命中率与右下角用量胶囊
     * 均按克隆后的累计值显示）。继承的部分另存于 [InheritedUsage]，洞察页据此做减法，
     * 仅显示自分支起的真实用量。
     *
     * 摘要三项必须清零：summaryUpTo 记录的是父会话中的消息下标，若照搬到被截断的分支，
     * 请求视图会从错误位置开始发送原文。
     */
    fun forked(base: ChatSession, messages: List<ChatMessage>): ChatSession =
        newSession().copy(
            title = "分支 · " + base.title.ifBlank { "新会话" },
            messages = messages,
            workspaceId = base.workspaceId,
            promptTokens = base.promptTokens,
            completionTokens = base.completionTokens,
            requests = base.requests,
            steps = base.steps,
            modelMillis = base.modelMillis,
            toolMillis = base.toolMillis,
            lastTurnModelMillis = base.lastTurnModelMillis,
            lastTurnCompletionTokens = base.lastTurnCompletionTokens,
            cacheHitTokens = base.cacheHitTokens,
            cacheMissTokens = base.cacheMissTokens,
            ttftMillis = base.ttftMillis,
            ttftCount = base.ttftCount,
            genMillis = base.genMillis,
            genTokens = base.genTokens,
            inheritedUsage = InheritedUsage(
                promptTokens = base.promptTokens,
                completionTokens = base.completionTokens,
                requests = base.requests,
                steps = base.steps,
                modelMillis = base.modelMillis,
                toolMillis = base.toolMillis,
                lastTurnModelMillis = base.lastTurnModelMillis,
                lastTurnCompletionTokens = base.lastTurnCompletionTokens,
                ttftMillis = base.ttftMillis,
                ttftCount = base.ttftCount,
                genMillis = base.genMillis,
                genTokens = base.genTokens,
                reasoningMillis = base.reasoningMillis,
                textMillis = base.textMillis,
                toolArgMillis = base.toolArgMillis,
                messages = messages.size
            )
        )

    /** 侧边栏用的相对时间：刚刚 / N 分钟前 / N 小时前 / MM-dd。 */
    fun relativeTime(updatedAt: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - updatedAt
        return when {
            // 负数（时钟回拨）同样归入「刚刚」，避免出现「-1 分钟前」
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
        // 未归档、未置顶时不写入该键：旧版本 App 读取未知键不会出错，文件也更小
        if (session.archived) obj.put(KEY_ARCHIVED, true)
        if (session.pinned) obj.put(KEY_PINNED, true)
        // 未分类（null）时不写入该键：读取时 has() 为 false 即 null，文件也更小
        session.workspaceId?.takeIf { it.isNotEmpty() }?.let { obj.put(KEY_WORKSPACE_ID, it) }
        // 计数为 0 时不写入：与 archived 一致，仅在存在数值时写入键，旧文件不会因升级而增大
        if (session.promptTokens != 0) obj.put(KEY_PROMPT_TOKENS, session.promptTokens)
        if (session.completionTokens != 0) obj.put(KEY_COMPLETION_TOKENS, session.completionTokens)
        if (session.requests != 0) obj.put(KEY_REQUESTS, session.requests)
        if (session.steps != 0) obj.put(KEY_STEPS, session.steps)
        // 耗时同理：未执行时不额外写入任何字节
        if (session.modelMillis != 0L) obj.put(KEY_MODEL_MILLIS, session.modelMillis)
        if (session.toolMillis != 0L) obj.put(KEY_TOOL_MILLIS, session.toolMillis)
        if (session.lastTurnModelMillis != 0L) {
            obj.put(KEY_LAST_TURN_MODEL_MILLIS, session.lastTurnModelMillis)
        }
        if (session.lastTurnCompletionTokens != 0) {
            obj.put(KEY_LAST_TURN_COMPLETION, session.lastTurnCompletionTokens)
        }
        // 缓存与速度的累计值同样仅在存在数值时写入：未使用缓存的会话不应多出六个键
        if (session.cacheHitTokens != 0) obj.put(KEY_CACHE_HIT_TOKENS, session.cacheHitTokens)
        if (session.cacheMissTokens != 0) obj.put(KEY_CACHE_MISS_TOKENS, session.cacheMissTokens)
        if (session.ttftMillis != 0L) obj.put(KEY_TTFT_MILLIS, session.ttftMillis)
        if (session.ttftCount != 0) obj.put(KEY_TTFT_COUNT, session.ttftCount)
        if (session.genMillis != 0L) obj.put(KEY_GEN_MILLIS, session.genMillis)
        if (session.genTokens != 0) obj.put(KEY_GEN_TOKENS, session.genTokens)
        // 构成与生成细分同样仅在存在数值时写入，旧文件不会被增大
        if (!session.composition.isEmpty) obj.put(KEY_COMPOSITION, compositionToJson(session.composition))
        if (session.reasoningMillis != 0L) obj.put(KEY_REASONING_MILLIS, session.reasoningMillis)
        if (session.textMillis != 0L) obj.put(KEY_TEXT_MILLIS, session.textMillis)
        if (session.toolArgMillis != 0L) obj.put(KEY_TOOL_ARG_MILLIS, session.toolArgMillis)
        // 摘要三项与前述计数一致：默认值不写入键，旧文件不会因升级而增大
        if (session.contextSummary.isNotEmpty()) obj.put(KEY_CONTEXT_SUMMARY, session.contextSummary)
        if (session.summaryUpTo != 0) obj.put(KEY_SUMMARY_UP_TO, session.summaryUpTo)
        if (session.lastSummaryAt != 0L) obj.put(KEY_LAST_SUMMARY_AT, session.lastSummaryAt)
        // 分叉继承基线：仅在确实从其他会话分叉时写入（普通会话不额外写入任何字节）
        if (!session.inheritedUsage.isEmpty) {
            obj.put(KEY_INHERITED_USAGE, inheritedUsageToJson(session.inheritedUsage))
        }
        val array = JSONArray()
        for (message in session.messages) array.put(messageToJson(message))
        obj.put(KEY_MESSAGES, array)
        return obj
    }

    /** 分叉继承基线落盘：键名复用会话级的同名字段，读取时可直接对应。 */
    private fun inheritedUsageToJson(usage: InheritedUsage): JSONObject {
        val obj = JSONObject()
        obj.put(KEY_PROMPT_TOKENS, usage.promptTokens)
        obj.put(KEY_COMPLETION_TOKENS, usage.completionTokens)
        obj.put(KEY_REQUESTS, usage.requests)
        obj.put(KEY_STEPS, usage.steps)
        obj.put(KEY_MODEL_MILLIS, usage.modelMillis)
        obj.put(KEY_TOOL_MILLIS, usage.toolMillis)
        obj.put(KEY_LAST_TURN_MODEL_MILLIS, usage.lastTurnModelMillis)
        obj.put(KEY_LAST_TURN_COMPLETION, usage.lastTurnCompletionTokens)
        obj.put(KEY_TTFT_MILLIS, usage.ttftMillis)
        obj.put(KEY_TTFT_COUNT, usage.ttftCount)
        obj.put(KEY_GEN_MILLIS, usage.genMillis)
        obj.put(KEY_GEN_TOKENS, usage.genTokens)
        obj.put(KEY_REASONING_MILLIS, usage.reasoningMillis)
        obj.put(KEY_TEXT_MILLIS, usage.textMillis)
        obj.put(KEY_TOOL_ARG_MILLIS, usage.toolArgMillis)
        // 此处并非消息数组，而是克隆过来的消息条数（分叉边界）
        obj.put(KEY_MESSAGES, usage.messages)
        return obj
    }

    private fun inheritedUsageFromJson(obj: JSONObject): InheritedUsage = InheritedUsage(
        promptTokens = obj.optInt(KEY_PROMPT_TOKENS, 0),
        completionTokens = obj.optInt(KEY_COMPLETION_TOKENS, 0),
        requests = obj.optInt(KEY_REQUESTS, 0),
        steps = obj.optInt(KEY_STEPS, 0),
        modelMillis = obj.optLong(KEY_MODEL_MILLIS, 0L),
        toolMillis = obj.optLong(KEY_TOOL_MILLIS, 0L),
        lastTurnModelMillis = obj.optLong(KEY_LAST_TURN_MODEL_MILLIS, 0L),
        lastTurnCompletionTokens = obj.optInt(KEY_LAST_TURN_COMPLETION, 0),
        ttftMillis = obj.optLong(KEY_TTFT_MILLIS, 0L),
        ttftCount = obj.optInt(KEY_TTFT_COUNT, 0),
        genMillis = obj.optLong(KEY_GEN_MILLIS, 0L),
        genTokens = obj.optInt(KEY_GEN_TOKENS, 0),
        reasoningMillis = obj.optLong(KEY_REASONING_MILLIS, 0L),
        textMillis = obj.optLong(KEY_TEXT_MILLIS, 0L),
        toolArgMillis = obj.optLong(KEY_TOOL_ARG_MILLIS, 0L),
        messages = obj.optInt(KEY_MESSAGES, 0)
    )

    /** 会话构成落盘：嵌套一层，键名与 DSH 的八段一致。 */
    private fun compositionToJson(c: SessionComposition): JSONObject = JSONObject().apply {
        put("system", c.system)
        put("skills", c.skills)
        put("tools", c.tools)
        put("user", c.user)
        put("inject", c.inject)
        put("assistant", c.assistant)
        put("toolResults", c.toolResults)
        put("output", c.output)
    }

    private fun compositionFromJson(obj: JSONObject?): SessionComposition = SessionComposition(
        system = obj?.optInt("system", 0) ?: 0,
        skills = obj?.optInt("skills", 0) ?: 0,
        tools = obj?.optInt("tools", 0) ?: 0,
        user = obj?.optInt("user", 0) ?: 0,
        inject = obj?.optInt("inject", 0) ?: 0,
        assistant = obj?.optInt("assistant", 0) ?: 0,
        toolResults = obj?.optInt("toolResults", 0) ?: 0,
        output = obj?.optInt("output", 0) ?: 0
    )

    /**
     * 可空字段（reasoning / toolCallId / toolName）为空时**不写入该键**：
     * org.json 的 optString 遇到 JSON null 会返回字符串 "null"，因此不写入，读取时用 has() 判断。
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
        // 图片仅写入工作区相对路径；imageDataUrls 为请求期临时数据，一律不落盘
        if (message.images.isNotEmpty()) {
            val images = JSONArray()
            for (path in message.images) images.put(path)
            obj.put(KEY_IMAGES, images)
        }
        // 仅在存在数值时写入：旧版本落盘无这两个键，读取为 0 表示未统计
        if (message.promptTokens != 0) obj.put(KEY_MSG_PROMPT, message.promptTokens)
        if (message.completionTokens != 0) obj.put(KEY_MSG_COMPLETION, message.completionTokens)
        // 缓存三项同样仅在存在数值时写入：旧会话读取为 0 / false / 空串，面板按无此项数据显示
        if (message.cachedTokens != 0) obj.put(KEY_MSG_CACHED, message.cachedTokens)
        if (message.usageCacheReported) obj.put(KEY_MSG_CACHE_REPORTED, true)
        if (message.uncachedInputTokens != 0) obj.put(KEY_MSG_UNCACHED, message.uncachedInputTokens)
        message.cacheWriteTokens?.let { obj.put(KEY_MSG_CACHE_WRITE, it) }
        message.reasoningTokens?.let { obj.put(KEY_MSG_REASONING_TOKENS, it) }
        if (message.usageModel.isNotEmpty()) obj.put(KEY_MSG_USAGE_MODEL, message.usageModel)
        if (message.runtimeSnapshot.isNotEmpty()) obj.put(KEY_MSG_SNAPSHOT, message.runtimeSnapshot)
        // 仅写入 true：旧版本无此键，读取为 false 表示用户手动输入
        if (message.programmatic) obj.put(KEY_MSG_PROGRAMMATIC, true)
        obj.put(KEY_CREATED_AT, message.createdAt)
        return obj
    }

    /**
     * 补上「已派发但没有结果」的工具结果。
     *
     * 必要性：落盘检查点会在**执行有副作用的工具之前**写入一份会话，若进程在工具执行中终止，
     * 磁盘上会存在「assistant 含 tool_calls，但后续无对应 tool 消息」的记录。若不补齐：
     * ① 界面会残留无结果的工具气泡；② 请求视图的 [sanitized] 会丢弃**整轮**内容，
     * 模型无法得知该调用发生过，可能误判为未执行而重复执行，或误判为已完成而不作验证。
     *
     * 补齐文案的语义为**不自动重试**：要求先核实副作用是否已经发生。
     * 仅在读取磁盘时补齐（不写回磁盘），因此每次启动看到的历史一致，不会持续增加。
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
            // 紧随其后的 tool 结果（与请求视图的 sanitized() 口径一致：仅识别紧邻的一段）
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
            // id 为会话唯一标识，缺失时无法切换或删除，直接丢弃
            if (id.isEmpty()) continue
            val messages = ArrayList<ChatMessage>()
            val messageArray = obj.optJSONArray(KEY_MESSAGES)
            if (messageArray != null) {
                for (j in 0 until messageArray.length()) {
                    val item = messageArray.optJSONObject(j) ?: continue
                    messageFromJson(item)?.let { messages.add(it) }
                }
            }
            // 读盘时修复一次「有调用无结果」：进程在工具执行中终止会留下此类记录（见 repairDanglingToolCalls）
            val repaired = repairDanglingToolCalls(messages)
            sessions.add(
                ChatSession(
                    id = id,
                    title = obj.optString(KEY_TITLE, ""),
                    messages = repaired,
                    updatedAt = obj.optLong(KEY_UPDATED_AT, 0L),
                    // 旧文件无 archived / pinned，由 optBoolean 默认值处理，不会崩溃
                    archived = obj.optBoolean(KEY_ARCHIVED, false),
                    pinned = obj.optBoolean(KEY_PINNED, false),
                    // 旧文件无 workspaceId，无此键即为 null（未分类）
                    workspaceId = obj.stringOrNull(KEY_WORKSPACE_ID),
                    // 旧文件无这些计数，由 optInt 默认值处理，读取为 0
                    promptTokens = obj.optInt(KEY_PROMPT_TOKENS, 0),
                    completionTokens = obj.optInt(KEY_COMPLETION_TOKENS, 0),
                    requests = obj.optInt(KEY_REQUESTS, 0),
                    steps = obj.optInt(KEY_STEPS, 0),
                    // 旧文件无耗时，由 optLong 默认值处理：读取为 0
                    modelMillis = obj.optLong(KEY_MODEL_MILLIS, 0L),
                    toolMillis = obj.optLong(KEY_TOOL_MILLIS, 0L),
                    lastTurnModelMillis = obj.optLong(KEY_LAST_TURN_MODEL_MILLIS, 0L),
                    lastTurnCompletionTokens = obj.optInt(KEY_LAST_TURN_COMPLETION, 0),
                    // 缓存与速度：旧文件无这些键，读取为 0（表示无数据，界面显示「—」）
                    cacheHitTokens = obj.optInt(KEY_CACHE_HIT_TOKENS, 0),
                    cacheMissTokens = obj.optInt(KEY_CACHE_MISS_TOKENS, 0),
                    ttftMillis = obj.optLong(KEY_TTFT_MILLIS, 0L),
                    ttftCount = obj.optInt(KEY_TTFT_COUNT, 0),
                    genMillis = obj.optLong(KEY_GEN_MILLIS, 0L),
                    genTokens = obj.optInt(KEY_GEN_TOKENS, 0),
                    // 构成与生成细分：旧文件无这些键，读取为空或 0（界面显示「—」）
                    composition = compositionFromJson(obj.optJSONObject(KEY_COMPOSITION)),
                    reasoningMillis = obj.optLong(KEY_REASONING_MILLIS, 0L),
                    textMillis = obj.optLong(KEY_TEXT_MILLIS, 0L),
                    toolArgMillis = obj.optLong(KEY_TOOL_ARG_MILLIS, 0L),
                    // 旧文件无摘要三项，由 optString、optInt、optLong 默认值处理：无摘要时为空串与 0
                    contextSummary = obj.optString(KEY_CONTEXT_SUMMARY, ""),
                    summaryUpTo = obj.optInt(KEY_SUMMARY_UP_TO, 0),
                    lastSummaryAt = obj.optLong(KEY_LAST_SUMMARY_AT, 0L),
                    // 旧文件无分叉基线：读取为空值（普通会话，减 0 等同于不扣除）
                    inheritedUsage = obj.optJSONObject(KEY_INHERITED_USAGE)
                        ?.let { inheritedUsageFromJson(it) }
                        ?: InheritedUsage()
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
            // 旧版本落盘无 images，stringList 返回空列表，旧会话可正常打开
            images = obj.stringList(KEY_IMAGES),
            promptTokens = obj.optInt(KEY_MSG_PROMPT, 0),
            completionTokens = obj.optInt(KEY_MSG_COMPLETION, 0),
            cachedTokens = obj.optInt(KEY_MSG_CACHED, 0),
            usageCacheReported = obj.optBoolean(KEY_MSG_CACHE_REPORTED, false),
            uncachedInputTokens = obj.optInt(KEY_MSG_UNCACHED, 0),
            // 「未上报」以「键不存在」表示：旧会话读取为 null，面板据此不显示该行
            cacheWriteTokens = if (obj.has(KEY_MSG_CACHE_WRITE)) obj.optInt(KEY_MSG_CACHE_WRITE, 0) else null,
            reasoningTokens = if (obj.has(KEY_MSG_REASONING_TOKENS)) {
                obj.optInt(KEY_MSG_REASONING_TOKENS, 0)
            } else {
                null
            },
            usageModel = obj.optString(KEY_MSG_USAGE_MODEL, ""),
            // 旧会话无此键：读取为空串，请求中不拼接，行为与既有实现一致
            runtimeSnapshot = obj.optString(KEY_MSG_SNAPSHOT, ""),
            // 旧会话无此键：false 表示用户手动输入
            programmatic = obj.optBoolean(KEY_MSG_PROGRAMMATIC, false),
            createdAt = obj.optLong(KEY_CREATED_AT, System.currentTimeMillis())
        )
    }

    /**
     * 读取字符串数组：键不存在（旧文件无图片字段）或不是数组时一律返回空列表。
     * 元素按 [stringOrNull] 的规则过滤：空串与 "null"（org.json 会将 JSON null 读取为该字符串）均丢弃。
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

    /** 读取可空字符串：键不存在、为 JSON null 或空串时均返回 null（同时处理 "null" 特殊情况）。 */
    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key, "")
        return if (value.isEmpty() || value == "null") null else value
    }
}
