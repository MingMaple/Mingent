package com.zmf.agent.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zmf.agent.agent.session.ChatSession
import com.zmf.agent.agent.session.SessionStore
import com.zmf.agent.agent.workspace.WorkSpace
import com.zmf.agent.agent.workspace.WorkSpaceStore
import com.zmf.agent.ui.chat.DshIcons
import com.zmf.agent.ui.chat.FolderIcon
import com.zmf.agent.ui.chat.PlusIcon
import com.zmf.agent.ui.common.Chevron
import com.zmf.agent.ui.common.ChevronSize
import com.zmf.agent.ui.theme.Motion
import com.zmf.agent.ui.theme.Palette
import com.zmf.agent.ui.theme.ThemeMode

/** 工作区展开后先只显示这么多条会话。 */
private const val SESSION_PREVIEW = 5

/** 展开状态表的键：null（未分类）用空串占位，工作区 id 不会是空串。 */
private const val NO_WORKSPACE_KEY = ""

/** 树里的一个分组：工作区为 null 表示未分类。 */
private data class WorkspaceGroup(val space: WorkSpace?, val sessions: List<ChatSession>)

/**
 * 侧边栏，仿 DSH 左侧工作区栏：回到主页 / 终端 / 设置三行常驻，下面是可折叠的工作区与会话树。
 *
 * 层级口径（用户反馈的重点）：工作区是分组标题（14sp 半粗 + 文件夹图标），
 * 会话是它的子项（13sp 常规、整体再缩进 14dp、字色更淡），这样深浅色下都能一眼看出从属关系。
 */
@Composable
fun AppDrawerContent(
    workspaces: List<WorkSpace>,
    sessions: List<ChatSession>,
    currentWorkspaceId: String?,
    currentSessionId: String,
    themeMode: ThemeMode,
    onThemePick: (ThemeMode) -> Unit,
    onFloatingWindow: () -> Unit,
    onHome: () -> Unit,
    onTerminal: () -> Unit,
    onSettings: () -> Unit,
    onSearchSessions: () -> Unit,
    onPickSession: (String) -> Unit,
    onCreateSession: (String?) -> Unit,
    onCreateWorkspace: () -> Unit,
    onRenameWorkspace: (String, String) -> Unit,
    onDeleteWorkspace: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onForkSession: (String) -> Unit,
    onArchiveSession: (String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onCopySessionId: (String) -> Unit,
    onDeleteSession: (String) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    // 分组只在数据变化时重算：置顶的排前面，其余按 updatedAt 倒序；
    // 未分类永远留一组，否则没有落点看那些不属于任何工作区的历史会话
    val known = remember(workspaces) { workspaces.map { it.id }.toSet() }
    val groups = remember(workspaces, sessions) {
        val order = compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt }
        val buckets = sessions
            .filter { !it.archived }
            .groupBy { session -> session.workspaceId?.takeIf { it in known } }
        val list = ArrayList<WorkspaceGroup>(workspaces.size + 1)
        for (space in workspaces) {
            list.add(WorkspaceGroup(space, buckets[space.id].orEmpty().sortedWith(order)))
        }
        list.add(WorkspaceGroup(null, buckets[null].orEmpty().sortedWith(order)))
        list
    }

    // 展开状态按工作区记：没点过就用默认「只有当前工作区展开」
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val showAll = remember { mutableStateMapOf<String, Boolean>() }
    var themeExpanded by remember { mutableStateOf(false) }
    var sessionMenu by remember { mutableStateOf<ChatSession?>(null) }
    var workspaceMenu by remember { mutableStateOf<WorkSpace?>(null) }
    var renamingSession by remember { mutableStateOf<ChatSession?>(null) }
    var renamingWorkspace by remember { mutableStateOf<WorkSpace?>(null) }
    var deletingSession by remember { mutableStateOf<ChatSession?>(null) }
    var deletingWorkspace by remember { mutableStateOf<WorkSpace?>(null) }

    Column(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 头部（小窗入口 + 主题按钮）与主题面板合成一个 Column：
        // 主题面板收起时，外层 spacedBy 不能再因为一个高度为 0 的子项多留一条缝
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左上角原来的「Agent」大字位置：一个纯图形的小窗入口（不写字，形状即语义）
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(onClick = onFloatingWindow),
                    contentAlignment = Alignment.Center
                ) {
                    // 窗口图形：外框圆角矩形 + 顶部标题条 + 里面两条「消息」短线
                    Canvas(modifier = Modifier.size(20.dp)) {
                        val green = Palette.AccentGreen
                        val stroke = size.minDimension * 0.09f
                        val body = Rect(
                            left = 0f,
                            top = size.height * 0.12f,
                            right = size.width,
                            bottom = size.height * 0.88f
                        )
                        drawRoundRect(
                            color = green,
                            topLeft = body.topLeft,
                            size = body.size,
                            cornerRadius = CornerRadius(size.minDimension * 0.18f),
                            style = Stroke(width = stroke)
                        )
                        drawLine(
                            color = green,
                            start = Offset(body.left, size.height * 0.36f),
                            end = Offset(body.right, size.height * 0.36f),
                            strokeWidth = stroke
                        )
                        drawLine(
                            color = green.copy(alpha = 0.8f),
                            start = Offset(size.width * 0.22f, size.height * 0.55f),
                            end = Offset(size.width * 0.72f, size.height * 0.55f),
                            strokeWidth = stroke * 0.8f
                        )
                        drawLine(
                            color = green.copy(alpha = 0.55f),
                            start = Offset(size.width * 0.22f, size.height * 0.70f),
                            end = Offset(size.width * 0.52f, size.height * 0.70f),
                            strokeWidth = stroke * 0.8f
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // 主题入口：不再跳页面，就地展开三行（展开态用绿字标记）
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { themeExpanded = !themeExpanded },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = themeMode.glyph,
                        fontSize = 16.sp,
                        color = if (themeExpanded) Palette.Neutral else onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            // 主题三选项：从按钮下方整块推开，收起时高度弹簧收回，避免抽屉里出现「闪一下」的硬切
            AnimatedVisibility(
                visible = themeExpanded,
                enter = expandVertically(animationSpec = Motion.spring()) + fadeIn(Motion.fadeIn()),
                exit = shrinkVertically(animationSpec = Motion.spring()) + fadeOut(Motion.fadeOut())
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                        Text(
                            text = "主题",
                            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                            fontSize = 12.sp,
                            color = onSurface.copy(alpha = 0.45f)
                        )
                        ThemeMode.entries.forEach { mode ->
                            ThemePickRow(
                                mode = mode,
                                selected = mode == themeMode,
                                onClick = {
                                    themeExpanded = false
                                    onThemePick(mode)
                                }
                            )
                        }
                    }
                }
            }
        }

        DrawerCard {
            // 三行图标走同一套 24 网格线稿（DshIcons），不再用「⌂ ❯ ⚙」这类字符：字符的字重、基线
            // 全由字体决定，换设备就可能忽大忽小、跟文字对不齐
            DrawerRow(icon = DshIcons.Home, label = "主页", onClick = onHome)
            DrawerRow(icon = DshIcons.Terminal, label = "终端", value = "Ubuntu 24 · PRoot", onClick = onTerminal)
            DrawerRow(icon = DshIcons.Gear, label = "设置", onClick = onSettings)
        }

        // 删掉底部两行之后，这里的小标题行是「搜索会话 / 新建工作区」仅剩的入口
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "工作区",
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                color = onSurface.copy(alpha = 0.45f)
            )
            IconHit(32.dp, onSearchSessions) {
                Canvas(modifier = Modifier.size(14.dp)) {
                    val tint = onSurface.copy(alpha = 0.55f)
                    val stroke = size.minDimension * 0.1f
                    drawCircle(
                        color = tint,
                        radius = size.minDimension * 0.32f,
                        center = Offset(size.width * 0.42f, size.height * 0.42f),
                        style = Stroke(width = stroke)
                    )
                    drawLine(
                        color = tint,
                        start = Offset(size.width * 0.63f, size.height * 0.63f),
                        end = Offset(size.width * 0.92f, size.height * 0.92f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
            IconHit(32.dp, onCreateWorkspace) {
                Icon(
                    imageVector = PlusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Palette.Neutral
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp)
            ) {
                groups.forEach { group ->
                    val space = group.space
                    val key = space?.id ?: NO_WORKSPACE_KEY
                    // 没手动点过就按默认：只有当前工作区展开
                    val open = expanded[key] ?: (space?.id == currentWorkspaceId)
                    WorkspaceSection(
                        name = space?.name ?: "未分类",
                        sessions = group.sessions,
                        isCurrentWorkspace = space?.id == currentWorkspaceId,
                        expanded = open,
                        showAll = showAll[key] == true,
                        currentSessionId = currentSessionId,
                        onToggle = { expanded[key] = !open },
                        onShowAllChange = { showAll[key] = it },
                        onPickSession = onPickSession,
                        onSessionMenu = { sessionMenu = it },
                        onCreateSession = { onCreateSession(space?.id) },
                        // 未分类不是一个工作区：没有重命名 / 删除的对象，右侧「⋯」整块不出现
                        onWorkspaceMenu = space?.let { target -> { workspaceMenu = target } }
                    )
                }
            }
        }
    }

    // 会话窗口：六项，顺序与文案照 DSH
    sessionMenu?.let { session ->
        MinimalDialog(onDismiss = { sessionMenu = null }) {
            MenuRow(text = if (session.pinned) "取消置顶" else "置顶会话") {
                sessionMenu = null
                onPinSession(session.id, !session.pinned)
            }
            MenuRow(text = "重命名") {
                sessionMenu = null
                renamingSession = session
            }
            MenuRow(text = "分叉会话") {
                sessionMenu = null
                onForkSession(session.id)
            }
            MenuRow(text = "归档会话") {
                sessionMenu = null
                onArchiveSession(session.id)
            }
            MenuRow(text = "复制 Session ID") {
                sessionMenu = null
                onCopySessionId(session.id)
            }
            MenuRow(text = "删除会话", color = MaterialTheme.colorScheme.error) {
                sessionMenu = null
                deletingSession = session
            }
        }
    }

    // 工作区窗口：只有当前工作区行才挂「⋯」，所以不会出现点了没反应的分组
    workspaceMenu?.let { space ->
        MinimalDialog(onDismiss = { workspaceMenu = null }) {
            MenuRow(text = "重命名工作区") {
                workspaceMenu = null
                renamingWorkspace = space
            }
            // 默认工作区删不掉（WorkSpaceStore 明确拒绝），菜单里干脆不出现
            if (space.id != WorkSpaceStore.DEFAULT_ID) {
                MenuRow(text = "删除工作区", color = MaterialTheme.colorScheme.error) {
                    workspaceMenu = null
                    deletingWorkspace = space
                }
            }
        }
    }

    renamingWorkspace?.let { space ->
        RenameDialog(
            title = "重命名工作区",
            initial = space.name,
            onDismiss = { renamingWorkspace = null },
            onSave = { name ->
                renamingWorkspace = null
                onRenameWorkspace(space.id, name)
            }
        )
    }

    renamingSession?.let { session ->
        RenameDialog(
            title = "重命名会话",
            // 界面上一律显示「新会话」，输入框里也得是用户看到的那个名字，否则像是丢字
            initial = session.title.ifBlank { "新会话" },
            onDismiss = { renamingSession = null },
            onSave = { name ->
                renamingSession = null
                onRenameSession(session.id, name)
            }
        )
    }

    deletingSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deletingSession = null },
            title = { Text("删除会话", fontSize = 17.sp) },
            text = {
                Text(
                    text = "「" + session.title.ifBlank { "新会话" } + "」以及里面的对话都会被删除，不能撤销。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deletingSession = null
                    onDeleteSession(session.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingSession = null }) { Text("取消") }
            }
        )
    }

    deletingWorkspace?.let { space ->
        AlertDialog(
            onDismissRequest = { deletingWorkspace = null },
            title = { Text("删除工作区", fontSize = 17.sp) },
            text = {
                Text(
                    text = "「" + space.name + "」的目录移入回收站，里面的会话会解绑到「未分类」，不能撤销。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deletingWorkspace = null
                    onDeleteWorkspace(space.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingWorkspace = null }) { Text("取消") }
            }
        )
    }
}

/** 主题面板里的一行：选中行加 1dp 描边，不用 Material 那套单选控件。 */
@Composable
private fun ThemePickRow(mode: ThemeMode, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (selected) Modifier.border(1.dp, onSurface.copy(alpha = 0.22f), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = mode.glyph,
            fontSize = 15.sp,
            color = if (selected) onSurface else onSurface.copy(alpha = 0.45f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = mode.label,
            fontSize = 15.sp,
            // 选中用满不透明正文色、未选中压到 45%：强调色已经很接近正文色，
            // 再拿它区分「选中／未选中」两边会糊成一片，只能靠不透明度拉层级
            color = if (selected) onSurface else onSurface.copy(alpha = 0.45f)
        )
    }
}

/**
 * 一个工作区：标题行（文件夹图标 + 名称 + 折叠箭头 + 右侧图标区）加它展开后的会话。
 *
 * 点标题行只做展开/折叠，绝不切当前工作区——切换由用户点会话或右侧「＋」决定，
 * 否则在抽屉里「看别的工作区有什么」会顺带改掉当前上下文。
 */
@Composable
private fun WorkspaceSection(
    name: String,
    sessions: List<ChatSession>,
    isCurrentWorkspace: Boolean,
    expanded: Boolean,
    showAll: Boolean,
    currentSessionId: String,
    onToggle: () -> Unit,
    onShowAllChange: (Boolean) -> Unit,
    onPickSession: (String) -> Unit,
    onSessionMenu: (ChatSession) -> Unit,
    onCreateSession: () -> Unit,
    onWorkspaceMenu: (() -> Unit)?
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            // 纵向内距从 8dp 收到 4dp：右侧 32dp 命中区会把行撑到 48dp 高，
            // 收紧一点后整行约 40dp，跟原来的 35dp 接近，抽屉里的分组节奏不至于被拉散
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 折叠时图标压淡一档：不展开的分组在视觉上往后退，展开的分组是当前在读的
        Icon(
            imageVector = FolderIcon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = onSurface.copy(alpha = if (expanded) 0.78f else 0.42f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            color = onSurface.copy(alpha = if (isCurrentWorkspace) 1f else 0.76f)
        )
        Spacer(Modifier.width(2.dp))
        // 右侧三个命中区：折叠箭头 / 「⋯」/「＋」一律 32dp、间距 8dp、整体贴右。
        // 后两个只给当前工作区——不是当前工作区时整块不出现，行尾干净
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconHit(32.dp, onToggle) {
                Chevron(
                    expanded = expanded,
                    size = ChevronSize.SMALL,
                    tint = onSurface.copy(alpha = 0.4f)
                )
            }
            if (isCurrentWorkspace) {
                if (onWorkspaceMenu != null) {
                    IconHit(32.dp, onWorkspaceMenu) {
                        Text(
                            text = "⋯",
                            fontSize = 15.sp,
                            color = onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
                IconHit(32.dp, onCreateSession) {
                    Icon(
                        imageVector = PlusIcon,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = Palette.Neutral
                    )
                }
            }
        }
    }

    if (expanded) {
        val visible = if (showAll || sessions.size <= SESSION_PREVIEW) {
            sessions
        } else {
            sessions.take(SESSION_PREVIEW)
        }
        visible.forEach { session ->
            SessionTreeRow(
                session = session,
                isCurrent = session.id == currentSessionId,
                onClick = { onPickSession(session.id) },
                onMenu = { onSessionMenu(session) }
            )
        }
        if (sessions.size > SESSION_PREVIEW) {
            Text(
                text = if (showAll) "收起" else "展开其余 " + (sessions.size - SESSION_PREVIEW) + " 个会话",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowAllChange(!showAll) }
                    .padding(start = 40.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
                fontSize = 12.sp,
                color = Palette.AccentGreen.copy(alpha = 0.9f)
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * 树里的一行会话：标题 + 相对时间 + 常显的「⋯」。
 *
 * 内缩 14dp（相对工作区行的左内距）再配小一号的字和更淡的字色，
 * 是「会话属于上面的工作区」这件事唯一的视觉线索，所以不能省。
 */
@Composable
private fun SessionTreeRow(
    session: ChatSession,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) Palette.AccentGreen.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = session.title.ifBlank { "新会话" },
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isCurrent) onSurface else onSurface.copy(alpha = 0.62f)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = SessionStore.relativeTime(session.updatedAt),
            fontSize = 10.sp,
            maxLines = 1,
            color = if (isCurrent) onSurface.copy(alpha = 0.72f) else onSurface.copy(alpha = 0.3f)
        )
        IconHit(36.dp, onMenu) {
            Text(
                text = "⋯",
                fontSize = 15.sp,
                color = if (isCurrent) onSurface.copy(alpha = 0.9f) else onSurface.copy(alpha = 0.35f)
            )
        }
    }
}

/** 行内小图标的热区：图形小、热区大，免得手指点不准（36dp 是可稳定点中的下限）。 */
@Composable
private fun IconHit(size: Dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 极简弹窗：没有标题栏，只有可点的行。 */
@Composable
private fun MinimalDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                content = content
            )
        }
    }
}

/** 弹窗里的一行。 */
@Composable
private fun MenuRow(text: String, color: Color? = null, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        fontSize = 15.sp,
        color = color ?: MaterialTheme.colorScheme.onSurface
    )
}

/**
 * 重命名用的悬浮输入框：预填原名、全部选中（用户一打字就是整段替换），
 * 右边对勾保存、左边叉号放弃。不做标题栏，跟其它悬浮卡同一套观感。
 */
@Composable
private fun RenameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var value by remember(initial) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focusRequester = remember { FocusRequester() }

    MinimalDialog(onDismiss = onDismiss) {
        Text(
            text = title,
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
        Spacer(Modifier.height(10.dp))
        Surface(
            color = onSurface.copy(alpha = 0.06f),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                textStyle = TextStyle(fontSize = 15.sp, color = onSurface),
                singleLine = true,
                cursorBrush = SolidColor(Palette.AccentGreen),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(value.text) })
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconHit(40.dp, onDismiss) {
                Text(text = "✕", fontSize = 15.sp, color = onSurface.copy(alpha = 0.5f))
            }
            Spacer(Modifier.weight(1f))
            IconHit(40.dp, { onSave(value.text) }) {
                Text(text = "✓", fontSize = 18.sp, color = Palette.AccentGreen)
            }
        }
        // 聚焦请求跟输入框同层组合：先组合、后 attach，请求焦点才不会落空
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}

@Composable
private fun DrawerCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) { content() }
    }
}

@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            // 20dp：比 16sp 的字略高一档，三行图标跟文字视觉齐平；24 网格按 20 缩放，线宽落在 1.5dp 左右
            modifier = Modifier.size(20.dp),
            tint = if (enabled) Palette.Neutral else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.35f)
        )
        Spacer(Modifier.weight(1f))
        // 没有说明文字（例如「设置」行）时整块不画，避免留下多余间隔
        if (value != null) {
            Text(
                text = value,
                modifier = Modifier.widthIn(max = 150.dp),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.55f else 0.25f)
            )
            Spacer(Modifier.width(6.dp))
        }
        // 行尾的「›」同样换成 Chevron：折叠态朝右，和全 App 的箭头口径一致
        Chevron(
            expanded = false,
            size = ChevronSize.NORMAL,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.35f else 0.15f)
        )
    }
}
