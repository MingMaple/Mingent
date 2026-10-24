package com.zmf.agent.ui.float

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.ui.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 悬浮球小窗 ↔ 聊天状态的数据桥。
 *
 * 小窗 Service 和主 App 是同一个进程，所以不走 AIDL、不走 Binder：
 * 抽屉（或 ChatScreen）把 [ChatViewModel] 的引用塞进来，小窗直接读它的 Compose 状态。
 * Service 注册时机由 App 侧决定（本对象只管存取，写成可空 var，注销时赋 null 即可）。
 *
 * 线程：Service 和 App 的主线程是同一个，读状态都在主线程，不需要额外同步；
 * 引用本身用 @Volatile 兜住「别的线程读一次」的极端情况。
 */
object ChatBridge {

    /** 真正的引用；对外通过 [viewModel] 暴露（写的时候顺带 bump 快照版本）。 */
    @Volatile
    private var bound: ChatViewModel? = null

    /**
     * 绑定版本号：绑上来 / 换绑 / 注销时 +1。
     * 它是 Compose 快照状态，读过 [messages] / [title] 的组合会被它通知重组，
     * 否则「Service 先起、App 后注册」这种情况下小窗会一直停在空列表。
     */
    private var bindVersion by mutableStateOf(0)

    /**
     * 小窗发消息用的作用域：ChatViewModel.send(text, scope) 的签名要一个 scope，
     * 它自己并不持有（主界面那边是 rememberCoroutineScope 传进去的）。
     * 这里用 Main.immediate：状态写入和流式回调本来就要在主线程。
     */
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * App 侧注册位：把 ChatViewModel 赋进来即可；注销时赋 null。
     * 类型必须是可写的 ChatViewModel?，注册方会拿它做 === 判重。
     */
    var viewModel: ChatViewModel?
        get() = bound
        set(value) {
            bound = value
            bindVersion++
            FileLog.log("float", if (value == null) "小窗数据桥已注销" else "小窗数据桥已绑定")
        }

    /**
     * 当前会话的原始消息列表（直接返回 SnapshotStateList，不 copy）：
     * 组合里遍历它就会自动订阅，新消息进来小窗自己重组。
     */
    fun messages(): List<ChatMessage> = current()?.messages ?: emptyList()

    /**
     * 小窗发送一条消息。
     * 流式中（isStreaming）时 ChatViewModel.send 内部会直接返回，不会打断正在生成的回复；
     * 这里只做空串过滤与异常兜底，免得小窗把服务搞崩。
     */
    fun send(text: String) {
        val target = current() ?: return
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        runCatching { target.send(prompt, bridgeScope) }
            .onFailure { FileLog.log("float", "小窗发送失败：" + it) }
    }

    /** 会话名：ChatViewModel.currentTitle() 已处理过「没标题 = 新会话」，取不到就兜底成「会话」。 */
    fun title(): String {
        val target = current() ?: return "会话"
        return runCatching { target.currentTitle() }.getOrDefault("会话").ifBlank { "会话" }
    }

    /** 读一次快照版本 + 拿引用：绑定/换绑能触发重组。 */
    @Suppress("UNUSED_EXPRESSION")
    private fun current(): ChatViewModel? {
        // 这一行是 Compose 快照读，不要删
        bindVersion
        return bound
    }
}
