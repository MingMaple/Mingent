package com.zmf.agent.tools.web

import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 抓网页：GET 一次，把 HTML 正文压成纯文本给模型看。
 *
 * 只做静态抓取（不跑 JS、不装 WebView），所以 SPA 页面可能只拿到一具空壳；
 * 正文提取是最小实现：丢 script/style/noscript/注释，块级标签换行，抹掉剩余标签，反转义常见实体。
 *
 * 两个硬约束：
 * - 响应体最多读 [MAX_BODY_BYTES] 字节，超大页面不能直接 body.string()，否则内存会被吃光；
 * - 返回正文按 max_chars 截断（默认 8000，上限 30000），避免把模型上下文撑爆。
 *
 * 网络只在 [Dispatchers.IO] 上做，不碰本地文件，所以权限档是 READ_ONLY。
 */
class WebTool : Tool {

    override val name = "web_fetch"

    override val description =
        "抓取一个网页，把 HTML 正文转成纯文本返回；只取静态 HTML，纯 JS 渲染的页面可能没有正文。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "url": {"type": "string", "description": "要抓取网页的地址，必须以 http:// 或 https:// 开头"},
            "max_chars": {"type": "number", "description": "返回正文最多多少字符，默认 8000，范围 500-30000"}
          },
          "required": ["url"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    // 和 DeepSeekProvider 一样自己建 client；网页服务器普遍比模型网关慢，超时给 20 秒
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val url = args.optString("url", "").trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return ToolResult.Error("url 必须是 http:// 或 https:// 开头的地址")
        }
        val maxChars = args.optInt("max_chars", DEFAULT_MAX_CHARS).coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)

        return try {
            withContext(Dispatchers.IO) { fetch(url, maxChars) }
        } catch (e: CancellationException) {
            // 用户点了「停止」：必须原样抛出，否则取消渗不下去
            throw e
        } catch (e: Exception) {
            ToolResult.Error("抓取失败：" + (e.message?.take(MAX_REASON_CHARS) ?: e.javaClass.simpleName))
        }
    }

    private fun fetch(url: String, maxChars: Int): ToolResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,application/json;q=0.9,*/*;q=0.8")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code !in 200..299) {
                return ToolResult.Error("HTTP " + response.code + "：" + response.message.ifBlank { "未知状态" })
            }
            val body = response.body ?: return ToolResult.Error("抓取失败：响应体为空")
            val raw = readTextLimited(body)

            val contentType = body.contentType()?.toString().orEmpty().lowercase()
            val plain = if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                htmlToText(raw)
            } else {
                // text/plain、application/json 之类直接当正文
                raw.trim()
            }

            // 跳转后的最终地址（OkHttp 默认跟随重定向），让模型知道内容来自哪里
            val header = "HTTP " + response.code + " · " + response.request.url + "\n\n"
            if (plain.isEmpty()) {
                return ToolResult.Ok(header + "(没有提取到正文，可能是纯 JS 渲染的页面)")
            }

            val text = if (plain.length > maxChars) {
                plain.take(maxChars) + "\n…（已截断，原文本 " + plain.length + " 字符）"
            } else {
                plain
            }
            return ToolResult.Ok(header + text)
        }
    }

    /** 最多读 [MAX_BODY_BYTES] 字节再按 UTF-8 解码；多出来的部分直接不读，连接随 response 关闭。 */
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

    /** HTML→纯文本：只用正则，不引 jsoup 之类的依赖。 */
    private fun htmlToText(html: String): String {
        var text = html
        // 1. 整段丢掉脚本、样式、noscript 回退内容和注释（含标签本身）
        text = DROP_BLOCKS.replace(text, "\n")
        // 2. 块级标签换成换行，保住段落结构
        text = BLOCK_TAGS.replace(text, "\n")
        // 3. 剩下的行内标签直接抹掉
        text = ANY_TAG.replace(text, "")
        // 4. 反转义常见实体（&amp; 放最后，避免 &amp;lt; 被二次解成 <）
        text = unescapeEntities(text)

        // 5. 逐行 trim，连续空行最多保留一个
        val builder = StringBuilder(text.length)
        var lastBlank = true
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (!lastBlank) {
                    builder.append('\n')
                    lastBlank = true
                }
            } else {
                builder.append(trimmed).append('\n')
                lastBlank = false
            }
        }
        return builder.toString().trim()
    }

    private fun unescapeEntities(html: String): String = html
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private companion object {
        const val DEFAULT_MAX_CHARS = 8000
        const val MIN_MAX_CHARS = 500
        const val MAX_MAX_CHARS = 30000
        const val MAX_BODY_BYTES = 2_000_000L
        const val MAX_REASON_CHARS = 200
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) zmf-agent/0.1"

        val DROP_BLOCKS = Regex(
            "<script\\b[^>]*>.*?</script>|<style\\b[^>]*>.*?</style>|<noscript\\b[^>]*>.*?</noscript>|<!--.*?-->",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val BLOCK_TAGS = Regex(
            "</?\\s*(?:br|p|div|li|tr|h[1-6]|section|article|header|footer)\\b[^>]*>",
            RegexOption.IGNORE_CASE
        )

        val ANY_TAG = Regex("<[^>]+>")
    }
}
