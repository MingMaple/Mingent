package com.zmf.agent.app.navigation
import com.zmf.agent.feature.terminal.TerminalActivity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.zmf.agent.feature.float.FloatingBallService
import com.zmf.agent.feature.shell.AppDrawerContent
import com.zmf.agent.core.prefs.DrawerPrefs
import com.zmf.agent.core.prefs.ThemeMode
import com.zmf.agent.feature.chat.findActivity
import com.zmf.agent.feature.chat.copyToClipboard
import com.zmf.agent.feature.chat.AppPage
import com.zmf.agent.feature.chat.ChatUiState
import com.zmf.agent.feature.chat.ChatViewModel
import com.zmf.agent.uikit.common.NoticeCenter
import com.zmf.agent.uikit.common.noticeName
import com.zmf.agent.uikit.common.NoticeTone

/**
 * 左侧抽屉内容：工作区、会话列表、主题与入口（主页、终端、设置、悬浮小窗）。
 * 会话搜索与归档筛选都在抽屉内完成，不再跳转二级页。
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
                    // 抽屉收起时复位内联搜索，下次打开回到分组树
                    drawerOpen = drawerOpen,
                    themeMode = themeMode,
                    // 主题在抽屉内联三选，选中即持久化（由 MainActivity 写入 ThemeStore）
                    onThemePick = { onThemeModeChange(it) },
                    onHome = {
                        drawerOpen = false
                        page = null
                    },
                    onTerminal = {
                        drawerOpen = false
                        context.startActivity(Intent(context, TerminalActivity::class.java))
                    },
                    onSettings = {
                        drawerOpen = false
                        page = AppPage.SETTINGS
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
                    onDeleteWorkspace = { id ->
                        viewModel.deleteWorkspace(id)
                        // 分组的展开状态留在偏好里也读不回来了：随工作区一起清掉
                        DrawerPrefs.forget(context, id)
                    },
                    onRenameSession = { id, name -> viewModel.renameSession(id, name) },
                    onForkSession = { id ->
                        drawerOpen = false
                        page = null
                        forkWithReload {
                            viewModel.switchSession(id)
                            viewModel.forkSession()
                        }
                    },
                    onArchiveSession = { id ->
                        // 常规归档只给一条可撤销的提示；命中特例时留给确认弹窗说明后果
                        val notes = viewModel.requestArchive(id)
                        if (notes.isEmpty()) {
                            val title = noticeName(
                                viewModel.sessionList.firstOrNull { it.id == id }?.title.orEmpty()
                            )
                            NoticeCenter.undo("已归档「" + title + "」") {
                                val demoted = viewModel.unarchiveSession(id)
                                NoticeCenter.show(
                                    if (demoted) "已恢复，归入「未分类」" else "已恢复「" + title + "」",
                                    NoticeTone.Success
                                )
                            }
                        } else {
                            ui.pendingArchiveId.value = id
                        }
                    },
                    // 与 DSH 一致：已归档会话只出现在筛选结果里，点击不直接打开
                    onArchivedSessionOpen = {
                        NoticeCenter.info("已归档会话需先取消归档")
                    },
                    onPinSession = { id, pinned -> viewModel.pinSession(id, pinned) },
                    onCopySessionId = { id ->
                        copyToClipboard(context, id)
                        NoticeCenter.info("已复制 Session ID")
                    },
                    // 会话的删除只在设置里的归档页提供，抽屉侧只负责发起「更改工作区」
                    onChangeWorkspace = { session -> ui.movingSessionId.value = session.id },
                    onChangeStorage = { space -> ui.storageWorkspaceId.value = space.id },
                    onDeleteWorkspacePrompt = { id -> viewModel.deleteWorkspacePrompt(id) },
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
                            NoticeCenter.error("需悬浮窗权限，正在打开授权页")
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
