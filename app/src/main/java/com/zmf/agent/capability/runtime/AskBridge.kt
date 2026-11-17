package com.zmf.agent.capability.runtime

/** 一个可选项：标签用于展示与回传，说明用于补充该选项的后果或适用范围。 */
data class AskOption(
    val label: String,
    val description: String = ""
)

/**
 * 一道提问。
 *
 * [id] 由调用方生成并在一次请求内保持唯一，界面按它回传作答；
 * [header] 为界面上的短标题，[options] 至少两项；[multiSelect] 与 [allowCustom] 决定作答形式。
 */
data class AskQuestion(
    val id: String,
    val question: String,
    val header: String,
    val options: List<AskOption>,
    val multiSelect: Boolean = false,
    val allowCustom: Boolean = true
)

/**
 * 一道提问的作答。
 *
 * [selected] 存放被选中的选项标签；[custom] 为用户补充的自由文本，未填写时为 null；
 * [skipped] 表示用户明确跳过了该题，其含义不等同于「没有作答」。
 */
data class AskAnswer(
    val id: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
    val skipped: Boolean = false
)

/** 界面返回的一组作答；题目与作答通过 [AskAnswer.id] 对应。 */
data class AskResponse(val answers: List<AskAnswer> = emptyList())

/**
 * 一次提问请求：令牌用于提交与撤销。
 *
 * [timeoutMs] 为等待上限；**null 表示不限制**（一直等到用户作答），
 * 界面据此不显示倒计时，也不做本地超时收尾。
 */
data class AskRequest(
    val token: String,
    val sessionId: String,
    val questions: List<AskQuestion>,
    val timeoutMs: Long?
)

/**
 * 提问通道：工具侧发起请求，界面侧展示并回传作答。
 *
 * 本对象只负责绑定与未决状态管理，不依赖聊天界面实现，避免 feature 之间互相引用。
 * 未绑定端口时 [ask] 返回 null，调用方据此回报「无界面」。
 */
object AskBridge {

    /** 界面侧实现的窄接口；[submit] 与 [cancel] 由界面或桥的持有者调用。 */
    interface Port {
        /** 展示提问并等待作答；界面无法展示时返回 null。 */
        suspend fun ask(request: AskRequest): AskResponse?

        /** 提交指定令牌的作答；令牌不存在或已结算时无副作用。 */
        fun submit(token: String, answers: List<AskAnswer>)

        /** 撤销指定会话的未决提问；[reason] 仅用于说明撤销原因。 */
        fun cancel(sessionId: String, reason: String)

        /**
         * 把指定会话的等待时限推后 [extraMs]（「延长 1 分钟」）。
         *
         * 时限由界面侧执行：界面持有时限的真相，工具只等待结算。返回 false 表示没有可延长的未决提问。
         */
        fun extend(sessionId: String, extraMs: Long): Boolean = false

        /** 上一次结算的原因；工具据此区分「超时」与「用户关闭」。 */
        fun settlementOf(sessionId: String): AskSettlement? = null
    }

    /** 未决提问的结算原因。 */
    enum class AskSettlement { TIMEOUT, CLOSED, STOPPED }

    /** 当前绑定的界面实现；未绑定时为 null。 */
    @Volatile
    var port: Port? = null

    private val lock = Any()

    /** 已占用的会话：同一会话同时只允许存在一个未决提问。 */
    private val pendingSessions = HashSet<String>()

    /** 已占用的令牌：用于识别重复请求并配合会话集合完成清理。 */
    private val pendingTokens = HashSet<String>()

    /**
     * 发起一次提问并等待界面作答。
     *
     * 未绑定端口时返回 null；同一会话已有未决提问时抛出 [IllegalStateException]，
     * 由调用方折算为明确的业务错误。无论正常返回、超时还是取消，占用状态都会在 finally 中释放。
     */
    suspend fun ask(request: AskRequest): AskResponse? {
        val target = port ?: return null
        synchronized(lock) {
            if (request.sessionId in pendingSessions || request.token in pendingTokens) {
                throw IllegalStateException("当前会话已有未决提问")
            }
            pendingSessions.add(request.sessionId)
            pendingTokens.add(request.token)
        }
        try {
            return target.ask(request)
        } finally {
            synchronized(lock) {
                pendingSessions.remove(request.sessionId)
                pendingTokens.remove(request.token)
            }
        }
    }

    /** 提交作答；未绑定端口时无副作用。 */
    fun submit(token: String, answers: List<AskAnswer>) {
        port?.submit(token, answers)
    }

    /** 撤销提问；未绑定端口时无副作用。 */
    fun cancel(sessionId: String, reason: String) {
        port?.cancel(sessionId, reason)
    }
}
