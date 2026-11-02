package com.zmf.agent.infra.api

import com.zmf.agent.core.log.FileLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 某个币种的余额（字段名和接口一致，金额一律按字符串保留，避免 Double 把 "0.00" 变成 0.0）。
 */
data class BalanceInfo(
    val currency: String,
    val totalBalance: String,
    val grantedBalance: String,
    val toppedUpBalance: String
)

/**
 * 一次余额查询的结果。
 *
 * 失败不抛异常：网络、鉴权或解析出错时 [ok] 为 false，[error] 为可直接展示的说明；
 * 设置页直接显示该文本即可，无需额外 try 包裹，查询失败不应导致设置页崩溃。
 */
data class BalanceResult(
    val ok: Boolean,
    /** 账户是否还能用（接口的 is_available）。 */
    val isAvailable: Boolean = false,
    val infos: List<BalanceInfo> = emptyList(),
    val error: String = ""
)

/**
 * DeepSeek 余额查询客户端：GET {baseUrl}/user/balance，Bearer 鉴权。
 *
 * 未复用 [com.zmf.agent.infra.llm.DeepSeekProvider]：该实现用于流式对话；
 * 此处仅需一次普通 GET 与一份小型 JSON，独立客户端更简单，也不会与对话的
 * 「读超时 0（流式）」配置相互影响。
 */
object BalanceClient {

    private const val TAG = "balance"

    /** 余额接口超时：该接口仅为查询，超过 15 秒未返回即视为网络异常，无需持续等待。 */
    private const val TIMEOUT_SECONDS = 15L

    /** 错误正文最大字符数，避免将整个 HTML 错误页写入界面。 */
    private const val MAX_ERROR_CHARS = 200

    private const val PATH_BALANCE = "/user/balance"

    // 懒加载：仅在查询余额时创建线程池，App 启动不承担该开销
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 查一次余额。
     *
     * [apiKey] / [baseUrl] 由调用方完整传入，此处不读取配置：配置读取属于上层职责（上层知晓当前 provider），
     * infra 仅负责发送请求；此处不回落至 [ApiConfig]，以避免 infra 反向依赖业务配置层。
     */
    suspend fun fetchBalance(apiKey: String, baseUrl: String): BalanceResult {
        val key = apiKey.trim()
        val trimmedBase = baseUrl.trim().trimEnd('/')
        if (key.isEmpty() || trimmedBase.isEmpty()) {
            return BalanceResult(ok = false, error = "还没有配置 API 密钥或端点")
        }
        val endpoint = trimmedBase + PATH_BALANCE

        return try {
            withContext(Dispatchers.IO) { request(endpoint, key) }
        } catch (e: CancellationException) {
            // 用户离开页面或取消协程：原样抛出，否则取消无法向上传播
            throw e
        } catch (e: Exception) {
            val reason = e.message?.take(MAX_ERROR_CHARS) ?: e.javaClass.simpleName
            FileLog.log(TAG, "余额查询失败：" + e.javaClass.simpleName + " / " + reason)
            BalanceResult(ok = false, error = "余额查询失败：" + reason)
        }
    }

    private fun request(endpoint: String, apiKey: String): BalanceResult {
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                FileLog.log(TAG, "余额查询 HTTP " + response.code + "：" + body.take(MAX_ERROR_CHARS))
                return BalanceResult(ok = false, error = "HTTP " + response.code + "：" + briefError(response.message, body))
            }
            return parse(body)
        }
    }

    /** 解析 {"is_available":true,"balance_infos":[{currency,total_balance,granted_balance,topped_up_balance}]}。 */
    private fun parse(body: String): BalanceResult {
        if (body.isBlank()) return BalanceResult(ok = false, error = "余额接口返回空响应")
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return BalanceResult(ok = false, error = "余额接口返回的不是合法 JSON：" + body.take(MAX_ERROR_CHARS))
        }

        val infos = ArrayList<BalanceInfo>()
        val array = root.optJSONArray("balance_infos")
        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                infos.add(
                    BalanceInfo(
                        currency = field(obj, "currency"),
                        totalBalance = field(obj, "total_balance"),
                        grantedBalance = field(obj, "granted_balance"),
                        toppedUpBalance = field(obj, "topped_up_balance")
                    )
                )
            }
        }
        if (infos.isEmpty()) {
            // 返回 200 但无 balance_infos：通常表示该密钥对应账号无余额字段；仍视为成功，但界面应能体现为 0 条
            FileLog.log(TAG, "余额接口没有 balance_infos")
        }
        return BalanceResult(
            ok = true,
            isAvailable = root.optBoolean("is_available", false),
            infos = infos
        )
    }

    /**
     * 读字符串字段：缺字段 / 显式 null / 字符串 "null" 一律当空串。
     *
     * org.json 的 optString 遇到 JSON null 会返回字符串 "null"，会直接显示为 "null"。
     * 金额是数字时 optString 会给出它的文本形式（"110.00" 原样保留），所以统一按字符串取。
     */
    private fun field(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val value = obj.optString(key, "").trim()
        return if (value == "null") "" else value
    }

    /** 错误提示：优先用服务端给的 JSON 里的 message，没有就用 HTTP 短语，都截断到 [MAX_ERROR_CHARS]。 */
    private fun briefError(message: String, body: String): String {
        if (body.isNotBlank()) {
            val detail = runCatching {
                val obj = JSONObject(body)
                val error = obj.optJSONObject("error")
                // OpenAI 兼容错误格式为 {"error":{"message":...}}，DeepSeek 采用相同格式
                error?.optString("message", "")?.takeIf { it.isNotBlank() }
                    ?: obj.optString("message", "").takeIf { it.isNotBlank() }
            }.getOrNull()
            if (!detail.isNullOrBlank()) return detail.take(MAX_ERROR_CHARS)
            return body.trim().take(MAX_ERROR_CHARS)
        }
        return message.ifBlank { "未知错误" }
    }
}
