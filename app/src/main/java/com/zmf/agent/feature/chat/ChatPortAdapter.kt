package com.zmf.agent.feature.chat

import com.zmf.agent.capability.runtime.ChatPort
import com.zmf.agent.capability.runtime.ChatPortSession
import com.zmf.agent.core.model.ChatMessage
import kotlinx.coroutines.CoroutineScope

/**
 * ChatViewModel 的窄口实现：小窗与定时投递只认这几个方法，不认整个 ViewModel。
 *
 * 单独成类，而非让 ChatViewModel 直接实现 [ChatPort]：门面需保持「只装配 + 转发」，
 * 同时避免 `messages` 同名字段在接口中改变类型。方法逐个转发，行为与原
 * 桥直接持有 ViewModel 时一致。
 */
class ChatPortAdapter(private val viewModel: ChatViewModel) : ChatPort {

    override fun messages(): List<ChatMessage> = viewModel.messages

    override fun send(text: String, scope: CoroutineScope) = viewModel.send(text, scope)

    override fun title(): String = viewModel.currentTitle()

    // 盘上数据可能被助手工具改过（新建工作区、移动会话），会话与工作区一起刷新
    override fun reloadSessions() = viewModel.reloadFromDisk()

    /**
     * 小窗会话切换列表：只列未归档，按最近更新排序。
     *
     * 读取 `sessionList` 这一 Compose 状态，会话增减或改名会使小窗重组；
     * 归档会话在小窗中没有恢复入口，故不列出。
     */
    override fun sessions(): List<ChatPortSession> {
        val current = viewModel.currentSessionId
        return viewModel.sessionList
            .asSequence()
            .filter { !it.archived && it.listed }
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
