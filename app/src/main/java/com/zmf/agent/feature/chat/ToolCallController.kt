package com.zmf.agent.feature.chat

import androidx.compose.runtime.mutableStateMapOf
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.capability.tools.ToolResult

/** 目标工具名：该工具执行完毕后目标即发生变化，需立即重新读取（见 [ToolCallController.onToolFinished]）。 */
private const val GOAL_TOOL = "goal"

/**
 * 工具调用展示：本次回复中每个 tool_call 的成败（用于工具卡着色），以及工具起止的进行中提示。
 *
 * 从 [ChatViewModel] 拆出，行为不变：字段名与原成员逐字一致，方法体原样保留；
 * 仅将两处外部动作改为注入——进行中提示的回显入口、goal 工具成功后的目标重读——
 * 避免该类反向持有整个 ViewModel。
 */
class ToolCallController(
    private val setActivity: (String?) -> Unit,
    private val reloadGoal: () -> Unit
) {

    /** tool_call_id -> 工具是否成功；仅用于界面为工具卡着色，不参与请求。 */
    val outcomes = mutableStateMapOf<String, Boolean>()

    /** 工具开始执行：只改进行中提示，不提前写结果。 */
    fun onToolStarted(call: ToolCall) {
        setActivity("调用 " + call.name)
    }

    /** 工具执行结束：记录该步骤的成败供工具卡读取，goal 工具成功时同时刷新目标。 */
    fun onToolFinished(call: ToolCall, result: ToolResult) {
        if (call.id.isNotEmpty()) outcomes[call.id] = result.ok
        setActivity((if (result.ok) "已返回 " else "失败 ") + call.name)
        // goal 工具修改 goals.json：修改后立即读取，目标页与状态行无需等待下一轮
        if (result.ok && call.name == GOAL_TOOL) reloadGoal()
    }

    /** 切换会话、删除会话、回滚等场景清除旧的成败记录，避免同 id 复用时颜色错乱。 */
    fun clear() {
        outcomes.clear()
    }
}
