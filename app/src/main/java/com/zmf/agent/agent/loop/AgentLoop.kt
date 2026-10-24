package com.zmf.agent.agent.loop

import com.zmf.agent.agent.context.ContextCompactor
import com.zmf.agent.agent.context.ContextPolicy
import com.zmf.agent.agent.instructions.AgentInstructions
import com.zmf.agent.agent.prompt.PromptBuilder
import com.zmf.agent.agent.prompt.PromptInput
import com.zmf.agent.agent.provider.ChatRequest
import com.zmf.agent.agent.provider.LlmEvent
import com.zmf.agent.agent.provider.LlmProvider
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolRegistry
import com.zmf.agent.tools.ToolResult
import com.zmf.agent.tools.spill.SpillStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 循环过程中的界面回调；AgentLoop 本身不碰 Compose。 */
interface AgentUi {
    fun onRound(round: Int)
    fun onToolStarted(call: ToolCall)
    fun onToolFinished(call: ToolCall, result: ToolResult)
    fun onUsage(promptTokens: Int, completionTokens: Int)
    fun onError(message: String)

    /**
     * 一次回复的时间账（毫秒）：模型流累计 + 工具执行累计，循环结束时回调一次。
     *
     * 给默认空实现：定时任务与子智能体不关心计时，它们不该被迫实现一个空方法。
     */
    fun onTiming(modelMillis: Long, toolMillis: Long) {}
}

/**
 * AgentLoop 组装请求视图时读的摘要状态（内存态，由 ChatViewModel 维护；落盘字段在 ChatSession 里）。
 *
 * [text] 空串 = 没有摘要，请求视图就是 summaryUpTo 之前的全量原文。
 * [fallback] = 上次摘要失败，只发最近 fallbackKeepRounds 轮。
 */
data class ContextSummaryState(
    val text: String = "",
    /** 摘要覆盖到的消息下标，必须是 user 消息边界；越界 / 不是边界时整份摘要都不用（发全量最安全）。 */
    val upTo: Int = 0,
    val fallback: Boolean = false
)

/**
 * Agent 主循环：请求 → 流式输出 → 若有工具调用就执行并回填 → 再请求，直到模型不再要工具。
 *
 * 消息列表直接由调用方持有（Compose 的 SnapshotStateList），循环内部只追加/替换，
 * 这样界面能实时看到「思考 / 正文 / 工具卡」三种状态，历史也不用再同步一份。
 */
class AgentLoop(
    private val provider: LlmProvider,
    private val registry: ToolRegistry,
    private val model: String,
    private val maxRounds: Int = 10,
    /** 上下文压缩策略；每次请求前现取，改设置立刻生效。 */
    private val contextPolicy: () -> ContextPolicy = { ContextPolicy() },
    /** 当前会话的摘要状态；同样每次请求前现取，摘要刚生成也能立刻生效。 */
    private val contextState: () -> ContextSummaryState = { ContextSummaryState() },
    /**
     * 系统提示词输入（环境 / 权限档 / Skill / 附加指令 / 覆盖）。
     *
     * 同样每次请求前现取：换工作区、切权限档、改附加指令都不用重建 AgentLoop。
     * 默认值是一份兜底输入，调用方还没接线时也至少能拿到身份、环境和「工作区档」提示。
     */
    private val promptProvider: () -> PromptInput = { PromptInput() }
) {

    /**
     * 上次注入的工作区指令指纹，只用来在日志里标出「内容已变」，不参与「投不投」的判断。
     *
     * 为什么不按指纹决定投不投：指令只进请求视图、不写 messages，请求视图每轮都重建，
     * 按指纹跳过等于让第 2..N 轮工具调用丢掉工作区约定。
     */
    private var injectedInstructionsHash: String? = null

    /**
     * 上一轮 provider 流花了多少毫秒。
     *
     * 计时必须贴着 stream / collect 的边界量：streamOnce 里还要拼提示词、读工作区指令，
     * 那部分算进「模型耗时」会把磁盘 IO 混进模型的账里。
     */
    private var lastStreamMillis = 0L

    /**
     * 本次尝试失败时给用户看的那一整句（可能带我们加的「请求失败（HTTP 400）：」前缀）。
     *
     * 为什么先存下来、由 run 统一报：流一开始报错时我们还不知道能不能「压缩后重发」，
     * 当场 ui.onError 会让恢复成功后界面上还挂着一条其实已经失败过的红字。
     */
    private var lastFailure = ""

    /**
     * 同上，但只存提供方原文（不带我们的前缀）：判「是不是上下文溢出」要用原话，
     * 前缀里的 HTTP 码和中文说明都不该参与匹配。
     */
    private var lastFailureRaw = ""

    private companion object {
        /**
         * 交计划的工具名。
         *
         * 为什么单独硬编码一个名字：写计划是唯一「产出即收尾」的工具——计划已经交给用户过目了，
         * 再让模型往下问一轮，它只会补几句「以上是计划」，白花一次请求。
         */
        const val PLAN_TOOL = "write_plan"

        /** 轮内切到计划模式时的短提示：只交代「从现在起别动手」，计划模式的完整规则本来就在提示词里。 */
        const val PLAN_MODE_ON_NOTICE =
            "（系统提示）用户刚刚把模式切到「计划模式」：从现在起只做调研（读文件、列目录、抓网页），" +
                "不要写文件、不要执行终端命令、不要产生任何副作用；调研完用 write_plan 提交计划，然后停下。"

        /** 轮内关掉计划模式时的短提示。 */
        const val PLAN_MODE_OFF_NOTICE = "（系统提示）用户刚刚关掉了「计划模式」，可以按正常流程执行任务了。"
    }

    /**
     * 上一次请求用的计划模式；null = 本次回复还没发过请求。
     *
     * 为什么需要它：模式和工具表虽然每次都现取，但模型手里还留着上一轮的印象
     * （它刚看完「现在是普通模式」的提示词），用户中途一开计划，它可能继续写文件。
     * 比出变化就用一条系统提示当场说明，比等下一轮自然生效更符合用户预期。
     */
    private var lastPlanMode: Boolean? = null

    /**
     * 跑一次完整回复。
     *
     * @param userText 这条提问的正文；**null = 不追加新的用户消息**，直接在当前历史之后继续跑。
     *   插话（忙时往历史里塞的那条 user 消息）之后本轮可能已经结束，需要再跑一回合把它接上，
     *   这时不能再追加一条一样的消息，否则模型会看到同一句话被问了两遍。
     */
    suspend fun run(
        messages: MutableList<ChatMessage>,
        userText: String?,
        thinking: Boolean,
        thinkingEffort: String?,
        toolContext: ToolContext,
        ui: AgentUi,
        /** 这条提问附带的图片：工作区相对路径（随会话落盘，模型与界面都能按路径回溯原图）。 */
        images: List<String> = emptyList(),
        /**
         * [images] 对应的 base64 data URL，只对本次请求有效（不落盘）。
         *
         * 由调用方读盘现编：模型接口要 data URL，磁盘上只有文件；把它存进会话会撑爆 sessions.json。
         */
        imageDataUrls: List<String> = emptyList(),
        /**
         * 上下文溢出的恢复入口：返回 true = 已经压过、请求视图需要重建。
         *
         * 默认 null：子智能体与定时任务没有摘要能力，也不该被这条恢复路径改变行为。
         * 只有「压缩功能是开的、调用方真的接了这个回调」时才会重发一次（对齐 DSH 的 maxOverflowRetries = 1）。
         */
        onOverflow: (suspend () -> Boolean)? = null
    ): Int {
        if (userText != null) {
            messages.add(
                ChatMessage(
                    role = "user",
                    content = userText,
                    images = images,
                    imageDataUrls = imageDataUrls
                )
            )
        }
        var round = 0
        var modelMillis = 0L
        var toolMillis = 0L
        // 新一次回复从「没有上一次」开始：第一次请求不加模式切换提示
        lastPlanMode = null
        try {
            while (round < maxRounds) {
                round++
                ui.onRound(round)
                var assistantIndex = messages.size
                messages.add(ChatMessage(role = "assistant", content = ""))
                // 重发前要把模式切换提示「还原」：第一次尝试已经消费过 lastPlanMode，
                // 不还原的话，压缩后真正成功的那次请求就收不到「刚刚切到计划模式」这句
                val planModeBefore = lastPlanMode
                var completed =
                    streamOnce(messages, assistantIndex, thinking, thinkingEffort, toolContext, ui)
                modelMillis += lastStreamMillis

                // 溢出恢复：只给一次机会（对齐 DSH 的 maxOverflowRetries = 1）。
                // 为什么不是「重试到成功」：溢出说明发的东西太多，压一次还不够就该让用户看见，
                // 否则会在同一轮里反复花整次请求的钱。
                if (!completed && canRecoverOverflow(onOverflow)) {
                    FileLog.log(
                        "context",
                        "溢出恢复：压缩后重发第 " + round + " 轮（原文：" + lastFailureRaw.take(200) + "）"
                    )
                    val compacted = onOverflow != null && onOverflow.invoke()
                    if (compacted) {
                        lastPlanMode = planModeBefore
                        // 失败的尝试已经把占位气泡删掉了，这里按当前长度重新占位
                        assistantIndex = messages.size
                        messages.add(ChatMessage(role = "assistant", content = ""))
                        completed =
                            streamOnce(messages, assistantIndex, thinking, thinkingEffort, toolContext, ui)
                        modelMillis += lastStreamMillis
                    }
                }
                if (!completed) {
                    // 恢复不了（没接线 / 没开压缩 / 压不动）或重发仍失败：原样把提供方那句话给用户，
                    // 不包装成「压缩失败」，也不吞掉——用户要看到的就是提供方到底说了什么
                    if (lastFailure.isNotEmpty()) ui.onError(lastFailure)
                    return round
                }
                val calls = messages.getOrNull(assistantIndex)?.toolCalls.orEmpty()
                if (calls.isEmpty()) return round

                // 本轮产出了计划就直接收尾：计划已经落地，不再问下一轮
                var producedPlan = false
                for (call in calls) {
                    ui.onToolStarted(call)
                    if (call.name == PLAN_TOOL) producedPlan = true
                    val toolStartedAt = System.nanoTime()
                    val raw = withContext(Dispatchers.IO) { registry.execute(call, toolContext) }
                    toolMillis += (System.nanoTime() - toolStartedAt) / 1_000_000
                    // 超大输出不整段塞进上下文：落盘后只回「头 + 省略 + 尾 + 文件路径」，
                    // 模型要细看再用 read_file 分页读（对齐 DSH 的 spill 策略）。
                    val result = if (raw.ok) {
                        val spilled = withContext(Dispatchers.IO) {
                            SpillStore.process(toolContext.context, toolContext.sessionId, call.name, raw.text)
                        }
                        if (spilled.spilled) ToolResult.Ok(spilled.text) else raw
                    } else {
                        raw
                    }
                    FileLog.log(
                        "tool",
                        call.name + " " + (if (result.ok) "ok" else "fail") +
                            " args=" + call.arguments.take(300) + " -> " + result.text.take(300)
                    )
                    ui.onToolFinished(call, result)
                    messages.add(
                        ChatMessage(
                            role = "tool",
                            content = result.text,
                            toolCallId = call.id,
                            toolName = call.name
                        )
                    )
                }

                if (producedPlan) {
                    // 写进日志是因为这一轮结束得「早」：没有正文、没有下一轮，排查时要知道是工具触发的
                    FileLog.log("plan", "write_plan 已产出计划，结束本轮")
                    return round
                }
            }
            ui.onError("本轮已连续调用 " + maxRounds + " 次工具，先停下等你确认下一步。")
            return round
        } finally {
            // 放在 finally：被「停止」打断的那一轮也要把已经花掉的时间报上去，
            // 否则计时只在成功的回合里累计，任务一停就整段丢失
            ui.onTiming(modelMillis, toolMillis)
        }
    }

    /**
     * 这次失败值不值得试一次「压缩后重发」。
     *
     * 三个条件缺一不可：调用方给了压缩入口、压缩功能是开的、失败原因确实是上下文超限。
     * 前两条保证「没接线的调用方（子智能体 / 定时任务）与没开压缩的用户」行为完全不变——
     * 没开压缩就没什么可压的，白跑一次摘要只是给用户多等几秒。
     */
    private fun canRecoverOverflow(onOverflow: (suspend () -> Boolean)?): Boolean {
        if (onOverflow == null) return false
        if (!contextPolicy().enabled) return false
        // 提供方原话优先：我们自己加的中文前缀不参与判断
        return isContextOverflow(if (lastFailureRaw.isNotEmpty()) lastFailureRaw else lastFailure)
    }

    /**
     * 提供方说的是不是「上下文太长」。
     *
     * 为什么只认这几组词：各家措辞不同（DeepSeek 常用 "maximum context length" / "too many tokens"），
     * 而误判的代价很实在——白花一次完整请求，还可能把「网络断了」说成「上下文溢出」，把用户引到错的方向。
     * 所以宁可漏判（用户还能像以前那样手点压缩），也不放宽到「只要提到 token 就算」。
     */
    private fun isContextOverflow(message: String): Boolean {
        val text = message.lowercase()
        if (text.isEmpty()) return false
        if (text.contains("context length")) return true
        if (text.contains("context window")) return true
        if (text.contains("maximum context")) return true
        if (text.contains("too many tokens")) return true
        // 「exceeds ... token(s)」「... token limit exceeded」这类组合：两个词都得在，避免单词误伤
        if (text.contains("exceeds") && text.contains("token")) return true
        if (text.contains("too long") && text.contains("token")) return true
        return false
    }

    /**
     * 轮内模式切换提示：和上一次请求的模式比，变了才返回一句短提示（首次 / 没变返回 null）。
     *
     * 顺手把 [lastPlanMode] 推到这里：模式只在「发请求」这一刻才算数，
     * 放在这里比放在 run 的循环里少一处分支。
     */
    private fun planModeSwitchNotice(current: Boolean): String? {
        val previous = lastPlanMode
        lastPlanMode = current
        if (previous == null || previous == current) return null
        FileLog.log("plan", "轮内模式切换 → " + (if (current) "计划" else "普通"))
        return if (current) PLAN_MODE_ON_NOTICE else PLAN_MODE_OFF_NOTICE
    }

    /**
     * 跑一轮流式请求。返回 false 表示这一轮失败，失败原文记在 [lastFailure] / [lastFailureRaw]，
     * 由调用方决定「压缩后重发」还是「直接报给用户」——流里不报，是因为这两种可能还没分出来。
     */
    private suspend fun streamOnce(
        messages: MutableList<ChatMessage>,
        index: Int,
        thinking: Boolean,
        thinkingEffort: String?,
        toolContext: ToolContext,
        ui: AgentUi
    ): Boolean {
        // 先清零：请求拼装阶段就抛异常时，调用方读到的应该是「这一轮没等到模型」，
        // 而不是上一轮留下的旧耗时
        lastStreamMillis = 0L
        // 失败信息也一样：只属于本次尝试。留着上一次的，会把正常请求误判成「又溢出了」
        lastFailure = ""
        lastFailureRaw = ""
        val history = ArrayList<ChatMessage>(index)
        for (i in 0 until index) history.add(messages[i])
        // 只改「发给模型的请求视图」，messages 与界面历史一律不动
        val policy = contextPolicy()
        val state = contextState()

        // ① 摘要覆盖过的部分不再进请求：从 user 边界 summaryUpTo 起（分界永远落在整轮开头）
        var start = 0
        var withSummary = false
        // 这里只认「有没有摘要」：摘要开关管的是「自动触发」，手动压缩出来的摘要任何情况下都要生效
        if (state.text.isNotEmpty()) {
            val upTo = state.upTo
            if (upTo in 0 until history.size && history[upTo].role == "user") {
                start = upTo
                withSummary = true
            } else {
                // 回滚 / 删消息后分界可能失效：宁可多带原文，也不把一轮切成半截
                FileLog.log("context", "摘要分界失效（" + upTo + " / " + history.size + " 条），本次退回全量")
            }
        }

        // ② 摘要失败的兜底：只发最近 fallbackKeepRounds 轮原文（整轮边界，工具消息不会断）
        if (policy.enabled && state.fallback) {
            val fallbackStart = history.size - ContextCompactor.compact(history, policy).messages.size
            if (fallbackStart > start) start = fallbackStart
        }

        val tail = history.subList(start, history.size)
        val sanitized = tail.sanitized()
        val request = ArrayList<ChatMessage>(sanitized.size + 1)
        // 系统提示词统一由 PromptBuilder 出：身份 / 环境 / 权限档 / 摘要 / 计划模式 / 工具总则 /
        // Skill / 附加指令全在一条 system 里（按 PromptOrders 排序，段间空一行）。
        // 仍然最多一条 system，放在请求最前面：prefix 稳定（provider 侧可吃 prompt cache），
        // 也不会像裸 role=user 那样被模型当成用户的新指令。
        // 摘要与 withSummary 同口径：摘要分界失效时整份摘要都不带（和改之前的行为一致）。
        // planMode 不再由调用方传进来：每次请求现取 promptProvider() 里的值，
        // 用户在思考中途打开计划，下一轮请求就能看到（循环不用重建）
        val input = promptProvider().copy(summaryText = if (withSummary) state.text else "")
        val systemText = PromptBuilder.build(input)
        if (systemText != null) request.add(ChatMessage(role = "system", content = systemText))
        // 轮内改模式：紧跟系统提示插一条短提示。只进本次请求视图（不写 messages、不落盘），
        // 所以界面上不会多出一条气泡，下一轮请求视图重建时也不会残留
        planModeSwitchNotice(input.planMode)?.let { notice ->
            request.add(ChatMessage(role = "system", content = notice))
        }

        // 工作区指令文件（AGENTS.md / CLAUDE.md 系列）：每次请求前现读，用户改完文件下一轮就生效。
        // 有就必须每轮都带——指令只进请求视图、不写 messages，请求视图每轮重建，
        // 少带一轮模型就少一轮约定可看。位置固定在 system 之后、历史之前，prefix 也稳定。
        val instructions = withContext(Dispatchers.IO) { AgentInstructions.load(toolContext.workspaceRoot) }
        if (instructions != null) {
            request.add(
                ChatMessage(
                    role = "user",
                    content = "<system-reminder>Instructions from: " + instructions.displayPath +
                        "\n\n" + instructions.text + "</system-reminder>"
                )
            )
            FileLog.log(
                "instructions",
                "injected " + instructions.displayPath + " " + instructions.text.length +
                    " chars hash=" + instructions.hash
            )
            if (instructions.hash != injectedInstructionsHash) {
                FileLog.log(
                    "instructions",
                    "内容已变（" + (injectedInstructionsHash ?: "无") + " → " + instructions.hash + "）"
                )
            }
            injectedInstructionsHash = instructions.hash
        } else {
            // 文件被删也要把指纹清掉：同样的内容再放回来时还能打出一行「内容已变」
            injectedInstructionsHash = null
        }
        request.addAll(sanitized)
        if (start > 0 || withSummary) {
            FileLog.log(
                "context",
                "请求视图：摘要" + (if (withSummary) "已带" else "未带") + "，原文从第 " + start + " 条起，" +
                    history.size + " 条历史 → " + request.size + " 条请求（约 " +
                    ContextCompactor.estimate(request) + " tokens）"
            )
        }
        // 工具表与系统提示词用同一个 planMode 来源，避免「提示词说在计划模式、工具表却给了写工具」
        val specs = registry.specs(toolContext.permission, input.planMode)

        val contentFilter = DsmlFilter("正文")
        val reasoningFilter = DsmlFilter("思考")
        val calls = LinkedHashMap<Int, ToolCall>()
        var content = ""
        var reasoning = ""
        var failed = false

        fun publish() {
            if (index !in messages.indices) return
            messages[index] = ChatMessage(
                role = "assistant",
                content = content,
                reasoningContent = reasoning.ifEmpty { null },
                toolCalls = calls.values.toList()
            )
        }

        // 只量「等模型」这一段：请求拼装、读工作区指令都在这之前，不该算成模型耗时
        val streamStartedAt = System.nanoTime()
        try {
            provider.stream(
                ChatRequest(
                    model = model,
                    messages = request,
                    tools = specs,
                    thinking = thinking,
                    reasoningEffort = if (thinking) thinkingEffort else null
                )
            ).collect { event ->
                when (event) {
                    is LlmEvent.ReasoningDelta -> {
                        val visible = reasoningFilter.feed(event.text)
                        if (visible.isNotEmpty()) {
                            reasoning += visible
                            publish()
                        }
                    }

                    is LlmEvent.ContentDelta -> {
                        val visible = contentFilter.feed(event.text)
                        if (visible.isNotEmpty()) {
                            content += visible
                            publish()
                        }
                    }

                    is LlmEvent.ToolCallDelta -> {
                        val prev = calls[event.index]
                        calls[event.index] = ToolCall(
                            id = event.id ?: prev?.id.orEmpty(),
                            name = event.name ?: prev?.name.orEmpty(),
                            arguments = (prev?.arguments.orEmpty()) + event.argumentsDelta.orEmpty()
                        )
                        publish()
                    }

                    is LlmEvent.Finished -> ui.onUsage(event.promptTokens, event.completionTokens)

                    is LlmEvent.Failed -> {
                        failed = true
                        lastFailureRaw = event.message
                        lastFailure = if (event.code > 0) {
                            "请求失败（HTTP " + event.code + "）：" + event.message
                        } else {
                            "请求失败：" + event.message
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            lastFailureRaw = e.message.orEmpty()
            lastFailure = "请求异常：" + (e.message ?: e.javaClass.simpleName)
        } finally {
            // 取消也要记：用户点停止时这一轮的等待时间是真实花掉的
            lastStreamMillis = (System.nanoTime() - streamStartedAt) / 1_000_000
        }

        content += contentFilter.flush()
        reasoning += reasoningFilter.flush()
        if (contentFilter.tripped || reasoningFilter.tripped) {
            ui.onError("模型输出里出现了非法标记，已截断，不会显示到界面")
        }

        if (failed || (content.isBlank() && calls.isEmpty())) {
            if (index in messages.indices) messages.removeAt(index)
            return false
        }
        publish()
        return true
    }

    /**
     * 发请求前的清洗：丢掉空 assistant 占位、孤儿 tool 消息，以及「有 tool_calls 但结果不齐」的整轮。
     * 停止按钮打断工具执行时，历史里就会留下这种半截轮次，不清洗下一轮必定 400。
     */
    private fun List<ChatMessage>.sanitized(): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(size)
        var i = 0
        while (i < size) {
            val m = this[i]
            when {
                m.role == "tool" -> i++
                m.role == "assistant" && m.content.isBlank() && m.toolCalls.isEmpty() -> i++
                m.role == "assistant" && m.toolCalls.isNotEmpty() -> {
                    var j = i + 1
                    val results = ArrayList<ChatMessage>()
                    while (j < size && this[j].role == "tool") {
                        results.add(this[j])
                        j++
                    }
                    val got = results.mapNotNull { it.toolCallId }.toSet()
                    val complete = m.toolCalls.all { it.id in got }
                    if (complete) {
                        out.add(m)
                        out.addAll(results)
                    }
                    i = j
                }
                else -> {
                    out.add(m)
                    i++
                }
            }
        }
        return out
    }
}

/**
 * 伪工具标记过滤。
 *
 * 标记可能被拆到两个 delta 里（例如「...<|DS」+「ML|>...」），所以尾巴上可能是标记前缀的
 * 部分要先扣住不显示，确认不是标记再放出去；一旦命中就永久截断本轮。
 */
class DsmlFilter(private val label: String) {

    private val pending = StringBuilder()

    var tripped = false
        private set

    fun feed(delta: String): String {
        if (tripped || delta.isEmpty()) return ""
        pending.append(delta)
        val hit = pending.indexOf(MARK)
        if (hit >= 0) {
            tripped = true
            val visible = pending.substring(0, hit)
            pending.setLength(0)
            FileLog.log("dsml", label + " 命中非法标记，已截断（截断前 " + visible.length + " 字符）")
            return visible
        }
        var keep = 0
        val maxKeep = minOf(pending.length, MARK.length - 1)
        for (k in maxKeep downTo 1) {
            if (pending.substring(pending.length - k) == MARK.substring(0, k)) {
                keep = k
                break
            }
        }
        val emitLength = pending.length - keep
        if (emitLength <= 0) return ""
        val visible = pending.substring(0, emitLength)
        pending.delete(0, emitLength)
        return visible
    }

    fun flush(): String {
        if (tripped) {
            pending.setLength(0)
            return ""
        }
        val visible = pending.toString()
        pending.setLength(0)
        return visible
    }

    companion object {
        const val MARK = "<|DSML|"
    }
}
