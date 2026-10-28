package com.zmf.agent.core.terminal

import android.content.Context
import com.zmf.agent.core.log.FileLog
import java.io.File

/**
 * 终端（PRoot Ubuntu）环境的体检与修复。
 *
 * 为什么需要「重装」这一手：Ubuntu 根文件系统里有成百上千个软链接（/bin → usr/bin、
 * /lib64/ld-linux-… 之类）和可执行位。备份包是 zip，而 zip **存不下软链接和权限位**：
 * 导入之后那些链接变成普通文件、脚本全丢了 x 位，PRoot 起得来、bash 却一进来就退：
 * 表现就是「终端一片黑、加进程闪一下就没」「记忆内核报 Hidden exec shell exited before ready」。
 * 镜像本身在 APK 里（assets/ubuntu-noble-…tar.xz），所以修法很直接：把解压出来的那份删掉，
 * 下次开终端时启动脚本会按内置镜像重新解压一份干净的。
 *
 * usr/bin 下的 proot / busybox / bash 不用管：它们是 native so 的软链，每次启动都会重新建。
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
     * 看起来装好了没：/bin/bash 与 /etc/os-release 都在。
     *
     * 只做「在不在」这种粗判：这里要回答的是「终端能不能起来」，
     * 不是校验每一个文件的哈希：那样的体检又慢又容易误报。
     */
    fun isInstalled(context: Context): Boolean {
        val root = rootfsDir(context)
        if (!root.isDirectory) return false
        // /bin 在 Ubuntu 里是 → usr/bin 的软链；软链坏了之后这里会退化成普通目录，
        // 所以两种落点都认一遍
        val bash = File(root, "bin/bash").takeIf { it.exists() } ?: File(root, "usr/bin/bash")
        // 关键判据是**可执行位**：zip 导入把 x 位全丢了，文件还在、bash 却跑不起来，
        // 只看 exist 会把坏环境报成「已安装」，用户照着点下去仍然是黑屏
        val runnable = bash.exists() && bash.canExecute()
        return runnable && File(root, "etc/os-release").exists()
    }

    /**
     * 重装：删掉解压出来的根文件系统，下次进终端时由启动脚本按内置镜像重新解压。
     *
     * 代价说明（界面上也要说）：重装只清 Ubuntu 里的东西（apt 装的包、/root 下的文件），
     * App 的会话、计划、记忆内核库都在别处，一个都不动。
     *
     * @return 释放的字节省略值（字节）；删除失败返回 0。
     */
    fun reinstall(context: Context): Long {
        val root = rootfsDir(context)
        val freed = sizeOf(root)
        val ok = deleteTree(root)
        // 装到一半留下的进度标记：不清掉的话启动脚本会以为「已经装好」或「正在装」
        File(context.filesDir, "tmp").let { tmp ->
            listOf("progress", "progress_des").forEach { name -> File(tmp, name).delete() }
        }
        FileLog.log(TAG, (if (ok) "已清除" else "清除失败") + " Ubuntu 根文件系统，约 " + freed + " 字节")
        return if (ok) freed else 0L
    }

    private fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0L
        return try {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) {
            0L
        }
    }

    private fun deleteTree(dir: File): Boolean {
        if (!dir.exists()) return true
        return try {
            // walkBottomUp：先删子项再删目录，软链本身当文件删（不能跟着走进去）
            dir.walkBottomUp().forEach { file ->
                if (file.isDirectory && !isSymlink(file)) file.delete() else file.delete()
            }
            !dir.exists()
        } catch (e: Exception) {
            FileLog.log(TAG, "删除根文件系统失败：" + e)
            false
        }
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
}
