package com.zmf.agent.capability.tools.goal.goal

import com.zmf.agent.core.store.Goal
import com.zmf.agent.core.store.GoalPhase
import com.zmf.agent.core.store.GoalStore
import com.zmf.agent.core.store.label
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * goal 工具：设置 / 更新本次会话的长期目标。
 *
 * 普通回复为一问一答，跨多轮的任务（安装环境、批量整理文件）容易在中途停止并等待用户催促。
 * 目标用于标记本次会话仍在推进的事项：ACTIVE 时 AgentLoop 会在每轮结束后自动继续，
 * 模型应当推进目标，或调用 pause / complete 结束；不得在目标未完成时空转，
 * 否则会浪费自动续跑的轮次。
 *
 * 修改已有目标必须带上读到的 revision：写之前精确比对，避免覆盖用户或其他轮次的改动。
 * 连续三次 blocked 后目标转入 NEEDS_USER，只能由用户在界面恢复。
 *
 * 只写入 appdata/goals.json，不访问工作区，因此权限档为 READ_ONLY。
 */
class GoalTool : Tool {

    override val name = "goal"

    override val description =
        "设置或更新本次会话的长期目标，action 决定具体动作。" +
            "目标进行中时每轮结束系统自动继续；完成后用 complete 收尾，无法推进时用 blocked 并写明原因。" +
            "修改已有目标必须带 revision，resume 仅限用户在界面触发；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["create", "edit", "update", "complete", "pause", "resume", "blocked"],
              "description": "动作，取 create（新建并替换本会话原目标）、edit（改写目标或附注，旧名 update）、pause（暂停）、resume（仅限用户在界面恢复）、complete（完成收尾）、blocked（遇到无法解决的阻塞）；必填，无默认值"
            },
            "objective": {
              "type": "string",
              "description": "目标的完整表述；create 必填，edit 时用于改写正文；无默认值"
            },
            "note": {
              "type": "string",
              "description": "附注：edit 时为进度说明，complete 时为结论，blocked 时必填并写阻塞原因；无默认值"
            },
            "revision": {
              "type": "integer",
              "description": "读取到的目标 revision，非负整数，取自上下文「当前目标」状态行；edit、update、pause、complete、blocked 修改已有目标时必填，无默认值"
            }
          },
          "required": ["action"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    /** 所有动作都会写目标存储，算副作用（落盘检查点据此在调用前存一份会话）。 */
    override val mutates = true

    /** 权限档只约束文件与命令：目标属于控制类状态，只读档同样可用。 */
    override val sandboxSensitive = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val raw = args.optString(ACTION, "").trim().lowercase()
        // edit 是官方 update_goal 的动作名，update 是早期会话使用过的旧名。
        val action = if (raw == "update") ACTION_UPDATE else raw
        if (action.isEmpty()) {
            return ToolResult.Error("[E_INVALID] 缺少 action：可选 " + ACTIONS_TEXT + "。")
        }
        val objective = textArg(args, "objective")
        val note = textArg(args, "note")
        val revision = readRevision(args)
        // 写入由 GoalStore 负责，其读写失败仅记录日志且不抛出异常，因此此处无需 try
        return withContext(Dispatchers.IO) { handleAction(action, objective, note, revision, ctx) }
    }

    private fun handleAction(
        action: String,
        objective: String,
        note: String,
        revision: Int,
        ctx: ToolContext
    ): ToolResult {
        val current = GoalStore.load(ctx.context, ctx.sessionId)
        return when (action) {
            ACTION_CREATE -> {
                if (objective.isEmpty()) {
                    return ToolResult.Error("[E_INVALID] create 需要 objective：请用一句话写清要长期推进的目标。")
                }
                // 已有目标同样覆盖：换目标属于用户的明确动作，但轮次从 0 重算（原目标的进度不再保留）
                val goal = Goal(
                    objective = objective,
                    phase = GoalPhase.ACTIVE,
                    rounds = 0,
                    note = note,
                    blockedStreak = 0
                )
                persist("目标已创建", goal, ctx)
            }

            ACTION_UPDATE -> {
                val goal = current ?: return missing()
                revisionError(goal, revision)?.let { return it }
                if (objective.isEmpty() && note.isEmpty()) {
                    return ToolResult.Error("[E_INVALID] edit 至少需要 objective 或 note 之一。")
                }
                // 阶段和轮次归 AgentLoop 管，edit 只碰正文和附注；成功更新即清零连续阻塞计数
                val next = goal.copy(
                    objective = objective.ifEmpty { goal.objective },
                    note = note.ifEmpty { goal.note },
                    blockedStreak = 0
                )
                persist("目标已更新", next, ctx)
            }

            ACTION_PAUSE -> {
                val goal = current ?: return missing()
                revisionError(goal, revision)?.let { return it }
                if (goal.phase == GoalPhase.DONE) {
                    return ToolResult.Error("[E_INVALID] 目标已完成，不能暂停；要开新任务请用 create。")
                }
                val next = goal.copy(phase = GoalPhase.PAUSED)
                persist("目标已暂停", next, ctx)
            }

            ACTION_RESUME -> {
                // 恢复意味着用户确认继续：这是用户界面上的动作，助手不得代劳
                val goal = current ?: return missing()
                ToolResult.Error(
                    "[E_USER_ONLY] resume 只能由用户在界面恢复目标；当前目标处于「" + goal.phase.label() +
                        "」，请等待用户操作，或按现状说明阻塞。"
                )
            }

            ACTION_COMPLETE -> {
                val goal = current ?: return missing()
                revisionError(goal, revision)?.let { return it }
                // complete 带 note 时将其作为结论写入，不带 note 时保留原附注
                val next = goal.copy(
                    phase = GoalPhase.DONE,
                    note = note.ifEmpty { goal.note },
                    blockedStreak = 0
                )
                persist("目标已完成", next, ctx)
            }

            ACTION_BLOCKED -> {
                val goal = current ?: return missing()
                revisionError(goal, revision)?.let { return it }
                if (note.isEmpty()) {
                    return ToolResult.Error("[E_INVALID] blocked 需要 note：请写清阻塞原因，供用户决定下一步。")
                }
                val streak = goal.blockedStreak + 1
                // 已转交用户的目标不得因再次 blocked 降级回 PAUSED，否则「需要用户处理」的语义会丢失
                val needsUser = streak >= MAX_BLOCKED_STREAK || goal.phase == GoalPhase.NEEDS_USER
                // 未到阈值：挂起为 PAUSED，等用户决定；到阈值：转入 NEEDS_USER，只能由用户恢复
                val next = goal.copy(
                    phase = if (needsUser) GoalPhase.NEEDS_USER else GoalPhase.PAUSED,
                    note = (if (needsUser) "连续阻塞三次，需要用户处理：" else "阻塞：") + note,
                    blockedStreak = streak
                )
                val result = if (needsUser) {
                    "目标已连续阻塞 " + streak + " 次，转入待用户处理"
                } else {
                    "目标已阻塞并暂停（连续第 " + streak + " 次，第 " + MAX_BLOCKED_STREAK + " 次将转交用户）"
                }
                persist(result, next, ctx)
            }

            else -> ToolResult.Error("[E_INVALID] 不认识的 action：" + action + "；可选 " + ACTIONS_TEXT + "。")
        }
    }

    /** 没有目标时的统一提示：直接说明下一步应调用的动作，减少一轮试探。 */
    private fun missing(): ToolResult =
        ToolResult.Error("[E_INVALID] 当前会话还没有目标：请先用 action=create 并带上 objective 新建。")

    /**
     * revision 精确比对：不传按错误处理（提示先读取当前目标），不一致则拒绝并给出当前摘要。
     *
     * 比对放在一切写入之前，任何拒绝都不会产生部分修改。
     */
    private fun revisionError(goal: Goal, revision: Int): ToolResult? {
        if (revision < 0) {
            return ToolResult.Error(
                "[E_REVISION] 修改已有目标必须带上当前 revision（当前为 " + goal.revision + "）；" +
                    "请先读取上下文「当前目标」状态行，再用同一 revision 重试。"
            )
        }
        if (revision != goal.revision) {
            return ToolResult.Error(
                "[E_REVISION] 目标已被修改（当前 revision=" + goal.revision + "，传入的是 " + revision +
                    "），本次修改未生效。当前内容摘要：阶段=" + goal.phase.label() +
                    "；正文=" + goal.objective.take(MAX_SUMMARY_CHARS) +
                    (if (goal.note.isNotEmpty()) "；附注=" + goal.note.take(MAX_SUMMARY_CHARS) else "") +
                    "。请按当前 revision 重新读取后再改。"
            )
        }
        return null
    }

    /** 落盘并回报；写入失败时明确告知，不把旧状态当成已生效。 */
    private fun persist(result: String, next: Goal, ctx: ToolContext): ToolResult {
        val saved = GoalStore.save(ctx.context, ctx.sessionId, next)
            ?: return ToolResult.Error(
                "[E_SERVICE_UNAVAILABLE] 目标保存失败：写入 goals.json 出错；请稍后重试，此项不能通过调整参数解决。"
            )
        return report(result, saved)
    }

    /** 成功文本：动作 + 当前阶段 + revision + 已跑轮次 + 目标正文（模型下一次据此判断要不要继续）。 */
    private fun report(result: String, goal: Goal): ToolResult {
        val text = buildString {
            append(result).append("；当前阶段：").append(goal.phase.label())
            append("；revision ").append(goal.revision)
            if (goal.rounds > 0) append("；已跑 ").append(goal.rounds).append(" 轮")
            append("。目标：").append(goal.objective)
            if (goal.note.isNotEmpty()) {
                append("。").append(if (goal.phase == GoalPhase.DONE) "结论：" else "附注：").append(goal.note)
            }
        }
        return ToolResult.Ok(text)
    }

    /** 读 revision：缺 / 显式 null / 非数字都返回 -1，由 [revisionError] 统一报错。 */
    private fun readRevision(args: JSONObject): Int {
        if (!args.has("revision") || args.isNull("revision")) return -1
        return args.optInt("revision", -1)
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
        const val ACTION_UPDATE = "edit"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_COMPLETE = "complete"
        const val ACTION_BLOCKED = "blocked"
        const val ACTIONS_TEXT = "create / edit（或旧名 update） / pause / resume / complete / blocked"

        /** 连续 blocked 达到该次数后目标转交用户，不再允许模型自行续跑。 */
        const val MAX_BLOCKED_STREAK = 3

        /** 拒绝回执里附带的当前内容摘要长度。 */
        const val MAX_SUMMARY_CHARS = 80
    }
}
