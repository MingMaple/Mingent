package com.zmf.agent.agent.subagent

import android.content.Context
import com.zmf.agent.agent.ApiConfig
import com.zmf.agent.agent.loop.AgentLoop
import com.zmf.agent.agent.loop.AgentUi
import com.zmf.agent.agent.prompt.PromptInput
import com.zmf.agent.agent.provider.LlmProvider
import com.zmf.agent.agent.provider.UsageBuckets
import com.zmf.agent.core.AppForeground
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.core.notify.Notifier
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolRegistry
import com.zmf.agent.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * 子智能体需要的两个外部依赖：provider 与 registry。
 *
 * 工具接口（invoke(args, ctx)）里拿不到这两个东西，所以采用「注册期注入」：
 * 父会话在启动时把 lambda 填进来，TaskTool 调用前检查空不空。
 */
object SubAgentDeps {
    @Volatile
    var provider: (() -> LlmProvider)? = null

    @Volatile
    var registry: (() -> ToolRegistry)? = null
}

/**
 * 子智能体一次执行（或续跑）的结果。
 *
 * [id] 是面板里的记录 id，父会话拿它续聊；并发闸拒绝时还没有记录，[id] 为空串。
 */
data class SubAgentOutcome(val id: String, val answer: String, val ok: Boolean)

/**
 * 子智能体：用一份全新的 messages 跑一个独立的小循环，只把最终结论交回父会话。
 *
 * 和父会话完全隔离：不上界面、不共享历史、不弹权限确认；工具仍按父会话的权限档过滤。
 * 并发闸是 [MAX_ACTIVE] 个：拿不到就立刻失败，让模型等已有子任务结束再来。
 * 运行进度与对话记录同时写进 [SubAgentRegistry]（面板读它，[resume] 拿它续聊）。
 */
object SubAgentRunner {

    /** 子智能体最多跑多少轮工具调用。 */
    const val MAX_ROUNDS = 24

    /** 单个子智能体的总超时（10 分钟）。 */
    const val TIMEOUT_MS = 10 * 60 * 1000

    /** 同时最多几个子智能体在跑。 */
    const val MAX_ACTIVE = 2

    /** 交回父会话的结论最长多少字符，超出截断。 */
    const val MAX_RESULT_CHARS = 8000

    /** 失败结论的统一前缀：TaskTool 也按它把结果折成 Error。 */
    const val FAIL_PREFIX = "子智能体失败："

    private const val TAG = "subagent"

    /** 并发闸拒绝的文案：TaskTool 与 send_message 都按它报「名额满了」。 */
    private val LIMIT_TEXT =
        "subagent limit reached (active child limit: " + MAX_ACTIVE + "); wait for an existing child to finish"

    /** 并发闸：用 tryAcquire，拿不到不排队，直接失败。 */
    private val semaphore = Semaphore(MAX_ACTIVE)

    /** 子智能体的系统提示词：它是独立执行者，不是聊天对象。 */
    private const val CHILD_SYSTEM = "你是子智能体，独立完成父会话指派给你的调研/实现任务。\n" +
        "- 不要开新的子智能体，也不要反过来让父会话替你决定，自己把任务做完。\n" +
        "- 需要查证、看文件、改文件就自己用工具做，不要凭空猜。\n" +
        "- 最终回复就是交给父会话的结论：直接给结果（做了什么、结论、关键文件或数据），不要客套，不要复述任务。"

    /**
     * 跑一个子智能体，返回交给父会话的文本。
     *
     * [thinking] / [thinkingEffort] / [model] 传 null 表示继承父会话当前设置（现读，不缓存）；
     * 传了就覆盖这一次子任务，不落盘、不影响父会话与后续子任务。
     *
     * 失败一律折成「子智能体失败：<原因>」并带 ok=false，不让异常穿透到工具层；
     * 只有协程取消（用户点停止）原样抛，否则停止会失效。
     */
    suspend fun run(
        context: Context,
        provider: LlmProvider,
        tools: ToolRegistry,
        permission: PermissionLevel,
        workspaceRoot: File,
        sessionId: String,
        description: String,
        prompt: String,
        thinking: Boolean? = null,
        thinkingEffort: String? = null,
        model: String? = null,
        multiTurn: Boolean = false
    ): SubAgentOutcome {
        if (!semaphore.tryAcquire()) {
            FileLog.log(TAG, "并发闸已满（上限 " + MAX_ACTIVE + "），拒绝：「" + description.take(60) + "」")
            return SubAgentOutcome("", LIMIT_TEXT, false)
        }
        // 拿到名额才登记：被闸拒掉的那次不占面板版面
        val id = SubAgentRegistry.begin(description, multiTurn)
        try {
            FileLog.log(TAG, "启动子智能体「" + description.take(80) + "」，会话 " + sessionId.ifBlank { "default" })
            val answer = withTimeout(TIMEOUT_MS.toLong()) {
                execute(
                    context = context,
                    provider = provider,
                    tools = tools,
                    permission = permission,
                    workspaceRoot = workspaceRoot,
                    sessionId = sessionId,
                    description = description,
                    prompt = prompt,
                    thinking = thinking,
                    thinkingEffort = thinkingEffort,
                    model = model,
                    id = id
                )
            }
            // execute 用统一前缀表达失败（超时/异常走下面的 catch，不会到这里）
            if (answer.startsWith(FAIL_PREFIX)) {
                val reason = answer.removePrefix(FAIL_PREFIX)
                SubAgentRegistry.fail(id, reason)
                notifyResult(context, false, reason, sessionId)
                return SubAgentOutcome(id, answer, false)
            }
            SubAgentRegistry.finish(id, answer)
            notifyResult(context, true, answer, sessionId)
            return SubAgentOutcome(id, answer, true)
        } catch (e: TimeoutCancellationException) {
            val reason = "超时（超过 " + (TIMEOUT_MS / 60000) + " 分钟仍未完成）"
            FileLog.log(TAG, "子智能体超时（" + TIMEOUT_MS / 60000 + " 分钟）：「" + description.take(60) + "」")
            SubAgentRegistry.fail(id, reason)
            notifyResult(context, false, reason, sessionId)
            return SubAgentOutcome(id, FAIL_PREFIX + reason, false)
        } catch (e: CancellationException) {
            // 用户点了停止：取消必须透传，不能当成「子智能体失败」吞掉
            SubAgentRegistry.fail(id, "已取消")
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            FileLog.log(TAG, "子智能体异常：" + reason)
            SubAgentRegistry.fail(id, reason)
            notifyResult(context, false, reason, sessionId)
            return SubAgentOutcome(id, FAIL_PREFIX + reason, false)
        } finally {
            semaphore.release()
        }
    }

    /** 真正跑循环：新 messages、空界面、system 固定成 CHILD_SYSTEM（用 PromptInput 的整份覆盖实现）。 */
    private suspend fun execute(
        context: Context,
        provider: LlmProvider,
        tools: ToolRegistry,
        permission: PermissionLevel,
        workspaceRoot: File,
        sessionId: String,
        description: String,
        prompt: String,
        thinking: Boolean?,
        thinkingEffort: String?,
        model: String?,
        id: String
    ): String {
        // 三个覆盖项现读：父会话里刚切过的开关也要在这条子任务上生效
        val useModel = model?.takeIf { it.isNotBlank() } ?: ApiConfig.loadModel(context)
        val useThinking = thinking ?: ApiConfig.loadThinkingEnabled(context)
        val useEffort = thinkingEffort ?: ApiConfig.loadThinkingEffort(context)
        val messages = ArrayList<ChatMessage>()
        val loop = AgentLoop(
            provider = provider,
            registry = tools,
            model = useModel,
            maxRounds = MAX_ROUNDS,
            // systemOverride 让 PromptBuilder 只出这一份中文提示词，不再拼父会话的身份/环境段
            promptProvider = { PromptInput(model = useModel, systemOverride = CHILD_SYSTEM) }
        )
        loop.run(
            messages = messages,
            userText = prompt,
            thinking = useThinking,
            // 思考关着时 AgentLoop 本来就不会发这个字段，这里照传即可
            thinkingEffort = useEffort,
            toolContext = ToolContext(
                context = context,
                permission = permission,
                workspaceRoot = workspaceRoot,
                sessionId = sessionId
            ),
            ui = RegistryUi(id)
        )
        // 收尾前存一份对话记录：resume 拿它重建上下文，面板也读这份
        SubAgentRegistry.attach(id, prompt, useModel, transcriptOf(messages))
        // 最后一轮可能只发起了工具调用、没有正文；取最后一条有内容的 assistant 消息
        val answer = messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
            ?.content
            .orEmpty()
            .trim()
        if (answer.isEmpty()) {
            FileLog.log(TAG, "子智能体没有产出结论：「" + description.take(60) + "」")
            return FAIL_PREFIX + "没有产出任何结论"
        }
        return truncate(answer)
    }

    /**
     * 给已存在的子智能体追加一条消息，接着它原来的上下文再跑一次。
     *
     * 依赖（provider / tools / 权限 / 工作区）与 [run] 一样由调用方传：工具层从 [SubAgentDeps] 取。
     * 记录不存在（App 重启后登记表是空的）或它还在跑，都直接返回 ok=false，不排队。
     */
    suspend fun resume(
        context: Context,
        id: String,
        text: String,
        provider: LlmProvider,
        tools: ToolRegistry,
        permission: PermissionLevel,
        workspaceRoot: File,
        sessionId: String
    ): SubAgentOutcome {
        val entry = SubAgentRegistry.find(id)
            ?: return SubAgentOutcome(id, "子智能体不存在（App 重启后子智能体不保留）", false)
        if (!entry.multiTurn) {
            return SubAgentOutcome(id, "该子智能体是单轮的，执行完不再接受消息", false)
        }
        if (entry.state == SubAgentRegistry.RUNNING) {
            return SubAgentOutcome(id, "该子智能体正在运行，等它结束再发", false)
        }
        if (!semaphore.tryAcquire()) {
            FileLog.log(TAG, "并发闸已满（上限 " + MAX_ACTIVE + "），拒绝续聊：" + id)
            return SubAgentOutcome(id, LIMIT_TEXT, false)
        }
        try {
            SubAgentRegistry.markRunning(id)
            FileLog.log(TAG, "续聊子智能体 " + id + "，会话 " + sessionId.ifBlank { "default" })
            // 沿用登记时的模型：中途换模型会让它前后判若两人
            val useModel = entry.model.takeIf { it.isNotBlank() } ?: ApiConfig.loadModel(context)
            val useThinking = ApiConfig.loadThinkingEnabled(context)
            val useEffort = ApiConfig.loadThinkingEffort(context)
            val messages = ArrayList<ChatMessage>()
            // 只回放正文：带 tool_calls 回来会伪造出对不上的 tool_call id
            for (turn in entry.transcript) {
                messages.add(ChatMessage(role = turn.role, content = turn.text))
            }
            val loop = AgentLoop(
                provider = provider,
                registry = tools,
                model = useModel,
                maxRounds = MAX_ROUNDS,
                promptProvider = { PromptInput(model = useModel, systemOverride = CHILD_SYSTEM) }
            )
            val answer = withTimeout(TIMEOUT_MS.toLong()) {
                loop.run(
                    messages = messages,
                    userText = text,
                    thinking = useThinking,
                    thinkingEffort = useEffort,
                    toolContext = ToolContext(
                        context = context,
                        permission = permission,
                        workspaceRoot = workspaceRoot,
                        sessionId = sessionId
                    ),
                    ui = RegistryUi(id)
                )
                messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
                    ?.content
                    .orEmpty()
                    .trim()
            }
            if (answer.isEmpty()) {
                val reason = "没有产出任何结论"
                FileLog.log(TAG, "续聊没有产出结论：" + id)
                SubAgentRegistry.fail(id, reason)
                notifyResult(context, false, reason, sessionId)
                return SubAgentOutcome(id, FAIL_PREFIX + reason, false)
            }
            val result = truncate(answer)
            SubAgentRegistry.appendTurns(
                id,
                listOf(SubAgentTurn(role = "user", text = text), SubAgentTurn(role = "assistant", text = result))
            )
            SubAgentRegistry.finish(id, result)
            notifyResult(context, true, result, sessionId)
            return SubAgentOutcome(id, result, true)
        } catch (e: TimeoutCancellationException) {
            val reason = "超时（超过 " + (TIMEOUT_MS / 60000) + " 分钟仍未完成）"
            FileLog.log(TAG, "续聊超时：" + id)
            SubAgentRegistry.fail(id, reason)
            notifyResult(context, false, reason, sessionId)
            return SubAgentOutcome(id, FAIL_PREFIX + reason, false)
        } catch (e: CancellationException) {
            SubAgentRegistry.fail(id, "已取消")
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            FileLog.log(TAG, "续聊异常：" + reason)
            SubAgentRegistry.fail(id, reason)
            notifyResult(context, false, reason, sessionId)
            return SubAgentOutcome(id, FAIL_PREFIX + reason, false)
        } finally {
            semaphore.release()
        }
    }

    /** messages 折成对话记录：工具结果消息不单独记，assistant 带上本轮调用过的工具名。 */
    private fun transcriptOf(messages: List<ChatMessage>): List<SubAgentTurn> {
        val turns = ArrayList<SubAgentTurn>()
        for (message in messages) {
            when (message.role) {
                "user" -> turns.add(SubAgentTurn(role = "user", text = message.content))
                "assistant" -> if (message.content.isNotBlank()) {
                    turns.add(
                        SubAgentTurn(
                            role = "assistant",
                            text = message.content,
                            tools = message.toolCalls.map { it.name }
                        )
                    )
                }
            }
        }
        return turns
    }

    /** 用户不在前台才发通知：正看着屏幕的人不需要系统再提醒一次。 */
    private fun notifyResult(context: Context, ok: Boolean, text: String, sessionId: String) {
        if (AppForeground.isForeground) return
        Notifier.notifySubagent(
            context,
            if (ok) "子智能体完成" else "子智能体失败",
            text.trim().take(200),
            sessionId
        )
    }

    /** 超长结论截断，明确写出「已截断」，父会话不会误以为拿到全文。 */
    private fun truncate(text: String): String {
        if (text.length <= MAX_RESULT_CHARS) return text
        return text.take(MAX_RESULT_CHARS) +
            "\n\n……（子智能体结果已截断：原文共 " + text.length + " 字符，只保留前 " + MAX_RESULT_CHARS + " 字符）"
    }

    /**
     * 空界面实现：子智能体不上屏，只把轮次写进面板记录，内部提示进日志。
     *
     * 每跑一轮更新一次 [SubAgentRegistry]，面板才能看到「running · 第 N 轮」实时变化。
     */
    private class RegistryUi(private val id: String) : AgentUi {
        override fun onRound(round: Int) {
            SubAgentRegistry.progress(id, round)
        }

        override fun onToolStarted(call: ToolCall) {}

        override fun onToolFinished(call: ToolCall, result: ToolResult) {}

        override fun onUsage(usage: UsageBuckets) {}

        override fun onError(message: String) {
            FileLog.log(TAG, "子智能体内部提示：" + message)
        }
    }
}
