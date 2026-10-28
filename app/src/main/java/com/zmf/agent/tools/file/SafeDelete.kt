package com.zmf.agent.tools.file

import com.zmf.agent.tools.PathGate
import java.io.File

/**
 * 安全的递归删除。
 *
 * 与 `File.deleteRecursively()` 的关键区别：
 * 1. **不跟随符号链接**：软链接本身当文件删，绝不进链接内部（否则「工作区里的链接 → /」会端掉根）；
 * 2. **逐项判定后删除**：每一项都在自己的父目录里删，天然不会跳到父目录之外；
 * 3. **可统计**：返回删掉的文件数 / 目录数 / 字节数，回执能告诉模型到底动了多少东西。
 *
 * 注意：这不是事务。删到一半失败会留下「已删一部分」的状态，[Stats.failed] 会如实报出来。
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
        // 先判软链接：目录软链在 isDirectory 上会被解析成 true，跟着进去就删到别处了
        val symlink = PathGate.isSymlink(file)
        if (!symlink && file.isDirectory) {
            val children = file.listFiles()
            var all = true
            if (children != null) {
                for (child in children) {
                    if (!deleteRecursive(child, stats)) all = false
                }
            }
            // 目录本身删不掉（非空 / 权限）也要计数，回执才能解释「为什么还剩东西」
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

    /** 软链接目标可能已断，`exists()` 会返回 false，但链接本身仍在，必须单独认一次。 */
    private fun existsNoFollow(file: File): Boolean =
        file.exists() || PathGate.isSymlink(file)
}
