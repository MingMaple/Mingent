package com.zmf.agent.capability.tools.plan.write_plan

import com.zmf.agent.core.store.PlanStore
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * write_plan：计划模式下「把计划交出来」的唯一出口。
 *
 * 为何必须通过工具而非让模型写入正文：
 * 正文是纯文本，无法被「同意执行」按钮接收，也无法在下一轮原样带回上下文；
 * 计划需要以结构化形式落盘（PlanDoc → plans.json），页面才能逐条渲染并确认。
 *
 * 为何使用 READ_ONLY：计划模式的闸门是「只暴露 minPermission == READ_ONLY 的工具」，
 * 该档位下模型可读取文件、列出目录、抓取网页，但不能写入任何内容，唯一允许的产出就是这份计划。
 * 若提高到 WORKSPACE，计划模式将失去约束作用。
 *
 * 为何采用「追加一份」而非覆盖：便于切换查看多份方案、比较后单独删除；
 * 覆盖会直接抹去上一版，用户无法对比。代价是同一轮内反复调用会生成多份，
 * 但用户可在页面上直接删除，优于静默丢弃历史。
 */
class PlanTool : Tool {

    override val name = "write_plan"

    /** 交计划会写入 App 自身的计划存储，但属于控制类工具：权限档只约束文件与命令。 */
    override val mutates = true

    override val sandboxSensitive = false

    /** 计划模式唯一放行的「写」：交计划就是该模式的产出，计划模式正是为它放行。 */
    override fun planModeAllowed(args: JSONObject): Boolean = true

    override val description =
        "为当前会话提交一份可执行计划，每次调用各存一份新的完整计划。" +
            "计划不要写进正文，正文中的计划无法接受「同意执行」；提交成功后本轮结束，等待用户确认。" +
            "steps 按执行顺序一项一步、不写编号；计划模式唯一放行的写类工具，只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "title": {
              "type": "string",
              "description": "计划标题，一句话说明本次要达成什么；必填，无默认值"
            },
            "steps": {
              "type": "array",
              "description": "可执行步骤，按执行顺序一项一步；每步写成能直接照做的动作（改哪个文件、跑什么命令、验收标准），不加编号；必填，至少一条",
              "items": {"type": "string"}
            },
            "note": {
              "type": "string",
              "description": "可选补充：风险、前提、需要用户先确认的点；省略时不写"
            }
          },
          "required": ["title", "steps"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val title = str(args, "title")
        if (title.isEmpty()) {
            return ToolResult.Error("[E_INVALID] 缺少 title：请给这份计划起一个一句话标题后重试。")
        }

        // 「缺少参数」与「显式 null」需分别报告：org.json 的 optJSONArray 无法区分这两种情况
        if (!args.has("steps") || args.isNull("steps")) {
            return ToolResult.Error("[E_INVALID] 缺少 steps：请把计划拆成可执行步骤，一项一步放进字符串数组。")
        }
        val array = args.optJSONArray("steps")
            ?: return ToolResult.Error(
                "[E_INVALID] steps 必须是字符串数组，收到的是：" + args.opt("steps").toString().take(120) + "；请修正后重试。"
            )

        val steps = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val step = array.optString(i, "").trim()
            // 空串与字面 "null" 均视为未填写：宁可拒绝并让模型补充，也不将「null」写入计划步骤
            if (step.isEmpty() || step == "null") continue
            steps.add(step)
        }
        if (steps.isEmpty()) {
            return ToolResult.Error("[E_INVALID] steps 没有有效步骤：至少写一条能直接执行的步骤，一项一条。")
        }

        if (ctx.sessionId.isBlank()) {
            // 计划按会话存储，缺少会话 id 则无法落盘；静默丢弃会让用户误以为计划已提交
            return ToolResult.Error("[E_INVALID] 当前没有会话 id，计划无处保存；请在本会话内重试。")
        }

        // 落盘失败由 PlanStore 自行记录日志（不抛出），此处只需说明结果
        val saved = withContext(Dispatchers.IO) {
            PlanStore.appendPlan(
                ctx.context,
                ctx.sessionId,
                title,
                steps,
                str(args, "note")
            )
        }
        if (saved.isEmpty()) {
            // appendPlan 至少会加入一份，空列表只可能是写盘失败；此时返回「已保存」会误导用户
            return ToolResult.Error(
                "[E_SERVICE_UNAVAILABLE] 计划无法写入 appdata/plans.json；请检查存储空间后重试，此项不能通过调整参数解决。"
            )
        }
        return ToolResult.Ok(
            "已保存第 " + saved.size + " 份计划：「" + title + "」（" + steps.size + " 步），等待用户确认。"
        )
    }

    /** 读字符串参数：缺失、显式 null、字面 "null" 都算空（org.json 的 optString 会把 NULL 读成 "null"）。 */
    private fun str(args: JSONObject, key: String): String {
        if (!args.has(key) || args.isNull(key)) return ""
        val raw = args.optString(key, "").trim()
        return if (raw == "null") "" else raw
    }
}
