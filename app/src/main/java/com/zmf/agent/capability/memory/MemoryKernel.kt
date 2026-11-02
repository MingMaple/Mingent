package com.zmf.agent.capability.memory
import com.zmf.agent.infra.terminal.ShellResult
import com.zmf.agent.infra.terminal.TerminalApi

import android.content.Context
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
 * 它只做三件事：将内核完整铺设至 App 内部存储、将路径转换为 proot 路径、在 App 内 Ubuntu 中执行
 * `python3 -X utf8 -m mingmem <子命令>`。
 *
 * 为什么不在 Kotlin 中自行计算分数或存储记忆：
 * 内核的硬规则是「事件流 _Data/events 下的 jsonl 只增不删，是唯一真相源；检索与衰减只以内核 CLI 的输出为准，
 * 不得另建第二套计算」。因此强度、排序、巩固判定全部保留在内核中，此处仅负责转发：
 * 一旦在此实现第二套检索或衰减，两处计算的「记忆强度」将逐渐不一致，且无法再从事件流重建。
 *
 * 为什么每条命令都要重新 executeHiddenCommand 而不使用常驻进程：
 * 隐藏执行内部使用一把 shell 互斥锁，常驻进程反而会阻塞 terminal 工具；而内核是「进程启动、事件流落盘、退出」的
 * 一次性 CLI，短进程更不易使库处于半写状态。
 *
 * 内核是独立项目（仓库根即 `mingmem`，仅使用标准库、可独立升级）：本接入层仅负责
 * 「将 assets/mingmem 的完整目录树铺设至 App 内部存储的 mingmem/，再以 `-m mingmem` 调用」，
 * **不硬编码内核的任何文件路径**（文件清单由 assets 目录结构决定，内核新增模块时此处无需修改）。
 */
object MemoryKernel {

    /** 库根目录名（App 内部存储下的固定子目录）。独立内核的仓库根使用该名称，App 侧统一采用。 */
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
     * 故意与 terminal 工具的 "agent" 分开：隐藏执行按 key 划分 shell，各自持有一把互斥锁，
     * 共用同一 key 时，终端中执行长命令会使「记忆检索」在锁后排队等待。
     */
    private const val EXECUTOR_KEY = "mem"

    /** 回给模型的输出上限：超出时保留头尾并标注省略的字符数，避免一次性使上下文过度膨胀。 */
    private const val MAX_OUTPUT_CHARS = 16_000
    private const val HEAD_CHARS = 6_000

    /** 只有这些字符不加引号也安全；其余一律单引号包裹。 */
    private val SAFE_ARG = Regex("[A-Za-z0-9_@%+=:,./-]+")

    // ---------------------------------------------------------------- 铺库

    /**
     * 确保 App 内部存储里有可用的记忆内核，返回库根（`<filesDir>/mingmem/`）。
     *
     * 为什么不放在工作区：内核属于「程序」而非用户数据，置于工作区可能被用户或外壳的文件操作误删，
     * 删除后记忆功能即失效。`context.filesDir`（=/data/user/0/<pkg>/files）
     * 为 App 内部存储：文件管理器 / MTP 均无法访问，且 proot 中按同路径 bind
     * （`PRootMountMapping` 的 appDataDir 绑定），内核可直接在 Ubuntu 中读取。
     *
     * 将 assets/mingmem 下的完整目录树按原结构铺设至库根，三条写入约定：
     * 1. 按**内容**比对（而非版本号）：字节不同即视为版本不同，覆盖为 assets 中的新版本；
     *    内核没有稳定的 version 字段，比对内容更为可靠。
     * 2. _Data/ 仅执行 mkdirs，**绝不覆盖**：其中为 append-only 事件流，任何「同步」都可能造成数据事故。
     * 3. 库属于工具自身的运行时资产（等同 TodoStore 写入 appdata），因此 READ_ONLY 档也允许铺设。
     *
     * 会在调用线程上做文件 IO，调用方负责放到 IO 线程。
     */
    fun ensureLibrary(context: Context): File {
        val libRoot = File(context.filesDir, LIB_DIR_NAME)
        copyAssetTree(context, ASSET_DIR, libRoot)
        // 内核升级时可能删除或修改模块；清除上次遗留的字节码，避免陈旧 .pyc 覆盖新 .py
        removePycache(libRoot)
        val dataDir = File(libRoot, DATA_DIR)
        if (!dataDir.exists()) {
            dataDir.mkdirs()
            FileLog.log(TAG, "新建数据根：" + dataDir.absolutePath)
        }
        return libRoot
    }

    /**
     * 递归将 assets 下 [srcPath] 目录铺设至 [destDir]，文件按内容比对，仅在变化时写入。
     *
     * 为什么不用 `assets.list(child)` 判断目录：**AssetManager.list 对文件也返回空数组**（而非 null），
     * 依此分支会将每个文件创建为空目录（模拟器实测曾出现：内核 40 个文件全部变为空目录，
     * `__main__.py` 不再是文件 → 第 2 步报「定位内核失败」）。因此此处以「能否 open」判定：open 成功即为文件，
     * 抛出 FileNotFoundException 即为目录（list 对不存在路径返回空数组，递归自然收敛）。
     *
     * 同时具备自愈能力：上次误铺留下的「同名空目录」会在写入文件前删除，反之亦然，
     * 因此内核升级增删文件、或用户手动清理部分文件，均可由下一轮铺库修正。
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
     * 将 App 侧的绝对路径转换为 proot 中可用的路径：/storage/emulated/0/... → /sdcard/...
     *
     * 为什么必须转换：proot 启动时将外部存储根 bind 到 /sdcard（见 PRootMountMapping），
     * /storage/emulated/0 在 rootfs 中不保证存在，直接将 App 侧路径交给 Ubuntu 会报「No such file」。
     * 不在这两个根下的路径原样返回：转换责任由调用方承担，此处不作猜测。
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
     * 在 App 内 Ubuntu 中执行一条 `python3 -m mingmem <子命令>` 命令，返回 --json 输出。
     *
     * 失败一律注明「失败于第几步」并附带原文（内核缺失或报错必须显式上报，不得静默跳过）。
     * 此处返回的 failure 属于面向模型的中文原因，而非异常流：模型读到后应如实转告用户，并退回「无记忆」模式。
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

        // 开启 SSH 后 executeHiddenCommand 会发送至远端：远端没有本应用铺设的库，报错也会指向不存在的路径，
        // 因此直接说明「记忆内核仅在 App 内 Ubuntu 中运行」，优于让用户从大量 No such file 中推测原因。
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

        // 第 2 步：确认包存在（铺设成功但被删除或权限异常时，在此报告，避免 python 抛出 ImportError 使原因难以判断）
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

        // 第 3 步：在 App 内 Ubuntu 中执行
        val command = buildCommand(workspaceRoot.name, prootLibRoot, prootDataRoot, args, isVectorEnabled(context))
        FileLog.log(TAG, "执行：" + command)
        val result = try {
            TerminalApi.exec(context, command, EXECUTOR_KEY, timeoutMs)
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

        if (result !is ShellResult.Ok) {
            return@withContext Result.failure(IllegalStateException(describeExecFailure(result, prootLibRoot)))
        }

        val text = clean(result.output)
        when (result.exitCode) {
            0 -> Result.success(successText(text))
            // 退出码 2 为内核明确定义的「参数或数据错误，信息写入 stderr」，stderr 已并入 output，必须原样返回。
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
     * 每个片段单独 shellQuote：正文包含空格、引号或换行时，隐藏执行会将命令原样写入 heredoc 再交给 bash，
     * 未转义将导致「命令仅执行一半」这类难以排查的错误。
     *
     * 数据根 / 命名空间使用**环境变量**而非 --data-root / --ns：
     * - 内核并非每个子命令均支持 `--ns`（status / doctor 不支持），强行传入会使 argparse 直接以退出码 2 结束；
     *   而 `MINGMEM_NS` 是内核 `default_ns()` 的正式读取项，对所有子命令均无副作用。
     * - `MINGMEM_NS=project:<名>` 为默认值；mem 工具再传 `--ns=global` 时，CLI 优先级高于环境变量，仍可覆盖。
     * - `MINGMEM_DATA` 同理，避免为每个命令重复拼接数据根。
     *
     * 为什么显式设置 PYTHONUTF8 / PYTHONIOENCODING / -X utf8：
     * proot 中 LANG 为 en_US.UTF-8，但 rootfs 未必真正生成过该 locale；locale 一旦失效，
     * Python 的 stdout 可能退回 ascii，打印中文 JSON 时直接抛出 UnicodeEncodeError（退出码 1），
     * 表现为「记忆内核故障」。固定 UTF-8 的成本远低于事后在 Ubuntu 中安装 locale。
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
            TerminalApi.exec(context, command, EXECUTOR_KEY, 30_000L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException("依赖检测失败：" + (e.message ?: e.javaClass.simpleName), e)
            )
        }
        if (result !is ShellResult.Ok) {
            return@withContext Result.failure(
                IllegalStateException("依赖检测失败：" + shellFailureText(result, "Ubuntu 环境未就绪"))
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
            TerminalApi.exec(context, script, EXECUTOR_KEY, 600_000L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException("依赖安装失败：" + (e.message ?: e.javaClass.simpleName), e)
            )
        }
        if (result !is ShellResult.Ok) {
            return@withContext Result.failure(
                IllegalStateException("依赖安装失败：" + shellFailureText(result, "未知状态"))
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

    /** 用单引号包裹，并将内部单引号转义为 '\''，使任意字符均可原样传递。 */
    private fun shellQuote(raw: String): String {
        if (raw.isEmpty()) return "''"
        if (SAFE_ARG.matches(raw)) return raw
        return "'" + raw.replace("'", "'\\''") + "'"
    }

    // ---------------------------------------------------------------- 输出处理

    private fun isSshEnabled(context: Context): Boolean = context
        .getSharedPreferences("ssh_config", Context.MODE_PRIVATE)
        .getBoolean("ssh_enabled", false)

    /** 去除 ANSI 与首尾空白；proot 的登录提示可能混入输出，保留会干扰 JSON 解析。 */
    private fun clean(raw: String): String =
        runCatching { TerminalApi.stripAnsi(raw) }.getOrElse { raw }.trim()

    private fun orPlaceholder(text: String): String = text.ifEmpty { "（没有输出）" }

    /**
     * 退出码 0 的结果：能整体解析 JSON 则原样返回；
     * 无法解析说明内容并非 JSON（例如内核向 stderr 输出提示），此时**显式标注**后原样返回，
     * 既不伪装成功，也不丢弃有效信息。
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

    /** 非 Ok 状态的一句话描述：优先用上游给的细节，没有就退回状态名，再没有就用调用方给的兜底话术。 */
    private fun shellFailureText(result: ShellResult, fallback: String): String = when (result) {
        is ShellResult.ShellNotReady -> fallback
        is ShellResult.Failed -> result.detail.ifBlank { result.reason }.ifBlank { fallback }
        is ShellResult.Ok -> fallback
    }

    /** 把隐藏执行的失败态翻译成「卡在哪一步 + 怎么办」。 */
    private fun describeExecFailure(result: ShellResult, prootLibRoot: String): String {
        val reason = when (result) {
            is ShellResult.ShellNotReady ->
                "Ubuntu 环境尚未就绪：请先在侧边栏打开一次「终端」完成初始化，然后重试"
            is ShellResult.Failed -> when (result.reason) {
                "TIMEOUT" ->
                    "执行超时：大型记忆库执行周期扫描或重建索引耗时较长，可增大 timeoutMs 后重试"
                "SHELL_START_FAILED" ->
                    "隐藏执行通道未能启动：" + result.detail.ifBlank { "原因未知" }
                else -> result.detail.ifBlank { "状态 " + result.reason }
            }
            // 成功态不该走到这里；真走到了说明调用处判断写反了，留一句话便于定位
            is ShellResult.Ok -> "未预期的成功状态（调用处判断有误）"
        }
        val preview = when (result) {
            is ShellResult.Failed -> clean(result.rawPreview)
            else -> ""
        }
        val tail = if (preview.isEmpty()) "" else "\n原始输出片段：\n" + truncate(preview, "片段过长")
        return "第 3 步（启动 Ubuntu 环境）失败：" + reason + "\n库根：" + prootLibRoot + tail
    }
}
