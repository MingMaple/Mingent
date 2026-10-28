package com.zmf.agent.agent.goal

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONObject
import java.io.File

/** 目标的三个阶段：进行中 / 已暂停 / 已完成。 */
enum class GoalPhase { ACTIVE, PAUSED, DONE }

/**
 * 一条长期目标。
 *
 * objective 是目标正文（模型写给人看的那句话）；rounds 由 AgentLoop 每自动续跑一轮 +1，
 * 落盘保留是为了「切会话回来还记得跑到第几轮」；note 是进度附注，complete 时当作结论。
 */
data class Goal(
    val objective: String,
    val phase: GoalPhase = GoalPhase.ACTIVE,
    val rounds: Int = 0,
    val note: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/** 阶段的中文名：工具回给模型的文本和页面上的状态行共用一套词，避免两处各写一份。 */
fun GoalPhase.label(): String = when (this) {
    GoalPhase.ACTIVE -> "进行中"
    GoalPhase.PAUSED -> "已暂停"
    GoalPhase.DONE -> "已完成"
}

/**
 * 目标落盘：filesDir/appdata/goals.json，结构 {"<sessionId>":{objective,phase,rounds,note,createdAt,updatedAt}}。
 *
 * 每会话最多一条目标，所以用 sessionId 当 key 而不是数组：
 * 覆盖写一条就是「换目标」，没有「同时有两个目标」这种状态。
 *
 * 和 SessionStore 一样整文件覆盖写、先 .tmp 再 rename：写到一半被杀时旧文件仍是完整的。
 * 读写失败只记日志不抛：目标读不出来顶多是「当没有目标」，不能让对话崩掉。
 */
object GoalStore {

    private const val TAG = "goal"
    private const val FILE_NAME = "goals.json"

    /** 会话 id 缺省值：调用方没给就落到这一栏，避免空 key 把 JSON 写坏。 */
    private const val DEFAULT_SESSION = "default"

    private const val KEY_OBJECTIVE = "objective"
    private const val KEY_PHASE = "phase"
    private const val KEY_ROUNDS = "rounds"
    private const val KEY_NOTE = "note"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_UPDATED_AT = "updatedAt"

    /** 落盘位置：filesDir/appdata/goals.json（首次读时由 [AppPaths] 从旧位置迁过来）。 */
    private fun storeFile(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    private fun sessionKey(sessionId: String): String {
        val trimmed = sessionId.trim()
        return if (trimmed.isEmpty()) DEFAULT_SESSION else trimmed
    }

    /**
     * 读某会话的目标。
     *
     * - 文件不存在 / 空 / JSON 坏掉 → null，不抛；
     * - 这一会话没建过目标 → null；
     * - 没有正文的条目当没有（只剩空壳的目标在界面上没法看，也没法让模型推进）。
     */
    @Synchronized
    fun load(context: Context, sessionId: String): Goal? {
        val file = storeFile(context)
        if (!file.exists() || !file.isFile || file.length() == 0L) return null
        val root = try {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) return null
            JSONObject(raw)
        } catch (e: Exception) {
            FileLog.log(TAG, "读取目标失败，按没有目标处理：" + (e.message ?: e.javaClass.simpleName))
            return null
        }
        val obj = root.optJSONObject(sessionKey(sessionId)) ?: return null

        val objective = text(obj, KEY_OBJECTIVE)
        if (objective.isEmpty()) return null
        return Goal(
            objective = objective,
            phase = parsePhase(obj.optString(KEY_PHASE, "")),
            // rounds 只增不减，负数（手改文件 / 旧版本）当 0
            rounds = obj.optInt(KEY_ROUNDS, 0).coerceAtLeast(0),
            note = text(obj, KEY_NOTE),
            createdAt = obj.optLong(KEY_CREATED_AT, 0L),
            updatedAt = obj.optLong(KEY_UPDATED_AT, 0L)
        )
    }

    /**
     * 写某会话的目标（有就覆盖，别的会话的目标原样保留）。
     *
     * createdAt 只在第一次写时补上（之后不再变），updatedAt 每次都刷成当前时刻：
     * 页面要靠它判断「这条目标多久没动过」，让调用方自己填容易填错或忘了更新。
     */
    @Synchronized
    fun save(context: Context, sessionId: String, goal: Goal) {
        val objective = goal.objective.trim()
        if (objective.isEmpty()) {
            FileLog.log(TAG, "目标正文为空，不落盘")
            return
        }
        val now = System.currentTimeMillis()
        val stamped = goal.copy(
            objective = objective,
            rounds = goal.rounds.coerceAtLeast(0),
            createdAt = if (goal.createdAt > 0L) goal.createdAt else now,
            updatedAt = now
        )
        try {
            val file = storeFile(context)
            val root = readRoot(file)
            root.put(sessionKey(sessionId), toJson(stamped))
            writeRoot(file, root)
            FileLog.log(TAG, "保存目标（" + stamped.phase.label() + "，第 " + stamped.rounds + " 轮）：" + objective.take(60))
        } catch (e: Exception) {
            FileLog.log(TAG, "保存目标失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 删掉某会话的目标（用户点「删除目标」时用）；没有就当没这回事。 */
    @Synchronized
    fun clear(context: Context, sessionId: String) {
        try {
            val file = storeFile(context)
            val root = readRoot(file)
            val key = sessionKey(sessionId)
            if (!root.has(key)) return
            root.remove(key)
            writeRoot(file, root)
            FileLog.log(TAG, "删除会话 " + key + " 的目标")
        } catch (e: Exception) {
            FileLog.log(TAG, "删除目标失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 阶段名 → 枚举；不认识的（旧文件 / 手改）一律按 ACTIVE，宁可让用户手动暂停，也不让目标假死。 */
    private fun parsePhase(raw: String): GoalPhase = when (raw.trim().uppercase()) {
        GoalPhase.PAUSED.name -> GoalPhase.PAUSED
        GoalPhase.DONE.name -> GoalPhase.DONE
        else -> GoalPhase.ACTIVE
    }

    private fun toJson(goal: Goal): JSONObject = JSONObject()
        .put(KEY_OBJECTIVE, goal.objective)
        .put(KEY_PHASE, goal.phase.name)
        .put(KEY_ROUNDS, goal.rounds)
        .put(KEY_NOTE, goal.note)
        .put(KEY_CREATED_AT, goal.createdAt)
        .put(KEY_UPDATED_AT, goal.updatedAt)

    /**
     * 读字符串字段：缺字段 / 显式 null / 字符串 "null" 一律当空串。
     * org.json 的 optString 遇到 JSON null 会返回字符串 "null"，拼进界面就是一个 "null"。
     */
    private fun text(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val value = obj.optString(key, "").trim()
        return if (value == "null") "" else value
    }

    /**
     * 读整个文件当 root；文件坏掉就当空对象。
     *
     * 这里是有意「丢别的会话」的：坏文件本来就没有可信内容，从空表重写比让本次写入也失败要好。
     */
    private fun readRoot(file: File): JSONObject {
        if (!file.exists() || !file.isFile || file.length() == 0L) return JSONObject()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: Exception) {
            FileLog.log(TAG, "目标文件损坏，从空表重写：" + (e.message ?: e.javaClass.simpleName))
            JSONObject()
        }
    }

    /** 先写 .tmp 再 rename；rename 失败（个别文件系统）退化成直接覆盖，保证内容落地。 */
    private fun writeRoot(file: File, root: JSONObject) {
        val json = root.toString()
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, FILE_NAME + ".tmp")
        temp.writeText(json, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(json, Charsets.UTF_8)
            temp.delete()
        }
    }
}
