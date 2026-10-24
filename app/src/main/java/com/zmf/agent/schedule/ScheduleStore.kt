package com.zmf.agent.schedule

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * 一条定时任务。
 *
 * 三类：[ScheduleStore.KIND_EVERY] 每 N 分钟、[ScheduleStore.KIND_DAILY] 每天 HH:mm、
 * [ScheduleStore.KIND_ONCE] 只在某个时刻跑一次（[atMillis] 是绝对毫秒，跑完自动停用）。
 *
 * 字段全给默认值：手工改坏 / 旧版本写下的文件缺哪个 key 就吃哪个默认值，
 * 一条坏记录不会把整张任务表读成空。
 */
data class ScheduleTask(
    /** 稳定 id，同时当闹钟 PendingIntent 的 requestCode 用；空串交给 [ScheduleStore.upsert] 现发一个。 */
    val id: String = "",
    val title: String = "",
    val kind: String = ScheduleStore.KIND_EVERY,
    /** every 的间隔分钟数；别的类型不用。 */
    val everyMinutes: Int = ScheduleStore.DEFAULT_EVERY_MINUTES,
    /** daily / once 的触发时刻，"HH:mm"。 */
    val timeOfDay: String = ScheduleStore.DEFAULT_TIME_OF_DAY,
    /** once 的绝对触发时刻（毫秒）；别的类型不用。 */
    val atMillis: Long = 0L,
    /** 每次触发发给模型的指令。 */
    val prompt: String = "",
    /** 结果追加到哪条会话；null = 每次新开一条（不会污染用户正在聊的那条）。 */
    val targetSessionId: String? = null,
    /** 停用后不排闹钟，已经错过的那次也不补跑。 */
    val enabled: Boolean = true,
    /** 上次触发时刻，0 = 还没跑过。 */
    val lastRunAt: Long = 0L,
    /** 下一次触发时刻，0 = 不排期（已停用 / 一次性已跑完 / 时刻写坏）。 */
    val nextAt: Long = 0L
)

/**
 * 定时任务落盘：filesDir/appdata/schedules.json。
 *
 * 和会话 / 工作区一样只用一个 JSON 文件整体覆盖写（任务是用户手工点出来的，几条到几十条量级，
 * 不值得引入数据库），写失败只记日志不抛；先写 .tmp 再 rename，避免写到一半被杀留下半截文件。
 *
 * 闹钟本体不在这里：本对象只管「数据 + 下次是哪一刻」，排闹钟在 [ScheduleRunner]。
 */
object ScheduleStore {

    /** 每 N 分钟。 */
    const val KIND_EVERY = "every"

    /** 每天固定时刻。 */
    const val KIND_DAILY = "daily"

    /** 只跑一次。 */
    const val KIND_ONCE = "once"

    const val DEFAULT_EVERY_MINUTES = 60

    const val DEFAULT_TIME_OF_DAY = "09:00"

    /** 间隔下限：1 分钟。 */
    const val MIN_EVERY_MINUTES = 1

    /** 间隔上限：7 天。 */
    const val MAX_EVERY_MINUTES = 7 * 24 * 60

    private const val MINUTE = 60_000L

    private const val TAG = "schedule"
    private const val FILE_NAME = "schedules.json"

    private const val KEY_TASKS = "tasks"
    private const val KEY_ID = "id"
    private const val KEY_TITLE = "title"
    private const val KEY_KIND = "kind"
    private const val KEY_EVERY_MINUTES = "everyMinutes"
    private const val KEY_TIME_OF_DAY = "timeOfDay"
    private const val KEY_AT_MILLIS = "atMillis"
    private const val KEY_PROMPT = "prompt"
    private const val KEY_TARGET_SESSION_ID = "targetSessionId"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_RUN_AT = "lastRunAt"
    private const val KEY_NEXT_AT = "nextAt"

    /** 上一次发出的 id，保证同一毫秒内连点两次「保存」也不会撞 id。 */
    @Volatile
    private var lastIssuedId: Long = 0L

    /** 落盘位置：filesDir/appdata/schedules.json（首次读时由 [AppPaths] 从旧位置迁过来）。 */
    private fun storeFile(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    // ---- 对外 ----

    /** 读全部任务；文件不存在、空、JSON 坏掉，一律返回空表，绝不抛（定时任务是附加功能，坏了不能带崩入口）。 */
    fun load(context: Context): List<ScheduleTask> {
        val file = storeFile(context)
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) emptyList() else parse(text)
        } catch (e: Exception) {
            FileLog.log(TAG, "读取定时任务失败，从空表开始：" + e)
            emptyList()
        }
    }

    /** 整体覆盖写；失败只记日志。 */
    fun save(context: Context, tasks: List<ScheduleTask>) {
        try {
            val array = JSONArray()
            for (task in tasks) array.put(taskToJson(task))
            val root = JSONObject()
            root.put(KEY_TASKS, array)
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
            FileLog.log(TAG, "保存定时任务失败：" + e)
        }
    }

    /**
     * 写一条（id 空就先发号），返回真正落盘的副本。
     *
     * 返回值就是调用方该拿去排闹钟的那份：id / nextAt 都补全了，不用再猜。
     * 现读现写：整份覆盖写的文件和界面共用，读得越新丢更新越少。
     */
    @Synchronized
    fun upsert(context: Context, task: ScheduleTask): ScheduleTask {
        val stored = if (task.id.isEmpty()) task.copy(id = nextId()) else task
        val list = load(context)
        val index = list.indexOfFirst { it.id == stored.id }
        val merged = if (index >= 0) {
            list.toMutableList().also { it[index] = stored }
        } else {
            list + stored
        }
        save(context, merged)
        return stored
    }

    /** 删一条并返回剩下的表（界面直接拿去刷新）。 */
    @Synchronized
    fun remove(context: Context, id: String): List<ScheduleTask> {
        val list = load(context).filterNot { it.id == id }
        save(context, list)
        return list
    }

    /**
     * 按任务自身规则算「[now] 之后」的下一次触发时刻；算不出来返回 0。
     *
     * 这里刻意不看 [ScheduleTask.nextAt]：调用方要么在新建 / 重排，要么刚跑完，
     * 都需要「从现在起下一次」，而不是「上次排的那一次」。
     */
    fun nextAfter(task: ScheduleTask, now: Long = System.currentTimeMillis()): Long =
        when (task.kind) {
            KIND_DAILY -> nextTimeOfDay(task.timeOfDay, now)
            // 一次性任务：到点之后就没有下一次了，返回 0 让调用方去停用
            KIND_ONCE -> if (task.atMillis > now) task.atMillis else 0L
            // 未知 kind 一律按 every 处理（读坏数据时至少还能跑）
            else -> now + task.everyMinutes.coerceIn(MIN_EVERY_MINUTES, MAX_EVERY_MINUTES) * MINUTE
        }

    /**
     * "HH:mm" 的下一个到达时刻（今天的这一刻已经过了就顺延到明天）；格式非法返回 0。
     *
     * daily 与 once 共用：once 落盘时也是靠它把 "HH:mm" 换算成绝对毫秒 atMillis。
     */
    fun nextTimeOfDay(raw: String, now: Long = System.currentTimeMillis()): Long {
        val parsed = parseTimeOfDay(raw) ?: return 0L
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, parsed.first)
        cal.set(Calendar.MINUTE, parsed.second)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // 用 Calendar 加一天而不是 +86400000：跨夏令时地区也不会偏一小时
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    // ---- 序列化 ----

    private fun taskToJson(task: ScheduleTask): JSONObject {
        val obj = JSONObject()
        obj.put(KEY_ID, task.id)
        obj.put(KEY_TITLE, task.title)
        obj.put(KEY_KIND, task.kind)
        obj.put(KEY_EVERY_MINUTES, task.everyMinutes)
        obj.put(KEY_TIME_OF_DAY, task.timeOfDay)
        obj.put(KEY_AT_MILLIS, task.atMillis)
        obj.put(KEY_PROMPT, task.prompt)
        // null 不写这个 key：读回来 has() 为 false 就是 null，文件也更小（同 SessionStore.workspaceId）
        task.targetSessionId?.takeIf { it.isNotEmpty() }?.let { obj.put(KEY_TARGET_SESSION_ID, it) }
        obj.put(KEY_ENABLED, task.enabled)
        obj.put(KEY_LAST_RUN_AT, task.lastRunAt)
        obj.put(KEY_NEXT_AT, task.nextAt)
        return obj
    }

    private fun parse(text: String): List<ScheduleTask> {
        val array = JSONObject(text).optJSONArray(KEY_TASKS) ?: return emptyList()
        val list = ArrayList<ScheduleTask>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString(KEY_ID, "")
            // id 空就没法排闹钟、也没法删，这条留着只会变成幽灵任务
            if (id.isEmpty()) continue
            list.add(
                ScheduleTask(
                    id = id,
                    title = obj.optString(KEY_TITLE, ""),
                    kind = knownKind(obj.optString(KEY_KIND, KIND_EVERY)),
                    everyMinutes = normalizeMinutes(obj.optInt(KEY_EVERY_MINUTES, DEFAULT_EVERY_MINUTES)),
                    timeOfDay = normalizeTimeOfDay(obj.optString(KEY_TIME_OF_DAY, DEFAULT_TIME_OF_DAY)),
                    atMillis = obj.optLong(KEY_AT_MILLIS, 0L),
                    prompt = obj.optString(KEY_PROMPT, ""),
                    targetSessionId = obj.stringOrNull(KEY_TARGET_SESSION_ID),
                    enabled = obj.optBoolean(KEY_ENABLED, true),
                    lastRunAt = obj.optLong(KEY_LAST_RUN_AT, 0L),
                    nextAt = obj.optLong(KEY_NEXT_AT, 0L)
                )
            )
        }
        return list
    }

    /** 不认识的 kind 当 every：比整条丢掉好，用户至少能在列表里看到并改。 */
    private fun knownKind(raw: String): String =
        if (raw == KIND_EVERY || raw == KIND_DAILY || raw == KIND_ONCE) raw else KIND_EVERY

    /** 0 / 负数 / 超界的间隔统一收进合法范围。 */
    private fun normalizeMinutes(value: Int): Int =
        if (value <= 0) DEFAULT_EVERY_MINUTES else value.coerceIn(MIN_EVERY_MINUTES, MAX_EVERY_MINUTES)

    /** 时刻读回来就规整成 "HH:mm"（补零 / 去空格）；写坏了退回默认值，界面上不会出现 "9" 这种半截输入。 */
    private fun normalizeTimeOfDay(raw: String): String {
        val parsed = parseTimeOfDay(raw) ?: return DEFAULT_TIME_OF_DAY
        return String.format(Locale.US, "%02d:%02d", parsed.first, parsed.second)
    }

    private fun parseTimeOfDay(raw: String): Pair<Int, Int>? {
        val parts = raw.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    /** 读可空字符串：key 不存在 / 是 JSON null / 空串，都当 null（顺便防住 org.json 的 "null" 字符串）。 */
    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key, "")
        return if (value.isEmpty() || value == "null") null else value
    }

    /** 时间戳 id；同一毫秒内再发一次就 +1。 */
    private fun nextId(): String {
        val now = System.currentTimeMillis()
        val value = if (now > lastIssuedId) now else lastIssuedId + 1
        lastIssuedId = value
        return value.toString()
    }
}
