package com.zmf.agent.capability.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 悬浮球小窗 ↔ 聊天状态的数据桥。
 *
 * 小窗 Service 与主 App 处于同一进程，因此不使用 AIDL 或 Binder：
 * 界面侧注入 [ChatPort] 的实现，小窗直接读取其 Compose 状态。
 * 本对象只依赖窄接口，不依赖 ChatViewModel；小窗与定时任务共用聊天能力，避免 feature 之间的依赖。
 * Service 的注册时机由 App 侧决定（本对象只负责存取，字段可空，注销时赋 null）。
 *
 * 线程：Service 与 App 的主线程相同，状态读取均在主线程，无需额外同步；
 * 引用本身使用 @Volatile 以保证其他线程可见性。
 */
object ChatBridge {

    /** 真正的引用；对外通过 [port] 暴露（写入时同时递增快照版本）。 */
    @Volatile
    private var bound: ChatPort? = null

    /**
     * 绑定版本号：绑定 / 换绑 / 注销时 +1。
     * 它是 Compose 快照状态，读取过 [messages] / [title] 的组合会因它通知而重组，
     * 否则「Service 先启动、App 后注册」时小窗将始终显示空列表。
     */
    private var bindVersion by mutableStateOf(0)

    /**
     * 小窗发消息用的作用域：ChatPort.send 的签名要一个 scope，
     * 实现方并不持有该作用域（主界面通过 rememberCoroutineScope 传入）。
     * 此处使用 Main.immediate：状态写入与流式回调本就需要在主线程执行。
     */
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * App 侧注册位：将实现赋值即可；注销时赋 null。
     * 注册方会使用 === 判重，因此类型为可写的 ChatPort?。
     */
    var port: ChatPort?
        get() = bound
        set(value) {
            bound = value
            bindVersion++
            FileLog.log("float", if (value == null) "小窗数据桥已注销" else "小窗数据桥已绑定")
        }

    /**
     * 当前会话的原始消息列表（实现方直接返回 SnapshotStateList，不 copy）：
     * 组合中遍历它会自动订阅，新消息到达时小窗自动重组。
     */
    fun messages(): List<ChatMessage> = current()?.messages() ?: emptyList()

    /**
     * 小窗发送一条消息。
     * 流式生成期间（isStreaming）实现方会直接返回，不打断正在生成的回复；
     * 此处仅做空串过滤与异常兜底，避免小窗导致服务异常。
     */
    fun send(text: String) {
        val target = current() ?: return
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        runCatching { target.send(prompt, bridgeScope) }
            .onFailure { FileLog.log("float", "小窗发送失败：" + it) }
    }

    /** 会话名：实现方已处理「无标题即新会话」的情况，取不到时兜底为「会话」。 */
    fun title(): String {
        val target = current() ?: return "会话"
        return runCatching { target.title() }.getOrDefault("会话").ifBlank { "会话" }
    }

    /** 小窗会话切换列表；桥未绑定时返回空列表（小窗显示「还没有其它会话」）。 */
    fun sessions(): List<ChatPortSession> {
        val target = current() ?: return emptyList()
        return runCatching { target.sessions() }.getOrDefault(emptyList())
    }

    /** 在小窗中选中某条会话后切换至该会话。切换完成后消息表由实现方替换，小窗随之重组。 */
    fun switchSession(id: String) {
        val target = current() ?: return
        runCatching { target.switchSession(id) }
            .onFailure { FileLog.log("float", "小窗切换会话失败：" + it) }
    }

    /**
     * 后台（定时任务）修改会话文件后通知界面重新读取。
     *
     * 若缺少此通知，后台追加的会话需等用户重启进程后才可见，因为界面的 sessionList 是内存快照。
     * 切换到主线程执行：reloadSessions 会写入 Compose 状态。
     */
    fun notifySessionsChanged() {
        if (bound == null) return
        bridgeScope.launch {
            val target = bound ?: return@launch
            runCatching { target.reloadSessions() }
                .onFailure { FileLog.log("float", "后台刷新会话失败：" + it) }
        }
    }

    /** 读一次快照版本 + 拿引用：绑定/换绑能触发重组。 */
    @Suppress("UNUSED_EXPRESSION")
    private fun current(): ChatPort? {
        // 此处读取 Compose 快照状态，用于触发重组，不可移除
        bindVersion
        return bound
    }
}
