package com.zmf.agent.feature.schedule

import com.zmf.agent.core.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时任务的请求视图裁剪（纯逻辑，不依赖 Android 框架）。
 *
 * 约定与聊天页一致：会话有摘要时只发送摘要与分界之后的原文；分界失效按没有摘要处理，发送全部原文。
 * 后台不生成摘要，也不存在「摘要失败」的兜底标记，因此这里不会出现 fallback。
 */
class ScheduleSummaryTest {

    private fun user(text: String) = ChatMessage(role = "user", content = text)

    private fun assistant(text: String) = ChatMessage(role = "assistant", content = text)

    @Test
    fun userBoundary_isUsed() {
        val history = listOf(user("一"), assistant("二"), user("三"), assistant("四"))
        val state = ScheduleRunner.scheduledSummaryState(history, "旧对话摘要", 2)
        assertEquals("旧对话摘要", state.text)
        assertEquals(2, state.upTo)
    }

    @Test
    fun boundaryOnAssistant_isIgnored() {
        val history = listOf(user("一"), assistant("二"), user("三"), assistant("四"))
        val state = ScheduleRunner.scheduledSummaryState(history, "旧对话摘要", 1)
        assertTrue(state.text.isEmpty())
        assertEquals(0, state.upTo)
    }

    @Test
    fun boundaryOutOfRange_isIgnored() {
        val history = listOf(user("一"), assistant("二"))
        assertTrue(ScheduleRunner.scheduledSummaryState(history, "摘要", 2).text.isEmpty())
        assertTrue(ScheduleRunner.scheduledSummaryState(history, "摘要", 0).text.isEmpty())
        assertTrue(ScheduleRunner.scheduledSummaryState(emptyList(), "摘要", 1).text.isEmpty())
    }

    @Test
    fun emptySummary_isIgnored() {
        val history = listOf(user("一"), assistant("二"))
        val state = ScheduleRunner.scheduledSummaryState(history, "", 1)
        assertTrue(state.text.isEmpty())
        assertEquals(0, state.upTo)
    }

    @Test
    fun historyIsNotModified() {
        val history = listOf(user("一"), assistant("二"), user("三"))
        ScheduleRunner.scheduledSummaryState(history, "旧对话摘要", 2)
        assertEquals(listOf("一", "二", "三"), history.map { it.content })
    }
}
