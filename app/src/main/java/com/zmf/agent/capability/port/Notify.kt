package com.zmf.agent.capability.port

/** 通知类别；只决定文案前缀，不新建通知渠道。 */
enum class NotifyCategory { INFO, DONE, ATTENTION }

/** 通知发送结果；未送达时 [reason] 说明原因。 */
data class NotifyOutcome(val delivered: Boolean, val reason: String, val id: Int)

/**
 * 用户通知端口。
 *
 * 仅用于应用不在前台时提醒用户；实现复用既有通知渠道与一次性会话令牌。
 */
interface UserNotifier {
    fun notify(title: String, body: String, sessionId: String, category: NotifyCategory): NotifyOutcome

    /** 当前是否允许发送系统通知（权限已授予）。 */
    fun allowed(): Boolean
}
