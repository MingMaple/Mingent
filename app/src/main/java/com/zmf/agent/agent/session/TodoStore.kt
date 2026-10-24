package com.zmf.agent.agent.session

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一条待办事项。
 *
 * status 只有三种取值（见 [TodoStore] 的常量），模型也可能传别的词进来，
 * 所以读盘/入库时统一收敛，界面只认这三个值。
 */
data class Todo(
    val content: String,
    val status: String = TodoStore.PENDING
)

/**
 * 待办清单落盘：filesDir/appdata/todos.json，结构 {"<sessionId>":[{"content","status"}]}。
 *
 * 为什么按会话分 key 存一个文件、而不是一会话一文件：
 * 待办最多十几条，会话切换又频繁，一个文件整体覆盖写比反复建目录简单得多；
 * 换一个会话时只要换 key，别会话的清单不会丢。
 *
 * 和 SessionStore / McpServerStore 一样，读写失败只记日志不抛：
 * 待办丢了顶多是「忘了刚才要做啥」，不能让它把整个对话搞崩。
 */
object TodoStore {

    /** 三种合法状态，和 DSH 的 todo 工具取值保持一致（模型看到的就是这几个词）。 */
    const val PENDING = "pending"
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"

    /** 会话 id 缺省值：调用方没给就落到这一栏，避免所有空 id 会话互相覆盖。 */
    private const val DEFAULT_SESSION = "default"

    private const val TAG = "todo"
    private const val FILE_NAME = "todos.json"
    private const val KEY_CONTENT = "content"
    private const val KEY_STATUS = "status"

    /** 落盘位置：filesDir/appdata/todos.json（首次读时由 [AppPaths] 从旧位置迁过来）。 */
    private fun storeFile(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    /** 会话 key 规范化：空串/空白落到 default，防止一个空 key 把 JSON 写坏。 */
    private fun sessionKey(sessionId: String): String {
        val trimmed = sessionId.trim()
        return if (trimmed.isEmpty()) DEFAULT_SESSION else trimmed
    }

    /**
     * 读某会话的待办。
     *
     * - 文件不存在 / 内容为空 / JSON 坏掉 → 空表，不抛；
     * - 这一会话还没有清单 → 空表；
     * - 整条 content 为空白的条目丢掉（工具侧已挡过一轮，这里防手改文件）。
     */
    @Synchronized
    fun load(context: Context, sessionId: String): List<Todo> {
        val file = storeFile(context)
        if (!file.exists() || !file.isFile || file.length() == 0L) return emptyList()
        val array = try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return emptyList()
            JSONObject(text).optJSONArray(sessionKey(sessionId))
        } catch (e: Exception) {
            FileLog.log(TAG, "读取待办失败，按空表处理：" + (e.message ?: e.javaClass.simpleName))
            return emptyList()
        }
        if (array == null) return emptyList()
        val out = ArrayList<Todo>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val content = obj.optString(KEY_CONTENT, "").trim()
            if (content.isEmpty() || content == "null") continue
            out.add(Todo(content, normalizeStatus(obj.optString(KEY_STATUS, ""))))
        }
        return out
    }

    /**
     * 整表覆盖写某会话的待办（其它会话的清单原样保留）。
     *
     * 先写 .tmp 再 rename：写到一半被杀时旧文件还是完整的，下次读到的仍是上一版清单。
     * 目标清单为空时把这个 key 删掉，常年不用的会话不会在文件里留一堆空数组。
     */
    @Synchronized
    fun save(context: Context, sessionId: String, todos: List<Todo>) {
        try {
            val file = storeFile(context)
            val root = readRoot(file)
            val key = sessionKey(sessionId)
            if (todos.isEmpty()) {
                if (!root.has(key)) return
                root.remove(key)
            } else {
                val array = JSONArray()
                for (todo in todos) {
                    val content = todo.content.trim()
                    // 空内容写进去只会让模型下次读回来一头雾水，直接不落
                    if (content.isEmpty()) continue
                    array.put(
                        JSONObject()
                            .put(KEY_CONTENT, content)
                            .put(KEY_STATUS, normalizeStatus(todo.status))
                    )
                }
                root.put(key, array)
            }

            val json = root.toString()
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, FILE_NAME + ".tmp")
            temp.writeText(json, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                // rename 失败（个别文件系统）时退化成直接覆盖，保证内容落地
                file.writeText(json, Charsets.UTF_8)
                temp.delete()
            }
            FileLog.log(TAG, "保存会话 " + key + " 的待办 " + todos.size + " 条")
        } catch (e: Exception) {
            FileLog.log(TAG, "保存待办失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 把任意输入收敛成三种合法状态；不认识的词按 pending 处理（宁可当没做，也别显示成做完了）。 */
    fun normalizeStatus(raw: String): String = when (raw.trim().lowercase()) {
        IN_PROGRESS -> IN_PROGRESS
        COMPLETED -> COMPLETED
        else -> PENDING
    }

    /**
     * 读整个文件当 root；文件坏掉就当空对象。
     *
     * 这里是有意「丢别的会话」的：坏文件本来就没有可信内容，
     * 与其为了保住它让本次写入也失败，不如从空表重新开始。
     */
    private fun readRoot(file: File): JSONObject {
        if (!file.exists() || !file.isFile || file.length() == 0L) return JSONObject()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: Exception) {
            FileLog.log(TAG, "待办文件损坏，从空表重写：" + (e.message ?: e.javaClass.simpleName))
            JSONObject()
        }
    }
}
