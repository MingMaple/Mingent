package com.zmf.agent.tools.plan

import com.zmf.agent.agent.session.PlanStore
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * write_plan —— 计划模式下「把计划交出来」的唯一出口。
 *
 * 为什么必须是工具、而不是让模型把计划写进正文：
 * 正文是纯文本，写完既没法被「同意执行」按钮接住，也没法在下一轮原样带回上下文；
 * 而计划要以结构化形式落盘（PlanDoc → plans.json），页面才能逐条渲染、逐条确认。
 *
 * 为什么是 READ_ONLY：计划模式的闸门就是「只暴露 minPermission == READ_ONLY 的工具」，
 * 这一档下模型读得到文件、列得了目录、抓得了网页，却写不了任何东西——它唯一被允许的「产出」就是这份计划。
 * 如果把权限抬到 WORKSPACE，计划模式就名存实亡了。
 *
 * 为什么是「追加一份」而不是覆盖：用户要像 DSH 那样能切着看多份方案、比较后单独删；
 * 覆盖写会把上一版直接抹掉，用户连对比的机会都没有。代价是同一轮里反复调用会堆出多份，
 * 但那正是用户在页面上一眼能删掉的东西——比悄悄丢历史划算。
 */
class PlanTool : Tool {

    override val name = "write_plan"

    override val description =
        "写出当前会话的一份计划（每次调用各存一份）。这是「产出计划」的唯一入口：调用成功后这一轮就结束，计划会交给用户确认——" +
            "所以不要把计划写进正文里，正文里写的计划用户点不到「同意执行」。" +
            "何时用：计划模式下调研完了、准备动手之前，把要做的事拆成可执行的一步一条。" +
            "参数：title 一句话标题；steps 字符串数组，按执行顺序，一项一步；note 可选一句补充（风险、前提、待确认项）。" +
            "硬边界：每次调用都存一份**新的完整计划**（不是往上一份里打补丁），所以 steps 必须写全；" +
            "多次调用会各存一份，供用户分别预览与比较，不要自己想着去覆盖旧的——旧计划只能由用户在计划页单独删。" +
            "步骤必须是能直接照着做的动作（改哪个文件、跑什么命令、验收标准是什么），" +
            "不要写「优化一下」「完善功能」这种无法验收的话；不要自己加「1.」「-」这类编号，编号由界面生成；" +
            "拿不准的地方写进 note 或写成「先确认 X」，不要编造事实。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "title": {
              "type": "string",
              "description": "计划标题，一句话说清这次要达成什么"
            },
            "steps": {
              "type": "array",
              "description": "可执行的步骤，按执行顺序；一项一步，不要写序号前缀，也不要合并成一整段",
              "items": {"type": "string"}
            },
            "note": {
              "type": "string",
              "description": "可选的补充说明：风险、前提、需要用户先确认的点"
            }
          },
          "required": ["title", "steps"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val title = str(args, "title")
        if (title.isEmpty()) {
            return ToolResult.Error("缺 title：给这份计划起一个一句话标题（例如「重写登录页的输入校验」）")
        }

        // 「缺参数」和「显式 null」要分开报：org.json 的 optJSONArray 分不出这两种情况
        if (!args.has("steps") || args.isNull("steps")) {
            return ToolResult.Error("缺 steps：把计划拆成可执行的步骤，一步一条放进 steps 字符串数组")
        }
        val array = args.optJSONArray("steps")
            ?: return ToolResult.Error("steps 必须是字符串数组，收到的是：" + args.opt("steps").toString().take(120))

        val steps = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val step = array.optString(i, "").trim()
            // 空串和字面 "null" 都算没写：宁可拦下来让模型补，也不要存一条「null」步骤进计划里
            if (step.isEmpty() || step == "null") continue
            steps.add(step)
        }
        if (steps.isEmpty()) {
            return ToolResult.Error("steps 里没有有效步骤：至少写一条能直接执行的步骤（一条一行，不要写成一整段）")
        }

        if (ctx.sessionId.isBlank()) {
            // 计划按会话存，没有会话 id 就没地方落盘；静默丢弃会让用户以为计划交上来了
            return ToolResult.Error("当前没有会话 id，计划无处可存：请在某个会话里重试")
        }

        // 落盘失败由 PlanStore 自己记日志兜住（不抛），这里只把结果说清楚
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
            // appendPlan 一定至少加一份，空列表只能是写盘失败：这时报「已保存」会让用户以为计划在
            return ToolResult.Error("计划没能写入磁盘（appdata/plans.json 不可写），请检查存储空间或应用私有目录权限后重试")
        }
        return ToolResult.Ok(
            "已保存第 " + saved.size + " 份计划：" + title + "（" + steps.size + " 步）"
        )
    }

    /** 读字符串参数：缺失、显式 null、字面 "null" 都算空（org.json 的 optString 会把 NULL 读成 "null"）。 */
    private fun str(args: JSONObject, key: String): String {
        if (!args.has(key) || args.isNull(key)) return ""
        val raw = args.optString(key, "").trim()
        return if (raw == "null") "" else raw
    }
}
