package com.zmf.agent.app.navigation
import com.zmf.agent.feature.terminal.TerminalActivity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.zmf.agent.feature.float.FloatingBallService
import com.zmf.agent.feature.shell.AppDrawerContent
import com.zmf.agent.core.prefs.ThemeMode
import com.zmf.agent.feature.chat.findActivity
import com.zmf.agent.feature.chat.copyToClipboard
import com.zmf.agent.feature.chat.AppPage
import com.zmf.agent.feature.chat.ChatUiState
import com.zmf.agent.feature.chat.ChatViewModel

/**
 * 左侧抽屉内容：工作区、会话列表、主题与入口（主页、终端、设置、会话搜索、悬浮小窗）。
 *
 * 整块由回调与列表组成，不占用布局位置，因此独立于 [ChatScreen] 实现。
 * `drawerOpen` 与 `page` 的收放规则见各调用处注释。
 */
@Composable
internal fun ChatDrawerPane(
    viewModel: ChatViewModel,
    ui: ChatUiState,
    context: Context,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val scope = rememberCoroutineScope()
    var drawerOpen by ui.drawerOpen
    var page by ui.page
    var showCreateWorkspace by ui.showCreateWorkspace
    val forkWithReload: (() -> Unit) -> Unit = { action -> ui.forkWithReload(scope, action) }

                AppDrawerContent(
                    workspaces = viewModel.workspaceList,
                    sessions = viewModel.sessionList,
                    currentWorkspaceId = viewModel.currentWorkspaceId,
                    currentSessionId = viewModel.currentSessionId,
                    themeMode = themeMode,
                    // 主题在抽屉内联三选，选中即持久化（由 MainActivity 写入 ThemeStore）
                    onThemePick = { onThemeModeChange(it) },
                    onHome = {
                        drawerOpen = false
                        page = null
                    },
                    onTerminal = {
                        drawerOpen = false
                        context.startActivity(Intent(context, com.zmf.agent.feature.terminal.TerminalActivity::class.java))
                    },
                    onSettings = {
                        drawerOpen = false
                        page = AppPage.SETTINGS
                    },
                    onSearchSessions = {
                        drawerOpen = false
                        page = AppPage.SESSIONS
                    },
                    // 抽屉仅在四种情况下收起：进入会话、进入分叉、删除当前会话后自动新建、
                    // 新建工作区后自动进入会话；其余操作保持展开。
                    onPickSession = { id ->
                        drawerOpen = false
                        page = null
                        viewModel.switchSession(id)
                    },
                    onCreateSession = { workspaceId ->
                        if (workspaceId != viewModel.currentWorkspaceId) viewModel.switchWorkspace(workspaceId)
                        viewModel.newSession()
                    },
                    onCreateWorkspace = { showCreateWorkspace = true },
                    onRenameWorkspace = { id, name -> viewModel.renameWorkspace(id, name) },
                    onDeleteWorkspace = { id -> viewModel.deleteWorkspace(id) },
                    onRenameSession = { id, name -> viewModel.renameSession(id, name) },
                    onForkSession = { id ->
                        drawerOpen = false
                        page = null
                        forkWithReload {
                            viewModel.switchSession(id)
                            viewModel.forkSession()
                        }
                    },
                    onArchiveSession = { id -> viewModel.archiveSession(id) },
                    onPinSession = { id, pinned -> viewModel.pinSession(id, pinned) },
                    onCopySessionId = { id ->
                        copyToClipboard(context, id)
                        Toast.makeText(context, "已复制 Session ID", Toast.LENGTH_SHORT).show()
                    },
                    onDeleteSession = { id ->
                        // 删除当前会话时：ViewModel 会补充一条空会话，界面返回主页并收起抽屉
                        val wasCurrent = id == viewModel.currentSessionId
                        viewModel.deleteSession(id)
                        if (wasCurrent) {
                            drawerOpen = false
                            page = null
                        }
                    },
                    // 打开系统悬浮小窗：先收起抽屉，有权限时直接启动服务，否则请求权限。
                    // 小窗为独立系统悬浮窗，启动后将应用退至后台，仅保留小窗。
                    onFloatingWindow = {
                        drawerOpen = false
                        if (Settings.canDrawOverlays(context)) {
                            // expand = true：入口直接打开小窗，球态仅在用户折叠后出现
                            FloatingBallService.start(context, expand = true)
                            // 仅在应用内主动启动时退至后台；无法获取 Activity 时返回桌面兜底
                            val activity = findActivity(context)
                            if (activity != null) {
                                activity.moveTaskToBack(true)
                            } else {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_MAIN)
                                            .addCategory(Intent.CATEGORY_HOME)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        } else {
                            Toast.makeText(context, "请允许悬浮窗权限", Toast.LENGTH_SHORT).show()
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + context.packageName)
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }
                )
}
