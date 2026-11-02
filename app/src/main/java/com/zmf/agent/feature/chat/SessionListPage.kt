package com.zmf.agent.feature.chat
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.blockTapsOnly

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
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.zmf.agent.uikit.common.saltFieldColors

/**
 * 「会话列表」页（侧边栏一级页面）：上面两个 tab「会话」「归档」，左右滑动切换。
 *
 * 归档能力此前为设置页中的单独页面，现并入此处：归档 tab = sessions.filter { it.archived }，
 * 行菜单里的「归档」在归档 tab 里换成「移回会话」，两个方向都走同一个 [onArchive]。
 *
 * 顶部搜索框按「标题 + 任意消息正文」过滤：有关键词时切成单列结果（不分组、按 updatedAt 倒序），
 * 两个 tab 均包含在结果中，查找会话无需先判断其是否在归档中。
 *
 * 流式生成中（streaming）所有动作均不可点击、整体压暗：ViewModel 的 new/switch/rename/delete/archive
 * 在流式时均静默返回，若界面仍可点击，会出现「点击无反应」的无效按钮。
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
    // 新建会话：可选，不传入则不显示「＋ 新会话」一行
    onNew: () -> Unit = {},
    // 将「当前是否为第 1 页」上报上层：上层据此决定抽屉是否需在 pager 之前拦截向右位移
    onFirstPage: (Boolean) -> Unit = {}
) {
    // pageCount 使用 lambda：与 PackagePage 采用同一写法
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    // 存入 rememberUpdatedState：上层每次重组均传入新 lambda，不可使 effect 随之重建
    val currentOnFirstPage by rememberUpdatedState(onFirstPage)
    // 停在第 2 页时抽屉不应拦截横滑，否则无法翻页；初值亦需上报，因此挂载于 LaunchedEffect
    LaunchedEffect(pagerState.currentPage) {
        currentOnFirstPage(pagerState.currentPage == 0)
    }
    // 搜索词：空 = 正常分 tab；非空 = 单列结果（不分组、按时间倒序）
    var query by remember { mutableStateOf("") }

    // 行菜单 / 重命名 / 删除确认均保留在页面内部，ChatScreen 仅负责接收回调
    var menuTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }

    // 工作区名查表：workspaceId 为 null 时归入「未分类」
    val names = remember(workspaces) { workspaces.associate { it.id to it.name } }
    /** 工作区 id → 显示名；null（未关联工作区）归「未分类」。 */
    fun workspaceNameOf(id: String?): String = id?.let { names[it] } ?: "未分类"

    Surface(
        // 铺满且不透明；最外层消费点击，避免穿透到下方聊天界面
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
                    // 两页均保留：滑回时无需重新排序
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
 * 两个 tab + 可滑动指示条（参照 PackagePage 的写法）：
 * 指示条跟随「currentPage + currentPageOffsetFraction」，拖拽时随手指实时移动，
 * 点击 tab 时由 animateScrollToPage 驱动像素滚动，指示条同时由弹簧带动。
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
                        // 中心对齐至当前 tab，宽 24dp 因此需减去一半
                        .offset(x = tabWidth * (animated + 0.5f) - 12.dp)
                        .size(width = 24.dp, height = 3.dp)
                        .background(UiColors.accentFg, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/** 单个 tab：选中使用 Salt 绿，未选中使用 onSurface 45%。 */
@Composable
private fun SessionTab(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // tab 为矩形区域、无自身圆角，波纹按 10dp 裁剪即可贴合（与设置页的 tab 同一约定）
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
                UiColors.accentFg
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
                    color = UiColors.accentFg.copy(alpha = if (streaming) 0.35f else 1f)
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
                val now = rememberMinuteNow()
                sessions.forEach { session ->
                    SessionRow(
                        session = session,
                        workspaceName = workspaceNameOf(session.workspaceId),
                        isCurrent = session.id == currentId,
                        streaming = streaming,
                        now = now,
                        onPick = { onPick(session.id) },
                        onMenu = { onMenu(session) }
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 搜索结果上限：超出后用户无法读完，50 条之后继续翻阅没有意义。 */
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
                cursorBrush = SolidColor(UiColors.accentFg),
                // 占位符与输入文字位于同一格：空串时输入区不会收缩为一条线
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
 * 搜索期间不显示「＋ 新会话」：此时用户正在查找已有会话，而非新建。
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
    // 会话列表为普通 list，数量级为「用户手工创建」，关键词变化时线性过滤即可，无需建立索引
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
                val now = rememberMinuteNow()
                matched.forEach { session ->
                    SessionRow(
                        session = session,
                        workspaceName = workspaceNameOf(session.workspaceId),
                        isCurrent = session.id == currentId,
                        streaming = streaming,
                        now = now,
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
 * 使用 contains(ignoreCase) 而非先将全文转为小写：后者需为每条消息再创建一份副本，
 * 会话增多后将无谓占用内存。
 */
private fun sessionMatches(session: ChatSession, query: String): Boolean {
    if (query.isEmpty()) return true
    if (session.title.contains(query, ignoreCase = true)) return true
    return session.messages.any { it.content.contains(query, ignoreCase = true) }
}

/**
 * 每分钟更新一次的「现在」。
 *
 * 「刚刚 / N 分钟前」是纯函数算出来的，只有重组才会重算；列表本身没有状态变化，
 * 因此时间会停留在进入时刻——一小时前的会话持续显示「刚刚」，进入一次会话后才变为「1 小时前」。
 * 该节拍仅负责触发刷新，不参与任何业务状态。
 */
@Composable
private fun rememberMinuteNow(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * 一行会话：标题 + 一条副标题（时间 · 消息数 · 工作区名），右侧「⋯」开菜单。
 *
 * 长按整行也可打开同一菜单：手机上行尾的「⋯」较难点按，长按更为便捷。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: ChatSession,
    workspaceName: String,
    isCurrent: Boolean,
    streaming: Boolean,
    now: Long,
    onPick: () -> Unit,
    onMenu: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val meta = SessionStore.relativeTime(session.updatedAt, now) +
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
                color = onSurface
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
        // 菜单按钮单独接收点击：长按作用于整行，点击此处仅打开菜单
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
 * 行菜单：与首页的极简弹窗保持同一观感，无标题、无按钮，仅含可点击的行。
 *
 * 「归档」项在归档 tab 中改为「移回会话」，其余三项两种 tab 一致。
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

/** 重命名弹窗：一行输入 + 取消 / 保存，打开后即聚焦。 */
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
                // 聚焦与输入框置于同一层组合中：先组合、后 attach，焦点请求才不会失败
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
                Text("保存", color = UiColors.accentFg)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
