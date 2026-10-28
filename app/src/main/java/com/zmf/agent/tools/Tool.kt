package com.zmf.agent.tools

import android.content.Context
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.PermissionLevel
import org.json.JSONObject
import java.io.File

/** 权限档的中文名（用户可见一律中文）。 */
fun PermissionLevel.displayName(): String = when (this) {
    PermissionLevel.READ_ONLY -> "只读"
    PermissionLevel.WORKSPACE -> "工作区"
    PermissionLevel.FULL -> "完全"
}

/**
 * 权限档的高低比较：显式给 rank，不再依赖枚举声明顺序。
 *
 * 用 ordinal 曾经很方便，但以后往枚举里插一档（比如「只读但可执行」）就会静默错位，
 * 而这类错误只会表现为「某个操作突然被允许/被拒绝」，极难查。
 */
fun PermissionLevel.rank(): Int = when (this) {
    PermissionLevel.READ_ONLY -> 0
    PermissionLevel.WORKSPACE -> 1
    PermissionLevel.FULL -> 2
}

/** 工具的下限权限判断（见 [rank]）。 */
fun PermissionLevel.atLeast(other: PermissionLevel): Boolean = rank() >= other.rank()

/**
 * 工具执行上下文：工作区根、当前权限档、应用 Context。
 *
 * workspaceRoot 由调用方（ChatViewModel）按当前工作区传入，
 * 不再是「永远等于默认工作区目录」，这样文件工具的相对路径才会跟着工作区走。
 */
data class ToolContext(
    val context: Context,
    val permission: PermissionLevel,
    val workspaceRoot: File,
    /** 当前会话 id：超大输出落盘按会话分目录；空串落到 default。 */
    val sessionId: String = "",
    /**
     * 是否处于计划模式。
     *
     * 工具目录**不因它变化**：进出计划模式只改提示词段；限制在这里逐调用执行，
     * 见 [ToolRegistry.execute] 的闸。
     */
    val planMode: Boolean = false,
    /**
     * 会话里**最近一条真实 user 消息**的惰性读取器。
     *
     * 必须是惰性 lambda，不能是快照：ToolContext 在 AgentLoop 追加新 user 消息**之前**构造，
     * 直接传值会永远拿到上一条。高危确认只认这条消息（且 [ChatMessage.programmatic] 必须为 false），
     * 模型自己生成的 assistant 内容完全不参与判定。
     *
     * 默认返回 null：子智能体 / 定时任务没有「用户当场授权」这条通道，
     * 需要确认的高危操作在那里一律被拒绝（确定性优先）。
     */
    val lastUserMessage: () -> ChatMessage? = { null }
) {
    init {
        // 没有 context 的 ToolPolicy.checkPath 需要「当前工作区根」当越界比较基准；
        // ToolContext 是唯一保证这个根准确的入口（每次工具调用新建一个），所以在这里顺手刷新快照。
        ToolPolicy.bindWorkspaceRoot(workspaceRoot)
        // 高危闸门要判「App 核心数据目录 / 存储根 / PRoot 根」，这些路径只能从 Context 推出来。
        // 同样每次工具调用刷新一次，避免会话切换工作区后仍用旧快照。
        HighRiskGate.bind(context)
    }

    /** [workspaceRoot] 的别名，留给按旧名字调用的代码。 */
    val workspace: File get() = workspaceRoot
}

/**
 * 工具执行结果。
 * 失败不是异常：错误文本会作为 tool 消息回给模型，让它自己改参数重试，
 * 这样「连续 5 轮工具调用」才不会因为一次越界直接断掉。
 */
sealed interface ToolResult {
    /** 成功与否，等价于「是不是 [Ok]」。 */
    val ok: Boolean
    /** 回给模型的文本：成功是结果正文，失败是失败原因。 */
    val text: String

    data class Ok(override val text: String) : ToolResult {
        override val ok: Boolean get() = true
    }

    data class Error(override val text: String) : ToolResult {
        override val ok: Boolean get() = false
    }
}

/** 一个可被模型调用的工具。 */
interface Tool {
    /** 工具名，即请求里 tools[].function.name；同名时以先注册者为准（见 [ToolRegistry]）。 */
    val name: String

    /** 给模型看的用途说明，随工具声明一起发出去。 */
    val description: String

    /** JSON Schema 字符串，直接塞进 tools[].function.parameters。 */
    val parametersJson: String

    /** 使用本工具要求的最低权限档。 */
    val minPermission: PermissionLevel

    /**
     * 这个工具**是否可能**产生持久副作用：写文件、执行命令、改 App 自己的状态。
     *
     * 用途：只读档与计划模式都按**能力**拒绝，而不是按工具名维护一份名单：
     * 名单漏一个，新增的写工具就会在只读档/计划模式下被静默放行。只读就是只读，
     * 不分 App 内部还是外部。
     */
    val mutates: Boolean get() = false

    /**
     * 给定参数，**这一次**调用是否会产生副作用。
     *
     * 读写一体的工具（mem、goal）覆盖它：`mem recall` 是只读，`mem add` 是写。默认等于 [mutates]。
     */
    fun mutatesWith(args: JSONObject): Boolean = mutates

    /**
     * 计划模式下是否**放行**这次调用。
     *
     * 默认 false（有副作用的一律拒绝）。唯一例外是 `write_plan`：交计划本身就是计划模式
     * 唯一的正规产出，它同样常驻在工具目录里、由执行期放行。
     */
    fun planModeAllowed(args: JSONObject): Boolean = false

    /** 执行一次。失败一律折算成 [ToolResult.Error]，只有协程取消可以向外抛。 */
    suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult
}

/**
 * 路径闸门：把「权限三档」落到文件路径上。
 *
 * - 只读：只能读工作区内的文件；写操作一律拒绝。
 * - 工作区：工作区内可读可写；越界路径拒绝。
 * - 完全：任意路径可读可写。
 *
 * 工作区根由 ToolContext.workspaceRoot 决定（默认工作区仍是 getExternalFilesDir()/workspace，
 * adb 可直接 pull；自建工作区是 externalFilesDir/workspaces/<dirName>），默认根的便捷重载保留。
 */
object PathGate {

    private const val WORKSPACE_DIR = "workspace"

    /** 工作区里给工具放临时文件的子目录名：它是工作区档允许写的第二根。 */
    private const val TMP_DIR = "tmp"

    /** 默认工作区根：externalFilesDir/workspace（外部存储不可用时退回内部 filesDir），不存在就建。 */
    fun workspaceRoot(context: Context): File {
        val base = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir
        val dir = File(base, WORKSPACE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 相对路径接在指定根下；绝对路径按原样。 */
    fun resolve(root: File, rawPath: String): File {
        val trimmed = rawPath.trim()
        require(trimmed.isNotEmpty()) { "路径为空" }
        return if (trimmed.startsWith("/")) File(trimmed) else File(root, trimmed)
    }

    /** 便捷重载：按默认工作区根解析（兼容旧调用）。 */
    fun resolve(context: Context, rawPath: String): File = resolve(workspaceRoot(context), rawPath)

    /** 目标是否在指定根之内（canonical 比较）。 */
    fun isInside(root: File, file: File): Boolean {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val target = runCatching { canonicalOrNearest(file) }.getOrNull() ?: return false
        return target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)
    }

    /**
     * 目标是否在工作区根或 `<workspace>/tmp` 内（canonical 比较）。
     *
     * 现在 tmp 物理上就在工作区根里，单独判一次是让「可写根」与提示词承诺的沙箱范围逐条对齐：
     * 提示词向模型承诺的就是「workspace 与 workspace/tmp 可写」，以后 tmp 真挪出去也不用改调用方。
     * 用户可见的路径闸（ToolPolicy.checkPath）也复用这里，避免两处各写一套越界判断。
     */
    fun isInsideWorkspaceOrTemp(root: File, file: File): Boolean =
        isInside(root, file) || isInside(File(root, TMP_DIR), file)

    /** 便捷重载：按默认工作区根判断（兼容旧调用）。 */
    fun isInsideWorkspace(context: Context, file: File): Boolean = isInside(workspaceRoot(context), file)

    private fun canonicalOrNearest(file: File): File {
        var cursor: File? = file.absoluteFile
        val tail = ArrayList<String>()
        while (cursor != null && !cursor.exists()) {
            cursor.name.takeIf { it.isNotEmpty() }?.let { tail.add(0, it) }
            cursor = cursor.parentFile
        }
        var result = cursor?.canonicalFile ?: file.absoluteFile
        for (name in tail) result = File(result, name)
        return result
    }

    /**
     * 规范化失败（路径非法、权限异常）返回 null。
     *
     * 与 [canonicalOrNearest] 的区别：这里**不**给不存在的路径兜底拼回尾部，
     * 用于「必须确认真实落点」的判定（高危删除）。
     */
    fun canonicalOrNull(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    /**
     * 规范化后的绝对路径；路径还不存在时按「最近存在的祖先 + 剩余段」拼出来。
     *
     * 供「按真实落点判定」的调用方使用（回收站读面封锁）：既能识破软链接，
     * 又不会因为目标刚被删掉就返回 null。
     */
    fun canonicalPathOrNull(file: File): String? =
        runCatching { canonicalOrNearest(file).path }.getOrNull()

    /** 是否符号链接（不跟随）。删除时不进链接内部，只删链接本身。 */
    fun isSymlink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * [file] 相对 [root] 的层级深度：root 本身 0，直接子项 1，依此类推；不在 root 下返回 -1。
     *
     * 用「就近规范化」而不是严格 canonical：删除目标常常还不存在（拼错/刚被删），
     * 这时仍要能按它的意图位置判深度。
     */
    fun depthFrom(root: File, file: File): Int {
        val canonicalRoot = runCatching { canonicalOrNearest(root) }.getOrNull() ?: return -1
        val target = runCatching { canonicalOrNearest(file) }.getOrNull() ?: return -1
        return depthOf(canonicalRoot, target)
    }

    /** 同 [depthFrom]，但两个参数都已是规范化路径（调用方自己 canonical 过时用）。 */
    internal fun depthOf(canonicalRoot: File, target: File): Int {
        if (target.path == canonicalRoot.path) return 0
        val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        if (!target.path.startsWith(prefix)) return -1
        return target.path.removePrefix(prefix).count { it == File.separatorChar } + 1
    }

    /** [maybeAncestor] 是否等于 [file]，或是它的祖先目录（canonical 比较）。 */
    fun isSameOrAncestor(maybeAncestor: File, file: File): Boolean {
        val a = runCatching { canonicalOrNearest(maybeAncestor) }.getOrNull() ?: return false
        val b = runCatching { canonicalOrNearest(file) }.getOrNull() ?: return false
        return b.path == a.path || b.path.startsWith(a.path.trimEnd(File.separatorChar) + File.separator)
    }

    /** 就近规范化的公开入口：高危闸门判「最终落点」时用。 */
    fun canonicalOrNearestOrNull(file: File): File? =
        runCatching { canonicalOrNearest(file) }.getOrNull()

    /** 能否读：回收站永不放行；工作区（含 tmp）内放行；其余只有「完全」档。 */
    fun canRead(ctx: ToolContext, file: File): Boolean {
        // 回收站是 Agent 的黑区（主体分离）：读也不行，否则「删完看一眼再清空」就成了闭环
        if (HighRiskGate.isTrashPath(file.path, ctx.context.packageName)) return false
        if (isInsideWorkspaceOrTemp(ctx.workspaceRoot, file)) return true
        return ctx.permission == PermissionLevel.FULL
    }

    /** 能否写：回收站与只读档永不放行；工作区（含 tmp）内放行；其余只有「完全」档。 */
    fun canWrite(ctx: ToolContext, file: File): Boolean {
        if (HighRiskGate.isTrashPath(file.path, ctx.context.packageName)) return false
        if (ctx.permission == PermissionLevel.READ_ONLY) return false
        if (isInsideWorkspaceOrTemp(ctx.workspaceRoot, file)) return true
        return ctx.permission == PermissionLevel.FULL
    }
}
