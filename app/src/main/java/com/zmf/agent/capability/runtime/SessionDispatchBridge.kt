package com.zmf.agent.capability.runtime

import com.zmf.agent.capability.port.DeliveryResult

/**
 * 会话间投递桥：助手工具 send_message 经此向目标会话投递一轮。
 *
 * 投递实现由组合根装配；本对象只持有窄接口，不依赖 feature 层，也不持有协程作用域。
 * 投递只负责把消息写进目标会话并启动回合，完成与否由目标会话的落盘消息与 [PeerCollab] 按节点判定。
 */
object SessionDispatchBridge {

    /** 投递实现；未装配时为 null，投递返回 [DeliveryResult.Failed]。 */
    @Volatile
    var port: Port? = null

    /** 投递接口：目标会话、正文、来源会话与当前跳数。 */
    interface Port {
        fun deliver(sessionId: String, text: String, from: String, hop: Int): DeliveryResult
    }

    /** 投递一条消息；端口未装配时返回 `E_NO_DISPATCH`。 */
    fun deliver(sessionId: String, text: String, from: String, hop: Int): DeliveryResult {
        val target = port ?: return DeliveryResult.Failed("E_NO_DISPATCH")
        return target.deliver(sessionId, text, from, hop)
    }
}
