package com.zmf.agent.core.capability

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings

/**
 * Android 侧系统权限的「查询 + 跳转」小工具。
 *
 * 只做两件事，不做授权决策、也不碰 Shizuku（Shizuku 自己的状态在 [ShizukuBridge]）：
 * 1. **[status]**：一次性把权限页要显示的几项状态查全；查询本身没有副作用，页面回到前台就重查一次；
 * 2. **open*Settings**：把用户送到对应的系统页。系统页是「用户自己决定」的地方，
 *    App 只在用户回来之后重查状态，不假设用户点了什么。
 *
 * 所有跳转都 runCatching 包住：国产 ROM 上这些 action 经常没 Activity 接，
 * 抛 ActivityNotFoundException 直接崩过一次用户就不会再点第二次，所以失败一律返回 false 让调用方兜底。
 */
object AndroidPermissions {

    /** 权限页要显示的全部状态；true = 已授权。 */
    data class Status(
        /** 共享存储的运行时权限（targetSdk 28 上 READ/WRITE_EXTERNAL_STORAGE 仍按老规则弹框）。 */
        val storage: Boolean,
        /** 整盘访问（Android 11+ 的 MANAGE_EXTERNAL_STORAGE）；低版本一律 false。 */
        val storageLegacy: Boolean,
        /** 悬浮窗。 */
        val overlay: Boolean,
        /** 通知；Android 13 以下没有这项运行时权限，直接算已授权。 */
        val notification: Boolean,
        /** 是否已加入电池优化白名单。 */
        val batteryIgnore: Boolean,
        /** 同 storageLegacy，单独列一个字段是给「所有文件访问」那一行直接读。 */
        val allFiles: Boolean
    )

    /** 查一次当前状态。不碰 Shizuku，也不申请任何东西。 */
    fun status(context: Context): Status {
        val allFiles = isAllFilesGranted()
        return Status(
            storage = isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE) ||
                isGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            storageLegacy = allFiles,
            overlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false),
            // Android 13 起通知变成运行时权限；12 及以下是装机即授予，直接当已授权
            notification = if (Build.VERSION.SDK_INT >= 33) {
                isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true
            },
            batteryIgnore = isIgnoringBatteryOptimizations(context),
            allFiles = allFiles
        )
    }

    // ---- 跳系统页：统一 runCatching，回不来就 false ----

    /** 悬浮窗权限页（「显示在其他应用上层」）。 */
    fun openOverlaySettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context)))

    /**
     * 电池优化白名单页。
     *
     * 先试「直接弹忽略电池优化的确认框」；部分 ROM 不认这个 action（或没声明
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS），就退回电池优化列表页让用户自己选。
     */
    fun openBatterySettings(context: Context): Boolean {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            packageUri(context)
        )
        if (start(context, request)) return true
        return start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** 本 App 的「所有文件访问」页；Android 11 以下没有这项权限，直接返回 false。 */
    fun openAllFilesSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return start(context, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(context)))
    }

    /** 本 App 的系统详情页（所有权限的兜底入口）。 */
    fun openAppSettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))

    // ---- 内部 ----

    private fun isGranted(context: Context, permission: String): Boolean =
        runCatching { context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    /** 整盘访问是否已授权（API 30+ 才有这个判断，低版本永远是 false）。 */
    private fun isAllFilesGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        power?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    private fun packageUri(context: Context): Uri = Uri.parse("package:" + context.packageName)

    /**
     * 起系统页。必须 NEW_TASK：这些跳转常从非 Activity 的 Context（悬浮窗里）发出，
     * 少了这个 flag 会直接抛异常，用户看到的就是「点了没反应」。
     */
    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
