package com.zmf.agent.tools.file

import android.content.Context
import android.system.Os
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.tools.PathGate
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * 回收站：删除先落进这里，「撤销」与「清空」都由用户自己发起。
 *
 * 为什么按「卷」建池：回收站的价值建立在 `rename` 的 O(1) 原子上；跨卷 rename 会退化成
 * 「复制 + 删除」，既慢又可能在中途断电，还占双倍空间。所以池必须和目标在同一个 `st_dev` 上，
 * 选中方式比较设备号，不比较路径前缀（前缀比较会被绑定/软链接骗）。
 *
 * 池放在 `Android/data/<pkg>/files/.trash`：
 * - SAF 与 MediaStore 都不展示它，不进相册、不进文件选择器；
 * - 模型默认看不见（Agent 对回收站是黑区，见 [com.zmf.agent.tools.HighRiskGate] 的禁区判定）。
 *
 * 目录结构：
 * ```
 * .trash/.nomedia
 * .trash/<sessionId>/<时间戳>-<随机6位>/payload   原目标整棵 rename/复制进来
 * .trash/<sessionId>/<时间戳>-<随机6位>/meta.json 原路径 / 统计 / 时间 / 来源
 * ```
 *
 * 淘汰：7 天或单池 512 MB，FIFO 先删最旧。跑在 App 启动与后台，不经工具层，因此不需要令牌：
 * 这件事的主体是 App 自己，不是 Agent。
 */
object TrashStore {

    /** 回收站目录名：各卷的 `<externalFilesDir>/.trash`。 */
    const val DIR_NAME = ".trash"

    private const val META_NAME = "meta.json"

    private const val PAYLOAD_NAME = "payload"

    private const val TAG = "trash"

    /** 保留期：7 天。超期条目由 [sweep] 自动清掉。 */
    const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

    /** 单池上限：512 MB。超限按时间 FIFO 淘汰。 */
    const val POOL_LIMIT_BYTES = 512L * 1024L * 1024L

    /** 统计/搬运的条目上限：只为 meta 里的一个数字，不值得把整棵大树走三遍。 */
    private const val MAX_SCAN_ENTRIES = 200_000

    private val random = SecureRandom()

    private val lock = Any()

    /** 一次删除的规模：字节 / 文件数 / 目录数。truncated = 触到了扫描上限，数字只是下限。 */
    data class Stats(
        val bytes: Long,
        val files: Int,
        val dirs: Int,
        val truncated: Boolean = false
    ) {
        val total: Int get() = files + dirs
    }

    /** 一条回收站记录。 */
    data class Entry(
        val dir: File,
        val id: String,
        val sessionId: String,
        val originalPath: String,
        /** 可本地恢复的绝对路径；SAF 推不出本地路径时为 null（只能看，不能恢复）。 */
        val localPath: String?,
        val bytes: Long,
        val files: Int,
        val dirs: Int,
        val time: Long,
        val volume: String,
        val tool: String
    ) {
        val payload: File get() = File(dir, PAYLOAD_NAME)
        val total: Int get() = files + dirs
    }

    /** 恢复/清空的结论：成功与否 + 给用户看的一句话。 */
    data class Outcome(val ok: Boolean, val message: String)

    // =========================================================================
    // 卷池
    // =========================================================================

    /** 全部可用的池根（`<externalFilesDir>/.trash`），按系统给的卷顺序。 */
    fun pools(context: Context): List<File> {
        val dirs = context.getExternalFilesDirs(null)?.filterNotNull().orEmpty()
        val out = ArrayList<File>(dirs.size)
        for (dir in dirs) {
            val root = File(dir, DIR_NAME)
            if (out.none { it.absolutePath == root.absolutePath }) out += root
        }
        return out
    }

    /**
     * 目标所在卷的池根；没有同卷池返回 null（调用方必须把这次删除升为「不可恢复」）。
     *
     * 用 `st_dev` 比较，而不是比较路径字符串：`/sdcard`、`/storage/self/primary`、
     * `/storage/emulated/0` 是同一个卷的不同写法，字符串前缀比较会把它们判成不同卷。
     */
    fun poolFor(context: Context, target: File): File? {
        val device = deviceOf(target) ?: return null
        val targetPath = canonicalPath(target)
        for (dir in context.getExternalFilesDirs(null).orEmpty().filterNotNull()) {
            if (deviceOf(dir) != device) continue
            val root = File(dir, DIR_NAME)
            // 目标包含池本身（删 Android/data 这类父目录）时不能进池：rename 会把自己搬进自己
            val rootPath = canonicalPath(root)
            if (targetPath == rootPath || rootPath.startsWith(targetPath.trimEnd('/') + "/")) {
                return null
            }
            return root
        }
        return null
    }

    /**
     * 解析软链接后的绝对路径。
     *
     * 为什么必须先归一：`/sdcard` 是 `/storage/self/primary` 的软链，再指到 `/storage/emulated/0`；
     * 真机实测同一卷上 `/sdcard/...` 的 `st_dev` 是 65029、`/storage/emulated/0/...` 是 83。
     * 不归一就直接 stat，会把「同一卷」误判成跨卷，于是 `/sdcard/...` 的删除永远进不了回收站。
     */
    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    /** 最近一个存在的祖先的 st_dev；目标不存在（刚被删/拼错）时也能判卷。 */
    private fun deviceOf(file: File): Long? {
        var cursor: File? = file.absoluteFile
        while (cursor != null && !cursor.exists()) cursor = cursor.parentFile
        val existing = cursor ?: return null
        val canonical = runCatching { Os.stat(canonicalPath(existing)).st_dev }.getOrNull()
        if (canonical != null) return canonical
        return runCatching { Os.stat(existing.absolutePath).st_dev }.getOrNull()
    }

    // =========================================================================
    // 移入 / 复制
    // =========================================================================

    /** 把 [target] 整棵 rename 进同卷池；没有同卷池或 rename 失败返回 null（原目标未被改动）。 */
    fun moveInto(context: Context, target: File, sessionId: String, tool: String): Entry? {
        val pool = poolFor(context, target) ?: return null
        return moveIntoPool(pool, target, sessionId, tool)
    }

    /**
     * SAF 目录没有 rename 可用，只能「复制进池再删原物」。
     *
     * [copy] 负责把原条目写到池里的 payload 路径；返回 null 表示超限/失败（调用方按不可恢复处理）。
     * [probe] 是用于选卷的本地路径（SAF 的本地根；推不出来时上层会传 null，这里也返回 null）。
     */
    fun copyInto(
        context: Context,
        probe: File,
        originalPath: String,
        localPath: String?,
        sessionId: String,
        tool: String,
        copy: (File) -> Stats?
    ): Entry? = synchronized(lock) {
        val pool = poolFor(context, probe) ?: return null
        val entryDir = reserve(pool, sessionId) ?: return null
        val payload = File(entryDir, PAYLOAD_NAME)
        val stats = copy(payload)
        if (stats == null) {
            entryDir.deleteRecursively()
            return null
        }
        val meta = metaJson(originalPath, localPath, stats, sessionId, tool, pool)
        if (!writeMeta(entryDir, meta)) {
            entryDir.deleteRecursively()
            return null
        }
        entryOf(entryDir, meta)
    }

    /** 把 [target] rename 进指定池根；写不了 meta 或跨卷 rename 失败时返回 null，原目标不动。 */
    internal fun moveIntoPool(poolRoot: File, target: File, sessionId: String, tool: String): Entry? =
        synchronized(lock) {
            val stats = scan(target)
            val entryDir = reserve(poolRoot, sessionId) ?: return null
            val payload = File(entryDir, PAYLOAD_NAME)
            // 先写元数据再搬：搬完发现写不了 meta，就等于「删掉了但没记账」，回收站会漏项
            val meta = metaJson(target.absolutePath, target.absolutePath, stats, sessionId, tool, poolRoot)
            if (!writeMeta(entryDir, meta)) {
                entryDir.deleteRecursively()
                return null
            }
            if (!target.renameTo(payload)) {
                entryDir.deleteRecursively()
                return null
            }
            entryOf(entryDir, meta)
        }

    private fun reserve(poolRoot: File, sessionId: String): File? {
        if (!poolRoot.exists() && !poolRoot.mkdirs()) return null
        val marker = File(poolRoot, ".nomedia")
        if (!marker.exists()) runCatching { marker.createNewFile() }
        val bucket = File(poolRoot, sanitize(sessionId.ifBlank { "default" }))
        if (!bucket.exists() && !bucket.mkdirs()) return null
        val id = System.currentTimeMillis().toString() + "-" + randomHex(6)
        val entry = File(bucket, id)
        return if (entry.mkdirs()) entry else null
    }

    private fun metaJson(
        originalPath: String,
        localPath: String?,
        stats: Stats,
        sessionId: String,
        tool: String,
        poolRoot: File
    ): JSONObject = JSONObject().apply {
        put("version", 1)
        put("originalPath", originalPath)
        put("localPath", localPath ?: JSONObject.NULL)
        put("bytes", stats.bytes)
        put("files", stats.files)
        put("dirs", stats.dirs)
        put("truncated", stats.truncated)
        put("time", System.currentTimeMillis())
        put("sessionId", sessionId)
        put("tool", tool)
        put("volume", poolRoot.parentFile?.absolutePath ?: poolRoot.absolutePath)
    }

    private fun writeMeta(entryDir: File, meta: JSONObject): Boolean =
        runCatching { File(entryDir, META_NAME).writeText(meta.toString(), Charsets.UTF_8) }.isSuccess

    private fun entryOf(entryDir: File, meta: JSONObject): Entry = Entry(
        dir = entryDir,
        id = entryDir.name,
        sessionId = meta.optString("sessionId"),
        originalPath = meta.optString("originalPath"),
        localPath = meta.optString("localPath").takeIf { it.isNotEmpty() && it != "null" },
        bytes = meta.optLong("bytes", 0L),
        files = meta.optInt("files", 0),
        dirs = meta.optInt("dirs", 0),
        time = meta.optLong("time", 0L),
        volume = meta.optString("volume"),
        tool = meta.optString("tool")
    )

    /** 有界统计：不跟随软链接；触到上限就标 truncated，不为了精确数字跑满整棵树。 */
    fun scan(target: File): Stats {
        var files = 0
        var dirs = 0
        var bytes = 0L
        var seen = 0
        val stack = ArrayDeque<File>()
        stack.addLast(target)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (++seen > MAX_SCAN_ENTRIES) {
                return Stats(bytes, files, dirs, truncated = true)
            }
            if (PathGate.isSymlink(current)) {
                files++
                continue
            }
            if (current.isDirectory) {
                dirs++
                current.listFiles()?.let { children ->
                    for (child in children) {
                        if (child.isDirectory && !PathGate.isSymlink(child)) {
                            stack.addLast(child)
                        } else {
                            seen++
                            files++
                            bytes += runCatching { child.length() }.getOrDefault(0L)
                        }
                    }
                }
            } else {
                files++
                bytes += runCatching { current.length() }.getOrDefault(0L)
            }
        }
        return Stats(bytes, files, dirs)
    }

    // =========================================================================
    // 列举 / 恢复 / 清空 / 淘汰
    // =========================================================================

    /** 全部池里的条目，按时间倒序；界面读一次就够，不在调用方缓存。 */
    fun entries(context: Context): List<Entry> = synchronized(lock) {
        val out = ArrayList<Entry>()
        for (pool in pools(context)) out.addAll(entriesIn(pool))
        out.sortedByDescending { it.time }
    }

    /** 条数与占用；设置页的入口行用它。 */
    fun summary(context: Context): Pair<Int, Long> {
        val list = entries(context)
        return list.size to list.sumOf { it.bytes }
    }

    /** 撤销一次删除：把 payload rename 回原路径。原位置已有同名条目时拒绝覆盖。 */
    fun restore(context: Context, entry: Entry): Outcome = synchronized(lock) {
        val payload = entry.payload
        if (!payload.exists() && !PathGate.isSymlink(payload)) {
            return Outcome(false, "回收站里已经没有这条记录了")
        }
        val local = entry.localPath
        if (local.isNullOrBlank()) {
            return Outcome(false, "这条记录来自系统授权目录，推不出本地路径，无法自动恢复")
        }
        val target = File(local)
        if (target.exists() || PathGate.isSymlink(target)) {
            return Outcome(false, "原位置已经有同名条目，请先改名或删除它")
        }
        target.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return Outcome(false, "建不了原目录：" + parent.absolutePath)
            }
        }
        if (!payload.renameTo(target)) {
            return Outcome(false, "恢复失败（目标所在卷可能已卸载或被改名）")
        }
        entry.dir.deleteRecursively()
        cleanEmptyBuckets(entry.dir.parentFile)
        Outcome(true, "已恢复到 " + target.absolutePath)
    }

    /** 永久删除一条记录（调用方是用户手势，不经 Agent）。 */
    fun deleteEntry(context: Context, entry: Entry): Boolean = synchronized(lock) {
        val ok = SafeDelete.delete(entry.payload).existed
        entry.dir.deleteRecursively()
        cleanEmptyBuckets(entry.dir.parentFile)
        ok
    }

    /** 清空全部池；返回「清掉的条数 to 释放字节数」。 */
    fun purge(context: Context): Pair<Int, Long> = synchronized(lock) {
        var count = 0
        var bytes = 0L
        for (pool in pools(context)) {
            for (entry in entriesIn(pool)) {
                if (deleteEntryFiles(entry)) {
                    count++
                    bytes += entry.bytes
                }
            }
            cleanEmptyBuckets(pool)
        }
        count to bytes
    }

    /**
     * 启动/后台淘汰：先按 7 天清过期，再按单池 512 MB FIFO 清最旧。
     *
     * 返回清掉的条数，调用方只用来写日志。
     */
    fun sweep(context: Context): Int = sweepPools(pools(context), System.currentTimeMillis())

    /** [sweep] 的实现：对给定池根按保留期与单池上限淘汰，返回清掉的条数。 */
    internal fun sweepPools(poolRoots: List<File>, now: Long): Int = synchronized(lock) {
        var removed = 0
        for (pool in poolRoots) {
            val list = entriesIn(pool).sortedBy { it.time }
            var total = 0L
            for (entry in list) {
                if (now - entry.time > RETENTION_MS) {
                    if (deleteEntryFiles(entry)) removed++
                } else {
                    total += entry.bytes
                }
            }
            var index = 0
            val rest = list.filter { now - it.time <= RETENTION_MS }
            while (total > POOL_LIMIT_BYTES && index < rest.size) {
                val entry = rest[index++]
                if (deleteEntryFiles(entry)) {
                    total -= entry.bytes
                    removed++
                }
            }
            cleanEmptyBuckets(pool)
        }
        removed
    }

    /** 列出某个池根下的全部条目（meta 读不出的目录直接跳过）。 */
    internal fun entriesIn(poolRoot: File): List<Entry> {
        val buckets = poolRoot.listFiles() ?: return emptyList()
        val out = ArrayList<Entry>()
        for (bucket in buckets) {
            if (!bucket.isDirectory) continue
            val entryDirs = bucket.listFiles() ?: continue
            for (dir in entryDirs) {
                if (!dir.isDirectory) continue
                readEntry(dir)?.let { out += it }
            }
        }
        return out
    }

    private fun readEntry(dir: File): Entry? {
        val metaFile = File(dir, META_NAME)
        if (!metaFile.isFile) return null
        val meta = runCatching { JSONObject(metaFile.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        val payload = File(dir, PAYLOAD_NAME)
        if (!payload.exists() && !PathGate.isSymlink(payload)) return null
        return entryOf(dir, meta)
    }

    private fun deleteEntryFiles(entry: Entry): Boolean {
        val result = runCatching { SafeDelete.delete(entry.payload) }
            .onFailure { FileLog.log(TAG, "回收站条目删除异常：" + it.message) }
            .getOrNull()
        val removed = entry.dir.deleteRecursively()
        cleanEmptyBuckets(entry.dir.parentFile)
        return result?.existed != false || removed
    }

    private fun cleanEmptyBuckets(dir: File?) {
        var cursor = dir ?: return
        // 只清到池根为止，绝不动 `.trash` 的父目录
        if (cursor.name == DIR_NAME) return
        while (cursor.name != DIR_NAME) {
            val parent = cursor.parentFile ?: return
            if (cursor.listFiles()?.isEmpty() != true) return
            cursor.delete()
            cursor = parent
        }
    }

    private fun sanitize(raw: String): String {
        val cleaned = raw.map { ch ->
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.') ch else '_'
        }.joinToString("")
        return cleaned.take(64).ifBlank { "default" }
    }

    private fun randomHex(length: Int): String {
        val bytes = ByteArray((length + 1) / 2)
        random.nextBytes(bytes)
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val value = b.toInt() and 0xFF
            hex.append("0123456789ABCDEF"[value shr 4]).append("0123456789ABCDEF"[value and 0x0F])
        }
        return hex.substring(0, length)
    }
}
