package com.zmf.agent.agent.mcp

import android.content.Context
import com.ai.assistance.operit.terminal.HiddenHandle
import com.ai.assistance.operit.terminal.TerminalManager
import com.zmf.agent.core.log.FileLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 客户端的公共面：http/sse 走 [McpClient]，stdio 走 [McpStdioClient]。
 *
 * 三个方法的名字、参数、返回形状和 [McpClient] 完全一致（都是 Result），
 * 所以 McpManager / McpToolAdapter 不需要按传输分支，只认这个接口。
 */
interface McpTransport {

    /** 握手（initialize + notifications/initialized）；成功后才能 listTools / callTool。 */
    suspend fun initialize(): Result<Unit>

    /** tools/list。 */
    suspend fun listTools(): Result<List<McpToolInfo>>

    /** tools/call，argumentsJson 原样转发。 */
    suspend fun callTool(name: String, argumentsJson: String): Result<String>

    /**
     * 释放底层连接。
     *
     * stdio 的「连接」就是一条本机进程，被 McpManager 从缓存里摘掉时必须真的杀掉，
     * 否则配置改了/服务删了，旧进程还一直挂着。http/sse 没有持久状态，默认什么都不做。
     */
    fun close() {}
}

/**
 * stdio 传输的 MCP 客户端：在 App 内 Ubuntu（proot）里起一条命令，
 * 用「一行一个 JSON」的 JSON-RPC 通过它的 stdin/stdout 对话。
 *
 * 协议面只做三件事：initialize（含 notifications/initialized）、tools/list、tools/call，
 * 和 [McpClient] 对齐，少一个依赖也少一层抽象。
 *
 * 几条约定：
 * - 一行一个 JSON：读 stdout 按行切，不是 JSON 的行（proot 的 LOGIN_SUCCESSFUL、装 Ubuntu 的进度、
 *   服务自己的 printf 日志）只记日志、绝不喂给解析；
 * - 服务写 stderr 的内容由 TerminalManager 排空并记日志，不进协议流；
 * - 单次请求 30 秒超时（stdio 服务是本地进程，启动慢主要在首次进 proot，用超时兜住）；
 * - 进程退出只重连一次：第二次还退说明命令本身起不来，直接报错给用户；
 * - 前提：App 内的 Ubuntu 已经初始化过（和终端工具一样，首次要打开一次「终端」把 rootfs 装好）。
 */
class McpStdioClient(
    private val context: Context,
    private val server: McpServer
) : McpTransport {

    /** 进程生命周期与请求发送都走这把锁：stdio 服务本身是单进程串行的，并发发出去只会互相打架。 */
    private val mutex = Mutex()

    /** JSON-RPC 请求 id 自增，整个 client 生命周期内单调，重连也不回绕（避免旧响应对上新请求）。 */
    private val requestIds = AtomicInteger(0)

    /** 发出去还没等到响应的请求：id -> 结果槽。 */
    private val pending = HashMap<Int, CompletableDeferred<JSONObject>>()

    /** 三个状态都是「调用线程写、TerminalManager 的读线程读」，必须 volatile，否则迟到回调看不到新值。 */
    @Volatile
    private var handle: HiddenHandle? = null

    /** initialize 的握手是否已经做成；进程一退就置 false。 */
    @Volatile
    private var handshakeDone = false

    /** 每次起进程加一：迟到的 onExit / onLine 回调靠它识别「我已经不是当前那条进程了」。 */
    @Volatile
    private var generation = 0

    /** 被 McpManager 从缓存里摘掉并 close 之后就永久关闭：不再重连，否则会留下没人管的进程。 */
    @Volatile
    private var closed = false

    // ---- 对外 ----

    override suspend fun initialize(): Result<Unit> = guarded("初始化") {
        mutex.withLock {
            // 已经握手过的进程直接复用：McpManager.reload 会重复调 initialize，
            // 每次都重启一遍 stdio 进程等于把用户正在用的服务掐断
            ensureReady()
        }
    }

    override suspend fun listTools(): Result<List<McpToolInfo>> = guarded("获取工具列表") {
        withProcess {
            val result = rpc(METHOD_LIST_TOOLS, JSONObject())
            val array = result.optJSONArray("tools")
                ?: throw IllegalStateException("返回里没有 tools 数组")
            val infos = ArrayList<McpToolInfo>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue
                // inputSchema 原样透传（模型要原始 JSON Schema），不解析、不改写
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
    }

    override suspend fun callTool(name: String, argumentsJson: String): Result<String> =
        guarded("调用工具 " + name) {
            withProcess {
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
        }

    override fun close() {
        closed = true
        synchronized(pending) {
            for (deferred in pending.values) {
                deferred.completeExceptionally(StdioProcessGone("stdio 连接已关闭"))
            }
            pending.clear()
        }
        closeHandle()
    }

    // ---- 进程 ----

    /** 没有可用进程就起一条并握手；已有可用进程就直接返回。 */
    private suspend fun ensureReady() {
        if (handle?.isAlive == true && handshakeDone) return
        closeHandle()
        startProcess()
        handshake()
    }

    /**
     * 一次完整操作：确保握手完成 → 干活。
     * 干活途中进程没了，就重连一次（只一次）再重试；再失败交给上层报错。
     */
    private suspend fun <T> withProcess(action: suspend () -> T): T = mutex.withLock {
        try {
            ensureReady()
            action()
        } catch (e: StdioProcessGone) {
            // 服务已被卸载（close 过）就别重连了，免得留下没有主人的进程
            if (closed) throw e
            // 重连放在 catch 里而不是外面：第二次再断就让它抛出去，不无限重启
            FileLog.log(TAG, "服务「" + server.name + "」的 stdio 进程退出了，重连一次后重试：" + describe(e))
            closeHandle()
            startProcess()
            handshake()
            action()
        }
    }

    private fun startProcess() {
        if (closed) throw IllegalStateException("MCP 服务已卸载，不再启动 stdio 进程")
        val command = server.command.trim()
        if (command.isEmpty()) throw IllegalStateException("stdio 服务没有配置命令")
        val commandLine = buildCommandLine(command, server.args)
        val current = ++generation
        // 清掉上一条进程留下的等待者，免得它们的 id 被新响应对上
        synchronized(pending) { pending.clear() }
        val created = TerminalManager.getInstance(context).openHiddenProcess(
            commandLine = commandLine,
            onLine = { line -> if (current == generation) onStdoutLine(line) },
            env = server.env,
            cwd = server.cwd,
            onExit = { code -> if (current == generation) onProcessExit(code) }
        ) ?: throw IllegalStateException("无法启动 stdio 进程：命令为空")
        handle = created
        FileLog.log(TAG, "服务「" + server.name + "」启动 stdio 进程：" + commandLine.take(MAX_ERROR_CHARS))
    }

    /** 进程退出：握手作废，并把还在等的请求全部叫醒（让 withProcess 走重连分支）。 */
    private fun onProcessExit(code: Int?) {
        handshakeDone = false
        val reason = "stdio 进程已退出" + (if (code != null) "（exit=" + code + "）" else "")
        FileLog.log(TAG, "服务「" + server.name + "」" + reason)
        val waiting = synchronized(pending) {
            val snapshot = pending.values.toList()
            pending.clear()
            snapshot
        }
        for (deferred in waiting) {
            deferred.completeExceptionally(StdioProcessGone(reason))
        }
    }

    private fun closeHandle() {
        handshakeDone = false
        val old = handle
        handle = null
        runCatching { old?.close() }
    }

    // ---- JSON-RPC ----

    private suspend fun handshake() {
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put(
                "clientInfo",
                JSONObject().put("name", CLIENT_NAME).put("version", CLIENT_VERSION)
            )
        val result = rpc(METHOD_INITIALIZE, params)
        handshakeDone = true
        val serverName = result.optJSONObject("serverInfo")?.optString("name", "").orEmpty()
        val protocol = result.optString("protocolVersion", "").trim()
        FileLog.log(
            TAG,
            "服务「" + server.name + "」stdio 初始化成功" +
                (if (serverName.isNotEmpty()) "（" + serverName + "）" else "") +
                (if (protocol.isNotEmpty()) "，协议 " + protocol else "")
        )
        // 通知没有 id、不等响应；发不出去只记日志，不能把初始化判死
        try {
            sendLine(JSONObject().put("jsonrpc", JSON_RPC_VERSION).put("method", METHOD_INITIALIZED).toString())
        } catch (e: StdioProcessGone) {
            FileLog.log(TAG, "通知 " + METHOD_INITIALIZED + " 发送失败（忽略）：" + describe(e))
        }
    }

    /** 发一次带 id 的请求，返回 result 对象；服务端报 error 或超时就抛可读原因。 */
    private suspend fun rpc(method: String, params: JSONObject?): JSONObject {
        val id = requestIds.incrementAndGet()
        val payload = JSONObject()
            .put("jsonrpc", JSON_RPC_VERSION)
            .put("id", id)
            .put("method", method)
        if (params != null) payload.put("params", params)

        val deferred = CompletableDeferred<JSONObject>()
        synchronized(pending) { pending[id] = deferred }
        sendLine(payload.toString())

        val envelope = try {
            withTimeoutOrNull(CALL_TIMEOUT_MS) { deferred.await() }
        } catch (e: CancellationException) {
            synchronized(pending) { pending.remove(id) }
            throw e
        }
        if (envelope == null) {
            synchronized(pending) { pending.remove(id) }
            throw IllegalStateException("服务没有在 " + CALL_TIMEOUT_MS + "ms 内响应 " + method)
        }
        val error = envelope.optJSONObject("error")
        if (error != null) {
            throw IllegalStateException(
                "JSON-RPC 错误 " + error.optInt("code", 0) + "：" + error.optString("message", "未知错误")
            )
        }
        return envelope.optJSONObject("result") ?: JSONObject()
    }

    /** 写一行给子进程；进程不在就抛 [StdioProcessGone]（由 withProcess 决定要不要重连）。 */
    private fun sendLine(line: String) {
        val target = handle
        if (target == null || !target.writeLine(line)) {
            throw StdioProcessGone("stdio 进程不可写（还没起来或已经退出）")
        }
    }

    /** stdout 的一行：认出 JSON-RPC 响应就交给等它的请求，其余当日志丢掉。 */
    private fun onStdoutLine(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null || !obj.has("jsonrpc")) {
            // proot 启动日志、服务自己的 printf 调试输出：不是协议，别让它污染 JSON 解析
            FileLog.log(TAG, "服务「" + server.name + "」非协议输出：" + text.take(MAX_ERROR_CHARS))
            return
        }
        val id = if (obj.has("id") && !obj.isNull("id")) obj.optInt("id", -1) else -1
        if (id < 0) {
            // 服务端的通知/请求（日志、sampling 之类）目前不处理，只留一条记录
            FileLog.log(TAG, "服务「" + server.name + "」发来通知：" + obj.optString("method", "?"))
            return
        }
        val deferred = synchronized(pending) { pending.remove(id) }
        if (deferred == null) {
            FileLog.log(TAG, "服务「" + server.name + "」返回了没人等的响应 id=" + id)
            return
        }
        deferred.complete(obj)
    }

    // ---- 杂项 ----

    /** 命令 + 参数拼成一行；参数逐个用单引号包住，带空格的参数才不会被 shell 拆开。 */
    private fun buildCommandLine(command: String, args: List<String>): String {
        val builder = StringBuilder(command)
        for (arg in args) builder.append(' ').append(shellQuote(arg))
        return builder.toString()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** 只拼 text 项，image/resource 一律忽略；多条之间补换行。 */
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

    /** 协议 / 子进程异常折成带可读中文的 Result.failure；CancellationException 原样抛出。 */
    private suspend fun <T> guarded(action: String, block: suspend () -> T): Result<T> = try {
        Result.success(withContext(Dispatchers.IO) { block() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(IllegalStateException("MCP「" + server.name + "」" + action + "失败：" + describe(e), e))
    }

    private fun describe(e: Throwable): String =
        (e.message ?: e.javaClass.simpleName).take(MAX_ERROR_CHARS)

    /** stdio 进程没了：只在内部用来触发「重连一次」，不会漏到 Result 外面。 */
    private class StdioProcessGone(reason: String) : Exception(reason)

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

        /** 单次调用（含 initialize）最长等 30 秒：本地进程还要先起 proot，给足但不无限等。 */
        const val CALL_TIMEOUT_MS = 30_000L

        const val MAX_ERROR_CHARS = 300
    }
}
