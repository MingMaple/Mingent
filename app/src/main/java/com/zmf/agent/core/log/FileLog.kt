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
 * 这台 vivo 机型 adb logcat 完全没有输出，真机调试只能靠文件日志：
 * 写到 外部私有目录 files/logs/agent.log，再用 adb pull 取回。
 * 超过 512KB 自动轮转为 agent.log.1，避免长期运行把目录撑爆。
 *
 * 为什么是「长驻句柄 + 每行一次 write」，而不是队列 + 写线程：
 * 卡调用线程的根因不是 write，而是原来的 File.appendText 每行都 open/close 一次（外加一次 length()）。
 * 一次 write 到内核页缓存是微秒级，主线程扛得住；而 open/close 在外部存储（sdcardfs/FUSE）上要几十到上百微秒，
 * 长会话几百上千行下来就是一条条看得见的小卡顿。现在句柄常驻，每行只剩：
 * 拼字符串 + UTF-8 编码 + 一次 write + 一次字节累加。
 *
 * 顺带解决了「用户从最近任务强杀」的丢日志问题：write() 过的字节已经在内核页缓存里，
 * 进程被杀照样会被刷盘，只有断电/内核崩溃才会丢。队列方案必须靠定时唤醒，天生有一个时间窗口；
 * 这里没有窗口：已经进 write() 的行不会被进程死亡带走，只有磁盘写失败才会丢。
 *
 * 为什么不套 BufferedOutputStream：那会把「已写」变成「还在用户态缓冲」，进程一被杀就丢，
 * 正好抵消上面那条唯一的优势。要批量就得自己攒，攒就有窗口，所以不攒。
 *
 * 代价：常驻一个 fd；轮转时先 close 再 rename 再重开（见 rotateLocked）。写操作全在一把对象锁里串行化，
 * 但锁里只做页缓存写，绝不做 fsync——fsync 只在 [flush] 里出现，且只给崩溃处理器 / onStop / 日志页用。
 */
object FileLog {

    private const val MAX_BYTES = 512L * 1024L

    private const val FILE_NAME = "agent.log"

    /** 轮转出去的旧文件（见 rotateLocked）。读取时它排在当前文件前面。 */
    private const val ROTATED_NAME = "agent.log.1"

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    /** 长驻的追加流；null = 还没打开，或上次写失败后被主动关掉了（下次 log 惰性重开）。 */
    private var stream: FileOutputStream? = null

    /**
     * 当前文件里已写的字节数（自己累加，不每行 stat）。
     *
     * 只在该文件被打开 / 轮转时用 file.length() 对齐一次，之后纯加法——
     * 这是「每行只剩一次 write」的关键：stat 也要进内核，和省下的 open/close 是同一种成本。
     */
    private var writtenBytes = 0L

    /**
     * 下一次允许尝试轮转的字节数（初始 = MAX_BYTES）。
     *
     * 为什么要可变：rename 失败（目录只读、被外部占用）时文件不会变小，下一个行还会满足「超了」的条件，
     * 于是每记一行就白做一次 close/rename/open —— 日志模块自己变成热点，比不轮转还糟。
     * 失败后把阈值往后推一个 MAX_BYTES，最多每 512KB 重试一次。
     */
    private var rotateLimit = MAX_BYTES

    fun init(context: Context) {
        if (logFile != null) return
        logFile = File(logDir(context), FILE_NAME)
    }

    /**
     * 日志目录：外部私有目录 files/logs/（不存在就建）。
     *
     * 目录随取随建，因为日志页可能在任何时刻被打开，不能假设 init() 已经跑过——
     * 页面要的是一个能读的目录，而不是空指针或运行目录 "/logs"。
     * getExternalFilesDir 在外部存储没挂载时返回 null，此时退回内部 filesDir：
     * 宁可日志落到内部空间，也不要落到 File(null, "logs") 解析出来的相对路径上。
     */
    fun logDir(context: Context): File {
        val app = context.applicationContext
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 记一条日志：拼行 → 编码 → 一次 write，返回。
     *
     * 主线程可以放心调：锁内只有内存拼装 + 一次页缓存写（微秒级），没有 open/close/fsync。
     * 唯一的例外是「这一刻正好要轮转」——那次会 close/rename/reopen，512KB 才发生一次，
     * 摊到几千行上可以忽略；真要更平滑，可以把轮转挪到后台，但那会引入「轮转期间写哪」的状态机，不值得。
     */
    @Synchronized
    fun log(tag: String, message: String) {
        // 没 init 就静默丢掉：老实现同样直接 return，日志不该把调用方搞崩
        if (logFile == null) return
        val bytes = (format(tag, message) + '\n').toByteArray(Charsets.UTF_8)
        try {
            val file = logFile ?: return
            if (stream == null) openLocked(file)
            if (writtenBytes + bytes.size > rotateLimit) {
                rotateLocked(file)
                // 轮转后 writtenBytes 归零；没归零说明 rename 没成，把阈值推后，别每行都重试一次
                if (writtenBytes + bytes.size > rotateLimit) rotateLimit = writtenBytes + MAX_BYTES
            }
            val out = stream ?: return
            out.write(bytes)
            writtenBytes += bytes.size
        } catch (e: Exception) {
            // 磁盘满 / 文件被外部删除 / 挂载点被卸：关掉流、下次惰性重开。
            // 日志失败绝不能影响业务流程，所以这里吞掉异常，也不重试（重试只会把错误放大成循环）
            closeLocked()
        }
    }

    /**
     * 把用户态缓冲推给内核，并做一次 fsync。
     *
     * 只有关键路径该调：崩溃处理器（死之前保底）、MainActivity.onStop（从最近任务划走）、日志页读之前。
     * fsync 是毫秒级真实等待，放到热路径上就等于把「同步写盘」又加回来了。
     *
     * [timeoutMs] 只为调用点不改而保留：现在没有队列可等，它不参与任何逻辑。
     */
    @Synchronized
    fun flush(timeoutMs: Long = 500L) {
        runCatching {
            val out = stream ?: return
            out.flush()
            // 只有这一步才保证「此刻断电也不丢」；平时 write 过的字节已在页缓存，进程死不掉
            out.fd.sync()
        }
    }

    /**
     * 读日志：最新的在最前面，最多 [maxLines] 行。
     *
     * 顺序是「按时间正序拼接再整体倒序」：agent.log.1 是轮转出去的旧内容，排在当前 agent.log 前面。
     * 只留末尾 maxLines 行的做法是边读边丢队头（两个文件加起来能到 1MB），
     * 日志页只展示 2000 行，没必要把整份日志都变成 String 对象堆进内存。
     *
     * 读之前只 flush 流、不 fsync：读的是本进程刚写过的页缓存，flush 一下就够，没必要付落盘的钱。
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
     * 清空日志：先关流再删文件。
     *
     * 顺序不能反：流开着删文件的话，后续 write 会写进那个已被 unlink 的 inode——
     * 用户在文件系统里看不到，日志却还在占空间，是最难查的那种「日志写成功但打不开」。
     * 故意不重置 logFile：下一次 log() 会惰性重开并重建 agent.log，不用重启进程。
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
            // 新文件（含轮转后重开）把阈值复位：上一次失败留下的推后不该粘到新文件上
            if (writtenBytes == 0L) rotateLimit = MAX_BYTES
        }.onFailure {
            // 打不开（目录被删、权限异常）就保持 null，下次再来
            closeLocked()
        }
    }

    /**
     * 轮转：close → 删旧备份 → rename → reopen。
     *
     * 顺序不能反：流开着改名的话，写会继续落到那个已改名的旧 inode 上，新文件一直是空的，
     * 表现就是「日志看着停了」——所以必须关掉旧句柄再换个路径重新打开。
     */
    private fun rotateLocked(file: File) {
        closeLocked()
        val backup = File(file.parentFile, ROTATED_NAME)
        runCatching { backup.delete() }
        runCatching { file.renameTo(backup) }
        // reopen 会以真实长度重新对齐计数：rename 失败时文件还在，从原文末尾接着写
        openLocked(file)
    }

    private fun closeLocked() {
        runCatching { stream?.close() }
        stream = null
    }

    /** 拼一行：「MM-dd HH:mm:ss.SSS [tag] message」。不带换行，由调用方补。 */
    private fun format(tag: String, message: String): String {
        // 目前只有 log() 调它、而 log() 已持对象锁；这层锁是防以后有人从别的入口调它
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        return stamp + " [" + tag + "] " + message
    }
}
