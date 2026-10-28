package com.zmf.agent.agent.mcp

import android.content.Context
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全部 MCP 服务的连接/工具注册总闸。
 *
 * 一个服务的生命周期：读配置（[McpServerStore]）→ initialize → tools/list → 把每个工具包成
 * [McpToolAdapter] 注册进 [ToolRegistry]（来源名「mcp:<服务名>」）。
 *
 * 三条硬规则：
 * 1. **单个服务失败不影响别的服务**：谁失败谁标 FAILED 并记原因，其余的照旧 READY；
 * 2. **重载前先把所有 mcp 来源摘干净**：不然删掉的旧工具会一直留在注册表里；
 * 3. **每个服务复用一个 [McpTransport]**（[McpClient] 或 [McpStdioClient]）：Mcp-Session-Id / 子进程
 *    存在 client 里，重连时复用才不会每次重新握手或重启进程。
 *    服务的 transport/url/headers/命令 变了才换新实例（旧实例随本次 reload 清掉，stdio 的进程要真的杀掉）。
 *
 * 状态用 StateFlow 暴露，包管理页直接 collect 就能实时看进度（CONNECTING → READY/FAILED）。
 */
object McpManager {

    /** 一个服务当前所处的状态。 */
    enum class ServerState { DISABLED, CONNECTING, READY, FAILED }

    /** 给界面看的一条状态：服务配置 + 当前状态 + 工具数 + 说明（失败原因/停用原因）。 */
    data class ServerStatus(
        val server: McpServer,
        val state: ServerState,
        val toolCount: Int = 0,
        val message: String = ""
    )

    private const val TAG = "mcp"

    /** 注册表来源名前缀：mcp:<服务名>。 */
    const val SOURCE_PREFIX = "mcp:"

    private val _status = MutableStateFlow<List<ServerStatus>>(emptyList())

    /** 包管理页观察这个。 */
    val status: StateFlow<List<ServerStatus>> = _status.asStateFlow()

    private val lock = Any()

    /** 复用中的 client，key 见 [clientKey]（配置变了就是新 key，等于换新实例）。 */
    private val clients = LinkedHashMap<String, McpTransport>()

    /** 某个服务在注册表里的来源名。 */
    fun sourceOf(server: McpServer): String = SOURCE_PREFIX + server.name

    /** 取（或建）这个服务的 client；重连复用同一个实例。 */
    private fun clientOf(context: Context, server: McpServer): McpTransport = synchronized(lock) {
        clients.getOrPut(clientKey(server)) { createClient(context, server) }
    }

    /** stdio 是「本机起一条进程」，需要 Context 拿 TerminalManager；其余两种就是 HTTP 客户端。 */
    private fun createClient(context: Context, server: McpServer): McpTransport =
        if (server.isStdio) McpStdioClient(context, server) else McpClient(server)

    /**
     * 重新加载全部 MCP 服务：
     * 1. 先对所有服务（含已从配置里删掉的）unregister("mcp:"+name)；
     * 2. 逐个 enabled 的服务 initialize + listTools，把适配器注册进 registry；
     * 3. 全程更新 [status]，单个失败只影响自己。
     *
     * 由调用方的协程驱动（内部是阻塞网络调用，已切到 IO 线程）。取消会原样抛出。
     */
    suspend fun reload(context: Context, registry: ToolRegistry) {
        val servers = McpServerStore.load(context)

        // 1. 旧来源全部摘掉：配置里删掉的服务也要摘，不能靠新列表漏掉
        val names = HashSet<String>()
        _status.value.forEach { names.add(it.server.name) }
        servers.forEach { names.add(it.name) }
        for (name in names) registry.unregister(SOURCE_PREFIX + name)

        FileLog.log(TAG, "开始加载 MCP 服务：" + servers.size + " 个")

        val results = MutableList(servers.size) { index ->
            val server = servers[index]
            if (server.enabled) {
                ServerStatus(server, ServerState.CONNECTING)
            } else {
                ServerStatus(server, ServerState.DISABLED, 0, "已停用")
            }
        }
        _status.value = results.toList()

        // 2. 逐个连：谁失败只标自己
        val usedKeys = HashSet<String>()
        for ((index, server) in servers.withIndex()) {
            if (!server.enabled) continue
            val client = clientOf(context, server)
            usedKeys.add(clientKey(server))
            results[index] = try {
                connect(context, server, client, registry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerStatus(server, ServerState.FAILED, 0, describe(e))
            }
            // 每连完一个就推一次状态，界面上能看到「谁先连上」
            _status.value = results.toList()
        }

        // 3. 清掉这次没用到的 client（服务被删或配置改了）
        pruneClients(usedKeys)

        FileLog.log(TAG, "MCP 加载完成：" + summarize(results))
    }

    /**
     * 停止全部 MCP：把 "mcp:" 来源的工具全部摘掉，状态置为 DISABLED（配置不动）。
     * 之后想恢复要再调一次 [reload]。
     */
    fun stopAll(registry: ToolRegistry) {
        val names = HashSet<String>()
        _status.value.forEach { names.add(it.server.name) }
        for (name in names) registry.unregister(SOURCE_PREFIX + name)
        if (names.isNotEmpty()) {
            FileLog.log(TAG, "已停止全部 MCP 服务（摘掉 " + names.size + " 个来源）：" + names.joinToString(", "))
        }
        _status.value = _status.value.map {
            it.copy(state = ServerState.DISABLED, toolCount = 0, message = "已停止")
        }
    }

    // ---- 单个服务 ----

    /** initialize + listTools + 注册工具；任何一步失败返回 FAILED 状态（不抛异常）。 */
    private suspend fun connect(
        context: Context,
        server: McpServer,
        client: McpTransport,
        registry: ToolRegistry
    ): ServerStatus {
        client.initialize().exceptionOrNull()?.let { error ->
            return ServerStatus(server, ServerState.FAILED, 0, describe(error))
        }
        val infos = client.listTools().getOrElse { error ->
            return ServerStatus(server, ServerState.FAILED, 0, describe(error))
        }
        // 适配器里用 lambda 取「当前」的 client，重连后不用重建适配器
        val adapters = infos.map { info -> McpToolAdapter(server, info) { clientOf(context, server) } }
        registry.register(SOURCE_PREFIX + server.name, adapters)
        val message = if (adapters.isEmpty()) "已连接，但服务端没有提供工具" else "已连接"
        return ServerStatus(server, ServerState.READY, adapters.size, message)
    }

    // ---- 杂项 ----

    /** client 缓存 key：名字 + 传输 + 地址 + 请求头 + stdio 命令，任一变了就换新实例（旧会话/旧进程不能沿用）。 */
    private fun clientKey(server: McpServer): String =
        server.name + "\u0000" + server.transport.lowercase() + "\u0000" + server.url + "\u0000" +
            server.headers.toSortedMap().toString() + "\u0000" + server.command + "\u0000" +
            server.args.joinToString("\u0001") + "\u0000" + server.env.toSortedMap().toString() +
            "\u0000" + server.cwd

    private fun pruneClients(used: Set<String>) {
        synchronized(lock) {
            val iterator = clients.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key in used) continue
                iterator.remove()
                // stdio 的「连接」是本机进程：只是从表里删掉的话进程会一直挂着
                runCatching { entry.value.close() }
            }
        }
    }

    private fun summarize(list: List<ServerStatus>): String =
        if (list.isEmpty()) {
            "没有配置任何服务"
        } else {
            list.joinToString("；") { status ->
                status.server.name + "=" + status.state +
                    (if (status.toolCount > 0) "(" + status.toolCount + " 个工具)" else "") +
                    (if (status.message.isNotEmpty()) " " + status.message else "")
            }
        }

    private fun describe(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).take(300)
}
