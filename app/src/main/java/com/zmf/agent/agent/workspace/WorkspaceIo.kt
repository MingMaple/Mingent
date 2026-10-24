package com.zmf.agent.agent.workspace

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作区里的一个条目（列目录用）。
 *
 * size 未知时是 -1：SAF 的 provider 不一定会给 COLUMN_SIZE，别把「不知道」当成 0。
 */
data class WorkspaceEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long
)

/**
 * 工作区读写抽象：把「文件工具」和「工作区的根到底是普通目录还是 SAF 授权目录」解耦。
 *
 * 两种实现：
 * - [FileWorkspaceIo]：根是 File（默认工作区、自建工作区、以及用户选的绝对路径）；
 * - [SafWorkspaceIo]：根是 SAF 授权目录（ACTION_OPEN_DOCUMENT_TREE 选来的），全部操作走
 *   [TreeRoot]（框架 DocumentsContract），File 表示不了这种目录。
 *
 * 约定：
 * 1. 所有路径都是**工作区相对路径**（用 / 分隔，空串 = 根本身），绝对路径只在
 *    [relativePathOf] 里出现一次，负责把工具解析出来的 File 翻成相对路径；
 * 2. 读写的失败统一抛异常（FileNotFound / IOException），工具层用 runCatching 兜成给模型的一句错误；
 * 3. 目录不存在不算错误：[list] 返回空列表，[exists] 返回 false。
 */
interface WorkspaceIo {

    /** 给人看的根：File 版是绝对路径，SAF 版尽量给本地可读路径。 */
    val displayRoot: String

    /** 根是不是 SAF 授权目录（File 表示不了它）。 */
    val isSaf: Boolean

    /** 这个工作区当前用不了的原因（授权失效 / 用户目录被删）；能用就是 null。 */
    val problem: String?

    fun exists(rel: String): Boolean

    fun isDirectory(rel: String): Boolean

    /** 字节数；未知返回 -1。 */
    fun size(rel: String): Long

    /** 列目录（不递归）；不是目录返回空列表。 */
    fun list(rel: String): List<WorkspaceEntry>

    /** 读整个文本（UTF-8）；不存在或读不了抛异常。 */
    fun readText(rel: String): String

    /** 流式读（按行截断的大文件用这个）；不存在或打不开抛异常。 */
    fun openReader(rel: String): BufferedReader

    /** 整篇写文本（UTF-8），父目录不存在自动创建。 */
    fun writeText(rel: String, text: String)

    /** 建目录（含中间层）；成功 true。 */
    fun mkdirs(rel: String): Boolean

    /** 删除文件或子目录（子目录递归）；成功 true。根目录不会被删。 */
    fun delete(rel: String): Boolean

    /** 绝对路径 → 工作区相对路径；不在这个工作区里返回 null。 */
    fun relativePathOf(file: File): String?

    /** 相对路径 → 回给模型看的那串路径。 */
    fun displayPath(rel: String): String
}

/** 普通目录实现：根就是一个 File，行为跟改造前的 java.io.File 完全一致。 */
class FileWorkspaceIo(val root: File) : WorkspaceIo {

    override val displayRoot: String get() = root.absolutePath
    override val isSaf: Boolean get() = false
    override val problem: String? get() = null

    private fun target(rel: String): File {
        val clean = cleanRel(rel)
        return if (clean.isEmpty()) root else File(root, clean)
    }

    override fun exists(rel: String): Boolean = target(rel).exists()

    override fun isDirectory(rel: String): Boolean = target(rel).isDirectory

    override fun size(rel: String): Long = target(rel).length()

    override fun list(rel: String): List<WorkspaceEntry> {
        val dir = target(rel)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.map { child ->
            WorkspaceEntry(
                name = child.name,
                isDirectory = child.isDirectory,
                size = if (child.isDirectory) -1L else child.length()
            )
        }.orEmpty()
    }

    override fun readText(rel: String): String = target(rel).readText(Charsets.UTF_8)

    override fun openReader(rel: String): BufferedReader =
        target(rel).bufferedReader(Charsets.UTF_8)

    override fun writeText(rel: String, text: String) {
        val file = target(rel)
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        file.writeText(text, Charsets.UTF_8)
    }

    override fun mkdirs(rel: String): Boolean {
        val dir = target(rel)
        return if (dir.exists()) dir.isDirectory else dir.mkdirs()
    }

    override fun delete(rel: String): Boolean {
        val file = target(rel)
        // 根目录永远不删：delete("") 传进来就当失败处理，别把整个工作区端掉
        if (file == root) return false
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    override fun relativePathOf(file: File): String? = relativeUnder(root, file)

    override fun displayPath(rel: String): String = target(rel).absolutePath
}

/**
 * SAF 授权目录实现：所有读写都走 [TreeRoot]（框架 DocumentsContract）。
 *
 * 三条路径口径：
 * - 相对路径按 [fallbackRoot]（dirOf 给的私有兜底目录）反推 —— 工具是拿它当根解析相对路径的；
 * - 绝对路径如果落在这棵树的本地可读路径（[localRoot]）下，也能反推成相对路径；
 * - 本地路径推不出来的（SD 卡等），只能用工具传来的相对路径，绝对路径一律判为「不在这个工作区」。
 */
class SafWorkspaceIo internal constructor(
    private val root: TreeRoot,
    /** 这棵树的本地可读绝对路径；推不出来就是 null。 */
    private val localRoot: String?,
    /** dirOf 给的私有兜底目录：相对路径是从它解析出来的，按它反推相对路径。 */
    private val fallbackRoot: File
) : WorkspaceIo {

    override val displayRoot: String
        get() {
            val local = localRoot
            if (local != null) return local
            return "系统授权目录（" + root.treeUri + "）"
        }

    override val isSaf: Boolean get() = true

    override val problem: String?
        get() = if (root.isAvailable) null else "这个工作区的系统授权目录已不可用，请到设置里重新选一次目录"

    // ---- 路径解析 ----

    /** 走到某个子目录（空串 = 根本身）；中间层不存在返回 null。 */
    private fun dirAt(rel: String): TreeRoot? {
        val clean = cleanRel(rel)
        if (clean.isEmpty()) return root
        var cursor = root
        for (segment in clean.split('/')) {
            if (segment.isEmpty()) continue
            cursor = cursor.childDir(segment) ?: return null
        }
        return cursor
    }

    /** 逐层建目录，返回最终目录；建不出来返回 null。 */
    private fun ensureDir(rel: String): TreeRoot? {
        val clean = cleanRel(rel)
        if (clean.isEmpty()) return root
        var cursor = root
        for (segment in clean.split('/')) {
            if (segment.isEmpty()) continue
            val existing = cursor.childDir(segment)
            if (existing != null) {
                cursor = existing
                continue
            }
            if (cursor.mkdir(segment) == null) return null
            cursor = cursor.childDir(segment) ?: return null
        }
        return cursor
    }

    /** 相对路径拆成「父目录 + 名字」；上级目录不存在返回 null。 */
    private fun locate(rel: String): Pair<TreeRoot, String>? {
        val clean = cleanRel(rel)
        if (clean.isEmpty()) return null
        val name = clean.substringAfterLast('/')
        if (name.isEmpty()) return null
        val parent = dirAt(clean.substringBeforeLast('/', "")) ?: return null
        return parent to name
    }

    // ---- WorkspaceIo ----

    override fun exists(rel: String): Boolean {
        val clean = cleanRel(rel)
        if (clean.isEmpty()) return true
        val located = locate(clean) ?: return false
        return located.first.find(located.second) != null
    }

    override fun isDirectory(rel: String): Boolean {
        val clean = cleanRel(rel)
        if (clean.isEmpty()) return true
        val located = locate(clean) ?: return false
        return located.first.find(located.second)?.isDirectory == true
    }

    override fun size(rel: String): Long {
        val located = locate(rel) ?: return -1L
        return located.first.find(located.second)?.size ?: -1L
    }

    override fun list(rel: String): List<WorkspaceEntry> {
        val dir = dirAt(rel) ?: return emptyList()
        return dir.listChildren().map { item ->
            WorkspaceEntry(item.name, item.isDirectory, item.size)
        }
    }

    override fun readText(rel: String): String {
        val located = locate(rel) ?: throw FileNotFoundException("文件不存在：" + displayPath(rel))
        return located.first.readText(located.second)
            ?: throw IOException("读这个文件失败：" + displayPath(rel))
    }

    override fun openReader(rel: String): BufferedReader {
        val located = locate(rel) ?: throw FileNotFoundException("文件不存在：" + displayPath(rel))
        val input = located.first.openInputStream(located.second)
            ?: throw IOException("打不开这个文件：" + displayPath(rel))
        return input.bufferedReader(Charsets.UTF_8)
    }

    override fun writeText(rel: String, text: String) {
        val clean = cleanRel(rel)
        if (clean.isEmpty()) throw IOException("不能把内容写到工作区根目录上")
        val parentRel = clean.substringBeforeLast('/', "")
        val parent = ensureDir(parentRel)
            ?: throw IOException("建不了上级目录：" + displayPath(parentRel))
        val name = clean.substringAfterLast('/')
        if (!parent.writeText(name, text)) throw IOException("写这个文件失败：" + displayPath(clean))
    }

    override fun mkdirs(rel: String): Boolean = ensureDir(rel) != null

    override fun delete(rel: String): Boolean {
        val clean = cleanRel(rel)
        if (clean.isEmpty()) return false
        val located = locate(clean) ?: return false
        return located.first.delete(located.second)
    }

    override fun relativePathOf(file: File): String? {
        // 先按兜底目录认（工具的相对路径就是这么解析来的）
        relativeUnder(fallbackRoot, file)?.let { return it }
        val local = localRoot ?: return null
        return relativeUnder(File(local), file)
    }

    override fun displayPath(rel: String): String {
        val clean = cleanRel(rel)
        val local = localRoot
        if (local != null) {
            if (clean.isEmpty()) return local
            return local.trimEnd('/') + "/" + clean
        }
        val uri = root.treeUri.toString()
        return if (clean.isEmpty()) uri else uri + "/" + clean
    }
}

/**
 * 「空壳」实现：SAF 工作区的授权失效、或用户目录被删时用它，所有写/读都失败。
 *
 * 为什么不悄悄退回私有目录：那会让模型以为写进了用户选的目录，实际落在 App 私有沙盒里，
 * 用户在自己的文件夹里什么都看不到 —— 宁可直接报错，让用户重新选目录。
 */
class UnavailableWorkspaceIo(private val reason: String) : WorkspaceIo {

    override val displayRoot: String get() = "（目录不可用）"

    override val isSaf: Boolean get() = true

    override val problem: String get() = reason

    private fun fail(): Nothing = throw IllegalStateException(reason)

    override fun exists(rel: String): Boolean = false

    override fun isDirectory(rel: String): Boolean = false

    override fun size(rel: String): Long = -1L

    override fun list(rel: String): List<WorkspaceEntry> = emptyList()

    override fun readText(rel: String): String = fail()

    override fun openReader(rel: String): BufferedReader = fail()

    override fun writeText(rel: String, text: String) = fail()

    override fun mkdirs(rel: String): Boolean = false

    override fun delete(rel: String): Boolean = false

    override fun relativePathOf(file: File): String? = null

    override fun displayPath(rel: String): String = reason
}

// ---- 工厂与缓存 ----

/** 每个工作区根只认一次盘：工具是高频调用，不能每次都去解析 workspaces.json。 */
private val IO_CACHE = ConcurrentHashMap<String, WorkspaceIo>()

/**
 * 工具层入口：按 ToolContext 里那个「工作区根」反查工作区，决定用哪种实现。
 *
 * 反查靠 [WorkSpaceStore.findByRoot]（不调 dirOf，没有 mkdirs 副作用）：
 * ChatViewModel 传进来的 workspaceRoot 就是 dirOf / 用户目录，所以能对上。
 */
fun workspaceIoForRoot(context: Context, root: File): WorkspaceIo {
    val key = runCatching { root.canonicalPath }.getOrNull() ?: root.absolutePath
    IO_CACHE[key]?.let { return it }
    val space = runCatching { WorkSpaceStore.findByRoot(context, root) }.getOrNull()
    val io = if (space == null) {
        FileWorkspaceIo(root)
    } else {
        WorkSpaceStore.workspaceIoFor(context, space) ?: UnavailableWorkspaceIo(
            "工作区「" + space.name + "」的目录已经不可用（目录被移动/删除，或系统授权失效），请重新选一次目录"
        )
    }
    IO_CACHE[key] = io
    return io
}

/** 清缓存：[root] 为 null 清全部；换目录 / 授权变化后调用。 */
fun clearWorkspaceIoCache(root: File? = null) {
    if (root == null) {
        IO_CACHE.clear()
        return
    }
    val key = runCatching { root.canonicalPath }.getOrNull() ?: root.absolutePath
    IO_CACHE.remove(key)
}

// ---- 路径工具 ----

/** 把路径收敛成「工作区相对路径」：去掉开头的 /、合并重复斜杠、去尾斜杠；不允许 .. 往上跑。 */
internal fun cleanRel(raw: String): String {
    var text = raw.trim().replace('\\', '/')
    while (text.startsWith("/")) text = text.substring(1)
    while (text.startsWith("./")) text = text.substring(2)
    while (text.contains("//")) text = text.replace("//", "/")
    if (text.endsWith("/")) text = text.dropLast(1)
    if (text == ".") text = ""
    if (text.split('/').any { it == ".." }) {
        throw IllegalArgumentException("相对路径不能含 ..（越出工作区）")
    }
    return text
}

/** [file] 在 [root] 之下的相对路径（/ 分隔）；不在下面返回 null。 */
internal fun relativeUnder(root: File, file: File): String? {
    val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: root.absolutePath
    val filePath = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
    val base = rootPath.trimEnd(File.separatorChar)
    if (filePath == base) return ""
    if (!filePath.startsWith(base + File.separatorChar)) return null
    return filePath.substring(base.length + 1).replace(File.separatorChar, '/')
}
