package com.zmf.agent.ui.settings

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zmf.agent.agent.memory.MemoryHit
import com.zmf.agent.agent.memory.MemoryState
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.theme.Palette
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 「记忆」页（二级）：概览 / 检索 / 维护 / 导出四块。
 *
 * 为什么是无状态 Composable：所有数据都在 [MemoryState] 里，动作通过回调交给上层（上层再调 MemoryAdmin）。
 * 这样这一页不持有协程、不持有 Context，也不存在「页面拆掉了命令还在跑」的半死状态；
 * 代价是每次动作都会整份替换 state，所以 MemoryAdmin 每次都会带上一份概览（见那边的注释）。
 *
 * 视觉口径照 Salt Player：22dp 圆角大卡、底色用 colorScheme.surface、不描边、强调色只用 Palette.AccentGreen；
 * 卡片内的说明一律不做（师傅们说的「无解释小字」），只有数据本身和必须写明的操作结果。
 */
@Composable
fun MemoryPage(
    onBack: () -> Unit,
    state: MemoryState,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onRunCycle: () -> Unit,
    onExport: () -> Unit,
) {
    // 进页面刷一次：概览 + 体检要跑两个 CLI 才有数，不能每帧重算（同统计页 / 数据页的做法）
    LaunchedEffect(Unit) { onRefresh() }

    // 输入框自己的草稿：回车才交给 onSearch。逐字上抛会让每个按键都变成一次内核调用，
    // 而内核每次检索都会追加一条 access 事件（真跑一次写一次库，不能当输入法用）。
    var draft by remember { mutableStateOf(state.query) }
    // 全文弹窗的正文：命中行和 briefing 都只显示两行，展开走这里
    var detail by remember { mutableStateOf<String?>(null) }
    var doctorOpen by remember { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface

    Surface(
        // 铺满且不透明；最外层吞掉点击，避免穿透点到下面那一页
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
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "←",
                    modifier = Modifier
                        // 圆形按压：返回是孤立字形，方形波纹会露角
                        .saltRowClickable(RoundedCornerShape(50)) { onBack() }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "记忆",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // busy 一行盖住四块：它们被同一次动作一起替换，分开显示会给出「有的块在跑、有的块停了」的错觉
            if (state.busy) {
                Text(
                    text = "处理中…",
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp),
                    fontSize = 13.sp,
                    color = Palette.Neutral
                )
            }
            if (state.error.isNotEmpty()) {
                // 内核原文照抄，不翻译不截断：可能是 python3 缺失、库缺失、退出码 2 的 stderr
                Text(
                    text = state.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
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
                CycleCard(state, onRunCycle)
                ExportCard(state, onExport)
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
            modifier = Modifier.padding(vertical = 4.dp),
            color = onSurface.copy(alpha = 0.06f)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .saltRowClickable(RoundedCornerShape(0.dp)) { onToggleDoctor() }
                .padding(vertical = 4.dp),
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
                color = if (state.doctorOk) Palette.AccentGreen else MaterialTheme.colorScheme.error
            )
            Text(
                text = if (doctorOpen) "▴" else "▾",
                modifier = Modifier.padding(start = 6.dp),
                fontSize = 11.sp,
                color = onSurface.copy(alpha = 0.35f)
            )
        }
        if (doctorOpen) {
            state.doctorLines.forEach { line ->
                Text(
                    text = line,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    fontSize = 11.sp,
                    color = onSurface.copy(alpha = 0.5f)
                )
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
        MemorySearchField(
            value = draft,
            onValueChange = onDraftChange,
            onSearch = { onSearch(draft) }
        )
        if (state.query.isNotEmpty()) {
            val noHit = state.hits.isEmpty()
            Spacer(Modifier.height(8.dp))
            if (state.briefing.isNotEmpty()) {
                // 一条都没命中时，briefing 就是这次检索**唯一**的结果说明（查了什么、范围、为什么空），
                // 再按两行截断等于把它掐在半句话上（用户截图里那句「命中 0 条…」就是这里省略掉的）。
                // 所以只有「下面还有命中条目」时才收成两行、点开看全文。
                Text(
                    text = state.briefing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (noHit) Modifier
                            else Modifier.saltRowClickable(RoundedCornerShape(0.dp)) { onOpenDetail(state.briefing) }
                        )
                        .padding(vertical = 2.dp),
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

@Composable
private fun CycleCard(state: MemoryState, onRunCycle: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    MemoryCard(title = "维护") {
        ActionRow(text = "运行维护扫描", enabled = !state.busy, onClick = onRunCycle)
        val cycle = state.cycle
        if (cycle != null) {
            MemoryRow(label = "扫描", value = cycle.scanned.toString())
            MemoryRow(label = "归档", value = cycle.archived.toString())
            if (cycle.batches.isEmpty()) {
                MemoryRow(label = "待巩固批次", value = "0")
            } else {
                // 每个批次一行：界面只报数，不做「合不合得成」的判断——那要读完整报告，见 RULES §3.3
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
                    .padding(top = 6.dp),
                fontSize = 11.sp,
                color = onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun ExportCard(state: MemoryState, onExport: () -> Unit) {
    MemoryCard(title = "导出") {
        ActionRow(text = "导出记忆视图", enabled = !state.busy, onClick = onExport)
        if (state.exportPath.isNotEmpty()) {
            // 结果先说人话：路径是 proot 里的 /sdcard/…，单看那一行根本判断不了到底导出成没成
            Text(
                text = "已导出 " + state.exportLines + " 行",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                fontSize = 13.sp,
                color = Palette.AccentGreen
            )
            Text(
                text = state.exportPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Palette.Neutral
            )
        }
    }
}

// ---------------------------------------------------------------- 小件

/** 卡片：Salt 的 22dp 圆角大块，底色 surface，不描边（同数据页 / 上下文页）。 */
@Composable
private fun MemoryCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        // 卡片自己裁一刀：内部行的波纹是矩形，不裁会在 22dp 圆角处露方角
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/** 一行「左 label / 右 value」，行高按卡片内距收紧。 */
@Composable
private fun MemoryRow(label: String, value: String) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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

/** 一行可点的动作（绿字，不描边、不做按钮底色）。busy 时降透明度但仍然可见。 */
@Composable
private fun ActionRow(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .saltRowClickable(RoundedCornerShape(0.dp), enabled = enabled) { onClick() }
            .padding(vertical = 6.dp),
        fontSize = 14.sp,
        // 这张卡片里唯一能按的东西：它是「强调动作」，用 AccentGreen；Neutral 留给值、路径这类次要文字
        color = Palette.AccentGreen.copy(alpha = if (enabled) 1f else 0.4f)
    )
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
                cursorBrush = SolidColor(Palette.AccentGreen),
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
            .saltRowClickable(RoundedCornerShape(0.dp)) { onOpenDetail(hit.text) }
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Palette.AccentGreen.copy(alpha = 0.16f)
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
 * 那两处都是 private，跨文件引用就得改别人的文件；这里照着观感重画一份，不动它们。
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
        // 负数＝时钟回拨（内核与手机的时钟不同步时会出现），别显示「-3 分钟前」
        minutes < 1L -> "刚刚"
        minutes < 60L -> minutes.toString() + " 分钟前"
        minutes < 60L * 24L -> (minutes / 60L).toString() + " 小时前"
        minutes < 60L * 24L * 7L -> (minutes / (60L * 24L)).toString() + " 天前"
        else -> DateTimeFormatter.ofPattern("MM-dd").format(instant.atZone(ZoneId.systemDefault()))
    }
}
