package com.zmf.agent.core.store

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
 * 待办最多十几条，会话切换频繁，单文件整体覆盖写比反复创建目录更简单；
 * 切换会话只需更换 key，其他会话的清单不受影响。
 *
 * 和 SessionStore / McpServerStore 一样，读写失败只记日志不抛异常：
 * 待办丢失仅影响任务提示，不得导致对话中断。
 */
object TodoStore {

    /** 三种合法状态，取值与模型看到的词一致。 */
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

    /** 会话 key 规范化：空串或空白归入 default，避免空 key 破坏 JSON 结构。 */
    private fun sessionKey(sessionId: String): String {
        val trimmed = sessionId.trim()
        return if (trimmed.isEmpty()) DEFAULT_SESSION else trimmed
    }

    /**
     * 读某会话的待办。
     *
     * - 文件不存在 / 内容为空 / JSON 坏掉 → 空表，不抛；
     * - 这一会话还没有清单 → 空表；
     * - content 完全为空白时丢弃该条目（工具侧已做校验，此处防护手工修改文件）。
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
     * 清单为空时删除该 key，长期未使用的会话不会在文件中保留空数组。
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
                    // 空内容不写入，避免模型读回无意义条目
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
                // rename 失败（个别文件系统）时退化为直接覆盖，确保内容写入
                file.writeText(json, Charsets.UTF_8)
                temp.delete()
            }
            FileLog.log(TAG, "保存会话 " + key + " 的待办 " + todos.size + " 条")
        } catch (e: Exception) {
            FileLog.log(TAG, "保存待办失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 将任意输入收敛为三种合法状态；无法识别的取值按 pending 处理，避免误显示为已完成。 */
    fun normalizeStatus(raw: String): String = when (raw.trim().lowercase()) {
        IN_PROGRESS -> IN_PROGRESS
        COMPLETED -> COMPLETED
        else -> PENDING
    }

    /**
     * 读整个文件当 root；文件坏掉就当空对象。
     *
     * 此处有意丢弃其他会话的数据：损坏文件本来就没有可信内容，
     * 从空表重新开始优于让本次写入失败。
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
