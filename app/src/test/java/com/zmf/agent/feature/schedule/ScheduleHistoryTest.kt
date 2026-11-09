package com.zmf.agent.feature.schedule

import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 定时任务的历史携带口径（纯逻辑，不依赖 Android 框架）。
 *
 * 约定：与普通对话一致，原样携带目标会话的全部消息。不按「与本轮指令逐字相同」删减历次投递，
 * 不截取窗口，也不过过滤工具消息；请求视图的裁剪由 AgentLoop 按会话摘要处理（见 [ScheduleSummaryTest]），
 * 工具消息的配对由 sanitized() 清洗。
 */
class ScheduleHistoryTest {

    private val prompt = "每天七点整理当日新闻"

    private fun user(text: String) = ChatMessage(role = "user", content = text)

    private fun assistant(text: String) = ChatMessage(role = "assistant", content = text)

    @Test
    fun samePromptDeliveries_areKept() {
        val history = listOf(
            user(prompt), assistant("第一天的答复"),
            user(prompt), assistant("第二天的答复"),
            user(prompt)
        )
        val out = ScheduleRunner.scheduledHistoryOf(history)
        assertEquals(5, out.size)
        assertEquals("第一天的答复", out[1].content)
        assertEquals("第二天的答复", out[3].content)
    }

    @Test
    fun longHistory_isNotTruncated() {
        val history = (1..200).map { assistant("第 $it 条") }
        val out = ScheduleRunner.scheduledHistoryOf(history)
        assertEquals(200, out.size)
        assertEquals("第 1 条", out.first().content)
        assertEquals("第 200 条", out.last().content)
    }

    @Test
    fun toolMessages_areKept() {
        val call = ToolCall(id = "call-1", name = "write_file", arguments = "{\"path\":\"a.md\"}")
        val history = listOf(
            user(prompt),
            ChatMessage(role = "assistant", content = "", toolCalls = listOf(call)),
            ChatMessage(role = "tool", content = "已写入 a.md", toolCallId = "call-1", toolName = "write_file")
        )
        val out = ScheduleRunner.scheduledHistoryOf(history)
        assertEquals(3, out.size)
        assertEquals("tool", out[2].role)
        assertEquals("call-1", out[2].toolCallId)
        assertEquals(1, out[1].toolCalls.size)
    }

    @Test
    fun order_isPreserved() {
        val history = listOf(user("一"), assistant("二"), user("三"), assistant("四"))
        val out = ScheduleRunner.scheduledHistoryOf(history)
        assertEquals(listOf("一", "二", "三", "四"), out.map { it.content })
    }
}
