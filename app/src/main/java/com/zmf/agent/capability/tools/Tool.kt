package com.zmf.agent.capability.tools

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
 * 权限档的高低比较：显式定义 rank，不依赖枚举声明顺序。
 *
 * 若使用 ordinal，往枚举中插入新档位（如「只读但可执行」）会导致序号静默错位，
 * 错误只表现为权限判断异常，难以排查。
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
 * workspaceRoot 由调用方（ChatViewModel）按当前工作区传入，而非固定等于默认工作区目录，
 * 这样文件工具的相对路径才会跟随工作区变化。
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
     * 本次调用是否来自子智能体。
     *
     * 子智能体没有可与用户交互的界面（提问卡、通知都落在父会话页上），
     * 依赖用户当场作答的工具必须据此拒绝，而不是猜测会话 id。
     * 由子智能体执行路径显式置 true，其余调用方保持 false。
     */
    val isSubAgent: Boolean = false,
    /**
     * 本次调用的执行者标识：子智能体为其面板记录 id，主会话与定时任务为空串。
     *
     * 终端据此把子智能体的命令分流到独立执行通道（对齐 DSH「每个 owner 一个 shell」）：
     * 同一子智能体内部仍串行，不同子智能体互不排队。只由子智能体执行路径填写。
     */
    val agentId: String = "",
    /**
     * 会话里**最近一条真实 user 消息**的惰性读取器。
     *
     * 必须是惰性 lambda，不能是快照：ToolContext 在 AgentLoop 追加新 user 消息**之前**构造，
     * 直接传值会永远拿到上一条。高危确认只认这条消息（且 [ChatMessage.programmatic] 必须为 false），
     * 模型自己生成的 assistant 内容完全不参与判定。
     *
     * 默认返回 null：子智能体 / 定时任务没有「用户当场授权」这条通道，
     * 需要确认的高危操作在这类调用方中一律被拒绝（确定性优先）。
     */
    val lastUserMessage: () -> ChatMessage? = { null },
    /**
     * 工具自行发起的旁路请求用量回调（目前只有联网检索）。
     *
     * 这类请求与对话回合走同一条计费通道，却不属于本轮上下文：
     * 上报后只进会话累计与总账，不参与本轮请求的输入构成。
     * 默认空实现：子智能体等没有计费归属的调用方不必实现。
     */
    val onAuxUsage: (AuxUsage) -> Unit = {}
) {
    init {
        // 高危闸门要判「App 核心数据目录 / 存储根 / PRoot 根」，这些路径只能从 Context 推出来。
        // 同样每次工具调用刷新一次，避免会话切换工作区后仍用旧快照。
        HighRiskGate.bind(context)
    }
}

/**
 * 工具执行结果。
 * 失败不是异常：错误文本会作为 tool 消息回给模型，由模型自行调整参数后重试，
 * 避免「连续 5 轮工具调用」因一次越界而中断。
 */
sealed interface ToolResult {
    /** 成功与否，等价于是否为 [Ok]。 */
    val ok: Boolean
    /** 回给模型的文本：成功是结果正文，失败是失败原因。 */
    val text: String

    data class Ok(
        override val text: String,
        /**
         * 工具向模型回传的图片，形如 `data:image/png;base64,...`。
         *
         * 仅对当次请求有效，**不得**写入会话文件：图片本身已存在于工作区，落盘只会使会话体积膨胀。
         * 为空表示本次结果不附带图片。
         */
        val imageDataUrls: List<String> = emptyList(),
        /**
         * [imageDataUrls] 对应的图片路径，通常为工作区相对路径。
         *
         * 供模型与界面按路径回溯原图；**不得**用于替代 [imageDataUrls]，模型接口只接受后者。
         */
        val imagePaths: List<String> = emptyList()
    ) : ToolResult {
        override val ok: Boolean get() = true
    }

    data class Error(override val text: String) : ToolResult {
        override val ok: Boolean get() = false
    }
}

/**
 * 工具自行发起的旁路请求用量（目前只有联网检索）。
 *
 * 检索端点每条 query 就是一次真实的模型请求，服务端同样按 token 与成功请求次数计费；
 * 这些数字不在对话回合的 usage 里，必须由工具自己上报。
 */
data class AuxUsage(
    /** 计费用模型名，与对话页同一拼法（`provider/model`）。 */
    val model: String,
    /** 本批旁路请求数（一条 query 一次请求）。 */
    val modelRequests: Int,
    /** 输入总量：未命中 + 命中 + 写入。 */
    val promptTokens: Int,
    val uncachedInputTokens: Int,
    val cachedTokens: Int,
    /** 缓存写入 tokens；服务端未上报时恒为 0。 */
    val cacheWriteTokens: Int,
    /** 服务端是否上报了缓存读取字段；未上报时缓存按 0 计。 */
    val cacheReadReported: Boolean,
    val completionTokens: Int,
    /** 服务端上报的检索次数，按次计费。 */
    val searchRequests: Int
)

/**
 * 每个工具声明里都会追加的公共参数名：这一步在做什么。
 *
 * 与 DSH 的 `bash` 工具同名同义（其参数说明为「5–10 个词的主动语态短语，界面直接显示」），
 * 声明由 [com.zmf.agent.capability.tools.ToolRegistry.specs] 统一注入，工具实现自行忽略；
 * 取值只服务界面（工具卡与轨迹行标题），不参与执行。
 */
const val STEP_DESCRIPTION_PARAM = "description"

/**
 * 旧字段名：2026-11-10 之前注入的是这个键，历史会话里仍有。
 *
 * 只用于读取，不再写入声明；删除它会使旧会话的工具行退回「运行命令 …」一类的原文摘要。
 */
const val LEGACY_ACTION_SUMMARY_PARAM = "action_summary"

/** 一个可被模型调用的工具。 */
interface Tool {
    /** 工具名，即请求里 tools[].function.name；同名时以先注册者为准（见 [ToolRegistry]）。 */
    val name: String

    /** 给模型看的用途说明，随工具声明一起发出去。 */
    val description: String

    /** JSON Schema 字符串，直接写入 tools[].function.parameters。 */
    val parametersJson: String

    /** 使用本工具要求的最低权限档。 */
    val minPermission: PermissionLevel

    /**
     * 这个工具**是否可能**产生持久副作用：写文件、执行命令、改 App 自己的状态。
     *
     * 用途：给 AgentLoop 的落盘检查点用（执行有副作用的工具前先写一份会话）。
     * 权限档不再按它判定，见 [sandboxSensitive]。
     */
    val mutates: Boolean get() = false

    /**
     * 给定参数，**这一次**调用是否会产生副作用。
     *
     * 读写一体的工具（mem、goal）覆盖它：`mem recall` 是只读，`mem add` 是写。默认等于 [mutates]。
     */
    fun mutatesWith(args: JSONObject): Boolean = mutates

    /**
     * 是否允许与**同一步**内其他并发安全工具重叠执行。
     *
     * 默认 false：独占调用，构成屏障（对齐 DSH 的 `isConcurrencySafe` 缺省口径）。
     * 只有既不写父会话、也不共享有状态资源（终端通道、浏览器标签、审批面板）的工具才可置 true；
     * 子智能体类工具的全部副作用发生在各自独立的子循环里，因此是当前唯一符合条件的工具组。
     */
    val concurrencySafe: Boolean get() = false

    /**
     * 是否受文件系统与命令的沙箱约束。
     *
     * 用户的权限档（只读 / 工作区 / 完全）**只约束写文件与执行命令**；计划、待办、目标、记忆、
     * 定时任务这类「控制类」工具在只读档照常可用，因此它们必须覆盖为 `false`。
     *
     * 默认与 [mutates] 一致，避免新增的写工具因漏标而被只读档静默放行；
     * 判定时还会叠加 [mutatesWith]，`apply_patch dry_run` 这类只读用法不会被误拦。
     */
    val sandboxSensitive: Boolean get() = mutates

    /**
     * 计划模式下是否**放行**这次调用。
     *
     * 默认 false（有副作用的一律拒绝）。唯一例外是 `write_plan`：交计划本身就是计划模式
     * 唯一的正规产出，它同样常驻在工具目录里、由执行期放行。
     */
    fun planModeAllowed(args: JSONObject): Boolean = false

    /** 执行一次。失败一律折算成 [ToolResult.Error]，只有协程取消可以向外抛。 */
    suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult

    /**
     * 本工具单次调用的时间上界（毫秒）；0 表示不限。
     *
     * 执行入口（[ToolRegistry.execute]）按它施加一层总闸，见 [TOOL_EXEC_TIMEOUT_GRACE_MS]。
     * 工具自带时限的（终端、提问）覆盖此项：自带时限先到，总闸只兜「工具没有任何时限且永久挂起」。
     * 需要用户当场作答的工具必须覆盖为 0，否则会被总闸判成超时。
     */
    val timeoutMs: Long get() = DEFAULT_TOOL_TIMEOUT_MS
}

/**
 * 工具执行的总闸默认值：30 分钟。
 *
 * 取这么大的理由：它是**兜底**而不是时限本身。各工具自己的时限（终端默认 60 秒、最长 10 分钟）
 * 都比它短，因此正常路径永远由工具自己先结束，两层不会互相抢答。
 */
const val DEFAULT_TOOL_TIMEOUT_MS = 30 * 60 * 1000L

/** 总闸在工具声明值之外多给的余量：让工具自己的时限先到，避免两层计时互相打断。 */
const val TOOL_EXEC_TIMEOUT_GRACE_MS = 5_000L
