package com.zmf.agent.feature.chat

import com.zmf.agent.capability.runtime.ChatPort
import com.zmf.agent.core.model.ChatMessage
import kotlinx.coroutines.CoroutineScope

/**
 * ChatViewModel 的窄口实现：小窗与定时投递只认这四个方法，不认整个 ViewModel。
 *
 * 单独成类而不是让 ChatViewModel 直接实现 [ChatPort]：门面要保持「只装配 + 转发」，
 * 也避免 `messages` 这个同名字段在接口里换类型。四个方法逐字转发，行为与原来
 * 桥直接攥住 ViewModel 时一致。
 */
class ChatPortAdapter(private val viewModel: ChatViewModel) : ChatPort {

    override fun messages(): List<ChatMessage> = viewModel.messages

    override fun send(text: String, scope: CoroutineScope) = viewModel.send(text, scope)

    override fun title(): String = viewModel.currentTitle()

    override fun reloadSessions() = viewModel.reloadSessionsFromDisk()
}
