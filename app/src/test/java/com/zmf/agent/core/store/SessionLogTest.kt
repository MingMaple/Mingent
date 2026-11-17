package com.zmf.agent.core.store

import com.zmf.agent.core.model.ChatMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 事件流回归（D1.3-C/D1.3-E）：整份写入 → 读取折叠一致、补偿事件（trunc / msg.upd）读取、
 * seq 断号按帧截断、缺 header 判损坏。
 */
class SessionLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun session(
        id: String = "1001",
        title: String = "标题",
        messages: List<ChatMessage> = listOf(
            ChatMessage(role = "user", content = "你好", createdAt = 10L),
            ChatMessage(role = "assistant", content = "在的", createdAt = 11L)
        )
    ): ChatSession = ChatSession(
        id = id,
        title = title,
        messages = messages,
        updatedAt = 123L,
        turns = 1,
        wallMs = 456L
    )

    /** writeFull → read：字段与消息逐字还原。 */
    @Test
    fun writeFullThenReadFoldsBack() {
        val file = File(tmp.root, "1001.jsonl")
        val original = session()
        assertTrue(SessionLog.writeFull(file, original))
        val fold = SessionLog.read(file)
        assertEquals("1001", fold!!.header.id)
        assertEquals(original.updatedAt, fold.fields.optLong("updatedAt"))
        assertEquals(original.title, fold.fields.optString("title"))
        assertEquals(original.turns, fold.fields.optInt("turns"))
        assertEquals(original.wallMs, fold.fields.optLong("wallMs"))
        assertEquals(original.messages.size, fold.chatMessages.size)
        assertEquals(original.messages, fold.chatMessages)
        // meta 占 seq 0，两条 msg 占 1、2
        assertEquals(2L, fold.seq)
    }

    /** 补偿事件读取：trunc（回滚）+ 重新追加 + msg.upd（用量回填）折叠正确。 */
    @Test
    fun compensatingEventsFoldCorrectly() {
        val file = File(tmp.root, "1002.jsonl")
        val messages = mutableListOf(
            ChatMessage(role = "user", content = "问一", createdAt = 1L),
            ChatMessage(role = "assistant", content = "答一", createdAt = 2L),
            ChatMessage(role = "user", content = "问二", createdAt = 3L),
            ChatMessage(role = "assistant", content = "答二", createdAt = 4L)
        )
        assertTrue(SessionLog.writeFull(file, session("1002", messages = messages)))
        val fold = SessionLog.read(file)!!

        // 回滚到只剩前 2 条，再追加新一轮（事件流下的「重新生成」路径）
        val events = listOf(SessionLog.truncEvent(0, 0L, 2)) + listOf(
            SessionLog.msgEvent(0, 0L, 2, SessionStore.messageToJson(ChatMessage(role = "user", content = "问三", createdAt = 5L))),
            SessionLog.msgEvent(0, 0L, 3, SessionStore.messageToJson(ChatMessage(role = "assistant", content = "答三", createdAt = 6L)))
        )
        assertTrue(SessionLog.appendEvents(file, fold, events))
        val reread = SessionLog.read(file)!!
        assertEquals(
            listOf("问一", "答一", "问三", "答三"),
            reread.chatMessages.map { it.content }
        )
        assertEquals(7L, reread.seq)

        // 用量回填：整条替换最后一条 assistant（msg.upd）
        val last = reread.chatMessages.last()
        val updated = last.copy(completionTokens = 88, promptTokens = 7)
        assertTrue(
            SessionLog.appendEvents(
                file,
                reread,
                listOf(SessionLog.updEvent(0, 0L, 3, SessionStore.messageToJson(updated)))
            )
        )
        val final = SessionLog.read(file)!!
        assertEquals(4, final.chatMessages.size)
        assertEquals(88, final.chatMessages[3].completionTokens)
        assertEquals(7, final.chatMessages[3].promptTokens)
        assertEquals("答三", final.chatMessages[3].content)
    }

    /** seq 断号：坏帧起整段按帧边界截断，坏帧之前的帧完整保留。 */
    @Test
    fun seqGapTruncatesToFrameBoundary() {
        val file = File(tmp.root, "1003.jsonl")
        val fold = SessionLog.read(file) ?: run {
            assertTrue(SessionLog.writeFull(file, session("1003", messages = emptyList())))
            SessionLog.read(file)!!
        }
        // 第一批事件（seq 接续 3、4）落一帧
        assertTrue(
            SessionLog.appendEvents(
                file,
                fold,
                listOf(
                    SessionLog.metaEvent(0, 0L, JSONObject().put("title", "改过的标题")),
                    SessionLog.msgEvent(0, 0L, 0, SessionStore.messageToJson(ChatMessage(role = "user", content = "A", createdAt = 20L)))
                )
            )
        )
        val frame1End = file.length()
        // 第二批事件带断号（fold.seq+1 之外）：写入时重编号，需要手工构造断号帧——
        // 直接以 ZstdFrames 追加一个含跳号事件的帧
        val gapLine = JSONObject()
            .put("t", "meta")
            .put("seq", fold.seq + 9) // 断号
            .put("at", 0L)
            .put("p", JSONObject().put("title", "不该读到的标题"))
            .toString() + "\n"
        assertTrue(ZstdFrames.appendFrame(file, gapLine.toByteArray(Charsets.UTF_8)))
        val reread = SessionLog.read(file)!!
        // 断号帧被截掉：标题停在上一帧的值，文件长度回到帧边界
        assertEquals("改过的标题", reread.fields.optString("title"))
        assertEquals(frame1End, file.length())
        assertEquals("A", reread.chatMessages.lastOrNull()?.content)
    }

    /** 缺 header（id 与文件名不符）判损坏：文件移入 .corrupt/，read 返回 null。 */
    @Test
    fun mismatchedHeaderIsQuarantined() {
        val file = File(tmp.root, "1004.jsonl")
        assertTrue(SessionLog.writeFull(file, session(id = "9999")))
        assertNull(SessionLog.read(file))
        val corruptDir = File(tmp.root, ".corrupt")
        assertTrue(corruptDir.isDirectory)
        assertTrue(corruptDir.listFiles()!!.isNotEmpty())
        assertFalse(file.exists())
    }

    /** mno 不连续同样判为损坏帧并截断。 */
    @Test
    fun msgOrdinalGapTruncates() {
        val file = File(tmp.root, "1005.jsonl")
        assertTrue(SessionLog.writeFull(file, session("1005", messages = emptyList())))
        val fold = SessionLog.read(file)!!
        val base = file.length()
        // 跳号 msg（mno=1，但当前 messages.size=0）
        val badLine = JSONObject()
            .put("t", "msg")
            .put("seq", fold.seq + 1)
            .put("at", 0L)
            .put("mno", 1)
            .put("m", SessionStore.messageToJson(ChatMessage(role = "user", content = "X", createdAt = 1L)))
            .toString() + "\n"
        assertTrue(ZstdFrames.appendFrame(file, badLine.toByteArray(Charsets.UTF_8)))
        val reread = SessionLog.read(file)!!
        assertEquals(base, file.length())
        assertTrue(reread.chatMessages.isEmpty())
    }

    /** 追加失败不留半帧：appendFrame 返回 false 时文件长度不变（由帧层测试覆盖语义，这里补跨层一致性）。 */
    @Test
    fun appendKeepsSeqMonotonicAcrossRuns() {
        val file = File(tmp.root, "1006.jsonl")
        assertTrue(SessionLog.writeFull(file, session("1006")))
        val fold1 = SessionLog.read(file)!!
        // 重新从磁盘折叠（模拟进程重启）：seq 必须接得上。
        // 注意 appendEvents 会把 fold1.seq 原地推进（内存与磁盘同源推进是设计行为），
        // 因此接续点必须在追加之前捕获。
        val nextSeq = fold1.seq + 1
        assertTrue(
            SessionLog.appendEvents(
                file,
                fold1,
                listOf(SessionLog.metaEvent(0, 0L, JSONObject().put("pinned", true)))
            )
        )
        val fold2 = SessionLog.read(file)!!
        assertEquals(nextSeq, fold2.seq)
        assertEquals(true, fold2.fields.optBoolean("pinned"))
    }
}
