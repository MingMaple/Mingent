package com.zmf.agent.feature.settings
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.blockTapsOnly

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zmf.agent.capability.mcp.McpManager
import com.zmf.agent.capability.mcp.McpServer
import com.zmf.agent.capability.mcp.McpServerStore
import com.zmf.agent.capability.skill.SkillEntry
import com.zmf.agent.capability.skill.SkillStore
import com.zmf.agent.capability.tools.ToolRegistry
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.common.stableStatusBarPadding
import com.zmf.agent.uikit.components.salt.SaltSwitch
import com.zmf.agent.uikit.layout.LocalUiWindow
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zmf.agent.uikit.common.saltFieldColors
import com.zmf.agent.uikit.common.NoticeCenter

/**
 * 「包管理」页：Skill 与插件（MCP 服务）两个 tab，左右滑动切换。
 *
 * 版式照 Salt：整页铺满、顶部一个返回箭头 + 标题、tab 不用 Material 的 TabRow 而是自己排一行，
 * 选中项下面一条 24×3dp 的绿条跟着手势走；内容区是 HorizontalPager，两页各自滚动。
 *
 * 约定（供上层使用）：凡修改 MCP 配置或 Skill 目录，都会回调一次 [onChanged]，
 * 上层据此刷新提示词中的 Skill 目录 / 工具声明；本页不直接修改提示词。
 */
@Composable
fun PackagePage(
    registry: ToolRegistry,
    onBack: () -> Unit,
    onChanged: () -> Unit = {},
    // 将「当前是否位于第 1 页」上报给上层：上层据此决定抽屉是否在 pager 之前拦截向右滑动
    onFirstPage: (Boolean) -> Unit = {}
) {
    // pageCount 使用 lambda：HorizontalPager（foundation 1.12）要求的形式
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    // 收进 rememberUpdatedState：上层每次重组都给新 lambda，不能让 effect 跟着重建
    val currentOnFirstPage by rememberUpdatedState(onFirstPage)
    // 停在第 2 页时抽屉不应拦截横向滑动，否则无法翻页；初值也需上报，因此挂在 LaunchedEffect 上
    LaunchedEffect(pagerState.currentPage) {
        currentOnFirstPage(pagerState.currentPage == 0)
    }

    Surface(
        // 铺满且不透明；最外层拦截点击，避免事件穿透到下方的设置页
        modifier = Modifier
            .fillMaxSize()
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .stableStatusBarPadding()
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "←",
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(50)) { onBack() }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "包管理",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            PackageTabBar(pagerState = pagerState, scope = scope)

            HorizontalPager(
                state = pagerState,
                // 两页都留着：切回来不用重新读盘扫 Skill 目录
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                when (page) {
                    0 -> SkillTab(onChanged = onChanged)
                    else -> McpTab(registry = registry, onChanged = onChanged)
                }
            }
        }
    }
}

/**
 * 两个 tab + 会滑动的指示条。
 *
 * 不使用 Material3 的 TabRow：其默认指示条为等分下划线，与 Salt 的交互效果不一致。
 * 这里自己排 Row（两个 tab 等分宽度），指示条用 animateFloatAsState 追
 * 「currentPage + currentPageOffsetFraction」：手拖的时候跟着手指实时动，
 * 点 tab 时由 animateScrollToPage 驱动像素滚动，指示条同时被弹簧带过去。
 */
@Composable
private fun PackageTabBar(pagerState: PagerState, scope: CoroutineScope) {
    // 0f = 停在第 1 页，1f = 停在第 2 页，中间值表示拖动进度
    val progress = pagerState.currentPage + pagerState.currentPageOffsetFraction
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = Motion.spring(),
        label = "packageTabIndicator"
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
                PackageTab(
                    text = "Skill",
                    selected = pagerState.currentPage == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } }
                )
                PackageTab(
                    text = "插件",
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
                        .background(UiColors.accentFg, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/** 单个 tab：选中转强调文字色并对齐指示条，未选中是 onSurface 45%。 */
@Composable
private fun PackageTab(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .saltRowClickable(RoundedCornerShape(10.dp), onClick = onClick),
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

// ---- 第一个 tab：MCP 服务 ----

/**
 * 插件 tab：MCP 服务清单。
 *
 * 行 = 名称 + 状态（就绪 · N 个工具 / 未启用 / 连接中 / 失败：原因）+ 启停开关；
 * 点行编辑，长按或右侧「删除」走二次确认；启停、保存、删除之后都重新 reload，
 * 因为工具需要在注册表中增删，而非仅切换一个开关。
 */
@Composable
private fun McpTab(registry: ToolRegistry, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<McpServer>>(emptyList()) }
    val statuses by McpManager.status.collectAsState()

    // 编辑框：editorOpen 控制显隐，editorSource 为 null 表示新建
    var editorOpen by remember { mutableStateOf(false) }
    var editorSource by remember { mutableStateOf<McpServer?>(null) }
    var pendingDelete by remember { mutableStateOf<McpServer?>(null) }

    LaunchedEffect(Unit) { servers = readServers(context) }

    /** 改完配置的统一收尾：重新连接（工具增删都在里面）。 */
    fun reload() {
        scope.launch {
            McpManager.reload(context, registry)
            onChanged()
        }
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // 顶部右侧的小按钮：只重连，不动配置
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "重新连接",
                modifier = Modifier
                    .saltRowClickable(RoundedCornerShape(8.dp)) { reload() }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                fontSize = 12.sp,
                color = onSurface.copy(alpha = 0.6f)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (servers.isEmpty()) {
                Text(
                    text = "还没有 MCP 服务",
                    modifier = Modifier.padding(vertical = 12.dp),
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        servers.forEachIndexed { index, server ->
                            McpRow(
                                server = server,
                                status = statuses.firstOrNull { it.server.name == server.name },
                                onToggle = { on ->
                                    scope.launch {
                                        servers = withContext(Dispatchers.IO) {
                                            McpServerStore.upsert(context, server.copy(enabled = on))
                                        }
                                        McpManager.reload(context, registry)
                                        onChanged()
                                    }
                                },
                                onEdit = {
                                    editorSource = server
                                    editorOpen = true
                                },
                                onDelete = { pendingDelete = server }
                            )
                            if (index != servers.lastIndex) {
                                HorizontalDivider(
                                    color = onSurface.copy(alpha = 0.06f)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 页面级加号行（不在上面的服务卡里，是卡下方独立的一行）：
                // 圆角取 16dp，与它上方的 16dp 服务卡同弧；10dp 会看得出比卡片尖一点
                .saltRowClickable(RoundedCornerShape(16.dp)) {
                    editorSource = null
                    editorOpen = true
                }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "＋ 添加服务", fontSize = 15.sp, color = Palette.Neutral)
        }
    }

    if (editorOpen) {
        McpEditorDialog(
            original = editorSource,
            onDismiss = { editorOpen = false },
            onSave = { updated ->
                val oldName = editorSource?.name
                editorOpen = false
                scope.launch {
                    servers = withContext(Dispatchers.IO) {
                        // 改名即更换主键：先移除旧条目，否则会残留一条孤立配置
                        if (oldName != null && oldName != updated.name) {
                            McpServerStore.remove(context, oldName)
                        }
                        McpServerStore.upsert(context, updated)
                    }
                    McpManager.reload(context, registry)
                    onChanged()
                }
            }
        )
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除服务", fontSize = 17.sp) },
            text = {
                Text(
                    text = "「" + server.name + "」会被移除，它注册进来的工具也会一起摘掉。",
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        servers = withContext(Dispatchers.IO) {
                            McpServerStore.remove(context, server.name)
                        }
                        McpManager.reload(context, registry)
                        onChanged()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 一个 MCP 服务：点行编辑、长按删除、右侧开关启停、右侧「删除」走二次确认。 */
@Composable
private fun McpRow(
    server: McpServer,
    status: McpManager.ServerStatus?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val state = status?.state
    val dotColor = when (state) {
        McpManager.ServerState.READY -> UiColors.success
        McpManager.ServerState.CONNECTING -> UiColors.warning
        McpManager.ServerState.FAILED -> MaterialTheme.colorScheme.error
        else -> onSurface.copy(alpha = 0.25f)
    }
    // 五种显示：无状态（尚未连接）也单独说明，不显示为「连接中」
    val detail = when (state) {
        null -> "未连接"
        McpManager.ServerState.DISABLED -> "未启用"
        McpManager.ServerState.CONNECTING -> "连接中"
        McpManager.ServerState.READY -> "就绪 · " + status.toolCount + " 个工具"
        McpManager.ServerState.FAILED -> "连接失败，请检查地址与网络后重试"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行本身不用 clickable：长按要一起接，detectTapGestures 才能都拿到
            .pointerInput(server) {
                detectTapGestures(
                    onTap = { onEdit() },
                    onLongPress = { onDelete() }
                )
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                fontSize = 15.sp,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(dotColor, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = detail,
                    fontSize = 11.sp,
                    color = if (state == McpManager.ServerState.FAILED) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                    } else {
                        onSurface.copy(alpha = 0.45f)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = "删除",
            modifier = Modifier
                .saltRowClickable(RoundedCornerShape(8.dp), onClick = onDelete)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
        SaltSwitch(checked = server.enabled, onCheckedChange = onToggle)
    }
}

/**
 * 新增 / 编辑服务。
 *
 * [original] 为 null 即表示新建；headers 使用「Key: Value」一行一条的文本编辑，
 * 相比一排动态输入框更简洁，且与 mcp.json 的存储形式一致。
 *
 * 三种 transport 的字段不共用：http/sse 填地址和请求头；stdio 填命令/参数/环境变量/工作目录，
 * 此时地址和请求头都不显示（填写无意义），保存前只校验当前传输类型真正需要的字段。
 */
@Composable
private fun McpEditorDialog(
    original: McpServer?,
    onDismiss: () -> Unit,
    onSave: (McpServer) -> Unit
) {
    var name by remember(original) { mutableStateOf(original?.name.orEmpty()) }
    var transport by remember(original) {
        mutableStateOf(McpServer.normalizeTransport(original?.transport ?: McpServer.TRANSPORT_HTTP))
    }
    var url by remember(original) { mutableStateOf(original?.url.orEmpty()) }
    var headers by remember(original) { mutableStateOf(headersToText(original?.headers)) }
    // stdio 四项：命令必填，其余留空时取默认值（args 无、env 无、cwd=/root）
    var command by remember(original) { mutableStateOf(original?.command.orEmpty()) }
    var args by remember(original) { mutableStateOf(original?.args?.joinToString(" ").orEmpty()) }
    var env by remember(original) { mutableStateOf(envToText(original?.env)) }
    var cwd by remember(original) { mutableStateOf(original?.cwd.orEmpty()) }
    val stdio = McpServer.normalizeTransport(transport) == McpServer.TRANSPORT_STDIO
    val canSave = name.isNotBlank() && if (stdio) command.isNotBlank() else url.isNotBlank()

    PackageDialog(
        title = if (original == null) "添加服务" else "编辑服务",
        confirmText = "保存",
        confirmEnabled = canSave,
        onDismiss = onDismiss,
        onConfirm = {
            onSave(
                McpServer(
                    name = name.trim(),
                    transport = McpServer.normalizeTransport(transport),
                    // url/headers 不按传输清空：用户切回 http/sse 时不用重填
                    url = url.trim(),
                    headers = parseHeaders(headers),
                    enabled = original?.enabled ?: true,
                    command = command.trim(),
                    args = parseArgs(args),
                    env = parseEnv(env),
                    cwd = cwd.trim()
                )
            )
        }
    ) {
        OutlinedTextField(
                colors = saltFieldColors(),
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            placeholder = { Text("名称，例如 GitHub", fontSize = 12.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
        )

        // transport 三种值，三个小胶囊三选一
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransportChip(
                text = "http",
                selected = transport == McpServer.TRANSPORT_HTTP,
                onClick = { transport = McpServer.TRANSPORT_HTTP }
            )
            TransportChip(
                text = "sse",
                selected = transport == McpServer.TRANSPORT_SSE,
                onClick = { transport = McpServer.TRANSPORT_SSE }
            )
            TransportChip(
                text = "stdio",
                selected = stdio,
                onClick = { transport = McpServer.TRANSPORT_STDIO }
            )
        }

        if (stdio) {
            // 命令行在 App 内的 Ubuntu 中执行：npx / python3 等直接写命令名即可
            OutlinedTextField(
                colors = saltFieldColors(),
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                placeholder = { Text("命令，例如 npx", fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            OutlinedTextField(
                colors = saltFieldColors(),
                value = args,
                onValueChange = { args = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                placeholder = { Text("参数，空格分隔：-y @modelcontextprotocol/server-filesystem /root", fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            OutlinedTextField(
                colors = saltFieldColors(),
                value = env,
                onValueChange = { env = it },
                modifier = Modifier.fillMaxWidth().height(88.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                placeholder = { Text("环境变量，每行一条：KEY=VALUE", fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            OutlinedTextField(
                colors = saltFieldColors(),
                value = cwd,
                onValueChange = { cwd = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                placeholder = { Text("工作目录，留空即 /root", fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
        } else {
            OutlinedTextField(
                colors = saltFieldColors(),
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                placeholder = { Text("地址，例如 https://example.com/mcp", fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )

            OutlinedTextField(
                colors = saltFieldColors(),
                value = headers,
                onValueChange = { headers = it },
                modifier = Modifier.fillMaxWidth().height(110.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                placeholder = {
                    Text("请求头，每行一条：Authorization: Bearer xxx", fontSize = 12.sp)
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
        }
    }
}

/** 传输方式的小胶囊：选中墨底反白，未选中是 6% 淡影。 */
@Composable
private fun TransportChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) onSurface else onSurface.copy(alpha = 0.06f),
        // 点击反馈必须跟着 16dp 胶囊走：把 clickable 交给 Surface 的 modifier 时，
        // Surface 自己的 clip 在它之后生效，水波纹会按矩形铺开、四个角露出方角
        modifier = Modifier.saltCardRow(onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.surface else onSurface.copy(alpha = 0.6f)
        )
    }
}

// ---- 第二个 tab：Skill ----

/**
 * Skill tab：列出现有 Skill（读盘），底部导入 zip / 新建。
 *
 * 读盘全部走 Dispatchers.IO：一个大 zip 解出来几十个文件时不能卡主线程。
 * 导入 / 新建 / 删除之后都回调 [onChanged]，上层的 available_skills 目录要重扫。
 */
@Composable
private fun SkillTab(onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<SkillEntry>>(emptyList()) }
    // 编辑目标：第一个元素为 null 表示新建，非 null 表示编辑该技能
    var editor by remember { mutableStateOf<Pair<SkillEntry?, SkillDraft>?>(null) }
    // 导入前发现同名技能时先挂起，等用户确认覆盖
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var conflictName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { skills = readSkills(context) }

    fun launchImport(uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { SkillStore.importZip(context, uri) }
            // onSuccess / onFailure 的 lambda 不在挂起上下文中，文件读取需在此处分别处理
            val count = result.getOrNull()
            if (count == null) {
                toast(context, "导入失败：压缩包无法读取或格式不符，请换一个文件后重试")
            } else {
                skills = readSkills(context)
                onChanged()
                toast(context, "导入成功，当前共 " + count + " 个 Skill")
            }
        }
    }

    fun openEditor(entry: SkillEntry?) {
        if (entry == null) {
            editor = null to SkillDraft("", "", "", "")
            return
        }
        scope.launch {
            val raw = withContext(Dispatchers.IO) { SkillStore.readRaw(context, entry.id) }
            editor = entry to skillDraft(entry, raw)
        }
    }

    // OpenDocument：仅选择 zip；未取得 uri 表示用户已取消
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                // 先按条目名判断会落成哪个 id：同名时不能静默覆盖别人的技能
                val id = withContext(Dispatchers.IO) { SkillStore.peekZipId(context, uri) }
                val conflict = if (id.isNullOrEmpty()) {
                    null
                } else {
                    withContext(Dispatchers.IO) { SkillStore.find(context, id) }
                }
                if (conflict == null) {
                    launchImport(uri)
                } else {
                    pendingImport = uri
                    conflictName = conflict.name
                }
            }
        }
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (skills.isEmpty()) {
                Text(
                    text = "还没有 Skill",
                    modifier = Modifier.padding(vertical = 12.dp),
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        skills.forEachIndexed { index, entry ->
                            SkillRow(
                                entry = entry,
                                onEdit = { openEditor(entry) },
                                onDelete = {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            SkillStore.delete(context, entry.id)
                                        }
                                        skills = readSkills(context)
                                        if (ok) onChanged()
                                        toast(
                                            context,
                                            if (ok) "已删除「" + entry.name + "」" else "删除失败，请稍后重试"
                                        )
                                    }
                                }
                            )
                            if (index != skills.lastIndex) {
                                HorizontalDivider(
                                    color = onSurface.copy(alpha = 0.06f)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "＋ 导入 zip",
                modifier = Modifier
                    .saltRowClickable(RoundedCornerShape(8.dp)) {
                        zipLauncher.launch(arrayOf("application/zip"))
                    }
                    .padding(vertical = 2.dp),
                fontSize = 15.sp,
                color = Palette.Neutral
            )
            Spacer(Modifier.width(24.dp))
            Text(
                text = "＋ 新建",
                modifier = Modifier
                    .saltRowClickable(RoundedCornerShape(8.dp)) { openEditor(null) }
                    .padding(vertical = 2.dp),
                fontSize = 15.sp,
                color = Palette.Neutral
            )
        }
    }

    editor?.let { pair ->
        val target = pair.first
        SkillEditorDialog(
            isNew = target == null,
            initial = pair.second,
            onDismiss = { editor = null },
            onSave = { name, description, whenToUse, body ->
                editor = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        SkillStore.save(
                            context = context,
                            key = target?.id,
                            name = name,
                            description = description,
                            body = body,
                            whenToUse = whenToUse,
                            // 进入编辑态就意味着用户已经看到过这个技能，覆盖无需再确认
                            overwrite = target != null
                        )
                    }
                    val saved = result.getOrNull()
                    if (saved == null) {
                        toast(context, "保存失败，请稍后重试")
                    } else {
                        skills = readSkills(context)
                        onChanged()
                        toast(context, (if (target == null) "已创建「" else "已更新「") + saved.name + "」")
                    }
                }
            }
        )
    }

    val pending = pendingImport
    if (pending != null) {
        AlertDialog(
            onDismissRequest = {
                pendingImport = null
                conflictName = null
            },
            title = { Text("已存在同名 Skill") },
            text = { Text("「" + (conflictName ?: "") + "」已经存在，继续导入会整体覆盖它。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        conflictName = null
                        launchImport(pending)
                    }
                ) { Text("覆盖导入") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        conflictName = null
                    }
                ) { Text("取消") }
            }
        )
    }
}

/** 一个 Skill：名称 + 描述（最多两行）+ 右侧删除；点按整行进入编辑（正文即预览）。 */
@Composable
private fun SkillRow(entry: SkillEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltRowClickable(RoundedCornerShape(12.dp), onClick = onEdit)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontSize = 15.sp,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 11.sp,
                    color = onSurface.copy(alpha = 0.45f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                // 描述是模型挑选技能的唯一依据，缺了等于这个技能不会被用到，必须让用户看见
                Text(
                    text = "缺少描述：模型无法判断何时使用，点按补上",
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 11.sp,
                    color = UiColors.accentFg
                )
            }
        }
        Text(
            text = "删除",
            modifier = Modifier
                .saltRowClickable(RoundedCornerShape(8.dp), onClick = onDelete)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
    }
}

/** 编辑框的初始值。 */
private data class SkillDraft(
    val name: String,
    val description: String,
    val whenToUse: String,
    val body: String
)

/** 从原始 SKILL.md 还原初始值；raw 为空（读盘失败）时退回列表里的摘要。 */
private fun skillDraft(entry: SkillEntry?, raw: String?): SkillDraft {
    if (raw == null) {
        return SkillDraft(
            name = entry?.name.orEmpty(),
            description = entry?.description.orEmpty(),
            whenToUse = entry?.whenToUse.orEmpty(),
            body = ""
        )
    }
    val parsed = SkillStore.frontMatter(raw)
    val meta = parsed.meta
    return SkillDraft(
        name = meta["name"].orEmpty().ifBlank { entry?.name.orEmpty() },
        description = meta["description"].orEmpty().ifBlank { entry?.description.orEmpty() },
        whenToUse = meta["whenToUse"].orEmpty().ifBlank { meta["when_to_use"].orEmpty() },
        body = parsed.body
    )
}

/**
 * 新建 / 编辑 Skill：名称、描述、适用场景、正文，落成 skills/<id>/SKILL.md。
 *
 * 编辑时正文框本身就是预览，不需要再单独做只读页；未识别的 frontmatter 字段
 * （license、allowed-tools 等）由 [SkillStore.save] 原样保留。
 */
@Composable
private fun SkillEditorDialog(
    isNew: Boolean,
    initial: SkillDraft,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, whenToUse: String, body: String) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var description by remember(initial) { mutableStateOf(initial.description) }
    var whenToUse by remember(initial) { mutableStateOf(initial.whenToUse) }
    var body by remember(initial) { mutableStateOf(initial.body) }
    val canSave = name.isNotBlank() && description.isNotBlank() && body.isNotBlank()

    PackageDialog(
        title = if (isNew) "新建 Skill" else "编辑 Skill",
        confirmText = if (isNew) "创建" else "保存",
        confirmEnabled = canSave,
        onDismiss = onDismiss,
        onConfirm = { onSave(name.trim(), description.trim(), whenToUse.trim(), body) }
    ) {
        OutlinedTextField(
                colors = saltFieldColors(),
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            placeholder = { Text("名称（小写字母、数字、- _）", fontSize = 12.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
        )
        OutlinedTextField(
                colors = saltFieldColors(),
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            placeholder = { Text("一句话描述：什么时候该用它（必填）", fontSize = 12.sp) },
            singleLine = true
        )
        OutlinedTextField(
                colors = saltFieldColors(),
            value = whenToUse,
            onValueChange = { whenToUse = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            placeholder = { Text("适用场景（可选）：更具体的触发条件", fontSize = 12.sp) },
            singleLine = true
        )
        OutlinedTextField(
                colors = saltFieldColors(),
            value = body,
            onValueChange = { body = it },
            modifier = Modifier.fillMaxWidth().height(180.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            placeholder = { Text("正文：给模型看的具体步骤（必填）", fontSize = 12.sp) }
        )
    }
}

// ---- 共用壳子 / 小工具 ----

/**
 * 弹窗壳子：跟首页的 MinimalDialog 同一套观感（0.9 宽的圆角卡），但多了标题与取消/确认。
 *
 * 内容区使用 weight(1f, fill = false) + 滚动：字段较多时可自行滚动，不会将按钮挤出屏幕。
 */
@Composable
private fun PackageDialog(
    title: String,
    confirmText: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = LocalUiWindow.current.dialogMaxWidth)
                .fillMaxWidth(0.92f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = LocalUiWindow.current.dialogMaxHeight)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = onConfirm,
                        enabled = confirmEnabled
                    ) {
                        Text(
                            text = confirmText,
                            color = if (confirmEnabled) {
                                UiColors.accentFg
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 读配置读盘：MCP 只是一条 JSON，但也别占主线程。 */
private suspend fun readServers(context: Context): List<McpServer> =
    withContext(Dispatchers.IO) { McpServerStore.load(context) }

/** 读 Skill 目录：要遍历目录、解析每个 SKILL.md 的 frontmatter，必须离开主线程。 */
private suspend fun readSkills(context: Context): List<SkillEntry> =
    withContext(Dispatchers.IO) { SkillStore.list(context) }

/**
 * 把 headers 文本解析成 Map：「Key: Value」一行一条。
 * 空行、没有冒号的行、key 为空的行直接跳过：宁可缺少一个请求头，也不写入无效键。
 */
private fun parseHeaders(text: String): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    text.lines().forEach { line ->
        val trim = line.trim()
        if (trim.isEmpty()) return@forEach
        val index = trim.indexOf(':')
        if (index <= 0) return@forEach
        val key = trim.substring(0, index).trim()
        if (key.isNotEmpty()) map[key] = trim.substring(index + 1).trim()
    }
    return map
}

/**
 * 把参数文本按空格拆成数组：UI 上是「空格分隔」的一行，存的是数组，
 * 因此单个参数中确实需要包含空格时，只能通过其它方式编辑 mcp.json（此处不解析引号，避免错误吞并字符）。
 */
private fun parseArgs(text: String): List<String> =
    text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * 环境变量文本「KEY=VALUE」一行一条；只按第一个 = 切分，值里带 = 也不受影响。
 * 空行、没有 = 的行、key 为空的都跳过。
 */
private fun parseEnv(text: String): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    text.lines().forEach { line ->
        val trim = line.trim()
        if (trim.isEmpty()) return@forEach
        val index = trim.indexOf('=')
        if (index <= 0) return@forEach
        val key = trim.substring(0, index).trim()
        if (key.isNotEmpty()) map[key] = trim.substring(index + 1)
    }
    return map
}

/** 反向：env 摊成「KEY=VALUE」多行文本，喂给编辑框。 */
private fun envToText(env: Map<String, String>?): String {
    if (env.isNullOrEmpty()) return ""
    return env.entries.joinToString("\n") { entry -> entry.key + "=" + entry.value }
}

/** 反向：配置里的 headers 摊成「Key: Value」多行文本，喂给编辑框。 */
private fun headersToText(headers: Map<String, String>?): String {
    if (headers.isNullOrEmpty()) return ""
    return headers.entries.joinToString("\n") { entry -> entry.key + ": " + entry.value }
}

private fun toast(context: Context, text: String) {
    NoticeCenter.info(text)
}
