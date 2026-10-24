package com.zmf.agent.core

import android.content.Context
import com.zmf.agent.core.log.FileLog
import java.io.File

/**
 * 应用私有目录约定。
 *
 * 以前 sessions.json / workspaces.json / stats.json 直接躺在 filesDir 根目录，
 * 跟终端模块要用的 usr/ bin/ tmp/ common.sh 混在一起；现在统一挪到 filesDir/appdata/ 下。
 *
 * 迁移是一次性的、只复制不删除：老文件留在原处（用户可能还在用旧版本 App / 想手动备份），
 * 复制失败就把旧文件返回给调用方，绝不抛异常打断启动。
 */
object AppPaths {

    /** 迁移日志的 tag。 */
    private const val TAG = "appdata"

    /** 应用自己的数据子目录名。 */
    private const val DATA_DIR = "appdata"

    /** 迁移时先写这个后缀的文件再改名，避免复制到一半被杀、留下半截文件被当成「已迁移」。 */
    private const val MIGRATE_SUFFIX = ".migrating"

    /** 应用自己的数据目录：filesDir/appdata（不存在就建）。 */
    fun dataDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, DATA_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * filesDir/appdata/<name>。
     *
     * - appdata/<name> 已存在 → 直接用新位置；
     * - 不存在而旧的 filesDir/<name> 存在 → 先复制过去（一次性迁移，旧文件保留）；
     * - 复制失败 → 返回旧文件（调用方照旧读写，行为不退化）。
     *
     * 多个 store 会在首次读盘时并发调用，迁移整段加对象锁，避免同一次启动里复制两遍。
     */
    @Synchronized
    fun dataFile(context: Context, name: String): File {
        val app = context.applicationContext
        val dir = dataDir(app)
        val target = File(dir, name)
        if (target.exists()) return target

        val legacy = File(app.filesDir, name)
        if (!legacy.exists() || !legacy.isFile) return target

        FileLog.log(TAG, "迁移 " + name + " 到 appdata/")
        val temp = File(dir, name + MIGRATE_SUFFIX)
        return try {
            temp.delete()
            legacy.copyTo(temp, overwrite = true)
            if (temp.renameTo(target)) {
                target
            } else if (target.exists()) {
                // 极端情况下别的进程抢先写好了新文件：用它
                temp.delete()
                target
            } else {
                temp.delete()
                legacy
            }
        } catch (e: Exception) {
            // 复制失败：清掉半截临时文件，继续用旧路径（旧文件本来就没删）
            FileLog.log(TAG, "迁移 " + name + " 失败，继续用旧文件：" + e)
            temp.delete()
            legacy
        }
    }
}
