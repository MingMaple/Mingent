package com.zmf.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.main.TerminalScreen
import com.ai.assistance.operit.terminal.rememberTerminalEnv

/** 全屏终端页：承载终端模块的 [TerminalScreen]，状态栏与导航栏按沉浸式收起。 */
class TerminalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 终端按全屏沉浸处理：状态栏/导航栏收起，避免压住内容
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        // 首帧兜底：SurfaceView 刚 attach 时画布内容往往已经渲染出来，但窗口这一层没有重新合成，
        // 屏幕上就是一片黑；任何能让窗口重绘/重排一次的外部事件（滑动、收起输入法）都会让它出现。
        // 这里主动补几次「重新应用 insets + 重新布局」，等价于触发一次窗口级重绘。
        listOf(300L, 900L, 1800L).forEach { delayMs ->
            window.decorView.postDelayed({
                window.decorView.requestApplyInsets()
                window.decorView.requestLayout()
            }, delayMs)
        }
        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                val context = LocalContext.current
                val manager = remember { TerminalManager.getInstance(context) }
                val env = rememberTerminalEnv(manager)
                // 首帧兜底刷新已下沉到 CanvasTerminalView.refreshNow()（由 CanvasTerminalCompose 定时触发）：
                // 早先在这里 invalidate decorView 的做法无效，因为画布是自绘 Surface，不吃 View 系统的脏区重绘。
                TerminalScreen(
                    env = env,
                    useLocalImeHandling = true,
                    checkUpdatesOnEnter = false
                )
            }
        }
    }
}
