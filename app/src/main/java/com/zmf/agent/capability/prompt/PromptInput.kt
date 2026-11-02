package com.zmf.agent.capability.prompt
import com.zmf.agent.core.llm.ApiDefaults

import com.zmf.agent.core.model.PermissionLevel

/**
 * 组装系统提示词的全部输入。各字段均提供默认值，
 * 调用方可直接构造 `PromptInput()` 得到一份可用的提示词，不会因缺少参数而无法生成主提示词。
 *
 * @param model 当前对话模型（写进提示词超长告警日志，方便排查「换个模型提示词是否生效」）
 * @param workspacePath 当前工作区绝对路径，写进环境段
 * @param permission 当前权限档，决定用哪一档的能力边界说明
 * @param androidVersion 系统版本号（例如「14」），空串 = 未知
 * @param nowText 当前时间（调用方格式化好，含星期与时区偏移），空串 = 不注入时间行
 * @param skills 可用 Skill：名称 to 描述；空列表则不出 Skill 段
 * @param extraInstructions 用户附加指令，空串 = 不加
 * @param systemOverride 用户整份覆盖；非空时默认提示词全部不用
 * @param planMode 计划模式（只调研、不产生副作用）
 * @param summaryText 会话摘要，空串 = 没有摘要
 * @param goalText 当前目标的一句话（含轮次），空串 = 没有目标；只进尾部快照，不进 system
 */
data class PromptInput(
    val model: String = ApiDefaults.MODEL,
    val workspacePath: String = "",
    val permission: PermissionLevel = PermissionLevel.WORKSPACE,
    val androidVersion: String = "",
    val nowText: String = "",
    val skills: List<Pair<String, String>> = emptyList(),
    val extraInstructions: String = "",
    val systemOverride: String? = null,
    val planMode: Boolean = false,
    val summaryText: String = "",
    val goalText: String = ""
)
