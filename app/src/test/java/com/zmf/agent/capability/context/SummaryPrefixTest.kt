package com.zmf.agent.capability.context

import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摘要前缀的工具配对口径。
 *
 * 回归背景（2026-11-16）：长会话命中摘要闸门时固定返回 HTTP 400
 * 「assistant message with 'tool_calls' must be followed by tool messages」，
 * 压缩从未成功。原因是摘要前缀把区间原文逐条重放，未做与主请求视图相同的配对清洗：
 * 回合被中断、插话落在工具结果之前、工具组只回来一部分时，历史里本就存在半组工具调用，
 * 主请求靠 sanitized() 丢弃、摘要请求却原样带上。
 */
class SummaryPrefixTest {

    private fun user(text: String) = ChatMessage(role = "user", content = text)

    private fun assistant(text: String) = ChatMessage(role = "assistant", content = text)

    private fun calls(vararg ids: String) = ChatMessage(
        role = "assistant",
        content = "",
        toolCalls = ids.map { ToolCall(it, "terminal", "{}") }
    )

    private fun result(id: String) = ChatMessage(role = "tool", content = "ok", toolCallId = id)

    /** 与主请求视图同一套 API 约束：tool 结果必须紧跟其调用，且数量与 id 必须配平；末尾不得停在调用上。 */
    private fun assertApiLegal(messages: List<ChatMessage>) {
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            assertFalse("出现空 assistant 占位：下标 $i", m.role == "assistant" && m.content.isBlank() && m.toolCalls.isEmpty())
            if (m.role == "assistant" && m.toolCalls.isNotEmpty()) {
                var j = i + 1
                val got = HashSet<String>()
                while (j < messages.size && messages[j].role == "tool") {
                    messages[j].toolCallId?.let { got.add(it) }
                    j++
                }
                assertEquals(
                    "assistant(tool_calls) 与 tool 结果不配平：下标 $i",
                    m.toolCalls.map { it.id }.toSet(),
                    got
                )
                i = j
                continue
            }
            assertFalse("出现孤立 tool 消息：下标 $i", m.role == "tool")
            i++
        }
        assertFalse(
            "前缀末尾停在 assistant(tool_calls) 上",
            messages.lastOrNull()?.let { it.role == "assistant" && it.toolCalls.isNotEmpty() } == true
        )
    }

    @Test
    fun trailingToolCallGroupIsMovedOutOfSummaryRange() {
        // 分界 2 之前是一条带 tool_calls 的 assistant，它的结果落在分界之后（中断后的迟到结果）
        val history = listOf(
            user("第一问"),
            calls("c1"),
            user("第二问"),
            result("c1"),
            assistant("第二答")
        )

        val end = ContextCompactor.summaryEndAligned(history, 0, 2)
        assertEquals("应整轮移出，回到第一问之前没有可摘要内容", 0, end)

        // 末端对齐无法前移时，清洗仍要保证前缀合法（丢弃未闭合的调用）
        val prefix = history.subList(0, 2).sanitizeToolPairing()
        assertApiLegal(prefix)
        assertEquals(listOf("user"), prefix.map { it.role })
    }

    @Test
    fun trailingIncompleteGroupMovesBackToWholeRound() {
        // 三轮：第三轮在分界处只回来一半工具结果
        val history = listOf(
            user("第一问"),
            assistant("第一答"),
            user("第二问"),
            assistant("第二答"),
            user("第三问"),
            calls("c1", "c2"),
            result("c1")
        )

        val end = ContextCompactor.summaryEndAligned(history, 0, 7)
        assertEquals("应把第三轮整轮移出摘要区间", 4, end)

        val prefix = history.subList(0, end).sanitizeToolPairing()
        assertApiLegal(prefix)
    }

    @Test
    fun completeGroupAtTailKeepsRangeEnd() {
        val history = listOf(
            user("第一问"),
            assistant("第一答"),
            user("第二问"),
            calls("c1"),
            result("c1")
        )

        assertEquals(5, ContextCompactor.summaryEndAligned(history, 0, 5))
        val prefix = history.sanitizeToolPairing()
        assertApiLegal(prefix)
        assertEquals(5, prefix.size)
    }

    @Test
    fun incompleteGroupInMiddleIsDroppedWhole() {
        // 贴近真机导出 1794810976689 的形态：调用与结果之间夹进了用户消息与助手消息
        val history = listOf(
            user("第一问"),
            calls("c1"),
            assistant("先说明一句"),
            user("插话"),
            result("c1"),
            assistant("答复"),
            user("第二问"),
            assistant("第二答")
        )

        val view = history.sanitizeToolPairing()
        assertApiLegal(view)
        assertEquals(listOf("user", "assistant", "user", "assistant", "user", "assistant"), view.map { it.role })
        assertEquals("插话", view[2].content)
        assertFalse("迟到的孤立 tool 结果必须丢弃", view.any { it.role == "tool" })
    }

    @Test
    fun leadingOrphanToolIsSkippedToUserBoundary() {
        val history = listOf(
            result("c0"),
            user("第一问"),
            assistant("第一答")
        )

        val view = history.subList(0, 3).sanitizeToolPairing()
        assertApiLegal(view)
        assertEquals(2, view.size)
        assertEquals("user", view.first().role)
    }

    @Test
    fun everyUserBoundaryOfRealisticHistoryIsApiLegal() {
        // 真机同构历史：连续 assistant 占位、无结果的调用、迟到的 isolated 结果并存
        val history = listOf(
            user("问一"),
            calls("a1"),
            result("a1"),
            assistant("答一"),
            assistant(""),
            calls("a2"),
            assistant(""),
            assistant("答二"),
            user("问二"),
            calls("a3"),
            assistant("先说明"),
            user("插话"),
            result("a3"),
            assistant("答复"),
            user("问三"),
            assistant("答三")
        )

        for (boundary in history.indices) {
            if (history[boundary].role != "user") continue
            val end = ContextCompactor.summaryEndAligned(history, 0, boundary)
            val prefix = history.subList(0, end).sanitizeToolPairing()
            assertTrue("边界 $boundary 的前缀必须非空或为空区间", end <= boundary)
            assertApiLegal(prefix)
        }
    }
}
