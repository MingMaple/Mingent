package com.zmf.agent.core.store

import com.zmf.agent.core.model.ChatMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * upsert 差异算法与迁移的回归（D1.3-H 语义边界、D1.4 幂等）。
 */
class SessionLogDiffAndMigrateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun msg(content: String, at: Long = 1L) = ChatMessage(role = "user", content = content, createdAt = at)

    // ---- meta 差分（C5⑤）：回到缺省值的字段也必须落盘 ----

    /** blank/listed/archived/permissionPreset 翻回默认时会产生差分；折叠后再次比较不再重复写。 */
    @Test
    fun metaPatchWritesFieldsReturningToDefault() {
        val old = JSONObject()
            .put("blank", true)
            .put("listed", false)
            .put("archived", true)
            .put("permissionPreset", "workspace-write")
            .put("futureKey", "保留")
        val new = SessionStore.sessionFieldsToJson(ChatSession(id = "1"))

        val patch = SessionStore.metaPatch(old, new)
        assertFalse(patch.getBoolean("blank"))
        assertTrue(patch.getBoolean("listed"))
        assertFalse(patch.getBoolean("archived"))
        assertEquals("", patch.getString("permissionPreset"))
        assertFalse(patch.has("futureKey"))

        val folded = JSONObject(old.toString())
        for (key in patch.keys()) folded.put(key, patch.opt(key))
        assertEquals(0, SessionStore.metaPatch(folded, new).length())
    }

    /** 索引快读时正文未加载，仍应按 msgCount 把带消息的「空白会话」转正。 */
    @Test
    fun healBlankUsesMessageCountWhenBodiesAreLazy() {
        val lazy = ChatSession(id = "1", blank = true, listed = false, msgCount = 2)
        val healed = SessionStore.healBlank(lazy)
        assertFalse(healed.blank)
        assertTrue(healed.listed)

        val empty = ChatSession(id = "2", blank = true, listed = false)
        assertTrue(SessionStore.healBlank(empty).blank)
    }

    // ---- diffEvents（D1.3-H 语义 6：save 不允许截断）----

    /** 纯追加：磁盘为前缀，只产出尾部 msg 事件。 */
    @Test
    fun appendOnlyDiff() {
        val disk = listOf(msg("a"), msg("b"))
        val target = listOf(msg("a"), msg("b"), msg("c"), msg("d"))
        val events = SessionLog.diffEvents(disk, target, allowTruncate = false)
        assertNotNull(events)
        assertEquals(2, events!!.size)
        assertTrue(events.all { it.optString("t") == "msg" })
    }

    /** 内存比磁盘短且不允许截断：返回 null（save 路径忽略疑似过期快照）。 */
    @Test
    fun shorterSnapshotWithoutTruncateIsRejected() {
        val disk = listOf(msg("a"), msg("b"), msg("c"))
        val target = listOf(msg("a"))
        assertNull(SessionLog.diffEvents(disk, target, allowTruncate = false))
    }

    /** 内存比磁盘短且允许截断（回滚路径）：trunc(keep=L) + 重新追加。 */
    @Test
    fun shorterSnapshotWithTruncateEmitsTrunc() {
        val disk = listOf(msg("a"), msg("b"), msg("c"))
        val target = listOf(msg("a"), msg("b2"))
        val events = SessionLog.diffEvents(disk, target, allowTruncate = true)
        assertNotNull(events)
        assertEquals("trunc", events!!.first().optString("t"))
        assertEquals(1, events.first().optInt("keep"))
        // b2 与磁盘第 2 条不同：trunc 到 1 之后全部重写
        assertEquals(1, events.size - 1)
        assertTrue(events.drop(1).all { it.optString("t") == "msg" })
    }

    /** 等长原地变化（用量回填）：逐条 msg.upd，不产生 trunc。 */
    @Test
    fun inPlaceChangeEmitsUpd() {
        val disk = listOf(msg("a"), msg("b"))
        val target = listOf(msg("a"), msg("b").copy(promptTokens = 5))
        val events = SessionLog.diffEvents(disk, target, allowTruncate = false)
        assertNotNull(events)
        assertEquals(1, events!!.size)
        assertEquals("msg.upd", events[0].optString("t"))
        assertEquals(1, events[0].optInt("mno"))
    }

    /** 分叉且不允许截断（磁盘更长、分支下标内容也不同）：忽略，返回 null。 */
    @Test
    fun divergedSnapshotWithoutTruncateIsRejected() {
        val disk = listOf(msg("a"), msg("disk-extra"), msg("disk-tail"))
        val target = listOf(msg("a"), msg("memory-extra"))
        assertNull(SessionLog.diffEvents(disk, target, allowTruncate = false))
    }

    // ---- 迁移（D1.4：幂等、.bak 保留、失败降级、导入覆盖）----

    private fun legacyJson(vararg sessions: ChatSession): String {
        val array = org.json.JSONArray()
        for (session in sessions) {
            val obj = SessionStore.sessionFieldsToJson(session)
            val messages = org.json.JSONArray()
            for (m in session.messages) messages.put(SessionStore.messageToJson(m))
            obj.put("messages", messages)
            array.put(obj)
        }
        return org.json.JSONObject().put("sessions", array).toString()
    }

    /** 迁移：每个旧会话一个 .jsonl；二次执行幂等；sessions.json 改名 .bak 保留。 */
    @Test
    fun migrateIsIdempotentAndKeepsBak() {
        val dir = tmp.newFolder("sessions")
        val legacy = File(tmp.root, "sessions.json")
        legacy.writeText(
            legacyJson(
                ChatSession(id = "111", title = "甲", messages = listOf(msg("内容一"))),
                ChatSession(id = "222", title = "乙", messages = listOf(msg("内容二")))
            ),
            Charsets.UTF_8
        )
        val marker = File(dir, SessionLog.MIGRATED_MARKER)
        assertTrue(SessionLog.migrate(dir, legacy, marker))
        assertTrue(marker.isFile)
        assertEquals(2, dir.listFiles { f -> f.name.endsWith(".jsonl") }!!.size)
        assertTrue(File(tmp.root, "sessions.json.bak").isFile)
        assertFalse(legacy.exists())

        // 幂等：标记存在直接返回 true；删掉标记再跑也不重复写
        val files = dir.listFiles { f -> f.name.endsWith(".jsonl") }!!.map { it.name to it.length() }.toSet()
        assertTrue(SessionLog.migrate(dir, null, marker))
        marker.delete()
        assertTrue(SessionLog.migrate(dir, null, marker))
        assertEquals(files, dir.listFiles { f -> f.name.endsWith(".jsonl") }!!.map { it.name to it.length() }.toSet())

        // 折叠结果与旧 JSON 一致
        val fold = SessionLog.read(File(dir, "111.jsonl"))!!
        assertEquals("甲", fold.fields.optString("title"))
        assertEquals(1, fold.chatMessages.size)
        assertEquals("内容一", fold.chatMessages[0].content)
    }

    /** 部分迁移后重试：已存在的会话文件跳过，只补缺的。 */
    @Test
    fun migrateRetriesOnlyMissingSessions() {
        val dir = tmp.newFolder("sessions2")
        val legacy = File(tmp.root, "sessions.json")
        legacy.writeText(
            legacyJson(
                ChatSession(id = "333", title = "已有"),
                ChatSession(id = "444", title = "待补")
            ),
            Charsets.UTF_8
        )
        val marker = File(dir, SessionLog.MIGRATED_MARKER)
        // 预置第一个会话的文件（模拟上次迁移写了一半就崩）
        assertTrue(SessionLog.writeFull(File(dir, "333.jsonl"), ChatSession(id = "333", title = "已有")))
        val before = File(dir, "333.jsonl").length()
        assertTrue(SessionLog.migrate(dir, legacy, marker))
        assertEquals(before, File(dir, "333.jsonl").length())
        assertTrue(File(dir, "444.jsonl").isFile)
    }

    /** 覆盖模式（备份导入的当场迁移）：同名会话以导入内容为准。 */
    @Test
    fun migrateOverwriteReplacesExisting() {
        val dir = tmp.newFolder("sessions3")
        val existing = File(dir, "555.jsonl")
        assertTrue(SessionLog.writeFull(existing, ChatSession(id = "555", title = "旧标题")))
        val legacy = File(tmp.root, "sessions.json")
        legacy.writeText(legacyJson(ChatSession(id = "555", title = "导入标题")), Charsets.UTF_8)
        val marker = File(dir, SessionLog.MIGRATED_MARKER)
        assertTrue(marker.createNewFile())
        // overwrite=false：跳过
        assertTrue(SessionLog.migrate(dir, legacy, marker, overwrite = false))
        assertEquals("旧标题", SessionLog.read(existing)!!.fields.optString("title"))
        // overwrite=true：以导入内容覆盖
        marker.delete()
        assertTrue(SessionLog.migrate(dir, legacy, marker, overwrite = true))
        assertEquals("导入标题", SessionLog.read(existing)!!.fields.optString("title"))
    }

    /** 旧 JSON 损坏：迁移失败返回 false，不写标记，不产生 .bak。 */
    @Test
    fun migrateFailsOnCorruptLegacy() {
        val dir = tmp.newFolder("sessions4")
        val legacy = File(tmp.root, "sessions.json")
        legacy.writeText("{ 不是 JSON", Charsets.UTF_8)
        val marker = File(dir, SessionLog.MIGRATED_MARKER)
        assertFalse(SessionLog.migrate(dir, legacy, marker))
        assertFalse(marker.exists())
        assertTrue(legacy.exists())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    // ---- meta 与帧一致性（X05）：索引 len 与磁盘不符时重建 ----

    @Test
    fun indexLenMismatchTriggersRebuild() {
        val dir = tmp.newFolder("sessions5")
        val file = File(dir, "777.jsonl")
        assertTrue(SessionLog.writeFull(file, ChatSession(id = "777", title = "索引测试", messages = listOf(msg("x")))))
        // 写一份「len 过期」的索引（X05 一致性检查应判 stale）
        val idx = File(tmp.root, "sessions.idx.json")
        val entry = SessionStore.sessionFieldsToJson(
            ChatSession(id = "777", title = "索引测试", messages = listOf(msg("x")))
        )
        entry.put("len", 1L) // 磁盘文件远大于 1 字节
        org.json.JSONObject()
            .put("v", 1)
            .put("sessions", org.json.JSONArray().put(entry))
            .also { idx.writeText(it.toString(), Charsets.UTF_8) }
        val summaries = SessionIndex.summaries(dir, idx)
        assertEquals(1, summaries.size)
        assertEquals("索引测试", summaries[0].session.title)
        assertEquals(file.length(), summaries[0].len)
        // 重建后的索引再次读取不再触发重建（len 一致）
        val again = SessionIndex.summaries(dir, idx)
        assertEquals(summaries[0].len, again[0].len)
    }
}
