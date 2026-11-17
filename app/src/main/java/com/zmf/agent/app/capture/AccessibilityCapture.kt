package com.zmf.agent.app.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import com.zmf.agent.capability.port.CaptureResult

/**
 * 应用外截屏入口。
 *
 * 服务实例由 [CaptureAccessibilityService] 在连接与销毁时登记；未启用无障碍时实例为空，
 * 截屏请求返回 `E_ACCESSIBILITY_DENIED`。系统版本低于 Android 11 时返回 `E_UNSUPPORTED`。
 */
object AccessibilityCapture {

    @Volatile
    private var service: CaptureAccessibilityService? = null

    /** 由服务在 [CaptureAccessibilityService.onServiceConnected] 中调用。 */
    internal fun attach(value: CaptureAccessibilityService) {
        service = value
    }

    /** 由服务在 [CaptureAccessibilityService.onDestroy] 中调用；只清除自身登记。 */
    internal fun detach(value: CaptureAccessibilityService) {
        if (service === value) service = null
    }

    /**
     * 本应用的无障碍服务是否已启用。
     *
     * 只判断系统已启用的服务列表，不判断服务是否已连接；连接状态由 [capture] 的返回值体现。
     */
    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val fullName = CaptureAccessibilityService::class.java.name
        val simpleName = "." + CaptureAccessibilityService::class.java.simpleName
        return enabled.any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
            val name = serviceInfo.name ?: return@any false
            serviceInfo.packageName == context.packageName &&
                (name == fullName || name.endsWith(simpleName))
        }
    }

    /**
     * 截取前台其他应用。
     *
     * [expectedPackage] 非空时必须与当前前台应用一致，否则返回 `E_TARGET_NOT_FOREGROUND`。
     */
    suspend fun capture(expectedPackage: String?): CaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return CaptureResult.Failure(
                "E_UNSUPPORTED",
                "系统版本低于 Android 11（API 30），无法截取其他应用：" +
                    "必须升级系统后重试，或改用 source=self 截取本应用。"
            )
        }
        val current = service ?: return CaptureResult.Failure(
            "E_ACCESSIBILITY_DENIED",
            "无障碍服务未启用：必须前往「设置 → 无障碍 → 本应用」开启后再截屏；" +
                "应用内截屏（source=self）无需该服务。"
        )
        if (expectedPackage != null) {
            val foreground = current.foregroundPackage()
            if (foreground != expectedPackage) {
                return CaptureResult.Failure(
                    "E_TARGET_NOT_FOREGROUND",
                    "目标应用 " + expectedPackage + " 当前不在前台（当前前台为 " +
                        (foreground ?: "无法读取") + "）：必须先将目标应用切换到前台后重试。"
                )
            }
        }
        return current.takeScreenshotCompat()
    }
}
