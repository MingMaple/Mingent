package com.zmf.agent.core.store

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONObject
import java.io.File

/**
 * 队列落盘（P33）：`filesDir/appdata/queues.json`，按会话分键存「繁忙时排队」的待发消息。
 *
 * DSH 把队列寄存在会话事件里（每次 inbox 变更提交一条 splice 事件）；我方单独落一份 JSON：
 * 会话正文已是事件流（D1），但队列条目带自己的结构（附件卡、图片卡），并入事件流会让
 * `msg` 事件里混进非消息对象，回放端要多一套类型。整份小文件、条目数少，每次变更同步写。
 *
 * 序列化由调用方（SendController）完成：本文件只管字节落盘，不认识 QueuedMessage——
 * core 层不得反向依赖 feature 层（架构检查约束）。
 */
object QueueStore {

    private const val TAG = "queue"

    private const val FILE_NAME = "queues.json"

    private fun file(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    /** 整份写入（先写 .tmp 再 rename）；失败只记日志，队列丢失不阻断发送流程。 */
    @Synchronized
    fun save(context: Context, root: JSONObject): Boolean {
        return try {
            val file = file(context)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, FILE_NAME + ".tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                temp.delete()
            }
            true
        } catch (e: Exception) {
            FileLog.log(TAG, "队列落盘失败：" + e)
            false
        }
    }

    /** 整份读回；文件不存在、为空或损坏时返回 null（按「无队列」处理）。 */
    @Synchronized
    fun load(context: Context): JSONObject? {
        return try {
            val file = file(context)
            if (!file.isFile || file.length() == 0L) return null
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            FileLog.log(TAG, "队列读盘失败，按无队列处理：" + (e.message ?: e.javaClass.simpleName))
            null
        }
    }
}
