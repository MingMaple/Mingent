package com.zmf.agent.capability.tools

import com.zmf.agent.infra.llm.ToolSpec
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.capability.tools.plugin.ToolPlugin
import com.zmf.agent.capability.tools.plugin.ToolPlugins
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 当前可用的全部工具。
 *
 * 清单由 [ToolPlugins] 提供；本函数只保留为兼容入口，新增工具不应修改此处。
 */
fun defaultTools(): List<Tool> = ToolPlugins.tools()

/**
 * 工具注册表：支持运行期按「来源」动态增删工具，读路径不加锁。
 *
 * - 内置工具一件一个来源，来源名为 `tool:<工具名>`：`unregister("tool:grep")` 只注销 grep，
 *   不影响其他工具；
 * - 写（[register] / [unregister]）获取 [lock]，修改完成后整体重建一份不可变快照 [snapshot]；
 * - 读（[specs] / [find] / [execute]）只读 [snapshot] 这个引用，不在循环里反复 synchronized，
 *   并且一次调用内看到的是同一份列表（不会出现「声明里有、执行时没了」）。
 * - 同名工具以先注册者为准：后注册者记录一条日志后丢弃，不抛异常，避免插件同名导致 Agent 崩溃。
 *
 * @param initial 内置工具列表；每件工具分别登记在 `tool:<工具名>` 来源下。
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
            for (tool in initial) {
                sources[ToolPlugin.SOURCE_PREFIX + tool.name] = mutableListOf(tool)
            }
            snapshot = flattenLocked()
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
        // 参数先解析：其后两道闸均需读取参数（mem / goal 是读写一体的工具）
        val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrElse {
            return ToolResult.Error(
                "参数不是合法 JSON：要求一个完整的 JSON 对象，字符串必须带双引号，数组必须用方括号。" +
                    "示例如下——" +
                    "{\"title\":\"一句话标题\",\"steps\":[\"第一步\",\"第二步\"],\"note\":\"可选的补充\"}。" +
                    "收到的原文（截断）：" + call.arguments.take(200)
            )
        }
        // 必填参数统一校验：直接读工具自己声明的 JSON Schema（tools[].function.parameters.required），
        // 不靠每个工具各写一遍判空：漏一处即产生静默默认值，且新增工具时容易遗漏。
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
        // 两道闸只拦**文件系统与命令**：权限档不再约束计划 / 待办 / 目标 / 记忆这类控制类工具。
        // 判定叠加 mutatesWith：apply_patch dry_run 不写盘，不会被当成敏感操作。
        val sandboxed = tool.sandboxSensitive && tool.mutatesWith(args)
        if (sandboxed && ctx.permission == PermissionLevel.READ_ONLY) {
            FileLog.log(TAG, "只读档拒绝 " + call.name + "（受文件/命令约束）")
            return ToolResult.Error(
                "当前是「只读」权限档：权限档只约束文件系统写入与命令执行，不能写文件、不能执行命令，也不能做系统级操作。" +
                    "需要动手请让用户在侧栏切到「工作区」或「完全」；计划、待办、目标、记忆等控制类工具不受此限制，不必绕路。"
            )
        }
        // 计划模式：工具目录里仍然看得见写 / 终端 / 系统级工具，但执行期一律拒绝。
        // 文案要说明「为何被拒 + 如何解除」，否则模型容易将其理解为权限不足，转而用其他工具绕过限制。
        if (sandboxed && ctx.planMode && !tool.planModeAllowed(args)) {
            FileLog.log(TAG, "计划模式拒绝 " + call.name + "（受文件/命令约束）")
            return ToolResult.Error(
                "当前是计划模式：只做调研，不能写文件、不能执行终端命令、不能做系统级操作（计划、待办等控制类工具不受限）。" +
                    "要动手请让用户先关掉计划模式；这一轮请把要做的事写成计划，用 write_plan 提交。"
            )
        }
        return try {
            // 统一总闸：每个工具都在这里拿到一个硬上界，工具自己的时限仍先到（见 Tool.timeoutMs）。
            // 这样「工具有没有自己写超时」不再是行为差异：未声明时限的工具最多占用 30 分钟，
            // 不会让一整轮永久挂住；取消（用户点停止）照常向上传播，不被这里吞掉。
            val limit = tool.timeoutMs
            val result = if (limit <= 0L) {
                tool.invoke(args, ctx)
            } else {
                withTimeoutOrNull(limit + TOOL_EXEC_TIMEOUT_GRACE_MS) { tool.invoke(args, ctx) }
                    ?: run {
                        FileLog.log(TAG, "工具超时 " + call.name + "（上限 " + limit + "ms）")
                        ToolResult.Error(
                            "工具执行超时：超过 " + (limit / 1000L) + " 秒仍未结束，已中止本次调用。" +
                                "可以缩小任务范围后重试，或把卡点告诉用户。"
                        )
                    }
            }
            result
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
     * 这次调用能否与同一步内其他并发安全调用重叠执行。
     *
     * 两道条件：工具自己声明 [Tool.concurrencySafe]，且本次调用不产生副作用。
     * 后者是硬门槛——检查点要求「执行有副作用的工具之前落盘」，若允许并行，
     * 落盘顺序与执行顺序将无法对应；因此即使某工具误标并发安全，只要本次会写盘仍按独占处理。
     */
    fun parallelSafe(call: ToolCall): Boolean {
        val tool = find(call.name) ?: return false
        return tool.concurrencySafe && !mutates(call)
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

        // 权限档只约束文件与命令：由 Tool.sandboxSensitive 分类，控制类工具显式声明为 false。
        // mutates 仍用于落盘检查点，不再直接驱动这两道闸。
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
        "npm install →「安装依赖包」。不得照抄命令原文，也不要写成「执行命令」这类泛泛描述。"

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
    // 重建属性表：说明置于首位，原有属性按原序追加，相对次序不变
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
