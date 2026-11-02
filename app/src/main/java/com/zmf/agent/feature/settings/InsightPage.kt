package com.zmf.agent.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.store.InsightColors
import com.zmf.agent.core.store.InsightGlobalView
import com.zmf.agent.core.store.InsightPart
import com.zmf.agent.core.store.InsightRange
import com.zmf.agent.core.store.InsightSessionRow
import com.zmf.agent.core.store.InsightSessionView
import com.zmf.agent.core.store.InsightTiming
import com.zmf.agent.core.store.LedgerComposition
import com.zmf.agent.core.store.SessionComposition
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 页面内记忆：本次 App 进程内记住全局范围（默认近 7 天）。
 *
 * 不落盘：进程被杀（后台被删除）后回到默认值；页面退出/重进仍保留本次选择。
 */
private object InsightPageMemory {
    var range: InsightRange = InsightRange.WEEK
}

/**
 * 上下文洞察页（单页）。
 *
 * 顶部是本会话模块（三张卡、左上角三个按钮切换），下面接全局统计，最下面是会话列表。
 * 点会话行会滚回顶部并把模块换成该会话；离开页面后状态复位。
 */
@Composable
fun ContextInsightPage(
    initialSessionId: String,
    currentSessionId: String,
    workspaces: List<Pair<String, String>>,
    onBack: () -> Unit,
    loadSession: (String) -> InsightSessionView,
    loadGlobal: (InsightRange) -> InsightGlobalView
) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var viewedId by remember { mutableStateOf(initialSessionId) }
    var range by remember { mutableStateOf(InsightPageMemory.range) }
    var tab by remember { mutableIntStateOf(0) }
    val global = remember(range) { loadGlobal(range) }
    val session = remember(viewedId) { loadSession(viewedId) }

    // 洞察页锁定设计字号（fontScale = 1）：系统字体调大时版式保持不变，
    // 避免窄屏或大字号下标签被挤成竖排、按钮被撑满整行。系统字号仍作用于其它页面。
    val baseDensity = LocalDensity.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(density = baseDensity.density, fontScale = 1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(scroll)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "←",
                        modifier = Modifier
                            .saltRowClickable(RoundedCornerShape(50)) { onBack() }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "上下文洞察",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SessionModule(
                        session = session,
                        tab = tab,
                        onTab = { tab = it },
                        onBackToCurrent = {
                            viewedId = currentSessionId
                            scope.launch { scroll.animateScrollTo(0) }
                        }
                    )

                    GlobalSection(
                        global = global,
                        range = range,
                        onRange = {
                            range = it
                            InsightPageMemory.range = it
                        },
                        onPickSession = { row ->
                            viewedId = row.id
                            scope.launch { scroll.animateScrollTo(0) }
                        },
                        workspaces = workspaces
                    )
                }
            }
        }
    }
}

// ---- 本会话模块 ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionModule(
    session: InsightSessionView,
    tab: Int,
    onTab: (Int) -> Unit,
    onBackToCurrent: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    InsightCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = session.title,
                modifier = Modifier.weight(1f),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!session.isCurrent) {
                Text(
                    text = "回到当前",
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(8.dp)) { onBackToCurrent() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    // 文字型的强调：深色要提亮一档，Palette.Accent 在深底上只有 3.4:1
                    color = UiColors.accentFg,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TabPill(text = "占用明细", selected = tab == 0) { onTab(0) }
            TabPill(text = "Token 统计", selected = tab == 1) { onTab(1) }
            TabPill(text = "耗时统计", selected = tab == 2) { onTab(2) }
        }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(Motion.spring()) { it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally(Motion.spring()) { -it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 4 } + fadeOut())
                }
            },
            label = "insightTab"
        ) { index ->
            // AnimatedContent 的内容槽是 BoxScope：不套 Column，多行内容会全部叠在同一个位置
            Column(Modifier.fillMaxWidth()) {
                when (index) {
                    1 -> TokenTab(session)
                    2 -> TimingTab(session)
                    else -> CurrentContextTab(session)
                }
            }
        }
    }
}

@Composable
private fun CurrentContextTab(view: InsightSessionView) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val partsSum = view.parts.sumOf { it.tokens }
    // 窄屏或系统大字号下：左侧数字组吃掉剩余宽度，右侧百分比组按自身宽度贴右，
    // 「上下文已用」绝不逐字换行；空间不足时只截断左侧的「/ N tokens」。
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text(
                text = tokenText(view.estimatedTokens.toLong()),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = " / " + tokenText(view.window.toLong()) + " tokens",
                modifier = Modifier.padding(bottom = 2.dp),
                fontSize = 12.sp,
                color = onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = percentText(view.ratio),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = "上下文已用",
                modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
                fontSize = 12.sp,
                color = onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                softWrap = false
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    StackedBar(
        segments = view.parts.map { it.tokens to argb(it.colorArgb) },
        total = if (view.window > 0) view.window else partsSum
    )
    Spacer(Modifier.height(6.dp))
    for (part in view.parts) {
        PartRow(part, partsSum)
    }
}

@Composable
private fun TokenTab(view: InsightSessionView) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Text(
            text = "缓存命中 " + (view.cacheRate?.let { percentText(it) } ?: "—"),
            fontSize = 13.sp,
            color = onSurface.copy(alpha = 0.45f),
            maxLines = 1,
            softWrap = false
        )
    }
    Spacer(Modifier.height(4.dp))
    val composition = view.tokenComposition
    if (composition == null) {
        DashBlock()
        return
    }
    val total = composition.total
    if (total <= 0) {
        DashBlock()
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        DonutRing(
            segments = compositionSegments(composition),
            size = 88.dp,
            centerTop = tokenText(total.toLong()),
            centerBottom = "总用量"
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            CompositionLegend(composition)
        }
    }
}

@Composable
private fun TimingTab(view: InsightSessionView) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Text(
            text = "输出速度 " + (view.outputSpeed?.toString()?.plus(" tok/s") ?: "—"),
            fontSize = 13.sp,
            color = onSurface.copy(alpha = 0.45f),
            maxLines = 1,
            softWrap = false
        )
    }
    Spacer(Modifier.height(4.dp))
    val timing = InsightTiming(
        ttft = view.ttftMillis,
        reasoning = view.reasoningMillis,
        text = view.textMillis,
        toolArg = view.toolArgMillis,
        toolExec = view.toolMillis,
        other = (view.activeMillis - view.ttftMillis - view.reasoningMillis - view.textMillis -
            view.toolArgMillis - view.toolMillis).coerceAtLeast(0L),
        active = view.activeMillis
    )
    if (timing.active <= 0L) {
        DashBlock()
        return
    }
    val segments = timingSegments(timing, view.requests, view.toolCalls)
    Row(verticalAlignment = Alignment.CenterVertically) {
        DonutRing(
            segments = segments.map { it.fraction to it.color },
            size = 88.dp,
            centerTop = durationText(timing.active),
            centerBottom = "活跃时长"
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            for (seg in segments) {
                LegendTwoLine(
                    label = seg.label,
                    rightTop = percentText(seg.fraction),
                    rightBottom = durationText(seg.millis) +
                        (seg.count?.let { " · " + it + " 次" } ?: ""),
                    color = seg.color
                )
            }
        }
    }
}

// ---- 全局区 ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlobalSection(
    global: InsightGlobalView,
    range: InsightRange,
    onRange: (InsightRange) -> Unit,
    onPickSession: (InsightSessionRow) -> Unit,
    workspaces: List<Pair<String, String>>
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Text(
        text = "全局统计",
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        color = onSurface,
        modifier = Modifier.padding(top = 4.dp)
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        RangePill("今天", range == InsightRange.TODAY) { onRange(InsightRange.TODAY) }
        RangePill("近 7 天", range == InsightRange.WEEK) { onRange(InsightRange.WEEK) }
        RangePill("近 30 天", range == InsightRange.MONTH) { onRange(InsightRange.MONTH) }
        RangePill("全部", range == InsightRange.ALL) { onRange(InsightRange.ALL) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KpiCard(
            label = "全部会话",
            value = global.sessions.toString(),
            sub = "活跃 " + global.activeSessions + " · 归档 " + global.archived,
            modifier = Modifier.weight(1f)
        )
        KpiCard(
            label = "累计消耗",
            value = tokenText(global.totalTokens),
            sub = "轮次 " + global.rounds,
            modifier = Modifier.weight(1f)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KpiCard(
            label = "费用",
            value = global.costCny?.let { moneyText(it) } ?: "—",
            sub = "已计价 " + global.pricedSessions + "/" + global.sessions + " 个会话",
            modifier = Modifier.weight(1f)
        )
        KpiCard(
            label = "缓存命中",
            value = global.cacheRate?.let { percentText(it) } ?: "—",
            sub = "本范围",
            modifier = Modifier.weight(1f)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KpiCard(
            label = "工具调用",
            value = global.toolCalls.toString(),
            sub = "工具执行 " + (if (global.toolMillis > 0) durationText(global.toolMillis) else "—"),
            modifier = Modifier.weight(1f)
        )
        KpiCard(
            label = "活跃时长",
            value = if (global.activeMillis > 0) durationText(global.activeMillis) else "—",
            sub = "模型调用 " + global.requests,
            modifier = Modifier.weight(1f)
        )
    }
    }

    var dayMode by remember { mutableIntStateOf(0) }
    InsightCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "用量统计",
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface
            )
            // 分桶粒度不写在标题行：只有这一张卡会合并邻近日期，贴个「每 N 天」
            // 反而让标题行比其它卡多一截，视觉上像没对齐
            TabPill(text = "Token", selected = dayMode == 0) { dayMode = 0 }
            Spacer(Modifier.width(6.dp))
            TabPill(text = "请求", selected = dayMode == 1) { dayMode = 1 }
        }
        Spacer(Modifier.height(8.dp))
        val values = global.series.map { if (dayMode == 0) it.tokens else it.requests.toLong() }
        // 轴标签抽稀：最多 7 个，首末必留，其余等间隔
        val labelStep = ((global.series.size + 6) / 7).coerceAtLeast(1)
        val labels = global.series.mapIndexed { index, day ->
            val keep = index == 0 || index == global.series.lastIndex || index % labelStep == 0
            if (keep) day.label else ""
        }
        UsageLineChart(values = values, labels = labels, color = UiColors.accentFill)
    }

    InsightCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Token 统计",
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface
            )
            Text(
                text = "缓存命中 " + (global.cacheRate?.let { percentText(it) } ?: "—"),
                fontSize = 13.sp,
                color = onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                softWrap = false
            )
        }
        Spacer(Modifier.height(6.dp))
        val composition = global.composition
        if (composition == null || composition.total <= 0) {
            DashBlock()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutRing(
                    segments = ledgerSegments(composition),
                    size = 88.dp,
                    centerTop = tokenText(composition.total.toLong()),
                    centerBottom = "总用量"
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    LedgerLegend(composition)
                }
            }
        }
    }

    InsightCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "耗时统计",
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface
            )
            Text(
                text = "输出速度 " + (global.outputSpeed?.toString()?.plus(" tok/s") ?: "—"),
                fontSize = 13.sp,
                color = onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                softWrap = false
            )
        }
        Spacer(Modifier.height(6.dp))
        if (global.timing.active <= 0L) {
            DashBlock()
        } else {
            val segments = timingSegments(global.timing, global.requests, global.toolCalls)
            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutRing(
                    segments = segments.map { it.fraction to it.color },
                    size = 88.dp,
                    centerTop = durationText(global.timing.active),
                    centerBottom = "活跃时长"
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    for (seg in segments) {
                        LegendTwoLine(
                            label = seg.label,
                            rightTop = percentText(seg.fraction),
                            rightBottom = durationText(seg.millis) +
                                (seg.count?.let { " · " + it + " 次" } ?: ""),
                            color = seg.color
                        )
                    }
                }
            }
        }
    }

    SessionListCard(
        rows = global.rows,
        onPick = onPickSession,
        workspaces = workspaces
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionListCard(
    rows: List<InsightSessionRow>,
    onPick: (InsightSessionRow) -> Unit,
    workspaces: List<Pair<String, String>>
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var query by remember { mutableStateOf("") }
    var workspaceFilter by remember { mutableStateOf<String?>(null) }
    var archiveFilter by remember { mutableIntStateOf(0) }
    var visibleCount by remember { mutableIntStateOf(5) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(workspaceFilter, archiveFilter, query) {
        visibleCount = 5
    }
    val archiveRows = rows.filter { row ->
        when (archiveFilter) {
            1 -> !row.archived
            2 -> row.archived
            else -> true
        }
    }
    val filtered = archiveRows.filter { row ->
        val workspaceOk = when (workspaceFilter) {
            null -> true
            WORKSPACE_NONE -> row.workspaceId == null
            else -> row.workspaceId == workspaceFilter
        }
        workspaceOk && (query.isBlank() || row.title.contains(query, ignoreCase = true))
    }
    val shown = filtered.take(visibleCount)
    val archiveLabel = when (archiveFilter) {
        1 -> "隐藏已归档"
        2 -> "仅显示已归档"
        else -> "全部会话（显示已归档）"
    }
    InsightCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "会话",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface
            )
            Text(
                text = " " + archiveRows.size,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                color = onSurface.copy(alpha = 0.45f)
            )
            Box {
                Row(
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(8.dp)) { menuOpen = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = archiveLabel,
                        fontSize = 12.sp,
                        color = onSurface.copy(alpha = 0.65f),
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = " ▾",
                        fontSize = 11.sp,
                        color = onSurface.copy(alpha = 0.45f),
                        maxLines = 1,
                        softWrap = false
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp
                ) {
                    DropdownMenuItem(
                        text = { Text(prefix("全部会话", archiveFilter == 0)) },
                        onClick = { archiveFilter = 0; menuOpen = false }
                    )
                    DropdownMenuItem(
                        text = { Text(prefix("隐藏已归档", archiveFilter == 1)) },
                        onClick = { archiveFilter = 1; menuOpen = false }
                    )
                    DropdownMenuItem(
                        text = { Text(prefix("仅显示已归档", archiveFilter == 2)) },
                        onClick = { archiveFilter = 2; menuOpen = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            textStyle = TextStyle(fontSize = 13.sp, color = onSurface),
            decorationBox = { inner ->
                if (query.isBlank()) {
                    Text(
                        text = "搜索标题",
                        fontSize = 13.sp,
                        color = onSurface.copy(alpha = 0.35f)
                    )
                }
                inner()
            }
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            WorkspaceChip(
                text = "全部 " + archiveRows.size,
                selected = workspaceFilter == null
            ) { workspaceFilter = null }
            for (space in workspaces) {
                val count = archiveRows.count { it.workspaceId == space.first }
                if (count > 0) {
                    WorkspaceChip(
                        text = space.second + " " + count,
                        selected = workspaceFilter == space.first
                    ) { workspaceFilter = space.first }
                }
            }
            val noneCount = archiveRows.count { it.workspaceId == null }
            if (noneCount > 0) {
                WorkspaceChip(
                    text = "未分组 " + noneCount,
                    selected = workspaceFilter == WORKSPACE_NONE
                ) { workspaceFilter = WORKSPACE_NONE }
            }
        }
        Spacer(Modifier.height(2.dp))
        if (filtered.isEmpty()) {
            DashBlock()
        } else {
            for (row in shown) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .saltRowClickable(RoundedCornerShape(10.dp)) { onPick(row) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = row.title,
                            fontSize = 14.sp,
                            color = onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = row.rounds.toString() + " 轮 · " + tokenText(row.tokens),
                            fontSize = 11.sp,
                            color = onSurface.copy(alpha = 0.45f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = (row.cacheRate?.let { "缓存 " + percentText(it) } ?: "缓存 —") +
                                (row.costCny?.let { " · " + moneyText(it) } ?: "") +
                                " · " + relativeTime(row.updatedAt),
                            fontSize = 11.sp,
                            color = onSurface.copy(alpha = 0.35f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (filtered.size > visibleCount) {
                Text(
                    text = "展开其余 " + (filtered.size - visibleCount) + " 个会话",
                    modifier = Modifier
                        .fillMaxWidth()
                        .saltRowClickable(RoundedCornerShape(10.dp)) {
                            visibleCount += 5
                        }
                        .padding(vertical = 10.dp),
                    fontSize = 12.sp,
                    // 与下面「收起」成对：这类展开/收起是次要操作，不染强调色。
                    // 之前这里用了 Palette.Accent，一屏里只剩它在发蓝。
                    color = onSurface.copy(alpha = 0.55f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else if (visibleCount > 5) {
                Text(
                    text = "收起",
                    modifier = Modifier
                        .fillMaxWidth()
                        .saltRowClickable(RoundedCornerShape(10.dp)) {
                            visibleCount = 5
                        }
                        .padding(vertical = 10.dp),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.55f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun WorkspaceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                // 胶囊一律墨底反白：全 App 的 chip 只这一种选中形态，不分单选多选
                if (selected) onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
            )
            .saltRowClickable(RoundedCornerShape(16.dp), onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.surface else onSurface.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp)
        )
    }
}

private const val WORKSPACE_NONE = "__none__"

private fun prefix(text: String, selected: Boolean): String =
    if (selected) "✓ " + text else text

// ---- 组件与格式化 ----

private data class TimingSeg(
    val label: String,
    val millis: Long,
    val count: Int?,
    val fraction: Float,
    val color: Color
)

private fun timingSegments(
    timing: InsightTiming,
    requests: Int,
    toolCalls: Int
): List<TimingSeg> {
    val total = timing.total.coerceAtLeast(1L)
    fun seg(label: String, millis: Long, count: Int?, colorArgb: Long) = TimingSeg(
        label = label,
        millis = millis,
        count = count,
        fraction = millis.toFloat() / total,
        color = argb(colorArgb)
    )
    return listOf(
        seg("模型等待", timing.ttft, requests, InsightColors.ASSISTANT),
        seg("模型思考", timing.reasoning, requests, InsightColors.INJECT),
        seg("模型回复", timing.text, requests, InsightColors.OUTPUT),
        seg("工具参数", timing.toolArg, toolCalls, InsightColors.TOOLS),
        seg("工具执行", timing.toolExec, toolCalls, InsightColors.TOOL_RESULTS),
        seg("其他开销", timing.other, null, 0xFF9A9AA5)
    )
}

private fun timingSegments(timing: InsightTiming): List<TimingSeg> =
    timingSegments(timing, 0, 0)

private fun compositionSegments(c: SessionComposition): List<Pair<Float, Color>> {
    val total = c.total.coerceAtLeast(1)
    return listOf(
        part("系统提示词", c.system, total, InsightColors.SYSTEM),
        part("工具定义", c.tools, total, InsightColors.TOOLS),
        part("用户消息", c.user, total, InsightColors.USER),
        part("注入内容", c.inject, total, InsightColors.INJECT),
        part("技能注入", c.skills, total, InsightColors.SKILLS),
        part("助手消息", c.assistant, total, InsightColors.ASSISTANT),
        part("工具结果", c.toolResults, total, InsightColors.TOOL_RESULTS),
        part("输出", c.output, total, InsightColors.OUTPUT)
    )
}

private fun ledgerSegments(c: LedgerComposition): List<Pair<Float, Color>> {
    val total = c.total.coerceAtLeast(1)
    return listOf(
        part("系统提示词", c.system, total, InsightColors.SYSTEM),
        part("工具定义", c.tools, total, InsightColors.TOOLS),
        part("用户消息", c.user, total, InsightColors.USER),
        part("注入内容", c.inject, total, InsightColors.INJECT),
        part("技能注入", c.skills, total, InsightColors.SKILLS),
        part("助手消息", c.assistant, total, InsightColors.ASSISTANT),
        part("工具结果", c.toolResults, total, InsightColors.TOOL_RESULTS),
        part("输出", c.output, total, InsightColors.OUTPUT)
    )
}

@Composable
private fun CompositionLegend(c: SessionComposition) {
    val total = c.total.coerceAtLeast(1)
    LegendTwoLine("系统提示词", percentText(c.system.toFloat() / total), tokenOrDash(c.system), argb(InsightColors.SYSTEM))
    LegendTwoLine("工具定义", percentText(c.tools.toFloat() / total), tokenOrDash(c.tools), argb(InsightColors.TOOLS))
    LegendTwoLine("用户消息", percentText(c.user.toFloat() / total), tokenOrDash(c.user), argb(InsightColors.USER))
    LegendTwoLine("注入内容", percentText(c.inject.toFloat() / total), tokenOrDash(c.inject), argb(InsightColors.INJECT))
    LegendTwoLine("技能注入", percentText(c.skills.toFloat() / total), tokenOrDash(c.skills), argb(InsightColors.SKILLS))
    LegendTwoLine("助手消息", percentText(c.assistant.toFloat() / total), tokenOrDash(c.assistant), argb(InsightColors.ASSISTANT))
    LegendTwoLine("工具结果", percentText(c.toolResults.toFloat() / total), tokenOrDash(c.toolResults), argb(InsightColors.TOOL_RESULTS))
    LegendTwoLine("输出", percentText(c.output.toFloat() / total), tokenOrDash(c.output), argb(InsightColors.OUTPUT))
}

@Composable
private fun LedgerLegend(c: LedgerComposition) {
    val total = c.total.coerceAtLeast(1)
    LegendTwoLine("系统提示词", percentText(c.system.toFloat() / total), tokenOrDash(c.system), argb(InsightColors.SYSTEM))
    LegendTwoLine("工具定义", percentText(c.tools.toFloat() / total), tokenOrDash(c.tools), argb(InsightColors.TOOLS))
    LegendTwoLine("用户消息", percentText(c.user.toFloat() / total), tokenOrDash(c.user), argb(InsightColors.USER))
    LegendTwoLine("注入内容", percentText(c.inject.toFloat() / total), tokenOrDash(c.inject), argb(InsightColors.INJECT))
    LegendTwoLine("技能注入", percentText(c.skills.toFloat() / total), tokenOrDash(c.skills), argb(InsightColors.SKILLS))
    LegendTwoLine("助手消息", percentText(c.assistant.toFloat() / total), tokenOrDash(c.assistant), argb(InsightColors.ASSISTANT))
    LegendTwoLine("工具结果", percentText(c.toolResults.toFloat() / total), tokenOrDash(c.toolResults), argb(InsightColors.TOOL_RESULTS))
    LegendTwoLine("输出", percentText(c.output.toFloat() / total), tokenOrDash(c.output), argb(InsightColors.OUTPUT))
}

private fun tokenOrDash(tokens: Int): String = if (tokens > 0) "≈" + tokenText(tokens.toLong()) else "—"

@Composable
private fun PartRow(part: InsightPart, partsSum: Int) {
    LegendRow(
        label = part.label,
        tokens = part.tokens.toLong(),
        fraction = if (partsSum > 0) part.tokens.toFloat() / partsSum else null,
        color = argb(part.colorArgb)
    )
}

@Composable
private fun InsightCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            content = content
        )
    }
}

@Composable
private fun KpiCard(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(text = label, fontSize = 11.sp, color = onSurface.copy(alpha = 0.45f), maxLines = 1)
        Spacer(Modifier.height(1.dp))
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = sub,
            fontSize = 10.sp,
            color = onSurface.copy(alpha = 0.35f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TabPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                // 与模型页分段同一口径：单选控件选中即墨底反白。
                // 数据本身的八色不动——这里只管外壳。
                if (selected) onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
            )
            .saltRowClickable(RoundedCornerShape(10.dp), onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (selected) MaterialTheme.colorScheme.surface else onSurface.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun RangePill(text: String, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                // 与同页上方三个分段、模型页分段同一套：单选即墨底反白
                if (selected) onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
            )
            .saltRowClickable(RoundedCornerShape(10.dp), onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (selected) MaterialTheme.colorScheme.surface else onSurface.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun DashBlock() {
    Text(
        text = "—",
        modifier = Modifier.padding(vertical = 10.dp),
        fontSize = 15.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    )
}

private fun argb(value: Long): Color = Color(value.toInt())

private fun part(label: String, tokens: Int, total: Int, colorArgb: Long): Pair<Float, Color> =
    (tokens.toFloat() / total) to argb(colorArgb)

private fun moneyText(value: Double): String =
    "¥" + String.format(Locale.US, "%.2f", value)

private fun relativeTime(millis: Long): String {
    if (millis <= 0L) return "—"
    val diff = (System.currentTimeMillis() - millis).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    if (minutes < 1L) return "刚刚"
    if (minutes < 60L) return minutes.toString() + " 分钟前"
    val hours = minutes / 60L
    if (hours < 24L) return hours.toString() + " 小时前"
    val days = hours / 24L
    if (days < 7L) return days.toString() + " 天前"
    return SimpleDateFormat("MM-dd", Locale.US).format(Date(millis))
}
