package com.zmf.agent.core.api

import android.content.Context
import com.zmf.agent.agent.ApiConfig
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
 * 失败不抛异常：网络/鉴权/解析出问题时 [ok] 为 false、[error] 里是一句能直接显示给人看的话，
 * 设置页拿它画一行红字就行，不必再套 try——「查不到余额」不该让设置页崩掉。
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
 * 为什么不走 [com.zmf.agent.agent.provider.DeepSeekProvider]：那条路是流式对话，
 * 这里只要一次普通 GET + 一个小 JSON，单独一个客户端更简单，也不会和对话的
 * 「读超时 0（流式）」配置互相污染。
 */
object BalanceClient {

    private const val TAG = "balance"

    /** 余额接口的超时：它只是个查询，15 秒还不回来就是网络有问题，没必要让人一直等。 */
    private const val TIMEOUT_SECONDS = 15L

    /** 错误正文最多留多少字符，防止把整个 HTML 错误页塞进界面。 */
    private const val MAX_ERROR_CHARS = 200

    private const val PATH_BALANCE = "/user/balance"

    // 懒加载：只有真去查余额时才建线程池，App 启动不付这个成本
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
     * [apiKey] / [baseUrl] 留空时回落到设置页里当前的配置（[ApiConfig]）：
     * 设置页只想「刷新一下余额」，没必要自己先去把 Key 和端点各读一遍；
     * 传了值就以传入的为准（比如用户刚改完输入框还没保存）。
     */
    suspend fun fetchBalance(context: Context, apiKey: String, baseUrl: String): BalanceResult {
        val app = context.applicationContext
        val key = apiKey.trim().ifEmpty { ApiConfig.loadApiKey(app) }
        val endpoint = baseUrl.trim().trimEnd('/').ifEmpty { ApiConfig.loadBaseUrl(app) } + PATH_BALANCE

        return try {
            withContext(Dispatchers.IO) { request(endpoint, key) }
        } catch (e: CancellationException) {
            // 用户离开页面/取消协程：原样抛出，否则取消渗不下去
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
            // 200 但没有 balance_infos：多半是 Key 对应的是没有余额字段的账号，仍算成功但界面要能看出是 0 条
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
     * org.json 的 optString 遇到 JSON null 会返回字符串 "null"，直接显示到界面上就是一个 "null"。
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
                // OpenAI 兼容错误是 {"error":{"message":...}}，DeepSeek 也是同款
                error?.optString("message", "")?.takeIf { it.isNotBlank() }
                    ?: obj.optString("message", "").takeIf { it.isNotBlank() }
            }.getOrNull()
            if (!detail.isNullOrBlank()) return detail.take(MAX_ERROR_CHARS)
            return body.trim().take(MAX_ERROR_CHARS)
        }
        return message.ifBlank { "未知错误" }
    }
}
