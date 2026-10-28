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
 * 【入门文档】「记忆内核」（mingmem）的接入层。
 *
 * 它只做三件事：把内核整棵铺到 App 内部存储、把路径翻译成 proot 路径、在 App 内 Ubuntu 里跑
 * `python3 -X utf8 -m mingmem <子命令>`。
 *
 * 为什么不在 Kotlin 里自己算分数 / 自己存记忆：
 * 内核的硬规则是「事件流 _Data/events 下的 jsonl 只增不删，是唯一真相源；检索与衰减只认内核 CLI 的输出，
 * 不得另建第二套计算」。所以强度、排序、巩固判定全部留在内核里，这里只当传话筒：
 * 一旦在这里写第二套检索或衰减，两边算出来的「记得多牢」会慢慢对不上，且没法再从事件流重建。
 *
 * 为什么每条命令都要重新 executeHiddenCommand 而不是常驻进程：
 * 隐藏执行内部是一把 shell 互斥锁，常驻反而挡住 terminal 工具；而内核是「进程起、事件流落盘、退出」的
 * 一次性 CLI，短进程更不容易把库留在半写状态。
 *
 * 内核是独立项目（仓根即 `mingmem`，纯标准库、可独立升级）：本接入层只负责
 * 「把 assets/mingmem 的整棵树铺到 App 内部存储的 mingmem/、然后用 `-m mingmem` 调它」，
 * **不硬编码内核的任何文件路径**（文件清单由 assets 目录结构自己决定，内核加模块这里不用改）。
 */
object MemoryKernel {

    /** 库根目录名（App 内部存储下的固定子目录）。独立内核的仓根就叫这个，App 不再用「记忆内核库」。 */
    const val LIB_DIR_NAME = "mingmem"

    private const val TAG = "mingmem"

    private const val ASSET_DIR = "mingmem"
    private const val PACKAGE_DIR = "mingmem"
    private const val DATA_DIR = "_Data"

    /** 向量通道开关的偏好名 / 键（设置页「记忆 → 向量模型」开关写这里，内核读 MEM_VECTOR 环境变量）。 */
    private const val MEMORY_PREFS = "memory_config"
    private const val KEY_VECTOR_ENABLED = "vector_enabled"

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
     * 确保 App 内部存储里有可用的记忆内核，返回库根（`<filesDir>/mingmem/`）。
     *
     * 为什么不放工作区：内核是「程序」不是用户数据，放工作区会被
     * 用户／外壳的文件操作误删，删了记忆功能就坏。`context.filesDir`（=/data/user/0/<pkg>/files）
     * 是 App 内部存储：文件管理器 / MTP 都进不来，且 proot 里按同路径 bind
     * （`PRootMountMapping` 的 appDataDir 绑定），内核直接能在 Ubuntu 里读到。
     *
     * 把 assets/mingmem 下的整棵树按目录结构原样铺进库根，三条写入约定：
     * 1. 按**内容**比对（不是版本号）：字节不同就是版本不同，覆盖成 assets 里的新版；
     *    内核没有稳定的 version 字段，靠比对内容反而更可靠。
     * 2. _Data/ 只 mkdirs，**绝不覆盖**：里面是 append-only 事件流，任何「同步」都是数据事故。
     * 3. 库是工具自己的运行时资产（等同 TodoStore 写 appdata），所以 READ_ONLY 档也允许铺。
     *
     * 会在调用线程上做文件 IO，调用方负责放到 IO 线程。
     */
    fun ensureLibrary(context: Context): File {
        val libRoot = File(context.filesDir, LIB_DIR_NAME)
        copyAssetTree(context, ASSET_DIR, libRoot)
        // 内核升级时模块可能删改；把上次留下的字节码清掉，避免陈旧 .pyc 盖过新 .py
        removePycache(libRoot)
        val dataDir = File(libRoot, DATA_DIR)
        if (!dataDir.exists()) {
            dataDir.mkdirs()
            FileLog.log(TAG, "新建数据根：" + dataDir.absolutePath)
        }
        return libRoot
    }

    /**
     * 递归把 assets 下 [srcPath] 目录铺进 [destDir]，文件按内容比对、只在变化时写。
     *
     * 为什么不用 `assets.list(child)` 判目录：**AssetManager.list 对文件也返回空数组**（不是 null），
     * 按它分叉会把每个文件都建成空目录（模拟器实测踩过：内核 40 个文件全变成空目录，
     * `__main__.py` 不是文件 → 第 2 步报「定位内核失败」）。所以这里用「能否 open」判定：open 成功是文件，
     * 抛 FileNotFoundException 是目录（list 对不存在的路径返回空数组，递归天然收敛）。
     *
     * 顺带自愈：上次误铺留下的「同名空目录」在写文件前先删掉，反之亦然，
     * 这样内核升级增删文件、或用户手动清了部分文件，都能被下一轮铺库修正回来。
     */
    private fun copyAssetTree(context: Context, srcPath: String, destDir: File) {
        val assets = context.applicationContext.assets
        val entries = assets.list(srcPath) ?: return
        for (name in entries) {
            val child = srcPath + "/" + name
            val target = File(destDir, name)
            val stream = try {
                assets.open(child)
            } catch (e: IOException) {
                null
            }
            if (stream != null) {
                // 文件：内容比对后覆盖；上次误铺成同名目录时先删目录
                stream.use { input ->
                    val bytes = input.readBytes()
                    if (target.isDirectory) target.deleteRecursively()
                    target.parentFile?.mkdirs()
                    val old = if (target.isFile) runCatching { target.readBytes() }.getOrNull() else null
                    if (old != null && old.contentEquals(bytes)) return@use
                    target.writeBytes(bytes)
                    FileLog.log(
                        TAG,
                        (if (old == null) "落库：" else "更新：") + target.absolutePath + "（" + bytes.size + " 字节）"
                    )
                }
            } else {
                // 目录：递归；上次误铺成同名文件时先删文件
                if (target.isFile) target.delete()
                copyAssetTree(context, child, target)
            }
        }
    }

    private fun removePycache(dir: File) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                if (child.name == "__pycache__") child.deleteRecursively()
                else removePycache(child)
            }
        }
    }

    /**
     * 把 App 侧的绝对路径换成 proot 里能用的路径：/storage/emulated/0/... → /sdcard/...
     *
     * 为什么必须换：proot 启动时把外部存储根 bind 到 /sdcard（见 PRootMountMapping），
     * /storage/emulated/0 这条路径在 rootfs 里不保证存在，直接把 App 侧路径塞给 Ubuntu 会「No such file」。
     * 不在这两个根下的路径原样返回：那是调用方的事，这里不猜。
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
     * 在 App 内 Ubuntu 里跑一条 `python3 -m mingmem <子命令>` 命令，返回 --json 输出。
     *
     * 失败一律写明「卡在第几步」并带上原文（内核缺失或报错必须显式上报，不得静默跳过）。
     * 这里的 failure 不是异常流，而是给模型看的中文原因：它读到之后应该如实转告用户、退回「无记忆」模式。
     *
     * @param args 子命令与参数，**不含** --json（本方法统一追加）、--ns、--data-root（本方法统一补）。
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
                IllegalArgumentException("没有要执行的 mingmem 子命令（args 为空）")
            )
        }

        // 开了 SSH 后 executeHiddenCommand 会发到远端：那边没有我们铺好的库，报错也会指向不存在的路径，
        // 直接把「记忆内核只在 App 内 Ubuntu 里跑」说清楚，比让用户对着一堆 No such file 猜要好。
        if (isSshEnabled(context)) {
            return@withContext Result.failure(
                IllegalStateException(
                    "第 0 步（环境）失败：当前开启了 SSH 远程终端，命令会被发到远端，而记忆内核只在 App 内 Ubuntu 里——请先在终端设置里关闭 SSH"
                )
            )
        }

        // 第 1 步：铺库（在 App 内部存储，见 ensureLibrary 的注释）
        val libRoot = try {
            ensureLibrary(context)
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

        // 第 2 步：确认包在位（铺库成功但被删/权限不对时，先在这里报，别让 python 报 ImportError 让人猜）
        val entry = File(libRoot, PACKAGE_DIR + File.separator + "__main__.py")
        if (!entry.isFile) {
            return@withContext Result.failure(
                IllegalStateException(
                    "第 2 步（定位内核）失败：未找到 " + entry.absolutePath +
                        "（工作区初始化未完成，请确认工作区可写、外部存储已正常挂载）"
                )
            )
        }
        val prootLibRoot = toProotPath(libRoot.absolutePath)
        val prootDataRoot = toProotPath(File(libRoot, DATA_DIR).absolutePath)

        // 第 3 步：在 App 内 Ubuntu 里跑
        val command = buildCommand(workspaceRoot.name, prootLibRoot, prootDataRoot, args, isVectorEnabled(context))
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
            return@withContext Result.failure(IllegalStateException(describeExecFailure(result, prootLibRoot)))
        }

        val text = clean(result.output)
        when (result.exitCode) {
            0 -> Result.success(successText(text))
            // 退出码 2 是内核明确定义的「参数或数据错误，信息在 stderr」，stderr 已并入 output，必须原样带出去。
            2 -> Result.failure(
                IllegalStateException(
                    "第 4 步（mingmem）失败：退出码 2 = 参数或数据错误（内核把它写在 stderr，已并入下面的原文；" +
                        "照它改参数重试，别自己猜原因）\n" + orPlaceholder(text)
                )
            )
            127 -> Result.failure(
                IllegalStateException(
                    "第 4 步（mingmem）失败：退出码 127 = 命令或脚本找不到——可能是 App 内 Ubuntu 没装 python3" +
                        "（apt install -y python3），也可能是内核在 proot 里不可见（本次库根 " + prootLibRoot +
                        "）。\n" + orPlaceholder(text)
                )
            )
            else -> Result.failure(
                IllegalStateException(
                    "第 4 步（mingmem）失败：退出码 " + result.exitCode + "\n" + orPlaceholder(text)
                )
            )
        }
    }

    // ---------------------------------------------------------------- 命令拼装

    /**
     * 拼 `PYTHONUTF8=1 PYTHONIOENCODING=utf-8 PYTHONPATH=<库根> MINGMEM_NS=project:<名> \
     *    MINGMEM_DATA=<proot 数据根> python3 -X utf8 -m mingmem <参数…> --json`。
     *
     * 每个片段单独 shellQuote：正文里有空格、引号、换行时，隐藏执行会把命令原样写进 heredoc 再交给 bash，
     * 不转义就会出现「半条命令被执行」这种最难查的错。
     *
     * 数据根 / 命名空间走**环境变量**而不是 --data-root / --ns：
     * - 内核里不是每个子命令都有 `--ns`（status / doctor 就没有），硬塞会让 argparse 直接退出码 2；
     *   而 `MINGMEM_NS` 是内核 `default_ns()` 的正式读取项，对所有子命令都无害。
     * - `MINGMEM_NS=project:<名>` 是默认；mem 工具再传 `--ns=global` 时，CLI 优先级高于环境变量，仍能覆盖。
     * - `MINGMEM_DATA` 同理，免去每个命令都拼一遍数据根。
     *
     * 为什么要显式给 PYTHONUTF8 / PYTHONIOENCODING / -X utf8：
     * proot 里 LANG 是 en_US.UTF-8，但 rootfs 未必真的生成过这个 locale；locale 一失效，
     * Python 的 stdout 可能退回 ascii，打印中文 JSON 时直接 UnicodeEncodeError（退出码 1），
     * 看起来就像「记忆内核坏了」。钉死 UTF-8 比事后去 Ubuntu 里 apt 一个 locale 便宜得多。
     */
    private fun buildCommand(
        workspaceDirName: String,
        prootLibRoot: String,
        prootDataRoot: String,
        args: List<String>,
        vectorEnabled: Boolean,
    ): String {
        val parts = ArrayList<String>(args.size + 12)
        parts.add("PYTHONUTF8=1")
        parts.add("PYTHONIOENCODING=utf-8")
        parts.add("PYTHONPATH=" + shellQuote(prootLibRoot))
        parts.add("MINGMEM_NS=" + shellQuote(projectNamespace(workspaceDirName)))
        parts.add("MINGMEM_DATA=" + shellQuote(prootDataRoot))
        // 向量通道开关：内核用 MEM_VECTOR 显式控制（0=关，1/不设=开，见 vector.py）。
        // 开着但运行时没装 onnxruntime/numpy 时，内核自动降级为 BM25+图扩散，不影响命令。
        parts.add("MEM_VECTOR=" + if (vectorEnabled) "1" else "0")
        parts.add("python3")
        parts.add("-X")
        parts.add("utf8")
        parts.add("-m")
        parts.add(PACKAGE_DIR)
        for (arg in args) parts.add(shellQuote(arg))
        parts.add("--json")
        return parts.joinToString(" ")
    }

    // ---------------------------------------------------------------- 向量通道开关

    /** 向量模型是否开启。默认关：既未安装稠密运行依赖，也避免了首次检索时再拉起 onnxruntime。 */
    fun isVectorEnabled(context: Context): Boolean =
        context.getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VECTOR_ENABLED, false)

    /** 设置页「记忆 → 向量模型」开关的回写：只改偏好，不改内核数据。 */
    fun setVectorEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VECTOR_ENABLED, enabled).apply()
    }

    // ---------------------------------------------------------------- 稠密运行时

    /** 稠密通道运行时探测结果。[ready] 为真时 [detail] 是版本说明，否则是缺失原因。 */
    data class DenseRuntime(val ready: Boolean, val detail: String)

    /** 快速检测 onnxruntime 与 numpy 是否可用；只做 import 探测，不加载模型、不扫描记忆库。 */
    suspend fun checkDenseRuntime(context: Context): Result<DenseRuntime> = withContext(Dispatchers.IO) {
        if (isSshEnabled(context)) {
            return@withContext Result.failure(
                IllegalStateException("当前开启了 SSH 远程终端，依赖检测无法在 App 内 Ubuntu 中执行")
            )
        }
        val probe = "import numpy, onnxruntime; print(onnxruntime.__version__ + ' / numpy ' + numpy.__version__)"
        val command = "python3 -c " + shellQuote(probe)
        val result = try {
            TerminalManager.getInstance(context).executeHiddenCommand(command, EXECUTOR_KEY, 30_000L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException("依赖检测失败：" + (e.message ?: e.javaClass.simpleName), e)
            )
        }
        if (result.state != HiddenExecResult.State.OK) {
            return@withContext Result.failure(
                IllegalStateException("依赖检测失败：" + result.error.ifBlank { "Ubuntu 环境未就绪" })
            )
        }
        val text = clean(result.output)
        if (result.exitCode == 0) {
            Result.success(DenseRuntime(true, text.ifEmpty { "已安装" }))
        } else {
            Result.success(DenseRuntime(false, text.ifEmpty { "未安装" }))
        }
    }

    /**
     * 在 App 内 Ubuntu 中安装 onnxruntime 与 numpy。
     *
     * 先确认 pip 可用，缺失时经 apt 安装；pip 带 `--break-system-packages`，
     * 因为 Ubuntu 24.04 默认按 PEP 668 拒绝系统级安装。装完由调用方重新检测。
     */
    suspend fun installDenseRuntime(context: Context): Result<String> = withContext(Dispatchers.IO) {
        if (isSshEnabled(context)) {
            return@withContext Result.failure(
                IllegalStateException("当前开启了 SSH 远程终端，依赖安装无法在 App 内 Ubuntu 中执行")
            )
        }
        val script = listOf(
            "export DEBIAN_FRONTEND=noninteractive",
            "export PIP_DISABLE_PIP_VERSION_CHECK=1",
            "if ! python3 -m pip --version >/dev/null 2>&1; then apt-get update && apt-get install -y python3-pip; fi",
            "python3 -m pip install --no-cache-dir --break-system-packages \"onnxruntime>=1.18\" \"numpy>=1.24\"",
        ).joinToString("\n")
        val result = try {
            TerminalManager.getInstance(context).executeHiddenCommand(script, EXECUTOR_KEY, 600_000L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException("依赖安装失败：" + (e.message ?: e.javaClass.simpleName), e)
            )
        }
        if (result.state != HiddenExecResult.State.OK) {
            return@withContext Result.failure(
                IllegalStateException("依赖安装失败：" + result.error.ifBlank { result.state.name })
            )
        }
        val text = clean(result.output)
        if (result.exitCode == 0) {
            Result.success(text)
        } else {
            Result.failure(
                IllegalStateException("依赖安装失败：退出码 " + result.exitCode + "\n" + orPlaceholder(text))
            )
        }
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
     * 解析不了说明拿到的不是 JSON（如内核往 stderr 打了提示），**标注出来**再原样返回，
     * 既不假装成功，也不把有用信息吃掉。
     */
    private fun successText(text: String): String {
        if (text.isEmpty()) return "（mingmem 没有输出内容，退出码 0）"
        if (isJson(text)) return truncate(text, "结果太长，已按头尾截断；要更短的答案就调小 limit")
        return "（非 JSON 输出；mingmem 退出码 0，原文如下）\n" +
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
    private fun describeExecFailure(result: HiddenExecResult, prootLibRoot: String): String {
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
        return "第 3 步（启动 Ubuntu 环境）失败：" + reason + "\n库根：" + prootLibRoot + tail
    }
}
