package com.zmf.agent.agent.provider

import com.zmf.agent.core.model.ChatMessage
import kotlinx.coroutines.flow.Flow

data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val thinking: Boolean = true,
    /** 思考强度：low / high / max，null 表示不传（走服务端默认 high）。 */
    val reasoningEffort: String? = null,
    /**
     * 输出上限（token），null = 不传（走服务端默认）。
     *
     * 目前只有摘要请求会设：DSH 的摘要器用 maxTokens 兜住「摘要不能比原文还长」，
     * 对话请求不设——它本来就要按用户提问自由长度输出。
     */
    val maxTokens: Int? = null
)

sealed interface LlmEvent {
    data class ReasoningDelta(val text: String) : LlmEvent
    data class ContentDelta(val text: String) : LlmEvent
    /** 工具调用增量：同一个 index 的片段要自己拼接。 */
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String? = null
    ) : LlmEvent

    data class Finished(val finishReason: String?, val promptTokens: Int, val completionTokens: Int) : LlmEvent
    data class Failed(val code: Int, val message: String) : LlmEvent
}

interface LlmProvider {
    fun stream(request: ChatRequest): Flow<LlmEvent>
}
