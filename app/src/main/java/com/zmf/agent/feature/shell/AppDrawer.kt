package com.zmf.agent.feature.shell

import android.content.res.Configuration
import com.zmf.agent.uikit.common.AnimatedPresenceColumn
import com.zmf.agent.uikit.common.SaltMenuRow
import com.zmf.agent.uikit.common.SaltMenuPopover
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.stableStatusBarPadding
import com.zmf.agent.uikit.icons.DshIcons
import com.zmf.agent.uikit.icons.FolderIcon
import com.zmf.agent.uikit.icons.PlusIcon
import com.zmf.agent.uikit.common.Chevron
import com.zmf.agent.uikit.common.ChevronSize
import com.zmf.agent.uikit.layout.LocalUiWindow
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors
import com.zmf.agent.core.prefs.ArchivedFilter
import com.zmf.agent.core.prefs.DrawerPrefs
import com.zmf.agent.core.prefs.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 工作区展开后先只显示这么多条会话。 */
private const val SESSION_PREVIEW = 5

/** 搜索结果上限，与 DSH 的 resultLimit 同义；超出时提示缩小范围。 */
private const val SEARCH_RESULT_LIMIT = 50

/** 正文命中片段截取的字数。 */
private const val SEARCH_SNIPPET_CHARS = 60

/**
 * 非当前会话的「⋯」在抬手之后继续显示的时间。
 *
 * 只消除「抬手即消失」造成的闪烁：按住期间不倒计时，抬手后这 400 毫秒足以让手指
 * 继续移向按钮并按下；再长会与下一行的显示互相打断。
 */
private const val SESSION_MENU_LINGER_MS = 400L

/** 停留结束后「⋯」淡出的时长：只做视觉收尾，不再额外延长可点击时间。 */
private const val SESSION_MENU_FADE_MS = 200

/**
 * 抽屉树的行高规范：控制单个模块的上下留白，使整列间距均匀。
 *
 * 参照物是上面「主页／终端／设置」三行：13dp 上下内距 + 一行 16sp ≈ 50dp。
 * 会话行为两行文字，因此显式指定两行的 lineHeight（若继承 bodyLarge 的 24sp 行高，
 * 10sp 的相对时间也会占据 24dp，行高将明显大于导航行）：
 * `17 + 1 + 13 + 2×10 = 51dp`，仅比导航行高 1dp：小字号可读，整列间距均匀。
 * 工作区行使用相同的内容高度与内距，两行等高。
 */
private val TREE_ROW_PAD = 10.dp
private val SESSION_INSET = 34.dp
private val SESSION_TITLE_LINE = 17.dp
private val SESSION_TIME_LINE = 13.dp
private val SESSION_TIME_GAP = 1.dp
private val TREE_CONTENT_HEIGHT = SESSION_TITLE_LINE + SESSION_TIME_GAP + SESSION_TIME_LINE

/**
 * 树内单行会话的固定总高：14sp 基准下为 51dp（上下各 10dp 内距 + 31dp 两行文字）。
 *
 * 行高固定后，两行文字内容高固定 31dp（17 + 1 + 13），整行 51dp，16sp 时也不会被裁切。
 */
private val TREE_ROW_HEIGHT = TREE_ROW_PAD * 2 + TREE_CONTENT_HEIGHT

/**
 * 会话行两行文字的行高：按 dp 固定。
 *
 * `Dp.toSp()` 会按当前 density 与 fontScale 折算，因此渲染出来的高度恒为给定 dp，
 * 基准字号调大时行高不跟着长，抽屉的行距保持稳定。
 */
@Composable
private fun fixedLineHeight(dp: Dp): TextUnit = with(LocalDensity.current) { dp.toSp() }

/** 展开状态表的键：null（未分类）用空串占位，工作区 id 不会是空串。 */
private const val NO_WORKSPACE_KEY = ""

/**
 * 冷启动把「当前会话」带进视野的一次性标记。
 *
 * 抽屉面板在进程存活期间常驻组合，不会因收起而销毁，因此用一个对象字段记录是否已调整过：
 * 进程重启后自动复位，正好对应「每次启动只自动调整一次」。
 * 本次运行内用户再怎么折叠、怎么滑走都不再干预。
 */
private object DrawerBoot {
    var revealed = false
}

/** 树里的一个分组：工作区为 null 表示未分类。 */
private data class WorkspaceGroup(val space: WorkSpace?, val sessions: List<ChatSession>)

/** 一条搜索结果：会话、所属工作区名，以及正文命中时的片段。 */
private data class SessionSearchHit(
    val session: ChatSession,
    val workspaceName: String,
    val snippet: String?
)

/** 搜索结果与是否还有被截断的条目。 */
private data class SearchOutcome(val hits: List<SessionSearchHit>, val hasMore: Boolean)

/** 归档三档的文案，顺序与 DSH 的筛选面板一致。 */
private val ARCHIVED_FILTER_LABELS = listOf(
    ArchivedFilter.HIDE to "隐藏已归档",
    ArchivedFilter.SHOW to "全部对话（显示已归档）",
    ArchivedFilter.ONLY to "仅显示已归档"
)

/**
 * 判定一条会话在当前归档档位下是否可见，规则与 DSH 的 sessionVisible 一致。
 *
 * 先排除未列出的空白会话（当前会话例外，用户新建后需要在树里看到它），
 * 再按档位决定归档会话是否出现。
 */
private fun sessionVisible(
    session: ChatSession,
    filter: ArchivedFilter,
    currentSessionId: String
): Boolean {
    if (!session.listed && session.id != currentSessionId) return false
    return when (filter) {
        ArchivedFilter.HIDE -> !session.archived
        ArchivedFilter.SHOW -> true
        ArchivedFilter.ONLY -> session.archived
    }
}

/**
 * 搜索：标题、工作区名或消息正文命中即可，规则与 DSH 的 deriveSearchResults 同构。
 *
 * 先 `trim` 再忽略大小写；空串返回空结果；本地命中（标题／工作区名）按最近活动倒序在前，
 * 正文命中随后，去重后按上限截断。空白会话不参与搜索。
 */
private fun searchSessions(
    sessions: List<ChatSession>,
    workspaces: List<WorkSpace>,
    query: String,
    filter: ArchivedFilter,
    currentSessionId: String,
    contentIndex: Map<String, String>
): SearchOutcome {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return SearchOutcome(emptyList(), false)
    val workspaceNames = workspaces.associate { it.id to it.name }
    fun labelOf(session: ChatSession): String =
        session.workspaceId?.let { workspaceNames[it] } ?: "未分类"

    fun searchable(session: ChatSession): Boolean =
        !session.blank && session.listed &&
            sessionVisible(session, filter, currentSessionId)

    val local = sessions
        .filter { session ->
            searchable(session) &&
                (session.title.lowercase().contains(q) || labelOf(session).lowercase().contains(q))
        }
        .sortedByDescending { it.updatedAt }
    val localIds = local.mapTo(HashSet()) { it.id }
    val content = sessions
        .filter { session ->
            searchable(session) && session.id !in localIds &&
                contentIndex[session.id].orEmpty().contains(q)
        }
        .sortedByDescending { it.updatedAt }
    val merged = local + content
    return SearchOutcome(
        hits = merged.take(SEARCH_RESULT_LIMIT).map { session ->
            SessionSearchHit(
                session = session,
                workspaceName = labelOf(session),
                snippet = if (session.id in localIds) null else snippetOf(session, q)
            )
        },
        hasMore = merged.size > SEARCH_RESULT_LIMIT
    )
}

/** 取第一条命中消息的片段：以命中位置为中心截取，去掉换行以免撑高结果行。 */
private fun snippetOf(session: ChatSession, lowercaseQuery: String): String? {
    for (message in session.messages) {
        val index = message.content.lowercase().indexOf(lowercaseQuery)
        if (index < 0) continue
        val start = (index - SEARCH_SNIPPET_CHARS / 3).coerceAtLeast(0)
        val end = (start + SEARCH_SNIPPET_CHARS).coerceAtMost(message.content.length)
        return message.content.substring(start, end).replace('\n', ' ').trim()
    }
    return null
}

/**
 * 侧边栏，仿 DSH 左侧工作区栏：回到主页 / 终端 / 设置三行常驻，下面是可折叠的工作区与会话树。
 *
 * 层级口径：工作区是分组标题（14sp 半粗 + 文件夹图标），
 * 会话为其子项（13sp 常规、整体再缩进 14dp、字色更淡），深浅色下均可清晰辨识从属关系。
 */
@Composable
fun AppDrawerContent(
    workspaces: List<WorkSpace>,
    sessions: List<ChatSession>,
    currentWorkspaceId: String?,
    currentSessionId: String,
    /** 抽屉当前是否展开：收起时复位内联搜索，避免下次打开仍停留在上一次的查询。 */
    drawerOpen: Boolean,
    themeMode: ThemeMode,
    onThemePick: (ThemeMode) -> Unit,
    onFloatingWindow: () -> Unit,
    onHome: () -> Unit,
    onTerminal: () -> Unit,
    onSettings: () -> Unit,
    onPickSession: (String) -> Unit,
    onCreateSession: (String?) -> Unit,
    onCreateWorkspace: () -> Unit,
    onRenameWorkspace: (String, String) -> Unit,
    onDeleteWorkspace: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onForkSession: (String) -> Unit,
    onArchiveSession: (String) -> Unit,
    /** 点击已归档会话：与 DSH 一致，先提示取消归档，不直接打开。 */
    onArchivedSessionOpen: () -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onCopySessionId: (String) -> Unit,
    /** 会话「⋯」里的「更改工作区」：弹窗由装配层统一提供，抽屉只负责发起。 */
    onChangeWorkspace: (ChatSession) -> Unit,
    /** 工作区菜单里的「更改储存路径」：目录选择与可写校验由装配层完成。 */
    onChangeStorage: (WorkSpace) -> Unit,
    /** 删除工作区的确认文案按「有会话 / 目录空 / 目录非空」三选一，由控制器给出。 */
    onDeleteWorkspacePrompt: (String) -> String
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val context = LocalContext.current

    // 搜索与筛选都是抽屉内状态：搜索收起或抽屉关闭即复原，不落盘；筛选档位跨进程保存
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var archivedFilter by remember { mutableStateOf(DrawerPrefs.loadArchivedFilter(context)) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var filterAnchor by remember { mutableStateOf(Rect.Zero) }
    val searchFocus = remember { FocusRequester() }
    val searchActive = searchOpen && query.isNotBlank()
    // 正文按会话预先小写化并拼接一次，逐字输入时只做 contains，避免每敲一个字都遍历全部消息并重建字符串
    // 全部会话的正文可能很长，拼接与转换放到后台线程，避免消息更新时卡住抽屉的绘制
    var contentIndex by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 用会话数与消息数做键：快照列表的 equals 对自身恒为真，直接作为键不会在内容变化时重启
    val sessionsKey = sessions.size to sessions.sumOf { it.messages.size }
    LaunchedEffect(sessionsKey) {
        contentIndex = withContext(Dispatchers.IO) {
            sessions.associate { session ->
                session.id to session.messages.joinToString("\n") { it.content }.lowercase()
            }
        }
    }
    val searchOutcome = remember(sessions, workspaces, query, archivedFilter, currentSessionId, contentIndex) {
        if (searchActive) {
            searchSessions(sessions, workspaces, query, archivedFilter, currentSessionId, contentIndex)
        } else {
            SearchOutcome(emptyList(), false)
        }
    }

    // 抽屉收起时复位搜索：下次打开回到分组树，不保留上一次的查询
    LaunchedEffect(drawerOpen) {
        if (!drawerOpen) {
            searchOpen = false
            query = ""
            filterMenuOpen = false
        }
    }
    // 分组只在数据变化时重算：置顶的排前面，其余按 updatedAt 倒序。
    // 「未分类」是派生分组，只在其下有可见会话时成组：空桶不占一行（与 DSH 的未分组一致）；
    // 工作区是实体，空工作区仍保留标题行，作为继续新建会话的锚点。
    val known = remember(workspaces) { workspaces.map { it.id }.toSet() }
    // 当前会话也参与分组：尚未转正的空白会话只因「它是当前会话」才可见，切换后必须随之消失；
    // 归档会话按三档筛选显示，「仅显示已归档」时空分组不出现（与 DSH 一致）
    val groups = remember(workspaces, sessions, currentSessionId, archivedFilter) {
        val order = compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt }
        val buckets = sessions
            .filter { sessionVisible(it, archivedFilter, currentSessionId) }
            .groupBy { session -> session.workspaceId?.takeIf { it in known } }
        val list = ArrayList<WorkspaceGroup>(workspaces.size + 1)
        for (space in workspaces) {
            list.add(WorkspaceGroup(space, buckets[space.id].orEmpty().sortedWith(order)))
        }
        val stray = buckets[null].orEmpty().sortedWith(order)
        if (stray.isNotEmpty()) list.add(WorkspaceGroup(null, stray))
        if (archivedFilter == ArchivedFilter.ONLY) list.filter { it.sessions.isNotEmpty() } else list
    }

    // 展开状态按分组记录，并跨进程保存：用户上次点开或收起的分组，重启后保持原样；
    // 从未操作过的分组不写盘，沿用默认值「仅承载当前会话的分组展开」
    val expanded = remember {
        mutableStateMapOf<String, Boolean>().apply { putAll(DrawerPrefs.load(context)) }
    }
    val showAll = remember { mutableStateMapOf<String, Boolean>() }
    var themeExpanded by remember { mutableStateOf(false) }
    // 「⋯」使用伴随面板而非弹窗（理由见 DrawerMenuPopover）：工作区与会话两个层级一致。
    // anchor 记录该行「⋯」在窗口中的矩形，面板据此贴左下/左上弹出；点击空白处或按返回键收起。
    var sessionMenu by remember { mutableStateOf<ChatSession?>(null) }
    var sessionMenuAnchor by remember { mutableStateOf(Rect.Zero) }
    var workspaceMenu by remember { mutableStateOf<WorkSpace?>(null) }
    var workspaceMenuAnchor by remember { mutableStateOf(Rect.Zero) }
    // 「⋯」的临时显示区：同一时刻只保留一个会话（全局唯一，触发另一行即接管）。
    // 手指按住期间不倒计时；抬手后停留 SESSION_MENU_LINGER_MS 再收起。
    var revealedSessionId by remember { mutableStateOf<String?>(null) }
    var heldSessionId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revealedSessionId, heldSessionId) {
        val id = revealedSessionId ?: return@LaunchedEffect
        // 仍按在这一行上：不计时，抬手后本效果会因 heldSessionId 变化重新进入
        if (heldSessionId == id) return@LaunchedEffect
        delay(SESSION_MENU_LINGER_MS)
        if (revealedSessionId == id) revealedSessionId = null
    }
    // 冷启动只调整一次：展开当前会话所在分组、必要时把它提到可见的第 5 条，并滚动到它
    var bootPending by remember { mutableStateOf(!DrawerBoot.revealed) }
    // 被提到第 5 位的那条会话。回位完成后仍保留：一旦撤掉，该行会跳回原位置、移出视野。
    // 用户切到别的会话后随之失效，列表回到自然顺序
    var promotedId by remember { mutableStateOf(if (DrawerBoot.revealed) null else currentSessionId) }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    var revealBounds by remember { mutableStateOf<Rect?>(null) }
    // 搜索标题行的窗口矩形：横屏矮窗口整条抽屉可滚动，展开搜索时据此把它滚到顶部
    var searchRowBounds by remember { mutableStateOf(Rect.Zero) }
    val treeScroll = rememberScrollState()
    // 矮窗口（横屏手机常见 360~430dp 高）：固定头部与工作区树合并成一整条可滚动内容。
    // 若仍沿用「头部固定 + 树内滚动」，树只剩几十 dp，工作区与会话几乎不可见。
    val window = LocalUiWindow.current
    val compactHeight = window.compactChrome
    val drawerScroll = rememberScrollState()
    val scroll = if (compactHeight) drawerScroll else treeScroll
    val density = LocalDensity.current
    // 竖屏收窄顶部内距：状态栏高度已由 stableStatusBarPadding 按设备给出；横屏维持原内距
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    // 展开搜索即聚焦；矮窗口整条抽屉可滚动，输入框在键盘弹起后会被压到键盘下方，
    // 因此聚焦后再把标题行滚到抽屉顶部。输入框在本帧内刚进入组合，等两帧让布局完成后再请求焦点
    LaunchedEffect(searchOpen) {
        if (!searchOpen) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        searchFocus.requestFocus()
        if (compactHeight) {
            withFrameNanos { }
            withFrameNanos { }
            if (searchRowBounds.height > 0f && listBounds.height > 0f) {
                val margin = with(density) { 12.dp.toPx() }
                val target = (searchRowBounds.top - listBounds.top + drawerScroll.value - margin).toInt()
                drawerScroll.scrollTo(target.coerceAtLeast(0))
            }
        }
    }
    // 搜索态下返回键先收起搜索，不直接关闭抽屉
    BackHandler(enabled = searchOpen) {
        searchOpen = false
        query = ""
    }

    val currentGroupKey = remember(sessions, currentSessionId, workspaces) {
        val session = sessions.firstOrNull { it.id == currentSessionId }
        session?.workspaceId?.takeIf { id -> workspaces.any { it.id == id } } ?: NO_WORKSPACE_KEY
    }
    LaunchedEffect(bootPending, currentSessionId, currentGroupKey, sessions) {
        // 搜索态下树已被扁平结果替换：此时回位会对着一条被过滤掉的会话滚动，跳过
        if (!bootPending || searchActive || currentSessionId.isEmpty()) return@LaunchedEffect
        // 会话表尚未就绪时不要据此判断分组，等下一次数据到达
        if (sessions.none { it.id == currentSessionId }) return@LaunchedEffect
        // 首次组合时当前会话可能尚未读回，这里补上提位的目标
        if (promotedId != currentSessionId) promotedId = currentSessionId
        // 即便用户上次是手动折叠的，也要把当前会话所在分组打开：它必须回到视野
        if (expanded[currentGroupKey] != true) expanded[currentGroupKey] = true
    }
    LaunchedEffect(bootPending, revealBounds, listBounds) {
        if (!bootPending || searchActive) return@LaunchedEffect
        val rect = revealBounds ?: return@LaunchedEffect
        // 布局尚未就绪（尺寸为 0）时先不动：等下一次 onGloballyPositioned 给出真实矩形，
        // 此时若把 bootPending 置为 false，本次启动就再也不会回位
        if (listBounds.height <= 0f || rect.height <= 0f) return@LaunchedEffect
        val margin = with(density) { 16.dp.toPx() }
        val visible = rect.top >= listBounds.top - 1f && rect.bottom <= listBounds.bottom + 1f
        if (!visible) {
            // 内容坐标 = 当前滚动量 + 相对列表上沿的距离；再上移一档留出上下文
            val target = (rect.top - listBounds.top + scroll.value - margin).toInt()
            scroll.scrollTo(target.coerceAtLeast(0))
        }
        DrawerBoot.revealed = true
        bootPending = false
    }
    var renamingSession by remember { mutableStateOf<ChatSession?>(null) }
    var renamingWorkspace by remember { mutableStateOf<WorkSpace?>(null) }
    var deletingWorkspace by remember { mutableStateOf<WorkSpace?>(null) }

    Column(
        modifier = Modifier
            // 宽度由外壳统一决定（覆盖式按窗口比例、常驻式 280dp）；
            // 此处若再写 widthIn(max = 320.dp)，320dp 窗口会出现「面板 260dp、内容仍按 320dp 排版」的二次错位
            .fillMaxWidth()
            .fillMaxHeight()
            .stableStatusBarPadding()
            .navigationBarsPadding()
            // 输入法弹起时把抽屉底部让给键盘：横屏矮窗口下抽屉是整屏浮层，
            // 不让位会把搜索框压在键盘下方（实测 915×411 会被完全盖住）
            .imePadding()
            // 抽屉体水平内距收到 10dp（仅抽屉体，行内图标与操作区间距不变）：
            // 再低于 10dp 会使会话标题与右侧时间贴边
            // 顶部竖屏只保留 4dp、横屏维持 12dp：状态栏高度已由 stableStatusBarPadding 按设备给出，
            // 竖屏再叠 12dp 会让抽屉头部离状态栏过远
            .padding(
                start = 10.dp,
                end = 10.dp,
                top = if (portrait) 4.dp else 12.dp,
                bottom = 12.dp
            )
            // 矮窗口：整条抽屉（头部 + 入口卡 + 工作区树）一起滚动，树按内容自然展开；
            // 此时可视区边框由本列给出，冷启动回位以它为基准
            .then(
                if (compactHeight) {
                    Modifier
                        .onGloballyPositioned { listBounds = it.windowRect() }
                        .verticalScroll(drawerScroll)
                } else {
                    Modifier
                }
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 头部（小窗入口 + 主题按钮）与主题面板合成一个 Column：
        // 主题面板收起时，外层 spacedBy 不应因高度为 0 的子项额外留出间距
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左上角位置：纯图形的小窗入口（不含文字，以形状表达语义）
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
                        val green = Palette.Neutral
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
                // 主题入口：不跳转页面，就地展开三行（展开态以绿字标记）
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

            // 主题三选项：从按钮下方整体展开，收起时高度以弹簧回收，避免出现瞬间切换
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
            // 三行图标统一使用 24 网格线稿（DshIcons），不使用「⌂ ❯ ⚙」这类字符：字重与基线
            // 全部取决于字体，更换设备后可能出现大小不一、与文字未对齐
            DrawerRow(icon = DshIcons.Home, label = "主页", onClick = onHome)
            DrawerRow(icon = DshIcons.Terminal, label = "终端", value = "Ubuntu 24 · PRoot", onClick = onTerminal)
            DrawerRow(icon = DshIcons.Gear, label = "设置", onClick = onSettings)
        }

        // 标题行同时承载搜索与新建工作区：搜索就地展开为输入框，不切换页面、不收起抽屉
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { searchRowBounds = it.windowRect() }
                .padding(start = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (searchOpen) {
                // 展开态：左侧图标 + 输入框 + 清除按钮；筛选与新建随输入框展开而淡出
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(0.5.dp, onSurface.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                        .padding(start = 2.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconHit(28.dp, { searchFocus.requestFocus() }) {
                        SearchGlyph(11.dp, onSurface.copy(alpha = 0.55f))
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFocus),
                        textStyle = TextStyle(fontSize = 13.sp, color = onSurface),
                        singleLine = true,
                        cursorBrush = SolidColor(UiColors.accentFg),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {}),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        text = "搜索会话名称",
                                        fontSize = 13.sp,
                                        color = onSurface.copy(alpha = 0.35f)
                                    )
                                }
                                inner()
                            }
                        }
                    )
                    // 叉号在展开态常驻：没有输入时也作为关闭入口（与 DSH 一致）
                    IconHit(28.dp, { query = ""; searchOpen = false }) {
                        Text(text = "✕", fontSize = 13.sp, color = onSurface.copy(alpha = 0.5f))
                    }
                }
            } else {
                Text(
                    text = "工作区",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
                IconHit(32.dp, {
                    filterMenuOpen = false
                    searchOpen = true
                }) {
                    SearchGlyph(14.dp, onSurface.copy(alpha = 0.55f))
                }
                // 筛选按钮位于搜索与「＋」之间：伴随面板只保留 DSH 的三项归档档位
                IconHit(32.dp, { filterMenuOpen = true }, onBounds = { filterAnchor = it }) {
                    Icon(
                        imageVector = DshIcons.Sliders,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = onSurface.copy(alpha = 0.55f)
                    )
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
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            // 矮窗口跟随整条抽屉滚动：树不再吃剩余高度，按内容自然展开（无 weight）；
            // 高度充足时仍占满剩余空间并在树内部滚动
            modifier = Modifier
                .fillMaxWidth()
                .then(if (compactHeight) Modifier else Modifier.weight(1f))
        ) {
            Column(
                modifier = if (compactHeight) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                        .fillMaxHeight()
                        .onGloballyPositioned { listBounds = it.windowRect() }
                        .verticalScroll(treeScroll)
                }
            ) {
                if (searchActive) {
                    // 搜索态整棵树替换为扁平结果，不显示分组标题；空输入即恢复分组树
                    if (searchOutcome.hits.isEmpty()) {
                        Text(
                            text = "无匹配会话",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
                            fontSize = 13.sp,
                            color = onSurface.copy(alpha = 0.45f)
                        )
                    } else {
                        searchOutcome.hits.forEach { hit ->
                            val archived = hit.session.archived
                            SearchResultRow(
                                hit = hit,
                                isCurrent = hit.session.id == currentSessionId,
                                onClick = {
                                    if (archived) onArchivedSessionOpen() else onPickSession(hit.session.id)
                                }
                            )
                        }
                        if (searchOutcome.hasMore) {
                            Text(
                                text = "仅显示前 " + SEARCH_RESULT_LIMIT + " 条结果，请缩小搜索范围。",
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                fontSize = 12.sp,
                                color = onSurface.copy(alpha = 0.35f)
                            )
                        }
                    }
                } else if (groups.isEmpty()) {
                    // 「仅显示已归档」且没有任何归档会话：点名当前筛选并给出返回入口（同 DSH）
                    ArchivedOnlyEmpty(onShowAll = {
                        archivedFilter = ArchivedFilter.HIDE
                        DrawerPrefs.saveArchivedFilter(context, ArchivedFilter.HIDE)
                    })
                } else {
                    // 工作区增删走展开/收起动画：新建后整段平滑撑开，删除工作区时整段收回，
                    // 而不是让下面的分组瞬间跳到新位置
                    AnimatedPresenceColumn(
                        items = groups,
                        keyOf = { it.space?.id ?: NO_WORKSPACE_KEY }
                    ) { group ->
                        val space = group.space
                        val key = space?.id ?: NO_WORKSPACE_KEY
                        // 未手动操作时使用默认值：仅当前工作区展开
                        val open = expanded[key] ?: (space?.id == currentWorkspaceId)
                        WorkspaceSection(
                            // 冷启动待回位时才把当前会话提到可见位置；本次运行内不再干预
                            revealSessionId = promotedId?.takeIf { it == currentSessionId },
                            onRevealBounds = { rect -> revealBounds = rect },
                            onToggle = {
                                expanded[key] = !open
                                DrawerPrefs.save(context, key, !open)
                            },
                            name = space?.name ?: "未分类",
                            sessions = group.sessions,
                            isCurrentWorkspace = space?.id == currentWorkspaceId,
                            expanded = open,
                            showAll = showAll[key] == true,
                            currentSessionId = currentSessionId,
                            menuSessionId = sessionMenu?.id,
                            revealedSessionId = revealedSessionId,
                            onSessionHold = { id, pressed ->
                                if (pressed) {
                                    // 接管显示：另一行即使还在停留时间内也立即结束
                                    if (revealedSessionId != id) revealedSessionId = id
                                    heldSessionId = id
                                } else if (heldSessionId == id) {
                                    heldSessionId = null
                                }
                            },
                            onShowAllChange = { showAll[key] = it },
                            onPickSession = onPickSession,
                            onArchivedSessionOpen = onArchivedSessionOpen,
                            onSessionMenu = { session, anchor ->
                                sessionMenuAnchor = anchor
                                sessionMenu = session
                            },
                            onCreateSession = { onCreateSession(space?.id) },
                            // 「未分类」不是工作区；默认工作区不允许重命名、也不允许改路径与删除。
                            // 两者都没有可执行的动作，因此右侧只给「＋」，不给「⋯」
                            onWorkspaceMenu = if (space != null && !space.isDefault) {
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
    }

    // 筛选伴随面板：只保留 DSH「筛选会话」下的三项，不显示该分组标题；选中行尾打勾
    if (filterMenuOpen) {
        DrawerMenuPopover(anchor = filterAnchor, onDismiss = { filterMenuOpen = false }) {
            ARCHIVED_FILTER_LABELS.forEach { (filter, label) ->
                SaltMenuRow(
                    text = label,
                    trailing = {
                        if (filter == archivedFilter) {
                            Icon(
                                imageVector = DshIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Palette.Neutral
                            )
                        }
                    }
                ) {
                    archivedFilter = filter
                    DrawerPrefs.saveArchivedFilter(context, filter)
                    filterMenuOpen = false
                }
            }
        }
    }

    // 会话伴随面板：五项。置顶/取消置顶不在此处提供：列表已按 pinned 排序体现，
    // 菜单中再设开关只会增加一层状态
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
            MenuRow(text = "更改工作区") {
                sessionMenu = null
                onChangeWorkspace(session)
            }
            MenuRow(text = "复制会话 ID") {
                sessionMenu = null
                onCopySessionId(session.id)
            }
        }
    }

    // 工作区伴随面板：仅当前工作区行显示「⋯」，因此不会出现点击无响应的分组
    workspaceMenu?.let { space ->
        DrawerMenuPopover(anchor = workspaceMenuAnchor, onDismiss = { workspaceMenu = null }) {
            MenuRow(text = "重命名工作区") {
                workspaceMenu = null
                renamingWorkspace = space
            }
            // 默认工作区的目录固定，既不能改也不能删（WorkSpaceStore 明确拒绝），菜单中不提供这两项
            if (space.id != WorkSpaceStore.DEFAULT_ID) {
                MenuRow(text = "更改储存路径") {
                    workspaceMenu = null
                    onChangeStorage(space)
                }
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
            // 界面统一显示「新会话」，输入框中须使用同一名称，否则修改后会出现文字丢失的观感
            initial = session.title.ifBlank { "新会话" },
            onDismiss = { renamingSession = null },
            onSave = { name ->
                renamingSession = null
                onRenameSession(session.id, name)
            }
        )
    }

    deletingWorkspace?.let { space ->
        AlertDialog(
            onDismissRequest = { deletingWorkspace = null },
            title = { Text("删除工作区？", fontSize = 17.sp) },
            text = {
                Text(
                    text = onDeleteWorkspacePrompt(space.id),
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
            // 选中使用完全不透明正文色，未选中降至 45%：强调色与正文色接近，
            // 用于区分「选中／未选中」时对比不足，因此以不透明度区分层级
            color = if (selected) onSurface else onSurface.copy(alpha = 0.45f)
        )
    }
}

/**
 * 一个工作区：标题行（文件夹图标 + 名称 + 折叠箭头 + 右侧图标区）加它展开后的会话。
 *
 * 点击标题行仅执行展开/折叠，不切换当前工作区：切换由点击会话或右侧「＋」触发，
 * 否则查看其它工作区内容会连带改变当前上下文。
 */
@Composable
private fun WorkspaceSection(
    name: String,
    sessions: List<ChatSession>,
    isCurrentWorkspace: Boolean,
    expanded: Boolean,
    showAll: Boolean,
    /** 冷启动待回位的会话：若它排在前 5 之外，用第 5 个位置显示它。 */
    revealSessionId: String?,
    /** 目标会话行的窗口矩形：由上层据此滚动到它。 */
    onRevealBounds: (Rect) -> Unit,
    currentSessionId: String,
    /** 当前打开着伴随面板的会话 id：该行的「⋯」需保持可见，否则面板还开着按钮就消失了。 */
    menuSessionId: String?,
    /** 当前通过接触临时显示「⋯」的会话 id（全局唯一）。 */
    revealedSessionId: String?,
    /** 手指在该行上按下或抬起：抬起后由上层计时收起。 */
    onSessionHold: (String, Boolean) -> Unit,
    onToggle: () -> Unit,
    onShowAllChange: (Boolean) -> Unit,
    onPickSession: (String) -> Unit,
    /** 点击已归档会话：只提示取消归档，不直接打开。 */
    onArchivedSessionOpen: () -> Unit,
    onSessionMenu: (ChatSession, Rect) -> Unit,
    onCreateSession: () -> Unit,
    onWorkspaceMenu: ((Rect) -> Unit)?
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 「⋯」自身的窗口坐标：伴随面板需贴合该按钮弹出，不依赖推断
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }

    // 行高规范见文件头的 TREE_ROW_PAD 常量组：控制模块间距，使整列均匀

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltCardRow { onToggle() }
            .padding(start = 8.dp, end = 6.dp, top = TREE_ROW_PAD, bottom = TREE_ROW_PAD),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 折叠箭头置于行首：若位于行尾，会与「⋯」「＋」形成三个图标的工具栏式排列。
        // 移至文件夹之前后，左侧读作「可展开的分组」，右侧仅保留动作；
        // 整行仍可点击，箭头本身不设独立热区。
        Chevron(
            expanded = expanded,
            size = ChevronSize.SMALL,
            tint = onSurface.copy(alpha = if (expanded) 0.5f else 0.38f)
        )
        Spacer(Modifier.width(6.dp))
        // 折叠时图标降低一档不透明度：未展开的分组视觉后退，展开的分组为当前阅读对象
        Icon(
            imageVector = FolderIcon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = onSurface.copy(alpha = if (expanded) 0.78f else 0.42f)
        )
        Spacer(Modifier.width(8.dp))
        // 标题在「标题行 + 时间行」高度的盒子内单独居中。
        // 若将标题放入 Column 并以等高空行撑高，整体居中后
        // 标题会落在上半部分，呈现文字整体上移的观感。
        // 当前盒子高度与会话行的文字块一致（行高仍与会话行等高），仅将文字置于中心。
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = TREE_CONTENT_HEIGHT),
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
        // 右侧动作：折叠且当前会话不在其中时整体隐藏（此时该分组只是索引，不是操作对象）。
        // 展开后或当前会话在其内时：自建工作区给「⋯」与「＋」，默认工作区与未分类只给「＋」
        if (isCurrentWorkspace || expanded) {
            if (onWorkspaceMenu != null) {
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
            // 冷启动回位：目标会话原本排在第 5 名之后时，用它顶替第 5 个位置，
            // 被挤掉的那条退到第 6 位，仍可经「展开其余」看到
            val head = sessions.take(SESSION_PREVIEW).toMutableList()
            if (revealSessionId != null) {
                val index = sessions.indexOfFirst { it.id == revealSessionId }
                if (index >= SESSION_PREVIEW) head[SESSION_PREVIEW - 1] = sessions[index]
            }
            head
        }
        // 会话行也走增删动画：新建 / 分叉从 0 高度展开，归档 / 移出工作区时收缩后再移除，
        // 余下的行平滑补位，不会出现「整列瞬间跳一格」
        AnimatedPresenceColumn(items = visible, keyOf = { it.id }) { session ->
            SessionTreeRow(
                session = session,
                isCurrent = session.id == currentSessionId,
                archived = session.archived,
                onBounds = if (session.id == revealSessionId) onRevealBounds else null,
                menuOpen = menuSessionId == session.id,
                revealed = revealedSessionId == session.id,
                onHoldChange = { pressed -> onSessionHold(session.id, pressed) },
                onClick = {
                    if (session.archived) onArchivedSessionOpen() else onPickSession(session.id)
                },
                onMenu = { anchor -> onSessionMenu(session, anchor) }
            )
        }
        if (sessions.size > SESSION_PREVIEW) {
            Text(
                text = if (showAll) "收起" else "展开其余 " + (sessions.size - SESSION_PREVIEW) + " 个会话",
                modifier = Modifier
                    .fillMaxWidth()
                    .saltCardRow() { onShowAllChange(!showAll) }
                    // 固定容器高 34dp：行高 24sp，上下余量由居中分配，文字垂直居中
                    .height(34.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(start = 46.dp, end = 16.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}

/**
 * 树里的一行会话：标题（第一行）+ 相对时间（第二行小字）+ 按接触显示的「⋯」。
 *
 * 时间由「右侧同一行」移至标题下方并缩小字号：抽屉宽度仅 304dp，标题与时间同处一行时，
 * 稍长的标题会被截断，而时间占用约六十 dp；分行后标题可多显示七八个字，
 * 时间降为次要信息，不影响扫读。
 *
 * 选中态：整行淡绿底 + 标题使用完全不透明正文色 + 时间不降至 30%；未选中标题 62%。
 * 层级仍靠左内距 30dp 表达（比工作区行再缩进一个文件夹图标的宽度）。
 */
@Composable
private fun SessionTreeRow(
    session: ChatSession,
    isCurrent: Boolean,
    /** 已归档会话在「全部／仅显示已归档」档位下可见：文字降为说明色，且不提供归档菜单。 */
    archived: Boolean,
    /** 冷启动回位用：把该行在窗口中的矩形交给上层，由上层决定是否滚动。 */
    onBounds: ((Rect) -> Unit)? = null,
    /** 该行的伴随面板是否正开着：开着时「⋯」必须保持可见。 */
    menuOpen: Boolean,
    /** 接触后处于停留时间内：此时显示「⋯」，让用户来得及点一次。 */
    revealed: Boolean,
    /** 按下 / 抬起回调：按住期间上层不计时。 */
    onHoldChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onMenu: (Rect) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var anchor by remember { mutableStateOf(Rect.Zero) }
    // 手指接触即显示「⋯」，与 DSH 的鼠标悬停等价；显示时长与「只留一个」由上层统一管理。
    // 取 Initial 阶段的原始按压状态，而不是 clickable 的按压态：上下滑动时后者会被取消，
    // 而这里要的只是「手指还按在这一行上」，包括长按与滑动经过。
    var pressed by remember { mutableStateOf(false) }
    // 左右内距对称：左侧缩进 34dp，右侧同样保留 34dp，使文字不紧贴卡片右缘。
    // 「⋯」绘制在这 34dp 内、不额外占宽，因此无论是否显示「⋯」，两行文字的右边界保持一致。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onBounds == null) Modifier
                else Modifier.onGloballyPositioned { onBounds(it.windowRect()) }
            )
            .background(
                when {
                    !isCurrent -> Color.Transparent
                    isDark -> Palette.DarkRowSelected
                    else -> Palette.LightRowSelected
                }
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent(PointerEventPass.Initial)
                            .changes.any { it.pressed }
                        if (pressed != down) {
                            pressed = down
                            onHoldChange(down)
                        }
                    }
                }
            }
            // 短按进入会话；长按只留住「⋯」——抬手不再触发进入，否则露出的按钮根本没有机会被点
            .saltCardRow(onClick = onClick, onLongClick = {})
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 固定整行高度：竖向不再用内距撑高，两行文字在行内居中
                .height(TREE_ROW_HEIGHT)
                .padding(
                    start = SESSION_INSET,
                    end = SESSION_INSET
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifBlank { "新会话" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 行高显式给定：继承的 24sp 会把 13sp 标题放入 24dp 行框，
                    // 两行合计高度明显高于「主页/终端/设置」行
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = fixedLineHeight(SESSION_TITLE_LINE)
                    ),
                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    color = when {
                        archived -> onSurface.copy(alpha = 0.42f)
                        isCurrent -> onSurface
                        else -> onSurface.copy(alpha = 0.62f)
                    }
                )
                Text(
                    text = SessionStore.relativeTime(session.updatedAt),
                    modifier = Modifier.padding(top = SESSION_TIME_GAP),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 10.sp,
                        lineHeight = fixedLineHeight(SESSION_TIME_LINE)
                    ),
                    color = when {
                        archived -> onSurface.copy(alpha = 0.38f)
                        isCurrent -> onSurface.copy(alpha = 0.5f)
                        else -> onSurface.copy(alpha = 0.3f)
                    }
                )
            }
        }
        // 「⋯」出现在当前会话行，或手指正接触的那一行。
        // 若每行常驻显示，右侧会形成一条竖排点线；只在接触时出现，既保留可操作性也不干扰扫读。
        // 出现不设动画（接触即显示）；隐藏由上层先停留 SESSION_MENU_LINGER_MS，再在 200ms 内渐隐。
        // 渐隐只动透明度、不动宽度：按钮绘制在行尾内距里，本来就不占正文宽度，
        // 一旦同时收窄宽度，裁剪会把图形切成两半，观感上等同于瞬间消失。
        // 未唤醒的空壳会话不提供菜单：此时可执行的只有「重命名」，而它还没有任何内容需要组织。
        // 用 awakened 而不是 blank：旧数据没有 blank 键，历史里的 0 消息空壳也要一并挡掉。
        // 已归档会话同样不给「⋯」：其中的「归档会话」对它没有意义，取消归档在设置页完成。
        val menuVisible = !archived && session.awakened && (isCurrent || revealed || menuOpen)
        val menuAlpha by animateFloatAsState(
            targetValue = if (menuVisible) 1f else 0f,
            // 出现立即到位（接触即显示），消失按 SESSION_MENU_FADE_MS 渐隐
            animationSpec = if (menuVisible) snap() else tween(SESSION_MENU_FADE_MS),
            label = "sessionMenuAlpha"
        )
        if (menuAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .alpha(menuAlpha)
            ) {
                Box(modifier = Modifier.padding(end = 2.dp)) {
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
}

/**
 * 该节点在窗口中的矩形，**不裁剪**。
 *
 * 不能用 [boundsInWindow]：抽屉面板滑动时它会被父级裁剪，未完全展开时读到的是 0 尺寸或
 * 半截宽度，冷启动回位据此判断必然失败。位置与尺寸分开取，两者都不受裁剪影响。
 */
private fun LayoutCoordinates.windowRect(): Rect {
    val origin = positionInWindow()
    return Rect(origin.x, origin.y, origin.x + size.width, origin.y + size.height)
}

/** 放大镜：一个圆环加一段向右下的把手，尺寸由调用方给定（收起态 14dp、展开态 11dp）。 */
@Composable
private fun SearchGlyph(size: Dp, tint: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.1f
        drawCircle(
            color = tint,
            radius = this.size.minDimension * 0.32f,
            center = Offset(this.size.width * 0.42f, this.size.height * 0.42f),
            style = Stroke(width = stroke)
        )
        drawLine(
            color = tint,
            start = Offset(this.size.width * 0.63f, this.size.height * 0.63f),
            end = Offset(this.size.width * 0.92f, this.size.height * 0.92f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/**
 * 搜索结果的一行：两行结构，与 DSH 的结果行一致。
 *
 * 第一行是标题（左内距与树内会话行一致，读作同一条列表），第二行是工作区名；
 * 正文命中时第二行改为显示片段。归档结果整行降到说明色，点击只提示取消归档。
 */
@Composable
private fun SearchResultRow(
    hit: SessionSearchHit,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val archived = hit.session.archived
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .saltCardRow { onClick() }
            // 固定整行高度，两行内容在行内居中；竖向不再用内距撑高
            .height(TREE_ROW_HEIGHT)
            .padding(start = SESSION_INSET, end = 16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = hit.session.title.ifBlank { "新会话" },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                lineHeight = fixedLineHeight(SESSION_TITLE_LINE)
            ),
            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
            color = when {
                archived -> onSurface.copy(alpha = 0.42f)
                isCurrent -> onSurface
                else -> onSurface.copy(alpha = 0.72f)
            }
        )
        Row(
            modifier = Modifier.padding(top = SESSION_TIME_GAP),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 工作区名上限 40%，正文片段占其余 60%；没有片段时工作区名独占整行（同 DSH）
            Text(
                text = hit.workspaceName,
                modifier = if (hit.snippet == null) Modifier.fillMaxWidth() else Modifier.weight(0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 10.sp,
                    lineHeight = fixedLineHeight(SESSION_TIME_LINE)
                ),
                color = onSurface.copy(alpha = if (archived) 0.38f else 0.45f)
            )
            if (hit.snippet != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = hit.snippet,
                    modifier = Modifier.weight(0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 10.sp,
                        lineHeight = fixedLineHeight(SESSION_TIME_LINE)
                    ),
                    color = onSurface.copy(alpha = if (archived) 0.38f else 0.3f)
                )
            }
        }
    }
}

/**
 * 「仅显示已归档」且无内容时的空状态：点名当前筛选，并给出回到全部对话的入口（同 DSH）。
 *
 * 图标沿用归档线稿，不用插画：整块保持与树内其它行同一套单色语言。
 */
@Composable
private fun ArchivedOnlyEmpty(onShowAll: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = DshIcons.Archive,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = onSurface.copy(alpha = 0.28f)
        )
        Text(
            text = "暂无已归档会话",
            fontSize = 13.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
        Text(
            text = "查看其他会话",
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onShowAll)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 13.sp,
            color = UiColors.accentFg
        )
    }
}

/** 行内小图标的热区：图形较小、热区较大，避免手指点击不准（30dp 是抽屉内可点击的下限）。
 *  [onBounds] 回调该按钮在窗口中的矩形：伴随面板需贴合此按钮弹出，不依赖推断。 */
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
 * 抽屉里的伴随面板：直接复用 [SaltMenuPopover]。
 *
 * 组件上移到 uikit 的原因：回收站条目的菜单要用同一套观感，
 * 而 feature 之间不得互相引用，重复实现会出现两套阴影与圆角。
 */
@Composable
private fun DrawerMenuPopover(
    anchor: Rect,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) = SaltMenuPopover(anchor = anchor, onDismiss = onDismiss, content = content)

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

/** 伴随面板里的一行：直接复用 [SaltMenuRow]，与回收站条目菜单保持同一套内距与字号。 */
@Composable
private fun MenuRow(text: String, color: Color? = null, onClick: () -> Unit) =
    SaltMenuRow(text = text, color = color, onClick = onClick)

/**
 * 重命名用的悬浮输入框：预填原名并全部选中（输入即整体替换），
 * 右侧对勾保存、左侧叉号取消。不设标题栏，与其它悬浮卡观感一致。
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
                cursorBrush = SolidColor(UiColors.accentFg),
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
                Text(text = "✓", fontSize = 18.sp, color = UiColors.accentFg)
            }
        }
        // 聚焦请求与输入框在同层组合：先组合、后 attach，请求焦点才不会落空
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
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 抽屉内这几行若使用裸 clickable：默认水波纹为矩形，置于圆角卡片内会露出直角。
            // 统一使用 saltCardRow，形状由卡片自身的 clip 裁剪
            .saltCardRow { onClick() }
            // 固定容器高 50dp：行高 24sp，上下余量由居中分配，字号变化不改变容器高度
            .height(50.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            // 20dp：比 16sp 的字略高一档，三行图标与文字视觉齐平；24 网格按 20 缩放，线宽约为 1.5dp
            modifier = Modifier.size(20.dp),
            tint = Palette.Neutral
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            // 标题先量、优先占位：说明文字只吃剩余宽度，窄窗 + 16sp 时也不会把「终端」挤成省略号
            fontSize = 16.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(6.dp))
        // 没有说明文字（例如「设置」行）时整块不画，避免留下多余间隔
        if (value != null) {
            Text(
                text = value,
                // 剩余宽度不足时由说明文字自己省略，标题不参与让位
                modifier = Modifier.weight(1f, fill = false),
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Spacer(Modifier.width(6.dp))
        }
        // 行尾的「›」改为 Chevron：折叠态朝右，与全局箭头规范一致
        Chevron(
            expanded = false,
            size = ChevronSize.NORMAL,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        )
    }
}
