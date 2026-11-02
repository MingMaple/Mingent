package com.zmf.agent.capability.runtime

import com.zmf.agent.core.model.ChatMessage
import kotlinx.coroutines.CoroutineScope

/**
 * 小窗会话切换列表中的一行：仅包含可点击与可识别的信息，不带图标和工作区。
 *
 * [isCurrent] 用来高亮当前会话；标题为空时实现方已兜底成「新会话」。
 */
data class ChatPortSession(
    val id: String,
    val title: String,
    val isCurrent: Boolean = false
)

/**
 * 小窗与定时任务投递到聊天面板的窄接口。
 *
 * 悬浮窗与定时任务只需读取消息、发送消息、获取标题、通知界面重读、列出会话和切换会话，
 * 不应依赖 ChatViewModel 这一界面级对象；实现位于 feature/chat，本层只定义接口。
 */
interface ChatPort {

    /** 当前会话的消息表：实现方直接返回那份可观察列表，组合里读它就会自动重组。 */
    fun messages(): List<ChatMessage>

    /** 小窗发送一条消息；scope 由调用方提供（ChatBridge 使用 Main.immediate 作用域）。 */
    fun send(text: String, scope: CoroutineScope)

    /** 会话名；没有标题时的兜底由实现方或调用方决定。 */
    fun title(): String

    /** 后台（定时任务）改过会话文件之后，让界面重读一次。 */
    fun reloadSessions()

    /**
     * 小窗会话切换用的列表：按最近更新排序、不含已归档（归档会话在小窗里没有入口）。
     *
     * 返回普通 List 即可：实现方每次读都取最新快照，小窗在重组时调用。
     */
    fun sessions(): List<ChatPortSession>

    /** 在小窗中点击某条会话：切换至该会话（实现方负责落盘与消息表替换）。 */
    fun switchSession(id: String)
}
