package com.zmf.agent.capability.tools

import com.zmf.agent.infra.llm.ToolSpec
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.capability.tools.agent.SendMessageTool
import com.zmf.agent.capability.tools.agent.TaskTool
import com.zmf.agent.capability.tools.file.DeleteFileTool
import com.zmf.agent.capability.tools.file.ListFilesTool
import com.zmf.agent.capability.tools.file.PresentTool
import com.zmf.agent.capability.tools.file.ReadFileTool
import com.zmf.agent.capability.tools.goal.GoalTool
import com.zmf.agent.capability.tools.memory.MemTool
import com.zmf.agent.capability.tools.plan.PlanTool
import com.zmf.agent.capability.tools.file.WriteFileTool
import com.zmf.agent.capability.tools.jobs.JobKillTool
import com.zmf.agent.capability.tools.jobs.JobListTool
import com.zmf.agent.capability.tools.jobs.JobOutputTool
import com.zmf.agent.capability.tools.jobs.JobStartTool
import com.zmf.agent.capability.tools.schedule.ScheduleCreateTool
import com.zmf.agent.capability.tools.schedule.ScheduleDeleteTool
import com.zmf.agent.capability.tools.schedule.ScheduleListTool
import com.zmf.agent.capability.tools.skill.SkillSaveTool
import com.zmf.agent.capability.tools.skill.SkillTool
import com.zmf.agent.capability.tools.system.ShizukuTool
import com.zmf.agent.capability.tools.task.TodoTool
import com.zmf.agent.capability.tools.terminal.TerminalTool
import com.zmf.agent.capability.tools.session.SessionArchiveTool
import com.zmf.agent.capability.tools.session.SessionCreateTool
import com.zmf.agent.capability.tools.session.SessionDeleteTool
import com.zmf.agent.capability.tools.session.SessionListTool
import com.zmf.agent.capability.tools.session.SessionMoveTool
import com.zmf.agent.capability.tools.session.SessionRenameTool
import com.zmf.agent.capability.tools.session.SessionRestoreTool
import com.zmf.agent.capability.tools.session.SessionUpdateSettingsTool
import com.zmf.agent.capability.tools.workspace.WorkspaceCreateTool
import com.zmf.agent.capability.tools.workspace.WorkspaceDeleteTool
import com.zmf.agent.capability.tools.workspace.WorkspaceListTool
import com.zmf.agent.capability.tools.workspace.WorkspaceRenameTool
import com.zmf.agent.capability.tools.workspace.WorkspaceSetRootTool
import com.zmf.agent.capability.tools.web.WebSearchTool
import com.zmf.agent.capability.tools.web.WebTool
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * 当前可用的全部工具：文件工具、App 内 Ubuntu 的 terminal、网页抓取 web_fetch、Skill 加载 skill、
 * 联网检索 web_search、任务清单 todo_write、子智能体 subagent、子智能体续聊 send_message、
 * 记忆内核 mem、计划提交 write_plan、长期目标 goal、定时任务 schedule_create / schedule_list / schedule_delete。
 *
 * subagent 依赖 [com.zmf.agent.capability.subagent.SubAgentDeps] 里注入的 provider 与工具表：
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
        // 显式交付：PDF / Office / 图片这类 write_file 写不出的文件，靠它在回合末尾挂卡片
        PresentTool(),
        TerminalTool(),
        WebTool(),
        SkillTool(),
        // 写入侧单独成工具：skill 保持只读，避免「加载正文」这种高频动作被权限档连带收紧
        SkillSaveTool(),
        ShizukuTool(),
        WebSearchTool(),
        TodoTool(),
        TaskTool(),
        SendMessageTool(),
        MemTool(),
        // 计划与目标：都是只读档工具（各自只写 appdata 里自己的 JSON），
        // 所以计划模式（只暴露 READ_ONLY）下 write_plan 仍然可见
        PlanTool(),
        GoalTool(),
        // 后台任务：job_start 起长任务，job_output/job_list/job_kill 读、列、取消
        JobStartTool(),
        JobOutputTool(),
        JobListTool(),
        JobKillTool(),
        // 工作区与会话：只读写 appdata 里自己的工作区／会话文件，
        // 因此最低权限为只读档，写操作由 mutates 在执行期拦截
        WorkspaceListTool(),
        WorkspaceCreateTool(),
        WorkspaceRenameTool(),
        WorkspaceSetRootTool(),
        WorkspaceDeleteTool(),
        SessionListTool(),
        SessionCreateTool(),
        SessionRenameTool(),
        SessionMoveTool(),
        SessionArchiveTool(),
        SessionRestoreTool(),
        SessionDeleteTool(),
        SessionUpdateSettingsTool(),
        // 定时任务：创建、列出、删除；数据与闹钟经 ScheduleBridge 交给 feature 域
        ScheduleCreateTool(),
        ScheduleListTool(),
        ScheduleDeleteTool()
    )

/**
 * 工具注册表：支持运行期按「来源」动态增删工具，读路径不加锁。
 *
 * - 写（[register] / [unregister]）获取 [lock]，修改完成后整体重建一份不可变快照 [snapshot]；
 * - 读（[specs] / [find] / [execute]）只读 [snapshot] 这个引用，不在循环里反复 synchronized，
 *   并且一次调用内看到的是同一份列表（不会出现「声明里有、执行时没了」）。
 * - 同名工具以先注册者为准：后注册者记录一条日志后丢弃，不抛异常，避免插件同名导致 Agent 崩溃。
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

    /**
     * 请求里下发的工具声明。与 [snapshot] 同时重建：
     * 声明要经过一次 JSON 加工（追加公共参数），不宜每次取用都重算。
     */
    @Volatile
    private var specSnapshot: List<ToolSpec> = emptyList()

    init {
        synchronized(lock) {
            sources[BUILTIN_SOURCE] = initial.toMutableList()
            snapshot = initial.toList()
            specSnapshot = buildSpecs(snapshot)
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
     * 裁工具有两个实测代价：①工具表变化后，整个请求前缀随之失效，缓存从第一处差异起全部失效；
     * ②模型会把「工具被藏起来」误报成「我没有这个能力」。
     */
    fun specs(): List<ToolSpec> = specSnapshot

    /**
     * 由工具列表生成声明。
     *
     * 顺序按工具名排序（code-unit 序，与 locale 无关）。顺序本身不影响能力，
     * 但工具定义是请求前缀的一部分：固定成「名字序」才能保证换设备、换语言、重启后逐字节一致。
     */
    private fun buildSpecs(tools: List<Tool>): List<ToolSpec> =
        tools.sortedBy { it.name }
            .map { ToolSpec(it.name, it.description, it.parametersJson.withStepDescription(it.name)) }

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
            // 当前已占用的名称（含本来源此前登记的），先注册者优先
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
            specSnapshot = buildSpecs(snapshot)
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

    /** 摘掉某个来源登记过的全部工具（来源不存在则忽略，不抛异常）。 */
    fun unregister(source: String) {
        var removed: List<Tool>? = null
        synchronized(lock) {
            val bucket = sources.remove(source)
            if (bucket != null) {
                removed = bucket
                snapshot = flattenLocked()
            specSnapshot = buildSpecs(snapshot)
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
            // 参数问题不应伪装成「执行异常」：明确指出缺失项，供模型补齐后重试
            ToolResult.Error("参数不对：" + (e.message ?: "缺少必填项") + "（请对照参数说明补齐后重试）")
        } catch (e: Exception) {
            ToolResult.Error(
                "工具执行失败：" + (e.message ?: e.javaClass.simpleName) +
                    "（可以换一种做法，或把卡点与需要什么告诉用户）"
            )
        }
    }

    /**
     * 这次调用会不会产生副作用（写文件、执行命令、修改 App 内部状态）。
     *
     * 给 [com.zmf.agent.capability.loop.AgentLoop] 的落盘检查点用：执行有副作用的工具前先写一份会话，
     * 即使在工具内崩溃，也能留下「已派发」的证据。参数解析失败时按**有副作用**处理：宁可多写一次磁盘，
     * 也不遗漏一次不可逆操作。
     */
    fun mutates(call: ToolCall): Boolean {
        val tool = find(call.name) ?: return false
        if (!tool.mutates) return false
        val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrElse { return true }
        return tool.mutatesWith(args)
    }

    /**
     * 返回第一个缺失的必填参数名；没有声明 required 或都齐了就返回 null。
     *
     * 执行类工具的 `description` 也在此校验：它由 [buildSpecs] 注入而非各工具自己声明，
     * 只有在这里补一条，模型漏写时才会得到「缺少必填参数」的明确回执并重试；
     * 不校验的话，漏写的调用会照常执行，界面只能退回显示命令原文。
     */
    private fun missingRequired(tool: Tool, args: JSONObject): String? {
        val required = runCatching {
            JSONObject(tool.parametersJson).optJSONArray("required")
        }.getOrNull()
        if (required != null) {
            for (i in 0 until required.length()) {
                val name = required.optString(i, "")
                if (name.isEmpty()) continue
                if (!args.has(name) || args.isNull(name)) return name
            }
        }
        if (tool.name in STEP_DESCRIPTION_REQUIRED) {
            val description = args.optString(STEP_DESCRIPTION_PARAM, "").trim()
            if (description.isEmpty()) return STEP_DESCRIPTION_PARAM
        }
        return null
    }

    private companion object {
        /** 日志 tag，和 FileLog 里其他模块区分开。 */
        const val TAG = "tools"

        /** 构造时就有的那批工具记在这个来源名下。 */
        const val BUILTIN_SOURCE = "builtin"

        // 计划模式与只读档不维护工具拒绝名单：改由 Tool.mutates / mutatesWith 的能力声明驱动，
        // 新增写工具只需声明一次即可被识别（见 ToolRegistry.execute 的两道闸）。
    }
}

/**
 * 执行类工具：它们的参数里没有可读的宾语（只有一长串命令），工具行标题只能靠模型给的说明。
 * 这几个工具的 `description` 声明为**必填**，与 DSH 的 `bash` 工具一致。
 */
private val STEP_DESCRIPTION_REQUIRED = setOf("terminal", "job_start", "shizuku")

/**
 * [STEP_DESCRIPTION_PARAM] 的参数说明。
 *
 * 措辞对齐 DSH 的 `bash` 工具（「5–10 个词的主动语态短语，界面直接显示」），并给出命令到短语的
 * 对照示例——只写「简明说明」这类要求时，模型会照抄命令。
 */
private const val STEP_DESCRIPTION_HINT =
    "这一步在做什么：用 5–10 个词的主动语态短语，界面直接把它当作该步的标题。" +
        "示例：ls →「列出当前目录的文件」；git status →「查看工作区状态」；" +
        "npm install →「安装依赖包」。不得照抄命令原文，也不要写成「执行命令」这类空话。"

/**
 * 在工具的参数声明里注入 [STEP_DESCRIPTION_PARAM]，由全表统一注入，各工具不必逐一声明。
 *
 * **置于属性表首位**：DSH 的 `bash` 同样把 `description` 排在 `command` 之前，其文档写明
 * 「schema 把它列在 command 前、请模型先输出它，但不强制 JSON 成员顺序」。模型按声明顺序
 * 生成参数，落在末尾的字段更容易整段漏掉——这正是此前工具行退化成命令原文的原因之一。
 *
 * 执行类工具（见 [STEP_DESCRIPTION_REQUIRED]）把它列入 `required`：模型漏写时按参数缺失处理，
 * 补一次再调，换来的是工具行能读出「这一步做了什么」。其余工具的标题本就能从路径等参数推出，
 * 故只加属性、不强制，避免多付一次重试。
 *
 * 声明解析失败时原样返回：宁可少一个字段，也不能让整张工具表发不出去。
 */
private fun String.withStepDescription(toolName: String): String {
    val root = runCatching { JSONObject(this) }.getOrNull() ?: return this
    val properties = root.optJSONObject("properties") ?: JSONObject()
    if (properties.has(STEP_DESCRIPTION_PARAM)) return this
    // 重建属性表：说明在前，原有属性按原序跟在后面，相对次序不变
    val reordered = JSONObject()
    reordered.put(
        STEP_DESCRIPTION_PARAM,
        JSONObject()
            .put("type", "string")
            .put("description", STEP_DESCRIPTION_HINT)
    )
    val keys = properties.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        reordered.put(key, properties.opt(key))
    }
    root.put("properties", reordered)
    if (toolName in STEP_DESCRIPTION_REQUIRED) {
        val required = root.optJSONArray("required") ?: JSONArray().also { root.put("required", it) }
        var present = false
        for (i in 0 until required.length()) {
            if (required.optString(i) == STEP_DESCRIPTION_PARAM) present = true
        }
        if (!present) required.put(STEP_DESCRIPTION_PARAM)
    }
    return root.toString()
}
