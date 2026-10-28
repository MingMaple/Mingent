package com.zmf.agent.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.zmf.agent.agent.session.ChatSession
import com.zmf.agent.agent.session.SessionStore
import com.zmf.agent.agent.workspace.WorkSpace
import com.zmf.agent.agent.workspace.WorkSpaceStore
import com.zmf.agent.ui.common.saltCardRow
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

/**
 * 抽屉树的行高口径：单个模块的上下留白要收住，整列才不散。
 *
 * 参照物是上面「主页／终端／设置」三行：13dp 上下内距 + 一行 16sp ≈ 50dp。
 * 会话行是两行文字，所以把两行的 lineHeight 显式压住（原来继承 bodyLarge 的 24sp 行高，
 * 连 10sp 的相对时间也被撑成 24dp 空档，行高因此比导航行高出一大截）：
 * `17 + 1 + 13 + 2×10 = 51dp`，只比导航行高 1dp：小字读得开，整列又不散。
 * 工作区行用同一个内容高度 + 同一个内距，两行仍然等高。
 */
private val TREE_ROW_PAD = 10.dp
private val SESSION_INSET = 34.dp
private val SESSION_TITLE_LINE = 17.dp
private val SESSION_TIME_LINE = 13.dp
private val SESSION_TIME_GAP = 1.dp
private val TREE_CONTENT_HEIGHT = SESSION_TITLE_LINE + SESSION_TIME_GAP + SESSION_TIME_LINE

/** 展开状态表的键：null（未分类）用空串占位，工作区 id 不会是空串。 */
private const val NO_WORKSPACE_KEY = ""

/** 树里的一个分组：工作区为 null 表示未分类。 */
private data class WorkspaceGroup(val space: WorkSpace?, val sessions: List<ChatSession>)

/**
 * 侧边栏，仿 DSH 左侧工作区栏：回到主页 / 终端 / 设置三行常驻，下面是可折叠的工作区与会话树。
 *
 * 层级口径：工作区是分组标题（14sp 半粗 + 文件夹图标），
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
    // 「⋯」走伴随面板而不是弹窗（理由见 DrawerMenuPopover）：工作区与会话两个层级同一套。
    // anchor 记的是那一行「⋯」在窗口里的矩形，面板据此贴左下/左上弹出；点空白处或返回键收。
    var sessionMenu by remember { mutableStateOf<ChatSession?>(null) }
    var sessionMenuAnchor by remember { mutableStateOf(Rect.Zero) }
    var workspaceMenu by remember { mutableStateOf<WorkSpace?>(null) }
    var workspaceMenuAnchor by remember { mutableStateOf(Rect.Zero) }
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
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
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
                        onSessionMenu = { session, anchor ->
                            sessionMenuAnchor = anchor
                            sessionMenu = session
                        },
                        onCreateSession = { onCreateSession(space?.id) },
                        // 未分类不是一个工作区：没有重命名 / 删除的对象，右侧「⋯」整块不出现
                        onWorkspaceMenu = if (space != null) {
                            { anchor: Rect ->
                                workspaceMenuAnchor = anchor
                                workspaceMenu = space
                            }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    // 会话伴随面板：五项。置顶/取消置顶不在这里做：列表本身已按 pinned 排序体现，
    // 菜单里再挂一个开关只是多一层状态
    sessionMenu?.let { session ->
        DrawerMenuPopover(anchor = sessionMenuAnchor, onDismiss = { sessionMenu = null }) {
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

    // 工作区伴随面板：只有当前工作区行才挂「⋯」，所以不会出现点了没反应的分组
    workspaceMenu?.let { space ->
        DrawerMenuPopover(anchor = workspaceMenuAnchor, onDismiss = { workspaceMenu = null }) {
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
            // 界面上一律显示「新会话」，输入框里也得是同一个名字，否则改完像是丢了字
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
    val shape = RoundedCornerShape(12.dp)
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
 * 点标题行只做展开/折叠，绝不切当前工作区：切换由点会话或右侧「＋」决定，
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
    onSessionMenu: (ChatSession, Rect) -> Unit,
    onCreateSession: () -> Unit,
    onWorkspaceMenu: ((Rect) -> Unit)?
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 「⋯」自己的窗口坐标：伴随面板要贴着这个按钮弹，不能靠猜
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }

    // 行高口径见文件头的 TREE_ROW_PAD 一组常量：模块间距收住，整列才不散

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltCardRow { onToggle() }
            .padding(start = 8.dp, end = 6.dp, top = TREE_ROW_PAD, bottom = TREE_ROW_PAD),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 折叠箭头放在行首：它原来在行尾，和「⋯」「＋」挤成一排三个图标，行尾像工具栏。
        // 挪到文件夹前面之后，左半边读作「一个可展开的分组」，右边只剩动作；
        // 整行仍然可点，箭头本身不再挂热区。
        Chevron(
            expanded = expanded,
            size = ChevronSize.SMALL,
            tint = onSurface.copy(alpha = if (expanded) 0.5f else 0.38f)
        )
        Spacer(Modifier.width(6.dp))
        // 折叠时图标压淡一档：不展开的分组在视觉上往后退，展开的分组是当前在读的
        Icon(
            imageVector = FolderIcon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = onSurface.copy(alpha = if (expanded) 0.78f else 0.42f)
        )
        Spacer(Modifier.width(8.dp))
        // 标题单独居中在「标题行 + 时间行」那么高的盒子里。
        // 原来是把标题塞进 Column、下面垫一个等高空行把行撑高：整块内容居中后，
        // 标题就落在上半边，看起来像「文字整体向上偏移」。
        // 现在盒子高度与会话行的文字块一致（行高仍与会话行等高），只是把那一行文字摆到中心。
        Box(
            modifier = Modifier
                .weight(1f)
                .height(TREE_CONTENT_HEIGHT),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = name,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                color = onSurface.copy(alpha = if (isCurrentWorkspace) 1f else 0.72f)
            )
        }
        // 右侧动作：⋯ 只在当前工作区；＋ 在当前工作区或展开时出现（折叠的分组不占位）
        if (isCurrentWorkspace || expanded) {
            if (isCurrentWorkspace && onWorkspaceMenu != null) {
                IconHit(30.dp, onClick = { onWorkspaceMenu(menuAnchor) }, onBounds = { menuAnchor = it }) {
                    Text(
                        text = "⋯",
                        fontSize = 15.sp,
                        color = onSurface.copy(alpha = 0.55f)
                    )
                }
            }
            IconHit(30.dp, onCreateSession) {
                Icon(
                    imageVector = PlusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = Palette.Neutral
                )
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
                onMenu = { anchor -> onSessionMenu(session, anchor) }
            )
        }
        if (sessions.size > SESSION_PREVIEW) {
            Text(
                text = if (showAll) "收起" else "展开其余 " + (sessions.size - SESSION_PREVIEW) + " 个会话",
                modifier = Modifier
                    .fillMaxWidth()
                    .saltCardRow() { onShowAllChange(!showAll) }
                    .padding(start = 46.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
                fontSize = 12.sp,
                color = Palette.AccentGreen.copy(alpha = 0.9f)
            )
        }
    }
}

/**
 * 树里的一行会话：标题（第一行）+ 相对时间（第二行小字）+ 仅当前行显示的「⋯」。
 *
 * 时间从「右侧同一行」挪到标题下方变小字：抽屉只有 304dp 宽，标题和时间抢同一行时，
 * 稍长的标题一律被截成省略号，时间却还占着六十多 dp。分两行之后标题能多露七八个字，
 * 时间只是往后退一档，不影响扫读。
 *
 * 选中态：整行淡绿底 + 标题用满不透明正文色 + 时间不再压到 30%；未选中标题 62%。
 * 层级仍靠左内距 30dp 表达（比工作区行再缩进一个文件夹图标的宽度）。
 */
@Composable
private fun SessionTreeRow(
    session: ChatSession,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMenu: (Rect) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var anchor by remember { mutableStateOf(Rect.Zero) }
    // 左右内距对称：左边缩进 34dp，右边也留 34dp，文字不再一路顶到卡片右缘。
    // 「⋯」画在这 34dp 里、不额外占宽，所以有没有「⋯」，两行文字的右边界都是同一条线。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrent) Palette.AccentGreen.copy(alpha = 0.13f) else Color.Transparent)
            .saltCardRow { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = SESSION_INSET,
                    end = SESSION_INSET,
                    top = TREE_ROW_PAD,
                    bottom = TREE_ROW_PAD
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifBlank { "新会话" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 行高显式给定：继承下来的 24sp 会把 13sp 的标题撑进一个 24dp 的框，
                    // 两行加起来比「主页/终端/设置」那行高出一大截
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = SESSION_TITLE_LINE.value.sp
                    ),
                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    color = if (isCurrent) onSurface else onSurface.copy(alpha = 0.62f)
                )
                Text(
                    text = SessionStore.relativeTime(session.updatedAt),
                    modifier = Modifier.padding(top = SESSION_TIME_GAP),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 10.sp,
                        lineHeight = SESSION_TIME_LINE.value.sp
                    ),
                    color = if (isCurrent) onSurface.copy(alpha = 0.5f) else onSurface.copy(alpha = 0.3f)
                )
            }
        }
        // 「⋯」只在当前会话这一行出现：每行都挂一个，右侧会竖着一条点线，太吵。
        // 它和工作区行的「⋯ 只在当前工作区出现」同一条口径：当前那个才是正在动的对象。
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp)
            ) {
                IconHit(30.dp, onClick = { onMenu(anchor) }, onBounds = { anchor = it }) {
                    Text(
                        text = "⋯",
                        fontSize = 15.sp,
                        color = onSurface.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}

/** 行内小图标的热区：图形小、热区大，免得手指点不准（30dp 是抽屉里能点中的下限）。
 *  [onBounds] 回调自己在窗口里的矩形：伴随面板要贴着这个按钮弹出，不能靠猜。 */
@Composable
private fun IconHit(
    size: Dp,
    onClick: () -> Unit,
    onBounds: ((Rect) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (onBounds != null) {
                    Modifier.onGloballyPositioned { onBounds(it.boundsInWindow()) }
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * 抽屉里的伴随面板：贴着触发它的那一行弹出，不遮整屏。
 *
 * 用 Popup 而不是 Dialog / 内联 Box：
 * - Dialog 会把整屏压黑、抽屉被盖住，读不出「这个菜单属于哪一行」；
 * - 内联 Box 会被列表的 verticalScroll 裁掉（菜单常要探出可见区）；
 * - Popup 有自己的窗口，能直接按 anchor 的窗口坐标贴边，点外面自动 dismiss。
 *
 * 观感跟输入框上方那六张伴随面板同源：面板底色（LightPanel / DarkPanel）、8dp 阴影、
 * 只缩放不淡入：带 alpha 的节点 Android 不画 elevation 阴影。
 * 圆角这里取 18dp 而不是 24dp：菜单块小，24dp 会显得过于圆润。
 */
@Composable
private fun DrawerMenuPopover(
    anchor: Rect,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val gap = with(LocalDensity.current) { 6.dp.toPx() }.toInt()
    val provider = remember(anchor, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                // 右边缘与「⋯」的右边缘对齐（抽屉贴左，菜单往左长才不出画）；下面放不下就翻到上方
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val x = (anchor.right.toInt() - popupContentSize.width).coerceIn(0, maxX)
                val below = anchor.bottom.toInt() + gap
                val y = if (below + popupContentSize.height <= windowSize.height) {
                    below
                } else {
                    (anchor.top.toInt() - popupContentSize.height - gap).coerceAtLeast(gap)
                }
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        var shown by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (shown) 1f else 0.92f,
            animationSpec = Motion.spring(),
            label = "drawerMenuScale"
        )
        LaunchedEffect(Unit) { shown = true }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (isDark) Palette.DarkPanel else Palette.LightPanel,
            shadowElevation = 8.dp,
            modifier = Modifier
                // 菜单按内容宽走：曾经给到 max 236dp，单条「重命名工作区」会撑出一大片空白，
                // 看着像一张卡片而不是菜单
                .widthIn(min = 148.dp, max = 184.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    // 从右上角长出来：贴着「⋯」那一侧，视觉上就是「这一行的菜单」
                    transformOrigin = TransformOrigin(1f, 0f)
                }
        ) {
            // 行自己带上下内距，Column 不再垫一层：首/末行的波纹才能贴到 18dp 圆弧上被裁成圆角
            Column(content = content)
        }
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

/** 伴随面板里的一行：14sp、左右 14dp、上下 11dp（菜单块小，内距跟着收一档）。 */
@Composable
private fun MenuRow(text: String, color: Color? = null, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            // 面板里同样走通栏波纹：18dp 圆角由 Surface 自己裁，行自己不再各裁一次
            .saltCardRow() { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        fontSize = 14.sp,
        color = color ?: MaterialTheme.colorScheme.onSurface
    )
}

/**
 * 重命名用的悬浮输入框：预填原名、全部选中（一打字就是整段替换），
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
            shape = RoundedCornerShape(16.dp),
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
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier) { content() }
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
            // 抽屉里这几行原来用裸 clickable：默认水波纹是矩形，铺在圆角卡里显得方。
            // 统一走 saltCardRow，形状交给卡片自身的 clip 去裁
            .saltCardRow(enabled = enabled) { onClick() }
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
