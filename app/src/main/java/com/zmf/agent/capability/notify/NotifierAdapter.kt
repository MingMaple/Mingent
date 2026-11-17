package com.zmf.agent.capability.notify

import android.content.Context
import com.zmf.agent.capability.port.NotifyCategory
import com.zmf.agent.capability.port.NotifyOutcome
import com.zmf.agent.capability.port.UserNotifier
import com.zmf.agent.core.notify.Notifier

/**
 * 通知端口的系统实现。
 *
 * 只负责发送，不判断前后台，也不做频控：调用方（工具）负责在合适的时机调用，
 * 这样才能在回执中准确说明「为什么没有发送」。
 */
class NotifierAdapter(private val context: Context) : UserNotifier {

    override fun notify(
        title: String,
        body: String,
        sessionId: String,
        category: NotifyCategory
    ): NotifyOutcome {
        if (!allowed()) {
            return NotifyOutcome(false, "denied", 0)
        }
        Notifier.notifyUser(context, title, body, sessionId)
        return NotifyOutcome(true, "posted", USER_ID)
    }

    override fun allowed(): Boolean = Notifier.isAllowed(context)

    private companion object {
        /** 与 [Notifier] 中工具提醒的固定 id 保持一致：同一类提醒只保留最新一条。 */
        const val USER_ID = 2005
    }
}
