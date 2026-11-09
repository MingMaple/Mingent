package com.zmf.agent.feature.schedule

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
 * [ScheduleStore.KIND_ONCE] 只在某个时刻执行一次（[atMillis] 是绝对毫秒，执行完成后自动停用）。
 *
 * 字段均提供默认值：手工修改损坏或旧版本文件缺少某个 key 时使用对应默认值，
 * 单条异常记录不会导致整张任务表读取为空。
 */
data class ScheduleTask(
    /** 稳定 id，同时用作闹钟 PendingIntent 的 requestCode；空字符串由 [ScheduleStore.upsert] 生成。 */
    val id: String = "",
    val title: String = "",
    val kind: String = ScheduleStore.KIND_EVERY,
    /** every 类型的间隔分钟数；其他类型不使用。 */
    val everyMinutes: Int = ScheduleStore.DEFAULT_EVERY_MINUTES,
    /** daily / once 的触发时刻，"HH:mm"。 */
    val timeOfDay: String = ScheduleStore.DEFAULT_TIME_OF_DAY,
    /** once 类型的绝对触发时刻（毫秒）；其他类型不使用。 */
    val atMillis: Long = 0L,
    /** 每次触发发给模型的指令。 */
    val prompt: String = "",
    /** 结果追加到哪条会话；null 表示首次触发时新建一条（之后固定在该会话中发送）。 */
    val targetSessionId: String? = null,
    /**
     * 归属工作区；null 表示默认工作区。
     *
     * 任务在后台执行时需要知道用哪个目录当运行根：早期实现按目标会话所属工作区取，
     * 会话被移到别处后目录会跟着变，因此改为任务自身记录归属，旧任务迁移为默认工作区。
     */
    val workspaceId: String? = null,
    /** 停用后不设置闹钟，已错过的触发也不补执行。 */
    val enabled: Boolean = true,
    /** 上次触发时刻，0 表示尚未执行。 */
    val lastRunAt: Long = 0L,
    /** 下一次触发时刻，0 表示不排期（已停用、一次性任务已执行完或时刻格式错误）。 */
    val nextAt: Long = 0L,
    /**
     * 是否改用闹钟式排期。
     *
     * 本机一旦发现本任务的触发时间被系统大幅推迟（见 [ScheduleRunner] 的迟到判定），
     * 就置为 true，此后改用 `setAlarmClock` 排期：系统不会把它推迟，代价是状态栏出现闹钟图标。
     * 正常机型永远不会被置位，也就不会出现该图标。
     */
    val clockAlarm: Boolean = false
)

/**
 * 定时任务持久化：filesDir/appdata/schedules.json。
 *
 * 与会话、工作区相同，仅使用一个 JSON 文件整体覆盖写入（任务由用户手工创建，数量在几条到几十条量级，
 * 无需引入数据库），写入失败只记录日志不抛出；先写 .tmp 再 rename，避免写入过程中被终止而留下不完整文件。
 *
 * 闹钟本身不在此处管理：本对象只负责数据与下次触发时刻，设置闹钟由 [ScheduleRunner] 完成。
 * 所有读写都在调用线程同步完成并整份重写文件，调用方须在 IO 线程执行。
 */
object ScheduleStore {

    /** 每 N 分钟。 */
    const val KIND_EVERY = "every"

    /** 每天固定时刻。 */
    const val KIND_DAILY = "daily"

    /** 仅执行一次。 */
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
    private const val KEY_WORKSPACE_ID = "workspaceId"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_RUN_AT = "lastRunAt"
    private const val KEY_NEXT_AT = "nextAt"
    private const val KEY_CLOCK_ALARM = "clockAlarm"

    /** 上一次生成的 id，保证同一毫秒内连续两次保存不会产生重复 id。 */
    @Volatile
    private var lastIssuedId: Long = 0L

    /** 持久化位置：filesDir/appdata/schedules.json（首次读取时由 [AppPaths] 从旧位置迁移）。 */
    private fun storeFile(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    // ---- 对外 ----

    /** 读取全部任务；文件不存在、为空或 JSON 解析失败时均返回空表，不抛出异常（定时任务属于附加功能，异常不应影响入口）。 */
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

    /** 整体覆盖写入；失败仅记录日志。 */
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
                // rename 失败（个别文件系统）时改为直接覆盖，保证内容写入
                file.writeText(json, Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: Exception) {
            FileLog.log(TAG, "保存定时任务失败：" + e)
        }
    }

    /**
     * 写入一条记录（id 为空时先生成），返回实际持久化的副本。
     *
     * 返回值即调用方用于设置闹钟的版本：id 与 nextAt 均已补全。
     * 现读现写：整份覆盖写的文件与界面共用，读取越新则丢失的更新越少。
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

    /** 删除一条并返回剩余列表（界面可直接用于刷新）。 */
    @Synchronized
    fun remove(context: Context, id: String): List<ScheduleTask> {
        val list = load(context).filterNot { it.id == id }
        save(context, list)
        return list
    }

    /**
     * 按任务自身规则计算 [now] 之后的下一次触发时刻；无法计算时返回 0。
     *
     * 此处刻意不参考 [ScheduleTask.nextAt] 以外的内容：调用方无论是新建、重排还是刚执行完，
     * 都需要从当前时刻起的下一次，而非上次安排的时刻。
     */
    fun nextAfter(task: ScheduleTask, now: Long = System.currentTimeMillis()): Long =
        when (task.kind) {
            KIND_DAILY -> nextTimeOfDay(task.timeOfDay, now)
            // 一次性任务：到达触发时刻后不存在下一次，返回 0 由调用方停用
            KIND_ONCE -> if (task.atMillis > now) task.atMillis else 0L
            // 未知 kind 统一按 every 处理（数据损坏时仍可执行）
            else -> nextEvery(task, now)
        }

    /**
     * 周期任务：以计划时刻而非实际触发时刻为锚计算下一次。
     *
     * 真机实测（`dumpsys alarm`）：`setExactAndAllowWhileIdle` 在本机 ROM 上被降级为 45 秒窗口的
     * 模糊闹钟（`exactAllowReason=compat`），实际稳定延迟约 45 秒；若以实际触发时刻加间隔计算，
     * 延迟会逐轮累加（「每 1 分钟」实测为 1 分 45 秒）。锚定计划时刻后周期恒等于配置值，
     * 单次延迟仍受系统窗口限制。设备长时间休眠会跨过多个周期：仅跳到 [now] 之后最近的时刻，不补执行。
     */
    private fun nextEvery(task: ScheduleTask, now: Long): Long {
        val step = task.everyMinutes.coerceIn(MIN_EVERY_MINUTES, MAX_EVERY_MINUTES) * MINUTE
        // nextAt 为 0 表示新任务或重新启用，没有可锚定的计划时刻，以当前时刻起算一次
        val anchor = if (task.nextAt > 0L) task.nextAt else now
        var next = anchor + step
        if (next <= now) {
            // 相差超过一个周期（休眠或强制停止）：跳到当前时刻之后最近的周期，不连续补执行多次
            next += ((now - next) / step + 1) * step
        }
        return next
    }

    /**
     * "HH:mm" 的下一个到达时刻（当天的时刻已过则顺延到次日）；格式非法时返回 0。
     *
     * daily 与 once 共用：once 持久化时同样通过它把 "HH:mm" 换算为绝对毫秒 atMillis。
     */
    fun nextTimeOfDay(raw: String, now: Long = System.currentTimeMillis()): Long {
        val parsed = parseTimeOfDay(raw) ?: return 0L
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, parsed.first)
        cal.set(Calendar.MINUTE, parsed.second)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // 使用 Calendar 增加一天而非 +86400000：跨夏令时地区不会产生一小时偏差
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
        // null 时不写入该 key：读取时 has() 为 false 即为 null，文件也更小（与 SessionStore.workspaceId 相同）
        task.targetSessionId?.takeIf { it.isNotEmpty() }?.let { obj.put(KEY_TARGET_SESSION_ID, it) }
        // null（默认工作区）时不写入该键，旧任务读取为 null 即落在默认工作区
        task.workspaceId?.takeIf { it.isNotEmpty() }?.let { obj.put(KEY_WORKSPACE_ID, it) }
        obj.put(KEY_ENABLED, task.enabled)
        obj.put(KEY_LAST_RUN_AT, task.lastRunAt)
        obj.put(KEY_NEXT_AT, task.nextAt)
        if (task.clockAlarm) obj.put(KEY_CLOCK_ALARM, true)
        return obj
    }

    private fun parse(text: String): List<ScheduleTask> {
        val array = JSONObject(text).optJSONArray(KEY_TASKS) ?: return emptyList()
        val list = ArrayList<ScheduleTask>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString(KEY_ID, "")
            // id 为空时无法设置闹钟，也无法删除，保留该记录只会形成不可管理的任务
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
                    // 旧任务没有该键：读取为 null，即默认工作区
                    workspaceId = obj.stringOrNull(KEY_WORKSPACE_ID),
                    enabled = obj.optBoolean(KEY_ENABLED, true),
                    lastRunAt = obj.optLong(KEY_LAST_RUN_AT, 0L),
                    nextAt = obj.optLong(KEY_NEXT_AT, 0L),
                    clockAlarm = obj.optBoolean(KEY_CLOCK_ALARM, false)
                )
            )
        }
        return list
    }

    /** 无法识别的 kind 按 every 处理：优于整条丢弃，用户至少能在列表中看到并修改。 */
    private fun knownKind(raw: String): String =
        if (raw == KIND_EVERY || raw == KIND_DAILY || raw == KIND_ONCE) raw else KIND_EVERY

    /** 0、负数或越界的间隔统一修正到合法范围。 */
    private fun normalizeMinutes(value: Int): Int =
        if (value <= 0) DEFAULT_EVERY_MINUTES else value.coerceIn(MIN_EVERY_MINUTES, MAX_EVERY_MINUTES)

    /** 读取的时刻统一规整为 "HH:mm"（补零并去除空格）；格式错误时退回默认值，界面不会出现 "9" 这类不完整输入。 */
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

    /** 读取可空字符串：key 不存在、JSON null 或空字符串均视为 null（同时兼容 org.json 的 "null" 字符串）。 */
    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key, "")
        return if (value.isEmpty() || value == "null") null else value
    }

    /** 时间戳 id；同一毫秒内再次生成时递增 1。 */
    private fun nextId(): String {
        val now = System.currentTimeMillis()
        val value = if (now > lastIssuedId) now else lastIssuedId + 1
        lastIssuedId = value
        return value.toString()
    }
}
