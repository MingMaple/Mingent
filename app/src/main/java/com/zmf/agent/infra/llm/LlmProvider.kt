package com.zmf.agent.infra.llm

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
     * 目前仅摘要请求设置该值：用于保证摘要不超过原文；
     * 对话请求不设置，其输出长度应由用户提问决定。
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
     * 服务端未返回（非 DeepSeek、旧接口或未启用缓存）时为 0，界面应显示「—」而非「0%」。
     *
     * 其余三个字段用于在其他模型下完整记账：各家上报用量的位置与口径不同，
     * 归一化在此处完成，上层（循环、界面、落盘）仅面对一套统一结构。
     */
    data class Finished(
        val finishReason: String?,
        val promptTokens: Int,
        val completionTokens: Int,
        val cachedTokens: Int = 0,
        /** 服务端是否上报缓存字段：未上报时不得计入，界面显示「—」而非 0%。 */
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
 * 一次请求的用量桶，**已按服务商口径归一化**：上层无需再判断各服务商是否上报缓存。
 *
 * 归一化的原因：DeepSeek 的 prompt_tokens 含缓存命中，Anthropic 系的 input_tokens 仅为未缓存部分；
 * 若不统一，「未缓存输入」会在切换模型后少算或多算一整段缓存。
 */
data class UsageBuckets(
    /** 输入总量（未缓存 + 缓存读取 + 缓存写入），跨服务商同一口径。 */
    val promptTokens: Int,
    val completionTokens: Int,
    /** 未命中缓存的输入 token 数（面板「未缓存输入」一行的口径）。 */
    val uncachedInputTokens: Int,
    /** 命中缓存的输入 token 数；[cacheReadReported] 为 false 时恒为 0。 */
    val cacheReadTokens: Int,
    /** 服务端是否上报缓存读取：未上报时面板不显示该行，而非写入 0。 */
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
