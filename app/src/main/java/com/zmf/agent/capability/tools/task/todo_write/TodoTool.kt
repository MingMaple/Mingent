package com.zmf.agent.capability.tools.task.todo_write

import com.zmf.agent.core.store.Todo
import com.zmf.agent.core.store.TodoStore
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * todo_write 工具：整表替换当前会话的待办清单。
 *
 * 为何采用「整表替换」而非 add / update / remove 三个独立操作：
 * 模型每次均能看到上一版清单，直接提交新版本更为简便，也不会产生
 * 「删除一半、修改一半」的中间状态；持久化侧同样只需一次覆盖写。
 *
 * 仅写入自身的 appdata/todos.json，不涉及工作区文件，因此权限档为 READ_ONLY：
 * 只读档下模型也应能够整理自己的待办，不因「写清单」被拦截。
 */
class TodoTool : Tool {

    override val name = "todo_write"

    /** 待办写入 App 自身状态，但属于控制类工具：权限档只约束文件与命令。 */
    override val mutates = true

    override val sandboxSensitive = false

    override val description =
        "整表替换本次会话的任务清单，todos 未列出的任务会被丢弃。" +
            "仅在多步任务中使用；步数少的任务不建清单，清单用于防遗漏，不作进度汇报。" +
            "status 同时最多一条 in_progress，todos 传空数组即清空；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "todos": {
              "type": "array",
              "description": "任务清单的完整内容，整表替换而非增量；必填，无默认值，传空数组表示清空",
              "items": {
                "type": "object",
                "properties": {
                  "content": {"type": "string", "description": "任务内容，去首尾空白后不能为空；必填，无默认值"},
                  "status": {
                    "type": "string",
                    "enum": ["pending", "in_progress", "completed"],
                    "description": "任务状态，取 pending（未开始）、in_progress（正在做，整个清单最多一条）、completed（已完成）；必填，无默认值"
                  }
                },
                "required": ["content", "status"]
              }
            }
          },
          "required": ["todos"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        // 「缺少参数」与「显式 null」需分别报告：org.json 的 optJSONArray 无法区分这两种情况
        if (!args.has("todos") || args.isNull("todos")) {
            return ToolResult.Error("[E_INVALID] 缺少 todos 参数：请把完整清单放进 todos 数组，清空时传空数组。")
        }
        val array = args.optJSONArray("todos")
            ?: return ToolResult.Error(
                "[E_INVALID] todos 必须是数组，收到的是：" + args.opt("todos").toString().take(120) + "；请改为数组后重试。"
            )

        // 逐条校验：content 去空白后必须非空，status 必须是三种合法值之一。
        // 任一条不合格即整表拒绝，与官方 todo 工具一致；静默修正会让模型误以为写入成功。
        val parsed = ArrayList<Todo>(array.length())
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: return ToolResult.Error(
                    "[E_INVALID] todos 第 " + (i + 1) + " 项不是对象；请把每项写成含 content 与 status 的对象。"
                )
            val content = obj.optString("content", "").trim()
            if (content.isEmpty() || content == "null") {
                return ToolResult.Error("[E_INVALID] `content` 不能为空：请为第 " + (i + 1) + " 项填写任务内容。")
            }
            if (!seen.add(content)) {
                return ToolResult.Error("[E_INVALID] todos 出现重复内容「" + content + "」；请合并为一条。")
            }
            val status = obj.optString("status", "")
            if (status != TodoStore.PENDING && status != TodoStore.IN_PROGRESS && status != TodoStore.COMPLETED) {
                return ToolResult.Error(
                    "[E_INVALID] `status` 取值「" + status + "」不合法；只能取 pending、in_progress、completed。"
                )
            }
            parsed.add(Todo(content, status))
        }

        val inProgress = parsed.count { it.status == TodoStore.IN_PROGRESS }
        if (inProgress > 1) {
            return ToolResult.Error(
                "[E_INVALID] 同时有 " + inProgress + " 条任务处于 in_progress；整个清单最多一条。"
            )
        }

        // 落盘失败由 TodoStore 自行记录日志（不抛出），此处不再额外包裹 try
        withContext(Dispatchers.IO) { TodoStore.save(ctx.context, ctx.sessionId, parsed) }
        return ToolResult.Ok(summaryText(parsed))
    }

    /** 回执使用中文：逐项报出未开始、进行中、已完成的数量，并提示按清单继续。 */
    private fun summaryText(todos: List<Todo>): String {
        val pending = todos.count { it.status == TodoStore.PENDING }
        val inProgress = todos.count { it.status == TodoStore.IN_PROGRESS }
        val completed = todos.count { it.status == TodoStore.COMPLETED }
        return "已更新任务清单：" + pending + " 条未开始，" + inProgress + " 条进行中，" +
            completed + " 条已完成。按清单继续下一步。"
    }
}
