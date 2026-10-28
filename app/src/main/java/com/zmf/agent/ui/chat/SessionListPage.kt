package com.zmf.agent.ui.chat
import com.zmf.agent.ui.common.saltCardRow
import com.zmf.agent.ui.common.blockTapsOnly

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zmf.agent.agent.session.ChatSession
import com.zmf.agent.agent.session.SessionStore
import com.zmf.agent.agent.workspace.WorkSpace
import com.zmf.agent.ui.theme.Motion
import com.zmf.agent.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.zmf.agent.ui.common.saltFieldColors

/**
 * 「会话列表」页（侧边栏一级页面）：上面两个 tab「会话」「归档」，左右滑动切换。
 *
 * 归档能力原来在设置页里单独一页，现在并进这里：归档 tab = sessions.filter { it.archived }，
 * 行菜单里的「归档」在归档 tab 里换成「移回会话」，两个方向都走同一个 [onArchive]。
 *
 * 顶部搜索框按「标题 + 任意消息正文」过滤：有关键词时切成单列结果（不分组、按 updatedAt 倒序），
 * 两个 tab 都在这个结果里，找会话不用先猜它在不在归档里。
 *
 * 流式生成中（streaming）所有动作都点不动、整体压暗：ViewModel 的 new/switch/rename/delete/archive
 * 在流式时都会静默返回，界面要是还能点，就会出现「点了没反应」的假按钮。
 */
@Composable
fun SessionListPage(
    sessions: List<ChatSession>,
    workspaces: List<WorkSpace>,
    currentId: String,
    streaming: Boolean,
    onPick: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onArchive: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onFork: (String) -> Unit,
    onBack: () -> Unit,
    // 新建会话：可选，不传就不显示「＋ 新会话」那一行
    onNew: () -> Unit = {},
    // 把「当前是不是第 1 页」报给上层：上层据此决定抽屉要不要在 pager 之前抢向右的位移
    onFirstPage: (Boolean) -> Unit = {}
) {
    // pageCount 用 lambda：跟 PackagePage 是同一套写法
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    // 收进 rememberUpdatedState：上层每次重组都给新 lambda，不能让 effect 跟着重建
    val currentOnFirstPage by rememberUpdatedState(onFirstPage)
    // 停在第 2 页时抽屉不该抢横滑，否则翻页翻不动；初值也要报，所以挂在 LaunchedEffect 上
    LaunchedEffect(pagerState.currentPage) {
        currentOnFirstPage(pagerState.currentPage == 0)
    }
    // 搜索词：空 = 正常分 tab；非空 = 单列结果（不分组、按时间倒序）
    var query by remember { mutableStateOf("") }

    // 行菜单 / 重命名 / 删除确认都留在页面内部，ChatScreen 只管接回调
    var menuTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }

    // 工作区名查表：workspaceId 为 null 归「未分类」
    val names = remember(workspaces) { workspaces.associate { it.id to it.name } }
    /** 工作区 id → 显示名；null（未关联工作区）归「未分类」。 */
    fun workspaceNameOf(id: String?): String = id?.let { names[it] } ?: "未分类"

    Surface(
        // 铺满且不透明；最外层吞掉点击，避免穿透点到下面的聊天界面
        modifier = Modifier
            .fillMaxSize()
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "←",
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "会话列表",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            SessionSearchField(query = query, onQueryChange = { query = it })

            if (query.isBlank()) {
                SessionTabBar(pagerState = pagerState, scope = scope)

                HorizontalPager(
                    state = pagerState,
                    // 两页都留着：滑回来不用重排序
                    beyondViewportPageCount = 1,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    val archived = page == 1
                    SessionTabContent(
                        sessions = if (archived) sessions.filter { it.archived } else sessions.filter { !it.archived },
                        currentId = currentId,
                        streaming = streaming,
                        archived = archived,
                        workspaceNameOf = { workspaceNameOf(it) },
                        onNew = onNew,
                        onPick = onPick,
                        onMenu = { menuTarget = it }
                    )
                }
            } else {
                SessionSearchResults(
                    sessions = sessions,
                    query = query,
                    currentId = currentId,
                    streaming = streaming,
                    workspaceNameOf = { workspaceNameOf(it) },
                    onPick = onPick,
                    onMenu = { menuTarget = it }
                )
            }
        }
    }

    // 行菜单：重命名 / 分叉会话 / 归档（归档页里是「移回会话」）/ 删除
    menuTarget?.let { session ->
        SessionActionSheet(
            archived = session.archived,
            onDismiss = { menuTarget = null },
            onRename = {
                menuTarget = null
                renameTarget = session
            },
            onFork = {
                menuTarget = null
                onFork(session.id)
            },
            onToggleArchive = {
                menuTarget = null
                onArchive(session.id, !session.archived)
            },
            onDelete = {
                menuTarget = null
                deleteTarget = session
            }
        )
    }

    renameTarget?.let { session ->
        RenameSessionDialog(
            initial = session.title,
            onDismiss = { renameTarget = null },
            onSave = { title ->
                renameTarget = null
                onRename(session.id, title)
            }
        )
    }

    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话", fontSize = 17.sp) },
            text = {
                Text(
                    text = "「" + (session.title.ifBlank { "新会话" }) + "」以及里面的对话都会被删除，不能撤销。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    onDelete(session.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 两个 tab + 会滑动的指示条（照 PackagePage 的写法）：
 * 指示条追「currentPage + currentPageOffsetFraction」，手拖时跟着手指实时动，
 * 点 tab 时由 animateScrollToPage 驱动像素滚动，指示条同时被弹簧带过去。
 */
@Composable
private fun SessionTabBar(pagerState: PagerState, scope: CoroutineScope) {
    val progress = pagerState.currentPage + pagerState.currentPageOffsetFraction
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = Motion.spring(),
        label = "sessionTabIndicator"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // 两个 tab 等分：指示条中心 = tabWidth * (page + 0.5)
        val tabWidth = maxWidth / 2
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                SessionTab(
                    text = "会话",
                    selected = pagerState.currentPage == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } }
                )
                SessionTab(
                    text = "归档",
                    selected = pagerState.currentPage == 1,
                    modifier = Modifier.weight(1f),
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } }
                )
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        // 中心对齐到当前 tab，24dp 宽所以要减掉一半
                        .offset(x = tabWidth * (animated + 0.5f) - 12.dp)
                        .size(width = 24.dp, height = 3.dp)
                        .background(Palette.AccentGreen, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/** 单个 tab：选中吃 Salt 绿，未选中是 onSurface 45%。 */
@Composable
private fun SessionTab(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // tab 是矩形区域、没有自己的圆角，波纹按 10dp 裁一下就够贴合（和设置页的 tab 同口径）
    Box(
        modifier = modifier
            .height(44.dp)
            .saltCardRow(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                Palette.AccentGreen
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            }
        )
    }
}

/** 一个 tab 的内容：会话 tab 顶部多一行「＋ 新会话」，归档 tab 没有。 */
@Composable
private fun SessionTabContent(
    sessions: List<ChatSession>,
    currentId: String,
    streaming: Boolean,
    archived: Boolean,
    workspaceNameOf: (String?) -> String,
    onNew: () -> Unit,
    onPick: (String) -> Unit,
    onMenu: (ChatSession) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (!archived) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !streaming, onClick = onNew)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "＋ 新会话",
                    fontSize = 15.sp,
                    color = Palette.AccentBlue.copy(alpha = if (streaming) 0.35f else 1f)
                )
            }
            HorizontalDivider(color = onSurface.copy(alpha = 0.08f))
        }

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (archived) "还没有归档的会话" else "还没有会话",
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .alpha(if (streaming) 0.4f else 1f)
                    .verticalScroll(rememberScrollState())
            ) {
                sessions.forEach { session ->
                    SessionRow(
                        session = session,
                        workspaceName = workspaceNameOf(session.workspaceId),
                        isCurrent = session.id == currentId,
                        streaming = streaming,
                        onPick = { onPick(session.id) },
                        onMenu = { onMenu(session) }
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 搜索结果上限：再多用户也读不完，50 条之后没有继续往下翻的意义。 */
private const val SESSION_SEARCH_LIMIT = 50

/** 圆角搜索框：一行输入 + 清空 ✕（没有搜索词时不显示 ✕）。 */
@Composable
private fun SessionSearchField(query: String, onQueryChange: (String) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = onSurface.copy(alpha = 0.06f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = onSurface),
                singleLine = true,
                cursorBrush = SolidColor(Palette.AccentGreen),
                // 占位符叠在输入文字同一格里：空串时输入区不会塌成一条线
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text("搜索会话", fontSize = 14.sp, color = onSurface.copy(alpha = 0.4f))
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                Text(
                    text = "✕",
                    modifier = Modifier
                        .clickable { onQueryChange("") }
                        .padding(start = 10.dp),
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
            }
        }
    }
}

/**
 * 搜索结果：标题或任意一条消息正文命中即算，不分组、按 updatedAt 倒序，
 * 最多 [SESSION_SEARCH_LIMIT] 条。
 *
 * 搜索期间不显示「＋ 新会话」：这时用户是在找已有会话，不是要新建。
 */
@Composable
private fun SessionSearchResults(
    sessions: List<ChatSession>,
    query: String,
    currentId: String,
    streaming: Boolean,
    workspaceNameOf: (String?) -> String,
    onPick: (String) -> Unit,
    onMenu: (ChatSession) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 会话列表是普通的 list，量级是「人手工点出来的」，关键词一变就线性过滤一遍，不值得建索引
    val matched = remember(sessions, query) {
        sessions
            .filter { sessionMatches(it, query.trim()) }
            .sortedByDescending { it.updatedAt }
            .take(SESSION_SEARCH_LIMIT)
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (matched.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "没有匹配的会话",
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .alpha(if (streaming) 0.4f else 1f)
                    .verticalScroll(rememberScrollState())
            ) {
                matched.forEach { session ->
                    SessionRow(
                        session = session,
                        workspaceName = workspaceNameOf(session.workspaceId),
                        isCurrent = session.id == currentId,
                        streaming = streaming,
                        onPick = { onPick(session.id) },
                        onMenu = { onMenu(session) }
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 命中口径：标题或任意一条消息的正文里出现这个子串（忽略大小写）。
 *
 * 用 contains(ignoreCase) 而不是先把全文转小写：后者要给每条消息再造一份副本，
 * 会话多了就是白占内存。
 */
private fun sessionMatches(session: ChatSession, query: String): Boolean {
    if (query.isEmpty()) return true
    if (session.title.contains(query, ignoreCase = true)) return true
    return session.messages.any { it.content.contains(query, ignoreCase = true) }
}

/**
 * 一行会话：标题 + 一条副标题（时间 · 消息数 · 工作区名），右侧「⋯」开菜单。
 *
 * 长按整行也能开同一个菜单：手机上一行右边那个「⋯」不太好点，长按更顺手。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: ChatSession,
    workspaceName: String,
    isCurrent: Boolean,
    streaming: Boolean,
    onPick: () -> Unit,
    onMenu: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val meta = SessionStore.relativeTime(session.updatedAt) +
        " · " + session.messages.size + " 条消息" +
        " · " + workspaceName
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = !streaming,
                onClick = onPick,
                onLongClick = onMenu
            )
            .padding(start = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title.ifBlank { "新会话" },
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) Palette.AccentBlue else onSurface
            )
            Text(
                text = meta,
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.45f)
            )
        }
        // 菜单按钮单独接点击：长按走整行，点这里只开菜单
        Text(
            text = "⋯",
            modifier = Modifier
                .clickable(enabled = !streaming, onClick = onMenu)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 16.sp,
            color = onSurface.copy(alpha = if (streaming) 0.2f else 0.45f)
        )
    }
}

/**
 * 行菜单：跟首页的极简弹窗同一套观感，没有标题、没有按钮，只有可点的行。
 *
 * 「归档」这一项在归档 tab 里换成「移回会话」，其余三项两种 tab 一样。
 */
@Composable
private fun SessionActionSheet(
    archived: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                SheetRow(text = "重命名", onClick = onRename)
                SheetRow(text = "分叉会话", onClick = onFork)
                SheetRow(
                    text = if (archived) "移回会话" else "归档",
                    onClick = onToggleArchive
                )
                SheetRow(
                    text = "删除",
                    color = MaterialTheme.colorScheme.error,
                    onClick = onDelete
                )
            }
        }
    }
}

/** 菜单里的一行：整行可点，[color] 传 null 用正文色（删除项传错误色）。 */
@Composable
private fun SheetRow(
    text: String,
    color: Color? = null,
    onClick: () -> Unit
) {
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

/** 重命名弹窗：一行输入 + 取消 / 保存，打开就聚焦。 */
@Composable
private fun RenameSessionDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initial) { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话", fontSize = 17.sp) },
        text = {
            Column {
                // 聚焦跟输入框放在同一层组合里：先组合、后 attach，请求焦点才不会落空
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                OutlinedTextField(
                colors = saltFieldColors(),
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    placeholder = { Text("会话名称", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(value) })
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) {
                Text("保存", color = Palette.AccentGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
