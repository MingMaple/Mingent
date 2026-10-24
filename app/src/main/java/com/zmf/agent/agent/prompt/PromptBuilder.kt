package com.zmf.agent.agent.prompt

import com.zmf.agent.agent.ApiConfig
import com.zmf.agent.agent.context.contextSummarySystemText
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.displayName

/**
 * 系统提示词组装器。
 *
 * 一次请求只发一条 system，内容是若干段按 [PromptOrders] 拼起来的：
 * 身份 / 环境 / 权限档 / 边界 / 摘要 / 计划模式 / 工具总则 / 沟通 / 记忆 / Skill / 用户附加指令。
 *
 * 两条硬规则：
 * - 用户整份覆盖（[PromptInput.systemOverride] 非空）优先，默认提示词一条都不出；
 * - 段与段之间空一行，空段不出，全部为空返回 null（调用方据此不塞 system 消息）。
 *
 * 总长超过 [MAX_PROMPT_TOKENS] 只写一条告警日志，不做截断 —— 提示词被砍一半比长一点更糟。
 */
object PromptBuilder {

    private const val TAG = "prompt"

    /**
     * 提示词占窗口的告警比例：0.5%。
     *
     * 为什么不再用写死的 4000：窗口 1M 时 4000 只占 0.4‰，附加指令稍长一点就天天报警，
     * 报警一多就没人看了。按窗口比例算，告警线才跟着模型容量走（1M → 5,242）。
     */
    private const val PROMPT_WINDOW_RATIO = 0.005f

    /** 告警线的硬下限：小窗口（8k）下 0.5% 只有 40，那样每轮都会报，反而失去意义。 */
    private const val MIN_PROMPT_TOKENS = 4000

    /** 提示词总长告警线（粗估 tokens）：窗口的 0.5%，且不低于 [MIN_PROMPT_TOKENS]。 */
    private fun maxPromptTokens(windowTokens: Int = ApiConfig.CONTEXT_WINDOW): Int {
        val byWindow = (windowTokens.coerceAtLeast(1) * PROMPT_WINDOW_RATIO).toInt()
        return maxOf(MIN_PROMPT_TOKENS, byWindow)
    }

    fun build(input: PromptInput): String? {
        // 用户覆盖优先：整份替换，连身份都不带
        val override = input.systemOverride?.trim()
        if (!override.isNullOrEmpty()) return override

        val sections = ArrayList<PromptSection>(12)
        sections += PromptSection(PromptOrders.IDENTITY, "身份", DefaultPrompts.IDENTITY)
        sections += PromptSection(PromptOrders.ENVIRONMENT, "环境", environmentText(input))
        sections += PromptSection(PromptOrders.PERMISSION, "权限", permissionText(input.permission))
        sections += PromptSection(PromptOrders.BOUNDARIES, "边界", DefaultPrompts.BOUNDARIES)
        sections += PromptSection(PromptOrders.TOOL_RULES, "工具总则", DefaultPrompts.TOOL_RULES)
        sections += PromptSection(PromptOrders.COMMUNICATION, "沟通", DefaultPrompts.COMMUNICATION)
        sections += PromptSection(PromptOrders.MEMORY, "记忆", DefaultPrompts.MEMORY_RULES)
        skillText(input.skills)?.let {
            sections += PromptSection(PromptOrders.SKILLS, "Skill", it)
        }
        if (input.extraInstructions.isNotBlank()) {
            sections += PromptSection(PromptOrders.USER_EXTRA, "附加指令", input.extraInstructions.trim())
        }
        if (input.planMode) {
            sections += PromptSection(PromptOrders.PLAN, "计划模式", DefaultPrompts.PLAN_MODE_PROMPT)
        }
        // 常驻：不管在不在计划模式，「在对话里说同意 ≠ 授权执行」这条都成立
        sections += PromptSection(PromptOrders.PLAN_RULES, "计划确认", DefaultPrompts.PLAN_RULES)
        if (input.summaryText.isNotBlank()) {
            // 抬头复用上下文模块里那个函数（原来只有 AgentLoop 在用）：措辞不变，模型不会把摘要当成新发言
            sections += PromptSection(PromptOrders.SUMMARY, "摘要", contextSummarySystemText(input.summaryText))
        }

        val ordered = sections.sortedBy { it.order }.filter { it.text.isNotBlank() }
        if (ordered.isEmpty()) return null

        val text = ordered.joinToString("\n\n") { it.text.trim() }
        val tokens = estimateTokens(text)
        val limit = maxPromptTokens()
        if (tokens > limit) {
            val detail = ordered.joinToString(" / ") { it.name + "≈" + estimateTokens(it.text) }
            FileLog.log(
                TAG,
                "系统提示词偏长：约 " + tokens + " tokens（告警线 " + limit + " = 窗口 " +
                    ApiConfig.CONTEXT_WINDOW + " 的 0.5%，不低于 " + MIN_PROMPT_TOKENS + "），" +
                    "模型=" + input.model + "，各段：" + detail
            )
        }
        return text
    }

    /**
     * 粗估 token：汉字（含全角标点）按 1 字 1 token，其余字符按 4 字符约 1 token、向上取整。
     *
     * 口径跟 ContextCompactor 一致，只用来判断提示词是不是太长，不参与计费。
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

    /** 环境段：模型最容易搞错的就是「这不是 x86 服务器」，PRoot 和宿主机存储必须点明。 */
    private fun environmentText(input: PromptInput): String {
        val android = input.androidVersion.ifBlank { "未知" }
        val workspace = input.workspacePath.ifBlank { "（未提供，按相对路径读写即可）" }
        val now = input.nowText.ifBlank { "未知" }
        return """
            运行环境：
            - 设备：Android 手机（系统版本 $android）。你是 App 内的本地 Agent，不是云端服务，也碰不到用户电脑上的文件。
            - 工作区绝对路径：$workspace
            - 终端：App 内置的 Ubuntu 24（PRoot），不是手机宿主机系统的 shell，也不是 x86 服务器。
            - 当前时间：$now
        """.trimIndent()
    }

    /** 权限段：正文说明 + 明确写出当前档位，模型才不会按上一轮的旧权限行事。 */
    private fun permissionText(level: PermissionLevel): String {
        val body = when (level) {
            PermissionLevel.READ_ONLY -> DefaultPrompts.PERMISSION_READ_ONLY
            PermissionLevel.WORKSPACE -> DefaultPrompts.PERMISSION_WORKSPACE
            PermissionLevel.FULL -> DefaultPrompts.PERMISSION_FULL
        }
        return body + "\n当前档位：" + level.displayName()
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
