package com.zmf.agent.agent.mcp

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一个 MCP 服务配置。
 *
 * - transport：传输方式，三种取值：
 *   - "http"：新版 Streamable HTTP（POST 一个地址，响应可能是 JSON 也可能是 text/event-stream）；
 *   - "sse"：旧版 HTTP+SSE（先 GET 一条事件流，从 event: endpoint 里拿后续 POST 地址）；
 *   - "stdio"：本地进程（起一条命令，用「一行一个 JSON」的 JSON-RPC 通过 stdin/stdout 对话）。
 * - url：服务地址（http 为 MCP 端点，sse 为事件流地址）；stdio 不用，留空。
 * - headers：附加请求头（认证 token 之类），POST/GET 都会带上；stdio 不用。
 * - command/args/env/cwd：stdio 专用，其它传输为空。
 * - enabled：是否参与连接；关掉就不注册它的任何工具。
 */
data class McpServer(
    val name: String,
    val transport: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /**
     * stdio 专用四件套。默认值全空，所以只有 http/sse 的旧 mcp.json 读进来照样成立。
     *
     * - [command]：可执行名或绝对路径（在 App 内 Ubuntu 里解析，所以 npx / python3 都能直接写）；
     * - [args]：参数数组，一个元素一个参数，不靠空格拆（带空格的参数才不会被打散）；
     * - [env]：追加给子进程的环境变量（KEY=VALUE）；
     * - [cwd]：子进程工作目录（Ubuntu 里的路径，空则 /root）。
     */
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String = ""
) {
    /** 是否旧版 SSE 传输（大小写不敏感）。 */
    val isSse: Boolean get() = transport.equals(TRANSPORT_SSE, ignoreCase = true)

    /** 是否 stdio 传输：本机起一个进程，走 stdin/stdout。 */
    val isStdio: Boolean get() = transport.equals(TRANSPORT_STDIO, ignoreCase = true)

    companion object {
        const val TRANSPORT_HTTP = "http"
        const val TRANSPORT_SSE = "sse"
        const val TRANSPORT_STDIO = "stdio"

        /** 把用户输入/配置文件里的 transport 收敛成三种合法值；认不出来的按 http（历史默认）。 */
        fun normalizeTransport(raw: String): String {
            val value = raw.trim()
            return when {
                value.equals(TRANSPORT_SSE, ignoreCase = true) -> TRANSPORT_SSE
                value.equals(TRANSPORT_STDIO, ignoreCase = true) -> TRANSPORT_STDIO
                else -> TRANSPORT_HTTP
            }
        }
    }
}

/**
 * MCP 服务配置落盘：filesDir/appdata/mcp.json，
 * 结构 {"servers":[{name,transport,url,headers,enabled[,command,args,env,cwd]}]}；
 * 最后四个键只在 transport=="stdio" 时写，旧文件没有它们，读的时候按空值处理。
 *
 * 和 SessionStore / WorkSpaceStore 一样，整个文件一次覆盖写：
 * 服务配置是用户手工填的，几条到几十条，不需要数据库。
 * 所有读写失败都只记日志、不抛异常——配置坏了顶多是「没有 MCP 服务」，不能把 App 弄崩。
 */
object McpServerStore {

    private const val TAG = "mcp"
    private const val FILE_NAME = "mcp.json"

    private const val KEY_SERVERS = "servers"
    private const val KEY_NAME = "name"
    private const val KEY_TRANSPORT = "transport"
    private const val KEY_URL = "url"
    private const val KEY_HEADERS = "headers"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_COMMAND = "command"
    private const val KEY_ARGS = "args"
    private const val KEY_ENV = "env"
    private const val KEY_CWD = "cwd"

    /** 落盘位置：filesDir/appdata/mcp.json。 */
    private fun storeFile(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    /**
     * 读全部服务配置。
     *
     * - 文件不存在 → 空表（用户还没配过）；
     * - JSON 坏掉 / 读盘失败 → 记日志后返回空表，不抛异常；
     * - name 为空的条目直接丢弃：name 是注册表来源名和工具前缀，不能空；
     * - 名字重复的保留第一条（否则两个服务会互相顶掉工具）。
     */
    fun load(context: Context): List<McpServer> {
        val file = storeFile(context)
        if (!file.exists() || !file.isFile) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return emptyList()
            parse(text)
        } catch (e: Exception) {
            FileLog.log(TAG, "读取 MCP 配置失败，按空表处理：" + (e.message ?: e.javaClass.simpleName))
            emptyList()
        }
    }

    /** 整体覆盖写；先写 .tmp 再 rename，避免写到一半被杀留下坏文件。失败只记日志。 */
    fun save(context: Context, list: List<McpServer>) {
        try {
            val array = JSONArray()
            for (server in list) array.put(toJson(server))
            val root = JSONObject().put(KEY_SERVERS, array)
            val json = root.toString()

            val file = storeFile(context)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, FILE_NAME + ".tmp")
            temp.writeText(json, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                // rename 失败（个别文件系统）时退化成直接覆盖，保证内容落地
                file.writeText(json, Charsets.UTF_8)
                temp.delete()
            }
            FileLog.log(TAG, "保存 MCP 配置 " + list.size + " 条")
        } catch (e: Exception) {
            FileLog.log(TAG, "保存 MCP 配置失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 按 name 新增或覆盖，返回保存后的完整列表（方便调用方直接拿去刷新界面）。 */
    fun upsert(context: Context, server: McpServer): List<McpServer> {
        val current = load(context).toMutableList()
        val index = current.indexOfFirst { it.name == server.name }
        if (index >= 0) current[index] = server else current.add(server)
        val list = current.toList()
        save(context, list)
        return list
    }

    /** 按 name 删除，返回保存后的完整列表；名字不存在就当没这回事。 */
    fun remove(context: Context, name: String): List<McpServer> {
        val current = load(context)
        val list = current.filterNot { it.name == name }
        if (list.size != current.size) save(context, list)
        return list
    }

    // ---- 序列化 ----

    private fun toJson(server: McpServer): JSONObject {
        val headers = JSONObject()
        for ((key, value) in server.headers) headers.put(key, value)
        val json = JSONObject()
            .put(KEY_NAME, server.name)
            .put(KEY_TRANSPORT, McpServer.normalizeTransport(server.transport))
            .put(KEY_URL, server.url)
            .put(KEY_HEADERS, headers)
            .put(KEY_ENABLED, server.enabled)
        // stdio 字段只在 stdio 时落盘：http/sse 的条目里塞空数组/空对象纯粹是噪声
        if (McpServer.normalizeTransport(server.transport) == McpServer.TRANSPORT_STDIO) {
            val args = JSONArray()
            for (arg in server.args) args.put(arg)
            val env = JSONObject()
            for ((key, value) in server.env) env.put(key, value)
            json.put(KEY_COMMAND, server.command)
                .put(KEY_ARGS, args)
                .put(KEY_ENV, env)
                .put(KEY_CWD, server.cwd)
        }
        return json
    }

    private fun parse(text: String): List<McpServer> {
        val root = JSONObject(text)
        val array = root.optJSONArray(KEY_SERVERS) ?: return emptyList()
        val out = ArrayList<McpServer>(array.length())
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString(KEY_NAME, "").trim()
            if (name.isEmpty()) {
                FileLog.log(TAG, "MCP 配置第 " + (i + 1) + " 条没有 name，跳过")
                continue
            }
            if (!seen.add(name)) {
                FileLog.log(TAG, "MCP 配置里有重名服务「" + name + "」，只保留第一条")
                continue
            }
            out.add(
                McpServer(
                    name = name,
                    transport = McpServer.normalizeTransport(obj.optString(KEY_TRANSPORT, McpServer.TRANSPORT_HTTP)),
                    url = obj.optString(KEY_URL, "").trim(),
                    headers = readStringMap(obj.optJSONObject(KEY_HEADERS)),
                    enabled = obj.optBoolean(KEY_ENABLED, true),
                    // 旧文件里没有这四个键：全部按空值处理，不报错
                    command = obj.optString(KEY_COMMAND, "").trim(),
                    args = readStringList(obj.optJSONArray(KEY_ARGS)),
                    env = readStringMap(obj.optJSONObject(KEY_ENV)),
                    cwd = obj.optString(KEY_CWD, "").trim()
                )
            )
        }
        return out
    }

    /** headers 和 env 都是「字符串到字符串」，同一份读取逻辑，空键直接丢掉。 */
    private fun readStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null || obj.length() == 0) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (key in obj.keys()) {
            val value = obj.optString(key, "")
            if (key.isNotBlank()) map[key.trim()] = value
        }
        return map
    }

    /** args 数组；非字符串元素（手改坏了）跳过，不让一条脏数据毁掉整份配置。 */
    private fun readStringList(array: JSONArray?): List<String> {
        if (array == null || array.length() == 0) return emptyList()
        val list = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            if (item is String) list.add(item)
        }
        return list
    }
}
