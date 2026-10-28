package com.zmf.agent.core.model

/** 模型发起的一次工具调用：[arguments] 是模型给出的原始 JSON 字符串，由执行层再解析。 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

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
     * 只存路径不存图片本身：图片已经是工作区里的真实文件，工具（read_file 等）能按路径再读、
     * 界面也能按路径显示；把 base64 塞进 sessions.json 会把会话文件撑到几十 MB。
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
     * 只写在**本轮最后一条** assistant 消息上：一轮里每次工具调用之后都要再问一次模型，
     * 中间那几条 assistant 只代表某一步，把用量摊在每条气泡上会变成好几行。
     * 默认 0 = 这条消息没有用量（老会话读回来也是 0），界面据此决定显不显示那颗胶囊。
     */
    val promptTokens: Int = 0,
    /** 这一轮收下来的输出 tokens；口径同 [promptTokens]。 */
    val completionTokens: Int = 0,
    /**
     * 这一轮输入里命中提示词缓存的 tokens；口径同 [promptTokens]（只写在最后一条 assistant 上）。
     *
     * 单条消息自己存一份，是为了「本轮用量」面板：面板上要拆成未缓存输入 / 缓存读取两行，
     * 只有会话级的累计值不够：用户看的是**这一轮**花了多少、命中多少。
     */
    val cachedTokens: Int = 0,
    /**
     * 服务端这一次到底报没报缓存字段。
     *
     * false 时面板的「缓存命中」写「—」：编一个 0% 出来，用户会以为缓存一次都没命中，
     * 而事实只是这家服务商不给这项。
     */
    val usageCacheReported: Boolean = false,
    /** 未缓存输入（服务商口径已归一化）：面板上「未缓存输入」那一行就用它。 */
    val uncachedInputTokens: Int = 0,
    /** 写入缓存的 tokens；null = 这家服务商没有这一项，面板上不显示这一行。 */
    val cacheWriteTokens: Int? = null,
    /** 输出里的推理 tokens；null = 不报，面板上不显示括号里那句。 */
    val reasoningTokens: Int? = null,
    /**
     * 这一轮的「提供方/模型」（写成 `deepseek/deepseek-flash` 这种形态，用量面板按它显示）。
     *
     * 写完就定格：之后切成别的模型，回看历史时面板里仍应是当时用的那个。
     */
    val usageModel: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 这条 user 消息是不是 App 代发的。
     *
     * 高危操作的一次性确认只认真实用户输入：模型生成的是 assistant 消息，无法伪造 user 消息；
     * 但 App 自己会以 user 角色代发内容（例如计划页的「按计划开始执行」），
     * 把它和用户亲手输入的内容分开，确认码校验才不会把程序文本当成用户授权。
     * 老会话读回来是 false（历史里只有用户自己的输入）。
     */
    val programmatic: Boolean = false,
    /**
     * 这条 user 消息发出时附带的「运行时快照」正文（权限档 / 工作区 / 计划模式 / 时间）。
     *
     * **不渲染到界面**，但它会随消息落盘、并在每次组装请求时原样拼回这条消息的末尾：
     * 它为的是让「上一轮实际发出去的字节」与「这一轮读到的历史前缀」完全一致：
     * DeepSeek 的提示词缓存按前缀完整匹配，差一个字节，那一整段就白丢。
     * 老会话读回来是空串，等于没有快照，不拼。
     */
    val runtimeSnapshot: String = ""
)

/** 权限档：只读、工作区、完全；高低顺序见 tools 包的 rank()，不要依赖枚举声明顺序。 */
enum class PermissionLevel { READ_ONLY, WORKSPACE, FULL }
