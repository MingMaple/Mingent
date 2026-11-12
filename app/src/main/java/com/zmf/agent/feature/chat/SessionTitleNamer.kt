package com.zmf.agent.feature.chat

import android.content.Context
import com.zmf.agent.capability.config.ApiConfig
import com.zmf.agent.core.llm.ApiDefaults
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.infra.llm.ChatRequest
import com.zmf.agent.infra.llm.DeepSeekProvider
import com.zmf.agent.infra.llm.LlmEvent
import com.zmf.agent.infra.llm.LlmProvider
import com.zmf.agent.infra.llm.ProviderStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话标题的模型生成器：用首条用户消息向当前模型要一个短标题。
 *
 * 口径对照 DSH（DeepSeek Harness）的 `session-title-first-prompt-llm`：
 * - 只读入**首条**用户消息，并以 JSON 数组的形式交给模型，避免正文里的分隔符破坏提示结构；
 * - 提示词要求目标 5 个词或 10 个汉字、只用自然语言一行正文；
 * - 输入超过 4096 字节时**不发送**（DSH 在原实现里直接报错，效果同样是失败并保留兜底标题）；
 * - 60 秒超时，失败、超时、空结果、输出被截断一律返回 null，由调用方保留兜底标题。
 *
 * 本类只负责「拿到一段可用的标题文本」；标题的清洗与 80 字节上限由
 * [com.zmf.agent.core.store.SessionStore.normalizeTitle] 在受理处统一施加。
 */
object SessionTitleNamer {

    private const val TAG = "session-title"

    /** 与 DSH 的 `targetWords` 同值。 */
    private const val TARGET_WORDS = 5

    /** 与 DSH 的 `targetCjkCharacters` 同值。 */
    private const val TARGET_CJK_CHARACTERS = 10

    /** 与 DSH 的 `maxInputBytes` 同值：超过即放弃本次生成。 */
    private const val MAX_INPUT_BYTES = 4096

    /** 与 DSH 的 `timeoutMs` 同值；项目内的摘要请求也用 60 秒。 */
    private const val TIMEOUT_MS = 60_000L

    /**
     * 输出上限（token）。
     *
     * DSH 由配置项 `maxOutputTokens` 指定；本项目取 64：目标只有一行十来个汉字，
     * 该值足以容纳标题并留出余量，同时避免模型把一次小任务写成一段话。
     */
    private const val MAX_OUTPUT_TOKENS = 64

    /** 整行被星号强调包裹，如 `**标题**`：只剥掉外层标记，行内的星号原样保留。 */
    private val EMPHASIS_WRAPPER = Regex("^(\\*{1,3})(\\S(?:.*\\S)?)\\1$")

    /** 当前复用的请求通道；Key 或端点变化时重建，避免每次起标题都新建客户端。 */
    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cachedProvider: LlmProvider? = null

    /**
     * 为一条会话生成标题。
     *
     * @param context 用于读取当前提供商、模型与鉴权信息
     * @param firstPrompt 首条用户消息的正文（调用方已按标题候选规则选定）
     * @param onUsage 收到服务端收尾用量时回调；标题请求同样计费，由调用方入账
     * @return 清洗后仍非空的标题文本；任何失败路径均返回 null
     */
    suspend fun generate(
        context: Context,
        firstPrompt: String,
        onUsage: ((LlmEvent.Finished) -> Unit)? = null
    ): String? {
        val provider = providerFor(context) ?: return null
        val model = ApiConfig.loadModel(context)
        if (model.isBlank()) {
            FileLog.log(TAG, "标题跳过：模型名为空")
            return null
        }
        val input = frame(firstPrompt)
        val inputBytes = input.toByteArray(Charsets.UTF_8).size
        if (inputBytes > MAX_INPUT_BYTES) {
            // DSH 在此处直接报错：超限的输入不截断，本次生成按失败处理，兜底标题保留
            FileLog.log(TAG, "标题跳过：输入 " + inputBytes + " 字节，超过上限 " + MAX_INPUT_BYTES)
            return null
        }

        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = SYSTEM_PROMPT),
                ChatMessage(role = "user", content = input)
            ),
            // 标题只需要结果：关闭思考，既省 token，也避免推理内容混入输出
            thinking = false,
            maxTokens = MAX_OUTPUT_TOKENS
        )

        var failure: String? = null
        var toolCall = false
        var finishReason: String? = null
        val output = StringBuilder()
        val startedAt = System.currentTimeMillis()
        try {
            withTimeout(TIMEOUT_MS) {
                provider.stream(request)
                    // 收到 Failed 后立即停止收集，避免把不完整的输出当成标题
                    .takeWhile { failure == null }
                    .collect { event ->
                        when (event) {
                            is LlmEvent.ContentDelta -> output.append(event.text)
                            is LlmEvent.ToolCallDelta -> toolCall = true
                            is LlmEvent.Finished -> {
                                finishReason = event.finishReason
                                // 即使后续按失败处理（截断、含工具调用），这次请求的 token 已经消耗
                                onUsage?.invoke(event)
                            }
                            is LlmEvent.Failed -> failure = if (event.code > 0) {
                                "HTTP " + event.code + "：" + event.message
                            } else {
                                event.message
                            }

                            else -> {}
                        }
                    }
            }
        } catch (e: TimeoutCancellationException) {
            FileLog.log(TAG, "标题超时（" + (TIMEOUT_MS / 1000) + " 秒），保留兜底标题")
            return null
        } catch (e: CancellationException) {
            // 用户停止或会话销毁：正常取消必须向外传递，不得吞没
            throw e
        } catch (e: Exception) {
            FileLog.log(TAG, "标题请求异常：" + e)
            return null
        }

        if (failure != null) {
            FileLog.log(TAG, "标题请求失败：" + failure)
            return null
        }
        if (toolCall) {
            // DSH 的 `tool-calls` 收尾同样按失败处理：标题必须是纯文本
            FileLog.log(TAG, "标题输出含工具调用，按失败处理")
            return null
        }
        if (finishReason == "length" || finishReason == "max_tokens") {
            // 对应 DSH 的 `max-tokens`：输出被截断，标题可能不完整，保留兜底标题
            FileLog.log(TAG, "标题输出达到输出上限，按失败处理")
            return null
        }

        val title = titleFromOutput(output.toString())
        if (title.isNullOrEmpty()) {
            FileLog.log(TAG, "标题结果为空，按失败处理")
            return null
        }
        FileLog.log(TAG, "标题生成完成，用时 " + (System.currentTimeMillis() - startedAt) + "ms")
        return title
    }

    /** 取模型输出里第一条非空行；整行被星号强调包裹时剥掉外层标记。 */
    private fun titleFromOutput(text: String): String? {
        val line = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val groups = EMPHASIS_WRAPPER.find(line)?.groupValues ?: return line
        val marker = groups.getOrNull(1).orEmpty()
        val inner = groups.getOrNull(2).orEmpty()
        return if (marker.isEmpty() || inner.isEmpty() || inner.contains(marker)) line else inner
    }

    /** 把首条消息包装成 JSON 数组：用户正文里的结构符号不会破坏提示的定界。 */
    private fun frame(firstPrompt: String): String {
        val array = JSONArray()
        array.put(JSONObject().put("text", firstPrompt))
        return "Generate the session title from this JSON array of human messages:\n" + array
    }

    /** 当前提供商的请求通道；Key 或端点变化时重建。 */
    private fun providerFor(context: Context): LlmProvider? {
        val cfg = ProviderStore.current(context)
        val apiKey = cfg.apiKey.trim()
        if (apiKey.isEmpty()) {
            FileLog.log(TAG, "标题跳过：当前提供商还没有配置 API 密钥")
            return null
        }
        val baseUrl = cfg.baseUrl.trim().trimEnd('/').ifBlank {
            // 仅内置 DeepSeek 允许回退至官方端点；其他厂商的空地址由 provider 明确报错
            if (cfg.id == ProviderStore.DEEPSEEK_ID) ApiDefaults.BASE_URL else ""
        }
        val key = apiKey + "\u0000" + baseUrl
        cachedProvider?.let { if (cachedKey == key) return it }
        val created = DeepSeekProvider(apiKey = apiKey, baseUrl = baseUrl)
        cachedKey = key
        cachedProvider = created
        return created
    }

    /** 与 DSH 的 `systemPrompt` 逐句对应，仅替换目标词数与汉字数。 */
    private val SYSTEM_PROMPT = listOf(
        "Create a concise title for an AI coding-assistant session from the supplied human messages.",
        "Return only the title on one line, **in plain text of natural language**, with no quotes, " +
            "prefix, explanation, Markdown, XML, or terminal control codes. No code is allowed.",
        "Use the language of the messages.",
        "Aim for about " + TARGET_WORDS + " words in non-CJK languages or " +
            TARGET_CJK_CHARACTERS + " CJK characters.",
        "If the messages give little to name, still return a short best-effort title, " +
            "such as Greeting, instead of explaining."
    ).joinToString("\n")
}
