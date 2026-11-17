package com.zmf.agent.app.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.zmf.agent.capability.port.CaptureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 截屏用无障碍服务。
 *
 * 本服务只提供截屏与前台包名读取，不处理任何无障碍事件：无障碍授权在本应用中仅用于截取其他应用。
 * 系统版本低于 Android 11 时没有 takeScreenshot，截屏请求由 [AccessibilityCapture] 统一返回不支持。
 */
class CaptureAccessibilityService : AccessibilityService() {

    /**
     * 截屏回调里的位图复制与前台包名读取都放这里：两者都可能耗几十毫秒（整屏拷贝、读窗口树），
     * 不能占主线程。SupervisorJob 保证一条任务失败不影响后续截屏。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityCapture.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        AccessibilityCapture.detach(this)
        super.onDestroy()
    }

    /**
     * 当前处于活动状态的应用窗口包名；无法读取时返回 null。
     *
     * 必须配置 `flagRetrieveInteractiveWindows` 才能读取窗口根节点；根节点已失效时按无前台窗口处理。
     */
    fun foregroundPackage(): String? {
        val active = runCatching {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
        }.getOrNull() ?: return null
        return runCatching { active.root?.packageName?.toString() }.getOrNull()
    }

    /**
     * 截取默认显示屏。
     *
     * 成功时返回 ARGB_8888 位图，[HardwareBuffer] 在复制完成后立即关闭。
     * 超过 5 秒未回调返回 `E_CAPTURE_TIMEOUT`；系统回调错误码非 0 返回 `E_CAPTURE_FAILED`。
     */
    suspend fun takeScreenshotCompat(): CaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return CaptureResult.Failure(
                "E_UNSUPPORTED",
                "系统版本低于 Android 11（API 30），无法截取其他应用：" +
                    "必须升级系统后重试，或改用 source=self 截取本应用。"
            )
        }
        return withTimeoutOrNull(TIMEOUT_MS) { requestScreenshot() }
            ?: CaptureResult.Failure(
                "E_CAPTURE_TIMEOUT",
                "截屏请求超过 5 秒未返回：必须稍后重试；若持续超时，应在系统设置中重新开启本应用的无障碍服务。"
            )
    }

    /** 发起一次系统截屏请求；位图复制完成后再返回结果。 */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestScreenshot(): CaptureResult =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        // 回调在主线程：复制整屏与读窗口树都挪到后台，完成后才 resume continuation
                        scope.launch {
                            val bitmap = copyToBitmap(screenshot)
                            if (!continuation.isActive) {
                                bitmap?.recycle()
                                return@launch
                            }
                            if (bitmap == null) {
                                continuation.resume(
                                    CaptureResult.Failure(
                                        "E_CAPTURE_FAILED",
                                        "系统未能返回可用的屏幕图像：必须稍后重试；" +
                                            "若持续失败，应重新开启本应用的无障碍服务。"
                                    )
                                )
                            } else {
                                val pkg = foregroundPackage() ?: packageName
                                // 读前台包名期间若已超时/取消，位图不能漏回收
                                if (!continuation.isActive) {
                                    bitmap.recycle()
                                    return@launch
                                }
                                continuation.resume(
                                    CaptureResult.Success(
                                        bitmap = bitmap,
                                        packageName = pkg,
                                        capturedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (!continuation.isActive) return
                        continuation.resume(
                            CaptureResult.Failure(
                                "E_CAPTURE_FAILED",
                                "系统截屏回调返回错误码 " + errorCode +
                                    "：必须稍后重试；若持续失败，应重新开启本应用的无障碍服务。"
                            )
                        )
                    }
                }
                val requested = runCatching {
                    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
                }
                if (requested.isFailure && continuation.isActive) {
                    continuation.resume(
                        CaptureResult.Failure(
                            "E_CAPTURE_FAILED",
                            "截屏请求无法发起：必须确认无障碍服务仍处于开启状态后重试。"
                        )
                    )
                }
            }
        }

    /**
     * 把系统返回的 [AccessibilityService.ScreenshotResult] 复制为 ARGB_8888 位图。
     *
     * 复制完成后立即回收包装位图并关闭 [HardwareBuffer]；错误码非 0 时读取缓冲会抛出异常，按失败返回 null。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun copyToBitmap(result: AccessibilityService.ScreenshotResult): Bitmap? {
        var buffer: HardwareBuffer? = null
        var wrapped: Bitmap? = null
        try {
            val hardware = result.hardwareBuffer
            buffer = hardware
            wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
            return wrapped?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            wrapped?.recycle()
            buffer?.close()
        }
    }

    private companion object {
        /** 截屏超时：系统回调通常在一帧内返回，5 秒未回视为失败。 */
        const val TIMEOUT_MS = 5_000L
    }
}
