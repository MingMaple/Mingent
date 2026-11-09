package com.zmf.agent.infra.terminal

import android.content.Context
import com.zmf.agent.core.log.FileLog
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 终端（PRoot Ubuntu）环境的体检与修复。
 *
 * 需要重装机制的原因：Ubuntu 根文件系统包含大量软链接（/bin → usr/bin、
 * /lib64/ld-linux-… 等）与可执行位。备份包为 zip，而 zip **无法保存软链接与权限位**：
 * 导入后这些链接变为普通文件、脚本丢失可执行位，PRoot 可以启动，但 bash 一进入即退出：
 * 表现为终端黑屏、进程短暂出现后消失，或记忆内核报告 Hidden exec shell exited before ready。
 * 镜像位于 APK 内（assets/ubuntu-noble-…tar.xz），因此修复方式为删除已解压的根文件系统，
 * 下次启动终端时由启动脚本依据内置镜像重新解压完整环境。
 *
 * usr/bin 下的 proot / busybox / bash 无需处理：它们是指向 native so 的软链接，每次启动都会重新创建。
 */
object TerminalEnv {

    private const val TAG = "terminal-env"

    /** 解压出来的 Ubuntu 根文件系统：filesDir/usr/var/lib/proot-distro/installed-rootfs/ubuntu。 */
    fun rootfsDir(context: Context): File =
        File(
            File(context.filesDir, "usr"),
            "var/lib/proot-distro/installed-rootfs/ubuntu"
        )

    /**
     * 是否看起来已安装：/bin/bash 与 /etc/os-release 均存在。
     *
     * 仅做存在性粗判：此处判断的是终端能否启动，
     * 而非校验每个文件的哈希；逐文件校验较慢且容易误报。
     */
    fun isInstalled(context: Context): Boolean {
        val root = rootfsDir(context)
        if (!root.isDirectory) return false
        // /bin 在 Ubuntu 中是指向 usr/bin 的软链接；软链接损坏后此处会退化为普通目录，
        // 因此两种路径均需检查
        val bash = File(root, "bin/bash").takeIf { it.exists() } ?: File(root, "usr/bin/bash")
        // 关键判据为**可执行位**：zip 导入会丢失可执行位，文件仍存在但 bash 无法运行；
        // 仅检查 exists 会将损坏环境误报为「已安装」，用户操作后仍然黑屏
        val runnable = bash.exists() && bash.canExecute()
        return runnable && File(root, "etc/os-release").exists()
    }

    /**
     * 重装：删除已解压的根文件系统，下次进入终端时由启动脚本依据内置镜像重新解压。
     *
     * 代价说明（界面同样需提示）：重装仅清理 Ubuntu 环境中的内容（通过 apt 安装的包、/root 下的文件），
     * App 的会话、计划与记忆内核库位于其他目录，均不受影响。
     *
     * @return 释放的字节数（近似值）；删除失败返回 0。
     */
    fun reinstall(context: Context): Long {
        val root = rootfsDir(context)
        val freed = sizeOf(root)
        val ok = deleteTree(root)
        // 安装中断残留的进度标记：若不清理，启动脚本会误判为「已安装」或「正在安装」
        File(context.filesDir, "tmp").let { tmp ->
            listOf("progress", "progress_des").forEach { name -> File(tmp, name).delete() }
        }
        FileLog.log(TAG, (if (ok) "已清除" else "清除失败") + " Ubuntu 根文件系统，约 " + freed + " 字节")
        return if (ok) freed else 0L
    }

    private fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0L
        return try {
            // 不跟随软链接统计，避免链接指回祖辈目录时死循环或把外部文件算进来
            var total = 0L
            Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
            })
            total
        } catch (e: Exception) {
            0L
        }
    }

    private fun deleteTree(dir: File): Boolean {
        if (!Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        return try {
            // walkFileTree 默认不跟随软链接：只删除链接本身，不会误删链接指向的目录外内容
            Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(d)
                    return FileVisitResult.CONTINUE
                }
            })
            !Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (e: Exception) {
            FileLog.log(TAG, "删除根文件系统失败：" + e)
            false
        }
    }
}
