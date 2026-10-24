package com.zmf.agent.ui.settings
import com.zmf.agent.ui.common.blockTapsOnly

import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.zmf.agent.agent.mcp.McpManager
import com.zmf.agent.agent.mcp.McpServer
import com.zmf.agent.agent.mcp.McpServerStore
import com.zmf.agent.agent.skill.SkillEntry
import com.zmf.agent.agent.skill.SkillStore
import com.zmf.agent.tools.ToolRegistry
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.shell.SaltSwitch
import com.zmf.agent.ui.theme.Motion
import com.zmf.agent.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「包管理」页：插件（MCP 服务）与 Skill 两个 tab，左右滑动切换。
 *
 * 版式照 Salt：整页铺满、顶部一个返回箭头 + 标题、tab 不用 Material 的 TabRow 而是自己排一行，
 * 选中项下面一条 24×3dp 的绿条跟着手势走；内容区是 HorizontalPager，两页各自滚动。
 *
 * 约定（上层要用）：凡是改了 MCP 配置或 Skill 目录，都会回调一次 [onChanged]，
 * 上层拿它去刷新提示词里的 Skill 目录 / 工具声明；本页不直接碰提示词。
 */
@Composable
fun PackagePage(
    registry: ToolRegistry,
    onBack: () -> Unit,
    onChanged: () -> Unit = {},
    // 把「当前是不是第 1 页」报给上层：上层据此决定抽屉要不要在 pager 之前抢向右的位移
    onFirstPage: (Boolean) -> Unit = {}
) {
    // pageCount 用 lambda：HorizontalPager 在 foundation 1.12 里的写法
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    // 收进 rememberUpdatedState：上层每次重组都给新 lambda，不能让 effect 跟着重建
    val currentOnFirstPage by rememberUpdatedState(onFirstPage)
    // 停在第 2 页时抽屉不该抢横滑，否则翻页翻不动；初值也要报，所以挂在 LaunchedEffect 上
    LaunchedEffect(pagerState.currentPage) {
        currentOnFirstPage(pagerState.currentPage == 0)
    }

    Surface(
        // 铺满且不透明；最外层吞掉点击，避免穿透点到下面的设置页
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
                    0 -> McpTab(registry = registry, onChanged = onChanged)
                    else -> SkillTab(onChanged = onChanged)
                }
            }
        }
    }
}

/**
 * 两个 tab + 会滑动的指示条。
 *
 * 不用 Material3 的 TabRow：它的默认指示条是等分的下划线，跟 Salt 的手感不一致。
 * 这里自己排 Row（两个 tab 等分宽度），指示条用 animateFloatAsState 追
 * 「currentPage + currentPageOffsetFraction」——手拖的时候跟着手指实时动，
 * 点 tab 时由 animateScrollToPage 驱动像素滚动，指示条同时被弹簧带过去。
 */
@Composable
private fun PackageTabBar(pagerState: PagerState, scope: CoroutineScope) {
    // 0f = 停在第 1 页，1f = 停在第 2 页，中间的零头就是拖拽进度
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
                    text = "插件",
                    selected = pagerState.currentPage == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } }
                )
                PackageTab(
                    text = "Skill",
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
                Palette.Neutral
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
 * 因为工具要在注册表里增删，不是只改一个开关。
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
            modifier = Modifier.fillMaxWidth().height(36.dp),
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
                    shape = RoundedCornerShape(22.dp),
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
                                    modifier = Modifier.padding(start = 16.dp),
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
                .saltRowClickable(RoundedCornerShape(10.dp)) {
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
                        // 改了名字等于换主键：先把旧条目摘掉，不然会多出一条孤儿配置
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
        McpManager.ServerState.READY -> Palette.AccentGreen
        McpManager.ServerState.CONNECTING -> Palette.AccentBlue
        McpManager.ServerState.FAILED -> MaterialTheme.colorScheme.error
        else -> onSurface.copy(alpha = 0.25f)
    }
    // 五种显示：没有状态（还没接过）也单独说一句，不假装成「连接中」
    val detail = when (state) {
        null -> "未连接"
        McpManager.ServerState.DISABLED -> "未启用"
        McpManager.ServerState.CONNECTING -> "连接中"
        McpManager.ServerState.READY -> "就绪 · " + (status?.toolCount ?: 0) + " 个工具"
        McpManager.ServerState.FAILED -> "失败：" + status?.message.orEmpty()
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
 * [original] 为 null 就是新建；headers 用「Key: Value」一行一条的文本编辑，
 * 比一排动态输入框更省事，也跟 mcp.json 里存的是一回事。
 *
 * 三种 transport 的字段不共用：http/sse 填地址和请求头；stdio 填命令/参数/环境变量/工作目录，
 * 此时地址和请求头都不显示（填了也没用），保存前只校验当前那种传输真正需要的字段。
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
    // stdio 四件套：命令必填，其余留空就按默认（args 无、env 无、cwd=/root）
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
            // 命令行在 App 内的 Ubuntu 里执行：npx / python3 这种直接写名字就行
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                placeholder = { Text("命令，例如 npx", fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            OutlinedTextField(
                value = args,
                onValueChange = { args = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                placeholder = { Text("参数，空格分隔：-y @modelcontextprotocol/server-filesystem /root", fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            OutlinedTextField(
                value = env,
                onValueChange = { env = it },
                modifier = Modifier.fillMaxWidth().height(88.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                placeholder = { Text("环境变量，每行一条：KEY=VALUE", fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            OutlinedTextField(
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
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                placeholder = { Text("地址，例如 https://example.com/mcp", fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )

            OutlinedTextField(
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

/** 传输方式的小胶囊：选中吃 Salt 绿，未选中是 6% 淡影。 */
@Composable
private fun TransportChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Palette.AccentGreen.copy(alpha = 0.16f) else onSurface.copy(alpha = 0.06f),
        // 点击反馈必须跟着 12dp 胶囊走：把 clickable 交给 Surface 的 modifier 时，
        // Surface 自己的 clip 在它之后生效，水波纹会按矩形铺开、四个角露出方角
        modifier = Modifier.saltRowClickable(RoundedCornerShape(12.dp), onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Palette.AccentGreen else onSurface.copy(alpha = 0.6f)
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
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { skills = readSkills(context) }

    // OpenDocument：只挑 zip；拿不到 uri 说明用户取消了
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { SkillStore.importZip(context, uri) }
                // onSuccess / onFailure 的 lambda 不是挂起上下文，读盘要在这里分开做
                val count = result.getOrNull()
                if (count == null) {
                    toast(context, "导入失败：" + describeError(result.exceptionOrNull()))
                } else {
                    skills = readSkills(context)
                    onChanged()
                    toast(context, "导入成功，当前共 " + count + " 个 Skill")
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
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        skills.forEachIndexed { index, entry ->
                            SkillRow(
                                entry = entry,
                                onDelete = {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            SkillStore.delete(context, entry.id)
                                        }
                                        skills = readSkills(context)
                                        if (ok) onChanged()
                                        toast(
                                            context,
                                            if (ok) "已删除「" + entry.name + "」" else "删除失败"
                                        )
                                    }
                                }
                            )
                            if (index != skills.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp),
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
                    .saltRowClickable(RoundedCornerShape(8.dp)) { showCreate = true }
                    .padding(vertical = 2.dp),
                fontSize = 15.sp,
                color = Palette.Neutral
            )
        }
    }

    if (showCreate) {
        NewSkillDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, description, body ->
                showCreate = false
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        SkillStore.importText(context, name, description, body)
                    }
                    val entry = result.getOrNull()
                    if (entry == null) {
                        toast(context, "创建失败：" + describeError(result.exceptionOrNull()))
                    } else {
                        skills = readSkills(context)
                        onChanged()
                        toast(context, "已创建「" + entry.name + "」")
                    }
                }
            }
        )
    }
}

/** 一个 Skill：名称 + 描述（最多两行）+ 右侧删除。 */
@Composable
private fun SkillRow(entry: SkillEntry, onDelete: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
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

/** 新建 Skill：名称 / 描述 / 正文，落成 skills/<id>/SKILL.md。 */
@Composable
private fun NewSkillDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, body: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    val canCreate = name.isNotBlank() && description.isNotBlank()

    PackageDialog(
        title = "新建 Skill",
        confirmText = "创建",
        confirmEnabled = canCreate,
        onDismiss = onDismiss,
        onConfirm = { onCreate(name.trim(), description.trim(), body) }
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            placeholder = { Text("名称（小写字母、数字、- _）", fontSize = 12.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            placeholder = { Text("一句话描述：什么时候该用它", fontSize = 12.sp) },
            singleLine = true
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            modifier = Modifier.fillMaxWidth().height(150.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            placeholder = { Text("正文：给模型看的具体步骤", fontSize = 12.sp) }
        )
    }
}

// ---- 共用壳子 / 小工具 ----

/**
 * 弹窗壳子：跟首页的 MinimalDialog 同一套观感（0.9 宽的圆角卡），但多了标题与取消/确认。
 *
 * 内容区用 weight(1f, fill = false) + 滚动：字段多的时候自己滚，不会把按钮顶出屏幕。
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
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
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
                                Palette.AccentGreen
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
 * 空行、没有冒号的行、key 为空的行直接跳过——宁可少一个请求头，也不要存进去一个坏键。
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
 * 所以单个参数里真要带空格得从别的途径写 mcp.json（这里不猜引号，避免自以为聪明地吃掉字符）。
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

/** 把异常折成一句能看的话：优先 message，没有就退回类名。 */
private fun describeError(error: Throwable?): String =
    error?.message ?: error?.javaClass?.simpleName ?: "未知错误"

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
