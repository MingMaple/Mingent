package com.zmf.agent.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zmf.agent.uikit.common.stableStatusBarPadding
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.capability.memory.MemoryHit
import com.zmf.agent.capability.memory.MemoryState
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.components.salt.SaltSwitch
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 导出路径的默认值：系统下载目录。PRoot 里 `/sdcard` 指向真实存储，用户可直接改这一行。 */
private const val DEFAULT_EXPORT_PATH = "/sdcard/Download"

/**
 * 「记忆」页（二级）：概览 / 检索 / 维护 / 导出四块。
 *
 * 为什么是无状态 Composable：所有数据都在 [MemoryState] 里，动作通过回调交给上层（上层再调 MemoryAdmin）。
 * 这样该页不持有协程与 Context，也不存在「页面已销毁但命令仍在执行」的残留状态；
 * 代价是每次动作都会整份替换 state，所以 MemoryAdmin 每次都会带上一份概览（见那边的注释）。
 *
 * 视觉口径照 Salt Player：16dp 圆角大卡、底色用 colorScheme.surface、不描边、强调色只用 Palette.Accent；
 * 卡片内的说明一律不做（界面上不留解释性小字），只有数据本身和必须写明的操作结果。
 */
@Composable
fun MemoryPage(
    onBack: () -> Unit,
    state: MemoryState,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onRunCycle: () -> Unit,
    onExport: (String) -> Unit,
    vectorEnabled: Boolean,
    vectorBusy: Boolean,
    vectorStatus: String?,
    vectorFailed: Boolean,
    onVectorToggle: (Boolean) -> Unit,
    onVectorPanelOpened: () -> Unit,
) {
    // 进入页面刷新一次：概览与体检需执行两个 CLI 才能取得数据，不能每帧重算（同统计页 / 数据页的做法）
    LaunchedEffect(Unit) {
        onRefresh()
        // 临时状态只在本次停留期间有效，重进页面回到默认注释
        onVectorPanelOpened()
    }

    // 输入框自身的草稿：回车时才交给 onSearch。逐字上抛会让每次按键都触发一次内核调用，
    // 而内核每次检索都会追加一条 access 事件（因此不能按输入法的频率触发）。
    var draft by remember { mutableStateOf(state.query) }
    // 全文弹窗的正文：命中行和 briefing 都只显示两行，展开走这里
    var detail by remember { mutableStateOf<String?>(null) }
    var doctorOpen by remember { mutableStateOf(false) }
    // 「维护」「导出」默认折叠；展开「维护」会同时执行一次扫描
    var cycleOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    // 导出目标目录：默认使用系统下载目录的绝对路径，用户可修改为手机中已有的任意目录
    var exportPath by remember { mutableStateOf(DEFAULT_EXPORT_PATH) }
    val onSurface = MaterialTheme.colorScheme.onSurface

    Surface(
        // 铺满且不透明；最外层拦截点击，避免事件穿透到下方的页面
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
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "←",
                    modifier = Modifier
                        // 圆形按压：返回按钮为孤立字形，方形波纹会露出直角
                        .saltRowClickable(RoundedCornerShape(50)) { onBack() }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "长期记忆",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                // 「处理中…」放在标题行最右：
                // 独立成行时，其出现与消失会使下方四张卡整体上移。
                // 放入标题行后行高不变，界面完全不动；标题行 17sp，可容纳 13sp 的提示。
                Spacer(Modifier.weight(1f))
                if (state.busy) {
                    Text(
                        text = "处理中…",
                        modifier = Modifier.padding(end = 16.dp),
                        fontSize = 13.sp,
                        color = Palette.Neutral
                    )
                }
            }
            if (state.error.isNotEmpty()) {
                // 内核原文含命令名与退出码，只放进详情弹窗；此处给可执行的说明
                Text(
                    text = "记忆服务运行失败，请稍后重试；点击可查看错误详情。",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .saltCardRow { detail = state.error },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OverviewCard(state, doctorOpen) { doctorOpen = !doctorOpen }
                SearchCard(
                    state = state,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSearch = onSearch,
                    onOpenDetail = { detail = it }
                )
                // 展开「维护」即执行一次扫描；收起仅折叠，不重复执行
                CycleCard(
                    state = state,
                    expanded = cycleOpen,
                    onToggle = {
                        cycleOpen = !cycleOpen
                        if (cycleOpen) onRunCycle()
                    }
                )
                ExportCard(
                    state = state,
                    expanded = exportOpen,
                    onToggle = { exportOpen = !exportOpen },
                    path = exportPath,
                    onPathChange = { exportPath = it },
                    onStart = { if (exportPath.isNotBlank()) onExport(exportPath.trim()) }
                )
                VectorCard(
                    enabled = vectorEnabled,
                    busy = vectorBusy,
                    status = vectorStatus,
                    failed = vectorFailed,
                    onToggle = onVectorToggle
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    detail?.let { text ->
        MemoryDialog(onDismiss = { detail = null }) {
            Text(
                text = text,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                fontSize = 13.sp,
                color = onSurface
            )
        }
    }
}

// ---------------------------------------------------------------- 四块

@Composable
private fun OverviewCard(state: MemoryState, doctorOpen: Boolean, onToggleDoctor: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val status = state.status
    MemoryCard(title = "概览") {
        MemoryRow(label = "事件行数", value = status?.eventsLines?.toString() ?: "—")
        MemoryRow(label = "节点", value = status?.nodes?.toString() ?: "—")
        MemoryRow(label = "归档", value = status?.archived?.toString() ?: "—")
        HorizontalDivider(
            // 只在线上方留 4dp：线**下面**不留白，否则「体检」行上方会出现一条既不可点、
            // 也没有涟漪的空白（波纹未覆盖该区域，视觉上呈断裂状）
            modifier = Modifier.padding(top = 4.dp),
            color = onSurface.copy(alpha = 0.06f)
        )
        // 箭头与展开动效和「上下文配置 → 提示词 → 预览」那一行完全对齐：
        // 箭头弹簧翻转 180°、内容走 expandVertically + fadeIn/shrinkVertically + fadeOut，
        // 行内距也是 top 10 / bottom 10，折叠时行底直接贴卡片下缘，波纹被下圆弧裁成半圆。
        // 只换「▾/▴」字形、直接显隐则没有过渡，与「预览」行的效果不一致。
        val arrowTurn by animateFloatAsState(
            targetValue = if (doctorOpen) 180f else 0f,
            animationSpec = Motion.spring(),
            label = "doctorArrow"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 末行（分割线以下唯一的一行）：波纹通栏矩形，下圆弧由卡片裁。
                .saltCardRow { onToggleDoctor() }
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "体检",
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                color = onSurface
            )
            Text(
                text = if (state.doctorOk) "通过" else "异常",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (state.doctorOk) onSurface else MaterialTheme.colorScheme.error
            )
            Text(
                text = "▾",
                modifier = Modifier.padding(start = 6.dp).rotate(arrowTurn),
                fontSize = 11.sp,
                color = onSurface.copy(alpha = 0.35f)
            )
        }
        AnimatedVisibility(
            visible = doctorOpen,
            enter = expandVertically(Motion.spring()) + fadeIn(Motion.fadeIn()),
            exit = shrinkVertically(Motion.spring()) + fadeOut(Motion.fadeOut())
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                state.doctorLines.forEachIndexed { index, line ->
                    Text(
                        text = line,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = if (index == 0) 0.dp else 2.dp
                            ),
                        fontSize = 11.sp,
                        color = onSurface.copy(alpha = 0.5f)
                    )
                }
                // 展开态的最后一行要离卡片下缘留出 10dp，否则文字贴边
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun SearchCard(
    state: MemoryState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    MemoryCard(title = "检索") {
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            MemorySearchField(
                value = draft,
                onValueChange = onDraftChange,
                onSearch = { onSearch(draft) }
            )
        }
        if (state.query.isNotEmpty()) {
            val noHit = state.hits.isEmpty()
            Spacer(Modifier.height(8.dp))
            if (state.briefing.isNotEmpty()) {
                // 一条都没命中时，briefing 就是这次检索**唯一**的结果说明（查了什么、范围、为什么空），
                // 再按两行截断会在半句处截断（界面上的「命中 0 条…」即由此省略）。
                // 所以只有「下面还有命中条目」时才收成两行、点开看全文。
                Text(
                    text = state.briefing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (noHit) Modifier
                            else Modifier.saltCardRow { onOpenDetail(state.briefing) }
                        )
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    fontSize = 13.sp,
                    maxLines = if (noHit) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = onSurface
                )
                if (!noHit) Spacer(Modifier.height(6.dp))
            }
            if (noHit) {
                // 内核没给 briefing 时才补这一行；给了就不在结论后面再重复一遍「没有命中」
                if (state.briefing.isEmpty()) {
                    Text(
                        text = "没有命中",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        fontSize = 13.sp,
                        color = Palette.Neutral
                    )
                }
            } else {
                state.hits.forEach { hit -> HitRow(hit, onOpenDetail) }
            }
        }
    }
}

/**
 * 「维护」：默认折叠，点击标题行右侧箭头后展开并**同时执行一次扫描**：
 * 展开后内容向下扩充，再次点击可折叠。
 *
 * 不使用一行灰字「运行维护扫描」作为按钮：深色模式下其颜色与相邻数值接近，
 * 难以辨识其可点击性。
 */
@Composable
private fun CycleCard(state: MemoryState, expanded: Boolean, onToggle: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    MemoryCard(title = "维护", expanded = expanded, onToggle = onToggle) {
        val cycle = state.cycle
        if (cycle == null) {
            Text(
                text = if (state.busy) "扫描中…" else "尚未扫描",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                fontSize = 12.sp,
                color = onSurface.copy(alpha = 0.4f)
            )
        } else {
            MemoryRow(label = "扫描", value = cycle.scanned.toString())
            MemoryRow(label = "归档", value = cycle.archived.toString())
            if (cycle.batches.isEmpty()) {
                MemoryRow(label = "待巩固批次", value = "0")
            } else {
                // 每个批次一行：界面只报告数量，不判断是否合并成功，该判断需要读取完整报告。
                cycle.batches.forEach { batch ->
                    MemoryRow(label = "待巩固批次", value = batch.ns + " × " + batch.count)
                }
            }
            if (cycle.perfume.isEmpty()) {
                MemoryRow(label = "待熏习候选", value = "0")
            } else {
                cycle.perfume.forEach { line ->
                    MemoryRow(label = "待熏习候选", value = line)
                }
            }
            Text(
                text = "巩固由对话里的模型执行",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp),
                fontSize = 11.sp,
                color = onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

/**
 * 「导出」：同样默认折叠。展开后是「路径输入框 + 右下角开始」，导出结果追加在展开区底部
 * 默认目录必须位于用户可访问的位置：导入 App 私有目录会导致导出结果无法取出。
 *
 * 为什么不在这里直接写文件：导出由内核 `export --out=<路径>` 执行，界面只负责把路径送下去。
 * 内核在 PRoot 中运行，`/sdcard` 在其根文件系统中可见，因此默认值取 `/sdcard/Download`。
 */
@Composable
private fun ExportCard(
    state: MemoryState,
    expanded: Boolean,
    onToggle: () -> Unit,
    path: String,
    onPathChange: (String) -> Unit,
    onStart: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    MemoryCard(title = "导出", expanded = expanded, onToggle = onToggle) {
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            ExportPathField(value = path, onValueChange = onPathChange)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (state.busy) "导出中…" else "开始",
                modifier = Modifier
                    .saltRowClickable(RoundedCornerShape(50), enabled = !state.busy) { onStart() }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = UiColors.accentFg.copy(alpha = if (state.busy) 0.4f else 1f)
            )
        }
        if (state.exportPath.isNotEmpty()) {
            // 结果文案沿用「体检」折叠区那一档（11sp + 40~50% 透明度），不另起一套强调色
            Text(
                text = "已导出 " + state.exportLines + " 行",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp),
                fontSize = 11.sp,
                color = onSurface.copy(alpha = 0.5f)
            )
            Text(
                text = state.exportPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

/**
 * 「向量模型」：开启时检测并安装 onnxruntime 与 numpy，状态显示在开关下方。
 *
 * 没有临时状态时按开关本身给注释：关闭说清楚**当前用的就是** BM25 + 图扩散，
 * 开启说清楚会自动装依赖、装好报「依赖正常」（见 ChatViewModel.ensureVectorRuntime）。
 * 状态存在时取代注释，重进页面后状态清空、恢复按开关生成的注释。
 */
@Composable
private fun VectorCard(
    enabled: Boolean,
    busy: Boolean,
    status: String?,
    failed: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val idleNote = if (enabled) {
        "已启用语义检索；开启时会自动准备运行环境，准备失败时回退到关键词检索。"
    } else {
        "当前使用关键词检索；开启后会先准备运行环境，再启用语义检索。"
    }
    MemoryCard(title = "向量模型") {
        // 整行可点（Salt Player 的口径）：点行与点开关都能切换，
        // 两处反馈一致：行的涟漪通栏、由卡片 16dp 圆角裁，开关本体也有自己的涟漪。
        // 开关放在行里只做展示与无障碍语义，点击同样会走到 onToggle（子节点先消费事件，
        // 不会与行点击叠加为「一次点击触发两次」）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .saltCardRow(enabled = !busy) { onToggle(!enabled) }
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "启用稠密检索",
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                color = onSurface
            )
            SaltSwitch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = !busy
            )
        }
        Text(
            text = status ?: idleNote,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp),
            fontSize = 11.sp,
            color = when {
                failed -> MaterialTheme.colorScheme.error
                status != null -> onSurface.copy(alpha = 0.5f)
                else -> onSurface.copy(alpha = 0.35f)
            }
        )
    }
}

// ---------------------------------------------------------------- 小件

/**
 * 卡片：Salt 的 16dp 圆角大块，底色 surface，不描边（同数据页 / 上下文页）。
 *
 * [expanded] / [onToggle] 非空时，标题行变成可点的一行，右侧带 ▾ / ▴ 箭头，内容跟着折叠。
 * 箭头用的是与「体检」那一行同一套字形（11sp、35% 透明度），全页只此一种开合语汇。
 */
@Composable
private fun MemoryCard(
    title: String,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        // 卡片自身裁剪：内部行的波纹为矩形，不裁剪会在 16dp 圆角处露出方角
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        // 卡片 Column **不带横向内距**：横向 16dp 由每一行自己带，
        // 可点行才能通栏、波纹被卡片 16dp 圆角裁出圆弧，与设置一级页同一套口径。
        // Column 带 horizontal=16dp 时，行只能用 saltRowClickable(12dp) 显式圆角，
        // 于是波纹缩进 16dp、四角自成圆角，与一级页的「通栏 + 卡片裁剪」存在明显差异。
        //
        // 收起时的底部留白**放进可点击行内**（tailPad）：否则卡片下缘会保留一块不可点击、
        // 也没有涟漪的圆弧空白，而该区域正是「展开」的入口：点击后内容向下扩展，
        // 否则会形成点击空白的观感。
        // 收起时行底内距 14dp、离开卡片下缘只有 0，涟漪一路盖到底并被圆角裁圆。
        val tailPad = if (onToggle != null && expanded == false) 14.dp else 6.dp
        Column(modifier = Modifier.fillMaxWidth()) {
            if (onToggle == null) {
                Text(
                    text = title,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 标题行整行可点：箭头仅 11sp，单独点击箭头命中范围过小。
                        // 波纹通栏、顶部圆弧由卡片裁剪，折叠卡片的第一行与一级页第一行一致。
                        .saltCardRow { onToggle() }
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = tailPad),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = onSurface
                    )
                    Text(
                        text = if (expanded == true) "▴" else "▾",
                        fontSize = 11.sp,
                        color = onSurface.copy(alpha = 0.35f)
                    )
                }
            }
            // 折叠时内容整体不参与组合：扫描/导出本为「点击后才执行」的动作，保留空容器会造成无数据的观感
            if (expanded == null || expanded) {
                content()
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

/** 一行「左 label / 右 value」，行高按卡片内距收紧。横向 16dp 自己带（卡片的 Column 不再带内距）。 */
@Composable
private fun MemoryRow(label: String, value: String) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            color = onSurface
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = onSurface.copy(alpha = 0.5f)
        )
    }
}

/**
 * 导出路径输入框：与检索框使用同一套自绘（6% 淡影底、Salt 绿光标），仅字号收小一档：
 * 这是一条完整的绝对路径，13sp 可在一行内多显示两三个目录层级。
 */
@Composable
private fun ExportPathField(value: String, onValueChange: (String) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = onSurface.copy(alpha = 0.06f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = onSurface),
                singleLine = true,
                cursorBrush = SolidColor(UiColors.accentFg),
                // 占位符与输入文字同格：清空后输入区不会塌成一条线
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = "/sdcard/Download",
                                fontSize = 13.sp,
                                color = onSurface.copy(alpha = 0.4f)
                            )
                        }
                        inner()
                    }
                }
            )
        }
    }
}

/**
 * 自绘检索框：不用 OutlinedTextField（聚焦会画 Material 蓝描边），这里只用 6% 淡影做底，
 * 光标走 Salt 绿；回车（IME 搜索键）才触发检索。
 */
@Composable
private fun MemorySearchField(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = onSurface.copy(alpha = 0.06f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = onSurface),
                singleLine = true,
                cursorBrush = SolidColor(UiColors.accentFg),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                // 占位符叠在输入文字同一格里：空串时输入区不会塌成一条线
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = "检索记忆",
                                fontSize = 14.sp,
                                color = onSurface.copy(alpha = 0.4f)
                            )
                        }
                        inner()
                    }
                }
            )
            if (value.isNotEmpty()) {
                Text(
                    text = "✕",
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(50)) { onValueChange("") }
                        .padding(start = 10.dp),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

/** 一条命中：kind 标签 + 分数 + 相对时间 + 两行正文（点开全文）。 */
@Composable
private fun HitRow(hit: MemoryHit, onOpenDetail: (String) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 命中条目也是卡片里的一行：波纹通栏，横向内距自己带
            .saltCardRow { onOpenDetail(hit.text) }
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            ) {
                Text(
                    text = hit.kind,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    fontSize = 10.sp,
                    color = Palette.Neutral
                )
            }
            Text(
                text = scoreText(hit.score),
                modifier = Modifier.padding(start = 8.dp),
                fontSize = 10.sp,
                color = onSurface.copy(alpha = 0.4f)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = hitTime(hit.ts),
                fontSize = 10.sp,
                color = onSurface.copy(alpha = 0.35f)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = hit.text,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = onSurface.copy(alpha = 0.9f)
        )
    }
}

/**
 * 极简弹窗：没有标题、没有解释小字，只有内容（同 ChatScreen / AppDrawer 里的 MinimalDialog）。
 *
 * 那两处均为 private，跨文件引用需要修改其它文件；此处按相同观感重新实现，不改动原实现。
 */
@Composable
private fun MemoryDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                content = content
            )
        }
    }
}

// ---------------------------------------------------------------- 格式化

/** 分数只做展示：保留两位小数，不参与任何再计算（排序与截断都由内核给）。 */
private fun scoreText(score: Double): String =
    ((score * 100).roundToInt() / 100.0).toString()

/**
 * ISO 时间（内核带时区偏移，如 2026-10-22T12:50:54+08:00）→ 相对时间。
 *
 * 用 java.time 而不是自己数字符串：内核的历史事件是 +00:00、新事件是 +08:00，
 * 按字面时间比大小会把同一天的旧记忆显示成「几小时前」。
 */
private fun hitTime(ts: String): String {
    if (ts.isBlank()) return ""
    val instant = runCatching { OffsetDateTime.parse(ts).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(ts) }.getOrNull()
        ?: return ts.take(10)
    val minutes = Duration.between(instant, Instant.now()).toMinutes()
    return when {
        // 负数表示时钟回拨（内核与手机时钟不同步时会出现），此时不显示「-3 分钟前」
        minutes < 1L -> "刚刚"
        minutes < 60L -> minutes.toString() + " 分钟前"
        minutes < 60L * 24L -> (minutes / 60L).toString() + " 小时前"
        minutes < 60L * 24L * 7L -> (minutes / (60L * 24L)).toString() + " 天前"
        else -> DateTimeFormatter.ofPattern("MM-dd").format(instant.atZone(ZoneId.systemDefault()))
    }
}
