package com.zmf.agent.tools

import com.zmf.agent.agent.provider.ToolSpec
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.tools.agent.SendMessageTool
import com.zmf.agent.tools.agent.TaskTool
import com.zmf.agent.tools.file.ListFilesTool
import com.zmf.agent.tools.file.ReadFileTool
import com.zmf.agent.tools.goal.GoalTool
import com.zmf.agent.tools.memory.MemTool
import com.zmf.agent.tools.plan.PlanTool
import com.zmf.agent.tools.file.WriteFileTool
import com.zmf.agent.tools.skill.SkillTool
import com.zmf.agent.tools.system.ShizukuTool
import com.zmf.agent.tools.task.TodoTool
import com.zmf.agent.tools.terminal.TerminalTool
import com.zmf.agent.tools.web.WebSearchTool
import com.zmf.agent.tools.web.WebTool
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * 当前可用的全部工具：文件三件 + App 内 Ubuntu 里的 terminal + 抓网页的 web_fetch + 加载 Skill 的 skill
 * + 联网检索的 web_search + 任务清单的 todo_write + 派子智能体的 subagent + 续聊子智能体的
 * send_message + 记忆内核的 mem + 交计划的 write_plan + 长期目标的 goal。
 *
 * subagent 依赖 [com.zmf.agent.agent.subagent.SubAgentDeps] 里注入的 provider 与工具表：
 * 没接线时它只会明确报「子智能体未初始化」，不会假装跑成功。
 */
fun defaultTools(): List<Tool> =
    listOf(
        ReadFileTool(),
        WriteFileTool(),
        ListFilesTool(),
        TerminalTool(),
        WebTool(),
        SkillTool(),
        ShizukuTool(),
        WebSearchTool(),
        TodoTool(),
        TaskTool(),
        SendMessageTool(),
        MemTool(),
        // 计划与目标：都是只读档工具（各自只写 appdata 里自己的 JSON），
        // 所以计划模式（只暴露 READ_ONLY）下 write_plan 仍然可见
        PlanTool(),
        GoalTool()
    )

/**
 * 工具注册表：支持运行期按「来源」动态增删工具，读路径不加锁。
 *
 * - 写（[register] / [unregister]）拿 [lock]，改完整体重建一份不可变快照 [snapshot]；
 * - 读（[specs] / [find] / [execute]）只读 [snapshot] 这个引用，不在循环里反复 synchronized，
 *   并且一次调用内看到的是同一份列表（不会出现「声明里有、执行时没了」）。
 * - 同名工具以先注册者为准：后来者记一条日志后丢弃，不抛异常——插件之间撞名不该把 Agent 弄崩。
 */
class ToolRegistry(initial: List<Tool> = defaultTools()) {

    /** 结构变更（增删来源）都要拿的锁。 */
    private val lock = Any()

    /** 按来源分组的工具；LinkedHashMap 记住注册顺序，也就是同名时谁优先。 */
    private val sources = LinkedHashMap<String, MutableList<Tool>>()

    /** 扁平快照，每次变更整体替换。@Volatile 让其他线程立刻看到新引用。 */
    @Volatile
    private var snapshot: List<Tool> = emptyList()

    init {
        synchronized(lock) {
            sources[BUILTIN_SOURCE] = initial.toMutableList()
            snapshot = initial.toList()
        }
        if (initial.isNotEmpty()) {
            FileLog.log(TAG, "内置工具 " + initial.size + " 个：" + initial.joinToString(", ") { it.name })
        }
    }

    /**
     * 发给模型的工具声明：只暴露当前权限档够得着的工具。
     * [readOnlyOnly] = 计划模式：再往下砍一层，只留只读工具，模型连「写」的能力都看不到。
     */
    fun specs(permission: PermissionLevel, readOnlyOnly: Boolean = false): List<ToolSpec> {
        val current = snapshot
        val result = ArrayList<ToolSpec>(current.size)
        for (tool in current) {
            if (!permission.atLeast(tool.minPermission)) continue
            if (readOnlyOnly && tool.minPermission != PermissionLevel.READ_ONLY) continue
            result.add(ToolSpec(tool.name, tool.description, tool.parametersJson))
        }
        return result
    }

    fun find(name: String): Tool? = snapshot.firstOrNull { it.name == name }

    /**
     * 登记一个来源的工具。同一个来源可以多次调用（后续调用只补新工具，不会顶掉已有同名工具）。
     * 同名工具已被别的来源占用时：记日志、丢弃、不抛异常。
     */
    fun register(source: String, tools: List<Tool>) {
        if (tools.isEmpty()) return
        val added = ArrayList<String>()
        val skipped = ArrayList<String>()
        synchronized(lock) {
            val bucket = sources.getOrPut(source) { ArrayList() }
            // 当前已经占用的名字（含本来源以前登记的），先注册者优先
            val taken = HashSet<String>()
            for (list in sources.values) for (tool in list) taken.add(tool.name)
            for (tool in tools) {
                if (!taken.add(tool.name)) {
                    skipped.add(tool.name)
                    continue
                }
                bucket.add(tool)
                added.add(tool.name)
            }
            snapshot = flattenLocked()
        }
        if (added.isEmpty() && skipped.isEmpty()) return
        val detail = buildString {
            append("来源「").append(source).append("」")
            if (added.isNotEmpty()) append(" 注册 ").append(added.joinToString(", "))
            if (skipped.isNotEmpty()) {
                if (added.isNotEmpty()) append("；")
                append("忽略同名 ").append(skipped.joinToString(", "))
            }
            append("，当前共 ").append(snapshot.size).append(" 个工具")
        }
        FileLog.log(TAG, detail)
    }

    /** 摘掉某个来源登记过的全部工具（来源不存在就当没这回事，不抛异常）。 */
    fun unregister(source: String) {
        var removed: List<Tool>? = null
        synchronized(lock) {
            val bucket = sources.remove(source)
            if (bucket != null) {
                removed = bucket
                snapshot = flattenLocked()
            }
        }
        val list = removed ?: return
        FileLog.log(
            TAG,
            "来源「" + source + "」注销 " + list.joinToString(", ") { it.name } +
                "，当前共 " + snapshot.size + " 个工具"
        )
    }

    /** 重建快照，调用方必须持有 [lock]。 */
    private fun flattenLocked(): List<Tool> {
        val merged = ArrayList<Tool>()
        for (list in sources.values) merged.addAll(list)
        return merged.toList()
    }

    /**
     * 执行一次工具调用。任何失败都折算成 ToolResult.Error 交给模型，不往上抛，
     * 只有协程取消要原样抛出（否则「停止」会失效）。
     */
    suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val tool = find(call.name) ?: return ToolResult.Error("没有这个工具：" + call.name)
        if (!ctx.permission.atLeast(tool.minPermission)) {
            return ToolResult.Error(
                "当前权限档是「" + ctx.permission.displayName() + "」，不允许使用 " + call.name +
                    "（至少需要「" + tool.minPermission.displayName() + "」）"
            )
        }
        val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrElse {
            return ToolResult.Error("参数不是合法 JSON：" + call.arguments.take(200))
        }
        return try {
            tool.invoke(args, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Error("工具执行异常：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    private companion object {
        /** 日志 tag，和 FileLog 里其他模块区分开。 */
        const val TAG = "tools"

        /** 构造时就有的那批工具记在这个来源名下。 */
        const val BUILTIN_SOURCE = "builtin"
    }
}
