package com.zmf.agent.app

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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zmf.agent.core.AppForeground
import com.zmf.agent.infra.system.ShizukuBridge
import com.zmf.agent.core.notify.Notifier
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.feature.schedule.ScheduleRunner
import com.zmf.agent.app.navigation.ChatScreen
import com.zmf.agent.uikit.common.NoticeHost
import com.zmf.agent.uikit.theme.AgentTheme
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.core.prefs.ThemeMode
import com.zmf.agent.core.prefs.ThemeStore
import com.zmf.agent.core.prefs.UiScaleStore

/**
 * 主界面：承载聊天页，并负责进程级初始化（通知会话跳转、崩溃日志写入、Shizuku 绑定、
 * 定时任务重排、窗口底色）与前台状态计数。
 */
class MainActivity : ComponentActivity() {

    /** 从通知进入时需要切换到的会话；null 表示本次不是从通知进入。 */
    private var notificationSessionId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 冷启动：通知携带的会话 id 位于启动 Intent 中（空字符串视为未携带）
        notificationSessionId = sessionIdFrom(intent)
        // 读取后立即移除：Activity 因配置变化重建时会复用同一 Intent，残留 extra 会再次切换
        // 已处理的通知会话，覆盖用户后续选择
        intent?.removeExtra(Notifier.EXTRA_SESSION_ID)
        installCrashHandler()
        enableEdgeToEdge()
        hideStatusBar()
        // Shizuku 必须在 setContent 之前初始化：权限页进入时即读取 state，
        // 且 binder 连接与丢失回调需先于界面挂载，否则首屏状态始终显示为「未安装」
        ShizukuBridge.init(this)
        // 定时任务：先注册到会话归档所需的窄口，再为所有已启用任务重排闹钟
        // （重排用于重装、重启或被系统清除后的兜底）
        // 日志须先于重排绑定：重排发生在界面之前，未绑定时它写的「重排 N 条」会被静默丢弃
        FileLog.init(this)
        runCatching { ScheduleRunner.installBridge(this) }
        runCatching { ScheduleRunner.rescheduleAll(this) }
        // 必须早于 setContent：首帧显示的是窗口底色，未提前设置时深色主题冷启动会闪现系统浅色窗底
        applyWindowBackground()
        setContent {
            val context = applicationContext
            // 深浅色仅修改此处的 Compose 状态，不重建 Activity，会话状态不会丢失
            var mode by remember { mutableStateOf(ThemeStore.load(context)) }
            // 界面字号：档位合法性由 UiScaleStore 保证，读取结果已回退到合法档
            var uiScale by remember { mutableStateOf(UiScaleStore.load(context)) }
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // uiScale 仅放大字号（AgentTheme 内部仅乘以 fontScale），修改不重建 Activity，会话不丢失
            AgentTheme(darkTheme = dark, uiScale = uiScale) {
                // 提示条挂在整棵界面的最外层：抽屉、设置各级页、全屏页都在它下面，
                // 任何位置发起的一次反馈都能以同一个形态出现在屏幕顶部
                Box(modifier = Modifier.fillMaxSize()) {
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
                    NoticeHost(modifier = Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }

    /**
     * 窗口底色取当前主题的页面背景色，避免启动与切页时露出系统默认底色。
     *
     * isSystemInDarkTheme() 只能在组合中读取；「跟随系统」档在此读取相同的夜间配置，
     * 判定结果与组合内一致。
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

    /** 点击通知（应用已在后台或前台）：仅切换会话 id，不重建界面。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sessionIdFrom(intent)?.let { notificationSessionId = it }
        // 同样移除会话 id，避免重建时再次消费
        intent.removeExtra(Notifier.EXTRA_SESSION_ID)
    }

    /**
     * 状态栏沉浸：与 Salt 一致，默认完全隐藏，从屏幕顶部下滑时才临时伸出，
     * 约两秒后由系统自动收回（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE）。
     *
     * 不隐藏导航栏：底部手势条是全面屏返回的唯一提示，隐藏后不便于操作。
     */
    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    /**
     * 回到前台、切回本应用或旋转屏幕后系统可能重新显示状态栏；重新申请隐藏，
     * 保证每次回到聊天页都恢复沉浸式，而不是等用户再下滑一次。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    /** 前台计数加 1：只有实际显示在屏幕上的 Activity 才算用户正在查看，通知据此决定是否弹出。 */
    override fun onResume() {
        super.onResume()
        AppForeground.active++
    }

    override fun onPause() {
        super.onPause()
        // 计数为正时才递减：个别机型或多窗口会重复回调，减为负数会导致前台判断持续错误，影响通知发送
        if (AppForeground.active > 0) AppForeground.active--
    }

    /**
     * 退至后台时强制刷新日志队列。
     *
     * 用户常直接从最近任务中划掉应用，该过程仅触发 onStop；日志为异步写入，
     * 不在此刷新则最近若干行（常包含问题线索）会随进程丢失。300ms 为写线程余量。
     */
    override fun onStop() {
        super.onStop()
        runCatching { com.zmf.agent.core.log.FileLog.flush(300L) }
    }

    /** 读取通知携带的会话 id；不存在或为空字符串时返回 null。 */
    private fun sessionIdFrom(intent: Intent?): String? =
        intent?.getStringExtra(Notifier.EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }

    /**
     * 崩溃日志写入。
     *
     * 本机 vivo 设备没有可用的 logcat，崩溃后只能通过 adb 现场获取；将 crash.log 写入外部私有目录，
     * 用户可在「设置 → 日志」中直接查看，无需再描述现象。写入失败不得影响原有崩溃流程。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 日志改为异步队列后，崩溃前必须先将队列刷出，否则最后数十行（通常包含线索）会随进程丢失
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
