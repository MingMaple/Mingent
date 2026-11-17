package com.zmf.agent.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zmf.agent.capability.loop.AgentUi
import com.zmf.agent.capability.loop.ModelRetryInfo
import com.zmf.agent.capability.loop.OutputTiming
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.infra.llm.UsageBuckets

/**
 * 一个回合专属的界面回调：把循环的进展记到**它自己那条会话**头上。
 *
 * 用户 2026-11-15 要求「每条会话各跑一个回合」（A 跑任务、切到 B 再让 B 跑），
 * 原先 `ChatViewModel` 自己实现 [AgentUi] 且不带会话 id，并行之后 B 的用量、步数、
 * 工具成败会被记到最近一次收尾的那条会话上。因此每个回合新建一份，绑死 sessionId。
 *
 * [failure] 只属于本回合：收尾发通知时读它，而不是读全局那一句错误——否则 A 失败会把
 * B 的成功回合一并报成「回复失败」。
 */
class TurnAgentUi(
    private val sessionId: String,
    private val context: ContextState,
    private val toolCalls: ToolCallController,
    /** 一次请求成功：顶栏状态点回绿。 */
    private val onRequestOk: () -> Unit,
    /** 一次请求失败：界面提示 + 日志；本回合据此判定要不要发「回复失败」通知。 */
    private val onRequestFailed: (String) -> Unit,
    /**
     * 自动重试全部用尽后的终态失败：写入消息流内的错误节点（落盘可重放）。
     *
     * 与 [onRequestFailed] 分开：那个是输入条上方的临时提示（如非法标记告警），
     * 只有这一条才会变成会话里持久的「本轮运行失败」。
     */
    private val onTurnFailed: (String, String) -> Unit,
    /** 自动重试排期：null = 重试结束（界面收起倒计时节点）。 */
    private val retryNotice: (ModelRetryInfo?) -> Unit
) : AgentUi {

    /** 本回合最后一次失败的说明；成功过就为空。 */
    var failure by mutableStateOf<String?>(null)
        private set

    /** 与 [failure] 配套的稳定失败分类（AUTH / RATE_LIMIT / SERVER ...）；成功过就为空。 */
    var failureCode by mutableStateOf<String?>(null)
        private set

    /**
     * 本回合已派发（收到过 [onToolStarted]）的调用 id。
     *
     * 用户中止回合时据此区分「已派发但没返回」与「尚未派发」：两者都要补合成结果，
     * 前者还提示副作用可能已经发生。
     */
    val startedCalls: MutableSet<String> = mutableSetOf()

    /** 新一轮开始：只记「本次回复」已达到的轮次。 */
    override fun onRound(round: Int) = context.noteRound(sessionId, round)

    /** 工具开始执行：不写输入条活动行（用户 2026-11-16 定），过程组头与工具卡自会表示。 */
    override fun onToolStarted(call: ToolCall) {
        startedCalls.add(call.id)
    }

    /** 工具执行结束：记录这一步的成败供工具卡读取，goal 工具成功时同时刷新目标。 */
    override fun onToolFinished(call: ToolCall, result: ToolResult) =
        toolCalls.onToolFinished(sessionId, call, result)

    /** 收到一次服务端 usage：累加**本会话**本轮与会话用量、写全局总账，并刷新水位与状态行。 */
    override fun onUsage(usage: UsageBuckets) {
        context.recordUsage(sessionId, usage)
        // 收到 usage 说明这一轮请求真的成功了，顶栏状态点可以回绿
        failure = null
        failureCode = null
        onRequestOk()
    }

    /** 请求失败：把错误挂到界面并把顶栏状态点置为失败，直到下一次成功。 */
    override fun onError(message: String) {
        failure = message
        failureCode = null
        onRequestFailed(message)
    }

    /**
     * 终态失败：除通知与状态点之外，交给会话把它写成消息流内的错误节点。
     *
     * 错误节点带稳定分类码，重进会话仍能看到（落盘），与 DSH 的 turn-error 同口径。
     */
    override fun onFailure(code: String, message: String) {
        failure = message
        failureCode = code
        onTurnFailed(code, message)
    }

    /** 自动重试排期：只影响界面上的倒计时节点。 */
    override fun onModelRetry(info: ModelRetryInfo?) = retryNotice(info)

    /** 循环收尾回调一次：只累计本回合的时间账，落会话记录在 commitTiming 里做。 */
    override fun onTiming(modelMillis: Long, toolMillis: Long) =
        context.addTurnTiming(sessionId, modelMillis, toolMillis)

    /** 输出侧账目（首字等待 / 生成时长 / 输出 tokens）：跟 [onTiming] 一起落进会话记录。 */
    override fun onOutputTiming(timing: OutputTiming) =
        context.recordOutputTiming(sessionId, timing)
}
