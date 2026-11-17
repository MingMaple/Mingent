package com.zmf.agent.app.llm

import android.content.Context
import android.util.Base64
import com.zmf.agent.capability.port.ImageGenerator
import com.zmf.agent.capability.port.ImageResult
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.infra.llm.ProviderConfig
import com.zmf.agent.infra.llm.ProviderStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 按既有模型配置生成图片。
 *
 * 不新增设置项：从 [ProviderStore] 的模型清单识别图像模型，优先当前服务商，其次其他已填密钥的服务商。
 * 协议为 OpenAI 兼容的 `/images/generations`，返回 `b64_json` 或一次性下载的 `url`。
 */
class ProviderImageGenerator(private val context: Context) : ImageGenerator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun available(): Boolean = detect() != null

    override suspend fun generate(prompt: String, size: String, model: String?): ImageResult {
        val picked = detect() ?: return ImageResult.Failure(
            "E_IMAGE_PROVIDER_UNSET",
            "未识别到可用的图像模型。请在「设置 → 模型」中配置包含 image 字样的模型名称，并填写密钥。"
        )
        val name = model?.takeIf { it.isNotBlank() } ?: picked.model
        val endpoint = imagesEndpoint(picked.baseUrl)
        val body = JSONObject()
            .put("model", name)
            .put("prompt", prompt)
            .put("size", size)
            .put("n", 1)
            .put("response_format", "b64_json")
            .toString()
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer " + picked.apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val call = client.newCall(request)
        // 协程取消时中断底层连接：阻塞在 execute() 上的生图请求最长需等待 120 秒，只有这一步能让停止立即生效
        val cancelHook = CoroutineScope(currentCoroutineContext()).launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        return try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    FileLog.log(TAG, "生图失败 HTTP " + response.code)
                    return ImageResult.Failure(
                        "E_IMAGE_REQUEST_FAILED",
                        "图像服务返回 HTTP " + response.code + "。请核对模型名称与密钥。"
                    )
                }
                decode(text, name)
            }
        } catch (e: CancellationException) {
            // 取消是协程的正常结束路径，不能折成业务失败
            throw e
        } catch (e: Exception) {
            // call.cancel() 引发的连接关闭同样是取消路径：先把取消语义传出去
            currentCoroutineContext().ensureActive()
            FileLog.log(TAG, "生图请求异常：" + (e.message ?: e.javaClass.simpleName))
            ImageResult.Failure("E_IMAGE_REQUEST_FAILED", "图像请求失败：" + (e.message ?: "网络异常"))
        } finally {
            // 正常读完也要收起钩子；此时响应体已关闭，call.cancel() 无副作用
            cancelHook.cancel()
        }
    }

    private suspend fun decode(text: String, model: String): ImageResult {
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return ImageResult.Failure("E_IMAGE_REQUEST_FAILED", "图像服务返回的内容不是合法 JSON。")
        val data = root.optJSONArray("data")?.optJSONObject(0)
            ?: return ImageResult.Failure("E_IMAGE_REQUEST_FAILED", "图像服务未返回图像数据。")
        val b64 = data.optString("b64_json", "")
        if (b64.isNotEmpty()) {
            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
            return if (bytes == null) {
                ImageResult.Failure("E_IMAGE_REQUEST_FAILED", "图像数据解码失败。")
            } else {
                ImageResult.Success(bytes, "image/png", model)
            }
        }
        val url = data.optString("url", "")
        if (url.isEmpty()) {
            return ImageResult.Failure("E_IMAGE_REQUEST_FAILED", "图像服务未返回 b64_json 或 url。")
        }
        val download = Request.Builder().url(url).get().build()
        val call = client.newCall(download)
        // 下载同样需挂取消钩子：一次性 url 的正文可能很慢，不能让「停止」等待它读完
        val cancelHook = CoroutineScope(currentCoroutineContext()).launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        return try {
            call.execute().use { response ->
                val bytes = response.body?.bytes()
                if (!response.isSuccessful || bytes == null) {
                    ImageResult.Failure("E_IMAGE_REQUEST_FAILED", "图像下载失败。")
                } else if (bytes.size > MAX_BYTES) {
                    ImageResult.Failure("E_IMAGE_TOO_LARGE", "图像超过 20 MB 上限。")
                } else {
                    ImageResult.Success(bytes, "image/png", model)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            ImageResult.Failure("E_IMAGE_REQUEST_FAILED", "图像下载失败：" + (e.message ?: "网络异常"))
        } finally {
            cancelHook.cancel()
        }
    }

    private data class Picked(val baseUrl: String, val apiKey: String, val model: String)

    /** 依次在「当前服务商 → 其他填了密钥的服务商 → 记住的自定义模型名」中查找图像模型。 */
    private fun detect(): Picked? {
        val providers = runCatching { ProviderStore.load(context) }.getOrDefault(emptyList())
        val current = runCatching { ProviderStore.current(context) }.getOrNull()
        val ordered = ArrayList<ProviderConfig>()
        if (current != null) ordered.add(current)
        for (provider in providers) if (provider.id != current?.id) ordered.add(provider)
        for (provider in ordered) {
            if (provider.apiKey.isBlank()) continue
            val model = provider.models.firstOrNull { isImageModel(it) } ?: continue
            return Picked(provider.baseUrl, provider.apiKey, model)
        }
        return null
    }

    private fun isImageModel(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.containsAny(EXCLUDED)) return false
        return lower.containsAny(INCLUDED)
    }

    private fun String.containsAny(tokens: Array<String>): Boolean =
        tokens.any { contains(it) }

    /** 生成端点；基础地址已含 `/v1` 时不再重复拼接。 */
    private fun imagesEndpoint(baseUrl: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) base = base.removeSuffix("/chat/completions")
        return if (base.endsWith("/v1")) base + "/images/generations" else base + "/v1/images/generations"
    }

    private companion object {
        const val TAG = "image"
        const val MAX_BYTES = 20 * 1024 * 1024

        val INCLUDED = arrayOf(
            "image", "img", "dall-e", "gpt-image", "flux", "seedream",
            "imagen", "kolors", "cogview", "wanx", "sdxl"
        )

        val EXCLUDED = arrayOf("embedding", "rerank", "tts", "whisper", "audio", "video")
    }
}
