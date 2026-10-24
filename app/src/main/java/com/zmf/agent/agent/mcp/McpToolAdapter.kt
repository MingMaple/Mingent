package com.zmf.agent.agent.mcp

import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolResult
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 把一个 MCP 远程工具包装成本地 [Tool]，这样它能直接进 ToolRegistry、被模型按同一套 schema 调用。
 *
 * 命名规则：mcp__<服务名>__<工具名>，两段都只保留 [A-Za-z0-9_-]，其它字符换成 _。
 * 前缀 mcp__ 保证不和内置工具撞名；服务名参与命名，两个 MCP 服务有同名工具也不会打架。
 * 名字超过 64 字符时截断前缀并接 12 位 sha256，保证唯一且不超模型 API 的长度上限。
 *
 * 权限档固定 WORKSPACE：MCP 工具能对远端/本机做什么我们不知道，不能给只读档的自在。
 * client 用 () -> McpTransport 而不是直接实例：McpManager 重连时会换新 client，适配器要拿到当前的；
 * 类型是接口而不是 McpClient，因为 stdio 传输走的是 McpStdioClient。
 */
class McpToolAdapter(
    private val server: McpServer,
    private val info: McpToolInfo,
    private val client: () -> McpTransport
) : Tool {

    override val name: String = buildName(server.name, info.name)

    override val description: String =
        ("[MCP:" + server.name + "] " + info.description.replace("\r", " ").replace("\n", " ")).take(MAX_DESCRIPTION_CHARS)

    /** 服务端给的 inputSchema 直接用；没给就退化成「无参数的 object」。 */
    override val parametersJson: String =
        info.inputSchemaJson.trim().ifEmpty { EMPTY_SCHEMA }

    override val minPermission = PermissionLevel.WORKSPACE

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        // 模型给的就是 JSON，原样序列化发出去，不在这里做二次校验（远端 schema 才是权威）
        val arguments = args.toString()
        return client().callTool(info.name, arguments).fold(
            onSuccess = { text ->
                if (text.isBlank()) ToolResult.Ok("（" + info.name + " 执行完成，没有返回文本内容）") else ToolResult.Ok(text)
            },
            onFailure = { error -> ToolResult.Error(error.message ?: "调用 MCP 工具失败") }
        )
    }

    private companion object {
        /** 模型 API 对工具名的长度上限是 64。 */
        const val MAX_NAME_CHARS = 64

        /** 尾部哈希长度；前缀按「前 51 位」的规格再压 1 位，让 51+2+12 不越过 64。 */
        const val HASH_CHARS = 12
        const val PREFIX_CHARS = MAX_NAME_CHARS - 2 - HASH_CHARS

        const val MAX_DESCRIPTION_CHARS = 1000
        const val EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}"

        val ILLEGAL = Regex("[^A-Za-z0-9_-]")

        /** 只保留 [A-Za-z0-9_-]，其它字符（中文、空格、点、冒号）统一换成 _。 */
        fun safe(raw: String): String = raw.trim().replace(ILLEGAL, "_")

        fun buildName(serverName: String, toolName: String): String {
            val base = "mcp__" + safe(serverName) + "__" + safe(toolName)
            if (base.length <= MAX_NAME_CHARS) return base
            return base.take(PREFIX_CHARS) + "__" + sha256(base).take(HASH_CHARS)
        }

        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val out = StringBuilder(digest.size * 2)
            for (byte in digest) out.append(HEX[(byte.toInt() shr 4) and 0x0F]).append(HEX[byte.toInt() and 0x0F])
            return out.toString()
        }

        const val HEX = "0123456789abcdef"
    }
}
