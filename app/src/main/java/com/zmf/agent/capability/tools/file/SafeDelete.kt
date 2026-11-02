package com.zmf.agent.capability.tools.file

import com.zmf.agent.core.fs.PathGate
import java.io.File

/**
 * 安全的递归删除。
 *
 * 与 `File.deleteRecursively()` 的关键区别：
 * 1. **不跟随符号链接**：软链接按文件本身删除，不进入链接目标（否则「工作区内的链接 → /」可能删除根目录）；
 * 2. **逐项判定后删除**：每项都在其父目录中删除，不会越出父目录范围；
 * 3. **可统计**：返回删除的文件数、目录数与字节数，便于回执中说明实际改动范围。
 *
 * 注意：该操作不是事务。中途失败会留下「已删除一部分」的状态，[Stats.failed] 会如实报告。
 */
internal object SafeDelete {

    /** 删除计数：文件数 / 目录数 / 释放字节数 / 删不掉的项数。 */
    data class Stats(
        var files: Int = 0,
        var dirs: Int = 0,
        var bytes: Long = 0L,
        var failed: Int = 0
    )

    /** 一次删除的结果：[complete] 为 false 表示只删掉了一部分。 */
    data class Result(
        val existed: Boolean,
        val complete: Boolean,
        val stats: Stats
    )

    /** 递归删除 [target]，不跟随软链接；目标不存在时返回 existed=false 的空结果。 */
    fun delete(target: File): Result {
        if (!existsNoFollow(target)) return Result(false, false, Stats())
        val stats = Stats()
        val complete = deleteRecursive(target, stats)
        return Result(true, complete, stats)
    }

    private fun deleteRecursive(file: File, stats: Stats): Boolean {
        // 先判软链接：目录软链在 isDirectory 上会被解析成 true，跟随进入会删除链接目标
        val symlink = PathGate.isSymlink(file)
        if (!symlink && file.isDirectory) {
            val children = file.listFiles()
            var all = true
            if (children != null) {
                for (child in children) {
                    if (!deleteRecursive(child, stats)) all = false
                }
            }
            // 目录本身删除失败（非空或权限不足）也要计数，以便回执说明残留原因
            if (file.delete()) {
                stats.dirs++
            } else {
                stats.failed++
                all = false
            }
            return all
        }

        val size = runCatching { file.length() }.getOrDefault(0L)
        return if (file.delete()) {
            stats.files++
            stats.bytes += size
            true
        } else {
            stats.failed++
            false
        }
    }

    /** 软链接目标可能已断开，`exists()` 会返回 false，但链接本身仍存在，必须单独判定一次。 */
    private fun existsNoFollow(file: File): Boolean =
        file.exists() || PathGate.isSymlink(file)
}
