package com.zmf.agent.agent.provider

import com.zmf.agent.core.model.ChatMessage
import kotlinx.coroutines.flow.Flow

/** 下发给模型的一个工具声明：名称、描述与 JSON Schema 参数表。 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String
)

/** 一次对话请求的完整参数；[messages] 是已拼好的上下文，[tools] 为空即不声明任何工具。 */
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
     * 目前只有摘要请求会设：用它兜住「摘要不能比原文还长」，
     * 对话请求不设：它本来就要按用户提问自由长度输出。
     */
    val maxTokens: Int? = null
)

/** 提供方逐条推送的流式事件，按到达顺序消费。 */
sealed interface LlmEvent {
    /** 推理（思考）内容增量。 */
    data class ReasoningDelta(val text: String) : LlmEvent

    /** 正文内容增量。 */
    data class ContentDelta(val text: String) : LlmEvent

    /** 工具调用增量：同一个 index 的片段要自己拼接。 */
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String? = null
    ) : LlmEvent

    /**
     * 一次请求收尾。`cachedTokens` 是这次输入里命中**提示词缓存**的那部分 token：
     * 服务端没返回（非 DeepSeek / 老接口 / 没开缓存）时为 0，界面上要显示「—」而不是「0%」。
     *
     * 其余三个字段是为「换别家模型也把账记全」加的：各家把用量放在不同位置、口径也不同，
     * 归一化在这里做完，上层（循环、界面、落盘）只面对一套桶。
     */
    data class Finished(
        val finishReason: String?,
        val promptTokens: Int,
        val completionTokens: Int,
        val cachedTokens: Int = 0,
        /** 服务端这次有没有报缓存字段：没报就一个字都不能算，界面上显示「—」而不是 0%。 */
        val cacheReported: Boolean = false,
        /** 写入缓存的 tokens：Anthropic 系会报，DeepSeek / OpenAI 没有这一项（null = 不报）。 */
        val cacheWriteTokens: Int? = null,
        /** 输出里的推理 tokens：报了才显示，没报不显示（null = 不报）。 */
        val reasoningTokens: Int? = null,
        /**
         * 输入总量里是否已经包含缓存命中部分。
         *
         * DeepSeek / OpenAI：`prompt_tokens` 是**总量**（含命中）→ true，未缓存 = 总量 − 命中；
         * Anthropic 系：`input_tokens` **只是未缓存部分**，缓存读写另计 → false。
         * 两种口径混用会让「未缓存输入」多算或少算一整段缓存，所以必须在归一化时定死。
         */
        val promptIncludesCache: Boolean = true
    ) : LlmEvent
    /** 请求失败：携带服务端错误码与消息，由上层决定是否重试。 */
    data class Failed(val code: Int, val message: String) : LlmEvent
}

/**
 * 一次请求的用量桶，**已按服务商口径归一化**：上层不用再判断「这家报没报缓存」。
 *
 * 为什么要归一化：DeepSeek 的 prompt_tokens 含缓存命中，Anthropic 系的 input_tokens 只是未缓存部分；
 * 不统一的直接后果是「未缓存输入」这一行在换家模型之后算错一整段缓存。
 */
data class UsageBuckets(
    /** 输入总量（未缓存 + 缓存读取 + 缓存写入），跨服务商同一口径。 */
    val promptTokens: Int,
    val completionTokens: Int,
    /** 未命中缓存的输入 token 数（面板「未缓存输入」一行的口径）。 */
    val uncachedInputTokens: Int,
    /** 命中缓存的输入 token 数；[cacheReadReported] 为 false 时恒为 0。 */
    val cacheReadTokens: Int,
    /** 服务端报没报缓存读取：没报时面板上那一行整行不显示，而不是写 0。 */
    val cacheReadReported: Boolean,
    /** 缓存写入：null = 这家服务商没有这一项（DeepSeek / OpenAI），面板上不显示这一行。 */
    val cacheWriteTokens: Int? = null,
    /** 推理 tokens：null = 不报。 */
    val reasoningTokens: Int? = null
) {
    /**
     * 按服务商的原始 usage 归一化。
     *
     * 判定顺序即各家字段的实际出现顺序：DeepSeek 的 prompt_cache_hit_tokens 最明确；
     * OpenAI 系放在 prompt_tokens_details.cached_tokens；Anthropic 兼容端点用
     * cache_read_input_tokens / cache_creation_input_tokens，且 **input 里不含缓存部分**。
     */
    companion object {
        /**
         * 由提供方返回的原始 usage 生成归一化用量桶。
         *
         * @param cacheReported 是否上报了缓存读取字段；false 时缓存读取按 0 计并标记为未上报
         * @param promptIncludesCache 输入总量是否已含缓存命中部分（DeepSeek / OpenAI 为 true，Anthropic 系为 false）
         */
        fun of(
            promptTokens: Int,
            completionTokens: Int,
            cachedTokens: Int,
            cacheReported: Boolean,
            cacheWriteTokens: Int?,
            reasoningTokens: Int?,
            promptIncludesCache: Boolean
        ): UsageBuckets {
            val read = if (cacheReported) cachedTokens.coerceAtLeast(0) else 0
            val write = cacheWriteTokens?.coerceAtLeast(0)
            val uncached = if (promptIncludesCache) {
                (promptTokens - read).coerceAtLeast(0)
            } else {
                promptTokens.coerceAtLeast(0)
            }
            val total = if (promptIncludesCache) {
                promptTokens.coerceAtLeast(uncached)
            } else {
                uncached + read + (write ?: 0)
            }
            return UsageBuckets(
                promptTokens = total,
                completionTokens = completionTokens,
                uncachedInputTokens = uncached,
                cacheReadTokens = read,
                cacheReadReported = cacheReported,
                cacheWriteTokens = write,
                reasoningTokens = reasoningTokens?.coerceAtLeast(0)
            )
        }
    }
}

/** 大模型提供方的统一入口，上层只依赖这个接口，具体厂商的协议在实现类里。 */
interface LlmProvider {
    /**
     * 发起一次流式请求，按到达顺序推送 [LlmEvent]。
     *
     * 错误不通过抛异常上抛，而是以 [LlmEvent.Failed] 结束流。
     */
    fun stream(request: ChatRequest): Flow<LlmEvent>
}
