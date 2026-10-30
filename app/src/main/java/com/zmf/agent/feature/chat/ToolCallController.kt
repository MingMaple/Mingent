package com.zmf.agent.feature.chat

import androidx.compose.runtime.mutableStateMapOf
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.capability.tools.ToolResult

/** 目标工具名：它一跑完目标就变了，得立刻重读（见 [ToolCallController.onToolFinished]）。 */
private const val GOAL_TOOL = "goal"

/**
 * 工具调用展示：本次回复里每个 tool_call 的成败（工具卡上色用），以及工具起止的进行中提示。
 *
 * 从 [ChatViewModel] 搬出，行为不变：字段名与原成员逐字一致，方法体原样照搬；
 * 只把两处外部动作改成注入——进行中提示的回显入口、goal 工具成功后的目标重读——
 * 免得这个类反过来攥住整个 ViewModel。
 */
class ToolCallController(
    private val setActivity: (String?) -> Unit,
    private val reloadGoal: () -> Unit
) {

    /** tool_call_id -> 工具是否成功；只用于界面给工具卡上色，不参与请求。 */
    val outcomes = mutableStateMapOf<String, Boolean>()

    /** 工具开始执行：只改进行中提示，不提前写结果。 */
    fun onToolStarted(call: ToolCall) {
        setActivity("调用 " + call.name)
    }

    /** 工具执行结束：记下这一步的成败供工具卡读，goal 工具成功时顺带刷新目标。 */
    fun onToolFinished(call: ToolCall, result: ToolResult) {
        if (call.id.isNotEmpty()) outcomes[call.id] = result.ok
        setActivity((if (result.ok) "已返回 " else "失败 ") + call.name)
        // goal 工具改的是 goals.json：改完立刻读回来，目标页与状态行不用等下一轮
        if (result.ok && call.name == GOAL_TOOL) reloadGoal()
    }

    /** 切会话／删会话／回滚等场景把旧的成败清掉，免得同 id 复用时颜色错乱。 */
    fun clear() {
        outcomes.clear()
    }
}
