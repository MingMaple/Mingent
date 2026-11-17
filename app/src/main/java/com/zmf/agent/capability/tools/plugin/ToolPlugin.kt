package com.zmf.agent.capability.tools.plugin

import com.zmf.agent.capability.prompt.PromptSection
import com.zmf.agent.capability.tools.Tool
import kotlin.reflect.KClass

/**
 * 单个工具的插件条目。
 *
 * 注册、注销、依赖校验与提示词归属均以单工具为单位，与 DSH「一个工具一个包」的粒度一致。
 * [group] 只用于文档与界面分组，不参与注册，也不产生组级依赖。
 */
data class ToolPlugin(
    /** 工具本体；[Tool.name] 即插件标识。 */
    val tool: Tool,
    /** 分组标识，仅用于文档与界面，不参与注册。 */
    val group: String,
    /** 依赖的跨层服务；缺任一服务时仅本工具不注册。 */
    val requires: Set<KClass<out Any>> = emptySet(),
    /** 本工具独有的提示词规则；为空表示规则已由提示词总则覆盖。 */
    val prompt: String = ""
) {
    /** 插件标识，等于工具名；注销时使用 `tool:<name>`。 */
    val id: String get() = tool.name

    /** 注册来源名；注册表据此支持逐工具增删。 */
    val source: String get() = SOURCE_PREFIX + id

    /** 转为提示词片段；[index] 由清单按工具名排序后分配。 */
    fun promptSection(index: Int): PromptSection? {
        if (prompt.isBlank()) return null
        return PromptSection(PROMPT_BASE_ORDER + index, "tool/$id", prompt)
    }

    companion object {
        /** 内置工具的来源前缀。 */
        const val SOURCE_PREFIX = "tool:"

        /** 单工具片段的起始序号；位于计划规则之后、技能之前。 */
        const val PROMPT_BASE_ORDER = 2000
    }
}
