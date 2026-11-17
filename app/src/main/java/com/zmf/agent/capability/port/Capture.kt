package com.zmf.agent.capability.port

import android.graphics.Bitmap

/** 截屏目标。 */
enum class CaptureTarget {
    /** 本应用当前窗口，不申请任何权限。 */
    SELF,

    /** 其他应用的前台窗口，要求已启用无障碍服务。 */
    FOREGROUND_APP
}

/**
 * 截屏结果。
 *
 * 失败只返回错误码与说明，不抛异常：调用方需要把错误码原样写进工具回执。
 */
sealed interface CaptureResult {
    /** 成功；[packageName] 为被截窗口的包名，应用内截屏为本应用包名。 */
    data class Success(
        val bitmap: Bitmap,
        val packageName: String,
        val capturedAt: Long
    ) : CaptureResult

    /** 失败；[code] 以 `E_` 开头。 */
    data class Failure(val code: String, val message: String) : CaptureResult
}

/**
 * 截屏端口。
 *
 * 应用内实现使用 PixelCopy；应用外实现使用无障碍服务，系统版本低于 30 时返回不支持。
 */
interface ScreenCapture {
    suspend fun capture(target: CaptureTarget, expectedPackage: String? = null): CaptureResult
}
