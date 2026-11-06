package com.zmf.agent.core.model

/** 模型发起的一次工具调用：[arguments] 是模型给出的原始 JSON 字符串，由执行层再解析。 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/** 唤醒来源：用户手动发送。 */
const val ORIGIN_USER = "user"

/** 唤醒来源：定时任务在后台代发。 */
const val ORIGIN_SCHEDULE = "schedule"

/**
 * 会话里的一条消息，user / assistant / tool / system 四种角色共用。
 *
 * 字段即落盘格式（[imageDataUrls] 这类请求期数据例外，见各自说明），
 * 各字段的取值口径直接决定请求前缀是否与上一轮逐字节一致。
 */
data class ChatMessage(
    val role: String,                 // user / assistant / tool / system
    val content: String = "",
    val reasoningContent: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    /**
     * 这条消息附带的图片：**工作区相对路径**（如 uploads/img-1700000000000-1.jpg），随会话持久化。
     *
     * 仅存路径而不存图片本身：图片已是工作区中的真实文件，工具（read_file 等）与界面均可按路径读取；
     * 将 base64 写入 sessions.json 会使会话文件增大到数十 MB。
     */
    val images: List<String> = emptyList(),
    /**
     * 请求期临时数据：[images] 对应的 base64 data URL（data:image/jpeg;base64,...）。
     *
     * **不持久化**（SessionStore 只写 [images]）：每次发请求前按路径现读现编码，请求结束即可丢弃。
     * 之所以不直接复用 [images]：模型接口要的是 data URL，而磁盘上只有文件，
     * 编码结果只对「这一次请求」有意义，存下来只会让落盘文件无谓变大。
     */
    val imageDataUrls: List<String> = emptyList(),
    /**
     * 这一轮问上去的输入 tokens。
     *
     * 仅写在**本轮最后一条** assistant 消息上：一轮中每次工具调用后都会再次请求模型，
     * 中间几条 assistant 仅代表某一步，将用量分摊到每条气泡会产生多行显示。
     * 默认 0 表示该消息无用量（历史会话读回时同样为 0），界面据此决定是否显示胶囊标识。
     */
    val promptTokens: Int = 0,
    /** 这一轮收下来的输出 tokens；口径同 [promptTokens]。 */
    val completionTokens: Int = 0,
    /**
     * 这一轮输入里命中提示词缓存的 tokens；口径同 [promptTokens]（只写在最后一条 assistant 上）。
     *
     * 单条消息单独存储一份，用于「本轮用量」面板：面板需拆分为未缓存输入与缓存读取两行，
     * 仅会话级累计值不足；用户关注的是**本轮**花费与命中量。
     */
    val cachedTokens: Int = 0,
    /**
     * 服务端这一次到底报没报缓存字段。
     *
     * false 时面板的「缓存命中」显示「—」：若显示 0%，用户会误以为从未命中缓存，
     * 实际只是该服务商未提供该项。
     */
    val usageCacheReported: Boolean = false,
    /** 未缓存输入（服务商口径已归一化）：面板上「未缓存输入」那一行就用它。 */
    val uncachedInputTokens: Int = 0,
    /** 写入缓存的 tokens；null = 这家服务商没有这一项，面板上不显示这一行。 */
    val cacheWriteTokens: Int? = null,
    /** 输出里的推理 tokens；null = 不报，面板上不显示括号里那句。 */
    val reasoningTokens: Int? = null,
    /**
     * 本轮的「提供方/模型」（形如 `deepseek/deepseek-flash`，用量面板据此显示）。
     *
     * 写入后即固定：此后切换模型，查看历史时面板仍应显示当时的取值。
     */
    val usageModel: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 这条 user 消息是不是 App 代发的。
     *
     * 高危操作的一次性确认仅认可真实用户输入：模型生成的是 assistant 消息，无法伪造 user 消息；
     * 但 App 会以 user 角色代发内容（例如计划页的「按计划开始执行」），
     * 将其与用户手动输入的内容区分后，确认码校验才不会将程序文本视为用户授权。
     * 该标记随消息落盘并按原值读回；定时任务的提问同样是 App 代发，不构成授权。
     */
    val programmatic: Boolean = false,
    /**
     * 唤醒来源：[ORIGIN_USER]（用户手动发送）或 [ORIGIN_SCHEDULE]（定时任务）。
     *
     * 只影响后台统计口径：定时任务与普通任务一样计入轮次，
     * 但 [programmatic] 仍为 true——无人值守时不构成高危操作授权。
     */
    val origin: String = ORIGIN_USER,
    /**
     * 这条 user 消息发出时附带的「运行时快照」正文（权限档 / 工作区 / 计划模式 / 时间）。
     *
     * **不渲染到界面**，但随消息持久化，并在每次组装请求时原样拼回该消息末尾：
     * 目的是使「上一轮实际发送的字节」与「本轮读到的历史前缀」完全一致；
     * DeepSeek 的提示词缓存按前缀完整匹配，相差一个字节即导致整段缓存失效。
     * 历史会话读回为空串，表示无快照，不予拼接。
     */
    val runtimeSnapshot: String = ""
) {
    /**
     * 后台统计口径里的「一轮」：真人输入，以及定时系统唤醒的任务。
     *
     * 「定时任务当普通任务处理、只是唤醒来源不同」——统计上也按同一口径计轮次；
     * 其他 App 代发（计划页按钮、后台任务结算唤醒）仍不算。
     */
    val countsAsRound: Boolean
        get() = role == "user" && (!programmatic || origin == ORIGIN_SCHEDULE)
}

/** 权限档：只读、工作区、完全；高低顺序见 tools 包的 rank()，不应依赖枚举声明顺序。 */
enum class PermissionLevel { READ_ONLY, WORKSPACE, FULL }
