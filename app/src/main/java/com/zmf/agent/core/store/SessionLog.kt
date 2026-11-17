package com.zmf.agent.core.store

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import org.json.JSONObject
import java.io.File

/**
 * 会话事件流（D1.3-C/D1.4）：每会话一个 `<id>.jsonl`，帧内是 JSONL 事件，仅追加。
 *
 * 事件行类型（行内 `t` 字段区分）：
 *
 * | 类型       | 关键字段                      | 折叠语义                          |
 * |-----------|------------------------------|----------------------------------|
 * | `h`       | `v`/`id`/`at`/`app`          | 帧头，每文件唯一；缺失判损坏        |
 * | `meta`    | `seq`/`at`/`p`               | 会话字段 patch，同键后者覆盖        |
 * | `msg`     | `seq`/`at`/`mno`/`m`         | 追加一条消息；`mno` 必须连续        |
 * | `msg.upd` | `seq`/`at`/`mno`/`m`         | 整条替换该下标的消息（用量/产物回填）|
 * | `trunc`   | `seq`/`at`/`keep`            | 丢弃 `mno >= keep`；不物理删行      |
 *
 * **与设计稿的两处实现差异（均进实现报告）**：
 * 1. `msg.upd` 写整条消息而不是字段级 patch——读取端一次替换即可，避免字段级 diff 静默丢字段；
 * 2. 写入采用同步 write-through（屏障处直接落盘），没有 200 ms 后台批处理线程——
 *    三屏障本来就要同步 flush，统一成一条路径后不存在「读不到未落事件」的一致性窗口。
 *
 * 每条事件带 `seq`（每会话单调递增，从 0 起，header 不占号）：打开时逐事件校验连续性，
 * 断号即判该帧起不可信，按帧边界截断（D1.3-E）。
 *
 * 本文件与 [SessionStore] 的分工：这里只认识「文件、事件、折叠」，不知道谁在什么时候写；
 * 写入时机与语义（upsert/截断/忽略过期快照）全部在 [SessionStore]。
 */
object SessionLog {

    private const val TAG = "session"

    /** 事件流版本（header 里的 `v`）。 */
    const val LOG_VERSION = 1

    /** 迁移完成标记文件名（D1.4）：存在即表示 JSONL 模式生效。 */
    const val MIGRATED_MARKER = ".migrated"

    /** 损坏文件 quarantine 目录名（D1.3-C：缺 header 判损坏，整文件移入）。 */
    private const val CORRUPT_DIR = ".corrupt"

    /** 迁移期临时后缀：写 `<id>.jsonl.part` 成功后 rename。 */
    private const val PART_SUFFIX = ".part"

    /** 一帧最多的事件行数（D1.3-B）。 */
    private const val MAX_EVENTS_PER_FRAME = 500

    /** 一帧未压缩 payload 的字节上限（D1.3-B）。 */
    private const val MAX_FRAME_PAYLOAD = 256 * 1024

    private const val TYPE_HEADER = "h"
    private const val TYPE_META = "meta"
    private const val TYPE_MSG = "msg"
    private const val TYPE_MSG_UPD = "msg.upd"
    private const val TYPE_TRUNC = "trunc"

    /** 文件头信息：帧 0 的 header 行。 */
    data class Header(val id: String, val createdAt: Long)

    /**
     * 一份事件流的折叠结果（内存态）。
     *
     * [fields] 是会话字段的 JSON（**不含** messages 数组）；[messages] 是事件里逐条追加的消息 JSON，
     * [chatMessages] 是它们的解码形态（比对与上层使用，两者下标始终一一对应）。
     * [seq] 是已使用的最大事件序号，下一个事件用 `seq + 1`。
     */
    class Fold internal constructor(
        val header: Header,
        @Volatile var seq: Long,
        @Volatile var fields: JSONObject,
        val messages: MutableList<JSONObject>,
        val chatMessages: MutableList<ChatMessage>
    )

    // ---- 事件构造 ----

    /** meta 事件：[patch] 只写变化键。 */
    fun metaEvent(seq: Long, at: Long, patch: JSONObject): JSONObject =
        JSONObject().put("t", TYPE_META).put("seq", seq).put("at", at).put("p", patch)

    /** msg 事件：一条消息首次落盘。 */
    fun msgEvent(seq: Long, at: Long, mno: Int, message: JSONObject): JSONObject =
        JSONObject().put("t", TYPE_MSG).put("seq", seq).put("at", at).put("mno", mno).put("m", message)

    /** msg.upd 事件：整条替换该下标的消息。 */
    fun updEvent(seq: Long, at: Long, mno: Int, message: JSONObject): JSONObject =
        JSONObject().put("t", TYPE_MSG_UPD).put("seq", seq).put("at", at).put("mno", mno).put("m", message)

    /** trunc 事件：回滚补偿，丢弃 `mno >= keep` 的消息。 */
    fun truncEvent(seq: Long, at: Long, keep: Int): JSONObject =
        JSONObject().put("t", TYPE_TRUNC).put("seq", seq).put("at", at).put("keep", keep)

    /**
     * 消息差异 → 事件序列（纯函数，单测覆盖）。
     *
     * - [disk] 是磁盘折叠出的消息，[target] 是要落盘的消息；
     * - 最长公共前缀 [L] 之后：磁盘更长时按 [allowTruncate] 决定写 `trunc` 还是整体放弃
     *   （`save` 不允许截断，收到比磁盘短的列表记「忽略疑似过期快照」并返回 null）；
     * - 长度相同但内容不同（原地变化，如用量回填）逐条写 `msg.upd`；
     * - 目标更长时尾部逐条写 `msg`。
     */
    fun diffEvents(
        disk: List<ChatMessage>,
        target: List<ChatMessage>,
        allowTruncate: Boolean
    ): List<JSONObject>? {
        var l = 0
        while (l < target.size && l < disk.size && target[l] == disk[l]) l++
        val events = ArrayList<JSONObject>()
        val at = System.currentTimeMillis()
        // 只有磁盘比目标长才需要截断；等长原地变化（用量回填）走 msg.upd，不算截断
        val truncating = disk.size > target.size
        if (truncating && !allowTruncate) return null
        if (truncating) events.add(truncEvent(0, at, l))
        // 原地变化：未截断的公共区间里下标 i 内容不同——整条替换
        val inPlaceEnd = if (truncating) l else minOf(disk.size, target.size)
        for (i in l until inPlaceEnd) {
            if (target[i] != disk[i]) events.add(updEvent(0, at, i, SessionStore.messageToJson(target[i])))
        }
        // 新增消息
        val diskEnd = if (truncating) l else disk.size
        for (i in diskEnd until target.size) {
            events.add(msgEvent(0, at, i, SessionStore.messageToJson(target[i])))
        }
        return events
    }

    // ---- 读 ----

    /**
     * 读入并折叠一份会话文件。
     *
     * 崩溃恢复（D1.3-E）由 [ZstdFrames.scan] 完成：半帧/CRC/解压失败在扫描时截断；
     * 这里再补事件层判定——`seq` 断号或 `mno` 不连续同样按帧边界截断并重新折叠。
     * 缺 header 或 header 的 id 与文件名不符判损坏：整文件移入 `.corrupt/`，返回 null。
     */
    fun read(file: File): Fold? {
        if (!file.isFile) return null
        val expectedId = file.name.removeSuffix(".jsonl")
        val scan = ZstdFrames.scan(file)
        var fold: Fold? = null
        var truncateAt = -1L
        scan@ for (frameIndex in scan.frames.indices) {
            val payload = String(scan.frames[frameIndex].payload, Charsets.UTF_8)
            for (line in payload.split('\n')) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val obj = try {
                    JSONObject(trimmed)
                } catch (e: Exception) {
                    truncateAt = scan.frameStarts[frameIndex]
                    break@scan
                }
                if (fold == null) {
                    // 首个有效事件必须是 header，且 id 与文件名一致
                    val id = obj.optString("id", "")
                    if (obj.optString("t", "") != TYPE_HEADER || id != expectedId) {
                        quarantine(file)
                        return null
                    }
                    fold = Fold(Header(id, obj.optLong("at", 0L)), -1L, JSONObject(), ArrayList(), ArrayList())
                    continue
                }
                if (!applyEvent(fold, obj)) {
                    // seq 断号或 mno 不连续：该帧起不可信
                    truncateAt = scan.frameStarts[frameIndex]
                    break@scan
                }
            }
        }
        if (fold == null) {
            // 空文件或整帧损坏以致解不出 header：按损坏处理
            if (scan.frames.isEmpty() && file.length() > 0L) {
                quarantine(file)
                return null
            }
            return null
        }
        if (truncateAt >= 0) {
            ZstdFrames.truncateTo(file, truncateAt)
            FileLog.log(TAG, "会话 " + fold.header.id + " 事件序号断号，按帧边界截断到 " + truncateAt + " 字节")
        }
        return fold
    }

    /** 把一个事件折进 [fold]；非法（seq 断号 / mno 不连续 / 未知类型）返回 false。 */
    private fun applyEvent(fold: Fold, obj: JSONObject): Boolean {
        val type = obj.optString("t", "")
        val seq = obj.optLong("seq", Long.MIN_VALUE)
        if (seq == Long.MIN_VALUE) return false
        val expected = fold.seq + 1
        if (fold.seq >= 0 && seq != expected) return false
        when (type) {
            TYPE_META -> {
                val patch = obj.optJSONObject("p") ?: return false
                val fields = fold.fields
                for (key in patch.keys()) fields.put(key, patch.opt(key))
                fold.fields = fields
            }

            TYPE_MSG -> {
                val mno = obj.optInt("mno", -1)
                val message = obj.optJSONObject("m") ?: return false
                if (mno != fold.messages.size) return false
                val decoded = SessionStore.messageFromJson(message) ?: return false
                fold.messages.add(message)
                fold.chatMessages.add(decoded)
            }

            TYPE_MSG_UPD -> {
                val mno = obj.optInt("mno", -1)
                val message = obj.optJSONObject("m") ?: return false
                if (mno < 0 || mno >= fold.messages.size) return false
                val decoded = SessionStore.messageFromJson(message) ?: return false
                fold.messages[mno] = message
                fold.chatMessages[mno] = decoded
            }

            TYPE_TRUNC -> {
                val keep = obj.optInt("keep", -1)
                if (keep < 0) return false
                val clamped = keep.coerceAtMost(fold.messages.size)
                if (clamped < fold.messages.size) {
                    fold.messages.subList(clamped, fold.messages.size).clear()
                    fold.chatMessages.subList(clamped, fold.chatMessages.size).clear()
                }
            }

            else -> return false
        }
        fold.seq = seq
        return true
    }

    /** 损坏文件移入 `.corrupt/`（移动失败则保留原文件并记日志，读取端返回 null 即可）。 */
    private fun quarantine(file: File) {
        val dir = File(file.parentFile, CORRUPT_DIR)
        runCatching { dir.mkdirs() }
        val moved = runCatching {
            val target = File(dir, file.name + "." + System.currentTimeMillis())
            file.renameTo(target)
        }.getOrDefault(false)
        FileLog.log(
            TAG,
            "会话文件缺帧头或 id 不符，判损坏" + (if (moved) "，已移入 .corrupt/" else "（移动失败，保留原文件）") +
                "：" + file.name
        )
    }

    // ---- 写 ----

    /**
     * 整份写入（新会话 / 迁移 / 分叉）：header + meta + 全部消息，按 500 行 / 256 KB 分帧。
     * 任一帧写入失败即中止（调用方删除 `.part` 或重试），不留半份。
     */
    fun writeFull(file: File, session: ChatSession): Boolean {
        val now = System.currentTimeMillis()
        val lines = ArrayList<String>()
        lines.add(
            JSONObject()
                .put("t", TYPE_HEADER)
                .put("v", LOG_VERSION)
                .put("id", session.id)
                .put("at", now)
                .put("app", "mingent")
                .toString()
        )
        lines.add(metaEvent(0, now, SessionStore.sessionFieldsToJson(session)).toString())
        var seq = 0L
        for (i in session.messages.indices) {
            seq++
            lines.add(msgEvent(seq, session.messages[i].createdAt, i, SessionStore.messageToJson(session.messages[i])).toString())
        }
        file.parentFile?.mkdirs()
        var offset = 0
        while (offset < lines.size) {
            // 分帧：先装满一帧（500 行或 256 KB，先到者截断），再写盘
            val frame = ArrayList<String>()
            var bytes = 0
            while (offset < lines.size && frame.size < MAX_EVENTS_PER_FRAME) {
                val line = lines[offset]
                if (frame.isNotEmpty() && bytes + line.length > MAX_FRAME_PAYLOAD) break
                frame.add(line)
                bytes += line.length + 1
                offset++
            }
            val payload = frame.joinToString("\n") + "\n"
            if (!ZstdFrames.appendFrame(file, payload.toByteArray(Charsets.UTF_8))) return false
        }
        return true
    }

    /**
     * 把一批事件追加到文件末尾，并同步折进 [fold]（内存与磁盘同源推进）。
     *
     * 帧边界规则同 [writeFull]；任一帧失败返回 false（调用方作废缓存，下次读盘重新折叠）。
     * [events] 里的 seq/mno 允许为占位 0：本函数按 fold 当前状态重编号。
     */
    fun appendEvents(file: File, fold: Fold, events: List<JSONObject>): Boolean {
        if (events.isEmpty()) return true
        var seq = fold.seq
        // 空文件的第一个事件不受「seq 从 0 起」约束：fold.seq 为 -1 时从 0 开始
        val lines = ArrayList<String>(events.size)
        for (event in events) {
            seq++
            event.put("seq", seq)
            lines.add(event.toString())
        }
        file.parentFile?.mkdirs()
        var offset = 0
        while (offset < lines.size) {
            val frame = ArrayList<String>()
            var bytes = 0
            while (offset < lines.size && frame.size < MAX_EVENTS_PER_FRAME) {
                val line = lines[offset]
                if (frame.isNotEmpty() && bytes + line.length > MAX_FRAME_PAYLOAD) break
                frame.add(line)
                bytes += line.length + 1
                offset++
            }
            val payload = frame.joinToString("\n") + "\n"
            if (!ZstdFrames.appendFrame(file, payload.toByteArray(Charsets.UTF_8))) return false
        }
        // 全部帧落盘成功后才折进内存：失败时调用方作废缓存，下次从磁盘重新折叠
        fold.seq = seq
        for (event in events) {
            val type = event.optString("t")
            when (type) {
                TYPE_META -> {
                    val patch = event.optJSONObject("p")
                    if (patch != null) for (key in patch.keys()) fold.fields.put(key, patch.opt(key))
                }

                TYPE_MSG -> {
                    val message = event.optJSONObject("m")
                    if (message != null) {
                        fold.messages.add(message)
                        SessionStore.messageFromJson(message)?.let { fold.chatMessages.add(it) }
                    }
                }

                TYPE_MSG_UPD -> {
                    val mno = event.optInt("mno", -1)
                    val message = event.optJSONObject("m")
                    if (mno >= 0 && mno < fold.messages.size && message != null) {
                        fold.messages[mno] = message
                        SessionStore.messageFromJson(message)?.let { fold.chatMessages[mno] = it }
                    }
                }

                TYPE_TRUNC -> {
                    val keep = event.optInt("keep", -1).coerceAtMost(fold.messages.size)
                    if (keep >= 0 && keep < fold.messages.size) {
                        fold.messages.subList(keep, fold.messages.size).clear()
                        fold.chatMessages.subList(keep, fold.chatMessages.size).clear()
                    }
                }
            }
        }
        return true
    }

    // ---- 迁移（D1.4）----

    /**
     * 旧 `sessions.json` → 每会话 JSONL。幂等：已存在 `<id>.jsonl` 的会话跳过
     * （[overwrite] 为 true 时以旧文件内容覆盖，供备份导入的「当场迁移」使用）。
     *
     * 流程：逐会话写 `<id>.jsonl.part` → rename；全部成功后写 `.migrated` 标记，
     * 并把 `sessions.json` 改名 `sessions.json.bak`（代码不自动删除，裁定附 2）。
     * 任一步失败：删除本次 `.part`，保留 `sessions.json`，返回 false（本次启动降级为旧格式）。
     */
    fun migrateIfNeeded(context: Context, overwrite: Boolean = false): Boolean {
        val dir = AppPaths.sessionsDir(context)
        val marker = File(dir, MIGRATED_MARKER)
        val legacy = AppPaths.dataFile(context, "sessions.json")
        if (!marker.isFile && !legacy.isFile && !hasJsonl(dir)) {
            // 全新安装：无事可做，直接写标记进入 JSONL 模式
            writeMarker(marker, 0)
            return true
        }
        return migrate(dir, legacy, marker, overwrite)
    }

    private fun hasJsonl(dir: File): Boolean =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.isNotEmpty() == true

    /** 迁移主体（路径参数化，供单测直接驱动临时目录）。 */
    fun migrate(dir: File, legacy: File?, marker: File, overwrite: Boolean = false): Boolean {
        if (marker.isFile && !overwrite) return true
        val sessions: List<ChatSession> = if (legacy != null && legacy.isFile) {
            try {
                SessionStore.parseSessionsJson(legacy.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                FileLog.log(TAG, "旧 sessions.json 解析失败，本次沿用旧格式：" + (e.message ?: e.javaClass.simpleName))
                return false
            }
        } else {
            emptyList()
        }
        var written = 0
        for (session in sessions) {
            val target = File(dir, session.id + ".jsonl")
            if (target.isFile && !overwrite) continue
            val part = File(dir, session.id + ".jsonl" + PART_SUFFIX)
            try {
                part.delete()
                if (!writeFull(part, session)) throw IllegalStateException("写入 .part 失败")
                if (overwrite && target.isFile) target.delete()
                if (!part.renameTo(target)) throw IllegalStateException("rename 失败")
                written++
            } catch (e: Exception) {
                part.delete()
                FileLog.log(TAG, "迁移失败（会话 " + session.id + "），保留旧文件：" + (e.message ?: e.javaClass.simpleName))
                return false
            }
        }
        writeMarker(marker, sessions.size)
        if (legacy != null && legacy.isFile) {
            val bak = File(legacy.parentFile, "sessions.json.bak")
            if (bak.exists()) {
                FileLog.log(TAG, "sessions.json.bak 已存在，旧文件保留原处")
            } else if (!legacy.renameTo(bak)) {
                FileLog.log(TAG, "sessions.json 改名 .bak 失败（数据已迁移完成，不影响使用）")
            } else {
                FileLog.log(TAG, "旧 sessions.json 已改名 sessions.json.bak 保留")
            }
        }
        FileLog.log(TAG, "会话迁移完成：" + sessions.size + " 条（新写 " + written + "）→ sessions/*.jsonl")
        return true
    }

    private fun writeMarker(marker: File, sessions: Int) {
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(
                JSONObject()
                    .put("v", 1)
                    .put("at", System.currentTimeMillis())
                    .put("sessions", sessions)
                    .put("level", ZstdFrames.LEVEL)
                    .toString(),
                Charsets.UTF_8
            )
        }
    }
}
