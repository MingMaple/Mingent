package com.zmf.agent.core.store

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话索引（D1.3-F 随机读保护）：`appdata/sessions.idx.json`，列表页只读它，不解压任何会话文件。
 *
 * 每个条目复用旧 `sessions.json` 的会话 JSON 形态（**不含** messages 数组）外加一个 `len` 字段——
 * 该会话 `.jsonl` 文件写入时的字节长度。索引缺失、损坏或 [len] 与磁盘文件长度不一致时，
 * 从各会话文件重建（顺序解压，兜底路径，写日志）。
 *
 * 索引不进备份（D1.3-G）：它随时可由会话文件重建，备份包里只有 `.jsonl` 原样字节。
 * X05 的「meta 与帧一致性检查」落在 [summaries] 的 len 校验上：外部改写会话文件
 * （ADB 直写、上一版本写入、导入覆盖）会使索引整体过期，此时宁可选慢路径也不读旧数据。
 */
object SessionIndex {

    private const val TAG = "session"

    private const val FILE_NAME = "sessions.idx.json"

    private const val KEY_SESSIONS = "sessions"
    private const val KEY_LEN = "len"

    /** 一条索引：会话记录（无正文）+ 其文件长度。 */
    data class Entry(val session: ChatSession, val len: Long)

    private fun idxFile(context: Context): File = File(AppPaths.dataDir(context), FILE_NAME)

    /**
     * 读全部索引条目。len 与磁盘不符（任一条）即整体重建；
     * 索引文件缺失/损坏同样重建。重建失败返回空列表（启动按「无历史会话」处理，与旧版读盘失败一致）。
     */
    fun summaries(context: Context): List<Entry> =
        summaries(AppPaths.sessionsDir(context), idxFile(context))

    /** 路径参数化主体（单测直接驱动临时目录）。 */
    internal fun summaries(dir: File, idx: File): List<Entry> {
        if (idx.isFile) {
            val parsed = parseIndex(idx, dir)
            if (parsed != null) return parsed
            FileLog.log(TAG, "会话索引与文件长度不一致，整表重建（X05 一致性检查）")
        }
        return rebuild(dir, idx)
    }

    /** 从会话文件重建索引（顺序解压全部帧，兜底路径）。 */
    fun rebuild(context: Context): List<Entry> =
        rebuild(AppPaths.sessionsDir(context), idxFile(context))

    /** 解析索引并校验每条 len 与磁盘文件长度一致（X05 一致性检查）；不符返回 null 触发重建。 */
    private fun parseIndex(idx: File, dir: File): List<Entry>? {
        return try {
            val root = JSONObject(idx.readText(Charsets.UTF_8))
            val array = root.optJSONArray(KEY_SESSIONS) ?: return null
            val out = ArrayList<Entry>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: return null
                if (obj.optString("id", "").isEmpty()) return null
                val session = SessionStore.sessionFromJson(obj) ?: return null
                val len = obj.optLong(KEY_LEN, -1L)
                val disk = File(dir, session.id + ".jsonl")
                // len 为 -1（旧索引缺字段）或与磁盘不符都视为整体过期
                if (len < 0L || !disk.isFile || disk.length() != len) return null
                out.add(Entry(session, len))
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    internal fun rebuild(dir: File, idx: File): List<Entry> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name }
            ?: return emptyList()
        val out = ArrayList<Entry>(files.size)
        for (file in files) {
            val id = file.name.removeSuffix(".jsonl")
            val fold = SessionLog.read(file) ?: continue
            val session = SessionStore.sessionFromFields(fold.fields, fold.chatMessages) ?: continue
            out.add(Entry(session, file.length()))
        }
        saveTo(idx, out)
        FileLog.log(TAG, "会话索引重建完成：" + out.size + " 条")
        return out
    }

    /** 不做 len 校验的宽松读取（写路径维护索引用；读路径的一致性检查在 [summaries]）。 */
    fun readRaw(context: Context): List<Entry> {
        val file = idxFile(context)
        if (!file.isFile) return emptyList()
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val array = root.optJSONArray(KEY_SESSIONS) ?: return emptyList()
            val out = ArrayList<Entry>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optString("id", "").isEmpty()) continue
                val session = SessionStore.sessionFromJson(obj) ?: continue
                out.add(Entry(session, obj.optLong(KEY_LEN, -1L)))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 整表写回（小文件，先写 .tmp 再 rename）。 */
    fun save(context: Context, entries: List<Entry>) = saveTo(idxFile(context), entries)

    private fun saveTo(idx: File, entries: List<Entry>) {
        try {
            val array = JSONArray()
            for (entry in entries) {
                val obj = SessionStore.sessionFieldsToJson(entry.session)
                obj.put(KEY_LEN, entry.len)
                array.put(obj)
            }
            val root = JSONObject().put("v", 1).put("at", System.currentTimeMillis()).put(KEY_SESSIONS, array)
            idx.parentFile?.mkdirs()
            val temp = File(idx.parentFile, idx.name + ".tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(idx)) {
                idx.writeText(root.toString(), Charsets.UTF_8)
                temp.delete()
            }
        } catch (e: Exception) {
            FileLog.log(TAG, "会话索引写盘失败：" + e)
        }
    }

    /** 删除一条（会话真删时同步）；索引不存在时静默。 */
    fun remove(context: Context, id: String) {
        val file = idxFile(context)
        if (!file.isFile) return
        val entries = runCatching { summaries(context) }.getOrDefault(emptyList())
        val kept = entries.filterNot { it.session.id == id }
        if (kept.size != entries.size) save(context, kept)
    }

    /** 索引被外部整目录改写（备份导入）后作废：下次读取走重建。 */
    fun invalidate(context: Context) {
        runCatching { idxFile(context).delete() }
    }
}
