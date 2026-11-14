package com.zmf.agent.capability.prompt
import com.zmf.agent.core.llm.ApiDefaults

import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.displayName

/**
 * 提示词组装器，分成两层，越稳的越靠前：
 *
 * - **稳定层** [buildStable]：进 system。同一会话内逐字节不变，依次为身份、环境、权限事实、边界、
 *   工具总则、沙箱与删除、沟通、记忆、计划确认、Skill 目录、用户附加指令。它位于请求最前缀，
 *   只有逐字节不变才能命中 provider 的前缀缓存。
 * - **运行时快照** [buildRuntimeSnapshot]：不进 system，拼在**请求最末尾**（当前用户消息之后）。
 *   权限档、工作区路径、计划模式、当前时间、会话摘要这些会变的都在这里，并用一句
 *   「以下为最新状态，覆盖此前所有状态」把它和前面的历史隔开。
 *
 * 为什么这样切：DeepSeek 的缓存按「缓存前缀单元」完整匹配，system 位于最前缀。若把分钟级的
 * 「当前时间」拼在第 2 段，用户稍后再发一句，system 即发生变化，整个请求连同全部历史都不命中缓存。
 * 拆分之后，前缀保持稳定，易变部分仅落在末尾。
 *
 * 两条硬规则不变：
 * - 用户整份覆盖（[PromptInput.systemOverride] 非空）优先，默认提示词不出任何一条，此时也不注入快照；
 *   用户选择整份覆盖，即表示不需要再追加内容；
 * - 段与段之间空一行，空段不出，全部为空返回 null（调用方据此不塞 system 消息）。
 *
 * 总长超过告警线只写一条日志，不做截断：提示词被截断的代价高于长度略增。
 */
object PromptBuilder {

    private const val TAG = "prompt"

    /**
     * 运行时快照的抬头，也是「这份快照是不是新格式」的判据。
     *
     * 快照抬头的格式曾发生变更，旧格式文本仍保留在会话文件中。
     * 拼请求时只识别新抬头，旧文本一律跳过：否则旧会话会把「计划模式：已开启」这类过期状态
     * 重新带回上下文，导致模型将过期状态当作当前状态。
     */
    const val SNAPSHOT_MARKER = "本轮运行状态"

    /**
     * 提示词占窗口的告警比例：0.5%。
     *
     * 为什么采用窗口比例而非固定的 4000：窗口为 1M 时，4000 仅占 0.4‰，附加指令稍长即持续告警，
     * 告警过多将失去提示作用。按窗口比例计算，告警线可随模型容量调整（1M → 5,242）。
     */
    private const val PROMPT_WINDOW_RATIO = 0.005f

    /** 告警线的硬下限：小窗口（8k）下 0.5% 仅 40 tokens，每轮都会触发告警，反而失去提示意义。 */
    private const val MIN_PROMPT_TOKENS = 4000

    /** 提示词总长告警线（粗估 tokens）：窗口的 0.5%，且不低于 [MIN_PROMPT_TOKENS]。 */
    private fun maxPromptTokens(windowTokens: Int = ApiDefaults.CONTEXT_WINDOW): Int {
        val byWindow = (windowTokens.coerceAtLeast(1) * PROMPT_WINDOW_RATIO).toInt()
        return maxOf(MIN_PROMPT_TOKENS, byWindow)
    }

    /**
     * 稳定层：进 system 的那一份。
     *
     * 排序见 [PromptOrders]：越靠前越稳定。这一份必须做到「同一会话内一字不变」，
     * 任何随请求变化的内容都放 [buildRuntimeSnapshot]。
     */
    fun buildStable(input: PromptInput): String? {
        // 用户覆盖优先：整份替换，连身份都不带
        val override = input.systemOverride?.trim()
        if (!override.isNullOrEmpty()) return override

        val sections = ArrayList<PromptSection>(10)
        sections += PromptSection(PromptOrders.IDENTITY, "身份", DefaultPrompts.IDENTITY)
        sections += PromptSection(PromptOrders.ENVIRONMENT, "环境", deviceText(input))
        // 权限事实是**静态**的三档能力描述；「当前是哪一档」不进 system。
        // 理由（实测）：system 位于请求最前缀，改动一行即从该行起全部重新计算，
        // 切档那一轮命中率降至 4.0% / 29.1%，而状态不变时为 96.4%。
        // 因此权限档那一行同样放在运行时快照中，system 仅保留与取值无关的说明。
        sections += PromptSection(PromptOrders.PERMISSION_FACTS, "权限事实", DefaultPrompts.PERMISSION_FACTS)
        sections += PromptSection(PromptOrders.BOUNDARIES, "边界", DefaultPrompts.BOUNDARIES)
        sections += PromptSection(PromptOrders.TOOL_RULES, "工具总则", DefaultPrompts.TOOL_RULES)
        // 沙箱判定提前说明：模型了解当前档位与授权码适用条件后，不会在被拒后反复变换写法试探
        sections += PromptSection(PromptOrders.SANDBOX, "沙箱与删除", DefaultPrompts.SANDBOX_RULES)
        sections += PromptSection(PromptOrders.COMMUNICATION, "沟通", DefaultPrompts.COMMUNICATION)
        sections += PromptSection(PromptOrders.MEMORY, "记忆", DefaultPrompts.MEMORY_RULES)
        // 常驻段：无论是否处于计划模式，「在对话中表示同意」均不等于「授权执行」
        sections += PromptSection(PromptOrders.PLAN_RULES, "计划确认", DefaultPrompts.PLAN_RULES)
         // 计划模式段**不进 system**：请求走 OpenAI 兼容 chat/completions，
         // 未验证「user 之后插入第二条 system」是否被接受，因此改用等价方案：
         // 规则文本由运行时快照承载（仅在开启时出现，关闭时不输出任何内容）。
        skillText(input.skills)?.let {
            sections += PromptSection(PromptOrders.SKILLS, "Skill", it)
        }
        if (input.extraInstructions.isNotBlank()) {
            sections += PromptSection(PromptOrders.USER_EXTRA, "附加指令", input.extraInstructions.trim())
        }

        val text = joinSections(sections, input) ?: return null
        return text
    }

    /**
     * 运行时快照：拼在请求最末尾的那一份（不进 system）。
     *
     * 内容按「变化频率」从低到高排列：权限档 / 工作区 / 计划模式 / 时间 / 摘要。
     * 用户整份覆盖提示词时不注入：覆盖模式不再追加任何内容。
     */
    fun buildRuntimeSnapshot(input: PromptInput, changeLines: List<String> = emptyList()): String? {
        if (!input.systemOverride.isNullOrBlank()) return null
        val lines = ArrayList<String>(10)
         // 状态行的口径：
         // 1) 权限档与计划模式**每轮都给出正向值**，不依赖「某段是否存在」推断：
         //    若仅将状态放入 system，退出计划模式后模型看不到任何「关闭」字样，只能读取自身
         //    上一轮的回答，从而回答「仍在计划模式」。
        // 2) 整个块只**追加**在最末尾，system 因此逐字节不随状态变化（缓存不会整段失效）。
         // 3) 抬头声明「覆盖此前所有轮次」，历史中的旧块因此具有明确的有效期。
        lines += SNAPSHOT_MARKER + "（以此为准；历史消息里更早的状态块只描述当时，已经作废）："
        lines += permissionStateLine(input.permission)
        lines += planStateLine(input.planMode)
        input.workspacePath.takeIf { it.isNotBlank() }?.let {
            lines += "- 工作区绝对路径：" + it
        }
        input.nowText.takeIf { it.isNotBlank() }?.let {
            lines += "- 当前时间：" + it
        }
        input.goalText.takeIf { it.isNotBlank() }?.let {
            lines += "- 当前目标：" + it
        }
        if (changeLines.isNotEmpty()) {
            lines += "- 本轮变化：" + changeLines.joinToString("；")
        }
         // 计划模式的完整规则只在开启时出现：关闭时一个字都不出，前缀不受影响。
        if (input.planMode) {
            lines += ""
            lines += DefaultPrompts.PLAN_MODE_PROMPT
        }
        val text = lines.joinToString("\n")
        logLength(text, input, "运行时快照")
        return text
    }

    /**
     * 压缩检查点的包裹与抬头：检查点以一条合成 user 消息的形式插在历史之前。
     *
     * 位置：紧跟在 system / 工作区指令之后、历史原文之前，也就是「第一个可动消息」的位置。
     * 这样 system + 检查点成为**永久复用的前缀**，只有它后面的原文会随着新消息增长。
     */
    const val CHECKPOINT_OPEN = "<compacted-summary>"
    const val CHECKPOINT_CLOSE = "</compacted-summary>"

    /** 检查点抬头：说明该内容并非用户的新发言，并要求将其视为既定背景。 */
    const val CHECKPOINT_PREAMBLE =
        "以下是本次会话更早对话的压缩检查点（自动生成，不是用户的新发言）：应当将其中内容视为既定背景，" +
            "直接在此基础上继续，不得复述，也不得确认这份检查点。"

    /** 把检查点正文包成一条 user 消息的内容；摘要调用与主请求共用这一份，避免两处漂移。 */
    fun checkpointText(summary: String): String =
        CHECKPOINT_PREAMBLE + "\n\n" + CHECKPOINT_OPEN + "\n" + summary + "\n" + CHECKPOINT_CLOSE

    /**
     * 当前时间的提示词表述：`yyyy-MM-dd HH:mm（周X，GMT+偏移）`。
     *
     * 写清星期与 GMT 偏移：模型容易把纯时间戳按 UTC 解读，或不知道今天星期几；
     * 这两条都是「时间感知」最基础的缺口。每次请求前现取，时间始终是发请求那一刻的。
     *
     * 聊天页与定时任务共用同一份格式：定时任务由闹钟在后台冷启动，若快照缺少这一行，
     * 模型只能从历史里最后一条答复推断「今天是哪一天」，会把上一轮的早报再写一遍。
     */
    fun nowText(): String {
        val fmt = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm（E，'GMT'XXX）",
            java.util.Locale.getDefault()
        )
        fmt.timeZone = java.util.TimeZone.getDefault()
        return fmt.format(java.util.Date())
    }

    /**
     * 快照里的「权限档」那一行。
     *
     * 单独拆出来是给 AgentLoop 做**状态比对**用的：拿上一份快照里有没有这一行，
     * 判断「这一轮状态变了没有」，变了才写一句「本轮变化」。
     */
    fun permissionStateLine(level: PermissionLevel): String =
        "- 权限档：" + level.displayName() + "（三档：只读 / 工作区 / 完全；各档能做什么见 system 的权限事实段）"

    /** 快照里的「计划模式」那一行（同上，供状态比对复用）。 */
    fun planStateLine(planMode: Boolean): String = if (planMode) {
        "- 计划模式：开启（只做调研：读文件、列目录、抓网页；不改文件、不执行命令，调研完用 write_plan 交计划）"
    } else {
        "- 计划模式：关闭（普通模式，可以改文件、可以执行命令）"
    }

    /**
     * 稳定层 + 快照拼成一份完整文本。
     *
     * **只用于估算长度与统计**（洞察页、上下文分段），不要拿它去发请求：
     * 发请求必须分开：system 用 [buildStable]，快照走尾部。
     */
    fun build(input: PromptInput): String? {
        val stable = buildStable(input)
        val snapshot = buildRuntimeSnapshot(input)
        return when {
            stable == null -> snapshot
            snapshot == null -> stable
            else -> stable + "\n\n" + snapshot
        }
    }

    /** 排序、过滤空段、拼接，并在超长时记一条告警。 */
    private fun joinSections(sections: List<PromptSection>, input: PromptInput): String? {
        val ordered = sections.sortedBy { it.order }.filter { it.text.isNotBlank() }
        if (ordered.isEmpty()) return null
        val text = ordered.joinToString("\n\n") { it.text.trim() }
        logLength(text, input, "稳定层", ordered)
        return text
    }

    /** 超长只记日志不截断：记录各段长度，便于快速定位超长来源。 */
    private fun logLength(
        text: String,
        input: PromptInput,
        label: String,
        sections: List<PromptSection> = emptyList()
    ) {
        val tokens = estimateTokens(text)
        val limit = maxPromptTokens()
        if (tokens <= limit) return
        val detail = if (sections.isEmpty()) {
            ""
        } else {
            "，各段：" + sections.joinToString(" / ") { it.name + "≈" + estimateTokens(it.text) }
        }
        FileLog.log(
            TAG,
            label + "偏长：约 " + tokens + " tokens（告警线 " + limit + " = 窗口 " +
                ApiDefaults.CONTEXT_WINDOW + " 的 0.5%，不低于 " + MIN_PROMPT_TOKENS + "），" +
                "模型=" + input.model + detail
        )
    }

    /**
     * 粗估 token：汉字（含全角标点）按 1 字 1 token，其余字符按 4 字符约 1 token、向上取整。
     *
     * 估算口径与 ContextCompactor 一致，仅用于判断提示词是否过长，不参与计费。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var wide = 0
        var narrow = 0
        for (ch in text) {
            val code = ch.code
            if (code in 0x2E80..0x9FFF || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF) {
                wide++
            } else {
                narrow++
            }
        }
        return wide + (narrow + 3) / 4
    }

    /**
     * 设备段（稳定）：只讲「这是什么设备」，不讲路径与时间：那两个每分钟/每次换工作区就变。
     *
     * 模型最常见的误解是「这不是 x86 服务器」，因此必须点明 PRoot 与宿主机存储的关系。
     */
    private fun deviceText(input: PromptInput): String {
        val android = input.androidVersion.ifBlank { "未知" }
        return """
            运行环境：
            - 设备：Android 手机（系统版本 $android）。你是 App 内的本地 Agent，不是云端服务，也无法访问用户电脑上的文件。
            - 终端：App 内置的 Ubuntu 24（PRoot），不是手机宿主机系统的 shell，也不是 x86 服务器。
            - 工作区路径、当前权限档、计划模式见紧随用户消息的「本轮运行状态」块。
        """.trimIndent()
    }

    /** Skill 段单独估算：供洞察页把「技能注入」从系统提示词里拆出来。 */
    fun skillSectionTokens(input: PromptInput): Int {
        val text = skillText(input.skills) ?: return 0
        return estimateTokens(text)
    }

    /** Skill 目录：一条一行；没有 Skill 就返回 null（这一整段不出）。 */
    private fun skillText(skills: List<Pair<String, String>>): String? {
        val lines = skills
            .filter { it.first.isNotBlank() }
            .joinToString("\n") { pair ->
                "- " + pair.first.trim() + "：" + pair.second.trim()
            }
        if (lines.isEmpty()) return null
        return DefaultPrompts.SKILLS_HEADER + "\n" + lines
    }
}