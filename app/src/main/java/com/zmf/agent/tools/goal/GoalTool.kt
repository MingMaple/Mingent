package com.zmf.agent.tools.goal

import com.zmf.agent.agent.goal.Goal
import com.zmf.agent.agent.goal.GoalPhase
import com.zmf.agent.agent.goal.GoalStore
import com.zmf.agent.agent.goal.label
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * goal 工具：设置 / 更新本次会话的长期目标。
 *
 * 为什么需要它：普通回复是「一轮一问一答」，遇到要跨多轮推进的事（装环境、批量整理文件）
 * 模型很容易停在一半等人催。目标就是给这次会话挂一个「还在做的事」，
 * ACTIVE 时 AgentLoop 每轮结束会自动接着推进；模型要么推进、要么 pause/complete，
 * 不能挂着目标空转——空转会把自动续跑的轮次白白烧掉。
 *
 * 只写自己的 appdata/goals.json，不碰工作区，所以权限档是 READ_ONLY。
 */
class GoalTool : Tool {

    override val name = "goal"

    override val description =
        "设置或更新本次会话的长期目标。目标是跨多轮的长期任务：处于进行中时，每轮结束系统会自动继续推进，" +
            "所以要么用 update 汇报进度接着做，要么 pause / complete，不要空转；任务做完一定要用 complete 收尾。" +
            "create 需要 objective；update 改 objective / note；pause / resume / complete 改阶段，complete 可以带 note 当结论。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["create", "update", "complete", "pause", "resume"],
              "description": "create = 新建目标（会替换本会话原有目标），update = 改写目标或附注，pause / resume = 暂停 / 继续，complete = 做完收尾"
            },
            "objective": {
              "type": "string",
              "description": "目标的完整表述，create 必填；update 时用来改写目标正文"
            },
            "note": {
              "type": "string",
              "description": "附注：update 时是进度说明，complete 时是结论"
            }
          },
          "required": ["action"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val action = args.optString(ACTION, "").trim().lowercase()
        if (action.isEmpty()) {
            return ToolResult.Error("缺少 action：可选 " + ACTIONS_TEXT)
        }
        val objective = textArg(args, "objective")
        val note = textArg(args, "note")
        // 落盘走 GoalStore，读盘/写盘失败它自己记日志兜住（不抛），这里不套 try
        return withContext(Dispatchers.IO) { handleAction(action, objective, note, ctx) }
    }

    private fun handleAction(action: String, objective: String, note: String, ctx: ToolContext): ToolResult {
        val current = GoalStore.load(ctx.context, ctx.sessionId)
        return when (action) {
            ACTION_CREATE -> {
                if (objective.isEmpty()) {
                    return ToolResult.Error("create 需要 objective：用一句话写清要长期推进的目标")
                }
                // 已有目标也照样覆盖：换目标是用户的明确动作，但轮次从 0 重算（旧目标的进度不再有意义）
                val goal = Goal(objective = objective, phase = GoalPhase.ACTIVE, rounds = 0, note = note)
                GoalStore.save(ctx.context, ctx.sessionId, goal)
                report("目标已创建", goal)
            }

            ACTION_UPDATE -> {
                val goal = current ?: return missing()
                if (objective.isEmpty() && note.isEmpty()) {
                    return ToolResult.Error("update 至少要给 objective 或 note 之一")
                }
                // 阶段和轮次归 AgentLoop 管，update 只碰正文和附注
                val next = goal.copy(
                    objective = objective.ifEmpty { goal.objective },
                    note = note.ifEmpty { goal.note }
                )
                GoalStore.save(ctx.context, ctx.sessionId, next)
                report("目标已更新", next)
            }

            ACTION_PAUSE -> {
                val goal = current ?: return missing()
                if (goal.phase == GoalPhase.DONE) {
                    return ToolResult.Error("目标已完成，不能再暂停；要开新任务用 create")
                }
                val next = goal.copy(phase = GoalPhase.PAUSED)
                GoalStore.save(ctx.context, ctx.sessionId, next)
                report("目标已暂停", next)
            }

            ACTION_RESUME -> {
                val goal = current ?: return missing()
                if (goal.phase == GoalPhase.DONE) {
                    return ToolResult.Error("目标已完成，不能继续；要开新任务用 create")
                }
                val next = goal.copy(phase = GoalPhase.ACTIVE)
                GoalStore.save(ctx.context, ctx.sessionId, next)
                report("目标已继续", next)
            }

            ACTION_COMPLETE -> {
                val goal = current ?: return missing()
                // complete 带 note 时当结论写进去，不带就保留原来的附注
                val next = goal.copy(phase = GoalPhase.DONE, note = note.ifEmpty { goal.note })
                GoalStore.save(ctx.context, ctx.sessionId, next)
                report("目标已完成", next)
            }

            else -> ToolResult.Error("不认识的 action：" + action + "；可选 " + ACTIONS_TEXT)
        }
    }

    /** 没有目标时的统一提示：直接告诉模型下一步该调什么，省一轮试错。 */
    private fun missing(): ToolResult =
        ToolResult.Error("当前会话还没有目标，先用 action=create 并带上 objective 新建一个")

    /** 成功文本：动作 + 当前阶段 + 已跑轮次 + 目标正文（模型下一次据此判断要不要继续）。 */
    private fun report(result: String, goal: Goal): ToolResult {
        val text = buildString {
            append(result).append("；当前阶段：").append(goal.phase.label())
            if (goal.rounds > 0) append("；已跑 ").append(goal.rounds).append(" 轮")
            append("。目标：").append(goal.objective)
            if (goal.note.isNotEmpty()) {
                append("。").append(if (goal.phase == GoalPhase.DONE) "结论：" else "附注：").append(goal.note)
            }
        }
        return ToolResult.Ok(text)
    }

    /** 读字符串参数：缺 / 显式 null / 字符串 "null" 一律当空串。 */
    private fun textArg(args: JSONObject, key: String): String {
        if (!args.has(key) || args.isNull(key)) return ""
        val value = args.optString(key, "").trim()
        return if (value == "null") "" else value
    }

    private companion object {
        const val ACTION = "action"
        const val ACTION_CREATE = "create"
        const val ACTION_UPDATE = "update"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_COMPLETE = "complete"
        const val ACTIONS_TEXT = "create / update / pause / resume / complete"
    }
}
