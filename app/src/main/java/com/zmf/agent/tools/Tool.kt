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

