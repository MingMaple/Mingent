package com.zmf.agent.agent.prompt

/**
 * 系统提示词的一段。
 *
 * [order] 决定拼接顺序（小的在前）：system 是请求里最稳定的前缀，顺序固定才吃得到 provider 的
 * prompt cache；新加的段尽量排到后面，别插队到已有段前面把缓存整个冲掉。
 * [name] 只给日志和排查用（哪段太长、哪段这次没出），不会进提示词正文。
 */
data class PromptSection(val order: Int, val name: String, val text: String)

/**
 * 各段的固定次序。
 *
 * 取值故意留了间隔（10 / 100 / 1000 …），以后想在两段之间插新段时不用改已有常量。
 * - 身份 / 环境 / 权限 / 边界：几乎不变，放最前（缓存前缀）；边界紧跟在权限后面，
 *   因为权限段是「事实」、边界是「按事实该怎么处置」，两句挨着读才不打架；
 * - 摘要 / 计划模式：随会话变，但约束的是「怎么理解当前任务」，要排在工具总则之前；
 * - 工具总则 / 沟通 / Skill / 用户附加指令：越往后越容易变，放后面少动前缀。
 */
object PromptOrders {
    const val IDENTITY = -1000
    const val ENVIRONMENT = 0
    const val PERMISSION = 110
    const val BOUNDARIES = 150
    const val SUMMARY = 200
    const val PLAN = 500
    // 计划页的确认规则：紧跟计划模式之后（一个是「现在只准调研」，一个是「怎么才算授权」）
    const val PLAN_RULES = 520
    const val TOOL_RULES = 1000
    const val COMMUNICATION = 1500
    const val MEMORY = 3050
    const val SKILLS = 3100
    const val USER_EXTRA = 10200
}
