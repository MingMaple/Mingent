package com.zmf.agent

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zmf.agent.core.AppForeground
import com.zmf.agent.core.capability.ShizukuBridge
import com.zmf.agent.core.notify.Notifier
import com.zmf.agent.schedule.ScheduleRunner
import com.zmf.agent.ui.chat.ChatScreen
import com.zmf.agent.ui.theme.AgentTheme
import com.zmf.agent.ui.theme.Palette
import com.zmf.agent.ui.theme.ThemeMode
import com.zmf.agent.ui.theme.ThemeStore
import com.zmf.agent.ui.theme.UiScaleStore

class MainActivity : ComponentActivity() {

    /** 从通知点进来要切到的会话；null = 这次不是从通知进来的。 */
    private var notificationSessionId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 冷启动：通知带的会话 id 就在启动 Intent 上（空串按「没带」处理）
        notificationSessionId = sessionIdFrom(intent)
        installCrashHandler()
        enableEdgeToEdge()
        // Shizuku 必须在 setContent 之前初始化：权限页一进来就要读 state，
        // 而且 binder 到手/丢失的回调要挂得比界面早，否则首屏状态永远是「未安装」
        ShizukuBridge.init(this)
        // 定时任务：进程起来时把所有 enabled 任务重排一次闹钟（重装/重启/被系统清掉后靠这行兜底）
        runCatching { ScheduleRunner.rescheduleAll(this) }
        // 必须排在 setContent 之前：Compose 起来后第一帧之前露的是窗口底色，
        // 深色主题下不先刷成深色，冷启动会闪一下系统浅色窗底
        applyWindowBackground()
        setContent {
            val context = applicationContext
            // 深浅色只改这里的 Compose 状态，不重建 Activity，所以会话不会丢
            var mode by remember { mutableStateOf(ThemeStore.load(context)) }
            // 界面字号：档位合法性由 UiScaleStore 保证（读回来已经回落到合法档）
            var uiScale by remember { mutableStateOf(UiScaleStore.load(context)) }
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // uiScale 只放大字号（AgentTheme 内部只乘 fontScale），改它不重建 Activity，会话不丢
            AgentTheme(darkTheme = dark, uiScale = uiScale) {
                ChatScreen(
                    themeMode = mode,
                    onThemeModeChange = { picked ->
                        mode = picked
                        ThemeStore.save(context, picked)
                    },
                    uiScale = uiScale,
                    onUiScaleChange = { picked ->
                        uiScale = picked
                        UiScaleStore.save(context, picked)
                    },
                    initialSessionId = notificationSessionId
                )
            }
        }
    }

    /**
     * 把窗口底色刷成当前主题的页面背景色。
     *
     * 为什么不用 isSystemInDarkTheme()：它是 @Composable，只能进组合里读；
     * 「跟随系统」这一档在这里改成读同一份夜间配置，判定结果与组合里一致。
     */
    private fun applyWindowBackground() {
        val systemNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (ThemeStore.load(this)) {
            ThemeMode.SYSTEM -> systemNight
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        val color = if (dark) Palette.DarkBackground else Palette.LightBackground
        window.setBackgroundDrawable(ColorDrawable(color.toArgb()))
    }

    /** 点通知（App 已经在后台或前台）：只换会话 id，不重建界面。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sessionIdFrom(intent)?.let { notificationSessionId = it }
    }

    /** 前台计数 +1：只有真正露在屏幕上的 Activity 才算「用户在看」，通知据此决定弹不弹。 */
    override fun onResume() {
        super.onResume()
        AppForeground.active++
    }

    override fun onPause() {
        super.onPause()
        // 计数为正时才减：个别机型/多窗口会重复回调，减成负数后前台判断会一直错（少发通知）
        if (AppForeground.active > 0) AppForeground.active--
    }

    /**
     * 退到后台：把日志队列强制刷一次。
     *
     * 用户习惯直接从最近任务里划掉 App —— 那一步只会走 onStop，不会走任何"优雅退出"，
     * 日志是异步写的，不在这里刷一次，最后那一两秒（往往正好是出问题的那几行）就随进程没了。
     * 300ms 是给写线程的余量：正常情况下队列是空的，这一次调用几乎不花时间。
     */
    override fun onStop() {
        super.onStop()
        runCatching { com.zmf.agent.core.log.FileLog.flush(300L) }
    }

    /** 读通知带过来的会话 id；没有、空串都返回 null。 */
    private fun sessionIdFrom(intent: Intent?): String? =
        intent?.getStringExtra(Notifier.EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }

    /**
     * 崩溃落盘。
     *
     * 这台 vivo 没有可用的 logcat，崩了只能 adb 现场捞；写一份 crash.log 到外部私有目录，
     * 用户在「设置 → 日志」里直接能翻到，不必再描述现象。写失败绝不能影响原本的崩溃流程。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 日志改成异步队列后，崩溃前必须先把队列刷出去，否则最后那几十行（往往正好有线索）会随进程一起没了
            runCatching { com.zmf.agent.core.log.FileLog.flush(2000L) }
            runCatching { appendCrashLog(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun appendCrashLog(thread: Thread, error: Throwable) {
        val dir = java.io.File(getExternalFilesDir(null), "logs")
        if (!dir.exists()) dir.mkdirs()
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("?")
        val text = buildString {
            append(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date())
            )
            append(" [crash] v").append(version)
            append(" sdk=").append(Build.VERSION.SDK_INT)
            append(" model=").append(Build.MODEL)
            append(" thread=").append(thread.name)
            append('\n')
            append(Log.getStackTraceString(error))
            append('\n')
        }
        java.io.File(dir, "crash.log").appendText(text, Charsets.UTF_8)
    }
}
