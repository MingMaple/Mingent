package com.zmf.agent.core.log

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志。
 *
 * 真机 adb logcat 无输出，调试依赖文件日志：写入外部私有目录 files/logs/agent.log，
 * 可用 adb pull 取回；超过 512KB 自动轮转为 agent.log.1。
 *
 * 采用「长驻句柄 + 每行一次 write」而非队列 + 写线程：卡顿根因是每行 open/close，而非 write；
 * open/close 在外部存储（sdcardfs/FUSE）上需几十至上百微秒，写入内核页缓存仅微秒级。
 * 该方案也保证进程被强杀时不丢失已写入字节：数据已在内核页缓存，进程终止仍会刷盘，
 * 仅断电或内核崩溃会丢失；BufferedOutputStream 会将已写数据转为用户态缓冲，进程被杀即丢失，故不使用。
 *
 * 代价：常驻一个 fd；轮转按 close → rename → reopen 执行（见 rotateLocked）。
 * 写操作在同一对象锁内串行化，锁内不执行 fsync；fsync 仅出现在 [flush]（崩溃处理器、onStop 与日志页使用）。
 */
object FileLog {

    private const val MAX_BYTES = 512L * 1024L

    private const val FILE_NAME = "agent.log"

    /** 轮转后的备份文件（见 rotateLocked），读取时排在当前文件之前。 */
    private const val ROTATED_NAME = "agent.log.1"

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    /** 长驻追加流；null 表示尚未打开，或上次写入失败后已关闭（下次 log 时惰性重开）。 */
    private var stream: FileOutputStream? = null

    /**
     * 当前文件已写入的字节数（自行累加，不逐行 stat）。
     *
     * 仅在打开或轮转时用 file.length() 对齐一次，之后纯加法；stat 同样进入内核，
     * 与省去的 open/close 属于同类成本。
     */
    private var writtenBytes = 0L

    /**
     * 下次允许尝试轮转的字节阈值（初始为 MAX_BYTES）。
     *
     * rename 失败（目录只读或被占用）时文件不会变小，后续每行仍会满足超限条件，
     * 导致逐行执行无意义的 close/rename/open。失败后将阈值后移一个 MAX_BYTES，
     * 最坏每 512KB 重试一次。
     */
    private var rotateLimit = MAX_BYTES

    /** 绑定日志文件（files/logs/agent.log）；重复调用无副作用，未 init 时 [log] 静默丢弃。 */
    fun init(context: Context) {
        if (logFile != null) return
        logFile = File(logDir(context), FILE_NAME)
    }

    /**
     * 日志目录：外部私有目录 files/logs/（不存在时创建）。
     *
     * 目录按需创建：日志页可能随时打开，不能假设 init() 已执行。
     * getExternalFilesDir 在外部存储未挂载时返回 null，此时退回内部 filesDir，
     * 避免 File(null, "logs") 解析出相对路径。
     */
    fun logDir(context: Context): File {
        val app = context.applicationContext
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 记录一条日志：拼行 → 编码 → 一次 write。
     *
     * 主线程可安全调用：锁内仅有内存拼装与一次页缓存写，无 open/close/fsync。
     * 例外是恰好触发轮转，此时执行 close/rename/reopen，每 512KB 发生一次，可忽略。
     */
    @Synchronized
    fun log(tag: String, message: String) {
        // 未 init 时静默丢弃：日志失败不应影响调用方
        if (logFile == null) return
        val bytes = (format(tag, message) + '\n').toByteArray(Charsets.UTF_8)
        try {
            val file = logFile ?: return
            if (stream == null) openLocked(file)
            if (writtenBytes + bytes.size > rotateLimit) {
                rotateLocked(file)
                // 轮转后 writtenBytes 归零；未归零说明 rename 失败，将阈值后移，避免逐行重试
                if (writtenBytes + bytes.size > rotateLimit) rotateLimit = writtenBytes + MAX_BYTES
            }
            val out = stream ?: return
            out.write(bytes)
            writtenBytes += bytes.size
        } catch (e: Exception) {
            // 磁盘满、文件被外部删除或挂载点卸载时关闭流，下次惰性重开。
            // 日志失败不得影响业务流程，故忽略异常且不重试（重试会演变为循环）
            closeLocked()
        }
    }

    /**
     * 把用户态缓冲推给内核，并做一次 fsync。
     *
     * 仅关键路径调用：崩溃处理器（崩溃前保底）、MainActivity.onStop（从最近任务移除）、日志页读取前。
     * fsync 为毫秒级真实等待，置于热路径等同于同步写盘。
     *
     * [timeoutMs] 仅为保持调用点不变而保留：目前没有队列可等待，它不参与任何逻辑。
     */
    @Synchronized
    fun flush(timeoutMs: Long = 500L) {
        runCatching {
            val out = stream ?: return
            out.flush()
            // 仅此步骤可保证断电不丢失；平时 write 的字节已在页缓存，进程终止后仍会写入磁盘
            out.fd.sync()
        }
    }

    /**
     * 读取日志：最新的在最前，最多 [maxLines] 行。
     *
     * 按时间正序拼接后整体倒序：agent.log.1 为轮转后的内容，排在当前 agent.log 之前。
     * 仅保留末尾 maxLines 行（两个文件合计可达 1MB），日志页只展示 2000 行，
     * 无需将整份日志构造为 String 对象放入内存。
     *
     * 读取前仅 flush 流、不执行 fsync：读取的是本进程刚写入的页缓存，无需承担落盘开销。
     */
    @Synchronized
    fun readLines(context: Context, maxLines: Int = 2000): List<String> {
        if (maxLines <= 0) return emptyList()
        runCatching { stream?.flush() }
        val dir = logDir(context)
        val kept = ArrayDeque<String>()
        for (name in listOf(ROTATED_NAME, FILE_NAME)) {
            val file = File(dir, name)
            if (!file.isFile) continue
            runCatching {
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (kept.size == maxLines) kept.removeFirst()
                        kept.addLast(line)
                    }
                }
            }
        }
        return kept.reversed()
    }

    /**
     * 清空日志：先关闭流再删除文件。
     *
     * 顺序不可颠倒：流打开时删除文件，后续 write 会写入已被 unlink 的 inode，
     * 文件系统中不可见但日志仍占用空间，形成「写入成功却无法打开」的问题。
     * 有意不重置 logFile：下次 log() 会惰性重开并重建 agent.log，无需重启进程。
     */
    @Synchronized
    fun clear(context: Context) {
        closeLocked()
        writtenBytes = 0L
        val dir = logDir(context)
        for (name in listOf(FILE_NAME, ROTATED_NAME)) {
            runCatching { File(dir, name).delete() }
        }
    }

    // ---- 内部：以下函数都要求调用方已持有对象锁 ----

    /** 惰性打开追加流，并把字节计数对齐到文件的真实长度。 */
    private fun openLocked(file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            stream = FileOutputStream(file, true)
            writtenBytes = file.length()
            // 新文件（含轮转后重开）复位阈值：上次失败后移的阈值不应延续到新文件
            if (writtenBytes == 0L) rotateLimit = MAX_BYTES
        }.onFailure {
            // 无法打开（目录被删、权限异常）时保持 null，等待下次重试
            closeLocked()
        }
    }

    /**
     * 轮转：close → 删除旧备份 → rename → reopen。
     *
     * 顺序不可颠倒：流打开时改名，写入会继续落到已改名的 inode，新文件始终为空，
     * 表现为日志停止输出，因此必须先关闭旧句柄再以新路径重新打开。
     */
    private fun rotateLocked(file: File) {
        closeLocked()
        val backup = File(file.parentFile, ROTATED_NAME)
        runCatching { backup.delete() }
        runCatching { file.renameTo(backup) }
        // reopen 会按真实长度重新对齐计数：rename 失败时文件仍在，从原文件末尾继续写入
        openLocked(file)
    }

    private fun closeLocked() {
        runCatching { stream?.close() }
        stream = null
    }

    /** 拼一行：「MM-dd HH:mm:ss.SSS [tag] message」。不带换行，由调用方补。 */
    private fun format(tag: String, message: String): String {
        // 仅 log() 调用且已持对象锁；此处加锁用于防止其他入口调用时缺少同步
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        return stamp + " [" + tag + "] " + message
    }
}
