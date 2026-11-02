package com.zmf.agent.feature.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.zmf.agent.infra.terminal.TerminalScreenHost

/** 全屏终端页：承载终端模块的 [TerminalScreen]，状态栏与导航栏按沉浸式收起。 */
class TerminalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 终端按全屏沉浸处理：收起状态栏与导航栏，避免遮挡内容
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        // 首帧兜底：SurfaceView 刚 attach 时画布内容通常已渲染，但窗口层尚未重新合成，
        // 屏幕显示为黑色；任何触发窗口重绘或重排的外部事件（滑动、收起输入法）均会使其显示。
        // 此处主动执行数次「重新应用 insets + 重新布局」，等同于触发一次窗口级重绘。
        listOf(300L, 900L, 1800L).forEach { delayMs ->
            window.decorView.postDelayed({
                window.decorView.requestApplyInsets()
                window.decorView.requestLayout()
            }, delayMs)
        }
        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                // 上游的 manager、env 与 Screen 参数均封装在 infra 中，此处仅负责放置终端界面
                TerminalScreenHost()
            }
        }
    }
}
