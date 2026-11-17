package com.zmf.agent.feature.chat

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.zmf.agent.capability.runtime.AskAnswer
import com.zmf.agent.capability.runtime.AskBridge
import com.zmf.agent.capability.runtime.AskQuestion
import com.zmf.agent.capability.runtime.AskRequest
import com.zmf.agent.capability.runtime.AskResponse
import com.zmf.agent.core.AppForeground
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.notify.Notifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 提问卡缺省标题：题目未给出 header 时使用。 */
internal const val QUESTION_DEFAULT_TITLE = "需要你的选择"

/** 通知正文最多列出的选项数；超出时改为一句「共 N 项，点开作答」。 */
private const val NOTIFY_OPTION_LIMIT = 5

/**
 * 提问通道的界面侧实现。
 *
 * 职责有三项：把 [AskRequest] 压入 Compose 状态供消息列渲染、把作答回传为 [AskResponse]、
 * 在应用不处于前台时发出待作答通知。工具侧只认 [AskBridge.Port]，不依赖任何界面组件。
 *
 * 同一会话同时只允许一个未决提问：互斥由 [AskBridge] 保证，本对象只按会话保存一份快照，
 * 不提供绕过该限制的入口。不同会话的挂起提问可以并存，界面按当前会话取用。
 */
internal object QuestionCenter : AskBridge.Port {

    /**
     * 界面渲染所需的提问快照。
     *
     * 一次请求可能包含多道题：卡片逐题展示，作答一道后推进至下一道，
     * [index] 与 [total] 供界面判断是否为最后一题。
     */
    internal data class ActiveAsk(
        val token: String,
        val sessionId: String,
        val question: AskQuestion,
        val index: Int,
        val total: Int,
        /** 截止时刻，口径为 [SystemClock.elapsedRealtime]；null 表示不限时。 */
        val deadline: Long?
    ) {
        /** 卡片短标题。 */
        val title: String get() = question.header.ifBlank { QUESTION_DEFAULT_TITLE }
    }

    /** 一道提问的挂起记录：只在结算时移除，界面状态另行维护。 */
    private class Pending(
        val request: AskRequest,
        val deferred: CompletableDeferred<AskResponse?>,
        var deadline: Long?,
        var index: Int = 0,
        val answers: MutableList<AskAnswer> = ArrayList(),
        /** 到期自动结算的定时任务；延长时先取消再按新时刻重排。 */
        var timeoutJob: Job? = null
    )

    /** 按会话保存的界面快照；未绑定界面时保持为空。 */
    private val activeState = mutableStateOf<Map<String, ActiveAsk>>(emptyMap())

    private val pendings = HashMap<String, Pending>()

    /** 会话到令牌的索引：撤销与提交均按会话或令牌唯一定位。 */
    private val sessionTokens = HashMap<String, String>()

    private val lock = Any()

    /**
     * 时限的执行者。
     *
     * 时限的真相在界面这一侧（工具只等待结算），因此到期收尾、延长后重排都在这里做：
     * 用户点「延长 1 分钟」时，改的是同一份截止时刻，展示与实际等待不会各走一套。
     */
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 每个会话上一次的结算原因；工具侧据此区分超时与用户关闭。 */
    private val settlements = HashMap<String, AskBridge.AskSettlement>()

    /** 通知所需的应用上下文；由聊天页组合在绑定时写入。 */
    @Volatile
    private var appContext: Context? = null

    /**
     * 绑定界面上下文；可重复调用。
     *
     * 只记录应用上下文，端口绑定由调用方另行完成，二者分开以免重复绑定端口时影响未决请求。
     */
    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    /** 当前会话的未决提问；没有时为 null。读取该值即可建立组合订阅。 */
    fun activeFor(sessionId: String): ActiveAsk? = activeState.value[sessionId]

    /** 正在等待作答的会话 id 集合；侧边栏据此给这些会话挂提示点。读取即建立组合订阅。 */
    fun pendingSessions(): Set<String> = activeState.value.keys

    /**
     * 登记提问并挂起等待作答。
     *
     * 请求先以快照形式写入状态，随后在 [CompletableDeferred] 上等待；
     * 提交、撤销、调用方取消或界面销毁均会结束等待，且都能重复进入而不产生额外副作用。
     */
    override suspend fun ask(request: AskRequest): AskResponse? {
        val questions = request.questions
        if (questions.isEmpty()) return AskResponse()
        val deferred = CompletableDeferred<AskResponse?>()
        // null = 不限时：默认一直等到用户作答，卡片也不显示倒计时
        val deadline = request.timeoutMs?.let { SystemClock.elapsedRealtime() + it }
        val pending = Pending(request = request, deferred = deferred, deadline = deadline)
        val first = ActiveAsk(
            token = request.token,
            sessionId = request.sessionId,
            question = questions.first(),
            index = 0,
            total = questions.size,
            deadline = deadline
        )
        withContext(Dispatchers.Main.immediate) {
            synchronized(lock) {
                // 端口侧兜底：同一会话若仍留有未决项，先以「无作答」结算，避免两张卡片并存
                sessionTokens.remove(request.sessionId)?.let { stale ->
                    val old = pendings.remove(stale)
                    activeState.value = activeState.value - request.sessionId
                    old?.deferred?.complete(null)
                }
                pendings[request.token] = pending
                sessionTokens[request.sessionId] = request.token
                settlements.remove(request.sessionId)
                activeState.value = activeState.value + (request.sessionId to first)
            }
            armTimeout(pending)
        }
        FileLog.log(
            "ask",
            "界面收到提问：会话=" + request.sessionId + "，题数=" + questions.size +
                "，时限=" + (request.timeoutMs?.let { it.toString() + "ms" } ?: "不限")
        )
        // 应用不在前台时立即提醒；回到前台后由 onForeground 取消该通知
        if (!AppForeground.isForeground) notifyQuestion(request)
        return try {
            deferred.await()
        } finally {
            withdraw(request.token, request.sessionId)
        }
    }

    /**
     * 提交指定令牌的作答。
     *
     * 令牌不存在或已结算时无副作用；重复提交不会再次完成挂起。
     */
    override fun submit(token: String, answers: List<AskAnswer>) {
        val target = synchronized(lock) {
            val settled = pendings.remove(token)
            if (settled != null) {
                sessionTokens.remove(settled.request.sessionId)
                activeState.value = activeState.value - settled.request.sessionId
            }
            settled
        }
        if (target == null) return
        target.deferred.complete(AskResponse(answers))
        cancelNotification()
        FileLog.log("ask", "已提交作答：会话=" + target.request.sessionId + "，作答 " + answers.size + " 题")
    }

    /**
     * 撤销指定会话的未决提问。
     *
     * 超时与用户停止生成都走这里：一律收起卡片并从界面状态中移除，
     * **不保留**任何「已超时 / 重新回答」的残留入口——用户要重新作答只能由助手重新提问。
     */
    override fun cancel(sessionId: String, reason: String) {
        settle(sessionId, AskBridge.AskSettlement.CLOSED, reason)
    }

    /** 结算一次未决提问：收起卡片、结束等待，并记下原因供工具侧区分。 */
    private fun settle(sessionId: String, settlement: AskBridge.AskSettlement, reason: String) {
        // 先取消通知：即使端口侧已无记录（例如超时后调用方才发起撤销），提醒也必须同步撤掉
        cancelNotification()
        var settled: Pending? = null
        synchronized(lock) {
            val token = sessionTokens[sessionId] ?: return
            val pending = pendings.remove(token) ?: return
            pending.timeoutJob?.cancel()
            sessionTokens.remove(sessionId)
            settlements[sessionId] = settlement
            activeState.value = activeState.value - sessionId
            settled = pending
        }
        settled?.deferred?.complete(null)
        FileLog.log("ask", "撤销提问：会话=" + sessionId + "，原因=" + reason)
    }

    /** 按 [Pending.deadline] 排一次到期结算；截止时刻为空（不限时）时不排。 */
    private fun armTimeout(pending: Pending) {
        pending.timeoutJob?.cancel()
        val deadline = pending.deadline ?: return
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L) return
        pending.timeoutJob = timerScope.launch {
            delay(remaining)
            synchronized(lock) {
                if (pendings[pending.request.token] !== pending) return@launch
            }
            settle(pending.request.sessionId, AskBridge.AskSettlement.TIMEOUT, "用户未在限时内回答")
        }
    }

    /**
     * 「延长 1 分钟」：把截止时刻推后并重排定时器。
     *
     * 改的是同一份截止时刻，界面上的倒计时读的也是它，因此展示与实际等待同步。
     */
    override fun extend(sessionId: String, extraMs: Long): Boolean {
        var updated: Pending? = null
        var next: ActiveAsk? = null
        synchronized(lock) {
            val token = sessionTokens[sessionId] ?: return false
            val pending = pendings[token] ?: return false
            val deadline = pending.deadline ?: return false
            pending.deadline = deadline + extraMs
            updated = pending
            val active = activeState.value[sessionId]
            if (active != null) {
                next = active.copy(deadline = pending.deadline)
                activeState.value = activeState.value + (sessionId to next!!)
            }
        }
        updated?.let { armTimeout(it) }
        FileLog.log("ask", "延长提问时限：会话=" + sessionId + "，追加 " + extraMs + " 毫秒")
        return true
    }

    /** 上一次结算的原因；工具侧在收到「无作答」时用它区分超时与用户关闭。 */
    override fun settlementOf(sessionId: String): AskBridge.AskSettlement? = synchronized(lock) {
        settlements[sessionId]
    }

    /**
     * 用户为当前题作答。
     *
     * 尚有余题时只推进到下一题；最后一题作答后调用 [submit] 完成整次请求。
     */
    fun answer(sessionId: String, selected: List<String>, custom: String?) {
        val active = activeState.value[sessionId] ?: return
        val text = custom?.trim().orEmpty()
        val answer = AskAnswer(
            id = active.question.id,
            selected = selected,
            custom = text.takeIf { it.isNotEmpty() }
        )
        // 尚有余题时推进到下一题并返回 null；最后一题则返回待提交的完整作答
        val completion: Pair<String, List<AskAnswer>>? = synchronized(lock) {
            val pending = pendings[active.token] ?: return
            pending.answers += answer
            val nextIndex = pending.index + 1
            if (nextIndex < pending.request.questions.size) {
                pending.index = nextIndex
                activeState.value = activeState.value + (
                    sessionId to active.copy(
                        question = pending.request.questions[nextIndex],
                        index = nextIndex
                    )
                )
                null
            } else {
                active.token to pending.answers.toList()
            }
        }
        val finished = completion ?: return
        submit(finished.first, finished.second)
    }

    /** 应用回到前台：待作答通知已无意义，立即取消。 */
    fun onForeground() {
        cancelNotification()
    }

    /**
     * 切到同一请求里的另一题（底部翻页器的左右箭头）。
     *
     * 只改展示位置，**不产生作答**：题目之间的勾选与补充文本由界面按题保存，
     * 因此来回切不会丢草稿。索引越界、或该会话已无未决提问时无副作用。
     */
    fun goto(sessionId: String, index: Int) {
        synchronized(lock) {
            val active = activeState.value[sessionId] ?: return
            val pending = pendings[active.token] ?: return
            if (index !in pending.request.questions.indices || index == pending.index) return
            pending.index = index
            activeState.value = activeState.value + (
                sessionId to active.copy(question = pending.request.questions[index], index = index)
                )
        }
    }

    /**
     * 应用退到后台：若该会话仍有未超时的提问，发出待作答通知。
     *
     * 与 [ask] 登记时的提醒互补：提问发生在前台、随后用户切走的场景由本方法覆盖。
     * 此处不查 [AppForeground]：生命周期回调先于前台计数递减派发，查询结果必为前台。
     */
    fun onBackground(sessionId: String) {
        val active = activeState.value[sessionId] ?: return
        val pending = synchronized(lock) { pendings[active.token] } ?: return
        notifyQuestion(pending.request)
    }

    /**
     * 结算后的收尾：调用方取消且未经过提交或撤销时，仍须清掉端口侧记录与界面快照。
     *
     * 不保留任何「已超时」记录：卡片一旦结算就从消息列消失。
     */
    private fun withdraw(token: String, sessionId: String) {
        synchronized(lock) {
            if (sessionTokens[sessionId] != token) return
            pendings.remove(token)
            sessionTokens.remove(sessionId)
            activeState.value = activeState.value - sessionId
        }
    }

    /** 发出待作答通知：正文列出首题的选项，超出上限时改为一句说明。 */
    private fun notifyQuestion(request: AskRequest) {
        val context = appContext ?: return
        val question = request.questions.firstOrNull() ?: return
        val labels = question.options.map { it.label }
        val body = buildString {
            append(question.question)
            if (labels.isNotEmpty()) {
                append('\n')
                append(labels.take(NOTIFY_OPTION_LIMIT).joinToString("\n") { "· " + it })
            }
            if (labels.size > NOTIFY_OPTION_LIMIT) {
                append("\n共 ").append(labels.size).append(" 项，点开作答")
            }
        }
        Notifier.notifyQuestion(context, QUESTION_DEFAULT_TITLE, body, request.sessionId)
    }

    private fun cancelNotification() {
        val context = appContext ?: return
        Notifier.cancel(context, Notifier.questionId())
    }
}
