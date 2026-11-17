package com.zmf.agent.capability.runtime

import com.zmf.agent.capability.port.ContextMetrics
import com.zmf.agent.capability.port.ContextReading

/**
 * 上下文读数的持有者。
 *
 * 聊天页在每次刷新上下文后写入一份不可变快照；工具只读该快照，不直接访问界面状态。
 * 未绑定时返回 null，工具据此返回 `E_CONTEXT_UNAVAILABLE`。
 */
object ContextStatusBridge : ContextMetrics {

    @Volatile
    private var current: ContextReading? = null

    /** 写入当前会话读数；会话切换时由聊天页覆盖。 */
    fun publish(reading: ContextReading?) {
        current = reading
    }

    override fun read(sessionId: String): ContextReading? {
        val value = current ?: return null
        if (sessionId.isNotEmpty() && value.sessionId != sessionId) return null
        return value
    }
}
