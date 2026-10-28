package com.zmf.agent.core.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.terminal.TerminalEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 导出选项。 */
data class BackupOptions(
    val includeTerminal: Boolean = false,
    val includeLogs: Boolean = false,
    val includeMemory: Boolean = false
)

/** 备份清单（zip 里的 backup-manifest.json）。 */
data class BackupManifest(
    val format: Int,
    val applicationId: String,
    val versionName: String,
    val exportedAt: Long,
    val sections: List<String>
)

/** 导入结果：每段一行说明。 */
data class ImportReport(
    val restored: List<String>,
    val skipped: List<String>,
    val failed: List<String>
)

/**
 * 用户数据备份（导出 / 预览清单 / 导入）。
 *
 * 只依赖 JDK 自带的 java.util.zip、org.json 和 android.util.Xml，不引新依赖。
 * zip 内的包结构：
 *
 *   backup-manifest.json   清单：format / applicationId / versionName / exportedAt / sections
 *   data/<name>.json       应用自己的 JSON（sessions.json / workspaces.json / stats.json）
 *   prefs/<name>.xml       SharedPreferences（app_prefs.xml 导出前已删掉 api_key 那一条）
 *   workspace/             默认工作区文件（externalFilesDir/workspace，递归全部子目录）
 *   workspaces/            自建工作区文件（externalFilesDir/workspaces，递归全部子目录）
 *   logs/                  文件日志（externalFilesDir/logs，递归全部子目录）
 *   memory/                记忆数据（filesDir/mingmem/_Data，不含内核程序）
 *   terminal/              终端环境（filesDir/usr 递归全部子目录、common.sh、setup_fake_sysdata.sh）
 *
 * 原则：
 * - 重活全在 [Dispatchers.IO] 上做，边做边回调进度（百分比 + 阶段文案）；
 * - 导入**每段独立成败**：一段坏了不影响别的段，结果逐条进 [ImportReport]；
 * - 不抛给界面：只有整个包根本读不了才返回 Result.failure。
 */
object BackupArchive {

    /** 当前程序能认的最高格式号。 */
    const val FORMAT = 1

    private const val TAG = "backup"

    /** zip 里的清单文件名。 */
    private const val MANIFEST_NAME = "backup-manifest.json"

    private const val PREFIX_DATA = "data/"
    private const val PREFIX_PREFS = "prefs/"
    private const val PREFIX_WORKSPACE = "workspace/"
    private const val PREFIX_WORKSPACES = "workspaces/"
    private const val PREFIX_LOGS = "logs/"
    private const val PREFIX_TERMINAL = "terminal/"
    private const val PREFIX_SKILLS = "skills/"
    private const val PREFIX_MEMORY = "memory/"

    /** 记忆数据根：与 MemoryKernel 的 `filesDir/mingmem/_Data` 保持一致，只备份数据不备份程序。 */
    private const val MEMORY_DATA_PATH = "mingmem/_Data"
    private const val TEMP_MEMORY_DATA = "mingmem/_Data.importing"

    /** 应用自己的 JSON：都在 [AppPaths] 管的 appdata 目录里。mcp.json 是 1.1.0 新增（MCP 服务配置）。 */
    private val DATA_FILES =
        listOf(
            "sessions.json",
            "workspaces.json",
            "stats.json",
            "mcp.json",
            "providers.json",
            "goals.json",
            // 摘要/压缩事件：只读记录，换机排查「上下文是怎么被压掉的」全靠它
            "context_events.json"
        )

    /** 终端模块除 usr/ 之外还要带上的脚本。 */
    private val TERMINAL_ROOT_FILES = listOf("common.sh", "setup_fake_sysdata.sh")

    /**
     * 终端环境在 zip 里的条目名：**一整段 tar**，不是逐文件。
     *
     * 为什么改：zip 存不下软链接和权限位，Ubuntu 根文件系统靠这两样活着。上一版把 usr/ 里的
     * 每一个文件单独塞进 zip，导入后 /bin → usr/bin 变成普通目录、所有可执行位丢失，
     * 表现就是「终端一片黑、加进程闪一下就没、记忆内核报 shell 起不来」。
     * tar 这两样都能存，代价只是多一层解包。
     */
    private const val TERMINAL_TAR_ENTRY = PREFIX_TERMINAL + "usr.tar"

    /** 备份里可能写死的包名：正式版与共存版互为来源，导入时按当前包名改写。 */
    private const val BASE_PACKAGE_ID = "io.github.mingmaple.mingent"

    /** SharedPreferences 目录名。 */
    private const val PREFS_DIR = "shared_prefs"

    /** 存 API Key 的那份偏好文件：导出时删掉这个键，导入时永远不覆盖本机的 Key。 */
    private const val SENSITIVE_PREFS_XML = "app_prefs.xml"
    private const val SENSITIVE_KEY = "api_key"

    /** 工作区目录里不备份的东西。 */
    private const val TRASH_DIR = ".trash"
    private const val CACHE_DIR = "cache"
    private const val PART_SUFFIX = ".part"
    private const val TAR_XZ_SUFFIX = ".tar.xz"

    /** 解压用的临时目录（整体替换前的中转站）。 */
    private const val TEMP_USR = "usr.importing"
    private const val TEMP_WORKSPACE = ".import-workspace"
    private const val TEMP_WORKSPACES = ".import-workspaces"
    private const val TEMP_OLD_SUFFIX = ".import-old"

    /** 导入数据文件的临时后缀（刻意跟 store 自己的 .tmp 区分开，免得互相踩）。 */
    private const val IMPORT_TMP_SUFFIX = ".import.tmp"

    private const val COPY_BUFFER = 64 * 1024

    /** 进度权重的最小单位：每 64KB 算一格，小文件至少 1 格。 */
    private const val UNIT_BYTES = 64L * 1024L

    // ================= 导出 =================

    /**
     * 一次导出的结果。
     *
     * [bytes] 写进 zip 的总字节；[note] 需要额外告诉用户的一句话
     * （比如「终端环境未导出：…」），没有就是 null。
     */
    data class ExportOutcome(val bytes: Long, val note: String?)

    /** 写 zip 到 [uri]。onProgress(百分比 0..100, 阶段文案)。 */
    suspend fun export(
        context: Context,
        uri: Uri,
        options: BackupOptions,
        onProgress: (Int, String) -> Unit
    ): Result<ExportOutcome> = try {
        val outcome = withContext(Dispatchers.IO) {
            exportInternal(context.applicationContext, uri, options, onProgress)
        }
        Result.success(outcome)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FileLog.log(TAG, "导出失败：" + e)
        Result.failure(IllegalStateException("导出备份失败：" + describe(e), e))
    }

    /**
     * 打包主体。
     *
     * 先把要写的东西列成清单（顺手摸清体积用来算进度），再一口气写完：
     * 清单必须是最先写进 zip 的条目，所以不能边写边决定 sections。
     */
    private suspend fun exportInternal(
        app: Context,
        uri: Uri,
        options: BackupOptions,
        onProgress: (Int, String) -> Unit
    ): ExportOutcome {
        val plan = ArrayList<PlanItem>()
        val sections = ArrayList<String>()
        val missing = ArrayList<String>()

        /** 把一段的条目并进总清单；一个条目都没有就记成「跳过」。 */
        fun addSection(section: String, items: List<PlanItem>) {
            if (items.isEmpty()) {
                missing.add(section + "/")
                return
            }
            plan.addAll(items)
            sections.add(section)
        }

        // ---- data/：三个 JSON，缺哪个跳过哪个 ----
        val dataItems = ArrayList<PlanItem>()
        for (name in DATA_FILES) {
            val file = AppPaths.dataFile(app, name)
            if (file.isFile) {
                dataItems.add(PlanItem(PREFIX_DATA + name, file))
            } else {
                missing.add(PREFIX_DATA + name)
            }
        }
        addSection("data", dataItems)

        // ---- skills/：appdata/skills 下的技能包（SKILL.md 与它带的脚本）----
        val skillItems = ArrayList<PlanItem>()
        collectFiles(File(AppPaths.dataDir(app), "skills"), PREFIX_SKILLS, true, false, skillItems)
        addSection("skills", skillItems)

        // ---- memory/：仅当用户勾选；只带事件流与投影，不带 mingmem 程序本体 ----
        if (options.includeMemory) {
            val memoryItems = ArrayList<PlanItem>()
            collectFiles(File(app.filesDir, MEMORY_DATA_PATH), PREFIX_MEMORY, false, false, memoryItems)
            addSection("memory", memoryItems)
        }

        // ---- prefs/：shared_prefs 下所有 xml（app_prefs.xml 去掉 api_key）----
        val prefsItems = ArrayList<PlanItem>()
        val prefsDir = File(app.applicationInfo.dataDir, PREFS_DIR)
        val prefsFiles = prefsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            .orEmpty()
        for (file in prefsFiles) {
            prefsItems.add(
                PlanItem(
                    zipName = PREFIX_PREFS + file.name,
                    source = file,
                    filterApiKey = file.name == SENSITIVE_PREFS_XML
                )
            )
        }
        addSection("prefs", prefsItems)

        // ---- workspace/ 与 workspaces/：外部私有目录里的两组文件 ----
        val external = app.getExternalFilesDir(null)
        if (external != null) {
            val workspaceItems = ArrayList<PlanItem>()
            collectFiles(File(external, "workspace"), PREFIX_WORKSPACE, true, false, workspaceItems)
            addSection("workspace", workspaceItems)

            val workspacesItems = ArrayList<PlanItem>()
            collectFiles(File(external, "workspaces"), PREFIX_WORKSPACES, true, false, workspacesItems)
            addSection("workspaces", workspacesItems)
        } else {
            // 极少数机型拿不到外部目录：这两段直接不导
            missing.add(PREFIX_WORKSPACE)
            missing.add(PREFIX_WORKSPACES)
        }

        // ---- logs/：仅当用户勾了 ----
        if (options.includeLogs) {
            val logItems = ArrayList<PlanItem>()
            if (external != null) {
                collectFiles(File(external, "logs"), PREFIX_LOGS, true, false, logItems)
            }
            addSection("logs", logItems)
        }

        // ---- terminal/：仅当用户勾了；usr/ 整段打成 tar（见 TERMINAL_TAR_ENTRY 的注释）----
        // 先打到临时文件、成功之后才进 zip：根文件系统里有 PRoot 留下的挂载点目录读不了，
        // 边打边塞进 zip 的话一旦中途失败，zip 里那一条就是截断的（曾经导出到 40% 时提示直接消失）。
        var terminalTar: File? = null
        var terminalNote: String? = null
        if (options.includeTerminal) {
            val terminalItems = ArrayList<PlanItem>()
            val usr = File(app.filesDir, "usr")
            if (usr.isDirectory) {
                // 打包可能要十几秒到一分钟：先给用户一句交代，否则进度条一直停在 0%
                onProgress(0, "打包终端环境…")
                val packed = packTerminalTar(app, usr)
                terminalTar = packed.first
                terminalNote = packed.second
                packed.first?.let { terminalItems.add(PlanItem(TERMINAL_TAR_ENTRY, it)) }
            }
            for (name in TERMINAL_ROOT_FILES) {
                val file = File(app.filesDir, name)
                if (file.isFile) terminalItems.add(PlanItem(PREFIX_TERMINAL + name, file))
            }
            addSection("terminal", terminalItems)
        }

        val manifestItem = PlanItem(
            zipName = MANIFEST_NAME,
            bytes = buildManifest(app, sections).toString().toByteArray(Charsets.UTF_8)
        )

        var totalUnits = manifestItem.units
        for (item in plan) totalUnits += item.units

        // 进度状态放在函数作用域里，方便内层 local fun 直接累加
        var doneUnits = 0L
        var written = 0L
        // 所有条目是否都写完了：收尾异常能不能算成功，全看它
        var wroteEverything = false
        var lastPct = -2
        var label = "准备导出"
        /** 按已完成量算百分比，只有整数百分比变化时才回调一次。 */
        fun emit() {
            val pct = if (totalUnits <= 0L) 100 else ((doneUnits * 100L) / totalUnits).toInt().coerceIn(0, 100)
            if (pct != lastPct) {
                lastPct = pct
                onProgress(pct, label)
            }
        }

        onProgress(0, "准备导出")
        // "wt" 必须带：SAF 的默认 "w" 不截断，覆盖同名旧文件会留下尾巴
        val output = app.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("无法写入目标文件（打开失败）")
        try {
        output.use { raw ->
            ZipOutputStream(BufferedOutputStream(raw, COPY_BUFFER)).use { zos ->
                // 写一个条目（清单和普通文件共用同一套写法）
        /** 写一个 zip 条目并推进进度；条目顺序由调用方的计划列表决定。 */
                fun writeItem(item: PlanItem) {
                    label = "写入 " + item.zipName
                    val base = doneUnits
                    val planned = item.units
                    zos.putNextEntry(ZipEntry(item.zipName))
                    val source = item.source
                    if (source != null) {
                        if (item.filterApiKey) {
                            // app_prefs.xml：只删掉 name="api_key" 那一行（每个键一行，删一行文件仍然合法）
                            val text = source.readText(Charsets.UTF_8)
                            val filtered = text.lineSequence()
                                .filterNot { it.contains("name=\"" + SENSITIVE_KEY + "\"") }
                                .joinToString("\n")
                            val data = filtered.toByteArray(Charsets.UTF_8)
                            zos.write(data)
                            written += data.size
                        } else {
                            val length = source.length().coerceAtLeast(1L)
                            source.inputStream().use { input ->
                                val buffer = ByteArray(COPY_BUFFER)
                                var copied = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n <= 0) break
                                    zos.write(buffer, 0, n)
                                    written += n
                                    copied += n
                                    doneUnits = base + copied * planned / length
                                    emit()
                                }
                            }
                        }
                    } else {
                        val data = item.bytes ?: ByteArray(0)
                        zos.write(data)
                        written += data.size
                    }
                    zos.closeEntry()
                    // 收尾把这一格补满：按体积折算的整数除法会差一点，不能让进度永远停在 99
                    doneUnits = base + planned
                    emit()
                }

                // 清单必须第一个写：导入端只认第一个 backup-manifest.json
                writeItem(manifestItem)
                for (item in plan) {
                    coroutineContext.ensureActive()
                    writeItem(item)
                }
                // 所有条目都写完了：只有走到这里，收尾时的异常才可以按「写入已完整」处理
                wroteEverything = true
            }
        }
        } catch (e: Exception) {
            // 收尾异常（provider 侧 close/flush）**且**所有条目都已写完：数据确实落盘了，按成功处理。
            // 写到一半炸了就不行：那是一个没有目录区的半截 zip，按成功报等于骗人
            // （曾经终端环境 tar 中途报错，界面说「已导出」，文件其实打不开）。
            if (wroteEverything) {
                val size = contentLength(app, uri)
                if (size > 0L) {
                    FileLog.log(TAG, "导出收尾异常但条目已写完（" + size + " 字节），按成功处理：" + e)
                    onProgress(100, "导出完成")
                    return ExportOutcome(size, terminalNote)
                }
            }
            // 半截文件：删掉，别在下载目录里留一个打不开的垃圾
            runCatching { app.contentResolver.delete(uri, null, null) }
            FileLog.log(TAG, "导出中断，已删除半截文件：" + e)
            throw e
        } finally {
            // 临时 tar 是打包用的中转：成不成功都不该留在手机上（几百 MB）
            terminalTar?.let { runCatching { it.delete() } }
        }

        // 报给用户看的必须是**磁盘上那个文件**的大小：written 是未压缩字节，
        // 终端环境那种上 GB 的内容压完只剩三成，拿 written 显示会让人以为文件大得不对劲
        val onDisk = contentLength(app, uri).takeIf { it > 0L } ?: written
        FileLog.log(
            TAG,
            "导出完成：写入 " + written + " 字节（压缩后 " + onDisk + " 字节），段=" + sections +
                (if (missing.isEmpty()) "" else "，跳过=" + missing) +
                (if (terminalNote == null) "" else "，终端：" + terminalNote)
        )
        onProgress(100, "导出完成")
        return ExportOutcome(onDisk, terminalNote)
    }

    // ================= 预览清单 =================

    /** 只读清单，导入前用来给用户看内容摘要。 */
    suspend fun peekManifest(context: Context, uri: Uri): Result<BackupManifest> = try {
        val manifest = withContext(Dispatchers.IO) {
            readManifest(context.applicationContext, uri)
        }
        Result.success(manifest)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(IllegalStateException(describe(e), e))
    }

    // ================= 导入 =================

    /** 从 [uri] 还原。onProgress 同上。 */
    suspend fun import(
        context: Context,
        uri: Uri,
        onProgress: (Int, String) -> Unit
    ): Result<ImportReport> = try {
        val report = withContext(Dispatchers.IO) {
            importInternal(context.applicationContext, uri, onProgress)
        }
        Result.success(report)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FileLog.log(TAG, "导入失败：" + e)
        Result.failure(IllegalStateException("导入备份失败：" + describe(e), e))
    }

    /**
     * 解包主体。
     *
     * 每一段（data / prefs / workspace / workspaces / logs / terminal）各自处理、各自记结果：
     * 某一段坏掉只记一条 failed，不影响别的段，也不往外抛。
     */
    private suspend fun importInternal(
        app: Context,
        uri: Uri,
        onProgress: (Int, String) -> Unit
    ): ImportReport {
        val restored = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = ArrayList<String>()

        // 先独立读一次清单并校验格式（peek 那边用同一套规则给用户看过摘要）
        val manifest = readManifest(app, uri)
        validateManifest(manifest)

        val external = app.getExternalFilesDir(null)
        val filesDir = app.filesDir
        val usrDir = File(filesDir, "usr")
        val usrTemp = File(filesDir, TEMP_USR)
        val memoryTemp = File(filesDir, TEMP_MEMORY_DATA)
        val workspaceTemp = if (external != null) File(external, TEMP_WORKSPACE) else null
        val workspacesTemp = if (external != null) File(external, TEMP_WORKSPACES) else null

        // 上次半途失败留下的临时目录先清掉，别把旧垃圾一起换上去
        deleteTree(usrTemp)
        deleteTree(memoryTemp)
        if (workspaceTemp != null) deleteTree(workspaceTemp)
        if (workspacesTemp != null) deleteTree(workspacesTemp)

        var terminalFiles = 0
        var terminalUsrFiles = 0
        // 新格式：终端环境是整段 tar（软链接与权限位都在里面）
        var terminalUsrFromTar = false
        var workspaceFiles = 0
        var workspacesFiles = 0
        var logFiles = 0
        var skillFiles = 0
        var memoryFiles = 0

        // 进度：优先按「已读字节 / 包总大小」（zip 的压缩字节几乎线性推进）；
        // 拿不到总大小时退回按条目数估算（渐近到 95%，结束补 100），保证单调递增。
        val totalBytes = contentLength(app, uri)
        var readBytes = 0L
        var entries = 0
        var lastPct = -2
        var label = "读取备份"
        /** 按已读字节算百分比（拿不到总大小时按条目数渐近到 95%），只在变化时回调。 */
        fun emit() {
            val pct = if (totalBytes > 0L) {
                ((readBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
            } else {
                (entries * 100 / (entries + 25)).coerceIn(0, 95)
            }
            if (pct != lastPct) {
                lastPct = pct
                onProgress(pct, label)
            }
        }

        onProgress(0, "读取备份")
        try {
            val raw = app.contentResolver.openInputStream(uri)
            if (raw == null) {
                failed.add("无法读取备份文件（打开失败）")
            } else {
                raw.use { stream ->
                    val counting = CountingInputStream(BufferedInputStream(stream, COPY_BUFFER)) { n ->
                        readBytes += n
                        emit()
                    }
                    ZipInputStream(counting).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            coroutineContext.ensureActive()
                            entries++
                            val name = entry.name
                            if (!entry.isDirectory) {
                                label = "还原 " + name
                                try {
                                    when {
                                        // 清单已经读过，不再落盘
                                        name == MANIFEST_NAME -> Unit

                                        name.startsWith(PREFIX_DATA) -> {
                                            val fileName = name.removePrefix(PREFIX_DATA)
                                            if (fileName.isEmpty() || fileName.contains('/') || !fileName.endsWith(".json")) {
                                                throw IllegalStateException("数据条目名不合法")
                                            }
                                            // 先改写包名：正式版与共存版互为来源时，
                                            // 文件里写死的工作区/附件绝对路径要跟着换到当前包
                                            val text = rewritePackageIds(
                                                app,
                                                zis.readBytes().toString(Charsets.UTF_8)
                                            )
                                            // 必须先能解析成 JSON 才允许替换，坏包不能把好文件冲掉
                                            try {
                                                JSONObject(text)
                                            } catch (e: Exception) {
                                                throw IllegalStateException("内容不是合法的 JSON，已保留本机文件")
                                            }
                                            writeDataFile(app, fileName, text.toByteArray(Charsets.UTF_8))
                                            restored.add(dataLabel(fileName))
                                        }

                                        name.startsWith(PREFIX_PREFS) -> {
                                            val fileName = name.removePrefix(PREFIX_PREFS)
                                            if (fileName.isEmpty() || fileName.contains('/') || !fileName.endsWith(".xml")) {
                                                throw IllegalStateException("设置条目名不合法")
                                            }
                                            val bytes = zis.readBytes()
                                            val count = mergePrefs(
                                                app,
                                                fileName.removeSuffix(".xml"),
                                                bytes.toString(Charsets.UTF_8)
                                            )
                                            restored.add(
                                                "偏好设置 " + fileName + "：写入 " + count + " 项" +
                                                    (if (fileName == SENSITIVE_PREFS_XML) "，已跳过敏感字段 " + SENSITIVE_KEY else "")
                                            )
                                        }

                                        name.startsWith(PREFIX_WORKSPACES) -> {
                                            val rel = safeRelative(name.removePrefix(PREFIX_WORKSPACES))
                                                ?: throw IllegalStateException("路径不合法")
                                            val temp = workspacesTemp
                                                ?: throw IllegalStateException("外部目录不可用")
                                            if (workspacesFiles == 0) deleteTree(temp)
                                            writeEntryTo(zis, File(temp, rel))
                                            workspacesFiles++
                                        }

                                        name.startsWith(PREFIX_WORKSPACE) -> {
                                            val rel = safeRelative(name.removePrefix(PREFIX_WORKSPACE))
                                                ?: throw IllegalStateException("路径不合法")
                                            val temp = workspaceTemp
                                                ?: throw IllegalStateException("外部目录不可用")
                                            if (workspaceFiles == 0) deleteTree(temp)
                                            writeEntryTo(zis, File(temp, rel))
                                            workspaceFiles++
                                        }

                                        name.startsWith(PREFIX_LOGS) -> {
                                            val rel = safeRelative(name.removePrefix(PREFIX_LOGS))
                                                ?: throw IllegalStateException("路径不合法")
                                            val root = external
                                                ?: throw IllegalStateException("外部目录不可用")
                                            // 日志直接覆盖写，坏了也无所谓
                                            writeEntryTo(zis, File(File(root, "logs"), rel))
                                            logFiles++
                                        }

                                        name.startsWith(PREFIX_SKILLS) -> {
                                            val rel = safeRelative(name.removePrefix(PREFIX_SKILLS))
                                                ?: throw IllegalStateException("路径不合法")
                                            // 技能直接解到 appdata/skills（合并语义：同名覆盖，不删旧的技能）
                                            writeEntryTo(zis, File(File(AppPaths.dataDir(app), "skills"), rel))
                                            skillFiles++
                                        }

                                        name.startsWith(PREFIX_MEMORY) -> {
                                            val rel = safeRelative(name.removePrefix(PREFIX_MEMORY))
                                                ?: throw IllegalStateException("路径不合法")
                                            if (memoryFiles == 0) deleteTree(memoryTemp)
                                            writeEntryTo(zis, File(memoryTemp, rel))
                                            memoryFiles++
                                        }

                                        name == TERMINAL_TAR_ENTRY -> {
                                            // 整段 tar：解到 usr.importing 下，收尾阶段再整体换上去
                                            if (terminalUsrFromTar) {
                                                throw IllegalStateException("终端环境条目重复")
                                            }
                                            extractTar(app, zis, usrTemp)
                                            terminalUsrFromTar = true
                                            terminalFiles++
                                        }

                                        name.startsWith(PREFIX_TERMINAL) -> {
                                            val rel = safeRelative(name.removePrefix(PREFIX_TERMINAL))
                                                ?: throw IllegalStateException("路径不合法")
                                            if (rel == "usr") throw IllegalStateException("终端条目名不合法")
                                            if (rel.startsWith("usr/")) {
                                                // usr/ 解到 usr.importing，全部写完再整体换上去
                                                val inner = safeRelative(rel.removePrefix("usr/"))
                                                    ?: throw IllegalStateException("路径不合法")
                                                writeEntryTo(zis, File(usrTemp, inner))
                                                terminalUsrFiles++
                                            } else {
                                                // common.sh / setup_fake_sysdata.sh 之类的：直接落到 filesDir 根
                                                writeEntryTo(zis, File(filesDir, rel))
                                            }
                                            terminalFiles++
                                        }

                                        else -> skipped.add("未知段：" + firstSegment(name))
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // 单条失败只记一条，接着解下一条
                                    failed.add(name + "：" + describe(e))
                                }
                            }
                            emit()
                            entry = zis.nextEntry
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 包读到一半坏了：已经落盘的段照旧算数，剩下的记一条失败
            failed.add("读取备份中断：" + describe(e))
        }

        // ---- 收尾：把临时目录整体 rename 换成正式目录 ----
        if (workspaceFiles > 0 && external != null && workspaceTemp != null) {
            if (replaceDir(File(external, "workspace"), workspaceTemp)) {
                restored.add("工作区文件 workspace/：" + workspaceFiles + " 个文件")
            } else {
                failed.add("工作区文件（workspace/）：替换目录失败")
            }
        } else if (workspaceTemp != null) {
            deleteTree(workspaceTemp)
        }

        if (workspacesFiles > 0 && external != null && workspacesTemp != null) {
            if (replaceDir(File(external, "workspaces"), workspacesTemp)) {
                restored.add("自建工作区文件 workspaces/：" + workspacesFiles + " 个文件")
            } else {
                failed.add("自建工作区文件（workspaces/）：替换目录失败")
            }
        } else if (workspacesTemp != null) {
            deleteTree(workspacesTemp)
        }

        if (memoryFiles > 0) {
            if (replaceDir(File(filesDir, MEMORY_DATA_PATH), memoryTemp)) {
                restored.add("记忆数据 memory/：" + memoryFiles + " 个文件")
            } else {
                failed.add("记忆数据（memory/）：替换目录失败")
                deleteTree(memoryTemp)
            }
        } else {
            deleteTree(memoryTemp)
        }

        if (terminalUsrFromTar) {
            // 新格式：tar 解出来的结构是 usrTemp/usr，整体换到 filesDir/usr
            if (replaceDir(usrDir, File(usrTemp, "usr"))) {
                restored.add("终端环境：软链接与权限位已保留，可直接使用")
            } else {
                failed.add("终端环境 terminal/usr.tar：替换 usr 目录失败")
                deleteTree(usrTemp)
            }
        } else if (terminalUsrFiles > 0) {
            if (replaceDir(usrDir, usrTemp)) {
                // 旧格式（逐文件塞进 zip）还原出来的根文件系统一定缺软链接与可执行位：
                // 与其让用户开终端撞黑屏，不如当场清掉，让启动脚本按内置镜像重建（约 1 分钟）
                TerminalEnv.reinstall(app)
                restored.add(
                    "终端环境（旧格式，" + terminalFiles + " 个文件）：文件已还原，" +
                        "但 Ubuntu 根文件系统已自动清空并重建（旧格式无法保存软链接与权限位）"
                )
            } else {
                failed.add("终端环境（旧格式）：替换 usr 目录失败")
            }
        } else if (terminalFiles > 0) {
            // 只有 common.sh 之类的脚本：没有根文件系统要换
            deleteTree(usrTemp)
            restored.add("终端环境脚本：" + terminalFiles + " 个文件")
        } else {
            deleteTree(usrTemp)
        }

        if (manifest.applicationId.isNotBlank() && manifest.applicationId != app.packageName) {
            restored.add(
                "包名：备份来源为 " + manifest.applicationId + "，其中写有旧包名的路径已改写为 " + app.packageName
            )
        }
        if (logFiles > 0) restored.add("日志 logs/：" + logFiles + " 个文件")
        if (skillFiles > 0) restored.add("技能 skills/：" + skillFiles + " 个文件")

        onProgress(100, "导入完成")
        FileLog.log(
            TAG,
            "导入完成：格式=" + manifest.format + "，还原=" + restored.size +
                "，跳过=" + skipped.size + "，失败=" + failed.size
        )
        return ImportReport(restored = restored, skipped = skipped, failed = failed)
    }

    // ================= 清单 =================

    /** 组装清单；字段名就是 [BackupManifest] 里的名字。 */
    private fun buildManifest(app: Context, sections: List<String>): JSONObject {
        val obj = JSONObject()
        obj.put("format", FORMAT)
        obj.put("applicationId", app.packageName)
        obj.put("versionName", versionName(app))
        obj.put("exportedAt", System.currentTimeMillis())
        val array = JSONArray()
        for (section in sections) array.put(section)
        obj.put("sections", array)
        return obj
    }

    private fun versionName(app: Context): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        // 取不到就当空串，不影响备份本身
        ""
    }

    /** 打开一次包，只读到清单就停（不整包解压）。 */
    private fun readManifest(app: Context, uri: Uri): BackupManifest {
        val text = try {
            val stream = app.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取备份文件（打开失败）")
            stream.use { raw ->
                ZipInputStream(BufferedInputStream(raw, COPY_BUFFER)).use { zis ->
                    readManifestEntry(zis)
                }
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            // 不是 zip / 头部结构坏了
            throw IllegalStateException("这不是本应用的备份文件", e)
        }
        // zip 能打开但没有清单条目：不是本应用导出的包
        val body = text ?: throw IllegalStateException("这不是本应用的备份文件")
        return parseManifest(body)
    }

    private fun readManifestEntry(zis: ZipInputStream): String? {
        var entry = zis.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name == MANIFEST_NAME) {
                // readBytes 只读到条目数据末尾（ZipInputStream 在条目边界返回 -1），不会吃掉后面的条目
                return zis.readBytes().toString(Charsets.UTF_8)
            }
            entry = zis.nextEntry
        }
        return null
    }

    private fun parseManifest(text: String): BackupManifest {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalStateException("备份清单损坏，无法识别", e)
        }
        val sections = ArrayList<String>()
        val array = obj.optJSONArray("sections")
        if (array != null) {
            for (i in 0 until array.length()) {
                val section = array.optString(i, "")
                if (section.isNotEmpty()) sections.add(section)
            }
        }
        return BackupManifest(
            format = obj.optInt("format", 0),
            applicationId = obj.optString("applicationId", ""),
            versionName = obj.optString("versionName", ""),
            exportedAt = obj.optLong("exportedAt", 0L),
            sections = sections
        )
    }

    /** 格式校验：比本程序新就直说；连 format 都没有就不是本应用的包。 */
    private fun validateManifest(manifest: BackupManifest) {
        if (manifest.format > FORMAT) {
            throw IllegalStateException("备份来自更新版本，请先升级 App")
        }
        if (manifest.format <= 0) {
            throw IllegalStateException("这不是本应用的备份文件")
        }
    }

    // ================= 数据文件 =================

    /** 先写 .tmp 再 rename：坏包不会把正在用的文件写成半截。 */
    private fun writeDataFile(app: Context, fileName: String, bytes: ByteArray) {
        val target = AppPaths.dataFile(app, fileName)
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val temp = File(parent, fileName + IMPORT_TMP_SUFFIX)
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            // 个别文件系统 rename 不覆盖已有文件：删掉再试一次，最后退化成直接覆盖
            target.delete()
            if (!temp.renameTo(target)) {
                target.writeBytes(bytes)
                temp.delete()
            }
        }
    }

    /** 数据段在报告里显示的名字。 */
    private fun dataLabel(fileName: String): String = when (fileName) {
        "sessions.json" -> "会话数据 sessions.json"
        "workspaces.json" -> "工作区列表 workspaces.json"
        "stats.json" -> "用量统计 stats.json"
        "providers.json" -> "模型提供商与密钥 providers.json"
        "goals.json" -> "长期目标 goals.json"
        "context_events.json" -> "摘要与压缩事件 context_events.json"
        else -> "数据文件 " + fileName
    }

    // ================= 偏好设置 =================

    /**
     * 按 key 合并 SharedPreferences，**不整体覆盖文件**（别的键保持本机原值）。
     *
     * api_key 一律跳过：永远不用备份里的 Key 覆盖本机的 Key。
     * 返回实际写进去的键数量。
     */
    private fun mergePrefs(app: Context, prefsName: String, xml: String): Int {
        val editor = app.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
        var applied = 0
        val parser = Xml.newPullParser()
        // 个别 XML 带 BOM，先切掉，免得解析器在第一个字符上就报错
        parser.setInput(StringReader(xml.removePrefix("\uFEFF")))
        if (!seekStartTag(parser, "map")) {
            // 没有 map 根：当成空文件，什么都不写
            return 0
        }
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.name == "map") break
            if (event != XmlPullParser.START_TAG) continue
            val type = parser.name
            val key = parser.getAttributeValue(null, "name")
            if (key.isNullOrEmpty() || key == SENSITIVE_KEY) {
                // 没有 name 或者是要永远跳过的 Key：整段吃掉，不动本机
                skipElement(parser)
                continue
            }
            when (type) {
                "string" -> {
                    // 工作区路径这类值里写死着包名，换包名（正式版 ↔ 共存版）导入时要跟着改
                    editor.putString(key, rewritePackageIds(app, readText(parser)))
                    applied++
                }
                "int" -> {
                    editor.putInt(key, parser.getAttributeValue(null, "value")?.toIntOrNull() ?: 0)
                    applied++
                }
                "long" -> {
                    editor.putLong(key, parser.getAttributeValue(null, "value")?.toLongOrNull() ?: 0L)
                    applied++
                }
                "float" -> {
                    editor.putFloat(key, parser.getAttributeValue(null, "value")?.toFloatOrNull() ?: 0f)
                    applied++
                }
                "boolean" -> {
                    editor.putBoolean(key, parser.getAttributeValue(null, "value") == "true")
                    applied++
                }
                "set" -> {
                    editor.putStringSet(
                        key,
                        readStringSet(parser).map { rewritePackageIds(app, it) }.toSet()
                    )
                    applied++
                }
                // 不认识的类型（以后 SharedPreferences 加了新类型）：跳过，不猜
                else -> skipElement(parser)
            }
        }
        // 导入是一次性动作：用 commit() 保证返回前已经落盘
        editor.commit()
        return applied
    }

    /** 把解析游标推到指定名字的 START_TAG；推不到返回 false。 */
    private fun seekStartTag(parser: XmlPullParser, name: String): Boolean {
        while (true) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == name) return true
                    parser.next()
                }
                XmlPullParser.END_DOCUMENT -> return false
                else -> parser.next()
            }
        }
    }

    /** 游标停在某个 START_TAG 上，把整个元素（含子元素）吃干净。 */
    private fun skipElement(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
                else -> Unit
            }
        }
    }

    /** 读当前元素的文本（空元素返回空串）。读完游标停在它的 END_TAG 上。 */
    private fun readText(parser: XmlPullParser): String {
        val builder = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF ->
                    builder.append(parser.text ?: "")
                XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> return builder.toString()
                else -> Unit
            }
        }
    }

    /** 读 set 里的字符串集合；读完游标停在 set 的 END_TAG 上。 */
    private fun readStringSet(parser: XmlPullParser): Set<String> {
        val values = LinkedHashSet<String>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> values.add(readText(parser))
                XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> return values
                else -> Unit
            }
        }
    }

    // ================= 文件工具 =================

    /** 递归收集目录里的普通文件（迭代实现，避免很深的目录把栈撑爆）。 */
    private fun collectFiles(
        root: File,
        prefix: String,
        skipDirs: Boolean,
        skipTarXz: Boolean,
        out: MutableList<PlanItem>
    ) {
        if (!root.isDirectory) return
        val stack = ArrayDeque<Pair<File, String>>()
        stack.addLast(root to "")
        while (stack.isNotEmpty()) {
            val (dir, base) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                val name = child.name
                val rel = if (base.isEmpty()) name else base + "/" + name
                if (child.isDirectory) {
                    if (skipDirs && shouldSkipDir(name)) continue
                    stack.addLast(child to rel)
                } else if (child.isFile) {
                    if (name.endsWith(PART_SUFFIX)) continue
                    if (skipTarXz && name.endsWith(TAR_XZ_SUFFIX)) continue
                    out.add(PlanItem(zipName = prefix + rel, source = child))
                }
            }
        }
    }

    /**
     * 把终端环境（filesDir/usr）打成一份 tar，返回 (临时文件, 给用户看的一句话)。
     *
     * 为什么必须是 tar：zip 不存软链接、不存权限位，而 Ubuntu 根文件系统恰恰靠这两样活着
     * （/bin → usr/bin 的链接、所有可执行位）。上一版逐文件塞进 zip，导入之后的终端必然黑屏。
     *
     * 为什么自己写 tar、不借 busybox：busybox 按 argv[0] 派发 applet，App 里能直接执行的那份
     * 文件名是 libbusybox.so，跑起来只报 "applet not found"（实测两次都折在这一步）。
     * 自己写还有个必需的好处：PRoot 留下的挂载点目录读不了，可以就地跳过，不会因为一个目录让整包失败。
     *
     * 为什么先落临时文件再进 zip：边打边塞进 zip 的话，中途失败时 zip 里那一条就是截断的，
     * 整个备份跟着作废（表现就是「导出到 40% 提示直接消失，文件打不开」）。
     */
    private fun packTerminalTar(app: Context, dir: File): Pair<File?, String?> {
        val tarFile = File(app.cacheDir, "terminal-usr.tar")
        runCatching { tarFile.delete() }
        val base = dir.parentFile
            ?: return null to "终端环境未导出：目录不合法"
        val counter = intArrayOf(0)
        return try {
            val skipped: Int
            BufferedOutputStream(FileOutputStream(tarFile), COPY_BUFFER).use { out ->
                skipped = tarTree(out, base, dir, counter)
                // tar 结尾：两个全零块
                out.write(ByteArray(1024))
            }
            if (counter[0] <= 0) {
                runCatching { tarFile.delete() }
                null to "终端环境未导出：没有可打包的文件"
            } else {
                FileLog.log(
                    TAG,
                    "终端环境打包完成：" + tarFile.length() + " 字节，" + counter[0] + " 个条目，跳过 " + skipped
                )
                tarFile to if (skipped > 0) {
                    "终端环境已导出；其中 " + skipped + " 项因权限限制未能收录"
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            runCatching { tarFile.delete() }
            FileLog.log(TAG, "终端环境打包失败：" + e)
            null to "终端环境未导出：" + (e.message ?: e.javaClass.simpleName).take(160)
        }
    }

    /**
     * 递归把一个目录写成 tar 条目，返回被跳过的项数。
     *
     * 只做 ustar 里够用的三种：目录（5）、普通文件（0）、软链接（2）；
     * 名字或链接目标超过 100 字节时先写 GNU 的 L / K 扩展条目（busybox tar 解包认这两种）。
     */
    private fun tarTree(out: OutputStream, base: File, file: File, counter: IntArray): Int {
        val rel = try {
            file.relativeTo(base).path.replace('\\', '/')
        } catch (e: Exception) {
            return 1
        }
        if (rel.isEmpty() || rel == ".") return 0
        val path = file.toPath()
        val isLink = runCatching { Files.isSymbolicLink(path) }.getOrDefault(false)
        val mtime = file.lastModified() / 1000L
        if (isLink) {
            val target = runCatching { Files.readSymbolicLink(path).toString() }.getOrNull() ?: return 1
            writeTarHeader(out, rel, 0L, mtime, '2', target, 0x1FF)
            counter[0]++
            return 0
        }
        if (file.isDirectory) {
            // 读不了目录（PRoot 挂载点常见）：跳过整棵子树，不让它中断打包
            val children = if (file.canRead()) file.listFiles() else null
            if (children == null) {
                FileLog.log(TAG, "打包跳过不可读目录：" + rel)
                return 1
            }
            writeTarHeader(out, rel + "/", 0L, mtime, '5', "", if (file.canExecute()) 0x1ED else 0x1C0)
            counter[0]++
            var skipped = 0
            for (child in children) skipped += tarTree(out, base, child, counter)
            return skipped
        }
        if (!file.isFile) return 1
        if (!file.canRead()) {
            FileLog.log(TAG, "打包跳过不可读文件：" + rel)
            return 1
        }
        val size = file.length()
        writeTarHeader(out, rel, size, mtime, '0', "", if (file.canExecute()) 0x1ED else 0x1A4)
        // 内容读失败属于真异常：已经写了头，这条目只能作罢让整包失败（不写半截文件进去）
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER)
            var copied = 0L
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
                copied += n
            }
            val pad = ((512 - (copied % 512)) % 512).toInt()
            if (pad > 0) out.write(ByteArray(pad))
        }
        counter[0]++
        return 0
    }

    /** 写一个 ustar 头；名字或链接目标过长时先补 GNU L / K 扩展条目。 */
    private fun writeTarHeader(
        out: OutputStream,
        name: String,
        size: Long,
        mtime: Long,
        type: Char,
        linkName: String,
        mode: Int
    ) {
        if (utf8Length(name) > 100) writeGnuLongLink(out, name, 'L', mtime)
        if (utf8Length(linkName) > 100) writeGnuLongLink(out, linkName, 'K', mtime)
        val header = ByteArray(512)
        putString(header, 0, 100, name)
        putOctal(header, 100, 8, mode.toLong())
        putOctal(header, 108, 8, 0L)
        putOctal(header, 116, 8, 0L)
        putOctal(header, 124, 12, size)
        putOctal(header, 136, 12, mtime)
        header[156] = type.code.toByte()
        putString(header, 157, 100, linkName)
        putString(header, 257, 6, "ustar")
        putString(header, 263, 2, "00")
        putString(header, 265, 32, "root")
        putString(header, 297, 32, "root")
        putChecksum(header)
        out.write(header)
    }

    /** GNU 长名条目：内容就是那个超长字符串，类型 L（名字）或 K（链接目标）。 */
    private fun writeGnuLongLink(out: OutputStream, value: String, type: Char, mtime: Long) {
        val bytes = (value + "\u0000").toByteArray(Charsets.UTF_8)
        val header = ByteArray(512)
        putString(header, 0, 100, "././@LongLink")
        putOctal(header, 100, 8, 0x1A4L)
        putOctal(header, 108, 8, 0L)
        putOctal(header, 116, 8, 0L)
        putOctal(header, 124, 12, bytes.size.toLong())
        putOctal(header, 136, 12, mtime)
        header[156] = type.code.toByte()
        putString(header, 257, 6, "ustar")
        putString(header, 263, 2, "00")
        putChecksum(header)
        out.write(header)
        out.write(bytes)
        val pad = (512 - (bytes.size % 512)) % 512
        if (pad > 0) out.write(ByteArray(pad))
    }

    /** 校验和字段：先按「八个空格」算无符号字节和，再写成六位八进制 + NUL + 空格。 */
    private fun putChecksum(header: ByteArray) {
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var sum = 0
        for (b in header) sum += (b.toInt() and 0xFF)
        putString(header, 148, 8, String.format(java.util.Locale.US, "%06o", sum) + "\u0000 ")
    }

    /** 定长字段写八进制：宽度减一填数字（补零），最后一个字节留给 NUL。 */
    private fun putOctal(buf: ByteArray, offset: Int, length: Int, value: Long) {
        val digits = length - 1
        val text = java.lang.Long.toOctalString(value)
        val padded = if (text.length >= digits) text.substring(text.length - digits) else "0".repeat(digits - text.length) + text
        putString(buf, offset, digits, padded)
        buf[offset + length - 1] = 0
    }

    /** 定长字段写 UTF-8（超长截断），剩下的保持 0。 */
    private fun putString(buf: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val count = minOf(bytes.size, length)
        System.arraycopy(bytes, 0, buf, offset, count)
    }

    private fun utf8Length(value: String): Int = value.toByteArray(Charsets.UTF_8).size
    /**
     * App 自带的 busybox 在哪（**文件名必须叫 busybox**）。
     *
     * busybox 靠 argv[0] 派发 applet：以 libbusybox.so 的名字执行，它去找一个叫
     * "libbusybox.so" 的 applet，只回一句 applet not found / 退出码 127。
     *
     * 优先 filesDir/usr/bin/busybox（终端启动时由 TerminalManager 建好的软链，名字对）；
     * 终端还没启动过（或刚重装被清掉）就没有这条软链，那就把 APK 里那份 libbusybox.so
     * 复制到 cache/bin/busybox 再执行：直接用 .so 会让导入报 EPIPE（实测）。
     */
    private fun busyboxBinary(app: Context): File? {
        val linked = File(File(app.filesDir, "usr/bin"), "busybox")
        if (linked.exists()) {
            if (!linked.canExecute()) linked.setExecutable(true, false)
            if (linked.canExecute()) return linked
        }
        val source = File(app.applicationInfo.nativeLibraryDir, "libbusybox.so")
        if (!source.exists()) return null
        return try {
            val target = File(File(app.cacheDir, "bin"), "busybox")
            if (!target.exists() || target.length() != source.length()) {
                target.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
                }
            }
            target.setExecutable(true, false)
            if (target.canExecute()) target else null
        } catch (e: Exception) {
            FileLog.log(TAG, "准备 busybox 失败：" + e)
            null
        }
    }

    /** 目录里所有文件的体积合计。只用来估进度，量不出来就当 0（进度按一格算）。 */
    private fun fileTreeSize(root: File): Long = try {
        var total = 0L
        var seen = 0
        root.walkTopDown().forEach { file ->
            // 上限兜一手：软链接指回祖辈的目录会让 walk 打转，估个进度不值得卡死
            if (++seen > 200_000) return@forEach
            if (file.isFile) total += file.length()
        }
        total
    } catch (e: Exception) {
        0L
    }

    /**
     * 终端环境段导出的真实体积（字节）：filesDir/usr 递归 + 两个根脚本，和 [export] 进包的内容一致。
     *
     * 界面上的体积注释按它显示，不再写死常量：用户 apt 装了多少，这里就报多少。
     */
    fun terminalSectionBytes(app: Context): Long {
        var total = fileTreeSize(File(app.filesDir, "usr"))
        TERMINAL_ROOT_FILES.forEach { name ->
            val file = File(app.filesDir, name)
            if (file.isFile) total += file.length()
        }
        return total
    }

    /**
     * 备份里写死的包名换成当前包名。
     *
     * 正式版（io.github.mingmaple.mingent）与共存版（…​.dev）互为来源时，偏好里存的工作区绝对路径、
     * 会话里记的附件路径都带着旧包名，原样导入会指向另一个 App 的目录（甚至根本不存在）。
     * 只改这两个已知包名，别的字符串一个字节都不碰。
     */
    private fun rewritePackageIds(app: Context, text: String): String {
        if (!text.contains(BASE_PACKAGE_ID)) return text
        val current = app.packageName
        // 先换长的（带 .dev 的那个）：…mingent 是 …mingent.dev 的前缀，顺序反了会拼出 .dev.dev
        var out = text.replace(BASE_PACKAGE_ID + ".dev", current)
        out = out.replace(Regex(Regex.escape(BASE_PACKAGE_ID) + "(?!\\.dev)"), current)
        return out
    }

    /** 工作区目录里不备份的目录：回收站、缓存、以及所有以 . 开头的隐藏目录。 */
    private fun shouldSkipDir(name: String): Boolean =
        name == TRASH_DIR || name == CACHE_DIR || name.startsWith(".")

    /**
     * 把当前 zip 条目（tar 流）解开到 [usrTemp]。
     *
     * 解出来的结构是 usrTemp/usr/…，收尾阶段再整体换到 filesDir/usr。
     * 用 busybox tar 的 -p：软链接与权限位就是靠它保住；App 自己那套 zip 解压做不到。
     */
    private fun extractTar(app: Context, zis: ZipInputStream, usrTemp: File) {
        val busybox = busyboxBinary(app)
            ?: throw IllegalStateException("找不到 busybox，无法还原终端环境（先打开一次终端再导入）")
        deleteTree(usrTemp)
        if (!usrTemp.mkdirs() && !usrTemp.isDirectory) {
            throw IllegalStateException("临时目录建不出来：" + usrTemp.absolutePath)
        }
        val process = ProcessBuilder(
            busybox.absolutePath,
            "tar", "-xpf", "-",
            "-C", usrTemp.absolutePath
        ).start()
        val errors = StringBuilder()
        val errorReader = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (errors.length < 4000) errors.append(line).append('\n')
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            // 管道可能在中途断掉（busybox 早退）：这不算「这条 zip 条目坏了」之外的另一种结果，
            // 统一在下面按退出码 + stderr 报，别让 EPIPE 这种实现细节直接怼到用户脸上。
            var writeError: Exception? = null
            try {
                process.outputStream.use { out ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = zis.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                    }
                }
            } catch (e: Exception) {
                writeError = e
            }
            val code = process.waitFor()
            if (code != 0 || writeError != null) {
                runCatching { errorReader.join(1000) }
                val reason = when {
                    errors.isNotEmpty() -> errors.toString().trim().take(300)
                    writeError != null -> writeError.message ?: writeError.javaClass.simpleName
                    else -> ""
                }
                throw IllegalStateException(
                    "busybox tar 解包失败（退出码 " + code + "）" + if (reason.isEmpty()) "" else "：" + reason
                )
            }
        } finally {
            runCatching { process.destroy() }
            errorReader.interrupt()
        }
    }

    /** 把当前 zip 条目的内容写到 [target]，父目录自动建。 */
    private fun writeEntryTo(zis: ZipInputStream, target: File): Long {
        target.parentFile?.mkdirs()
        var total = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val n = zis.read(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
                total += n
            }
        }
        return total
    }

    /**
     * 目录整体替换：临时目录 → 正式目录。
     *
     * 先把旧目录 rename 到 .import-old（不是直接删），换成功再删旧的；
     * 换不上去就把旧的搬回来，保证「导入失败不毁数据」。
     */
    private fun replaceDir(target: File, temp: File): Boolean {
        if (!temp.exists()) return false
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val old = File(parent, target.name + TEMP_OLD_SUFFIX)
        deleteTree(old)
        val hadOld = target.exists()
        if (hadOld && !target.renameTo(old)) {
            FileLog.log(TAG, "旧目录挪不开，放弃替换：" + target.absolutePath)
            return false
        }
        if (temp.renameTo(target)) {
            deleteTree(old)
            return true
        }
        // 换不上去：把旧的搬回来，保持原样
        if (hadOld) old.renameTo(target)
        FileLog.log(TAG, "目录替换失败：" + target.absolutePath)
        return false
    }

    /** 递归删除；失败只记日志（清临时目录用，删不掉不致命）。 */
    private fun deleteTree(file: File) {
        try {
            if (file.exists()) file.deleteRecursively()
        } catch (e: Exception) {
            FileLog.log(TAG, "清理目录失败：" + file.absolutePath + "：" + e)
        }
    }

    /** 校验 zip 条目里的相对路径，挡掉 ../ 之类的越界写法；不合法返回 null。 */
    private fun safeRelative(raw: String): String? {
        val cleaned = raw.trim().replace('\\', '/')
        if (cleaned.isEmpty() || cleaned.startsWith("/")) return null
        val parts = cleaned.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return cleaned
    }

    /** zip 条目的第一段，用于「未知段：xxx」的说明。 */
    private fun firstSegment(name: String): String {
        val index = name.indexOf('/')
        return if (index <= 0) name else name.substring(0, index)
    }

    /** 目标文件的总字节数；SAF 拿不到就返回 -1（导入进度退回按条目数估）。 */
    private fun contentLength(app: Context, uri: Uri): Long = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun describe(e: Throwable): String {
        val message = e.message
        return if (message.isNullOrBlank()) e.javaClass.simpleName else message
    }

    /** 导出计划里的一个 zip 条目。 */
    private class PlanItem(
        val zipName: String,
        val source: File? = null,
        val bytes: ByteArray? = null,
        /** app_prefs.xml 专用：写之前删掉 name="api_key" 那行。 */
        val filterApiKey: Boolean = false
    ) {
        /** 进度权重：按体积折算（每 64KB 一格），小文件至少一格。 */
        val units: Long
            get() = when {
                source != null -> maxOf(1L, source.length() / UNIT_BYTES)
                bytes != null -> maxOf(1L, bytes.size.toLong() / UNIT_BYTES)
                else -> 1L
            }
    }
}

/** 统计已读字节的包装流：导入时拿它算进度。 */
private class CountingInputStream(
    private val delegate: InputStream,
    private val onRead: (Long) -> Unit
) : InputStream() {

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) onRead(1L)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) onRead(n.toLong())
        return n
    }

    override fun available(): Int = delegate.available()

    override fun close() {
        delegate.close()
    }
}
