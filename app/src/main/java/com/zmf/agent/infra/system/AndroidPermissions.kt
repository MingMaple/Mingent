package com.zmf.agent.infra.system

import android.Manifest
import android.app.AlarmManager
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
 * 仅执行两项职责，不做授权决策，也不涉及 Shizuku（其状态由 [ShizukuBridge] 管理）：
 * 1. **[status]**：一次性查询权限页所需的各项状态；查询本身无副作用，页面回到前台时重新查询；
 * 2. **open*Settings**：将用户引导至对应的系统页面。系统页由用户自行决定，
 *    App 仅在用户返回后重新查询状态，不预设用户操作结果。
 *
 * 所有跳转均以 runCatching 包裹：国产 ROM 上这些 action 常无 Activity 接收，
 * 抛出 ActivityNotFoundException 后用户往往不再重试，因此失败一律返回 false，由调用方处理。
 */
object AndroidPermissions {

    /** 权限页要显示的全部状态；true = 已授权。 */
    data class Status(
        /** 共享存储的运行时权限（targetSdk 28 上 READ/WRITE_EXTERNAL_STORAGE 仍按旧规则弹出授权框）。 */
        val storage: Boolean,
        /** 整盘访问（Android 11+ 的 MANAGE_EXTERNAL_STORAGE）；低版本一律 false。 */
        val storageLegacy: Boolean,
        /** 悬浮窗。 */
        val overlay: Boolean,
        /** 通知；Android 13 以下没有这项运行时权限，直接算已授权。 */
        val notification: Boolean,
        /** 是否已加入电池优化白名单。 */
        val batteryIgnore: Boolean,
        /** 精确闹钟（Android 12+ 的「闹钟与提醒」特殊权限）；低版本恒 true。 */
        val exactAlarm: Boolean,
        /** 同 storageLegacy，单独列一个字段是给「所有文件访问」那一行直接读。 */
        val allFiles: Boolean
    )

    /** 查询当前状态。不涉及 Shizuku，也不发起任何申请。 */
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
            exactAlarm = exactAlarmAllowed(context),
            allFiles = allFiles
        )
    }

    /**
     * 是否允许排精确闹钟。
     *
     * Android 12 起由「闹钟与提醒」特殊权限控制：没有授权时 AlarmManager 排精确闹钟会抛 SecurityException。
     * 低版本没有这项限制，一律算可用。
     */
    fun exactAlarmAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            manager?.canScheduleExactAlarms() == true
        }.getOrDefault(false)
    }

    // ---- 跳转系统页：统一使用 runCatching，失败时返回 false ----

    /** 悬浮窗权限页（「显示在其他应用上层」）。 */
    fun openOverlaySettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context)))

    /**
     * 电池优化白名单页。
     *
     * 优先尝试「直接弹出忽略电池优化的确认框」；部分 ROM 不支持该 action（或未声明
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS），则退回电池优化列表页供用户选择。
     */
    fun openBatterySettings(context: Context): Boolean {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            packageUri(context)
        )
        if (start(context, request)) return true
        return start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** 精确闹钟授权页；Android 12 以下没有这项设置，直接返回 false。 */
    fun openExactAlarmSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return start(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)))
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
     * 启动系统页。必须使用 NEW_TASK：这些跳转常从非 Activity 的 Context（悬浮窗）发出，
     * 缺少该 flag 会直接抛出异常，表现为点击无响应。
     */
    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
