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
 * 各段的固定次序（稳定在前、易变在后）。
 *
 * 取值留了间隔（10 / 100 / 9000 …），以后想在两段之间插新段不用改已有常量。
 *
 * **进 system 的（稳定层）**：契约是同一会话内一字不变，否则前缀缓存失效。
 * - 身份 → 设备事实 → 边界 → 工具总则 → 沟通 → 记忆 → 计划确认 → Skill → 附加指令。
 * - 边界排在设备事实之后、工具总则之前：判定类要早于描述类（长上下文里中间位置最容易被忽略，
 *   硬约束必须靠前）。
 *
 * **只进尾部快照的（易变层，编号统一放到 9000+）**：权限档、工作区路径、计划模式、当前时间、摘要。
 * 它们以前挤在 system 的第 2、3 段，导致「隔一分钟发一句话」就整份缓存作废。
 */
object PromptOrders {
    // ---- 稳定层（进 system）----
    const val IDENTITY = -1000
    const val ENVIRONMENT = 0
    // 权限三档的能力事实：静态，进 system。当前生效哪一档由快照那一行决定
    const val PERMISSION_FACTS = 50
    const val BOUNDARIES = 100
    const val TOOL_RULES = 200
    // 沙箱与删除：讲清硬闸门的三档判据（紧贴工具总则，判定类要早于风格类）
    const val SANDBOX = 210
    const val COMMUNICATION = 300
    const val MEMORY = 400
    const val PLAN_RULES = 500
    // 计划模式说明：**常驻**段。以前只在开启时出现，进出模式就改前缀、缓存全废
    const val PLAN_POLICY = 510
    const val SKILLS = 3100
    const val USER_EXTRA = 3200

    // ---- 易变层（进尾部运行时快照，只有 build() 估算时才按这个顺序拼）----
    const val PERMISSION = 9100
    const val PLAN = 9200
    const val SUMMARY = 9900
}
