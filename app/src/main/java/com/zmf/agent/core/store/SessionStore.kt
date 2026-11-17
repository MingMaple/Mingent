package com.zmf.agent.core.store

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ORIGIN_USER
import com.zmf.agent.core.model.ProducedArtifact
import com.zmf.agent.core.model.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
     * 后台执行（定时任务）累计的工具调用次数。
     *
     * 交互式的工具次数可以直接数消息里的 tool 条，但定时任务只把最终问答写回会话，
     * 工具中间态不落消息；不单独记一份，会话卡片的「工具执行」就会「有耗时、0 次」。
     */
    val toolCalls: Int = 0,
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
    val toolArgMillis: Long = 0,
    /**
     * 运行根所在的存储单元 id。
     *
     * 会话不保存工作区的绝对路径：分组（[workspaceId]）与运行根彻底分开，
     * 因此把会话移到别的工作区仍可指向原目录，工作区改路径也不会留下两份路径。
     * 旧文件无此键，读取为空串，由迁移按所属工作区补齐；补不齐时按默认存储单元处理。
     */
    val storageId: String = "",
    /** 是否尚未发出首轮消息。旧数据缺省为 false。 */
    val blank: Boolean = false,
    /** 是否出现在会话列表。工作区为空时自动创建的空白会话为 true，用户手动新建的隐藏，首轮消息后恒为 true。 */
    val listed: Boolean = true,
    /** 创建时快照的权限档（`read-only` / `workspace-write` / `danger-full-access`）。空串表示尚未写入。 */
    val permissionPreset: String = "",
    /** 创建时快照的思考强度（`off` / `low` / `high` / `max`）。空串表示尚未写入；`off` 即不发送思考参数。 */
    val reasoningEffort: String = "",
    /**
     * P32：回合开合标记。回合开局置 true、收尾置 false；启动读到 true 说明上次进程被杀时
     * 回合未收尾，恢复路径据此在正文末尾补一条中断标记（DSH 的合成 closer 口径）。
     * 旧数据缺省为 false。
     */
    val turnOpen: Boolean = false,
    /**
     * X06：本会话累计轮数（每次追加过用户消息的回合收尾时 +1；迁移时从消息回算）。
     * 界面状态行统一读该字段，不再从消息列表派生历史累计；旧数据缺省为 0。
     */
    val turns: Int = 0,
    /**
     * X06：本会话累计的回合墙钟毫秒（每次回合收尾按「回合开始到收尾」累加一次）。旧数据缺省为 0。
     */
    val wallMs: Long = 0,
    /**
     * 消息条数快照：事件流的 meta 里随每次落盘刷新。列表页/索引场景没有正文列表，
     * 「是否已经说过话」这类判定改读该字段，不必解压会话文件。
     */
    val msgCount: Int = 0
) {
    /**
     * 是否已「唤醒」：至少产生过一条正文，或确实发生过一次请求。
     *
     * 不能只看 [blank]：旧版本落盘没有这个键，默认 false，于是历史里会留下
     * 「0 消息、0 轮」的空壳会话。这类会话与新建未发言一样，不该出现在洞察统计里，
     * 也不该提供会话菜单。用「有没有非空正文」判断对旧数据同样成立；
     * 懒加载/索引场景正文可能尚未折入内存，[msgCount] 与用量字段是等价判据；
     * 少数只保留用量不含消息的会话（如导入后裁剪）再用 requests / tokens 兜底。
     */
    val awakened: Boolean
        get() = !blank && (
            messages.any { it.content.isNotBlank() } ||
                msgCount > 0 ||
                requests > 0 || promptTokens > 0 || completionTokens > 0
            )
}

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
    val toolCalls: Int = 0,
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
            toolCalls == 0 && modelMillis == 0L && toolMillis == 0L && lastTurnModelMillis == 0L &&
            lastTurnCompletionTokens == 0 && ttftMillis == 0L && ttftCount == 0 &&
            genMillis == 0L && genTokens == 0 && reasoningMillis == 0L &&
            textMillis == 0L && toolArgMillis == 0L && messages == 0
}

/**
 * 一次执行要在会话记录上累加的用量与耗时。
 *
 * 定时任务在后台跑完一轮后，既要把问答写进消息，也要把这一轮的账记到会话上：
 * 会话级「Token 统计」「耗时统计」与状态行读的都是这些字段，缺一项就会显示成 0。
 */
data class SessionUsageDelta(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** 成功的模型请求次数。 */
    val requests: Int = 0,
    /** 本次回复的步数（跑过的最大轮次）。 */
    val steps: Int = 0,
    /** 本次回复实际执行的工具调用次数。 */
    val toolCalls: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val composition: SessionComposition = SessionComposition(),
    val modelMillis: Long = 0,
    val toolMillis: Long = 0,
    val lastTurnModelMillis: Long = 0,
    val lastTurnCompletionTokens: Int = 0,
    val ttftMillis: Long = 0,
    val ttftCount: Int = 0,
    val genMillis: Long = 0,
    val genTokens: Int = 0,
    val reasoningMillis: Long = 0,
    val textMillis: Long = 0,
    val toolArgMillis: Long = 0
) {
    /** 全 0 表示没有可累加的内容：此时按原样写回，不碰这些字段。 */
    val isEmpty: Boolean
        get() = promptTokens == 0 && completionTokens == 0 && requests == 0 && steps == 0 &&
            toolCalls == 0 && cacheHitTokens == 0 && cacheMissTokens == 0 && composition.isEmpty &&
            modelMillis == 0L && toolMillis == 0L && lastTurnModelMillis == 0L &&
            lastTurnCompletionTokens == 0 && ttftMillis == 0L && ttftCount == 0 &&
            genMillis == 0L && genTokens == 0 && reasoningMillis == 0L &&
            textMillis == 0L && toolArgMillis == 0L
}

/**
 * 把一份用量并入会话，返回新副本（不改消息与标题）。
 *
 * 逐字段相加而不是整体替换：会话上的累计值会同时被聊天页与定时任务推进，
 * 任何一方整份覆盖都会抹掉另一方的记录。
 */
fun ChatSession.foldUsage(delta: SessionUsageDelta): ChatSession {
    if (delta.isEmpty) return this
    return copy(
        promptTokens = promptTokens + delta.promptTokens,
        completionTokens = completionTokens + delta.completionTokens,
        requests = requests + delta.requests,
        steps = steps + delta.steps,
        toolCalls = toolCalls + delta.toolCalls,
        cacheHitTokens = cacheHitTokens + delta.cacheHitTokens,
        cacheMissTokens = cacheMissTokens + delta.cacheMissTokens,
        composition = composition + delta.composition,
        modelMillis = modelMillis + delta.modelMillis,
        toolMillis = toolMillis + delta.toolMillis,
        lastTurnModelMillis = delta.lastTurnModelMillis,
        lastTurnCompletionTokens = delta.lastTurnCompletionTokens,
        ttftMillis = ttftMillis + delta.ttftMillis,
        ttftCount = ttftCount + delta.ttftCount,
        genMillis = genMillis + delta.genMillis,
        genTokens = genTokens + delta.genTokens,
        reasoningMillis = reasoningMillis + delta.reasoningMillis,
        textMillis = textMillis + delta.textMillis,
        toolArgMillis = toolArgMillis + delta.toolArgMillis
    )
}

/**
 * 会话落盘（D1）：每会话一个 `<id>.jsonl` 事件流文件（仅追加、zstd 帧拼接、帧级校验），
 * 外加一份 `sessions.idx.json` 索引供列表页快读。
 *
 * **公开签名保持不变**（D1.3-H 的 28 个调用文件核对）：
 * `load` / `save` / `saveMerged` / `appendMessages` / `addUsage` / `removeSession` / `newSession` /
 * `forked` / `fallbackTitle` / `normalizeTitle` / `relativeTime` 全部保留，语义边界如下（与单测一致）：
 *
 * 1. `save(list)` ＝ 逐会话 upsert（新增文件 / 追加新消息 / meta 变更），**不删**；
 * 2. `saveMerged(list)` ＝ 同 `save`（旧整写时代的磁盘合并逻辑删除：每会话独立文件后无「覆盖他人」问题）；
 * 3. `removeSession(id)` ＝ 删文件＋删索引；
 * 4. `appendMessages` ＝ 追加 `msg`（无读改写整表）；
 * 5. `addUsage` ＝ meta patch；
 * 6. `save` 收到「比磁盘更短」的消息列表时**不截断**，记一行「忽略疑似过期快照」日志
 *    （`save` 的调用方从不做截断；截断只发生在界面回滚路径，走 [saveAuthoritative]）。
 *
 * 迁移未完成（`.migrated` 标记不存在）时本类整体降级为旧的单文件整写行为：
 * 迁移失败不丢数据，下次启动重试（D1.4）。
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

    /** 旧版失败正文的固定抬头（319 之前把失败说明写成了普通 assistant 正文）。 */
    private const val LEGACY_ERROR_PREFIX = "请求失败"

    /**
     * 旧失败正文的长度上限：超过视为用户/模型的真实内容，不当错误节点处理。
     * 真正的失败说明都是短句；设上限是为了避免把「用户在问请求失败怎么排查」这类正文吞掉。
     */
    private const val LEGACY_ERROR_MAX_CHARS = 2000

    private const val KEY_SESSIONS = "sessions"
    private const val KEY_ID = "id"
    private const val KEY_TITLE = "title"
    private const val KEY_UPDATED_AT = "updatedAt"
    private const val KEY_ARCHIVED = "archived"
    private const val KEY_PINNED = "pinned"
    private const val KEY_WORKSPACE_ID = "workspaceId"
    private const val KEY_STORAGE_ID = "storageId"
    private const val KEY_BLANK = "blank"
    private const val KEY_LISTED = "listed"
    private const val KEY_PERMISSION = "permissionPreset"
    private const val KEY_EFFORT = "reasoningEffort"
    private const val KEY_PROMPT_TOKENS = "promptTokens"
    private const val KEY_COMPLETION_TOKENS = "completionTokens"
    private const val KEY_REQUESTS = "requests"
    private const val KEY_STEPS = "steps"
    private const val KEY_TOOL_CALL_COUNT = "toolCallCount"
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
    // 工具结果成败：产物卡据此判断文件是否真的写入成功，不依赖回执文案
    private const val KEY_TOOL_OK = "toolOk"
    private const val KEY_IMAGES = "images"
    // 本回合产物：图片、视频、文档等；与 images 分开保存，来源与类别随之落盘
    private const val KEY_PRODUCED = "produced"
    private const val KEY_PRODUCED_PATH = "path"
    private const val KEY_PRODUCED_KIND = "kind"
    private const val KEY_PRODUCED_SOURCE = "source"
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
    // 唤醒来源：用户 / 定时任务；只影响后台统计口径（见 ChatMessage.origin）
    private const val KEY_MSG_ORIGIN = "origin"
    // 被用户中止的 assistant 半截内容（见 ChatMessage.interrupted）：旧文件无此键，读取为 false
    private const val KEY_MSG_INTERRUPTED = "interrupted"
    // 中止后补写的合成工具结果（见 ChatMessage.toolAborted）：旧文件无此键，读取为 false
    private const val KEY_MSG_TOOL_ABORTED = "toolAborted"
    // 回合终态失败（见 ChatMessage.errorText / errorCode）：旧文件无这些键，读取为 null
    private const val KEY_MSG_ERROR = "errorText"
    private const val KEY_MSG_ERROR_CODE = "errorCode"
    // P32 / X06 / D1 索引所需的新字段：旧文件无这些键，读取为缺省值
    private const val KEY_TURN_OPEN = "turnOpen"
    private const val KEY_TURNS = "turns"
    private const val KEY_WALL_MS = "wallMs"
    private const val KEY_MSG_COUNT = "msgCount"

    /**
     * 缺省值表：上述键等于缺省值时不会写入 JSON（旧文件不因升级增大）。
     * 差分补丁必须补出缺省值再比较，否则「布尔翻回默认」「计数归零」
     * 这类变化永远不会落盘（历史问题：新建会话发送首条消息后 blank/listed 仍是旧值）。
     * 只列出本类自己管理的键；未来版本新增而本版本不认识的键不在此表，保持原样。
     */
    private val DEFAULT_FIELDS: Map<String, Any> = mapOf(
        KEY_ARCHIVED to false,
        KEY_PINNED to false,
        KEY_WORKSPACE_ID to JSONObject.NULL,
        KEY_STORAGE_ID to "",
        KEY_BLANK to false,
        KEY_LISTED to true,
        KEY_PERMISSION to "",
        KEY_EFFORT to "",
        KEY_PROMPT_TOKENS to 0,
        KEY_COMPLETION_TOKENS to 0,
        KEY_REQUESTS to 0,
        KEY_STEPS to 0,
        KEY_TOOL_CALL_COUNT to 0,
        KEY_MODEL_MILLIS to 0L,
        KEY_TOOL_MILLIS to 0L,
        KEY_LAST_TURN_MODEL_MILLIS to 0L,
        KEY_LAST_TURN_COMPLETION to 0,
        KEY_CACHE_HIT_TOKENS to 0,
        KEY_CACHE_MISS_TOKENS to 0,
        KEY_TTFT_MILLIS to 0L,
        KEY_TTFT_COUNT to 0,
        KEY_GEN_MILLIS to 0L,
        KEY_GEN_TOKENS to 0,
        KEY_COMPOSITION to JSONObject.NULL,
        KEY_REASONING_MILLIS to 0L,
        KEY_TEXT_MILLIS to 0L,
        KEY_TOOL_ARG_MILLIS to 0L,
        KEY_CONTEXT_SUMMARY to "",
        KEY_SUMMARY_UP_TO to 0,
        KEY_LAST_SUMMARY_AT to 0L,
        KEY_INHERITED_USAGE to JSONObject.NULL,
        KEY_TURN_OPEN to false,
        KEY_TURNS to 0,
        KEY_WALL_MS to 0L,
        KEY_MSG_COUNT to 0
    )

    private const val MINUTE = 60_000L
    private const val HOUR = 60L * MINUTE
    private const val DAY = 24L * HOUR

    /** 上一次发出的 id，确保同一毫秒内连续两次新建会话不会产生重复 id。 */
    @Volatile
    private var lastIssuedId: Long = 0L

    /** 落盘位置：filesDir/appdata/sessions.json（首次读时由 [AppPaths] 从旧位置迁过来）。 */
    private fun storeFile(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    // ---- D1：JSONL 模式与进程内缓存 ----

    /** 折叠结果缓存：按 (文件长度, mtime) 失效，命中时免去整份解压。 */
    private class FoldCache(val fold: SessionLog.Fold, val len: Long, val mtime: Long)

    private val cache = ConcurrentHashMap<String, FoldCache>()

    private fun sessionsDir(context: Context): File = AppPaths.sessionsDir(context)

    private fun sessionFile(context: Context, id: String): File =
        File(sessionsDir(context), id + ".jsonl")

    /** JSONL 模式开关：迁移标记存在即生效；否则整体沿用旧的单文件整写。 */
    private fun jsonlMode(context: Context): Boolean =
        File(sessionsDir(context), SessionLog.MIGRATED_MARKER).isFile

    /** 取一份会话的折叠结果：缓存按 (len, mtime) 命中，未命中（或文件被外部改过）时重新折叠。 */
    private fun foldOf(context: Context, id: String): SessionLog.Fold? {
        val file = sessionFile(context, id)
        if (!file.isFile) {
            cache.remove(id)
            return null
        }
        val len = file.length()
        val mtime = file.lastModified()
        val hit = cache[id]
        if (hit != null && hit.len == len && hit.mtime == mtime) return hit.fold
        val fold = SessionLog.read(file) ?: run {
            cache.remove(id)
            return null
        }
        cache[id] = FoldCache(fold, file.length(), file.lastModified())
        return fold
    }

    private fun sessionFromFold(fold: SessionLog.Fold): ChatSession? =
        sessionFromFields(fold.fields, fold.chatMessages)

    // ---- 对外 ----

    /** 读取全部会话；文件不存在、内容为空或 JSON 损坏时一律返回空列表，不抛出异常。 */
    @Synchronized
    fun load(context: Context): List<ChatSession> {
        if (!jsonlMode(context)) return legacyLoad(context).map { cleanLegacyErrorsInMemory(it) }
        val files = sessionsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name }
            ?: return emptyList()
        val out = ArrayList<ChatSession>(files.size)
        for (file in files) {
            val id = file.name.removeSuffix(".jsonl")
            val fold = foldOf(context, id) ?: continue
            val session = sessionFromFold(fold) ?: continue
            out.add(cleanLegacyErrors(context, healBlank(session)))
        }
        return out.sortedByDescending { it.updatedAt }
    }

    /**
     * 列表页快读（D1.3-F 随机读保护）：只读索引 `sessions.idx.json`，不解压任何会话文件。
     * 索引缺失/过期时 [SessionIndex] 自行重建（兜底路径）。旧格式模式退回全量 [load]。
     */
    fun loadSummaries(context: Context): List<ChatSession> {
        if (!jsonlMode(context)) return load(context)
        return SessionIndex.summaries(context).map { healBlank(it.session) }
    }

    /** 读单条会话（正文懒加载用）：只解压这一个文件；不存在或损坏返回 null。 */
    fun loadOne(context: Context, id: String): ChatSession? {
        if (!jsonlMode(context)) return load(context).firstOrNull { it.id == id }
        val fold = foldOf(context, id) ?: return null
        return sessionFromFold(fold)?.let { cleanLegacyErrors(context, healBlank(it)) }
    }

    /**
     * 空白会话指「还没发出第一条消息的空壳」。若它已经带了消息（定时任务写入，或旧版
     * 差分漏写 blank/listed 留下的存量数据），就不再是空白态，必须转正并列入侧边栏；
     * 否则结果与通知都在，用户却在工作区里找不到这条会话。
     *
     * 列表页走索引快读时消息正文尚未加载，因此同时检查 [ChatSession.msgCount]。
     */
    internal fun healBlank(session: ChatSession): ChatSession =
        if (session.blank && (session.messages.isNotEmpty() || session.msgCount > 0)) {
            session.copy(blank = false, listed = true)
        } else {
            session
        }

    // ---- 旧失败正文 → turn-error 节点 ----

    /**
     * 把 319 之前写进普通 assistant 正文的失败说明改写成 turn-error 节点（纯函数，单测覆盖）。
     *
     * 旧数据形如「请求失败（HTTP 429）：请求失败，请检查网络」：正文为空显示成空气泡，
     * 新结构则是「空正文 + [ChatMessage.errorText] + [ChatMessage.errorCode]」。
     * 命中判据见 [isLegacyErrorBody]；不满足的消息原样返回，列表不变时复用原对象。
     */
    internal fun cleanLegacyTurnErrors(messages: List<ChatMessage>): Pair<List<ChatMessage>, Boolean> {
        var changed = false
        val out = ArrayList<ChatMessage>(messages.size)
        for (message in messages) {
            // 319 已写成错误节点，但 errorText 里仍带「请求失败：请求失败…」重复前缀
            val errorText = message.errorText
            if (errorText != null && message.content.isEmpty() && message.toolCalls.isEmpty()) {
                val normalized = legacyErrorText(errorText)
                if (normalized != errorText) {
                    changed = true
                    out.add(message.copy(errorText = normalized))
                } else {
                    out.add(message)
                }
                continue
            }
            if (!isLegacyErrorBody(message)) {
                out.add(message)
                continue
            }
            changed = true
            out.add(
                message.copy(
                    content = "",
                    errorText = legacyErrorText(message.content),
                    errorCode = "UNKNOWN"
                )
            )
        }
        return if (changed) out to true else messages to false
    }

    /** 旧失败正文的判定：只看「空错误节点 + 无工具调用/思考 + 短正文以请求失败开头」。 */
    private fun isLegacyErrorBody(message: ChatMessage): Boolean =
        message.role == "assistant" &&
            message.errorText == null &&
            message.toolCalls.isEmpty() &&
            message.reasoningContent.isNullOrEmpty() &&
            message.content.length < LEGACY_ERROR_MAX_CHARS &&
            message.content.trimStart().startsWith(LEGACY_ERROR_PREFIX)

    /**
     * 失败的说明正文：去掉与抬头重复的「请求失败」。
     *
     * 「请求失败：请求失败，请检查网络」→「请检查网络」；
     * 带状态码的「请求失败（HTTP 429）：请求失败…」保留括号里的状态码，只去重后半段；
     * 没有重复时保留原文（不凭空改写提供方文案）。
     */
    internal fun legacyErrorText(content: String): String {
        val text = content.trim()
        val colon = text.indexOfFirst { it == '：' || it == ':' }
        if (colon <= 0) return text
        val head = text.substring(0, colon).trim()
        val rest = text.substring(colon + 1).trimStart()
        if (!head.startsWith(LEGACY_ERROR_PREFIX) || !rest.startsWith(LEGACY_ERROR_PREFIX)) return text
        val tail = rest.removePrefix(LEGACY_ERROR_PREFIX).trimStart()
            .trimStart('：', ':', '，', ',', '、', ' ', '\t', '\n')
        if (tail.isEmpty()) return text
        return if (head == LEGACY_ERROR_PREFIX) tail else head + "：" + tail
    }

    /**
     * 读取路径（[load] / [loadOne]）的清洗：有改动时写回 `msg.upd` 并返回清洗后的会话。
     *
     * 幂等：清洗后的消息带 errorText，第二次读取不再命中。
     * 写盘失败只记日志（best-effort），本次读取仍按清洗结果展示，下次启动重试。
     */
    private fun cleanLegacyErrors(context: Context, session: ChatSession): ChatSession {
        val (messages, changed) = cleanLegacyTurnErrors(session.messages)
        if (!changed) return session
        val cleaned = session.copy(messages = messages)
        runCatching { upsert(context, cleaned, allowTruncate = false) }
            .onFailure { FileLog.log(TAG, "旧错误节点清洗写盘失败：" + session.id + "：" + it) }
        return cleaned
    }

    /** 旧整写降级路径只做内存清洗：此时 upsert 会写出 JSONL，反而绕开旧文件，因此不写盘。 */
    private fun cleanLegacyErrorsInMemory(session: ChatSession): ChatSession {
        val (messages, changed) = cleanLegacyTurnErrors(session.messages)
        return if (changed) session.copy(messages = messages) else session
    }

    /** 整表落盘：逐会话 upsert（D1.3-H 语义 1），不删。失败只记日志。 */
    @Synchronized
    fun save(context: Context, sessions: List<ChatSession>) {
        if (!jsonlMode(context)) {
            legacySave(context, sessions)
            return
        }
        for (session in sessions) upsert(context, session, allowTruncate = false)
    }

    /** 与 [save] 同义（D1.3-H 语义 2）：事件流下每会话独立追加，不存在「后写覆盖先写」。 */
    @Synchronized
    fun saveMerged(context: Context, mine: List<ChatSession>) {
        if (!jsonlMode(context)) {
            legacySaveMerged(context, mine)
            return
        }
        save(context, mine)
    }

    /**
     * 以内存快照为准落盘（允许截断）：界面回滚 / 删除消息 / 编辑重发路径专用。
     *
     * [save] 收到更短的列表只会「忽略疑似过期快照」；只有本方法会把磁盘长出的尾部
     * 用 `trunc` 补偿事件裁掉——两条入口的分工即 D1.3-H 语义 6 的「截断走独立 API」。
     */
    @Synchronized
    fun saveAuthoritative(context: Context, session: ChatSession) {
        if (!jsonlMode(context)) {
            legacyReplace(context, session)
            return
        }
        upsert(context, session, allowTruncate = true)
    }

    /**
     * P32：只改 turnOpen 标记（回合开局 / 收尾各一次）。meta-only patch，不碰消息。
     */
    @Synchronized
    fun setTurnOpen(context: Context, id: String, open: Boolean) {
        if (!jsonlMode(context)) {
            val all = load(context)
            val merged = all.map { if (it.id == id) it.copy(turnOpen = open) else it }
            if (merged != all) legacySave(context, merged)
            return
        }
        val file = sessionFile(context, id)
        val fold = foldOf(context, id) ?: return
        val disk = sessionFromFold(fold) ?: return
        if (disk.turnOpen == open) return
        val events = listOf(
            SessionLog.metaEvent(0, System.currentTimeMillis(), JSONObject().put(KEY_TURN_OPEN, open))
        )
        if (SessionLog.appendEvents(file, fold, events)) {
            cache[id] = FoldCache(fold, file.length(), file.lastModified())
            refreshIndex(context, id)
        } else {
            cache.remove(id)
        }
    }

    /**
     * 从磁盘真正删除一条会话（D1.3-H 语义 3）：删文件＋删缓存＋删索引。
     *
     * 事件流下 `save` 不再具备「列表里没有即删除」的语义，删除必须走这里。
     */
    @Synchronized
    fun removeSession(context: Context, id: String) {
        if (!jsonlMode(context)) {
            legacyRemoveSession(context, id)
            return
        }
        cache.remove(id)
        runCatching { sessionFile(context, id).delete() }
            .onFailure { FileLog.log(TAG, "删除会话文件失败：" + id + "：" + it) }
        SessionIndex.remove(context, id)
        FileLog.log(TAG, "删除会话文件：" + id)
    }

    /**
     * 向指定会话末尾追加消息（定时任务使用）；会话不存在时返回 false。
     *
     * 与旧实现的区别：不再整表读改写——按磁盘折叠结果直接追加 `msg` 事件并写 meta patch。
     */
    @Synchronized
    fun appendMessages(
        context: Context,
        sessionId: String,
        added: List<ChatMessage>,
        usage: SessionUsageDelta = SessionUsageDelta(),
        titleIfBlank: String = ""
    ): Boolean {
        if (!jsonlMode(context)) {
            return legacyAppendMessages(context, sessionId, added, usage, titleIfBlank)
        }
        if (added.isEmpty() && usage.isEmpty) return true
        val fold = foldOf(context, sessionId) ?: return false
        val disk = sessionFromFold(fold) ?: return false
        val updated = disk.foldUsage(usage).copy(
            messages = disk.messages + added,
            updatedAt = System.currentTimeMillis(),
            // 后台首次写入时补标题：新会话在界面上不显示空标题
            title = if (disk.title.isBlank() && titleIfBlank.isNotBlank()) titleIfBlank else disk.title
        )
        upsert(context, updated, allowTruncate = false)
        return true
    }

    /**
     * 只把用量并入会话，不动消息、不动 `updatedAt`。
     *
     * 用于「花了钱但没有产出」的执行（例如定时任务本轮请求失败）：会话卡片上的
     * Token / 耗时应当反映真实消耗，但没有新消息，不能把会话顶到列表最前。
     * id 不存在时返回 false。
     */
    @Synchronized
    fun addUsage(context: Context, sessionId: String, usage: SessionUsageDelta): Boolean {
        if (!jsonlMode(context)) return legacyAddUsage(context, sessionId, usage)
        if (usage.isEmpty) return false
        val fold = foldOf(context, sessionId) ?: return false
        val disk = sessionFromFold(fold) ?: return false
        upsert(context, disk.foldUsage(usage), allowTruncate = false)
        return true
    }

    // ---- D1：内部写路径 ----

    /**
     * 统一 upsert：meta patch（只写变化键）+ 消息差异事件（D1.3-C）。
     *
     * [allowTruncate] = false 时磁盘更长的尾部保留（内存尾部记「忽略疑似过期快照」）；
     * = true 时写 `trunc(keep=L)` 补偿事件再追加新尾部（回滚路径）。
     */
    private fun upsert(context: Context, session: ChatSession, allowTruncate: Boolean) {
        val file = sessionFile(context, session.id)
        val fold = foldOf(context, session.id)
        if (fold == null) {
            // 新会话（或旧文件已损坏移除）：整份写入
            if (SessionLog.writeFull(file, session)) {
                val fresh = SessionLog.read(file)
                if (fresh != null) cache[session.id] = FoldCache(fresh, file.length(), file.lastModified())
                refreshIndex(context, session.id)
            } else {
                FileLog.log(TAG, "会话整份写入失败：" + session.id)
            }
            return
        }
        val disk = sessionFromFold(fold) ?: return
        val events = ArrayList<JSONObject>()
        val at = System.currentTimeMillis()
        // 1) meta patch：只写变化键
        val newFields = sessionFieldsToJson(session)
        val patch = metaPatch(fold.fields, newFields)
        if (patch.length() > 0) events.add(SessionLog.metaEvent(0, at, patch))
        // 2) 消息差异
        val diff = SessionLog.diffEvents(disk.messages, session.messages, allowTruncate)
        if (diff == null) {
            // save 不允许截断：磁盘更长的尾部保留，本次只应用 meta
            FileLog.log(
                TAG,
                "忽略疑似过期快照（会话 " + session.id + "：内存 " + session.messages.size +
                    " 条，磁盘 " + disk.messages.size + " 条）"
            )
        } else {
            events.addAll(diff)
        }
        if (events.isEmpty()) return
        if (SessionLog.appendEvents(file, fold, events)) {
            cache[session.id] = FoldCache(fold, file.length(), file.lastModified())
            refreshIndex(context, session.id)
        } else {
            // 写盘失败：缓存作废，下次读盘重新折叠（折回磁盘真实状态）
            cache.remove(session.id)
            FileLog.log(TAG, "会话事件追加失败，缓存作废：" + session.id)
        }
    }

    /** 把刚写过的一条会话合并进索引（内存条目读改写，不触发重建）。 */
    private fun refreshIndex(context: Context, id: String) {
        val cached = cache[id] ?: return
        val session = sessionFromFold(cached.fold) ?: return
        val entries = SessionIndex.readRaw(context).filterNot { it.session.id == id }
        SessionIndex.save(context, entries + SessionIndex.Entry(session, sessionFile(context, id).length()))
    }

    /**
     * 计算 meta 差分。
     *
     * 两侧都按 [DEFAULT_FIELDS] 补齐缺省值再比较：只看新字段会漏掉「回到缺省值」的键，
     * 导致 blank/listed/archived 等布尔与字符串无法翻回默认，重启后读到旧状态。
     */
    internal fun metaPatch(foldFields: JSONObject, newFields: JSONObject): JSONObject {
        val patch = JSONObject()
        val keys = LinkedHashSet<String>()
        for (key in newFields.keys()) keys.add(key)
        for (key in foldFields.keys()) {
            if (DEFAULT_FIELDS.containsKey(key)) keys.add(key)
        }
        for (key in keys) {
            val old = if (foldFields.has(key)) foldFields.opt(key) else DEFAULT_FIELDS[key]
            val value = if (newFields.has(key)) newFields.opt(key) else DEFAULT_FIELDS[key]
            if (!sameValue(old, value)) patch.put(key, value)
        }
        return patch
    }

    /** 数值比较用 double：org.json 解析回的小整数是 Integer，与新写入的 Long 比较会不相等。 */
    private fun sameValue(a: Any?, b: Any?): Boolean = when {
        isJsonNull(a) && isJsonNull(b) -> true
        isJsonNull(a) || isJsonNull(b) -> false
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is JSONObject && b is JSONObject -> a.toString() == b.toString()
        else -> a == b
    }

    /** 键不存在（null）与显式 JSON null 同义，比较时归一。 */
    private fun isJsonNull(value: Any?): Boolean = value == null || value === JSONObject.NULL

    // ---- 旧格式路径（迁移失败时的降级，行为与 D1 之前完全一致）----

    private fun legacyLoad(context: Context): List<ChatSession> {
        val file = storeFile(context)
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) emptyList() else parse(text).map { healBlank(it) }
        } catch (e: Exception) {
            FileLog.log(TAG, "读取会话失败，从空列表开始：" + e)
            emptyList()
        }
    }

    /** 旧格式整写；先写 .tmp 再 rename，避免写入中断后残留损坏文件。 */
    private fun legacySave(context: Context, sessions: List<ChatSession>) {
        try {
            val array = JSONArray()
            for (session in sessions) array.put(legacySessionJson(session))
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

    private fun legacySessionJson(session: ChatSession): JSONObject {
        val obj = sessionFieldsToJson(session)
        val array = JSONArray()
        for (message in session.messages) array.put(messageToJson(message))
        obj.put(KEY_MESSAGES, array)
        return obj
    }

    /** 旧格式下的整条替换（saveAuthoritative 的降级路径）。 */
    private fun legacyReplace(context: Context, session: ChatSession) {
        val all = legacyLoad(context)
        val exists = all.any { it.id == session.id }
        val merged = if (exists) {
            all.map { if (it.id == session.id) session else it }
        } else {
            all + session
        }
        legacySave(context, merged)
    }

    /** 旧格式下的合并落盘（saveMerged 的降级路径）：mine 覆盖同 id，磁盘其余会话保留。 */
    private fun legacySaveMerged(context: Context, mine: List<ChatSession>) {
        if (mine.isEmpty()) return
        val all = legacyLoad(context)
        val merged = ArrayList<ChatSession>(all.size + mine.size)
        for (session in all) {
            merged.add(mine.firstOrNull { it.id == session.id } ?: session)
        }
        for (session in mine) {
            if (merged.none { it.id == session.id }) merged.add(session)
        }
        legacySave(context, merged)
    }

    private fun legacyRemoveSession(context: Context, id: String) {
        val all = legacyLoad(context)
        val kept = all.filterNot { it.id == id }
        if (kept.size != all.size) legacySave(context, kept)
    }

    private fun legacyAppendMessages(
        context: Context,
        sessionId: String,
        added: List<ChatMessage>,
        usage: SessionUsageDelta,
        titleIfBlank: String
    ): Boolean {
        val all = legacyLoad(context)
        val index = all.indexOfFirst { it.id == sessionId }
        if (index < 0) return false
        val old = all[index]
        val merged = all.toMutableList()
        merged[index] = old.foldUsage(usage).copy(
            messages = old.messages + added,
            updatedAt = System.currentTimeMillis(),
            title = if (old.title.isBlank() && titleIfBlank.isNotBlank()) titleIfBlank else old.title
        )
        legacySave(context, merged)
        return true
    }

    private fun legacyAddUsage(context: Context, sessionId: String, usage: SessionUsageDelta): Boolean {
        if (usage.isEmpty) return false
        val all = legacyLoad(context)
        val index = all.indexOfFirst { it.id == sessionId }
        if (index < 0) return false
        val merged = all.toMutableList()
        merged[index] = all[index].foldUsage(usage)
        legacySave(context, merged)
        return true
    }

    // ---- 备份导入接驳 ----

    /**
     * 备份导入每会话 JSONL 文件（`data/sessions` 目录下的 *.jsonl）之后调用：清缓存、作废索引、确保迁移标记存在
     * （导入的 JSONL 从此是权威数据）。旧版 `sessions.json` 的导入走 [reimportLegacySessions]。
     */
    @Synchronized
    fun onSessionsImported(context: Context) {
        cache.clear()
        SessionIndex.invalidate(context)
        val marker = File(sessionsDir(context), SessionLog.MIGRATED_MARKER)
        if (!marker.isFile) SessionLog.migrateIfNeeded(context)
    }

    /**
     * 备份导入旧版 `data/sessions.json` 之后当场迁移（D1.3-G）：导入内容覆盖同名会话文件，
     * 迁移成功才生效；失败时保留现状并写日志（下次启动重试）。
     */
    @Synchronized
    fun reimportLegacySessions(context: Context) {
        cache.clear()
        SessionIndex.invalidate(context)
        File(sessionsDir(context), SessionLog.MIGRATED_MARKER).delete()
        SessionLog.migrateIfNeeded(context, overwrite = true)
    }

    // ---- 新建 / 分叉 / 标题 ----

    /**
     * 新建一条空会话。id 使用毫秒时间戳（同一毫秒内连续创建时自增，避免重复）。
     *
     * [storageId] / [permissionPreset] / [reasoningEffort] 由创建方按创建时的默认值快照传入；
     * [blank] 与 [listed] 由创建来源决定（见设计稿的空白会话表），默认值对应「已转正且可见」。
     */
    fun newSession(
        storageId: String = "",
        blank: Boolean = false,
        listed: Boolean = true,
        permissionPreset: String = "",
        reasoningEffort: String = ""
    ): ChatSession {
        val now = System.currentTimeMillis()
        val id = if (now > lastIssuedId) now else lastIssuedId + 1
        lastIssuedId = id
        return ChatSession(
            id = id.toString(),
            title = "",
            messages = emptyList(),
            updatedAt = now,
            storageId = storageId,
            blank = blank,
            listed = listed,
            permissionPreset = permissionPreset,
            reasoningEffort = reasoningEffort
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
        newSession(
            // 分叉沿用父会话的运行根与偏好：分支内容的附件路径相对同一目录，换目录会导致引用失效
            storageId = base.storageId,
            permissionPreset = base.permissionPreset,
            reasoningEffort = base.reasoningEffort
        ).copy(
            title = "分支 · " + base.title.ifBlank { "新会话" },
            messages = messages,
            workspaceId = base.workspaceId,
            promptTokens = base.promptTokens,
            completionTokens = base.completionTokens,
            requests = base.requests,
            steps = base.steps,
            toolCalls = base.toolCalls,
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
            turns = base.turns,
            wallMs = base.wallMs,
            inheritedUsage = InheritedUsage(
                promptTokens = base.promptTokens,
                completionTokens = base.completionTokens,
                requests = base.requests,
                steps = base.steps,
                toolCalls = base.toolCalls,
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

    /** 兜底标题的取词上限与字节上限，与 DSH 的 `session-title` 配置同值。 */
    private const val FALLBACK_MAX_WORDS = 5
    private const val FALLBACK_MAX_BYTES = 40

    /** 最终标题的 UTF-8 字节上限，与 DSH 的 `session-title` 的 `maxTitleBytes` 同值。 */
    private const val MAX_TITLE_BYTES = 80

    /** 非空白控制符与方向符：标题里出现会让显示失真，统一剔除（换行与制表符留给空白折叠）。 */
    private val TITLE_CONTROL = Regex(
        "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F" +
            "\\u200B\\u200E\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u206F\\uFEFF]"
    )
    private val TITLE_SPACES = Regex("\\s+")

    /**
     * 标题文本的清洗：剔除控制符与方向符，把连续空白压成一个空格，再去掉首尾空白。
     *
     * 口径与 DSH 的 `cleanTitleText` 相同（控制符不含换行与制表符，它们随后按空白折叠）。
     */
    private fun cleanTitleText(text: String): String = text
        .replace(TITLE_CONTROL, "")
        .replace(TITLE_SPACES, " ")
        .trim()

    /**
     * 会话的兜底标题：首条用户消息的前 5 个空格分隔词，UTF-8 不超过 40 字节。
     *
     * 口径对照 DSH（DeepSeek Harness）的 `session-title`：先清洗文本、取前 5 个词，
     * 再按 UTF-8 字节截断（40 字节，中文约 13 字）。
     * 中文正文没有空格，整句就是一个词，因此实际表现为「取开头一句并在 40 字节处截断」。
     */
    fun fallbackTitle(text: String): String {
        val cleaned = cleanTitleText(text)
        if (cleaned.isEmpty()) return ""
        val words = cleaned.split(' ').filter { it.isNotEmpty() }.take(FALLBACK_MAX_WORDS)
        return truncateUtf8(words.joinToString(" "), FALLBACK_MAX_BYTES).trimEnd()
    }

    /**
     * 最终标题的归一化：清洗文本后按 UTF-8 80 字节截断。
     *
     * 对应 DSH 的 `normalizeSessionTitle`，生效于两处受理点：用户改名与模型生成的标题。
     * 兜底标题不走这里（它自身只受 40 字节约束）。
     */
    fun normalizeTitle(text: String): String = truncateUtf8(cleanTitleText(text), MAX_TITLE_BYTES).trimEnd()

    /** 按 UTF-8 字节数截断，且不把多字节字符切成半个。 */
    private fun truncateUtf8(input: String, maxBytes: Int): String {
        if (input.toByteArray(Charsets.UTF_8).size <= maxBytes) return input
        val out = StringBuilder()
        var used = 0
        var index = 0
        while (index < input.length) {
            val codePoint = input.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val bytes = String(input.toCharArray(), index, chars).toByteArray(Charsets.UTF_8).size
            if (used + bytes > maxBytes) break
            out.appendCodePoint(codePoint)
            used += bytes
            index += chars
        }
        return out.toString()
    }

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

    /**
     * 会话字段（**不含** messages 数组）落盘：供旧格式整写与事件流的 meta 帧共用。
     * 未与缺省值不同的键不写入，旧文件不会因升级而增大。
     */
    internal fun sessionFieldsToJson(session: ChatSession): JSONObject {
        val obj = JSONObject()
        obj.put(KEY_ID, session.id)
        obj.put(KEY_TITLE, session.title)
        obj.put(KEY_UPDATED_AT, session.updatedAt)
        // 未归档、未置顶时不写入该键：旧版本 App 读取未知键不会出错，文件也更小
        if (session.archived) obj.put(KEY_ARCHIVED, true)
        if (session.pinned) obj.put(KEY_PINNED, true)
        // 未分类（null）时不写入该键：读取时 has() 为 false 即 null，文件也更小
        session.workspaceId?.takeIf { it.isNotEmpty() }?.let { obj.put(KEY_WORKSPACE_ID, it) }
        // 以下五项同样「与缺省值不同才写入」，旧文件不会因升级而增大
        session.storageId.takeIf { it.isNotEmpty() }?.let { obj.put(KEY_STORAGE_ID, it) }
        if (session.blank) obj.put(KEY_BLANK, true)
        if (!session.listed) obj.put(KEY_LISTED, false)
        session.permissionPreset.takeIf { it.isNotEmpty() }?.let { obj.put(KEY_PERMISSION, it) }
        session.reasoningEffort.takeIf { it.isNotEmpty() }?.let { obj.put(KEY_EFFORT, it) }
        // 计数为 0 时不写入：与 archived 一致，仅在存在数值时写入键，旧文件不会因升级而增大
        if (session.promptTokens != 0) obj.put(KEY_PROMPT_TOKENS, session.promptTokens)
        if (session.completionTokens != 0) obj.put(KEY_COMPLETION_TOKENS, session.completionTokens)
        if (session.requests != 0) obj.put(KEY_REQUESTS, session.requests)
        if (session.steps != 0) obj.put(KEY_STEPS, session.steps)
        if (session.toolCalls != 0) obj.put(KEY_TOOL_CALL_COUNT, session.toolCalls)
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
        // P32 / X06 / msgCount：与缺省值不同才写入
        if (session.turnOpen) obj.put(KEY_TURN_OPEN, true)
        if (session.turns != 0) obj.put(KEY_TURNS, session.turns)
        if (session.wallMs != 0L) obj.put(KEY_WALL_MS, session.wallMs)
        // 消息条数以调用方给的 messages 列表为准（事件流下与消息事件同步推进）
        if (session.messages.isNotEmpty()) obj.put(KEY_MSG_COUNT, session.messages.size)
        else if (session.msgCount != 0) obj.put(KEY_MSG_COUNT, session.msgCount)
        return obj
    }

    /** 从「字段 JSON + 消息列表」拼回会话记录（事件流折叠结果与索引条目共用）。 */
    internal fun sessionFromFields(fields: JSONObject, messages: List<ChatMessage>): ChatSession? {
        val id = fields.optString(KEY_ID, "")
        if (id.isEmpty()) return null
        return ChatSession(
            id = id,
            title = fields.optString(KEY_TITLE, ""),
            messages = messages,
            updatedAt = fields.optLong(KEY_UPDATED_AT, 0L),
            archived = fields.optBoolean(KEY_ARCHIVED, false),
            pinned = fields.optBoolean(KEY_PINNED, false),
            workspaceId = fields.stringOrNull(KEY_WORKSPACE_ID),
            storageId = fields.optString(KEY_STORAGE_ID, ""),
            blank = fields.optBoolean(KEY_BLANK, false),
            listed = fields.optBoolean(KEY_LISTED, true),
            permissionPreset = fields.optString(KEY_PERMISSION, ""),
            reasoningEffort = fields.optString(KEY_EFFORT, ""),
            promptTokens = fields.optInt(KEY_PROMPT_TOKENS, 0),
            completionTokens = fields.optInt(KEY_COMPLETION_TOKENS, 0),
            requests = fields.optInt(KEY_REQUESTS, 0),
            steps = fields.optInt(KEY_STEPS, 0),
            toolCalls = fields.optInt(KEY_TOOL_CALL_COUNT, 0),
            modelMillis = fields.optLong(KEY_MODEL_MILLIS, 0L),
            toolMillis = fields.optLong(KEY_TOOL_MILLIS, 0L),
            lastTurnModelMillis = fields.optLong(KEY_LAST_TURN_MODEL_MILLIS, 0L),
            lastTurnCompletionTokens = fields.optInt(KEY_LAST_TURN_COMPLETION, 0),
            cacheHitTokens = fields.optInt(KEY_CACHE_HIT_TOKENS, 0),
            cacheMissTokens = fields.optInt(KEY_CACHE_MISS_TOKENS, 0),
            ttftMillis = fields.optLong(KEY_TTFT_MILLIS, 0L),
            ttftCount = fields.optInt(KEY_TTFT_COUNT, 0),
            genMillis = fields.optLong(KEY_GEN_MILLIS, 0L),
            genTokens = fields.optInt(KEY_GEN_TOKENS, 0),
            composition = compositionFromJson(fields.optJSONObject(KEY_COMPOSITION)),
            reasoningMillis = fields.optLong(KEY_REASONING_MILLIS, 0L),
            textMillis = fields.optLong(KEY_TEXT_MILLIS, 0L),
            toolArgMillis = fields.optLong(KEY_TOOL_ARG_MILLIS, 0L),
            contextSummary = fields.optString(KEY_CONTEXT_SUMMARY, ""),
            summaryUpTo = fields.optInt(KEY_SUMMARY_UP_TO, 0),
            lastSummaryAt = fields.optLong(KEY_LAST_SUMMARY_AT, 0L),
            inheritedUsage = fields.optJSONObject(KEY_INHERITED_USAGE)
                ?.let { inheritedUsageFromJson(it) }
                ?: InheritedUsage(),
            turnOpen = fields.optBoolean(KEY_TURN_OPEN, false),
            turns = fields.optInt(KEY_TURNS, 0),
            wallMs = fields.optLong(KEY_WALL_MS, 0L),
            // 正文在场时以正文条数为准；索引条目（无正文）读 msgCount 字段
            msgCount = if (messages.isNotEmpty()) messages.size else fields.optInt(KEY_MSG_COUNT, 0)
        )
    }

    /** 从一份完整 JSON（含 messages）解析会话；供旧格式读盘与索引条目（messages 缺省为空）共用。 */
    internal fun sessionFromJson(obj: JSONObject): ChatSession? {
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
        return sessionFromFields(obj, repaired)
    }

    /** 旧格式整文件解析（迁移器与降级读盘共用）；调用方负责空串检查。 */
    internal fun parseSessionsJson(text: String): List<ChatSession> = parse(text)

    private fun parse(text: String): List<ChatSession> {
        val root = JSONObject(text)
        val array = root.optJSONArray(KEY_SESSIONS) ?: return emptyList()
        val sessions = ArrayList<ChatSession>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            // id 为会话唯一标识，缺失时无法切换或删除，直接丢弃
            if (obj.optString(KEY_ID, "").isEmpty()) continue
            sessionFromJson(obj)?.let { sessions.add(it) }
        }
        return sessions
    }

    /** 分叉继承基线落盘：键名复用会话级的同名字段，读取时可直接对应。 */
    private fun inheritedUsageToJson(usage: InheritedUsage): JSONObject {
        val obj = JSONObject()
        obj.put(KEY_PROMPT_TOKENS, usage.promptTokens)
        obj.put(KEY_COMPLETION_TOKENS, usage.completionTokens)
        obj.put(KEY_REQUESTS, usage.requests)
        obj.put(KEY_STEPS, usage.steps)
        obj.put(KEY_TOOL_CALL_COUNT, usage.toolCalls)
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
        toolCalls = obj.optInt(KEY_TOOL_CALL_COUNT, 0),
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
    internal fun messageToJson(message: ChatMessage): JSONObject {
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
        // 只在有结论时写入：null（未记录）不落盘，读回仍是 null
        message.toolOk?.let { obj.put(KEY_TOOL_OK, it) }
        // 图片仅写入工作区相对路径；imageDataUrls 为请求期临时数据，一律不落盘
        if (message.images.isNotEmpty()) {
            val images = JSONArray()
            for (path in message.images) images.put(path)
            obj.put(KEY_IMAGES, images)
        }
        if (message.produced.isNotEmpty()) {
            val produced = JSONArray()
            for (item in message.produced) {
                produced.put(
                    JSONObject()
                        .put(KEY_PRODUCED_PATH, item.path)
                        .put(KEY_PRODUCED_KIND, item.kind)
                        .put(KEY_PRODUCED_SOURCE, item.source)
                )
            }
            obj.put(KEY_PRODUCED, produced)
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
        // 仅写入非默认来源：旧版本无此键，读取为「用户手动发送」
        if (message.origin != ORIGIN_USER) obj.put(KEY_MSG_ORIGIN, message.origin)
        // 仅写入 true：旧版本无此键，读取为 false（未被中止 / 真实工具输出）
        if (message.interrupted) obj.put(KEY_MSG_INTERRUPTED, true)
        if (message.toolAborted) obj.put(KEY_MSG_TOOL_ABORTED, true)
        // 回合终态失败节点：正文为空的 assistant 消息，仅这两个键承载信息
        message.errorText?.takeIf { it.isNotEmpty() }?.let { obj.put(KEY_MSG_ERROR, it) }
        message.errorCode?.takeIf { it.isNotEmpty() }?.let { obj.put(KEY_MSG_ERROR_CODE, it) }
        obj.put(KEY_CREATED_AT, message.createdAt)
        return obj
    }

    /**
     * 补上「已派发但没有结果」的工具结果。
     *
     * 必要性：落盘检查点会在**执行有副作用的工具之前**写入一份会话，若进程在工具执行中终止，
     * 磁盘上会存在「assistant 含 tool_calls，但后续无对应 tool 消息」的记录。若不补齐：
     * ① 界面会残留无结果的工具气泡；② 请求视图的 sanitized() 会丢弃**整轮**内容，
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

    internal fun messageFromJson(obj: JSONObject): ChatMessage? {
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
            // 键不存在表示未记录（旧会话）：既不当成功也不当失败
            toolOk = if (obj.has(KEY_TOOL_OK)) obj.optBoolean(KEY_TOOL_OK, false) else null,
            // 旧版本落盘无 images，stringList 返回空列表，旧会话可正常打开
            images = obj.stringList(KEY_IMAGES),
            produced = obj.producedList(KEY_PRODUCED),
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
            // 旧会话无此键：默认「用户手动发送」，统计口径与旧版本一致
            origin = obj.optString(KEY_MSG_ORIGIN, ORIGIN_USER),
            // 旧会话无此键：false = 不是被中止的半截内容 / 不是合成的中止结果
            interrupted = obj.optBoolean(KEY_MSG_INTERRUPTED, false),
            toolAborted = obj.optBoolean(KEY_MSG_TOOL_ABORTED, false),
            // 旧会话无这两个键：读取为 null，界面不画错误节点
            errorText = obj.optString(KEY_MSG_ERROR, "").takeIf { it.isNotEmpty() },
            errorCode = obj.optString(KEY_MSG_ERROR_CODE, "").takeIf { it.isNotEmpty() },
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

    /** 读取产物列表；键不存在或元素缺路径时跳过。 */
    private fun JSONObject.producedList(key: String): List<ProducedArtifact> {
        val array = optJSONArray(key) ?: return emptyList()
        val result = ArrayList<ProducedArtifact>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val path = item.optString(KEY_PRODUCED_PATH, "")
            if (path.isEmpty()) continue
            result.add(
                ProducedArtifact(
                    path = path,
                    kind = item.optString(KEY_PRODUCED_KIND, ""),
                    source = item.optString(KEY_PRODUCED_SOURCE, "")
                )
            )
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

/**
 * 会话级发送锁：同一会话的普通回合与定时回合串行执行。
 *
 * 界面回合与后台定时回合都会整份覆盖写会话，两者同时跑会互相覆盖消息。
 * 聊天页开工前占住当前会话，定时任务在目标会话已被占用时跳过本轮；
 * 锁只存在于进程内，进程被杀后自然释放。
 */
object SessionBusy {

    private val held = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 尝试占用；已被占用返回 false。 */
    fun tryAcquire(sessionId: String): Boolean = held.add(sessionId)

    /** 释放占用；未占用时无副作用。 */
    fun release(sessionId: String) {
        held.remove(sessionId)
    }

    /** 只读检查，用于在产生副作用之前先拒绝这次发送。 */
    fun isBusy(sessionId: String): Boolean = sessionId in held
}
