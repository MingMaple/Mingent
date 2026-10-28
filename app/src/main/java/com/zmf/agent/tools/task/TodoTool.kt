package com.zmf.agent.tools.task

import com.zmf.agent.agent.session.Todo
import com.zmf.agent.agent.session.TodoStore
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * todo_write 工具：整表替换当前会话的待办清单。
 *
 * 为什么是「整表替换」而不是 add/update/remove 三件套：
 * 模型每次都能看到自己上一版的清单，直接交一份新的更简单，也不会出现
 * 「删了一半、改到一半」的中间态；持久化那边也就一次覆盖写。
 *
 * 只写自己的 appdata/todos.json，不碰工作区文件，所以权限档是 READ_ONLY
 * ：只读档下模型也该能整理自己的待办，不能因为「写清单」被拦。
 */
class TodoTool : Tool {

    override val name = "todo_write"

    /** 待办写的是 App 自己的状态，只读档下同样拒绝。 */
    override val mutates = true

    override val description =
        "创建或更新本次会话的任务清单：每次调用都用传入的 todos 整表替换旧清单，未列出的任务会被丢弃。" +
            "清单同时会显示给用户，做多步任务时用它让用户看到进度。" +
            "两三步能做完的事不要建清单：清单是给自己防遗漏的，不是进度汇报。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "todos": {
              "type": "array",
              "description": "任务清单的完整内容（整表替换，不是增量）；传空数组表示清空清单",
              "items": {
                "type": "object",
                "properties": {
                  "content": {"type": "string", "description": "任务内容，不能为空"},
                  "status": {
                    "type": "string",
                    "enum": ["pending", "in_progress", "completed"],
                    "description": "pending = 未开始，in_progress = 正在做（同时最多一条），completed = 已完成"
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
        // 缺参数和「显式 null」要分开报：org.json 的 optJSONArray 分不出这两种情况
        if (!args.has("todos") || args.isNull("todos")) {
            return ToolResult.Error("缺少 todos 参数：请把完整清单放进 todos 数组（清空就传空数组）")
        }
        val array = args.optJSONArray("todos")
            ?: return ToolResult.Error("todos 必须是数组，收到的是：" + args.opt("todos").toString().take(120))

        // 逐条校验：content trim 后必须非空，status 收敛成三种合法值。
        // 只要有一条不合格就整表拒绝：模型交上来的是一份完整清单，悄悄吞掉一条
        // 会让它以为写成功了，下一次读回来却少一项，比直接报错更难查。
        val parsed = ArrayList<Todo>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: return ToolResult.Error("第 " + (i + 1) + " 条不是 {\"content\":...,\"status\":...} 对象")
            val content = obj.optString("content", "").trim()
            if (content.isEmpty() || content == "null") {
                return ToolResult.Error("第 " + (i + 1) + " 条的 content 为空；每条待办都要有内容")
            }
            parsed.add(Todo(content, TodoStore.normalizeStatus(obj.optString("status", ""))))
        }

        val todos = tidy(parsed)
        // 落盘失败由 TodoStore 自己记日志兜住（不抛），这里不再套一层 try
        withContext(Dispatchers.IO) { TodoStore.save(ctx.context, ctx.sessionId, todos) }
        return ToolResult.Ok(summaryText(todos))
    }

    /**
     * 收尾：内容去重 + 同时最多一条 in_progress。
     *
     * 同名任务对用户就是同一个勾选框，留最先出现的那条；
     * 多条 in_progress 会让「当前正在做什么」无法判断，保留第一条、其余降级成 pending
     * （降级而不是报错：模型只是没整理好，没必要让它重写整张表）。
     */
    private fun tidy(todos: List<Todo>): List<Todo> {
        val seen = HashSet<String>()
        val out = ArrayList<Todo>(todos.size)
        var inProgressSeen = 0
        for (todo in todos) {
            if (!seen.add(todo.content)) {
                FileLog.log(TAG, "待办内容重复，丢弃后出现的那条：" + todo.content.take(80))
                continue
            }
            if (todo.status == TodoStore.IN_PROGRESS && ++inProgressSeen > 1) {
                FileLog.log(TAG, "同时有多条 in_progress，多余的降级为 pending：" + todo.content.take(80))
                out.add(Todo(todo.content, TodoStore.PENDING))
                continue
            }
            out.add(todo)
        }
        return out
    }

    /** 回给模型的文本用英文：与主流 todo 工具的输出格式对得上。 */
    private fun summaryText(todos: List<Todo>): String {
        val pending = todos.count { it.status == TodoStore.PENDING }
        val inProgress = todos.count { it.status == TodoStore.IN_PROGRESS }
        val completed = todos.count { it.status == TodoStore.COMPLETED }
        return "Updated todo list: " + pending + " pending, " + inProgress + " in progress, " + completed + " completed."
    }

    private companion object {
        const val TAG = "todo"
    }
}
