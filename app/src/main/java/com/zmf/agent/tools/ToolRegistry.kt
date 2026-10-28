package com.zmf.agent.tools

import com.zmf.agent.agent.provider.ToolSpec
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.tools.agent.SendMessageTool
import com.zmf.agent.tools.agent.TaskTool
import com.zmf.agent.tools.file.DeleteFileTool
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
        // 删除单独成工具：它是唯一能拿到「解析后的真实路径」的删除入口，
        // 高危闸门才能对规范化落点做判定（terminal 的 rm 只能看命令文本）
        DeleteFileTool(),
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
 * - 同名工具以先注册者为准：后来者记一条日志后丢弃，不抛异常：插件之间撞名不该把 Agent 弄崩。
 *
 * @param initial 内置工具列表，统一记在 builtin 来源下。
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
     * 发给模型的工具声明：**恒定为全部工具**，不按权限档、也不按计划模式裁剪。
     *
     * 计划模式「只加一个提示词段，从不改请求里的工具目录」，
     * 沙箱策略是**逐调用**携带的，由执行期拒绝，不是让工具从目录里消失。
     * 裁工具有两个实测代价：①工具表变了，整个请求前缀跟着作废，缓存从第一处差异起全废；
     * ②模型会把「工具被藏起来」误报成「我没有这个能力」。
     */
    fun specs(): List<ToolSpec> = snapshot
        // 按工具名排序（code-unit 序，与 locale 无关）。顺序本身不影响能力，
        // 但工具定义是请求前缀的一部分：固定成「名字序」才能保证换设备、换语言、重启后逐字节一致。
        .sortedBy { it.name }
        .map { ToolSpec(it.name, it.description, it.parametersJson) }

    /** 按名字查找工具；不存在返回 null。 */
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
        // 参数先解析：后面两道闸都要看参数（mem / goal 是读写一体的工具）
        val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrElse {
            return ToolResult.Error(
                "参数不是合法 JSON：要求一个完整的 JSON 对象，字符串必须带双引号，数组必须用方括号。" +
                    "示例如下——" +
                    "{\"title\":\"一句话标题\",\"steps\":[\"第一步\",\"第二步\"],\"note\":\"可选的补充\"}。" +
                    "收到的原文（截断）：" + call.arguments.take(200)
            )
        }
        // 必填参数统一校验：直接读工具自己声明的 JSON Schema（tools[].function.parameters.required），
        // 不靠每个工具各写一遍判空：那样漏一处就是静默默认值，而且新增工具容易忘。
        missingRequired(tool, args)?.let { missing ->
            FileLog.log(TAG, "参数缺失 " + call.name + "：" + missing)
            return ToolResult.Error("缺少必填参数「" + missing + "」（" + call.name + " 的参数说明里写了用途，补齐后重试）")
        }
        if (!ctx.permission.atLeast(tool.minPermission)) {
            return ToolResult.Error(
                "当前权限档是「" + ctx.permission.displayName() + "」，不允许使用 " + call.name +
                    "（至少需要「" + tool.minPermission.displayName() + "」）"
            )
        }
        // 两道闸都按**能力**判断，不按工具名维护名单：
        // 名单漏一个，新增的写工具就会在只读档/计划模式下被静默放行。
        val mutating = tool.mutatesWith(args)
        if (mutating && ctx.permission == PermissionLevel.READ_ONLY) {
            FileLog.log(TAG, "只读档拒绝 " + call.name + "（有副作用）")
            return ToolResult.Error(
                "当前是「只读」权限档：不能写文件、不能执行命令，也不能改计划 / 待办 / 记忆 / 目标这类 App 内部状态。" +
                    "需要动手请让用户在侧栏切到「工作区」或「完全」；这一轮只做调研，不要换别的工具绕。"
            )
        }
        // 计划模式：工具目录里仍然看得见写 / 终端 / 系统级工具，但执行期一律拒绝。
        // 文案要说清「为什么被拒 + 怎么解除」，否则模型容易把它当成权限不足去换别的工具绕。
        if (mutating && ctx.planMode && !tool.planModeAllowed(args)) {
            FileLog.log(TAG, "计划模式拒绝 " + call.name + "（有副作用）")
            return ToolResult.Error(
                "当前是计划模式，只做调研：不能写文件、不能执行终端命令、不能做系统级操作，也不改 App 内部状态。" +
                    "要动手请让用户先关掉计划模式；这一轮请把要做的事写成计划，用 write_plan 提交。"
            )
        }
        return try {
            tool.invoke(args, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // 参数问题不要伪装成「执行异常」：告诉它缺什么、改完重试
            ToolResult.Error("参数不对：" + (e.message ?: "缺少必填项") + "（请对照参数说明补齐后重试）")
        } catch (e: Exception) {
            ToolResult.Error(
                "工具执行失败：" + (e.message ?: e.javaClass.simpleName) +
                    "（可以换一种做法，或把卡点与需要什么告诉用户）"
            )
        }
    }

    /**
     * 这次调用会不会产生副作用（写文件 / 跑命令 / 改 App 内部状态）。
     *
     * 给 [com.zmf.agent.agent.loop.AgentLoop] 的落盘检查点用：执行有副作用的工具前先写一份会话，
     * 崩在工具里也能留下「已派发」的证据。参数解析失败时按**有副作用**算：宁可多写一次盘，
     * 也不要漏记一次不可逆操作。
     */
    fun mutates(call: ToolCall): Boolean {
        val tool = find(call.name) ?: return false
        if (!tool.mutates) return false
        val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrElse { return true }
        return tool.mutatesWith(args)
    }

    /** 返回第一个缺失的必填参数名；没有声明 required 或都齐了就返回 null。 */
    private fun missingRequired(tool: Tool, args: JSONObject): String? {
        val required = runCatching {
            JSONObject(tool.parametersJson).optJSONArray("required")
        }.getOrNull() ?: return null
        for (i in 0 until required.length()) {
            val name = required.optString(i, "")
            if (name.isEmpty()) continue
            if (!args.has(name) || args.isNull(name)) return name
        }
        return null
    }

    private companion object {
        /** 日志 tag，和 FileLog 里其他模块区分开。 */
        const val TAG = "tools"

        /** 构造时就有的那批工具记在这个来源名下。 */
        const val BUILTIN_SOURCE = "builtin"

        // 计划模式与只读档的拒绝名单**已删除**：改由 Tool.mutates / mutatesWith 的能力声明驱动，
        // 新增写工具只要声明一次就不会被漏掉（见 ToolRegistry.execute 的两道闸）。
    }
}
