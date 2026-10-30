package com.zmf.agent.infra.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.main.TerminalScreen
import com.ai.assistance.operit.terminal.rememberTerminalEnv

/**
 * 终端界面的宿主：把上游的 `TerminalScreen` 连同它需要的 env 一起包起来。
 *
 * 为什么要有这一层：`TerminalActivity` 只该知道「这里放一个终端」，
 * 不该知道上游怎么取 manager、env 叫什么、Screen 有几个参数。上游改签名只改这个文件。
 *
 * 首帧兜底刷新已下沉到 CanvasTerminalView.refreshNow()（由 CanvasTerminalCompose 定时触发）：
 * 早先在 Activity 里 invalidate decorView 的做法无效，画布是自绘 Surface，不吃 View 系统的脏区重绘。
 */
@Composable
fun TerminalScreenHost() {
    val context = LocalContext.current
    val manager = remember { TerminalManager.getInstance(context) }
    val env = rememberTerminalEnv(manager)
    TerminalScreen(
        env = env,
        useLocalImeHandling = true,
        checkUpdatesOnEnter = false
    )
}
