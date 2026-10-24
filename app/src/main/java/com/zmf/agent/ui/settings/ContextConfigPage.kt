package com.zmf.agent.ui.settings
import com.zmf.agent.ui.common.blockTapsOnly

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.agent.context.ContextEvent
import com.zmf.agent.agent.context.ContextEventKind
import com.zmf.agent.agent.context.ContextPolicy
import com.zmf.agent.agent.context.ContextSnapshot
import com.zmf.agent.agent.context.ContextStore
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.shell.SaltSwitch
import com.zmf.agent.ui.theme.Palette
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 摘要模型的固定档；空串表示「跟随当前模型」。 */
private const val SUMMARY_MODEL_FOLLOW = ""
private const val SUMMARY_MODEL_FIXED = "deepseek-flash"

/**
 * 「上下文配置」页：提示词（最上面）+ 摘要式滚动压缩的全部可调项。
 *
 * 提示词排在最前，是因为它决定「模型是谁」；压缩只在上下文吃不下时才介入，
 * 顺序跟用户调参时的思维顺序一致。
 *
 * 压缩区照旧没有任何「保存」按钮——每个开关 / 滑杆改完立刻 onApply 出去，改一点就生效一点；
 * 整列可竖向滚动，小屏也不会把底部说明挤没。
 */

@Composable
fun ContextConfigPage(
    onBack: () -> Unit,
    extraPrompt: String,
    overridePrompt: String,
    onSaveExtra: (String) -> Unit,
    onSaveOverride: (String) -> Unit,
    policy: ContextPolicy,
    snapshot: ContextSnapshot,
    summary: String,
    onApply: (ContextPolicy) -> Unit,
    onSummarize: ((Boolean) -> Unit) -> Unit,
    onClearSummary: () -> Unit,
    onRefresh: () -> Unit,
    events: List<ContextEvent>
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 总局：关掉之后下面整片置灰不可点
    val enabled = policy.enabled

    // 「立即压缩」一行有三态：空闲 → 压缩中… → 已更新摘要 / 压缩失败（3 秒后复原）
    var compressing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    // 用一个递增的计数触发「3 秒后清掉结果」，避免另起协程还要管取消
    var resultTick by remember { mutableStateOf(0) }
    LaunchedEffect(resultTick) {
        if (resultTick > 0) {
            delay(3000)
            result = ""
        }
    }

    // 进页面刷新一次：水位、已折叠条数这些要遍历消息才有的值不跟每帧重组一起算
    LaunchedEffect(Unit) { onRefresh() }

    Surface(
        // 页面铺满且不透明；最外层吞掉点击，避免穿透点到下面的聊天界面
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
                    text = "上下文配置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // 提示词是喂给模型的输入，压缩只决定历史怎么取舍，所以提示词排在上面
            PromptSection(
                extraPrompt = extraPrompt,
                overridePrompt = overridePrompt,
                onSaveExtra = onSaveExtra,
                onSaveOverride = onSaveOverride
            )

            ContextSection("压缩")
            ContextToggleRow(
                label = "启用压缩",
                checked = enabled,
                enabled = true,
                onChange = { onApply(policy.copy(enabled = it)) }
            )

            // 触发比例：0.1 ~ 0.95，滑杆不再画刻度点（steps = 0 是连续滑），
            // 0.05 的步进由 onChange 里的 snapRatio 吸附，0.40 这类位点照样精确落下；
            // 显示的始终是策略里的当前值，不写死
            ContextSliderRow(
                label = "触发比例",
                valueText = String.format(Locale.US, "%.2f", policy.triggerRatio) + " × 窗口",
                value = policy.triggerRatio.coerceIn(0.1f, 0.95f),
                range = 0.1f..0.95f,
                hint = triggerHint(policy.triggerRatio, snapshot.window),
                enabled = enabled,
                onChange = { onApply(policy.copy(triggerRatio = snapRatio(it))) }
            )

            ContextToggleRow(
                label = "按条数触发",
                checked = policy.triggerByCount,
                enabled = enabled,
                onChange = { onApply(policy.copy(triggerByCount = it)) }
            )

            // 条数阈值：1 ~ 20 的整数
            ContextSliderRow(
                label = "条数阈值",
                valueText = policy.triggerCount.toString() + " 条",
                value = policy.triggerCount.toFloat().coerceIn(1f, 20f),
                range = 1f..20f,
                // 不按条数触发时这一项没有意义，一起置灰
                enabled = enabled && policy.triggerByCount,
                onChange = {
                    onApply(policy.copy(triggerCount = it.roundToInt().coerceIn(1, 20)))
                }
            )

            // 保留最近原文：2 ~ 20 轮
            ContextSliderRow(
                label = "保留最近原文",
                valueText = policy.fallbackKeepRounds.toString() + " 轮",
                value = policy.fallbackKeepRounds.toFloat().coerceIn(2f, 20f),
                range = 2f..20f,
                enabled = enabled,
                onChange = {
                    onApply(policy.copy(fallbackKeepRounds = it.roundToInt().coerceIn(2, 20)))
                }
            )

            // 逐字保留：轮数是下限（上面那行），token 预算是上限；比例按 (窗口 − 输出预留) 换算，对齐 DSH
            val windowTokens = snapshot.window
            val retainBudget = ContextStore.retainBudgetTokens(policy, windowTokens)
            ContextSection("逐字保留（轮数为下限，token 预算为上限）")
            ContextSliderRow(
                label = "保留比例",
                valueText = (policy.retainRatio * 100f).roundToInt().toString() + "% · " +
                    retainBudgetText(retainBudget),
                value = policy.retainRatio.coerceIn(
                    ContextPolicy.MIN_RETAIN_RATIO,
                    ContextPolicy.MAX_RETAIN_RATIO
                ),
                range = ContextPolicy.MIN_RETAIN_RATIO..ContextPolicy.MAX_RETAIN_RATIO,
                // 填了绝对 token 时比例不再生效，置灰说明它被覆盖了
                enabled = enabled && policy.retainTokens <= 0,
                onChange = {
                    onApply(policy.copy(retainRatio = (it * 100f).roundToInt() / 100f))
                }
            )
            ContextTokenRow(
                label = "保留上限",
                tokens = policy.retainTokens,
                budgetText = retainBudgetText(retainBudget),
                enabled = enabled,
                onCommit = { value -> onApply(policy.copy(retainTokens = value)) }
            )
            if (enabled && ContextStore.retainClamped(policy, windowTokens)) {
                Text(
                    text = "已按阈值回落",
                    modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            ContextSection("摘要模型")
            ContextRadioRow(
                label = "跟随当前模型",
                selected = policy.summaryModel == SUMMARY_MODEL_FOLLOW,
                enabled = enabled,
                onClick = { onApply(policy.copy(summaryModel = SUMMARY_MODEL_FOLLOW)) }
            )
            ContextRadioRow(
                label = SUMMARY_MODEL_FIXED,
                selected = policy.summaryModel == SUMMARY_MODEL_FIXED,
                enabled = enabled,
                onClick = { onApply(policy.copy(summaryModel = SUMMARY_MODEL_FIXED)) }
            )

            ContextActionRow(
                label = "立即压缩",
                valueText = when {
                    compressing -> "压缩中…"
                    result.isNotEmpty() -> result
                    else -> ""
                },
                valueColor = if (result == "压缩失败") {
                    MaterialTheme.colorScheme.error
                } else {
                    onSurface.copy(alpha = 0.55f)
                },
                enabled = enabled && !compressing,
                onClick = {
                    compressing = true
                    result = ""
                    onSummarize { ok ->
                        compressing = false
                        result = if (ok) "已更新摘要" else "压缩失败"
                        resultTick++
                    }
                }
            )

            // 有摘要才亮：没有可清的，点了也是空动作
            ContextActionRow(
                label = "清除摘要",
                valueText = "",
                valueColor = onSurface.copy(alpha = 0.55f),
                enabled = enabled && summary.isNotEmpty() && !compressing,
                onClick = { onClearSummary() }
            )

            ContextStatusLine(
                "约 " + snapshot.estimatedTokens + " / " + snapshot.window +
                    " tokens（" + String.format(Locale.US, "%.1f", snapshot.ratio * 100) + "%）"
            )
            ContextStatusLine(
                "已折叠 " + snapshot.coveredMessages + " / " + snapshot.totalMessages + " 条"
            )
            ContextStatusLine(
                "上条摘要：" + if (snapshot.hasSummary) summaryTime(snapshot.lastSummaryAt) else "—"
            )

            // 事件列表放最底部：它是「刚刚发生过什么」的旁证，不该挤在可调的开关中间
            ContextEventsSection(events = events)

            Text(
                text = "摘要由当前模型关闭思考生成；失败时会退回只发最近几轮原文，不影响正常对话。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                fontSize = 11.sp,
                color = onSurface.copy(alpha = 0.35f)
            )
        }
    }
}

/** 分段小标题：跟统计页一样只有一行淡色小字，不做卡片。 */
@Composable
private fun ContextSection(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    )
}

/** 一行「左 label / 右开关」。 */
@Composable
private fun ContextToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface.copy(alpha = if (enabled) 1f else 0.35f)
        )
        SaltSwitch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled
        )
    }
}

/** 一行「左 label / 右数值」加下面一整条滑杆；行距、字号沿用 StatsRow。 */
@Composable
private fun ContextSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    hint: String = "",
    enabled: Boolean,
    onChange: (Float) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                color = onSurface.copy(alpha = if (enabled) 1f else 0.35f)
            )
            Text(
                text = valueText,
                fontSize = 12.sp,
                color = onSurface.copy(alpha = if (enabled) 0.45f else 0.25f)
            )
        }
        // steps = 0：不要 Material 那排刻度点，滑起来是连续的；
        // 离散落点仍由调用方的 snapRatio / roundToInt 在 onChange 里吸附。
        // 只压整体高度，Slider 内部（轨道、滑块）交给 Material 自己画，不自绘。
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().height(28.dp),
            enabled = enabled,
            valueRange = range,
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = Palette.AccentGreen,
                activeTrackColor = Palette.AccentGreen,
                inactiveTrackColor = onSurface.copy(alpha = 0.12f)
            )
        )
        if (hint.isNotEmpty()) {
            Text(
                text = hint,
                modifier = Modifier.padding(bottom = 2.dp),
                fontSize = 11.sp,
                color = onSurface.copy(alpha = if (enabled) 0.35f else 0.2f)
            )
        }
    }
}

/** 一行单选：选中时右侧打一个勾；整行可点。 */
@Composable
private fun ContextRadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltRowClickable(RoundedCornerShape(0.dp), enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface.copy(alpha = if (enabled) 1f else 0.35f)
        )
        if (selected) {
            Text(
                text = "✓",
                fontSize = 13.sp,
                color = Palette.Neutral.copy(alpha = if (enabled) 1f else 0.35f)
            )
        }
    }
}

/** 整行可点的动作行（立即压缩 / 清除摘要），右侧 value 放状态文字。 */
@Composable
private fun ContextActionRow(
    label: String,
    valueText: String,
    valueColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltRowClickable(RoundedCornerShape(0.dp), enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface.copy(alpha = if (enabled) 1f else 0.35f)
        )
        if (valueText.isNotEmpty()) {
            Text(text = valueText, fontSize = 12.sp, color = valueColor)
        }
    }
}

/** 状态区的一行小字（灰）。 */
@Composable
private fun ContextStatusLine(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    )
}

/** 滑杆给的浮点值对齐到 0.05 的整数倍（Float 步进，0.40 这种位点能精确落下）。 */
private fun snapRatio(value: Float): Float = (value * 20f).roundToInt() / 20f

/** 摘要时间：yyyy-MM-dd HH:mm（跟项目里其它时间一样用 Locale.US 的格式）。 */
private fun summaryTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

/**
 * 触发比例的说明行：窗口 × 比例 ≈ 多少 token 才触发摘要。
 * 数字全部按当前策略和当前窗口算（DeepSeek 是 1M 窗口，默认 0.40 → 约 419k），不写死。
 */
private fun triggerHint(ratio: Float, window: Int): String {
    val percent = String.format(Locale.US, "%.2f", ratio)
    if (window <= 0) return "窗口 × " + percent + " 触发摘要"
    val tokens = (window * ratio).roundToInt()
    return contextWindowLabel(window) + " 窗口 × " + percent + " ≈ " +
        contextTokenAmount(tokens) + " tokens 触发摘要"
}

/** token 数缩写：百万级用 M、万级用 k；一万以下原样显示（跟统计页同一套口径）。 */
private fun contextTokenAmount(tokens: Int): String = when {
    tokens >= 1_000_000 -> (tokens / 1_000_000).toString() + "M"
    tokens >= 10_000 -> (tokens / 1_000).toString() + "k"
    else -> tokens.toString()
}

/** 窗口大小缩写：1M / 131k。 */
private fun contextWindowLabel(window: Int): String = when {
    window >= 1_000_000 -> (window / 1_000_000).toString() + "M"
    window >= 1_000 -> (window / 1_000).toString() + "k"
    else -> window.toString()
}

/** 最多显示这么多条事件：配置页给人看的是「最近发生了什么」，不是完整日志。 */
private const val EVENT_LIMIT = 8

/**
 * 「摘要与压缩事件」只读列表。
 *
 * 不提供「更多 / 清空」：这页是可调项，历史翻页属于日志页的职责。
 */
@Composable
private fun ContextEventsSection(events: List<ContextEvent>) {
    ContextSection("摘要与压缩事件")
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 倒序：新的在上；同时兜住调用方传乱序的情况
    val recent = events.sortedByDescending { it.at }.take(EVENT_LIMIT)
    if (recent.isEmpty()) {
        Text(
            text = "—",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            fontSize = 15.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
    } else {
        recent.forEach { event -> ContextEventRow(event = event) }
    }
}

/** 单条事件：左时间 / 中描述 / 右状态色点，整行不可点。 */
@Composable
private fun ContextEventRow(event: ContextEvent) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 清除是用户主动动作，中性灰不用绿/红表态；其余按成败取色
    val statusColor = when {
        event.kind == ContextEventKind.CLEAR -> onSurface.copy(alpha = 0.35f)
        event.ok -> Palette.AccentGreen
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = eventTimeText(event.at),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.45f)
        )
        Text(
            text = eventDescription(event),
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            fontSize = 15.sp,
            color = onSurface
        )
        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .size(8.dp)
                .background(statusColor, CircleShape)
        )
    }
}

/** 事件描述：四种类型固定四句，数值全部取事件自身字段，不在界面里重算。 */
private fun eventDescription(event: ContextEvent): String = when (event.kind) {
    ContextEventKind.COMPRESS -> if (event.ok) {
        "立即压缩 · 折叠 " + event.foldedMessages + " 条（分界第 " + event.boundary + " 条）"
    } else {
        "压缩失败 · 已退回最近 " + event.keptRounds + " 轮"
    }
    ContextEventKind.FALLBACK -> "兜底：只发最近 " + event.keptRounds + " 轮原文"
    ContextEventKind.CLEAR -> "清除摘要"
}

/** 事件时间：同一年内只给「MM-dd HH:mm」，年份对排查无意义还占宽度。 */
private fun eventTimeText(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))

/** 保留预算的显示文案：窗口未知时预算算不出来，给「—」而不是 0。 */
private fun retainBudgetText(budget: Int): String =
    if (budget <= 0) "—" else "≈ " + contextTokenAmount(budget) + " tokens"

/**
 * 绝对保留预算一行：行内窄输入框，空 / 0 = 回到按比例。
 *
 * 不弹窗：这一组只有「轮数 / 比例 / 上限」三个数，弹一个窗反而要来回对照；
 * 失焦与回车都提交，用户点别处不会丢掉刚输的数字。
 */
@Composable
private fun ContextTokenRow(
    label: String,
    tokens: Int,
    budgetText: String,
    enabled: Boolean,
    onCommit: (Int) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var draft by remember(tokens) { mutableStateOf(if (tokens > 0) tokens.toString() else "") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface.copy(alpha = if (enabled) 1f else 0.35f)
        )
        ContextTokenField(
            value = draft,
            onValueChange = { draft = it.filter { ch -> ch.isDigit() } },
            enabled = enabled,
            onCommit = {
                val parsed = (draft.trim().toIntOrNull() ?: 0).coerceAtLeast(0)
                if (parsed != tokens) onCommit(parsed)
            }
        )
        Text(
            text = budgetText,
            modifier = Modifier.padding(start = 8.dp),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = if (enabled) 0.45f else 0.25f)
        )
    }
}

/**
 * 行内数字输入：只吃数字，回车或失焦提交。
 *
 * 用 BasicTextField 自绘（同提示词输入框）：这一页没有描边元素，套 OutlinedTextField 会带出 Material 的边框。
 */
@Composable
private fun ContextTokenField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onCommit: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = Modifier
            .width(78.dp)
            .height(32.dp)
            .onFocusChanged { state ->
                // 失焦提交：数字已经输完却直接点走的情况比回车更常见
                if (!state.isFocused && focused) {
                    onCommit()
                    focusManager.clearFocus()
                }
                focused = state.isFocused
            },
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 13.sp,
            color = onSurface.copy(alpha = if (enabled) 1f else 0.35f)
        ),
        singleLine = true,
        cursorBrush = SolidColor(Palette.AccentGreen),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onCommit() }),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = onSurface.copy(alpha = if (enabled) 0.06f else 0.03f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = "0",
                        fontSize = 13.sp,
                        color = onSurface.copy(alpha = 0.35f)
                    )
                }
                innerTextField()
            }
        }
    )
}
