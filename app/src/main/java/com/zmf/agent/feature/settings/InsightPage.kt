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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
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
                    }
                )
            }
        }
    }
}

// ---- 本会话模块 ----

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
                color = onSurface
            )
        }
        if (!session.isCurrent) {
            Text(
                text = "回到当前",
                modifier = Modifier
                    .padding(top = 4.dp)
                    .saltRowClickable(RoundedCornerShape(8.dp)) { onBackToCurrent() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = Palette.AccentGreen
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabPill(text = "当前上下文", selected = tab == 0) { onTab(0) }
            TabPill(text = "Token 统计", selected = tab == 1) { onTab(1) }
            TabPill(text = "耗时统计", selected = tab == 2) { onTab(2) }
        }
        Spacer(Modifier.height(10.dp))
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
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = tokenText(view.estimatedTokens.toLong()),
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = onSurface
        )
        Text(
            text = " / " + tokenText(view.window.toLong()) + " tokens",
            modifier = Modifier.padding(bottom = 4.dp),
            fontSize = 14.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = percentText(view.ratio),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = onSurface
        )
        Text(
            text = "上下文已用",
            modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
    }
    Spacer(Modifier.height(8.dp))
    StackedBar(
        segments = view.parts.map { it.tokens to argb(it.colorArgb) },
        total = if (view.window > 0) view.window else partsSum
    )
    Spacer(Modifier.height(8.dp))
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
            color = onSurface.copy(alpha = 0.45f)
        )
    }
    Spacer(Modifier.height(6.dp))
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
            color = onSurface.copy(alpha = 0.45f)
        )
    }
    Spacer(Modifier.height(6.dp))
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

@Composable
private fun GlobalSection(
    global: InsightGlobalView,
    range: InsightRange,
    onRange: (InsightRange) -> Unit,
    onPickSession: (InsightSessionRow) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Text(
        text = "全局统计",
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        color = onSurface,
        modifier = Modifier.padding(top = 4.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RangePill("今天", range == InsightRange.TODAY) { onRange(InsightRange.TODAY) }
        RangePill("近 7 天", range == InsightRange.WEEK) { onRange(InsightRange.WEEK) }
        RangePill("近 30 天", range == InsightRange.MONTH) { onRange(InsightRange.MONTH) }
        RangePill("全部", range == InsightRange.ALL) { onRange(InsightRange.ALL) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KpiCard(
            label = "全部会话",
            value = global.sessions.toString(),
            sub = "其中已归档 " + global.archived + " 个",
            modifier = Modifier.weight(1f)
        )
        KpiCard(
            label = "累计消耗",
            value = tokenText(global.totalTokens),
            sub = "轮次 " + global.rounds,
            modifier = Modifier.weight(1f)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

    var dayMode by remember { mutableIntStateOf(0) }
    InsightCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "近 7 天用量",
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface
            )
            TabPill(text = "Token", selected = dayMode == 0) { dayMode = 0 }
            Spacer(Modifier.width(6.dp))
            TabPill(text = "请求", selected = dayMode == 1) { dayMode = 1 }
        }
        Spacer(Modifier.height(8.dp))
        val values = global.days.map { if (dayMode == 0) it.tokens else it.requests.toLong() }
        SimpleBarChart(values = values, color = Palette.AccentGreen)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            for (day in global.days) {
                Text(
                    text = day.date.takeLast(5),
                    modifier = Modifier.weight(1f),
                    fontSize = 10.sp,
                    color = onSurface.copy(alpha = 0.35f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
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
                color = onSurface.copy(alpha = 0.45f)
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
                color = onSurface.copy(alpha = 0.45f)
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
        onPick = onPickSession
    )
}

@Composable
private fun SessionListCard(
    rows: List<InsightSessionRow>,
    onPick: (InsightSessionRow) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    val filtered = rows.filter { row ->
        val filterOk = when (filter) {
            1 -> !row.archived
            2 -> row.archived
            else -> true
        }
        filterOk && (query.isBlank() || row.title.contains(query, ignoreCase = true))
    }
    InsightCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "会话",
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface
            )
            Text(
                text = filtered.size.toString(),
                fontSize = 13.sp,
                color = onSurface.copy(alpha = 0.45f)
            )
        }
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
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
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RangePill("全部", filter == 0) { filter = 0 }
            RangePill("未归档", filter == 1) { filter = 1 }
            RangePill("归档", filter == 2) { filter = 2 }
        }
        Spacer(Modifier.height(4.dp))
        if (filtered.isEmpty()) {
            DashBlock()
        } else {
            for (row in filtered) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .saltRowClickable(RoundedCornerShape(10.dp)) { onPick(row) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(text = row.title, fontSize = 14.sp, color = onSurface)
                        Text(
                            text = row.rounds.toString() + " 轮 · " +
                                tokenText(row.tokens) +
                                (row.cacheRate?.let { " · 缓存 " + percentText(it) } ?: "") +
                                (row.costCny?.let { " · " + moneyText(it) } ?: "") +
                                " · " + relativeTime(row.updatedAt),
                            fontSize = 11.sp,
                            color = onSurface.copy(alpha = 0.45f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

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
                .padding(horizontal = 16.dp, vertical = 14.dp),
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
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(text = label, fontSize = 12.sp, color = onSurface.copy(alpha = 0.45f), maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = sub,
            fontSize = 11.sp,
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
                if (selected) Palette.AccentGreen.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
            )
            .saltRowClickable(RoundedCornerShape(10.dp), onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (selected) Palette.AccentGreen else onSurface.copy(alpha = 0.55f)
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
                if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
            )
            .saltRowClickable(RoundedCornerShape(10.dp), onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (selected) onSurface else onSurface.copy(alpha = 0.55f)
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
