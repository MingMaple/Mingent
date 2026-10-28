package com.zmf.agent.tools.web

import com.zmf.agent.agent.ApiConfig
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * 联网检索：借官方「服务端 web_search 工具」拿结果，不在本地抓搜索页。
 *
 * 为什么不自建搜索：结果页普遍要 JS 和反爬，域名与页面结构还一天一变；
 * 官方这条通道（Anthropic Messages 兼容 + tools:[{type:"web_search_20250305"}]）
 * 实测直接返回 title / url / 被引用的段落，稳定得多。本工具只做
 * 「并发发 N 条 query → 合并去重 → 压成短文本」；网页正文仍归 web_fetch，职责不重叠。
 *
 * 三个硬约束：
 * - 一条 query 一次请求，coroutineScope + async 并发：串行会把 60 秒超时叠成 4 倍等待；
 * - 超时只能靠 OkHttp 的 callTimeout 掐断：阻塞在 socket 读上的调用，协程超时打断不了；
 * - 没有 web_search_tool_result 块、或结果为空一律报错：静默返回空文本，模型会当成「网上没有」。
 */
class WebSearchTool : Tool {

    override val name = "web_search"

    override val description =
        "联网检索。一次可给 1-4 个检索词（并发请求后合并去重），每条返回标题、链接和被引用的摘要段落；" +
            "需要最新信息、具体事实或多个来源对照时用它。它只给结果列表，要网页正文得再用 web_fetch 抓链接。" +
            "已知确切网址就直接 web_fetch，不要先搜；同一个问题不要反复搜，换了说法再搜一次仍没有就说明情况。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "queries": {
              "type": "array",
              "items": {"type": "string"},
              "description": "检索词，1-4 个；多个会并发请求，结果按 url 去重合并"
            },
            "max_results": {"type": "integer", "description": "最多返回多少条结果，默认 8，上限 8"}
          },
          "required": ["queries"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val raw = args.optJSONArray("queries")
            ?: return ToolResult.Error("缺少参数 queries：字符串数组，1-" + MAX_QUERIES + " 个检索词")
        // 空串和重复词直接丢掉：多花一次请求换回一模一样的结果没有意义
        val queries = ArrayList<String>(raw.length())
        for (i in 0 until raw.length()) {
            val query = strAt(raw, i).trim()
            if (query.isEmpty()) continue
            if (queries.none { it.equals(query, ignoreCase = true) }) queries.add(query)
        }
        if (queries.isEmpty()) return ToolResult.Error("queries 里没有有效检索词")
        if (queries.size > MAX_QUERIES) {
            return ToolResult.Error(
                "queries 最多 " + MAX_QUERIES + " 个，这次给了 " + queries.size + " 个；请拆成几次调用，" +
                    "一次塞太多词反而会把结果的针对性摊薄"
            )
        }
        val maxResults = args.optInt("max_results", DEFAULT_MAX_RESULTS).coerceIn(1, MAX_RESULTS)

        val endpoint = ApiConfig.loadSearchBaseUrl(ctx.context).trimEnd('/') + "/messages"
        val apiKey = ApiConfig.loadApiKey(ctx.context)

        return try {
            coroutineScope {
                val started = System.currentTimeMillis()
                val outcomes = queries.map { query ->
                    async(Dispatchers.IO) { query to runCatching { search(endpoint, apiKey, query) } }
                }.awaitAll()
                val elapsed = System.currentTimeMillis() - started

                // 一条 query 失败就整次报错：只回半截结果，模型会误判成「网上只有这些」
                val failed = outcomes.firstOrNull { it.second.isFailure }
                if (failed != null) {
                    val reason = describe(failed.second.exceptionOrNull())
                    FileLog.log(TAG, "检索失败（" + endpoint + "）：query「" + failed.first + "」" + reason)
                    return@coroutineScope ToolResult.Error(
                        "检索失败（端点 " + endpoint + "）：query「" + failed.first + "」" + reason
                    )
                }

                // 按 url 去重：同一条结果常在不同 query 里重复出现；LinkedHashMap 保住「先出现者优先」
                val byUrl = LinkedHashMap<String, Hit>()
                val answers = ArrayList<String>()
                for ((_, outcome) in outcomes) {
                    val parsed = outcome.getOrThrow()
                    for (hit in parsed.hits) {
                        if (!byUrl.containsKey(hit.url)) byUrl[hit.url] = hit
                    }
                    if (parsed.answer.isNotBlank()) answers.add(parsed.answer)
                }
                val all = byUrl.values.toList()
                val shown = all.take(maxResults)
                FileLog.log(
                    TAG,
                    "检索 " + queries.size + " 条 query、" + elapsed + "ms，命中 " + all.size +
                        " 条，返回 " + shown.size + " 条"
                )

                val text = format(queries, mergeAnswer(answers), shown, all.size, maxResults)
                if (text.length > MAX_TEXT_CHARS) {
                    // 父会话以后会把超长输出交给 SpillStore 落盘；这里先截断，保证单条工具消息不失控
                    ToolResult.Ok(
                        text.take(MAX_TEXT_CHARS) +
                            "\n…（已截断：原始输出 " + text.length + " 字符，只保留前 " + MAX_TEXT_CHARS + " 字符）"
                    )
                } else {
                    ToolResult.Ok(text)
                }
            }
        } catch (e: CancellationException) {
            // 用户点了「停止」：必须原样抛出，否则取消渗不下去
            throw e
        } catch (e: Exception) {
            // 参数/解析层面的意外（例如端点回了非 JSON）也要落成 Error，带端点方便排查
            FileLog.log(TAG, "检索异常（" + endpoint + "）：" + describe(e))
            ToolResult.Error("检索失败（端点 " + endpoint + "）：" + describe(e))
        }
    }

    /** 发一条 query 的请求，返回该 query 的结果与 answer（命中的结果可能为空）。失败一律抛 [SearchException]。 */
    private fun search(endpoint: String, apiKey: String, query: String): Parsed {
        val request = Request.Builder()
            .url(endpoint)
            // 官方端点两套鉴权头都认（x-api-key 与 Bearer），同时带上，换中转站时少一类 401
            .header("x-api-key", apiKey)
            .header("authorization", "Bearer " + apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .post(buildBody(query).toString().toRequestBody(JSON))
            .build()

        CLIENT.newCall(request).execute().use { response ->
            val text = response.body?.let { readTextLimited(it) }.orEmpty()
            if (response.code !in 200..299) {
                throw SearchException(
                    "HTTP " + response.code + "：" + response.message.ifBlank { "未知状态" } + bodyHint(text)
                )
            }
            if (text.isBlank()) throw SearchException("响应体为空")
            val root = runCatching { JSONObject(text) }.getOrElse {
                throw SearchException("响应不是合法 JSON：" + text.take(MAX_REASON_CHARS))
            }
            return parse(root)
        }
    }

    private fun buildBody(query: String): JSONObject = JSONObject()
        .put("model", ApiConfig.SEARCH_MODEL)
        .put("max_tokens", MAX_TOKENS)
        .put(
            "messages",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "text")
                                .put("text", "Perform a web search for the query: " + query)
                        )
                    )
            )
        )
        .put(
            "tools",
            JSONArray().put(
                JSONObject()
                    .put("type", "web_search_20250305")
                    .put("name", "web_search")
                    .put("max_uses", MAX_USES)
            )
        )

    /**
     * 解析 content[]：结果块（web_search_tool_result）给标题和链接；正文块（text）的正文拼成 answer，
     * 它自带的 citations 给被引用段落。两者可能分属不同元素，所以先分别收集、最后按 url 合并。
     *
     * 为什么必须读正文块：实测这个端点的 citations 恒为空，只取 cited_text 一条摘要都拿不到。
     */
    private fun parse(root: JSONObject): Parsed {
        val content = root.optJSONArray("content")
            ?: throw SearchException("响应里没有 content 字段" + rootHint(root))

        val snippets = HashMap<String, String>()
        val hits = ArrayList<Hit>()
        val answers = ArrayList<String>()
        var sawResultBlock = false

        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (str(block, "type")) {
                "web_search_tool_result" -> {
                    sawResultBlock = true
                    val results = block.optJSONArray("content")
                    if (results == null) {
                        // 检索本身失败时，服务端在这里回一个对象：{type:"web_search_tool_result_error", error_code}
                        val error = block.optJSONObject("content")
                        val code = if (error == null) {
                            "web_search_tool_result_error"
                        } else {
                            str(error, "error_code").ifBlank { "web_search_tool_result_error" }
                        }
                        throw SearchException("检索没成功，服务端返回 " + code)
                    }
                    for (j in 0 until results.length()) {
                        val item = results.optJSONObject(j) ?: continue
                        if (str(item, "type") != "web_search_result") continue
                        val url = str(item, "url").trim()
                        if (url.isEmpty()) continue
                        hits.add(Hit(str(item, "title").trim(), url))
                    }
                }

                "text" -> {
                    // 模型把检索结论写在这段正文里：它才是端点的「回答」，citations 只是额外给的引用切片
                    val body = str(block, "text").trim()
                    if (body.isNotEmpty()) answers.add(body)
                    val citations = block.optJSONArray("citations") ?: continue
                    for (j in 0 until citations.length()) {
                        val citation = citations.optJSONObject(j) ?: continue
                        val url = str(citation, "url").trim()
                        val cited = str(citation, "cited_text")
                        if (url.isEmpty() || cited.isBlank()) continue
                        // 同一条结果被引用多次时，取第一次命中的段落
                        if (!snippets.containsKey(url)) snippets[url] = compress(cited)
                    }
                }
            }
        }

        if (!sawResultBlock) throw SearchException("响应里没有 web_search_tool_result 块" + rootHint(root))
        if (hits.isEmpty()) throw SearchException("检索没有返回任何结果")
        return Parsed(
            hits = hits.map { hit -> hit.copy(snippet = snippets[hit.url].orEmpty()) },
            answer = mergeAnswer(answers)
        )
    }

    /** 多个正文块接成一段：抹平空白，再按上限截断，免得复述把结果列表挤出 6000 字之外。 */
    private fun mergeAnswer(blocks: List<String>): String {
        if (blocks.isEmpty()) return ""
        val flat = blocks.joinToString(" ").replace(WHITESPACE, " ").trim()
        return if (flat.length > MAX_ANSWER_CHARS) flat.take(MAX_ANSWER_CHARS) + "…" else flat
    }

    /**
     * 先给整段 answer（真没有就明写「检索未给摘要」，免得模型把「没摘要」读成「网上没这回事」），
     * 再一行一条结果：标题 / 链接 / 摘要；摘要为空就只留前两段，不留占位符。
     */
    private fun format(
        queries: List<String>,
        answer: String,
        hits: List<Hit>,
        total: Int,
        maxResults: Int
    ): String = buildString {
        append("queries: ").append(queries.joinToString(", ")).append('\n')
        append("answer: ").append(answer.ifBlank { "（检索未给摘要）" }).append('\n')
        for (hit in hits) {
            append("- ").append(hit.title.ifBlank { "(无标题)" })
            append(" / ").append(hit.url)
            if (hit.snippet.isNotBlank()) append(" / ").append(hit.snippet)
            append('\n')
        }
        if (total > hits.size) {
            append("（合并去重后共 ").append(total).append(" 条，按 max_results=")
                .append(maxResults).append(" 只返回前 ").append(hits.size).append(" 条）")
        }
    }.trimEnd()

    /** 最多读 [MAX_BODY_BYTES] 字节：端点异常时可能回超大页面，直接 string() 会吃掉内存。 */
    private fun readTextLimited(body: ResponseBody): String {
        val declared = body.contentLength()
        val limit = if (declared in 1L..MAX_BODY_BYTES) declared else MAX_BODY_BYTES
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        body.byteStream().use { input ->
            var total = 0L
            while (total < limit) {
                val want = minOf(buffer.size.toLong(), limit - total).toInt()
                val n = input.read(buffer, 0, want)
                if (n <= 0) break
                out.write(buffer, 0, n)
                total += n
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** 引用段落常带换行和连续空格，压成一行，免得一条结果的摘要把整个版面撑歪。 */
    private fun compress(text: String): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        return if (flat.length > MAX_SNIPPET_CHARS) flat.take(MAX_SNIPPET_CHARS) + "…" else flat
    }

    /** HTTP 失败时附一小段响应体：官方 4xx 的 error.message 是排查唯一线索。 */
    private fun bodyHint(text: String): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        return if (flat.isEmpty()) "" else "；响应：" + flat.take(MAX_REASON_CHARS)
    }

    /** 顶层 error 字段（限额用尽、模型不支持 tools 等）尽量带出来。 */
    private fun rootHint(root: JSONObject): String {
        val error = root.optJSONObject("error") ?: return ""
        val message = str(error, "message").ifBlank { str(error, "type") }
        return if (message.isBlank()) "" else "：" + message.take(MAX_REASON_CHARS)
    }

    private fun describe(e: Throwable?): String {
        if (e == null) return "未知原因"
        val message = e.message.orEmpty()
        // OkHttp 的 callTimeout 只抛一个 InterruptedIOException("timeout")，直接回给模型看不懂
        if (e is InterruptedIOException) {
            val detail = if (message.isBlank()) "" else "：" + message
            return "超时（单次请求上限 " + TIMEOUT_SECONDS + " 秒）" + detail
        }
        return message.ifBlank { e.javaClass.simpleName }
    }

    /** org.json 的 optString 遇到 JSON null 会返回字符串 "null"，必须先 isNull 挡掉。 */
    private fun str(obj: JSONObject, key: String): String = if (obj.isNull(key)) "" else obj.optString(key, "")

    private fun strAt(array: JSONArray, index: Int): String =
        if (array.isNull(index)) "" else array.optString(index, "")

    /** 一条检索结果；snippet 要等解析完 citations 才知道，所以允许后补。 */
    private data class Hit(val title: String, val url: String, val snippet: String = "")

    /** 一次响应的两半：结果列表 + 正文块拼出的 answer。 */
    private data class Parsed(val hits: List<Hit>, val answer: String)

    /** 本工具自己判定的失败（HTTP 非 2xx、响应结构不对），message 就是给模型看的原因。 */
    private class SearchException(message: String) : Exception(message)

    private companion object {
        const val TAG = "websearch"

        /** 一条 query 一次请求；超过 4 条就变成烧配额，也让结果很难收敛。 */
        const val MAX_QUERIES = 4

        const val DEFAULT_MAX_RESULTS = 8
        const val MAX_RESULTS = 8

        /** 服务端让模型调用检索工具的次数上限，和接口里的 max_uses 对齐。 */
        const val MAX_USES = 5
        const val MAX_TOKENS = 4096

        const val ANTHROPIC_VERSION = "2023-06-01"

        /** 单次检索返回的正文上限：超了截断并注明（SpillStore 接上后由它落盘）。 */
        const val MAX_TEXT_CHARS = 6000

        /** answer 压成一段且不超过 1200 字：再长基本都是复述，会把结果列表顶出总长上限。 */
        const val MAX_ANSWER_CHARS = 1200

        const val MAX_SNIPPET_CHARS = 300
        const val MAX_REASON_CHARS = 200
        const val MAX_BODY_BYTES = 2_000_000L

        val WHITESPACE = Regex("\\s+")

        val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * 单次请求上限：官方检索通常 5-15 秒返回；因为 4 条 query 是并发的，
         * 整次调用的最坏等待也就这 60 秒，不会乘 4。
         */
        const val TIMEOUT_SECONDS = 60L

        /**
         * 全局单例 client：多条 query 共用一个连接池；callTimeout 兜住「连接 + 读完整响应」，
         * 只设 readTimeout 的话，慢速滴流的响应可以一直不读完。
         */
        val CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
