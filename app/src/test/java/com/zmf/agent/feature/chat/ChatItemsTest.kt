package com.zmf.agent.feature.chat

import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ORIGIN_JOB
import com.zmf.agent.core.model.ORIGIN_SUBAGENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表分段口径：每条真实提问都要独立成一个用户气泡，只有系统转交通知才并入相邻助手回合。
 *
 * 回归背景：通知判定由 Boolean 改成文案后，助手下标收集的终止条件被写反，
 * 普通用户消息被吞进上一轮，界面上除首条提问外全部气泡消失。
 */
class ChatItemsTest {

    private fun user(text: String) = ChatMessage(role = "user", content = text)

    private fun notice(text: String, origin: String) =
        ChatMessage(role = "user", content = text, programmatic = true, origin = origin)

    private fun assistant(text: String) = ChatMessage(role = "assistant", content = text)

    @Test
    fun everyUserMessageGetsItsOwnBubble() {
        val items = buildChatItems(
            listOf(
                user("第一问"),
                assistant("第一答"),
                user("第二问"),
                assistant("第二答")
            )
        )

        assertEquals(4, items.size)
        assertEquals(listOf("第一问", "第二问"), items.filterIsInstance<ChatItem.UserItem>().map { it.message.content })
        assertEquals(listOf(listOf(1), listOf(3)), items.filterIsInstance<ChatItem.AssistantItem>().map { it.indices })
    }

    @Test
    fun jobNoticeStaysInOneRoundWithoutBubble() {
        val items = buildChatItems(
            listOf(
                user("提问"),
                assistant("调用工具"),
                notice("后台任务已结算", ORIGIN_JOB),
                assistant("最终答复")
            )
        )

        assertEquals(2, items.size)
        assertEquals("提问", items.filterIsInstance<ChatItem.UserItem>().single().message.content)
        val round = items.filterIsInstance<ChatItem.AssistantItem>().single()
        assertEquals(listOf(1, 3), round.indices)
        assertEquals("后台任务状态更新", round.trigger)
    }

    @Test
    fun subAgentNoticeIsLabelledAndStillDoesNotHideNextQuestion() {
        val items = buildChatItems(
            listOf(
                user("提问"),
                notice("子智能体结论", ORIGIN_SUBAGENT),
                assistant("答复"),
                user("追问"),
                assistant("再答")
            )
        )

        val users = items.filterIsInstance<ChatItem.UserItem>()
        assertEquals(listOf("提问", "追问"), users.map { it.message.content })
        val rounds = items.filterIsInstance<ChatItem.AssistantItem>()
        assertEquals(2, rounds.size)
        assertEquals("子智能体结论回传", rounds.first().trigger)
        assertTrue(rounds.last().trigger == null)
    }
}
