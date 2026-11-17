package com.zmf.agent.capability.port

/** 投递目标：会话必填，工作区用于目标会话尚未落盘时新建。 */
data class DeliveryTarget(val sessionId: String, val workspaceId: String? = null)

/** 投递结果。 */
sealed interface DeliveryResult {
    /**
     * 已受理，将在目标会话内执行。
     *
     * [at] 是**实际追加到目标会话**的那条 user 消息的 `createdAt`，与投递动作同一次落盘。
     * 调用方以 `{session_id, at}` 作为节点，此后可按节点读取该轮次的落盘消息。
     */
    data class Accepted(val at: Long) : DeliveryResult

    /** 目标会话正忙，未受理。 */
    data object Busy : DeliveryResult

    /** 无法受理；[reason] 为可读原因。 */
    data class Failed(val reason: String) : DeliveryResult
}

/**
 * 会话投递端口。
 *
 * 定时任务、会话间协作与提问回执均经此入口进入目标会话，由目标会话自己的回合执行体负责运行。
 * 实现位于界面层与无界面执行器，端口本身不持有协程作用域。
 */
interface SessionDelivery {
    fun deliver(target: DeliveryTarget, text: String, origin: String): DeliveryResult
}
