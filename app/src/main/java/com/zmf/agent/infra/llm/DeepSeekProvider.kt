package com.zmf.agent.infra.llm

import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DeepSeek（OpenAI 兼容）流式实现。
 *
 * 该类同时作为任意 OpenAI 兼容端点的实现：baseUrl / apiKey 由调用方按当前
 * [com.zmf.agent.infra.llm.ProviderConfig] 传入；保留类名以避免影响调用方与日志口径。
 *
 * 关键约束：请求若带 tools，历史里每条 assistant 消息都必须回传 reasoning_content，
 * 否则服务端直接 400。见 https://api-docs.deepseek.com/zh-cn/guides/thinking_mode
 */
class DeepSeekProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com"
) : LlmProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        // 端点为空（自定义 provider 尚未填写地址）时优先返回明确提示：
        // 直接拼接 "/chat/completions" 请求只会抛出底层异常，用户无法判断需修改何处
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) {
            emit(LlmEvent.Failed(-1, "当前提供商还没有填写接口地址，请在设置 → 模型里补上"))
            return@flow
        }

        // 开源包不包含内置密钥：空 Key 直接发送只会返回 401，用户无法得知填写位置。
        // 此处于请求前拦截，并明确指出「设置 → 模型」。
        if (apiKey.isBlank()) {
            emit(LlmEvent.Failed(-1, "当前提供商还没有配置 API 密钥，请到设置 → 模型里填写"))
            return@flow
        }

        // 地址不合法（如缺少 https://）时 OkHttp 会在 build 阶段抛出 IllegalArgumentException；
        // 此处提前捕获，用户看到的是「地址无效」而非底层异常
        val httpRequest = try {
            Request.Builder()
                .url(base + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream")
                .post(buildRequestJson(request).toString().toRequestBody(JSON))
                .build()
        } catch (e: Exception) {
            emit(LlmEvent.Failed(-1, "接口地址无效，请在设置 → 模型里检查地址是否以 https:// 开头"))
            return@flow
        }

        val call = client.newCall(httpRequest)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    emit(LlmEvent.Failed(response.code, text.take(2000)))
                    return@use
                }
                val source = response.body?.source()
                if (source == null) {
                    emit(LlmEvent.Failed(-1, "服务端没有返回内容，请稍后重试"))
                    return@use
                }
                var finishReason: String? = null
                var promptTokens = 0
                var completionTokens = 0
                // 命中提示词缓存的输入 tokens：DeepSeek 用 prompt_cache_hit_tokens，
                // OpenAI 那一系放在 prompt_tokens_details.cached_tokens，两个都认
                var cachedTokens = 0
                var cacheReported = false
                // 另外三家的差异：Anthropic 兼容端点报 cache_read / cache_creation_input_tokens，
                // 且 input 只是未缓存部分；推理 tokens 在 completion_tokens_details 里（有的家放顶层）
                var cacheWriteTokens: Int? = null
                var reasoningTokens: Int? = null
                var promptIncludesCache = true

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty() || !line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break

                    val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue

                    chunk.optJSONObject("usage")?.let { usage ->
                        promptTokens = usage.optInt("prompt_tokens", promptTokens)
                        completionTokens = usage.optInt("completion_tokens", completionTokens)

                        // ① Anthropic 兼容端点：缓存读写各自成字段，且 prompt_tokens 里**不含**它们。
                        //    先判这一支，否则下面按「总量含命中」算会把未缓存输入算成 0。
                        val anthropicRead = usage.optIntOrNull("cache_read_input_tokens")
                        val anthropicWrite = usage.optIntOrNull("cache_creation_input_tokens")
                        if (anthropicRead != null || anthropicWrite != null) {
                            cachedTokens = anthropicRead ?: 0
                            cacheReported = anthropicRead != null
                            cacheWriteTokens = anthropicWrite
                            promptIncludesCache = false
                        }

                        // ② DeepSeek：命中放在顶层 prompt_cache_hit_tokens（总量含命中）
                        if (usage.has("prompt_cache_hit_tokens")) {
                            cachedTokens = usage.optInt("prompt_cache_hit_tokens", 0)
                            cacheReported = true
                            promptIncludesCache = true
                        }

                        // ③ OpenAI 系：命中放在 prompt_tokens_details.cached_tokens；
                        //    有的兼容端点还会在这里补一份 cache_creation_tokens
                        val details = usage.optJSONObject("prompt_tokens_details")
                        if (details != null) {
                            if (!cacheReported && details.has("cached_tokens")) {
                                cachedTokens = details.optInt("cached_tokens", 0)
                                cacheReported = true
                                promptIncludesCache = true
                            }
                            if (cacheWriteTokens == null) {
                                cacheWriteTokens = details.optIntOrNull("cache_creation_tokens")
                            }
                        }

                        // ④ 推理 tokens：优先 completion_tokens_details.reasoning_tokens，其次顶层 reasoning_tokens
                        val completionDetails = usage.optJSONObject("completion_tokens_details")
                        reasoningTokens = completionDetails?.optIntOrNull("reasoning_tokens")
                            ?: usage.optIntOrNull("reasoning_tokens")
                    }

                    val choices = chunk.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val choice = choices.getJSONObject(0)

                    if (!choice.isNull("finish_reason")) {
                        finishReason = choice.optString("finish_reason")
                    }
                    val delta = choice.optJSONObject("delta") ?: continue

                    // 注意：字段缺失和显式 null 是两回事。org.json 的 optString 遇到 JSON null
                    // 会返回字符串 "null"，直接把 "null" 拼进正文/思考里，必须先用 isNull 过滤。
                    if (!delta.isNull("reasoning_content")) {
                        val reasoning = delta.optString("reasoning_content", "")
                        if (reasoning.isNotEmpty()) emit(LlmEvent.ReasoningDelta(reasoning))
                    }

                    if (!delta.isNull("content")) {
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty()) emit(LlmEvent.ContentDelta(content))
                    }

                    delta.optJSONArray("tool_calls")?.let { calls ->
                        for (i in 0 until calls.length()) {
                            val callObj = calls.optJSONObject(i) ?: continue
                            val fn = callObj.optJSONObject("function")
                            emit(
                                LlmEvent.ToolCallDelta(
                                    index = callObj.optInt("index", i),
                                    id = callObj.optString("id", "").takeIf { it.isNotEmpty() },
                                    name = fn?.optString("name", "")?.takeIf { it.isNotEmpty() },
                                    argumentsDelta = fn?.optString("arguments", "")?.takeIf { it.isNotEmpty() }
                                )
                            )
                        }
                    }
                }
                emit(
                    LlmEvent.Finished(
                        finishReason = finishReason,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        cachedTokens = cachedTokens,
                        cacheReported = cacheReported,
                        cacheWriteTokens = cacheWriteTokens,
                        reasoningTokens = reasoningTokens,
                        promptIncludesCache = promptIncludesCache
                    )
                )
            }
        } catch (e: CancellationException) {
            // 取消是协程的正常结束路径，转成失败事件会掩盖取消语义
            throw e
        } catch (e: Exception) {
            FileLog.log("llm", "请求失败：" + e.javaClass.simpleName + " / " + (e.message ?: ""))
            emit(LlmEvent.Failed(-1, "请求失败，请检查网络连接后重试"))
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestJson(request: ChatRequest): JSONObject {
        val root = JSONObject()
        root.put("model", request.model)
        root.put("stream", true)
        root.put("stream_options", JSONObject().put("include_usage", true))
        root.put(
            "thinking",
            JSONObject().put("type", if (request.thinking) "enabled" else "disabled")
        )
        // 思考强度：官方文档里的 OpenAI 格式参数是顶层 reasoning_effort，取值 low/high/max（默认 high）。
        // 只在思考开启时发；关闭时发这个没有意义。
        if (request.thinking && !request.reasoningEffort.isNullOrBlank()) {
            root.put("reasoning_effort", request.reasoningEffort)
        }
        // 输出上限：只有摘要请求会带上（口径含推理 token）。
        // 不设置时不添加任何字段，对话请求行为与既有实现一致。
        request.maxTokens?.takeIf { it > 0 }?.let { root.put("max_tokens", it) }

        val messages = JSONArray()
        for (m in request.messages) messages.put(toApiMessage(m))
        root.put("messages", messages)

        if (request.tools.isNotEmpty()) {
            val tools = JSONArray()
            for (t in request.tools) {
                tools.put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", JSONObject(t.parametersJson))
                    )
                )
            }
            root.put("tools", tools)
        }
        return root
    }

    private fun toApiMessage(m: ChatMessage): JSONObject {
        val obj = JSONObject().put("role", m.role)
        when (m.role) {
            "assistant" -> {
                // 带 tools 的请求必须回传 reasoning_content（没有就传空串，不能省略键）
                obj.put("content", m.content)
                obj.put("reasoning_content", m.reasoningContent ?: "")
                if (m.toolCalls.isNotEmpty()) {
                    val calls = JSONArray()
                    for (call in m.toolCalls) {
                        calls.put(
                            JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject().put("name", call.name).put("arguments", call.arguments)
                                )
                        )
                    }
                    obj.put("tool_calls", calls)
                }
            }
            "tool" -> {
                obj.put("content", m.content)
                obj.put("tool_call_id", m.toolCallId ?: "")
            }
            // user / system：带图片时 content 使用多模态数组，不带图片时使用原字符串（历史请求逐字不变）
            else -> obj.put("content", contentOf(m))
        }
        return obj
    }

    /**
     * 正文的两种形态。
     *
     * 无 [ChatMessage.imageDataUrls] 时返回纯字符串，与既有实现完全一致；
     * 有图片时换成 OpenAI 兼容的多模态数组：先一段 text，再每张图一段 image_url。
     * assistant / tool 分支不走这里：历史里的助手消息只保留图片**路径**（images 字段），
     * 图片数据只在真正发用户消息的那一次请求里出现。
     */
    private fun contentOf(m: ChatMessage): Any {
        if (m.imageDataUrls.isEmpty()) return m.content
        val parts = JSONArray()
        parts.put(JSONObject().put("type", "text").put("text", m.content))
        for (url in m.imageDataUrls) {
            parts.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", url))
            )
        }
        return parts
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** 读取可空整数：字段不存在或为 JSON null 时返回 null：「未上报」与「上报 0」含义不同。 */
private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key, 0)
}
