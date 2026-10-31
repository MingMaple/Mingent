package com.zmf.agent.feature.chat

import com.zmf.agent.capability.runtime.ChatPort
import com.zmf.agent.capability.runtime.ChatPortSession
import com.zmf.agent.core.model.ChatMessage
import kotlinx.coroutines.CoroutineScope

/**
 * ChatViewModel 的窄口实现：小窗与定时投递只认这几个方法，不认整个 ViewModel。
 *
 * 单独成类而不是让 ChatViewModel 直接实现 [ChatPort]：门面要保持「只装配 + 转发」，
 * 也避免 `messages` 这个同名字段在接口里换类型。方法逐个转发，行为与原来
 * 桥直接攥住 ViewModel 时一致。
 */
class ChatPortAdapter(private val viewModel: ChatViewModel) : ChatPort {

    override fun messages(): List<ChatMessage> = viewModel.messages

    override fun send(text: String, scope: CoroutineScope) = viewModel.send(text, scope)

    override fun title(): String = viewModel.currentTitle()

    override fun reloadSessions() = viewModel.reloadSessionsFromDisk()

    /**
     * 小窗会话切换列表：只列未归档，按最近更新排序。
     *
     * 读完 `sessionList` 这个 Compose 状态，会话增减/改名会让小窗跟着重组；
     * 归档的会话在小窗里没有恢复入口，干脆不列出来。
     */
    override fun sessions(): List<ChatPortSession> {
        val current = viewModel.currentSessionId
        return viewModel.sessionList
            .asSequence()
            .filter { !it.archived }
            .sortedByDescending { it.updatedAt }
            .map {
                ChatPortSession(
                    id = it.id,
                    title = it.title.ifBlank { "新会话" },
                    isCurrent = it.id == current
                )
            }
            .toList()
    }

    override fun switchSession(id: String) = viewModel.switchSession(id)
}
