package com.zmf.agent.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.theme.Motion
import com.zmf.agent.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 「上下文洞察」页的唯一数据源。
 *
 * 全部字段都带默认值：父会话还没接线的字段先落 0 / 空串，页面自己会把 0 显示成「—」，
 * 不会因为缺一项就崩或显示 NaN。
 */
data class ContextInsight(
    /** 估算 token 占窗口的比例（0~1），水位条与档位色都按它取。 */
    val ratio: Float = 0f,
    val estimatedTokens: Int = 0,
    /** 模型上下文窗口大小；0 表示未知，界面显示「—」。 */
    val window: Int = 0,
    /** 已被摘要覆盖的消息条数。 */
    val coveredMessages: Int = 0,
    /** 会话消息总条数。 */
    val totalMessages: Int = 0,
    /** 提示词正文（不含摘要）。 */
    val systemTokens: Int = 0,
    /** 会话摘要（拼在 system 里的一段）。 */
    val summaryTokens: Int = 0,
    val toolTokens: Int = 0,
    val userTokens: Int = 0,
    val assistantTokens: Int = 0,
    val toolResultTokens: Int = 0,
    /** 本会话累计：输入 tokens / 输出 tokens / 请求次数。 */
    val sessionPromptTokens: Int = 0,
    val sessionCompletionTokens: Int = 0,
    val sessionRequests: Int = 0,
    /** 今日累计：输入 tokens / 输出 tokens / 请求次数。 */
    val todayPrompt: Int = 0,
    val todayCompletion: Int = 0,
    val todayRequests: Int = 0,
    /** 全局累计：输入 tokens / 输出 tokens / 请求次数。 */
    val globalPromptTokens: Int = 0,
    val globalCompletionTokens: Int = 0,
    val globalRequests: Int = 0,
    /** 会话首末消息的时间差，与模型/工具耗时无关。 */
    val activeMillis: Long = 0,
    /** 模型实际耗时合计；与 [toolMillis] 一起作为耗时占比的分母来源。 */
    val modelMillis: Long = 0,
    /** 工具实际耗时合计。 */
    val toolMillis: Long = 0,
    val outputTokens: Int = 0,
    /** 最近各次请求的上下文估算 tokens，趋势柱状图按它绘制。 */
    val trend: List<Int> = emptyList(),
    /** 对话轮数。 */
    val rounds: Int = 0,
    /** Agent 累计执行步数。 */
    val steps: Int = 0,
    /** 上一轮回复的模型耗时与输出 tokens（0 = 没有记录，速度显示「—」）。 */
    val lastTurnModelMillis: Long = 0,
    val lastTurnOutputTokens: Int = 0,
    /** 本会话第一条消息的时间（0 = 还没有消息）。 */
    val sessionFirstAt: Long = 0,
    val firstUsedDate: String = "",
    val lastUsedAt: Long = 0
)

/**
 * 「上下文洞察」页：一屏说清「模型现在拿到多少、都由什么组成、花了多少、多快」。
 *
 * 每个数字只在一个地方出现（水位只在第 1 块、成分只在第 2 块、用量按范围分三组），
 * 不预测、不摘录摘要正文：那两件事各有专页，放这里会和真值混淆；
 * 也不放提示词编辑：提示词是输入，不是上下文现状。
 */
@Composable
fun ContextHubPage(
    onBack: () -> Unit,
    insight: ContextInsight,
    onRefresh: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 进页面刷一次：这些值要遍历消息 / 读总账，交给调用方在 IO 里算，不跟每帧重组一起跑
    LaunchedEffect(Unit) { onRefresh() }

    Surface(
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "←",
                    modifier = Modifier
                        // 圆形按压：返回是个孤立字形，方形波纹会露角
                        .saltRowClickable(RoundedCornerShape(50)) { onBack() }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    // 标题跟首页入口「上下文洞察」一致，进页面不出现第二个叫法
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
                // 1 当前上下文：水位与请求规模
                HubCard(title = "当前上下文") {
                    WaterBar(ratio = insight.ratio)
                    HubRow(
                        label = "水位",
                        value = percentText(insight.ratio),
                        valueColor = Palette.Neutral
                    )
                    HubRow(
                        label = "估算",
                        value = "约 " + tokenAmount(insight.estimatedTokens) + " / " +
                            windowAmount(insight.window) + " tokens"
                    )
                    HubRow(
                        label = "省略",
                        value = if (insight.coveredMessages > 0) {
                            insight.coveredMessages.toString() + " / 共 " +
                                insight.totalMessages.toString() + " 条（已摘要）"
                        } else {
                            "无 · 共 " + insight.totalMessages.toString() + " 条"
                        }
                    )
                    HubRow(label = "口径", value = "消息 + 摘要")
                }

                // 2 上下文成分：一条六段占比条 + 六行图例
                HubCard(title = "上下文成分") {
                    val composition = compositionParts(insight)
                    val compositionTotal = composition.sumOf { it.second }
                    if (compositionTotal <= 0) {
                        DashLine()
                    } else {
                        CompositionBar(segments = composition.map { it.third to it.second })
                        Spacer(Modifier.height(4.dp))
                        composition.forEach { part ->
                            LegendRow(
                                label = part.first,
                                tokens = part.second,
                                total = compositionTotal,
                                color = part.third
                            )
                        }
                    }
                }

                // 3 消耗：本会话 / 今日 / 全局各只报一次
                HubCard(title = "消耗") {
                    GroupLabel("本会话")
                    HubRow(label = "输入", value = tokenAmount(insight.sessionPromptTokens))
                    HubRow(label = "输出", value = tokenAmount(insight.sessionCompletionTokens))
                    HubRow(label = "请求", value = insight.sessionRequests.toString() + " 次")

                    GroupLabel("今日")
                    HubRow(label = "输入", value = tokenAmount(insight.todayPrompt))
                    HubRow(label = "输出", value = tokenAmount(insight.todayCompletion))
                    HubRow(label = "请求", value = insight.todayRequests.toString() + " 次")

                    GroupLabel("全局累计")
                    HubRow(
                        label = "tokens",
                        value = tokenAmount(
                            insight.globalPromptTokens + insight.globalCompletionTokens
                        )
                    )
                    HubRow(label = "请求", value = insight.globalRequests.toString() + " 次")
                }

                // 4 耗时：活跃时长 + 模型 / 工具占比 + 输出速度
                HubCard(title = "耗时") {
                    // 活跃时长来自会话首末消息时间差，跟 modelMillis 无关，始终照实显示
                    HubRow(
                        label = "活跃时长",
                        value = if (insight.activeMillis > 0L) {
                            durationText(insight.activeMillis)
                        } else {
                            "—"
                        }
                    )
                    // 占比的分母用「AI 耗时合计」：拿对话跨度（活跃时长）当分母，
                    // 模型那几秒永远是 0.0%，等于没显示
                    val aiMillis = insight.modelMillis + insight.toolMillis
                    if (aiMillis <= 0L) {
                        // 没记到时间：逐行给「—」而不是编一个 0；占比条也一并省掉，
                        // 画一条空轨道会让人以为是 0% 而不是「没有数据」
                        HubRow(label = "AI 耗时合计", value = "—")
                        HubRow(label = "模型", value = "—")
                        HubRow(label = "工具", value = "—")
                        HubRow(label = "上次输出速度", value = "—")
                    } else {
                        HubRow(label = "AI 耗时合计", value = durationText(aiMillis))
                        ShareRow(label = "模型", millis = insight.modelMillis, active = aiMillis)
                        ShareRow(label = "工具", millis = insight.toolMillis, active = aiMillis)
                        HubRow(
                            label = "上次输出速度",
                            // 只按「上一轮」算：累计口径在旧会话里没有历史，会算出上万 tok/s
                            value = if (insight.lastTurnModelMillis > 0L &&
                                insight.lastTurnOutputTokens > 0
                            ) {
                                (insight.lastTurnOutputTokens * 1000.0 / insight.lastTurnModelMillis)
                                    .roundToLong().toString() + " tok/s"
                            } else {
                                "—"
                            }
                        )
                    }
                }

                // 5 趋势：最近 ≤20 次请求的输入 token
                HubCard(title = "趋势") {
                    val trend = insight.trend.takeLast(TREND_LIMIT)
                    if (trend.size < 2) {
                        DashLine()
                    } else {
                        TrendChart(values = trend, window = insight.window)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "第 1 轮",
                                modifier = Modifier.weight(1f),
                                fontSize = 12.sp,
                                color = onSurface.copy(alpha = 0.45f)
                            )
                            Text(
                                text = "第 " + trend.size + " 轮",
                                fontSize = 12.sp,
                                color = onSurface.copy(alpha = 0.45f)
                            )
                        }
                    }
                }

                // 6 状态：本会话规模与全局使用时间分开列（四个数字混在一组时，读者分不清哪个是本会话的）
                HubCard(title = "状态") {
                    GroupLabel("本会话")
                    HubRow(label = "对话轮数", value = insight.rounds.toString() + " 轮")
                    HubRow(label = "累计步数", value = insight.steps.toString() + " 步")
                    HubRow(
                        label = "首次提问",
                        value = if (insight.sessionFirstAt > 0L) dateOnly(insight.sessionFirstAt) else "—"
                    )
                    GroupLabel("全局")
                    HubRow(label = "首次使用", value = insight.firstUsedDate.ifEmpty { "—" })
                    HubRow(label = "最近使用", value = relativeTime(insight.lastUsedAt))
                }
            }
        }
    }
}

/** 趋势柱最多画这么多根：再多就挤成一片，看不出来形状。 */
private const val TREND_LIMIT = 20

/** 卡片：Salt 的圆角大块，底色 surface，不描边。 */
@Composable
private fun HubCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        // 卡片自己裁一刀：行内波纹是矩形，不裁的话波纹会在 16dp 圆角处露出方角
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
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
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/** 一行「左 label / 右 value」，行距照统计页的 StatsRow，只是挪进卡片内距。 */
@Composable
private fun HubRow(label: String, value: String, valueColor: Color? = null) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = valueColor ?: onSurface.copy(alpha = 0.45f)
        )
    }
}

/** 卡片内的分组小标题（本会话 / 今日 / 全局累计）。 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 8.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    )
}

/** 整块没有数据时的唯一一行：不写原因，只给「—」。 */
@Composable
private fun DashLine() {
    Text(
        text = "—",
        modifier = Modifier.padding(vertical = 6.dp),
        fontSize = 15.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    )
}

/**
 * 水位档取色：水位越高越警示，分三档取值。
 *
 * ratio 为 null（没有数据 / 分母未知）时回落描边色：宁可显示成灰，
 * 也不把「没数据」画成绿色的 0%。阈值用 90% / 70%。
 */
@Composable
private fun levelColor(ratio: Float?): Color = when {
    ratio == null -> MaterialTheme.colorScheme.outline
    ratio >= 0.90f -> Palette.LevelHigh
    ratio >= 0.70f -> Palette.LevelMid
    else -> Palette.LevelLow
}

/** 水位条：4dp 细条，色条长度弹簧跟随比例，颜色按水位档取，轨道是极淡的中性灰。 */
@Composable
private fun WaterBar(ratio: Float) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val animated by animateFloatAsState(
        targetValue = ratio.coerceIn(0f, 1f),
        animationSpec = Motion.spring(),
        label = "contextWaterBar"
    )
    // 档位色在组合期算好：Canvas 的 draw 作用域里读不到 MaterialTheme
    val barColor = levelColor(ratio)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = track, cornerRadius = radius)
        // 空对话时比例是 0，画一条 0 宽的圆角矩形没有意义，直接跳过
        if (animated > 0.001f) {
            drawRoundRect(
                color = barColor,
                size = Size(size.width * animated.coerceAtMost(1f), size.height),
                cornerRadius = radius
            )
        }
    }
}

/**
 * 六段成分条：各段按 token 数等比分宽。
 *
 * 用 clipPath 把整条裁成两端圆头，段与段之间不留缝：
 * 留缝会让各段加起来看着比实际少，跟下面图例的百分比对不上。
 */
@Composable
private fun CompositionBar(segments: List<Pair<Color, Int>>) {
    val total = segments.sumOf { it.second }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
    ) {
        if (total <= 0) return@Canvas
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    radiusX = size.height / 2f,
                    radiusY = size.height / 2f
                )
            )
        }
        clipPath(path) {
            var x = 0f
            segments.forEach { (color, tokens) ->
                val width = size.width * (tokens.toFloat() / total)
                if (width > 0f) {
                    drawRect(
                        color = color,
                        topLeft = Offset(x, 0f),
                        size = Size(width, size.height)
                    )
                }
                x += width
            }
        }
    }
}

/** 成分图例一行：左侧色点 + 名称，右侧「约 X · 百分比」。 */
@Composable
private fun LegendRow(label: String, tokens: Int, total: Int, color: Color) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            fontSize = 15.sp,
            color = onSurface
        )
        Text(
            text = "约 " + tokenAmount(tokens) + " · " +
                ((tokens.toFloat() / total) * 100f).roundToInt().toString() + "%",
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
    }
}

/** 占比行：一行「时长 · 百分比」，下面一条细占比条，颜色按占比档取。 */
@Composable
private fun ShareRow(label: String, millis: Long, active: Long) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val share = if (active <= 0L) 0f else (millis.toFloat() / active).coerceIn(0f, 1f)
    // 档位色在组合期算好：Canvas 的 draw 作用域里不能读 MaterialTheme
    val barColor = if (active <= 0L) levelColor(null) else levelColor(share)
    HubRow(label = label, value = durationText(millis) + " · " + percentText(share))
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = onSurface.copy(alpha = 0.10f), cornerRadius = radius)
        if (share > 0.001f) {
            drawRoundRect(
                color = barColor,
                size = Size(size.width * share, size.height),
                cornerRadius = radius
            )
        }
    }
}

/**
 * 趋势柱状图：按最大值归一，圆头柱，0 值也留 2dp 让「那次请求」看得见。
 *
 * 柱色按「那一次请求的上下文水位」分档，分母是当前窗口；窗口未知（<= 0）时没有档位可判，
 * 统一回落描边色。档位色必须在组合期算好，Canvas 的 draw 作用域里读不到 MaterialTheme。
 */
@Composable
private fun TrendChart(values: List<Int>, window: Int) {
    val barColors = values.map { value ->
        levelColor(if (window > 0) value.toFloat() / window else null)
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
    ) {
        val peak = values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (values.size - 1)) / values.size)
            .coerceAtLeast(1f)
        val minHeight = 2.dp.toPx()
        values.forEachIndexed { index, value ->
            val height = (size.height * (value.toFloat() / peak))
                .coerceIn(minHeight, size.height)
            drawRoundRect(
                color = barColors[index],
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(
                    minOf(3.dp.toPx(), barWidth / 2f),
                    minOf(3.dp.toPx(), barWidth / 2f)
                )
            )
        }
    }
}

/**
 * 六段成分的固定顺序与配色。
 *
 * 顺序按「模型最先看到 → 最后看到」排；每段一个类别色（见 [Palette] 的 Ctx* 组），
 * 不再靠同一支灰的不同透明度区分：那种画法图例和色条对不上号。
 */
private fun compositionParts(insight: ContextInsight): List<Triple<String, Int, Color>> = listOf(
    Triple("提示词", insight.systemTokens, Palette.CtxSystem),
    Triple("摘要", insight.summaryTokens, Palette.CtxInject),
    Triple("工具定义", insight.toolTokens, Palette.CtxTools),
    Triple("用户消息", insight.userTokens, Palette.CtxUser),
    Triple("助手消息", insight.assistantTokens, Palette.CtxAssistant),
    Triple("工具结果", insight.toolResultTokens, Palette.CtxToolResult)
)

/** 比率百分比：一位小数，0 也写「0.0%」。 */
private fun percentText(ratio: Float): String =
    oneDecimal(ratio.coerceIn(0f, 1f) * 100.0) + "%"

/** token 数缩写：百万级 M、万级 K，都留一位小数；一万以下原样显示。 */
private fun tokenAmount(tokens: Int): String = when {
    tokens >= 1_000_000 -> oneDecimal(tokens / 1_000_000.0) + "M"
    tokens >= 10_000 -> oneDecimal(tokens / 1_000.0) + "K"
    else -> tokens.toString()
}

/** 窗口大小缩写：1.0M / 131.0K；未知窗口给「—」。 */
private fun windowAmount(window: Int): String = when {
    window <= 0 -> "—"
    window >= 1_000_000 -> oneDecimal(window / 1_000_000.0) + "M"
    window >= 1_000 -> oneDecimal(window / 1_000.0) + "K"
    else -> window.toString()
}

/** 四舍五入到一位小数，避免格式串拼接（1.0 也显示成「1.0」）。 */
private fun oneDecimal(value: Double): String =
    ((value * 10).roundToInt() / 10.0).toString()

/**
 * 时长按量级写成人话：不足 1 分钟按秒、不足 1 小时 mm:ss、再往上小时 / 天。
 *
 * 一律 mm:ss 到底的话，13 小时的会话显示成「831:48」，读数的人得自己换算，也看不出是小时级。
 */
private fun durationText(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000L
    if (totalSeconds < 60L) return totalSeconds.toString() + " 秒"
    val minutes = totalSeconds / 60L
    if (minutes < 60L) {
        val seconds = totalSeconds % 60L
        val twoDigits = if (seconds < 10L) "0" + seconds else seconds.toString()
        return minutes.toString() + ":" + twoDigits
    }
    val hours = minutes / 60L
    if (hours < 24L) return hours.toString() + " 小时 " + (minutes % 60L).toString() + " 分"
    return (hours / 24L).toString() + " 天 " + (hours % 24L).toString() + " 小时"
}

/**
 * 相对时间：近一小时按分钟、近一天按小时、近一周按天，再往前才是完整日期。
 * 0 表示没有记录，统一「—」。
 */
private fun relativeTime(millis: Long): String {
    if (millis <= 0L) return "—"
    val diff = System.currentTimeMillis() - millis
    if (diff < 0L) return dateTime(millis)
    val minutes = diff / 60_000L
    if (minutes < 1L) return "刚刚"
    if (minutes < 60L) return minutes.toString() + " 分钟前"
    val hours = minutes / 60L
    if (hours < 24L) return hours.toString() + " 小时前"
    val days = hours / 24L
    if (days < 7L) return days.toString() + " 天前"
    return dateTime(millis)
}

/** 绝对时间写法：跟项目其它时间一致，yyyy-MM-dd HH:mm + Locale.US。 */
private fun dateTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

/** 只要日期：会话首次提问这种「哪一天」的信息，带时分反而分散注意。 */
private fun dateOnly(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
