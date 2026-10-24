package com.zmf.agent.agent.stats

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局总账的一份只读快照：统计页直接读它，不改 [UsageLedger] 内部状态。
 */
data class LedgerSnapshot(
    val totalPromptTokens: Int = 0,
    val totalCompletionTokens: Int = 0,
    val totalRequests: Int = 0,
    val totalSteps: Int = 0,
    val todayPromptTokens: Int = 0,
    val todayCompletionTokens: Int = 0,
    val todayRequests: Int = 0,
    val todayDate: String = "",
    val firstUsedAt: Long = 0L,
    val lastUsedAt: Long = 0L
)

/**
 * 全局用量总账（stats.json）：**只增不减的历史总账**。
 *
 * 口径（用户定死，别改）：
 * - 删会话 / 归档 / 清工作区都**不动**这里；只有真的发生模型请求才往上加。
 * - 本会话的累计另存在 sessions.json 的会话记录里，两套账互不覆盖。
 * - 今日三项跨天自动归零（todayDate 不是今天就把 today* 清零）。
 *
 * 落盘用 .tmp 再 rename（跟 SessionStore 一个写法）；坏文件 / 缺字段一律回落 0，绝不抛。
 */
object UsageLedger {

    private const val FILE_NAME = "stats.json"
    private const val TAG = "stats"

    private const val KEY_TOTAL_PROMPT = "totalPromptTokens"
    private const val KEY_TOTAL_COMPLETION = "totalCompletionTokens"
    private const val KEY_TOTAL_REQUESTS = "totalRequests"
    private const val KEY_TOTAL_STEPS = "totalSteps"
    private const val KEY_TODAY_PROMPT = "todayPromptTokens"
    private const val KEY_TODAY_COMPLETION = "todayCompletionTokens"
    private const val KEY_TODAY_REQUESTS = "todayRequests"
    private const val KEY_TODAY_DATE = "todayDate"
    private const val KEY_FIRST_USED_AT = "firstUsedAt"
    private const val KEY_LAST_USED_AT = "lastUsedAt"

    /** 内存里是否已经读过盘；进程重启后由 init 里的 load 置位。 */
    private var loaded = false

    private var totalPromptTokens = 0
    private var totalCompletionTokens = 0
    private var totalRequests = 0
    private var totalSteps = 0
    private var todayPromptTokens = 0
    private var todayCompletionTokens = 0
    private var todayRequests = 0
    private var todayDate = ""
    private var firstUsedAt = 0L
    private var lastUsedAt = 0L

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
                val obj = JSONObject(file.readText(Charsets.UTF_8))
                totalPromptTokens = obj.optInt(KEY_TOTAL_PROMPT, 0)
                totalCompletionTokens = obj.optInt(KEY_TOTAL_COMPLETION, 0)
                totalRequests = obj.optInt(KEY_TOTAL_REQUESTS, 0)
                totalSteps = obj.optInt(KEY_TOTAL_STEPS, 0)
                todayPromptTokens = obj.optInt(KEY_TODAY_PROMPT, 0)
                todayCompletionTokens = obj.optInt(KEY_TODAY_COMPLETION, 0)
                todayRequests = obj.optInt(KEY_TODAY_REQUESTS, 0)
                todayDate = obj.optString(KEY_TODAY_DATE, "")
                firstUsedAt = obj.optLong(KEY_FIRST_USED_AT, 0L)
                lastUsedAt = obj.optLong(KEY_LAST_USED_AT, 0L)
            } catch (e: Exception) {
                FileLog.log(TAG, "读取总账失败，从 0 开始：" + e)
                clearMemory()
            }
        }
        loaded = true
        // 读回来先过一遍跨天：隔天打开 App 时今日就是 0，不等到下一次请求
        rollDay()
    }

    /** 记一次真实请求：总账累加、今日累加（跨天先归零）、首次/最近使用时间跟着更新。 */
    @Synchronized
    fun record(context: Context, promptTokens: Int, completionTokens: Int) {
        load(context)
        rollDay()
        val now = System.currentTimeMillis()
        totalPromptTokens += promptTokens
        totalCompletionTokens += completionTokens
        totalRequests += 1
        todayPromptTokens += promptTokens
        todayCompletionTokens += completionTokens
        todayRequests += 1
        if (firstUsedAt <= 0L) firstUsedAt = now
        lastUsedAt = now
        save(context)
    }

    /** 加步数：由调用方在每次回复结束时加**一次**（onRound 里加会重复计）。 */
    @Synchronized
    fun addSteps(context: Context, count: Int) {
        if (count <= 0) return
        load(context)
        rollDay()
        totalSteps += count
        save(context)
    }

    /** 当前值快照；load 之后才有效（ChatViewModel init 里已经读过一次）。 */
    @Synchronized
    fun snapshot(): LedgerSnapshot = LedgerSnapshot(
        totalPromptTokens = totalPromptTokens,
        totalCompletionTokens = totalCompletionTokens,
        totalRequests = totalRequests,
        totalSteps = totalSteps,
        todayPromptTokens = todayPromptTokens,
        todayCompletionTokens = todayCompletionTokens,
        todayRequests = todayRequests,
        todayDate = todayDate,
        firstUsedAt = firstUsedAt,
        lastUsedAt = lastUsedAt
    )

    // ---- 内部 ----

    /** 跨天归零：todayDate 不是今天就把今日三项清零（总账一分不动）。 */
    private fun rollDay() {
        val today = dateText(System.currentTimeMillis())
        if (todayDate == today) return
        todayDate = today
        todayPromptTokens = 0
        todayCompletionTokens = 0
        todayRequests = 0
    }

    private fun clearMemory() {
        totalPromptTokens = 0
        totalCompletionTokens = 0
        totalRequests = 0
        totalSteps = 0
        todayPromptTokens = 0
        todayCompletionTokens = 0
        todayRequests = 0
        todayDate = ""
        firstUsedAt = 0L
        lastUsedAt = 0L
    }

    /** 整体覆盖写；先写 .tmp 再 rename，失败退化成直接覆盖，绝不抛。 */
    @Synchronized
    private fun save(context: Context) {
        try {
            val obj = JSONObject()
            obj.put(KEY_TOTAL_PROMPT, totalPromptTokens)
            obj.put(KEY_TOTAL_COMPLETION, totalCompletionTokens)
            obj.put(KEY_TOTAL_REQUESTS, totalRequests)
            obj.put(KEY_TOTAL_STEPS, totalSteps)
            obj.put(KEY_TODAY_PROMPT, todayPromptTokens)
            obj.put(KEY_TODAY_COMPLETION, todayCompletionTokens)
            obj.put(KEY_TODAY_REQUESTS, todayRequests)
            obj.put(KEY_TODAY_DATE, todayDate)
            obj.put(KEY_FIRST_USED_AT, firstUsedAt)
            obj.put(KEY_LAST_USED_AT, lastUsedAt)
            val json = obj.toString()

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
            FileLog.log(TAG, "保存总账失败：" + e)
        }
    }
}
