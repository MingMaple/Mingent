package com.zmf.agent.agent.provider

import com.zmf.agent.core.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
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
 * 多提供商后它同时是「任何 OpenAI 兼容端点」的实现：baseUrl / apiKey 由调用方按当前
 * [com.zmf.agent.agent.provider.ProviderConfig] 传进来，类名保留是为了不牵动调用方与日志口径。
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
        // 端点空着（自定义 provider 还没填地址）时先给一句人话：
        // 拼一个 "/chat/completions" 去请求只会抛底层异常，用户看不出该改哪里
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) {
            emit(LlmEvent.Failed(-1, "当前提供商没有填写 Base URL，请到设置里补上"))
            return@flow
        }

        // 开源包不带任何内置密钥：空 Key 直接发出去只会拿到一句 401，用户不知道该去哪填。
        // 这里在请求前就拦住，并明确指到「设置 → 模型」。
        if (apiKey.isBlank()) {
            emit(LlmEvent.Failed(-1, "当前提供商还没有配置 API 密钥，请到设置 → 模型里填写"))
            return@flow
        }

        // 地址本身不合法（少了 https:// 之类）时 OkHttp 会在 build 阶段抛 IllegalArgumentException，
        // 这一句提前收住，用户看到的是「地址无效」而不是一条底层异常
        val httpRequest = try {
            Request.Builder()
                .url(base + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream")
                .post(buildRequestJson(request).toString().toRequestBody(JSON))
                .build()
        } catch (e: Exception) {
            emit(LlmEvent.Failed(-1, "请求地址无效：" + (e.message ?: e.javaClass.simpleName)))
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
                    emit(LlmEvent.Failed(-1, "响应体为空"))
                    return@use
                }
                var finishReason: String? = null
                var promptTokens = 0
                var completionTokens = 0

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty() || !line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break

                    val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue

                    chunk.optJSONObject("usage")?.let { usage ->
                        promptTokens = usage.optInt("prompt_tokens", promptTokens)
                        completionTokens = usage.optInt("completion_tokens", completionTokens)
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
                emit(LlmEvent.Finished(finishReason, promptTokens, completionTokens))
            }
        } catch (e: Exception) {
            emit(LlmEvent.Failed(-1, e.message ?: e.javaClass.simpleName))
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
        // 输出上限：只有摘要请求会带上（DSH 的 maxTokens 口径，含推理 token）。
        // 不设就一个字段都不加，对话请求的行为与改造前完全一致。
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
            // user / system：带图片时 content 走多模态数组，不带就是原来的字符串（历史请求一字不变）
            else -> obj.put("content", contentOf(m))
        }
        return obj
    }

    /**
     * 正文的两种形态。
     *
     * 没有 [ChatMessage.imageDataUrls] 时返回纯字符串，跟改造前完全一致；
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
