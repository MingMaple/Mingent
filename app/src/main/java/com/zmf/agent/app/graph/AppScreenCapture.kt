package com.zmf.agent.app.graph

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import com.zmf.agent.capability.port.CaptureResult
import com.zmf.agent.capability.port.CaptureTarget
import com.zmf.agent.capability.port.ScreenCapture
import com.zmf.agent.app.capture.AccessibilityCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * 截屏端口实现。
 *
 * `SELF` 用 PixelCopy 截取本应用当前 Activity 的窗口，不申请任何权限；
 * `FOREGROUND_APP` 委托 [AccessibilityCapture]，由无障碍服务完成。
 *
 * 前台 Activity 由 [Application.ActivityLifecycleCallbacks] 记录，只保存弱引用：
 * 截屏对象与 Activity 的生命周期相互独立，强引用会使已销毁的界面无法回收。
 */
class AppScreenCapture(context: Context) : ScreenCapture {

    private val application = context.applicationContext as? Application

    /** 最近进入 RESUMED 的 Activity；退至暂停或销毁时必须清空，否则会把后台窗口当作前台。 */
    @Volatile
    private var resumed = WeakReference<Activity>(null)

    init {
        application?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumed.get() === activity) resumed = WeakReference<Activity>(null)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (resumed.get() === activity) resumed = WeakReference<Activity>(null)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    override suspend fun capture(target: CaptureTarget, expectedPackage: String?): CaptureResult =
        when (target) {
            CaptureTarget.SELF -> captureSelf()
            CaptureTarget.FOREGROUND_APP -> AccessibilityCapture.capture(expectedPackage)
        }

    /** 截取本应用当前窗口；PixelCopy 失败时退回视图绘制。 */
    private suspend fun captureSelf(): CaptureResult {
        val activity = resumed.get() ?: return appNotForeground()
        val decor = activity.window?.decorView ?: return appNotForeground()
        val metrics = activity.resources.displayMetrics
        val width = decor.width.takeIf { it > 0 } ?: metrics.widthPixels
        val height = decor.height.takeIf { it > 0 } ?: metrics.heightPixels
        if (width <= 0 || height <= 0) {
            return captureFailed("本应用窗口尺寸无效：必须等待界面完成布局后重试。")
        }
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return captureFailed("创建位图时内存不足：必须降低界面分辨率后重试。")
        }
        if (pixelCopy(activity, bitmap)) {
            return CaptureResult.Success(bitmap, activity.packageName, System.currentTimeMillis())
        }
        bitmap.recycle()
        val drawn = drawFallback(decor, width, height)
            ?: return captureFailed("本应用窗口截取失败：PixelCopy 与视图绘制均未成功，必须稍后重试；若持续失败，应重启本应用。")
        return CaptureResult.Success(drawn, activity.packageName, System.currentTimeMillis())
    }

    /** 用 PixelCopy 复制窗口画面；窗口已不可复制时返回 false。 */
    private suspend fun pixelCopy(activity: Activity, bitmap: Bitmap): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                val started = runCatching {
                    PixelCopy.request(
                        activity.window,
                        bitmap,
                        { result -> if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS) },
                        handler
                    )
                }
                if (started.isFailure && continuation.isActive) continuation.resume(false)
            }
        }

    /** 退回视图绘制：只能得到 View 树的内容，不含系统合成层；绘制异常时返回 null。 */
    private suspend fun drawFallback(view: View, width: Int, height: Int): Bitmap? =
        withContext(Dispatchers.Main) {
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.TRANSPARENT)
                view.draw(Canvas(bitmap))
                bitmap
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }

    private fun appNotForeground(): CaptureResult = CaptureResult.Failure(
        "E_APP_NOT_FOREGROUND",
        "本应用当前没有前台窗口：必须先将本应用切到前台后重试，或改用 source=foreground_app 截取其他应用。"
    )

    private fun captureFailed(message: String): CaptureResult = CaptureResult.Failure("E_CAPTURE_FAILED", message)
}
