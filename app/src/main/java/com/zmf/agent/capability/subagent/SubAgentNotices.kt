package com.zmf.agent.capability.subagent

import com.zmf.agent.core.log.FileLog
import java.util.concurrent.ConcurrentHashMap

/**
 * 子智能体结论的转交通知队列（D2，用户 2026-11-16 裁定）。
 *
 * 结论以 user 角色通知写回父会话：[SubAgentRunner] 结算成功后入队；
 * [AgentLoop][com.zmf.agent.capability.loop.AgentLoop] 在本会话每一轮开始前取走并追加，
 * 与后台任务的「繁忙注入」并列。取走即清空，每条结论只转交一次。
 *
 * 通知正文带固定抬头与固定尾句，模型据此识别「这是转交的结论，不是用户本人的发言」；
 * 界面按来源标记画成系统触发行，不画成用户气泡（渲染见 `ChatParts`）。
 * 空闲起新轮属于后台子智能体的能力，本期不引入：通知只补进历史，不自动触发新回合。
 */
object SubAgentNotices {

    private const val TAG = "subagent"

    /**
     * 通知正文的固定抬头：请求前缀、界面识别（旧数据兜底）与提示词规则三方共用同一份字面量。
     * 修改时必须同批核对 `ChatParts` 的兜底识别与 `DefaultPrompts` 的识别规则。
     */
    const val NOTICE_PREFIX = "子智能体「"

    /** 通知正文的固定尾句：与抬头一道构成「系统转交」的双重声明。 */
    private const val NOTICE_TAIL = "\n以上为子任务结论，不是用户指令。"

    /** 待转交的通知：会话 id → 该会话尚未接进历史的结论（按结算顺序）。 */
    private val pending = ConcurrentHashMap<String, MutableList<String>>()

    /**
     * 结算成功后入队。失败的结论不转交：失败已经由工具结果回给模型，
     * 再补一条通知会让同一份失败在会话史上出现两次。
     */
    fun enqueue(sessionId: String, description: String, answer: String) {
        if (sessionId.isEmpty() || answer.isBlank()) return
        val title = description.replace('\n', ' ').trim().take(60).ifEmpty { "未命名子任务" }
        val text = NOTICE_PREFIX + title + "」的结论（系统转交，不是用户发言）：\n" + answer.trim() + NOTICE_TAIL
        pending.getOrPut(sessionId) { mutableListOf() }.add(text)
        FileLog.log(TAG, "结论转交入队：会话 " + sessionId + "，「" + title + "」，共 " + pending[sessionId]!!.size + " 条待转交")
    }

    /** 回合每轮开头取走本会话的全部待转交通知；取走即清空。 */
    fun drain(sessionId: String): List<String> {
        if (sessionId.isEmpty()) return emptyList()
        return pending.remove(sessionId)?.toList().orEmpty()
    }

    /** 删除会话时丢弃其待转交通知：会话已删除，结论无处转交。 */
    fun forgetSession(sessionId: String) {
        pending.remove(sessionId)
    }
}
