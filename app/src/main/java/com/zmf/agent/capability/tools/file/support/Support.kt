package com.zmf.agent.capability.tools.file.support

import com.zmf.agent.core.store.WorkspaceEntry
import com.zmf.agent.core.store.WorkspaceIo
import java.io.BufferedReader
import java.io.File

/**
 * 这一组工具均通过 [WorkspaceIo] 读写：工作区根为普通目录时沿用 java.io.File 的原有行为，
 * 根为 SAF 授权目录（用户通过系统选择器选择的文件夹）时使用 DocumentsContract，
 * 因此「用户选择的目录无法写入」这一问题在工具层已不存在。
 *
 * 权限规则未变：工作区内可读写；工作区外的绝对路径仍需「完全」权限（该路径仅 File 支持）。
 */

/**
 * 结果中的路径：普通目录给规范化绝对路径，SAF 目录只能给 provider 的显示路径。
 */
internal fun displayPathOf(target: FsTarget): String {
    if (target.isSaf) return target.display
    return runCatching { target.local.canonicalPath }.getOrElse { target.local.absolutePath }
}

/**
 * 本次工具调用实际操作的目标。
 *
 * - 工作区里（含 SAF 授权目录）：一律走 [WorkspaceIo]，SAF 目录也能读能写；
 * - 工作区外的绝对路径：只有「完全」权限会走到这儿，仍用 java.io.File 的老路。
 *
 * 两种情形的对外表现（显示路径、大小、列目录）尽量对齐，工具的正文不用各写一套。
 */
internal class FsTarget(
    /** 非 null = 走 WorkspaceIo */
    private val io: WorkspaceIo?,
    /** 走 WorkspaceIo 时的相对路径 */
    private val rel: String?,
    /** 不走 WorkspaceIo 时（或 SAF 推不出相对路径时）的 File；只用于 inWorkspace=false 的分支 */
    val local: File
) {

    /** 这个目标在不在工作区里（决定权限口径）。 */
    val inWorkspace: Boolean get() = io != null && rel != null

    /** 是否走系统授权目录：这种目录没有 rename 接口，写入只能截断重写。 */
    val isSaf: Boolean get() = io?.isSaf == true

    val display: String
        get() {
            val adapter = io
            val path = rel
            return if (adapter != null && path != null) adapter.displayPath(path) else local.absolutePath
        }

    /** 目标是否存在：SAF 分支问 provider，File 分支问文件系统，两边口径一致。 */
    fun exists(): Boolean {
        val adapter = io
        val path = rel
        return if (adapter != null && path != null) adapter.exists(path) else local.exists()
    }

    /** 目标是不是目录；SAF provider 给不出类型时按「不是目录」算。 */
    fun isDirectory(): Boolean {
        val adapter = io
        val path = rel
        return if (adapter != null && path != null) adapter.isDirectory(path) else local.isDirectory
    }

    /** 普通文件判断：File 分支问文件系统；SAF 分支只能按「不是目录」算（provider 不给类型）。 */
    fun isRegularFile(): Boolean {
        val adapter = io
        val path = rel
        if (adapter != null && path != null) return adapter.exists(path) && !adapter.isDirectory(path)
        return local.isFile
    }

    /** 字节数；目录上的取值没有意义，调用方只在文件分支用它。 */
    fun size(): Long {
        val adapter = io
        val path = rel
        return if (adapter != null && path != null) adapter.size(path) else local.length()
    }

    /** 列一层子项（不递归）；目录读不了时返回空列表，不抛异常。 */
    fun listEntries(): List<WorkspaceEntry> {
        val adapter = io
        val path = rel
        if (adapter != null && path != null) return adapter.list(path)
        val children = local.listFiles() ?: return emptyList()
        return children.map { child ->
            WorkspaceEntry(
                name = child.name,
                isDirectory = child.isDirectory,
                size = if (child.isDirectory) -1L else child.length()
            )
        }
    }

    /** 取一个 UTF-8 文本读流，关闭由调用方负责。 */
    fun openReader(): BufferedReader {
        val adapter = io
        val path = rel
        return if (adapter != null && path != null) {
            adapter.openReader(path)
        } else {
            local.bufferedReader(Charsets.UTF_8)
        }
    }

    /** 整份读成文本（UTF-8）；保留原换行符与 BOM，供精确编辑使用。 */
    fun readText(): String = openReader().use { it.readText() }

    /** 整段覆盖写：File 分支同时创建父目录，SAF 分支直接交给 provider 的写接口。 */
    fun writeText(content: String) {
        val adapter = io
        val path = rel
        if (adapter != null && path != null) {
            adapter.writeText(path, content)
            return
        }
        local.parentFile?.let { if (!it.exists()) it.mkdirs() }
        local.writeText(content, Charsets.UTF_8)
    }

    companion object {
        /** 直接按「工作区里的某个相对路径」构造（列工作区根时用）。 */
        fun ofWorkspace(io: WorkspaceIo, rel: String): FsTarget =
            FsTarget(io, rel, File(io.displayRoot))
    }
}

/**
 * 把参数路径解析成 [FsTarget]。返回 null 只有一种情形：
 * SAF 工作区里出现「推不出相对路径」的绝对路径，那种路径在 File 这套上不成立，只能拒绝。
 *
 * 普通工作区外的绝对路径不算解析失败：原样交给 File，权限由调用方按 PathGate 判。
 */
internal fun targetFor(io: WorkspaceIo, file: File): FsTarget? {
    val rel = io.relativePathOf(file)
    if (rel != null) return FsTarget(io, rel, file)
    if (io.isSaf) return null
    return FsTarget(null, null, file)
}
