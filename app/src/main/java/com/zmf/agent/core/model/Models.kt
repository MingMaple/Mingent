package com.zmf.agent.core.model

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

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
    val createdAt: Long = System.currentTimeMillis()
)

enum class PermissionLevel { READ_ONLY, WORKSPACE, FULL }
