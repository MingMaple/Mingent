package com.zmf.agent.core

import android.content.Context
import com.zmf.agent.core.log.FileLog
import java.io.File

/**
 * 应用私有目录约定。
 *
 * 应用数据统一存放于 filesDir/appdata/ 下，与终端模块使用的 usr/、bin/、tmp/、common.sh 隔离。
 *
 * 迁移仅执行一次且只复制不删除：filesDir 根目录下的既有文件保留在原处，便于历史版本读取或手动备份；
 * 复制失败时返回该文件，不抛异常中断启动。
 */
object AppPaths {

    /** 迁移日志的 tag。 */
    private const val TAG = "appdata"

    /** 应用自己的数据子目录名。 */
    private const val DATA_DIR = "appdata"

    /** 迁移时先写入带此后缀的临时文件再重命名，避免复制中断后残留不完整文件被误判为已迁移。 */
    private const val MIGRATE_SUFFIX = ".migrating"

    /** 应用自己的数据目录：filesDir/appdata（不存在时创建）。 */
    fun dataDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, DATA_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * filesDir/appdata/<name>。
     *
     * 新位置已存在时直接返回；否则若 filesDir/<name> 存在，则一次性复制过去并保留源文件；
     * 复制失败时返回源文件，调用方读写行为不变。
     *
     * 多个 store 会在首次读盘时并发调用，整段迁移加对象锁，避免同一次启动内重复复制。
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
                // 其他进程已先写入新文件时，使用该文件
                temp.delete()
                target
            } else {
                temp.delete()
                legacy
            }
        } catch (e: Exception) {
            // 复制失败：删除临时文件并继续使用原路径（源文件未被删除）
            FileLog.log(TAG, "迁移 " + name + " 失败，继续用旧文件：" + e)
            temp.delete()
            legacy
        }
    }
}
