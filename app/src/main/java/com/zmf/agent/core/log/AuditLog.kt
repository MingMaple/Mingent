package com.zmf.agent.core.log

import android.content.Context
import com.zmf.agent.core.AppPaths
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 安全审计日志：记录每一次拦截、放行、授权码签发/校验、权限档切换。
 *
 * 与 [FileLog]（开发者排障用、外部私有目录、可被日志页清空）**刻意分开**：
 * 审计日志是「系统做过什么判定」的证据，落 `filesDir/appdata/audit.log`，
 * 且该文件所在的 `appdata` 目录本身列入高危禁区：Agent 不能改写自己的审计记录。
 *
 * 设计取舍：
 * - 一行一条、`|` 分隔 `key=value`：不引 JSON 依赖，文本工具也能直接看；
 * - 超过 [MAX_BYTES] 轮转为 `audit.log.1`（只留一份），不会无限增长；
 * - 任何写失败都吞掉：审计失败不能反过来把业务弄崩（与 FileLog 同一条原则）。
 */
object AuditLog {

    private const val MAX_BYTES = 1L * 1024L * 1024L

    private const val FILE_NAME = "audit.log"

    private const val ROTATED_NAME = "audit.log.1"

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var stream: FileOutputStream? = null

    private var writtenBytes = 0L

    /** 绑定审计日志文件（`appdata/audit.log`）；重复调用无副作用。 */
    @Synchronized
    fun init(context: Context) {
        if (logFile != null) return
        logFile = File(AppPaths.dataDir(context), FILE_NAME)
    }

    /**
     * 记一条审计事件。
     *
     * @param event 事件类型：`allow` / `deny` / `confirm` / `approve` / `permission` / `token`
     * @param fields 键值对；值里的 `|` 会被替换掉，保证一行可解析
     */
    fun record(context: Context, event: String, vararg fields: Pair<String, String>) {
        if (logFile == null) init(context)
        val line = buildString {
            append(formatTime())
            append(" | event=").append(clean(event))
            for ((key, value) in fields) {
                if (value.isEmpty()) continue
                append(" | ").append(clean(key)).append('=').append(clean(value))
            }
            append('\n')
        }
        write(line.toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    private fun write(bytes: ByteArray) {
        val file = logFile ?: return
        try {
            if (stream == null) openLocked(file)
            if (writtenBytes + bytes.size > MAX_BYTES) {
                rotateLocked(file)
            }
            val out = stream ?: return
            out.write(bytes)
            writtenBytes += bytes.size
        } catch (e: Exception) {
            closeLocked()
        }
    }

    private fun openLocked(file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            stream = FileOutputStream(file, true)
            writtenBytes = file.length()
        }.onFailure { closeLocked() }
    }

    private fun rotateLocked(file: File) {
        closeLocked()
        val backup = File(file.parentFile, ROTATED_NAME)
        runCatching { backup.delete() }
        runCatching { file.renameTo(backup) }
        openLocked(file)
    }

    private fun closeLocked() {
        runCatching { stream?.close() }
        stream = null
    }

    private fun formatTime(): String = synchronized(timeFormat) { timeFormat.format(Date()) }

    /** 值里不能出现换行与竖线：一行一条，别让一条记录把日志结构撑破。 */
    private fun clean(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ').replace('|', '/').take(300)
}
