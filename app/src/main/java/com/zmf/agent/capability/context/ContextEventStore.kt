package com.zmf.agent.capability.context

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 摘要/压缩相关的三类事件：压缩了、清了摘要、压缩失败退兜底。 */
enum class ContextEventKind { COMPRESS, CLEAR, FALLBACK }

/**
 * 一条上下文事件（只读记录，给人看）。
 *
 * 为什么这些数值都要保留：用户看到水位下降时，最关心的是「折叠了多少条、从第几条起发送原文、
 * 兜底保留了几轮」，而非「下降了多少」：缺少这些数值，「上下文被压缩」就只是推测。
 *
 * @param at 事件发生时刻（毫秒）
 * @param ok 本次操作是否成功（只有 COMPRESS 会用到 false）
 * @param fromMessages 压缩前本条会话的消息数
 * @param foldedMessages 被折叠进摘要的条数
 * @param boundary 摘要分界（消息下标，从这条起发原文）
 * @param keptRounds 退兜底时保留的轮数
 * @param note 失败原因等短说明；空串 = 没有
 */
data class ContextEvent(
    val at: Long,
    val kind: ContextEventKind,
    val ok: Boolean = true,
    val fromMessages: Int = 0,
    val foldedMessages: Int = 0,
    val boundary: Int = 0,
    val keptRounds: Int = 0,
    val note: String = ""
)

/**
 * 上下文事件落盘：filesDir/appdata/context_events.json，结构 {"<sessionId>": [事件...]}。
 *
 * 为什么每会话仅保留 50 条：这是供人工查看的排查记录，并非审计日志。保留过多既无人查阅，
 * 也会增大整份 JSON 与备份体积；旧记录自动丢弃，最新记录始终保留。
 *
 * 为什么损坏文件按空处理：内容属于可再生的观察数据，丢失不影响对话本身；
 * 下一次 append 会整体重写，损坏文件随之修复。
 */
object ContextEventStore {

    private const val TAG = "ctx-event"
    private const val FILE_NAME = "context_events.json"

    /** 每会话最多留多少条（超出丢最旧）。 */
    private const val MAX_PER_SESSION = 50

    /** 会话 id 缺省值：调用方没给就落到这一栏，避免空 key 把 JSON 写坏。 */
    private const val DEFAULT_SESSION = "default"

    private const val KEY_AT = "at"
    private const val KEY_KIND = "kind"
    private const val KEY_OK = "ok"
    private const val KEY_FROM = "fromMessages"
    private const val KEY_FOLDED = "foldedMessages"
    private const val KEY_BOUNDARY = "boundary"
    private const val KEY_KEPT = "keptRounds"
    private const val KEY_NOTE = "note"

    private fun storeFile(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    private fun sessionKey(sessionId: String): String {
        val trimmed = sessionId.trim()
        return if (trimmed.isEmpty()) DEFAULT_SESSION else trimmed
    }

    /** 读某会话的全部事件，按时间正序（供页面自上而下绘制）。文件不存在 / 为空 / 损坏时均返回空列表，不抛异常。 */
    @Synchronized
    fun load(context: Context, sessionId: String): List<ContextEvent> {
        val file = storeFile(context)
        if (!file.exists() || !file.isFile || file.length() == 0L) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return emptyList()
            val array = JSONObject(text).optJSONArray(sessionKey(sessionId)) ?: return emptyList()
            val list = ArrayList<ContextEvent>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val event = parse(item) ?: continue
                list.add(event)
            }
            // 写入时即为正序；排序仅用于兼容手动修改 / 旧文件 / 备份合并等情况
            list.sortedBy { it.at }
        } catch (e: Exception) {
            FileLog.log(TAG, "读取上下文事件失败，按空列表处理：" + (e.message ?: e.javaClass.simpleName))
            emptyList()
        }
    }

    /** 追加一条；超过 [MAX_PER_SESSION] 条时丢弃最旧记录。写失败仅记日志：观察数据不应导致对话中断。 */
    @Synchronized
    fun append(context: Context, sessionId: String, event: ContextEvent) {
        val key = sessionKey(sessionId)
        try {
            val file = storeFile(context)
            val root = readRoot(file)
            val existing = root.optJSONArray(key) ?: JSONArray()
            val list = ArrayList<JSONObject>(existing.length() + 1)
            for (i in 0 until existing.length()) existing.optJSONObject(i)?.let { list.add(it) }
            list.add(toJson(event))
            // 只留最新的 50 条：subList 的起点 = 超出的条数
            val start = (list.size - MAX_PER_SESSION).coerceAtLeast(0)
            val trimmed = JSONArray()
            for (i in start until list.size) trimmed.put(list[i])
            root.put(key, trimmed)
            writeRoot(file, root)
        } catch (e: Exception) {
            FileLog.log(TAG, "写入上下文事件失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 清掉某会话的事件；一条都不剩时删除文件，避免遗留空对象。 */
    @Synchronized
    fun clear(context: Context, sessionId: String) {
        try {
            val file = storeFile(context)
            if (!file.exists()) return
            val root = readRoot(file)
            val key = sessionKey(sessionId)
            if (!root.has(key)) return
            root.remove(key)
            if (root.length() == 0) {
                file.delete()
            } else {
                writeRoot(file, root)
            }
        } catch (e: Exception) {
            FileLog.log(TAG, "清除上下文事件失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * 读整个文件作为 root；文件损坏时按空对象处理。
     *
     * 有意丢弃其他会话：损坏文件本来就没有可信内容，从空表重写优于使本次写入一并失败。
     */
    private fun readRoot(file: File): JSONObject {
        if (!file.exists() || !file.isFile || file.length() == 0L) return JSONObject()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: Exception) {
            FileLog.log(TAG, "事件文件损坏，从空表重写：" + (e.message ?: e.javaClass.simpleName))
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

    private fun parse(obj: JSONObject): ContextEvent? {
        val kind = when (obj.optString(KEY_KIND, "").uppercase()) {
            ContextEventKind.COMPRESS.name -> ContextEventKind.COMPRESS
            ContextEventKind.CLEAR.name -> ContextEventKind.CLEAR
            ContextEventKind.FALLBACK.name -> ContextEventKind.FALLBACK
            // 无法识别的类型（旧版本 / 手动修改）直接丢弃：页面无法渲染，保留亦无意义
            else -> return null
        }
        return ContextEvent(
            at = obj.optLong(KEY_AT, 0L),
            kind = kind,
            ok = obj.optBoolean(KEY_OK, true),
            fromMessages = obj.optInt(KEY_FROM, 0),
            foldedMessages = obj.optInt(KEY_FOLDED, 0),
            boundary = obj.optInt(KEY_BOUNDARY, 0),
            keptRounds = obj.optInt(KEY_KEPT, 0),
            note = obj.optString(KEY_NOTE, "").takeIf { it.isNotEmpty() && it != "null" }.orEmpty()
        )
    }

    private fun toJson(event: ContextEvent): JSONObject {
        val obj = JSONObject()
        obj.put(KEY_AT, event.at)
        obj.put(KEY_KIND, event.kind.name)
        obj.put(KEY_OK, event.ok)
        obj.put(KEY_FROM, event.fromMessages)
        obj.put(KEY_FOLDED, event.foldedMessages)
        obj.put(KEY_BOUNDARY, event.boundary)
        obj.put(KEY_KEPT, event.keptRounds)
        // 空备注不写入 key：绝大多数事件没有说明，写入大量空串只会增大文件体积
        if (event.note.isNotEmpty()) obj.put(KEY_NOTE, event.note)
        return obj
    }
}
