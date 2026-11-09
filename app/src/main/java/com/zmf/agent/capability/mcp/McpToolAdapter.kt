package com.zmf.agent.capability.mcp

import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 把一个 MCP 远程工具包装成本地 [Tool]，这样它能直接进 ToolRegistry、被模型按同一套 schema 调用。
 *
 * 命名规则：mcp__<服务名>__<工具名>，两段都只保留 [A-Za-z0-9_-]，其它字符换成 _。
 * 前缀 mcp__ 保证不与内置工具重名；服务名参与命名，两个 MCP 服务存在同名工具也不会冲突。
 * 名字超过 64 字符时截断前缀并接 12 位 sha256，保证唯一且不超模型 API 的长度上限。
 *
 * 权限档固定为 WORKSPACE：MCP 工具对远端与本机的影响范围不可知，不能在只读档位开放。
 * client 使用 () -> McpTransport 而非直接实例：McpManager 重连时会替换 client，适配器需获取当前实例；
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

        /** 尾部哈希长度；前缀取「64 位上限 − 2 个分隔符 − 12 位哈希 = 50 位」。 */
        const val HASH_CHARS = 12
        const val PREFIX_CHARS = MAX_NAME_CHARS - 2 - HASH_CHARS

        const val MAX_DESCRIPTION_CHARS = 1000
        const val EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}"

        val ILLEGAL = Regex("[^A-Za-z0-9_-]")

        /** 只保留 [A-Za-z0-9_-]，其它字符（中文、空格、点、冒号）统一换成 _。 */
        fun safe(raw: String): String = raw.trim().replace(ILLEGAL, "_")

        /** 拼注册名：mcp__服务__工具；超长时保留前缀并追加内容哈希作为后缀，避免与其他工具重名。 */
        fun buildName(serverName: String, toolName: String): String {
            val base = "mcp__" + safe(serverName) + "__" + safe(toolName)
            if (base.length <= MAX_NAME_CHARS) return base
            return base.take(PREFIX_CHARS) + "__" + sha256(base).take(HASH_CHARS)
        }

        /** 十六进制摘要，只给超长工具名做稳定后缀，不做安全用途。 */
        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val out = StringBuilder(digest.size * 2)
            for (byte in digest) out.append(HEX[(byte.toInt() shr 4) and 0x0F]).append(HEX[byte.toInt() and 0x0F])
            return out.toString()
        }

        const val HEX = "0123456789abcdef"
    }
}
