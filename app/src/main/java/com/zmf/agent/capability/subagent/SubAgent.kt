package com.zmf.agent.capability.subagent

import android.content.Context
import com.zmf.agent.capability.config.ApiConfig
import com.zmf.agent.capability.loop.AgentLoop
import com.zmf.agent.capability.loop.AgentUi
import com.zmf.agent.capability.prompt.PromptInput
import com.zmf.agent.infra.llm.LlmProvider
import com.zmf.agent.infra.llm.UsageBuckets
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolRegistry
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.shell.terminal.TerminalTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 子智能体需要的两个外部依赖：provider 与 registry。
 *
 * 工具接口（invoke(args, ctx)）内无法获取这两个依赖，因此采用「注册期注入」：
 * 父会话在启动时填入 lambda，TaskTool 在调用前检查是否为空。
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
 * [id] 是面板中的记录 id，父会话据此续聊；并发限制拒绝时尚未创建记录，[id] 为空串。
 */
data class SubAgentOutcome(val id: String, val answer: String, val ok: Boolean)

/**
 * 子智能体：使用一份全新的 messages 运行独立的小循环，仅将最终结论返回父会话。
 *
 * 与父会话完全隔离：不渲染界面、不共享历史、不弹出权限确认；工具仍按父会话的权限档过滤。
 * 并发限制为**每会话 [MAX_PER_SESSION] 个**（D4）：满额立即失败不排队，其他会话的配额互不影响。
 * 运行进度与对话记录同时写入 [SubAgentRegistry]（面板读取它，[resume] 据此续聊）。
 */
object SubAgentRunner {

    /** 子智能体最多执行的工具调用轮数。 */
    const val MAX_ROUNDS = 24

    /** 单个子智能体的总超时（10 分钟）。 */
    const val TIMEOUT_MS = 10 * 60 * 1000

    /** 每个会话同时运行的子智能体最大数量（D4：对齐 DSH 每 activation 树 8）。 */
    const val MAX_PER_SESSION = 8

    /** 交回父会话的结论最大字符数，超出时截断。 */
    const val MAX_RESULT_CHARS = 8000

    /** 失败结论的统一前缀：TaskTool 也按它把结果折成 Error。 */
    const val FAIL_PREFIX = "子智能体失败："

    private const val TAG = "subagent"

    /** 并发闸拒绝的文案：新建与续聊共用，调用方原样交回模型。 */
    private val LIMIT_TEXT =
        "[E_LIMIT] 本会话同时运行的子智能体已达上限（" + MAX_PER_SESSION + " 个）；等其中某个结束后再试"

    /**
     * 每会话并发计数（D4）：键为父会话 id，值为该会话当前在跑的子智能体数。
     * 满额 tryAcquire 返回 false 直接拒绝，不排队；跨会话互不影响。
     */
    private val active = ConcurrentHashMap<String, AtomicInteger>()

    /** 尝试为会话占一个并发名额；满额返回 false（不排队）。 */
    private fun tryAcquire(sessionId: String): Boolean {
        val counter = active.computeIfAbsent(sessionId.ifBlank { "default" }) { AtomicInteger(0) }
        val now = counter.getAndIncrement()
        if (now >= MAX_PER_SESSION) {
            counter.decrementAndGet()
            FileLog.log(
                TAG,
                "并发闸已满（会话 " + sessionId.ifBlank { "default" } + " 已有 " + MAX_PER_SESSION +
                    " 个在跑），拒绝本次请求"
            )
            return false
        }
        FileLog.log(
            TAG,
            "并发名额获取：会话 " + sessionId.ifBlank { "default" } + "，当前在跑 " + (now + 1) + "/" + MAX_PER_SESSION
        )
        return true
    }

    /** 释放会话的并发名额；计数归零时移除条目，避免 map 无限增长。 */
    private fun release(sessionId: String) {
        val key = sessionId.ifBlank { "default" }
        val counter = active[key] ?: return
        val now = counter.decrementAndGet()
        if (now < 0) counter.set(0) // 防御：出现负数时归零
        if (counter.get() <= 0) active.remove(key, counter)
    }

    /** 会话删除时清掉其并发计数（正在跑的协程不受影响，只重置计数）。 */
    fun forgetSession(sessionId: String) {
        if (active.remove(sessionId.ifBlank { "default" }) != null) {
            FileLog.log(TAG, "会话已删除，清理子智能体并发计数：" + sessionId.ifBlank { "default" })
        }
    }

    /**
     * 运行中的子智能体协程（id → Job）：停止会话时据此连带中止，而不只依赖父回合 Job 的取消传播。
     *
     * 传播依赖子协程在挂起点响应取消；一旦它正卡在不可协作的阻塞调用里（例如等 shell 输出），
     * 取消就只能等到那次调用返回。登记 Job 后，停止路径可以先把面板与实际通道收尾。
     */
    private val runningJobs = ConcurrentHashMap<String, Job>()

    /** 记下这次执行所在的协程；取不到 Job 时不记（不影响执行本身）。 */
    private suspend fun track(id: String) {
        currentCoroutineContext()[Job]?.let { runningJobs[id] = it }
    }

    private fun untrack(id: String) {
        runningJobs.remove(id)
    }

    /**
     * 某会话的回合被用户停止时，连带中止它正在跑的子智能体（对齐 DSH：父 activation 拆除即取消子代理并释放其 shell）。
     *
     * 顺序固定为「标记已停止 → 取消协程 → 释放该子智能体自己的终端通道」：标记保证面板立刻不再转圈，
     * 通道释放会让卡在 shell 读取上的调用当场返回。只处理本会话名下的记录，其它会话不受影响；
     * 已入队的结论属于父会话的正常事实，这里不删（P11）。
     */
    fun cancelSession(context: Context, sessionId: String, reason: String = "已停止"): Int {
        if (sessionId.isBlank()) return 0
        val targets = SubAgentRegistry.snapshot(sessionId).filter { it.state == SubAgentRegistry.RUNNING }
        if (targets.isEmpty()) return 0
        for (entry in targets) {
            SubAgentRegistry.stop(entry.id, reason)
            runningJobs.remove(entry.id)?.cancel()
            TerminalTool.releaseSubAgentChannel(context, sessionId, entry.id)
        }
        FileLog.log(
            TAG,
            "父回合停止，连带中止子智能体 " + targets.size + " 个：" + targets.joinToString(",") { it.id },
            sessionId = sessionId
        )
        return targets.size
    }

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
     * 失败一律转换为「子智能体失败：<原因>」并带 ok=false，避免异常穿透至工具层；
     * 仅协程取消（用户点击停止）原样抛出，否则停止功能将失效。
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
        if (!tryAcquire(sessionId)) {
            FileLog.log(TAG, "拒绝新建：「" + description.take(60) + "」，会话 " + sessionId.ifBlank { "default" })
            return SubAgentOutcome("", LIMIT_TEXT, false)
        }
        // 获取名额后才登记：被并发限制拒绝的请求不占用面板条目
        val id = SubAgentRegistry.begin(description, multiTurn, sessionId)
        track(id)
        try {
            // P31：会话与子智能体归属随行——按 sid / sa 可分别筛出会话与子任务全过程
            FileLog.log(
                TAG,
                "启动子智能体「" + description.take(80) + "」",
                sessionId = sessionId,
                agentId = id
            )
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
                return SubAgentOutcome(id, answer, false)
            }
            SubAgentRegistry.finish(id, answer)
            // D2：成功结论转交父会话（失败不转交，避免父会话收到重复的失败报告）
            SubAgentNotices.enqueue(sessionId, description, answer)
            return SubAgentOutcome(id, answer, true)
        } catch (e: TimeoutCancellationException) {
            val reason = "超时（超过 " + (TIMEOUT_MS / 60000) + " 分钟仍未完成）"
            FileLog.log(
                TAG,
                "子智能体超时（" + TIMEOUT_MS / 60000 + " 分钟）：「" + description.take(60) + "」",
                sessionId = sessionId,
                agentId = id
            )
            SubAgentRegistry.fail(id, reason)
            return SubAgentOutcome(id, FAIL_PREFIX + reason, false)
        } catch (e: CancellationException) {
            // 用户点击停止：取消必须透传，不得作为「子智能体失败」吞没
            SubAgentRegistry.fail(id, "已取消")
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            FileLog.log(
                TAG,
                "子智能体异常：" + reason,
                sessionId = sessionId,
                agentId = id
            )
            SubAgentRegistry.fail(id, reason)
            return SubAgentOutcome(id, FAIL_PREFIX + reason, false)
        } finally {
            // 对齐 DSH「owner 拆除即终止 shell」：子智能体结束后立即释放它自己的终端通道，
            // 不把一条空闲 shell 留给下一条命令（未用过终端的子智能体不会产生日志行）
            untrack(id)
            TerminalTool.releaseSubAgentChannel(context, sessionId, id)
            release(sessionId)
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
            // systemOverride 使 PromptBuilder 仅输出这一份中文提示词，不拼接父会话的身份 / 环境段
            promptProvider = { PromptInput(model = useModel, systemOverride = CHILD_SYSTEM) }
        )
        loop.run(
            messages = messages,
            userText = prompt,
            thinking = useThinking,
            // 思考关闭时 AgentLoop 本就不会发送该字段，此处照常传递即可
            thinkingEffort = useEffort,
            toolContext = ToolContext(
                context = context,
                permission = permission,
                workspaceRoot = workspaceRoot,
                sessionId = sessionId,
                // 子智能体没有交互界面：依赖用户当场作答的工具据此拒绝
                isSubAgent = true,
                // 终端据此为每个子智能体分一条独立执行通道
                agentId = id
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
            FileLog.log(
                TAG,
                "子智能体没有产出结论：「" + description.take(60) + "」",
                sessionId = sessionId,
                agentId = id
            )
            return FAIL_PREFIX + "没有产出任何结论"
        }
        return truncate(answer)
    }

    /**
     * 向已存在的子智能体追加一条消息，沿用其原有上下文再次执行。
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
        // P24 归属校验：子智能体只属于创建它的会话，其他会话（包括 peer 会话）不得续聊。
        // 顺序固定：归属先于配额，避免先占名额后发现归属不对。旧记录无会话标记（空串）时不拦截。
        if (entry.sessionId.isNotEmpty() && entry.sessionId != sessionId) {
            FileLog.log(
                TAG,
                "归属拒绝：子智能体 " + id + " 属于会话 " + entry.sessionId + "，请求来自会话 " + sessionId.ifBlank { "default" }
            )
            return SubAgentOutcome(id, "[E_OWNER] 该子智能体不属于当前会话", false)
        }
        if (!entry.multiTurn) {
            return SubAgentOutcome(id, "该子智能体是单轮的，执行完不再接受消息", false)
        }
        if (entry.state == SubAgentRegistry.RUNNING) {
            return SubAgentOutcome(id, "该子智能体正在运行，等它结束再发", false)
        }
        if (!tryAcquire(sessionId)) {
            FileLog.log(TAG, "拒绝续聊：" + id + "，会话 " + sessionId.ifBlank { "default" })
            return SubAgentOutcome(id, LIMIT_TEXT, false)
        }
        try {
            SubAgentRegistry.markRunning(id)
            track(id)
            FileLog.log(TAG, "续聊子智能体 " + id + "，会话 " + sessionId.ifBlank { "default" })
            // 沿用登记时的模型：中途更换模型会使其前后行为明显不一致
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
                        sessionId = sessionId,
                        isSubAgent = true,
                        agentId = id
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
                return SubAgentOutcome(id, FAIL_PREFIX + reason, false)
            }
            val result = truncate(answer)
            SubAgentRegistry.appendTurns(
                id,
                listOf(SubAgentTurn(role = "user", text = text), SubAgentTurn(role = "assistant", text = result))
            )
            SubAgentRegistry.finish(id, result)
            // D2：续聊结论同样转交父会话（失败不转交）
            SubAgentNotices.enqueue(sessionId, entry.description, result)
            return SubAgentOutcome(id, result, true)
        } catch (e: TimeoutCancellationException) {
            val reason = "超时（超过 " + (TIMEOUT_MS / 60000) + " 分钟仍未完成）"
            FileLog.log(TAG, "续聊超时：会话 " + sessionId.ifBlank { "default" } + " 子智能体 " + id)
            SubAgentRegistry.fail(id, reason)
            return SubAgentOutcome(id, FAIL_PREFIX + reason, false)
        } catch (e: CancellationException) {
            SubAgentRegistry.fail(id, "已取消")
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            FileLog.log(TAG, "续聊异常：会话 " + sessionId.ifBlank { "default" } + " 子智能体 " + id + "：" + reason)
            SubAgentRegistry.fail(id, reason)
            return SubAgentOutcome(id, FAIL_PREFIX + reason, false)
        } finally {
            // 续聊与新建同口径：这次执行结束后释放它自己的终端通道
            untrack(id)
            TerminalTool.releaseSubAgentChannel(context, sessionId, id)
            release(sessionId)
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

    /** 超长结论截断，明确注明「已截断」，避免父会话误认为取得全文。 */
    private fun truncate(text: String): String {
        if (text.length <= MAX_RESULT_CHARS) return text
        return text.take(MAX_RESULT_CHARS) +
            "\n\n……（子智能体结果已截断：原文共 " + text.length + " 字符，只保留前 " + MAX_RESULT_CHARS + " 字符）"
    }

    /**
     * 空界面实现：子智能体不显示在界面，仅将轮次写入面板记录，内部提示写入日志。
     *
     * 每执行一轮即更新 [SubAgentRegistry]，面板才能实时显示「running · 第 N 轮」。
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
