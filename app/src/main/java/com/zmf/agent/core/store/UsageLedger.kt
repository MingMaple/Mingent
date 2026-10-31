package com.zmf.agent.core.store

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 计费构成八段：七段上下文 + 输出。
 *
 * 口径与 DSH 一致：服务端给的 prompt tokens 是真实总额，按请求视图的估算占比拆成七段；
 * 输出直接取 completionTokens。八段合计 = 真实输入 + 输出。
 */
data class LedgerComposition(
    val system: Int = 0,
    val skills: Int = 0,
    val tools: Int = 0,
    val user: Int = 0,
    val inject: Int = 0,
    val assistant: Int = 0,
    val toolResults: Int = 0,
    val output: Int = 0
) {
    val isEmpty: Boolean
        get() = system == 0 && skills == 0 && tools == 0 && user == 0 &&
            inject == 0 && assistant == 0 && toolResults == 0 && output == 0

    val total: Int
        get() = system + skills + tools + user + inject + assistant + toolResults + output

    operator fun plus(other: LedgerComposition): LedgerComposition = LedgerComposition(
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
 * 一份用量桶：总账与按天共用同一形状。
 *
 * 时间字段全部是毫秒；[toolCalls] 是工具调用次数，[requests] 是成功的模型请求次数。
 */
data class LedgerUsage(
    val prompt: Int = 0,
    val completion: Int = 0,
    val requests: Int = 0,
    /** 用户亲手输入的轮次（不含分叉克隆与 App 代发）；只增不减，删会话也不回落。 */
    val rounds: Int = 0,
    val steps: Int = 0,
    val cacheHit: Int = 0,
    val cacheMiss: Int = 0,
    val modelMs: Long = 0L,
    val toolMs: Long = 0L,
    val activeMs: Long = 0L,
    val ttftMs: Long = 0L,
    val genMs: Long = 0L,
    val reasoningMs: Long = 0L,
    val textMs: Long = 0L,
    val toolArgMs: Long = 0L,
    val genTokens: Int = 0,
    val toolCalls: Int = 0,
    /** 估算费用（人民币）；只累加已注册价目的供应商（目前 DeepSeek）。 */
    val costCny: Double = 0.0,
    val composition: LedgerComposition = LedgerComposition()
)

/**
 * 全局总账的一份只读快照：统计页直接读它，不改 [UsageLedger] 内部状态。
 *
 * 旧字段（totalPromptTokens / todayPromptTokens 等）保留成计算属性，
 * 免得调用点跟着新结构一起改。
 */
data class LedgerSnapshot(
    val total: LedgerUsage = LedgerUsage(),
    val today: LedgerUsage = LedgerUsage(),
    val todayDate: String = "",
    /** 按天账本（`yyyy-MM-dd` → 当天用量），只保留最近 [UsageLedger.DAYS_KEEP] 天。 */
    val days: Map<String, LedgerUsage> = emptyMap(),
    val firstUsedAt: Long = 0L,
    val lastUsedAt: Long = 0L
) {
    val totalPromptTokens: Int get() = total.prompt
    val totalCompletionTokens: Int get() = total.completion
    val totalRequests: Int get() = total.requests
    val totalSteps: Int get() = total.steps
    val todayPromptTokens: Int get() = today.prompt
    val todayCompletionTokens: Int get() = today.completion
    val todayRequests: Int get() = today.requests

    /** 按天账本逐字段求和；`null` 表示还没算过（由调用方缓存，别在大循环里反复调）。 */
    val daysSum: LedgerUsage by lazy { days.values.fold(LedgerUsage()) { acc, day -> acc.merge(day) } }

    /**
     * 历史遗留：总账里有、按天账本里没有的那部分。
     *
     * 来源是 v1 时代的累计值（迁移时只把「今日三项」放进当天桶）与已删会话的用量，
     * 它们没有可靠的日期归属。范围统计必须把它算进来，否则「全部」与「近 30 天」
     * 会给出两个互相矛盾的数字。
     */
    val legacy: LedgerUsage by lazy {
        val sum = daysSum
        LedgerUsage(
            prompt = (total.prompt - sum.prompt).coerceAtLeast(0),
            completion = (total.completion - sum.completion).coerceAtLeast(0),
            requests = (total.requests - sum.requests).coerceAtLeast(0),
            rounds = (total.rounds - sum.rounds).coerceAtLeast(0),
            steps = (total.steps - sum.steps).coerceAtLeast(0),
            cacheHit = (total.cacheHit - sum.cacheHit).coerceAtLeast(0),
            cacheMiss = (total.cacheMiss - sum.cacheMiss).coerceAtLeast(0),
            modelMs = (total.modelMs - sum.modelMs).coerceAtLeast(0L),
            toolMs = (total.toolMs - sum.toolMs).coerceAtLeast(0L),
            activeMs = (total.activeMs - sum.activeMs).coerceAtLeast(0L),
            ttftMs = (total.ttftMs - sum.ttftMs).coerceAtLeast(0L),
            genMs = (total.genMs - sum.genMs).coerceAtLeast(0L),
            reasoningMs = (total.reasoningMs - sum.reasoningMs).coerceAtLeast(0L),
            textMs = (total.textMs - sum.textMs).coerceAtLeast(0L),
            toolArgMs = (total.toolArgMs - sum.toolArgMs).coerceAtLeast(0L),
            genTokens = (total.genTokens - sum.genTokens).coerceAtLeast(0),
            toolCalls = (total.toolCalls - sum.toolCalls).coerceAtLeast(0),
            costCny = (total.costCny - sum.costCny).coerceAtLeast(0.0)
            // composition 不参与差额：v1 没有八段明细，凭空摊进某一段就是伪造
        )
    }

    /** 有明细的最早一天（`yyyy-MM-dd`）；没记过明细返回空串。 */
    val firstDay: String get() = days.keys.minOrNull() ?: ""
}

/**
 * 逐字段相加。总账、按天桶、范围求和共用同一份加法，避免某个字段漏加。
 *
 * 放在文件级（而不是 [UsageLedger] 内部）是因为 [LedgerSnapshot] 求和也要用它。
 */
internal fun LedgerUsage.merge(other: LedgerUsage): LedgerUsage = LedgerUsage(
    prompt = prompt + other.prompt,
    completion = completion + other.completion,
    requests = requests + other.requests,
    rounds = rounds + other.rounds,
    steps = steps + other.steps,
    cacheHit = cacheHit + other.cacheHit,
    cacheMiss = cacheMiss + other.cacheMiss,
    modelMs = modelMs + other.modelMs,
    toolMs = toolMs + other.toolMs,
    activeMs = activeMs + other.activeMs,
    ttftMs = ttftMs + other.ttftMs,
    genMs = genMs + other.genMs,
    reasoningMs = reasoningMs + other.reasoningMs,
    textMs = textMs + other.textMs,
    toolArgMs = toolArgMs + other.toolArgMs,
    genTokens = genTokens + other.genTokens,
    toolCalls = toolCalls + other.toolCalls,
    costCny = costCny + other.costCny,
    composition = composition + other.composition
)

/**
 * 全局用量总账（stats.json）：**只增不减的历史总账**。
 *
 * 口径（用户定死，别改）：
 * - 删会话 / 归档 / 清工作区都**不动**这里；只有真的发生模型请求才往上加。
 * - 本会话的累计另存在 sessions.json 的会话记录里，两套账互不覆盖。
 * - 按天桶用于「近 7 天 / 近 30 天 / 范围筛选」；只保留最近 [DAYS_KEEP] 天。
 *
 * 落盘格式 v2（version=2）；读到 v1 的扁平 10 个键时原样搬进 `total`，
 * 并先把旧文件复制成 `stats.json.bak`。坏文件 / 缺字段一律回落 0，绝不抛。
 */
object UsageLedger {

    private const val FILE_NAME = "stats.json"
    private const val TAG = "stats"

    /** 按天账本保留天数：7 天 / 30 天 / 全部区间都够用。 */
    const val DAYS_KEEP = 120

    private const val KEY_VERSION = "version"
    private const val KEY_TOTAL = "total"
    private const val KEY_DAYS = "days"
    private const val KEY_FIRST_USED_AT = "firstUsedAt"
    private const val KEY_LAST_USED_AT = "lastUsedAt"
    private const val KEY_COST_BACKFILLED = "costBackfilled"
    private const val KEY_ROUNDS_BACKFILLED = "roundsBackfilled"

    // v1 扁平键：只读迁移用，不再新写
    private const val KEY_TOTAL_PROMPT = "totalPromptTokens"
    private const val KEY_TOTAL_COMPLETION = "totalCompletionTokens"
    private const val KEY_TOTAL_REQUESTS = "totalRequests"
    private const val KEY_TOTAL_STEPS = "totalSteps"
    private const val KEY_TODAY_PROMPT = "todayPromptTokens"
    private const val KEY_TODAY_COMPLETION = "todayCompletionTokens"
    private const val KEY_TODAY_REQUESTS = "todayRequests"
    private const val KEY_TODAY_DATE = "todayDate"

    /** 内存里是否已经读过盘；进程重启后由 init 里的 load 置位。 */
    private var loaded = false

    private var total = LedgerUsage()
    private val days = HashMap<String, LedgerUsage>()
    private var firstUsedAt = 0L
    private var lastUsedAt = 0L
    /** 历史费用是否已从现存会话回填（只做一次）。 */
    private var costBackfilled = false
    private var roundsBackfilled = false

    /** 落盘位置：filesDir/appdata/stats.json（首次读时由 [AppPaths] 从旧位置迁过来）。 */
    private fun storeFile(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    /** "yyyy-MM-dd" 本地日期；毫秒时间戳 <= 0（从没用过）返回空串。 */
    fun dateText(millis: Long): String {
        if (millis <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
    }

    // ---- 对外 ----

    /**
     * 读总账。重复调用是空操作（内存里已载入就不再读盘），所以各处放心调用。
     * 文件不存在 / 空 / JSON 坏掉 / 缺字段，一律当 0，不崩。
     */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val file = storeFile(context)
        if (file.exists() && file.length() > 0L) {
            try {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                val version = root.optInt(KEY_VERSION, 1)
                if (version >= 2) {
                    total = usageFromJson(root.optJSONObject(KEY_TOTAL))
                    val dayObj = root.optJSONObject(KEY_DAYS)
                    if (dayObj != null) {
                        for (key in dayObj.keys()) {
                            days[key] = usageFromJson(dayObj.optJSONObject(key))
                        }
                    }
                } else {
                    // v1：先留备份，再原样搬进 total；今日三项按旧日期落到当天桶
                    backupV1(file)
                    total = LedgerUsage(
                        prompt = root.optInt(KEY_TOTAL_PROMPT, 0),
                        completion = root.optInt(KEY_TOTAL_COMPLETION, 0),
                        requests = root.optInt(KEY_TOTAL_REQUESTS, 0),
                        steps = root.optInt(KEY_TOTAL_STEPS, 0)
                    )
                    val oldDate = root.optString(KEY_TODAY_DATE, "")
                    if (oldDate.isNotEmpty()) {
                        days[oldDate] = LedgerUsage(
                            prompt = root.optInt(KEY_TODAY_PROMPT, 0),
                            completion = root.optInt(KEY_TODAY_COMPLETION, 0),
                            requests = root.optInt(KEY_TODAY_REQUESTS, 0)
                        )
                    }
                }
                firstUsedAt = root.optLong(KEY_FIRST_USED_AT, 0L)
                lastUsedAt = root.optLong(KEY_LAST_USED_AT, 0L)
                costBackfilled = root.optBoolean(KEY_COST_BACKFILLED, false)
                roundsBackfilled = root.optBoolean(KEY_ROUNDS_BACKFILLED, false)
            } catch (e: Exception) {
                FileLog.log(TAG, "读取总账失败，从 0 开始：" + e)
                clearMemory()
            }
        }
        loaded = true
    }

    /**
     * 一次性回填历史费用：按现存会话自己的消息（分叉克隆段不计）逐轮算钱，写进按天桶与总额。
     *
     * 这样费用像 token 总账一样持久：之后删会话不会让费用下降。
     */
    @Synchronized
    fun backfillCost(context: Context, messages: List<ChatMessage>) {
        load(context)
        if (costBackfilled) return
        var sum = 0.0
        for (message in messages) {
            val cost = UsageCost.turnCost(message) ?: continue
            sum += cost
            val date = dateText(message.createdAt)
            if (date.isNotEmpty()) {
                days[date] = (days[date] ?: LedgerUsage()).copy(
                    costCny = (days[date]?.costCny ?: 0.0) + cost,
                    requests = days[date]?.requests ?: 0
                )
            }
        }
        if (sum > 0.0) total = total.copy(costCny = total.costCny + sum)
        costBackfilled = true
        save(context)
    }

    /**
     * 一次性回填历史轮次：只数现存会话自分叉边界之后、用户亲手输入的 user 消息。
     *
     * 回填后轮次进入总账，之后删会话不再让全局轮次回落。
     */
    @Synchronized
    fun backfillRounds(context: Context, sessions: List<ChatSession>) {
        load(context)
        if (roundsBackfilled) return
        var sum = 0
        for (session in sessions) {
            for (message in session.messages.drop(session.inheritedUsage.messages)) {
                if (message.role != "user" || message.programmatic) continue
                val date = dateText(message.createdAt)
                if (date.isEmpty()) continue
                days[date] = (days[date] ?: LedgerUsage()).copy(
                    rounds = (days[date]?.rounds ?: 0) + 1
                )
                sum++
            }
        }
        if (sum > 0) total = total.copy(rounds = total.rounds + sum)
        roundsBackfilled = true
        save(context)
    }

    /** 记一次用户亲手提问：轮次只增不减，删会话不影响。 */
    @Synchronized
    fun recordRound(context: Context) {
        load(context)
        val now = System.currentTimeMillis()
        val date = dateText(now)
        total = total.copy(rounds = total.rounds + 1)
        days[date] = (days[date] ?: LedgerUsage()).copy(rounds = (days[date]?.rounds ?: 0) + 1)
        trimDays()
        if (firstUsedAt <= 0L) firstUsedAt = now
        lastUsedAt = now
        save(context)
    }

    /** 记一次成功请求：总账累加、当天桶累加、首次/最近使用时间跟着更新。 */
    @Synchronized
    fun record(
        context: Context,
        promptTokens: Int,
        completionTokens: Int,
       cacheHitTokens: Int,
        cacheMissTokens: Int,
        composition: LedgerComposition = LedgerComposition(),
        costCny: Double = 0.0
    ) {
        load(context)
        val now = System.currentTimeMillis()
        val date = dateText(now)
        val add = LedgerUsage(
            prompt = promptTokens,
            completion = completionTokens,
            requests = 1,
            cacheHit = cacheHitTokens,
            cacheMiss = cacheMissTokens,
            costCny = costCny,
            composition = composition
        )
        total = total.merge(add)
        days[date] = (days[date] ?: LedgerUsage()).merge(add)
        trimDays()
        if (firstUsedAt <= 0L) firstUsedAt = now
        lastUsedAt = now
        save(context)
    }

    /** 记一次回复的时间账：模型/工具/活跃/六段细分 + 工具调用次数。 */
    @Synchronized
    fun recordTiming(
        context: Context,
        modelMs: Long,
        toolMs: Long,
        activeMs: Long,
        ttftMs: Long,
        genMs: Long,
        reasoningMs: Long,
        textMs: Long,
        toolArgMs: Long,
        genTokens: Int,
        toolCalls: Int
    ) {
        load(context)
        val date = dateText(System.currentTimeMillis())
        val add = LedgerUsage(
            modelMs = modelMs,
            toolMs = toolMs,
            activeMs = activeMs,
            ttftMs = ttftMs,
            genMs = genMs,
            reasoningMs = reasoningMs,
            textMs = textMs,
            toolArgMs = toolArgMs,
            genTokens = genTokens,
            toolCalls = toolCalls
        )
        total = total.merge(add)
        days[date] = (days[date] ?: LedgerUsage()).merge(add)
        trimDays()
        lastUsedAt = System.currentTimeMillis()
        save(context)
    }

    /** 加步数：由调用方在每次回复结束时加**一次**（onRound 里加会重复计）。 */
    @Synchronized
    fun addSteps(context: Context, count: Int) {
        if (count <= 0) return
        load(context)
        val date = dateText(System.currentTimeMillis())
        total = total.copy(steps = total.steps + count)
        days[date] = (days[date] ?: LedgerUsage()).copy(steps = (days[date]?.steps ?: 0) + count)
        trimDays()
        save(context)
    }

    /** 当前值快照；load 之后才有效（ChatViewModel init 里已经读过一次）。 */
    @Synchronized
    fun snapshot(): LedgerSnapshot {
        val todayKey = dateText(System.currentTimeMillis())
        return LedgerSnapshot(
            total = total,
            today = days[todayKey] ?: LedgerUsage(),
            todayDate = todayKey,
            days = HashMap(days),
            firstUsedAt = firstUsedAt,
            lastUsedAt = lastUsedAt
        )
    }

    // ---- 内部 ----

    private fun trimDays() {
        if (days.size <= DAYS_KEEP) return
        val drop = days.keys.sorted().take(days.size - DAYS_KEEP)
        for (key in drop) days.remove(key)
    }

    private fun backupV1(file: File) {
        try {
            val bak = File(file.parentFile, FILE_NAME + ".bak")
            if (!bak.exists()) file.copyTo(bak, overwrite = true)
        } catch (e: Exception) {
            FileLog.log(TAG, "总账 v1 备份失败（不影响读取）：" + e)
        }
    }

    private fun clearMemory() {
        total = LedgerUsage()
        days.clear()
        firstUsedAt = 0L
        lastUsedAt = 0L
    }

    /** 整体覆盖写；先写 .tmp 再 rename，失败退化成直接覆盖，绝不抛。 */
    @Synchronized
    private fun save(context: Context) {
        try {
            val root = JSONObject()
            root.put(KEY_VERSION, 2)
            root.put(KEY_TOTAL, usageToJson(total))
            val dayObj = JSONObject()
            for ((key, usage) in days) dayObj.put(key, usageToJson(usage))
            root.put(KEY_DAYS, dayObj)
            root.put(KEY_FIRST_USED_AT, firstUsedAt)
            root.put(KEY_LAST_USED_AT, lastUsedAt)
            root.put(KEY_COST_BACKFILLED, costBackfilled)
            root.put(KEY_ROUNDS_BACKFILLED, roundsBackfilled)

            val file = storeFile(context)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, FILE_NAME + ".tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: Exception) {
            FileLog.log(TAG, "保存总账失败：" + e)
        }
    }

    private fun usageToJson(usage: LedgerUsage): JSONObject = JSONObject().apply {
        put("prompt", usage.prompt)
        put("completion", usage.completion)
        put("requests", usage.requests)
        put("rounds", usage.rounds)
        put("steps", usage.steps)
        put("cacheHit", usage.cacheHit)
        put("cacheMiss", usage.cacheMiss)
        put("modelMs", usage.modelMs)
        put("toolMs", usage.toolMs)
        put("activeMs", usage.activeMs)
        put("ttftMs", usage.ttftMs)
        put("genMs", usage.genMs)
        put("reasoningMs", usage.reasoningMs)
        put("textMs", usage.textMs)
        put("toolArgMs", usage.toolArgMs)
        put("genTokens", usage.genTokens)
        put("toolCalls", usage.toolCalls)
        put("costCny", usage.costCny)
        if (!usage.composition.isEmpty) {
            put("composition", compositionToJson(usage.composition))
        }
    }

    private fun usageFromJson(obj: JSONObject?): LedgerUsage {
        if (obj == null) return LedgerUsage()
        return LedgerUsage(
            prompt = obj.optInt("prompt", 0),
            completion = obj.optInt("completion", 0),
            requests = obj.optInt("requests", 0),
            rounds = obj.optInt("rounds", 0),
            steps = obj.optInt("steps", 0),
            cacheHit = obj.optInt("cacheHit", 0),
            cacheMiss = obj.optInt("cacheMiss", 0),
            modelMs = obj.optLong("modelMs", 0L),
            toolMs = obj.optLong("toolMs", 0L),
            activeMs = obj.optLong("activeMs", 0L),
            ttftMs = obj.optLong("ttftMs", 0L),
            genMs = obj.optLong("genMs", 0L),
            reasoningMs = obj.optLong("reasoningMs", 0L),
            textMs = obj.optLong("textMs", 0L),
            toolArgMs = obj.optLong("toolArgMs", 0L),
            genTokens = obj.optInt("genTokens", 0),
            toolCalls = obj.optInt("toolCalls", 0),
            costCny = obj.optDouble("costCny", 0.0),
            composition = compositionFromJson(obj.optJSONObject("composition"))
        )
    }

    private fun compositionToJson(c: LedgerComposition): JSONObject = JSONObject().apply {
        put("system", c.system)
        put("skills", c.skills)
        put("tools", c.tools)
        put("user", c.user)
        put("inject", c.inject)
        put("assistant", c.assistant)
        put("toolResults", c.toolResults)
        put("output", c.output)
    }

    private fun compositionFromJson(obj: JSONObject?): LedgerComposition {
        if (obj == null) return LedgerComposition()
        return LedgerComposition(
            system = obj.optInt("system", 0),
            skills = obj.optInt("skills", 0),
            tools = obj.optInt("tools", 0),
            user = obj.optInt("user", 0),
            inject = obj.optInt("inject", 0),
            assistant = obj.optInt("assistant", 0),
            toolResults = obj.optInt("toolResults", 0),
            output = obj.optInt("output", 0)
        )
    }
}
