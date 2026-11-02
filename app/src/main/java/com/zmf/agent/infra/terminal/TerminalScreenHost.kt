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
 * 设置该层的原因：`TerminalActivity` 只需知道「此处放置一个终端」，
 * 不应了解上游如何获取 manager、env 的名称及 Screen 的参数数量。上游签名变更时仅需修改本文件。
 *
 * 首帧兜底刷新已下沉至 CanvasTerminalView.refreshNow()（由 CanvasTerminalCompose 定时触发）：
 * 在 Activity 中调用 invalidate decorView 的方式无效，画布为自绘 Surface，不响应 View 系统的脏区重绘。
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
