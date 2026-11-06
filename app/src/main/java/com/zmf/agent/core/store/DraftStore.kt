package com.zmf.agent.core.store

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONObject
import java.io.File

/**
 * 未发送提示词的持久化：`filesDir/appdata/drafts.json`，键为会话 id，值为纯文本。
 *
 * 与 `sessions.json` 分开存放的原因：输入一次按键就整表重写会话文件，代价过大；
 * 草稿量级小且与消息无关，独立文件可以整体覆盖写而不触碰会话记录。
 *
 * 只保存文本：待发附件与图片不入库——文件已经复制进工作区，重建队列只会产生无人引用的副本。
 */
object DraftStore {

    private const val TAG = "draft"
    private const val FILE_NAME = "drafts.json"

    private fun storeFile(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    /** 读全部草稿；文件缺失或损坏时返回空表。 */
    @Synchronized
    fun load(context: Context): Map<String, String> {
        val file = storeFile(context)
        if (!file.exists() || file.length() == 0L) return emptyMap()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return emptyMap()
            val root = JSONObject(text)
            val keys = root.keys()
            val out = LinkedHashMap<String, String>(root.length())
            while (keys.hasNext()) {
                val key = keys.next()
                val value = root.optString(key, "")
                if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
            }
            out
        } catch (e: Exception) {
            FileLog.log(TAG, "读取草稿失败：" + e)
            emptyMap()
        }
    }

    /** 单条草稿；没有返回空串。 */
    fun text(context: Context, sessionId: String): String =
        if (sessionId.isEmpty()) "" else load(context)[sessionId].orEmpty()

    /**
     * 写入一条草稿；文本为空表示清空该键。
     *
     * 现读现写：草稿表很小，且界面与后台可能同时修改不同会话，整表读回再写可避免互相覆盖。
     */
    @Synchronized
    fun put(context: Context, sessionId: String, text: String) {
        if (sessionId.isEmpty()) return
        val current = load(context)
        val next = LinkedHashMap(current)
        if (text.isEmpty()) {
            if (!next.containsKey(sessionId)) return
            next.remove(sessionId)
        } else {
            if (current[sessionId] == text) return
            next[sessionId] = text
        }
        save(context, next)
    }

    /** 删除若干条草稿（删除会话、删除工作区时连带清理）。 */
    @Synchronized
    fun remove(context: Context, sessionIds: Collection<String>) {
        if (sessionIds.isEmpty()) return
        val current = load(context)
        val next = current.filterKeys { it !in sessionIds }
        if (next.size == current.size) return
        save(context, next)
    }

    /** 只保留 [keep] 中的键：用于清理已不存在的会话留下的孤儿草稿。 */
    @Synchronized
    fun retain(context: Context, keep: Set<String>) {
        val current = load(context)
        val next = current.filterKeys { it in keep }
        if (next.size == current.size) return
        save(context, next)
    }

    private fun save(context: Context, drafts: Map<String, String>) {
        try {
            val root = JSONObject()
            for ((key, value) in drafts) root.put(key, value)
            val file = storeFile(context)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, FILE_NAME + ".tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: Exception) {
            FileLog.log(TAG, "保存草稿失败：" + e)
        }
    }
}
