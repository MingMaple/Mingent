package com.zmf.agent.agent.prompt

import com.zmf.agent.agent.ApiConfig
import com.zmf.agent.core.model.PermissionLevel

/**
 * 组装系统提示词的全部输入。字段全给默认值：没接线的调用方（比如 ChatViewModel 还没传）
 * 也能直接 `PromptInput()` 出一份能用的提示词，不会因为缺参数拿不到主提示词。
 *
 * @param model 当前对话模型（写进提示词超长告警日志，方便排查「换个模型提示词是否生效」）
 * @param workspacePath 当前工作区绝对路径，写进环境段
 * @param permission 当前权限档，决定用哪一档的能力边界说明
 * @param androidVersion 系统版本号（例如「14」），空串 = 未知
 * @param nowText 当前时间（调用方格式化好），空串 = 未知
 * @param skills 可用 Skill：名称 to 描述；空列表则不出 Skill 段
 * @param extraInstructions 用户附加指令，空串 = 不加
 * @param systemOverride 用户整份覆盖；非空时默认提示词全部不用
 * @param planMode 计划模式（只调研、不产生副作用）
 * @param summaryText 会话摘要，空串 = 没有摘要
 * @param goalText 当前目标的一句话（含轮次），空串 = 没有目标；只进尾部快照，不进 system
 */
data class PromptInput(
    val model: String = ApiConfig.MODEL,
    val workspacePath: String = "",
    val permission: PermissionLevel = PermissionLevel.WORKSPACE,
    val androidVersion: String = "",
    val skills: List<Pair<String, String>> = emptyList(),
    val extraInstructions: String = "",
    val systemOverride: String? = null,
    val planMode: Boolean = false,
    val summaryText: String = "",
    val goalText: String = ""
)
