package com.zmf.agent.agent.mcp

import com.zmf.agent.core.log.FileLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 从 tools/list 里拿回来的一个工具：name/description 原样，inputSchema 原样存成 JSON 字符串。 */
data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchemaJson: String
)

/**
 * 一个 MCP 服务的最小 JSON-RPC 客户端。
 *
 * 只做三件事：initialize（含 notifications/initialized）、tools/list、tools/call。
 * 没有引入 MCP 官方 SDK：协议面很小，自己拼 JSON-RPC 更可控，也少一个依赖。
 *
 * 两种传输都支持：
 * - http（新版 Streamable HTTP）：所有请求都 POST 到 url，响应可能是 application/json，
 *   也可能是 text/event-stream（一行行 data:），统一交给 [parseBody] 处理；
 * - sse（旧版 HTTP+SSE）：先 GET url 读事件流，把 event: endpoint 的 data 当后续 POST 地址。
 *   握手失败（离线、服务端不支持、超时）只记一条日志并退化为直接 POST url，不抛异常中断流程。
 *
 * 所有异常都在 [initialize] / [listTools] / [callTool] 边界折成 Result.failure，
 * message 一定是可读中文，直接能显示在包管理页/回给模型。
 */
class McpClient(private val server: McpServer) : McpTransport {

    /** 网络请求超时统一 60 秒：MCP 服务可能挂在本机/局域网，也可能跑远端。 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** 服务端在 initialize 响应头里给的会话 id，后续每个请求都要带回去。 */
    @Volatile
    private var sessionId: String? = null

    /** 旧版 SSE 握手拿到的 POST 端点；null 表示还没握手成功。 */
    @Volatile
    private var sseEndpoint: String? = null

    /** JSON-RPC 请求 id 自增；initialize 用 1，后面顺延。 */
    private val requestIds = AtomicInteger(0)

    // ---- 对外 ----

    /**
     * 握手：POST initialize，记住 Mcp-Session-Id，再发一条无 id 的 notifications/initialized。
     * 成功后这个 client 才能 listTools / callTool。
     */
    override suspend fun initialize(): Result<Unit> = guarded("初始化") {
        // 旧版 SSE：先把 POST 端点问出来（失败会退化成直接 POST url）
        if (server.isSse) ensureSseEndpoint()

        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put(
                "clientInfo",
                JSONObject().put("name", CLIENT_NAME).put("version", CLIENT_VERSION)
            )
        val result = rpc(METHOD_INITIALIZE, params)
        val serverName = result.optJSONObject("serverInfo")?.optString("name", "").orEmpty()
        val protocol = result.optString("protocolVersion", "").trim()
        FileLog.log(
            TAG,
            "服务「" + server.name + "」初始化成功" +
                (if (serverName.isNotEmpty()) "（" + serverName + "）" else "") +
                (if (protocol.isNotEmpty()) "，协议 " + protocol else "") +
                "，transport=" + server.transport
        )
        // 通知没有 id、也不该等响应；失败只记日志，不能把初始化判死
        notify(METHOD_INITIALIZED)
    }

    /** tools/list：解析每个工具的名字、描述、inputSchema。 */
    override suspend fun listTools(): Result<List<McpToolInfo>> = guarded("获取工具列表") {
        val result = rpc(METHOD_LIST_TOOLS, JSONObject())
        val array = result.optJSONArray("tools")
            ?: throw IllegalStateException("返回里没有 tools 数组")
        val infos = ArrayList<McpToolInfo>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").trim()
            if (name.isEmpty()) continue
            // inputSchema 原样保留：模型要的是原始 JSON Schema，这里不解析不改写
            val schema = obj.opt("inputSchema")
            val schemaJson = if (schema == null || schema === JSONObject.NULL) "" else schema.toString()
            infos.add(
                McpToolInfo(
                    name = name,
                    description = obj.optString("description", "").trim(),
                    inputSchemaJson = schemaJson
                )
            )
        }
        FileLog.log(TAG, "服务「" + server.name + "」有 " + infos.size + " 个工具")
        infos
    }

    /**
     * tools/call：把 arguments 原样发给服务端，把 result.content 里 type=="text" 的 text 拼起来返回。
     * isError==true 时返回 Result.failure，但把服务端给的文本也带上（模型据此能自己改参数）。
     */
    override suspend fun callTool(name: String, argumentsJson: String): Result<String> = guarded("调用工具 " + name) {
        val arguments = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrElse {
            throw IllegalStateException("参数不是合法 JSON：" + argumentsJson.take(MAX_ERROR_CHARS))
        }
        val params = JSONObject().put("name", name).put("arguments", arguments)
        val result = rpc(METHOD_CALL_TOOL, params)
        val text = extractText(result)
        if (result.optBoolean("isError", false)) {
            throw IllegalStateException("工具 " + name + " 返回错误：" + text.ifBlank { "（服务端没给错误文本）" })
        }
        text
    }

    // ---- JSON-RPC ----

    /** 发一次带 id 的请求，返回 result 对象；服务端报 error 就抛出可读原因。 */
    private fun rpc(method: String, params: JSONObject?): JSONObject {
        val payload = JSONObject()
            .put("jsonrpc", JSON_RPC_VERSION)
            .put("id", requestIds.incrementAndGet())
            .put("method", method)
        if (params != null) payload.put("params", params)

        val envelope = post(payload)
            ?: throw IllegalStateException("服务端没有返回内容（" + method + "）")
        val error = envelope.optJSONObject("error")
        if (error != null) {
            throw IllegalStateException(
                "JSON-RPC 错误 " + error.optInt("code", 0) + "：" + error.optString("message", "未知错误")
            )
        }
        return envelope.optJSONObject("result") ?: JSONObject()
    }

    /** 发一条通知（无 id、不等响应）；失败只记日志。 */
    private fun notify(method: String) {
        val payload = JSONObject().put("jsonrpc", JSON_RPC_VERSION).put("method", method)
        try {
            post(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLog.log(TAG, "通知 " + method + " 发送失败（忽略）：" + describe(e))
        }
    }

    /** POST 一次 JSON-RPC；返回解析后的响应对象（通知这类无响应的是 null）。 */
    private fun post(payload: JSONObject): JSONObject? {
        val builder = Request.Builder()
            .url(targetUrl())
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            // 新版传输允许服务端按 SSE 回包，所以两种都声明可接受
            .header("Accept", ACCEPT)
        // 用户配置的头优先于默认头；会话 id 用服务端给的最新值
        for ((key, value) in server.headers) {
            if (key.isNotBlank()) builder.header(key.trim(), value)
        }
        sessionId?.takeIf { it.isNotBlank() }?.let { builder.header(HEADER_SESSION, it) }

        client.newCall(builder.build()).execute().use { response ->
            response.header(HEADER_SESSION)?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = text.trim().take(MAX_ERROR_CHARS).replace("\n", " ")
                throw IllegalStateException(
                    "HTTP " + response.code + " " + response.message +
                        (if (detail.isEmpty()) "" else "：" + detail)
                )
            }
            return parseBody(text)
        }
    }

    /** 当前该 POST 到哪：sse 传输且握手成功就用端点，否则用配置里的 url。 */
    private fun targetUrl(): String {
        if (!server.isSse) return server.url
        return sseEndpoint ?: server.url
    }

    // ---- 响应解析 ----

    /**
     * 统一解析响应正文：
     * 1. 先按纯 JSON 试（绝大多数情况）；
     * 2. 不行再按 SSE 事件流拆（只看 data: 行，挑第一条带 result/error 的 JSON-RPC 响应）。
     */
    private fun parseBody(text: String): JSONObject? {
        val body = text.trim()
        if (body.isEmpty()) return null
        // 先按纯 JSON 试（绝大多数服务端就是一次性 JSON 响应）
        val plain = try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
        if (plain != null) return plain
        // 再按 SSE 事件流拆（content-type 不可信，按正文形态判断更稳）
        parseSse(body)?.let { return it }
        throw IllegalStateException(
            "响应既不是 JSON 也不是 SSE：" + body.take(MAX_ERROR_CHARS).replace("\n", " ")
        )
    }

    /** 拆 SSE：eventName 用来跳过 endpoint 事件，data 的负载才是 JSON-RPC 响应。 */
    private fun parseSse(body: String): JSONObject? {
        var fallback: JSONObject? = null
        var eventName = ""
        val normalized = body.replace("\r\n", "\n").replace('\r', '\n')
        for (raw in normalized.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) {
                eventName = ""
                continue
            }
            if (line.startsWith("event:")) {
                eventName = line.removePrefix("event:").trim()
                continue
            }
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == SSE_DONE || eventName == "endpoint") continue
            val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
            // 有的服务端会先推一堆通知，真正的响应带 result/error
            if (obj.has("result") || obj.has("error")) return obj
            if (fallback == null) fallback = obj
        }
        return fallback
    }

    /** 拼 result.content 里所有 type=="text" 的文本。 */
    private fun extractText(result: JSONObject): String {
        val array = result.optJSONArray("content") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (!item.optString("type", "").equals("text", ignoreCase = true)) continue
            val text = item.optString("text", "")
            if (text.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(text)
        }
        return builder.toString()
    }

    // ---- 旧版 SSE 握手 ----

    /**
     * GET url 读事件流，取 event: endpoint 的 data 作为 POST 地址。
     *
     * 只读有限行（[MAX_SSE_HANDSHAKE_LINES]）就收手；握手阶段用更短的读超时，
     * 免得服务端一直不推 endpoint 时把整个 reload 卡 60 秒。
     * 任何失败都只记日志：退化成直接 POST 配置里的 url（很多「sse」配置其实也能吃 POST）。
     */
    private fun ensureSseEndpoint() {
        if (sseEndpoint != null) return
        // 握手读了多少行要跨 use 块记日志，所以放在 try 外面
        var lines = 0
        try {
            val builder = Request.Builder()
                .url(server.url)
                .header("Accept", "text/event-stream")
                .get()
            for ((key, value) in server.headers) {
                if (key.isNotBlank()) builder.header(key.trim(), value)
            }
            val handshakeClient = client.newBuilder()
                .readTimeout(SSE_HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(SSE_HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

            handshakeClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP " + response.code + " " + response.message)
                }
                val stream = response.body?.byteStream()
                    ?: throw IllegalStateException("响应体为空")
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var eventName = ""
                    while (lines < MAX_SSE_HANDSHAKE_LINES) {
                        val line = (reader.readLine() ?: break).trim()
                        lines++
                        if (line.isEmpty()) {
                            eventName = ""
                            continue
                        }
                        if (line.startsWith("event:")) {
                            eventName = line.removePrefix("event:").trim()
                            continue
                        }
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        // 规范里 endpoint 事件的 data 就是 POST 地址；有的服务端干脆只推一个 /xxx 路径
                        if (eventName == "endpoint" || data.startsWith("/") ||
                            data.startsWith("http://", true) || data.startsWith("https://", true)
                        ) {
                            sseEndpoint = resolveEndpoint(server.url, data)
                            FileLog.log(TAG, "服务「" + server.name + "」的 SSE 端点：" + sseEndpoint)
                            return
                        }
                    }
                }
            }
            FileLog.log(
                TAG,
                "服务「" + server.name + "」的 SSE 事件流里没有 endpoint 事件（读了 " +
                    lines + " 行），退化为直接 POST " + server.url
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 退化路径：不抛，让 initialize 继续试 POST url
            FileLog.log(
                TAG,
                "服务「" + server.name + "」的 SSE 握手失败（" + describe(e) + "），退化为直接 POST " + server.url
            )
        }
    }

    /** 把 SSE endpoint 里的相对地址补成绝对地址（"http(s)://…" 直接用）。 */
    private fun resolveEndpoint(base: String, target: String): String {
        val trimmed = target.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) return trimmed
        // 取 scheme://host[:port] 这一段
        val pathStart = base.indexOf('/', schemeEnd + 3)
        val origin = if (pathStart < 0) base else base.substring(0, pathStart)
        if (trimmed.startsWith("/")) return origin + trimmed
        val dirEnd = base.lastIndexOf('/')
        val dir = if (dirEnd > schemeEnd + 2) base.substring(0, dirEnd) else origin
        return dir + "/" + trimmed
    }

    // ---- 错误包装 ----

    /** 统一把异常折成 Result.failure，message 是可读中文；取消原样抛出。 */
    private suspend fun <T> guarded(action: String, block: suspend () -> T): Result<T> = try {
        Result.success(withContext(Dispatchers.IO) { block() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(IllegalStateException("MCP「" + server.name + "」" + action + "失败：" + describe(e), e))
    }

    private fun describe(e: Throwable): String =
        (e.message ?: e.javaClass.simpleName).take(MAX_ERROR_CHARS)

    private companion object {
        const val TAG = "mcp"

        const val JSON_RPC_VERSION = "2.0"
        const val PROTOCOL_VERSION = "2024-11-05"
        const val CLIENT_NAME = "mingent"
        const val CLIENT_VERSION = "1.1.0"

        const val METHOD_INITIALIZE = "initialize"
        const val METHOD_INITIALIZED = "notifications/initialized"
        const val METHOD_LIST_TOOLS = "tools/list"
        const val METHOD_CALL_TOOL = "tools/call"

        const val HEADER_SESSION = "Mcp-Session-Id"
        const val ACCEPT = "application/json, text/event-stream"
        const val SSE_DONE = "[DONE]"

        const val TIMEOUT_SECONDS = 60L
        const val SSE_HANDSHAKE_TIMEOUT_SECONDS = 15L
        const val MAX_SSE_HANDSHAKE_LINES = 200
        const val MAX_ERROR_CHARS = 300

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
