package com.zmf.agent.agent.memory

import android.content.Context
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiUtils
import com.zmf.agent.core.log.FileLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 【入门文档】「记忆内核」（mem-core）的接入层。
 *
 * 它只做三件事：把库铺到工作区、把 App 侧路径翻译成 proot 路径、在 App 内 Ubuntu 里跑一条 mem.py。
 *
 * 为什么不在 Kotlin 里自己算分数 / 自己存记忆：
 * 内核的硬规则是「事件流 _Data/events 下的 jsonl 只增不删，是唯一真相源；检索与衰减只认内核 CLI 的输出，
 * 不得另建第二套计算」。所以强度、排序、巩固判定全部留在 mem.py，这里只当传话筒——
 * 一旦在这里写第二套检索或衰减，两边算出来的「记得多牢」会慢慢对不上，且没法再从事件流重建。
 *
 * 为什么每条命令都要重新 executeHiddenCommand 而不是常驻进程：
 * 隐藏执行内部是一把 shell 互斥锁，常驻反而挡住 terminal 工具；而 mem.py 是「进程起、事件流落盘、退出」的
 * 一次性 CLI，短进程更不容易把库留在半写状态。
 */
object MemoryKernel {

    /** 库根目录名（工作区下的固定子目录）。 */
    const val LIB_DIR_NAME = "记忆内核库"

    private const val TAG = "mem-core"

    private const val ASSET_DIR = "mem-core"
    private const val SCRIPT_DIR = "_Scripts"
    private const val SCRIPT_NAME = "mem.py"
    private const val DATA_DIR = "_Data"

    /**
     * assets/mem-core 里的文件 → 库内相对路径。
     *
     * mem.py 必须落在 _Scripts/ 下：脚本用 Path(__file__).parent.parent 反推库根、再据此定位 _Data，
     * 位置挪错「数据根」就会跑到别处去，看起来像「记忆丢了」。
     */
    private val ASSET_FILES = listOf(
        "mem.py" to SCRIPT_DIR + "/" + SCRIPT_NAME,
        "RULES.md" to "RULES.md",
        "SKILL.md" to "SKILL.md",
    )

    /** App 侧看到的外部存储根；proot 里它被绑到 /sdcard。 */
    private const val EMULATED_ROOT = "/storage/emulated/0"
    private const val PROOT_SDCARD = "/sdcard"

    /**
     * 隐藏执行 shell 的 key。
     *
     * 故意和 terminal 工具的 "agent" 分开：隐藏执行按 key 分 shell、各有一把互斥锁，
     * 公用一个 key 时终端里跑长命令会把「回忆一下」卡在锁后面排队。
     */
    private const val EXECUTOR_KEY = "mem"

    /** 回给模型的输出上限：超过就保留头尾并标注省略了多少字符，别把上下文一次撑爆。 */
    private const val MAX_OUTPUT_CHARS = 16_000
    private const val HEAD_CHARS = 6_000

    /** 只有这些字符不加引号也安全；其余一律单引号包裹。 */
    private val SAFE_ARG = Regex("[A-Za-z0-9_@%+=:,./-]+")

    // ---------------------------------------------------------------- 铺库

    /**
     * 确保工作区里有可用的记忆内核库，返回库根（<workspace>/记忆内核库/）。
     *
     * 三条写入约定：
     * 1. 脚本与规则文件按**内容**比对（不是版本号）——字节不同就是版本不同，覆盖成 assets 里的新版；
     *    内核没有稳定的 version 字段，靠比对内容反而更可靠。
     * 2. _Data/ 只 mkdirs，**绝不覆盖**：里面是 append-only 事件流，任何「同步」都是数据事故。
     * 3. 库是工具自己的运行时资产（等同 TodoStore 写 appdata），所以 READ_ONLY 档也允许铺。
     *
     * 会在调用线程上做文件 IO，调用方负责放到 IO 线程。
     */
    fun ensureLibrary(context: Context, workspaceRoot: File): File {
        val libRoot = File(workspaceRoot, LIB_DIR_NAME)
        val assets = context.applicationContext.assets

        for ((assetName, relative) in ASSET_FILES) {
            val target = File(libRoot, relative)
            target.parentFile?.mkdirs()

            val bytes = try {
                assets.open(ASSET_DIR + "/" + assetName).use { it.readBytes() }
            } catch (e: IOException) {
                throw IOException(
                    "assets 里读不到 " + ASSET_DIR + "/" + assetName + "：" +
                        (e.message ?: e.javaClass.simpleName),
                    e
                )
            }

            // 读不出来（权限/坏文件）就当「需要重写」处理：库文件坏了必须能自愈，不能因为读失败而跳过。
            val old = if (target.isFile) runCatching { target.readBytes() }.getOrNull() else null
            if (old != null && old.contentEquals(bytes)) continue

            target.writeBytes(bytes)
            FileLog.log(
                TAG,
                (if (old == null) "落库：" else "更新：") + target.absolutePath + "（" + bytes.size + " 字节）"
            )
        }

        val dataDir = File(libRoot, DATA_DIR)
        if (!dataDir.exists()) {
            dataDir.mkdirs()
            FileLog.log(TAG, "新建数据根：" + dataDir.absolutePath)
        }
        return libRoot
    }

    /**
     * 把 App 侧的绝对路径换成 proot 里能用的路径：/storage/emulated/0/... → /sdcard/...
     *
     * 为什么必须换：proot 启动时把外部存储根 bind 到 /sdcard（见 PRootMountMapping），
     * /storage/emulated/0 这条路径在 rootfs 里不保证存在，直接把 App 侧路径塞给 Ubuntu 会「No such file」。
     * 不在这两个根下的路径原样返回——那是调用方的事，这里不猜。
     */
    fun toProotPath(workspaceAbsolutePath: String): String {
        val path = workspaceAbsolutePath.trim()
        if (path.isEmpty()) return path
        if (path == EMULATED_ROOT) return PROOT_SDCARD
        if (!path.startsWith(EMULATED_ROOT + "/")) return path
        return PROOT_SDCARD + path.removePrefix(EMULATED_ROOT)
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 在 App 内 Ubuntu 里跑一条 mem.py 命令，返回 --json 输出。
     *
     * 失败一律写明「卡在第几步」并带上原文（RULES §7：内核缺失/报错必须显式上报，不得静默跳过）。
     * 这里的 failure 不是异常流，而是给模型看的中文原因：它读到之后应该如实转告用户、退回「无记忆」模式。
     *
     * @param args 子命令与参数，**不含** --json（本方法统一追加）和可执行文件路径。
     * @param timeoutMs 隐藏执行的超时；大库的 cycle / rebuild 会更慢。
     */
    suspend fun run(
        context: Context,
        workspaceRoot: File,
        args: List<String>,
        timeoutMs: Long = 60_000L,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (args.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("没有要执行的 mem.py 子命令（args 为空）")
            )
        }

        // 开了 SSH 后 executeHiddenCommand 会发到远端：那边没有我们铺好的库，报错也会指向不存在的路径，
        // 直接把「记忆内核只在 App 内 Ubuntu 里跑」说清楚，比让用户对着一堆 No such file 猜要好。
        if (isSshEnabled(context)) {
            return@withContext Result.failure(
                IllegalStateException(
                    "第 0 步（环境）失败：当前开启了 SSH 远程终端，命令会被发到远端，而记忆内核库只在 App 内 Ubuntu 里——请先在终端设置里关闭 SSH"
                )
            )
        }

        // 第 1 步：铺库
        val libRoot = try {
            ensureLibrary(context, workspaceRoot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException(
                    "第 1 步（铺库）失败：把 assets/" + ASSET_DIR + " 同步到 " + LIB_DIR_NAME +
                        " 时出错——" + (e.message ?: e.javaClass.simpleName),
                    e
                )
            )
        }

        // 第 2 步：确认脚本在位（铺库成功但文件被删/权限不对时，先在这里报，别让 bash 报 127 让人猜）
        val script = File(libRoot, SCRIPT_DIR + File.separator + SCRIPT_NAME)
        if (!script.isFile) {
            return@withContext Result.failure(
                IllegalStateException(
                    "第 2 步（定位脚本）失败：未找到 " + script.absolutePath +
                        "（工作区初始化未完成，请确认工作区可写、外部存储已正常挂载）"
                )
            )
        }
        val prootScript = toProotPath(script.absolutePath)

        // 第 3 步：在 App 内 Ubuntu 里跑
        val command = buildCommand(workspaceRoot.name, prootScript, args)
        FileLog.log(TAG, "执行：" + command)
        val result = try {
            TerminalManager.getInstance(context).executeHiddenCommand(command, EXECUTOR_KEY, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException(
                    "第 3 步（启动 Ubuntu 环境）失败：" + (e.message ?: e.javaClass.simpleName),
                    e
                )
            )
        }

        if (result.state != HiddenExecResult.State.OK) {
            return@withContext Result.failure(IllegalStateException(describeExecFailure(result, prootScript)))
        }

        val text = clean(result.output)
        when (result.exitCode) {
            0 -> Result.success(successText(text))
            // 退出码 2 是内核明确定义的「参数或数据错误，信息在 stderr」，stderr 已并入 output，必须原样带出去。
            2 -> Result.failure(
                IllegalStateException(
                    "第 4 步（mem.py）失败：退出码 2 = 参数或数据错误（内核把它写在 stderr，已并入下面的原文；" +
                        "照它改参数重试，别自己猜原因）\n" + orPlaceholder(text)
                )
            )
            127 -> Result.failure(
                IllegalStateException(
                    "第 4 步（mem.py）失败：退出码 127 = 命令或脚本找不到——可能是 App 内 Ubuntu 没装 python3" +
                        "（apt install -y python3），也可能是脚本在 proot 里不可见（本次路径 " + prootScript +
                        "）。\n" + orPlaceholder(text)
                )
            )
            else -> Result.failure(
                IllegalStateException(
                    "第 4 步（mem.py）失败：退出码 " + result.exitCode + "\n" + orPlaceholder(text)
                )
            )
        }
    }

    // ---------------------------------------------------------------- 命令拼装

    /**
     * 拼 `MEM_NS=project:<工作区目录名> python3 <proot 脚本> <args…> --json`。
     *
     * 每个片段单独 shellQuote：正文里有空格、引号、换行时，隐藏执行会把命令原样写进 heredoc 再交给 bash，
     * 不转义就会出现「半条命令被执行」这种最难查的错。
     * 不传 --data-root：mem.py 自己按脚本位置定位 _Data，写死反而会把别处的数据根指过来。
     *
     * 为什么要显式给 PYTHONUTF8 / PYTHONIOENCODING：
     * proot 里 LANG 是 en_US.UTF-8，但 rootfs 未必真的生成过这个 locale；locale 一失效，
     * Python 的 stdout 可能退回 ascii，打印中文 JSON 时直接 UnicodeEncodeError（退出码 1），
     * 看起来就像「记忆内核坏了」。钉死 UTF-8 比事后去 Ubuntu 里 apt 一个 locale 便宜得多。
     */
    private fun buildCommand(workspaceDirName: String, prootScript: String, args: List<String>): String {
        val parts = ArrayList<String>(args.size + 5)
        parts.add("MEM_NS=" + shellQuote(projectNamespace(workspaceDirName)))
        parts.add("PYTHONUTF8=1")
        parts.add("PYTHONIOENCODING=utf-8")
        parts.add("python3")
        parts.add(shellQuote(prootScript))
        for (arg in args) parts.add(shellQuote(arg))
        parts.add("--json")
        return parts.joinToString(" ")
    }

    /** 默认命名空间：一个工作区一个项目名，跨工作区互不污染。 */
    private fun projectNamespace(workspaceDirName: String): String =
        "project:" + workspaceDirName.trim().ifEmpty { "workspace" }

    /** 单引号包裹 + 内部单引号转义成 '\''，这样任何字符都能原样落地。 */
    private fun shellQuote(raw: String): String {
        if (raw.isEmpty()) return "''"
        if (SAFE_ARG.matches(raw)) return raw
        return "'" + raw.replace("'", "'\\''") + "'"
    }

    // ---------------------------------------------------------------- 输出处理

    private fun isSshEnabled(context: Context): Boolean = context
        .getSharedPreferences("ssh_config", Context.MODE_PRIVATE)
        .getBoolean("ssh_enabled", false)

    /** 去 ANSI、去首尾空白；proot 的登录提示偶尔会混进来，留着只会干扰 JSON 解析。 */
    private fun clean(raw: String): String =
        runCatching { AnsiUtils.stripAnsi(raw) }.getOrElse { raw }.trim()

    private fun orPlaceholder(text: String): String = text.ifEmpty { "（没有输出）" }

    /**
     * 退出码 0 的结果：能整体解析 JSON 就原样返回；
     * 解析不了说明拿到的不是 JSON（如内核往 stderr 打了提示），**标注出来**再原样返回——
     * 既不假装成功，也不把有用信息吃掉。
     */
    private fun successText(text: String): String {
        if (text.isEmpty()) return "（mem.py 没有输出内容，退出码 0）"
        if (isJson(text)) return truncate(text, "结果太长，已按头尾截断；要更短的答案就调小 limit")
        return "（非 JSON 输出；mem.py 退出码 0，原文如下）\n" +
            truncate(text, "输出太长，已按头尾截断")
    }

    private fun isJson(text: String): Boolean =
        runCatching { JSONObject(text) }.isSuccess || runCatching { JSONArray(text) }.isSuccess

    private fun truncate(text: String, note: String): String {
        if (text.length <= MAX_OUTPUT_CHARS) return text
        val head = text.take(HEAD_CHARS)
        val tail = text.takeLast(MAX_OUTPUT_CHARS - HEAD_CHARS)
        return head + "\n…（" + note + "，省略 " + (text.length - MAX_OUTPUT_CHARS) + " 字符）…\n" + tail
    }

    /** 把隐藏执行的失败态翻译成「卡在哪一步 + 怎么办」。 */
    private fun describeExecFailure(result: HiddenExecResult, prootScript: String): String {
        val reason = when (result.state) {
            HiddenExecResult.State.SHELL_NOT_READY ->
                "Ubuntu 环境尚未就绪：请先在侧边栏打开一次「终端」完成初始化，然后重试"
            HiddenExecResult.State.TIMEOUT ->
                "执行超时：大型记忆库执行周期扫描或重建索引耗时较长，可增大 timeoutMs 后重试"
            HiddenExecResult.State.SHELL_START_FAILED ->
                "隐藏执行通道未能启动：" + result.error.ifBlank { "原因未知" }
            else -> result.error.ifBlank { "状态 " + result.state.name }
        }
        val preview = clean(result.rawOutputPreview)
        val tail = if (preview.isEmpty()) "" else "\n原始输出片段：\n" + truncate(preview, "片段过长")
        return "第 3 步（启动 Ubuntu 环境）失败：" + reason + "\n脚本：" + prootScript + tail
    }
}
