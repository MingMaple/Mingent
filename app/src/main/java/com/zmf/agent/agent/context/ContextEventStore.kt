package com.zmf.agent.agent.context

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
 * 为什么这些数都要留下来：用户看到水位下降时最想知道的不是「降了多少」，而是「折叠了多少条、从第几条起发原文、
 * 兜底留了几轮」：没有这几个数，「上下文被压掉了」就只是一句猜测。
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
 * 为什么只给每会话留 50 条：这是给人看的排查记录，不是审计日志。留太多既没人翻，
 * 还会把整份 JSON 撑大、备份也跟着变大；旧的自动丢，最新的永远在。
 *
 * 为什么坏文件当空：内容是可再生成的观察数据，丢了不影响对话本身；
 * 下一次 append 会整体重写，坏文件自然被修好。
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

    /** 读某会话的全部事件，按时间正序（给页面直接从上往下画）。文件不存在 / 空 / 坏掉一律空列表，绝不抛。 */
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
            // 写进去时就是正序；排序只是兜住手改 / 旧文件 / 备份合并这类情况
            list.sortedBy { it.at }
        } catch (e: Exception) {
            FileLog.log(TAG, "读取上下文事件失败，按空列表处理：" + (e.message ?: e.javaClass.simpleName))
            emptyList()
        }
    }

    /** 追加一条；超过 [MAX_PER_SESSION] 条时丢最旧的。写失败只记日志：观察数据不值得让对话崩。 */
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

    /** 清掉某会话的事件；一条都不剩时把文件也删了，别留个常年躺着的 {}。 */
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
     * 读整个文件当 root；文件坏掉就当空对象。
     *
     * 有意「丢别的会话」：坏文件本来就没有可信内容，从空表重写比让本次写入也失败要好。
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
            // 不认识的类型（旧版本 / 手改）直接丢：页面画不出来，留着也没有意义
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
        // 空备注不落 key：绝大多数事件没有说明，写一堆 "" 只是把文件撑大
        if (event.note.isNotEmpty()) obj.put(KEY_NOTE, event.note)
        return obj
    }
}
