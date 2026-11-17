package com.zmf.agent.core.store

import com.zmf.agent.core.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧失败正文 → turn-error 节点的清洗（319 兼容）：
 * 命中后正文置空、errorText 去重、errorCode 记 UNKNOWN；正常正文与已有错误节点不变。
 */
class SessionStoreLegacyErrorTest {

    /** 旧失败正文 → 错误节点：正文清空，重复的「请求失败」只留一次。 */
    @Test
    fun legacyFailureBodyBecomesErrorNode() {
        val legacy = ChatMessage(role = "assistant", content = "请求失败：请求失败，请检查网络")

        val (cleaned, changed) = SessionStore.cleanLegacyTurnErrors(listOf(legacy))

        assertTrue(changed)
        val node = cleaned.single()
        assertEquals("", node.content)
        assertEquals("请检查网络", node.errorText)
        assertEquals("UNKNOWN", node.errorCode)
    }

    /** 正常 assistant 正文原样返回（列表对象都不换）。 */
    @Test
    fun normalAssistantBodyIsUntouched() {
        val normal = ChatMessage(role = "assistant", content = "已检查网络连接，接下来继续执行。")
        val messages = listOf(normal)

        val (cleaned, changed) = SessionStore.cleanLegacyTurnErrors(messages)

        assertFalse(changed)
        assertSame(messages, cleaned)
        assertEquals(normal, cleaned.single())
    }

    /** 已经是 turn-error 节点的消息（有 errorText）不再清洗。 */
    @Test
    fun existingErrorNodeIsUntouched() {
        val node = ChatMessage(
            role = "assistant",
            content = "",
            errorText = "请求失败，请检查网络",
            errorCode = "RATE_LIMIT"
        )

        val (cleaned, changed) = SessionStore.cleanLegacyTurnErrors(listOf(node))

        assertFalse(changed)
        assertEquals(node, cleaned.single())
        assertEquals("RATE_LIMIT", cleaned.single().errorCode)
    }

    /** 幂等：清洗结果再走一遍不再命中。 */
    @Test
    fun cleaningIsIdempotent() {
        val legacy = ChatMessage(role = "assistant", content = "请求失败：请求失败，请检查网络")

        val (first, firstChanged) = SessionStore.cleanLegacyTurnErrors(listOf(legacy))
        val (second, secondChanged) = SessionStore.cleanLegacyTurnErrors(first)

        assertTrue(firstChanged)
        assertFalse(secondChanged)
        assertEquals(first, second)
    }

    /** 319 已写成的错误节点若仍带重复前缀，只改文案、保留原错误码。 */
    @Test
    fun duplicatedPrefixInExistingErrorNodeIsNormalized() {
        val node = ChatMessage(
            role = "assistant",
            content = "",
            errorText = "请求失败：请求失败，请检查网络连接后重试",
            errorCode = "TRANSPORT"
        )

        val (cleaned, changed) = SessionStore.cleanLegacyTurnErrors(listOf(node))

        assertTrue(changed)
        assertEquals("请检查网络连接后重试", cleaned.single().errorText)
        assertEquals("TRANSPORT", cleaned.single().errorCode)

        val (again, againChanged) = SessionStore.cleanLegacyTurnErrors(cleaned)
        assertFalse(againChanged)
    }
}
