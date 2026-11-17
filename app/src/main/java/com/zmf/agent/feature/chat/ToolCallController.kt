package com.zmf.agent.feature.chat

import androidx.compose.runtime.mutableStateMapOf
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.capability.tools.ToolResult

/** 目标工具名：该工具执行完毕后目标即发生变化，需立即重新读取（见 [ToolCallController.onToolFinished]）。 */
private const val GOAL_TOOL = "goal"

/**
 * 工具调用展示：本次回复中每个 tool_call 的成败（用于工具卡着色）。
 *
 * 从 [ChatViewModel] 拆出；用户 2026-11-15 要求「每条会话各跑一个回合」之后，
 * 成败表按会话分开——否则 B 会话的工具卡会读到 A 会话刚结束的成败。
 *
 * **不写输入条活动行**（用户 2026-11-16 真机复验未通过后定案）：工具开始与结束都不再往输入条上方
 * 写「调用 xxx」「已返回 xxx」「失败 xxx」。那条位置只留给上下文压缩提示；工具是否在跑由过程组头的
 * 流光与工具卡自身的状态承担，成败由工具卡的红标与轨迹页承担。
 */
class ToolCallController(private val reloadGoal: () -> Unit) {

    /** sessionId -> (tool_call_id -> 工具是否成功)；仅用于界面为工具卡着色，不参与请求。 */
    private val bySession = mutableStateMapOf<String, Map<String, Boolean>>()

    /** 某条会话的成败表；界面按当前显示的会话取。 */
    fun outcomesOf(sessionId: String): Map<String, Boolean> = bySession[sessionId].orEmpty()

    /**
     * 工具执行结束：记录该步骤的成败供工具卡读取，goal 工具成功时同时刷新目标。
     *
     * 这里不产生任何界面提示行：那是用户明确要求删掉的来源，见类注释。
     */
    fun onToolFinished(sessionId: String, call: ToolCall, result: ToolResult) {
        if (call.id.isNotEmpty()) {
            bySession[sessionId] = bySession[sessionId].orEmpty() + (call.id to result.ok)
        }
        // goal 工具修改 goals.json：修改后立即读取，目标页与状态行无需等待下一轮
        if (result.ok && call.name == GOAL_TOOL) reloadGoal()
    }

    /** 删除会话、回滚等场景清除该会话的旧成败记录，避免同 id 复用时颜色错乱。 */
    fun clear(sessionId: String) {
        bySession.remove(sessionId)
    }
}
