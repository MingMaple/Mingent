package com.zmf.agent.feature.settings
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.blockTapsOnly

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.capability.context.ContextEvent
import com.zmf.agent.capability.context.ContextEventKind
import com.zmf.agent.capability.context.ContextPolicy
import com.zmf.agent.capability.context.ContextSnapshot
import com.zmf.agent.capability.context.ContextStore
import com.zmf.agent.core.llm.ApiDefaults
import com.zmf.agent.infra.llm.ProviderStore
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.common.stableStatusBarPadding
import com.zmf.agent.uikit.components.salt.SaltSwitch
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// 摘要模型的「跟随当前模型」档；非空值统一编码成 `providerId|model`，
// 这样摘要请求能按所选提供商解析 Key 与端点，而不是把模型名硬发到当前端点
private const val SUMMARY_MODEL_FOLLOW = ""
/** 旧版本落盘的裸模型名不带分隔符：读到时按当前 provider 解释。 */
private const val SUMMARY_MODEL_SEPARATOR = '|'

/** 摘要模型的一个可选档；[value] 是落盘格式 `providerId|model`。 */
private data class SummaryOption(
    val value: String,
    val label: String,
    /** 旧版裸模型名是否等价于这一项（只有当前 provider 的同名模型算命中）。 */
    val legacyMatch: Boolean
)

/**
 * 「上下文配置」页：提示词（最上面）+ 摘要式滚动压缩的全部可调项。
 *
 * 提示词排在最前，因为它决定模型的身份设定；压缩仅在上下文容量不足时介入，
 * 顺序与用户调参时的思路一致。
 *
 * 压缩区不设「保存」按钮：每个开关 / 滑杆修改后立即通过 onApply 生效；
 * 整列可竖向滚动，小屏下底部说明也不会被挤出可视区域。
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
    // 总开关：关闭后下方整片置灰且不可点击
    val enabled = policy.enabled

    // 「立即压缩」一行有三态：空闲 → 压缩中… → 已更新摘要 / 压缩失败（3 秒后复原）
    var compressing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    // 用递增计数触发「3 秒后清除结果」，避免额外启动协程并管理取消
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
        // 页面铺满且不透明；最外层拦截点击，避免事件穿透到下方的聊天界面
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
                    text = "上下文配置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // 提示词是模型的输入，压缩仅决定历史的取舍方式，因此提示词置于上方
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

            // 触发比例：0.1 ~ 0.95，滑杆不绘制刻度点（steps = 0 为连续滑动），
            // 0.05 的步进由 onChange 中的 snapRatio 吸附，0.40 等位点可精确落位；
            // 显示值始终取自策略当前值，不做硬编码
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
                // 不按条数触发时该项无实际作用，一并置灰
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

            // 逐字保留：轮数是下限（上面那行），token 预算是上限；比例按 (窗口 − 输出预留) 换算
            val windowTokens = snapshot.window
            // 输入中的上限放在页级：比例行的置灰与高亮需随已输入的数值即时变化，
            // 不能等到失焦提交才刷新，否则修改后界面无反馈，易被误认为设置未生效
            var limitDraft by remember { mutableStateOf<String?>(null) }
            val typedLimit = limitDraft?.toIntOrNull()
                ?: if (limitDraft == null) policy.retainTokens else 0
            // 预算也按草稿计算：两行右侧的「≈ 多少 token」需与正在输入的数值同步，
            // 否则输入 1000 后仍显示旧的 209k
            val typedBudget = ContextStore.retainBudgetTokens(
                policy.copy(retainTokens = typedLimit),
                windowTokens
            )
            ContextSection("逐字保留（轮数为下限，token 预算为上限）")
            ContextSliderRow(
                label = "保留比例",
                // 填写绝对上限时比例不生效：此处不重复显示同一预算数字，直接说明已被覆盖
                valueText = (policy.retainRatio * 100f).roundToInt().toString() + "% · " +
                    if (typedLimit > 0) "已被上限覆盖" else retainBudgetText(typedBudget),
                value = policy.retainRatio.coerceIn(
                    ContextPolicy.MIN_RETAIN_RATIO,
                    ContextPolicy.MAX_RETAIN_RATIO
                ),
                range = ContextPolicy.MIN_RETAIN_RATIO..ContextPolicy.MAX_RETAIN_RATIO,
                // 填写绝对 token 时比例不生效，置灰并说明已被覆盖
                enabled = enabled && typedLimit <= 0,
                onChange = {
                    onApply(policy.copy(retainRatio = (it * 100f).roundToInt() / 100f))
                }
            )
            ContextTokenRow(
                label = "保留上限",
                draft = limitDraft,
                tokens = policy.retainTokens,
                budgetText = retainBudgetText(typedBudget),
                enabled = enabled,
                onDraftChange = { limitDraft = it },
                onCommit = {
                    // 直接读取页面当前草稿：失焦事件可能携带旧闭包中捕获的值，据此计算会把上一个数字写回
                    val draft = limitDraft
                    val parsed = if (draft == null) {
                        // 没编辑过这一行：保持落盘值
                        policy.retainTokens
                    } else {
                        // 已编辑：留空与填 0 同义（不设上限，回到按比例），与下面的说明一致
                        draft.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    }
                    if (parsed != policy.retainTokens) {
                        onApply(policy.copy(retainTokens = parsed))
                    }
                    // 提交后恢复显示落盘值，输入框与比例行均以实际值为准
                    limitDraft = null
                }
            )
            Text(
                text = "留空或填 0 表示不设上限，按上面的比例计算",
                modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
            // 「已按阈值回落」同样按草稿判定：输入 1000 时不应仍显示上限超出阈值的提示
            if (enabled && ContextStore.retainClamped(policy.copy(retainTokens = typedLimit), windowTokens)) {
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
            // 只列「已配置 Key」的提供商：列一个没 Key 的模型等于给用户一个必然失败的选项。
            // 自定义提供商的模型列表可能为空，此时用它上次使用的模型名（也是请求真正会发的那个）。
            val context = LocalContext.current
            // 读取提供商配置会访问磁盘，放到后台执行，避免首帧阻塞
            var summaryOptions by remember { mutableStateOf<List<SummaryOption>>(emptyList()) }
            var providersLoaded by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                summaryOptions = withContext(Dispatchers.IO) {
                    val currentId = ProviderStore.currentId(context)
                    ProviderStore.load(context)
                        .filter { it.apiKey.isNotBlank() }
                        .flatMap { provider ->
                            val models = provider.models.ifEmpty {
                                listOf(
                                    ProviderStore.modelOf(context, provider.id)
                                        .ifBlank { ApiDefaults.MODEL }
                                )
                            }
                            models.map { model ->
                                SummaryOption(
                                    value = provider.id + SUMMARY_MODEL_SEPARATOR + model,
                                    label = provider.name + " · " + model,
                                    legacyMatch = provider.id == currentId && model == policy.summaryModel
                                )
                            }
                        }
                }
                providersLoaded = true
            }
            // 固定档收进「切换模型」里折叠：默认只显示一行当前选中项，
            // 点开后才列出全部可选模型，选择后自动收起，避免一长串模型把「立即压缩」推出屏幕
            var modelsExpanded by remember { mutableStateOf(false) }
            // 折叠时右侧要能看出当前选的是哪个模型，所以先按落盘值反查一次
            val selectedOption = summaryOptions.firstOrNull {
                policy.summaryModel == it.value ||
                    policy.summaryModel.indexOf(SUMMARY_MODEL_SEPARATOR) < 0 && it.legacyMatch
            }
            ContextExpandRow(
                label = "切换模型",
                valueText = selectedOption?.label.orEmpty(),
                expanded = modelsExpanded,
                enabled = enabled && summaryOptions.isNotEmpty(),
                onClick = { modelsExpanded = !modelsExpanded }
            )
            if (modelsExpanded) {
                summaryOptions.forEach { option ->
                    ContextRadioRow(
                        label = option.label,
                        selected = policy.summaryModel == option.value ||
                            policy.summaryModel.indexOf(SUMMARY_MODEL_SEPARATOR) < 0 &&
                            option.legacyMatch,
                        enabled = enabled,
                        onClick = {
                            onApply(policy.copy(summaryModel = option.value))
                            // 选择后即收起：该操作意图已完成，保留列表只会占用纵向空间
                            modelsExpanded = false
                        }
                    )
                }
            }
            if (providersLoaded && summaryOptions.isEmpty()) {
                Text(
                    text = "没有已配置 Key 的提供商，摘要只能跟随当前模型。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.35f)
                )
            }

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

            // 有摘要时才可点击：无内容可清除时点击不产生效果
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

            // 事件列表置于最底部：它作为近期操作的旁证，不宜插入可调项之间
            ContextEventsSection(events = events)

            Text(
                text = "摘要由所选模型关闭思考生成；选「跟随当前模型」时能原样重放主请求前缀以命中缓存，" +
                    "选其它提供商则按该提供商的 Key 与端点发请求、无法复用当前会话的前缀缓存。" +
                    "失败时会退回只发最近几轮原文，不影响正常对话。",
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

/** 一行「左 label / 右开关」。整行可点（Salt Player 口径）：点行＝点开关。 */
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
            // 这一页的行不在卡片里、直接铺在页面底色上，所以显式给 16dp 圆角。
            .saltRowClickable(RoundedCornerShape(16.dp), enabled = enabled) { onChange(!checked) }
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
        // 两套值必须同时提供：仅设置启用态时，禁用态会回落到 M3 默认的 38%/12%，
        // 与自定义的启用态在灰底上区分度不足，难以判断「能否拖动」。
        //
        // 深浅各一套，因为同一个 alpha 在两种底上不是同一种观感：
        // 浅色下 50% 墨色即显得过重；深色下 45% 白色对比适中。
        // 所以浅色整体下移（68% / 40% / 14%），深色保持（90% / 45% / 14%）。
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        // steps = 0：不要 Material 那排刻度点，滑起来是连续的；
        // 离散落点仍由调用方的 snapRatio / roundToInt 在 onChange 里吸附。
        // 只压缩整体高度，Slider 内部（轨道、滑块）由 Material 自行绘制，不自行实现。
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().height(28.dp),
            enabled = enabled,
            valueRange = range,
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = onSurface.copy(alpha = if (isDark) 0.90f else 0.68f),
                activeTrackColor = onSurface.copy(alpha = if (isDark) 0.45f else 0.40f),
                inactiveTrackColor = onSurface.copy(alpha = 0.14f),
                disabledThumbColor = onSurface.copy(alpha = if (isDark) 0.28f else 0.22f),
                disabledActiveTrackColor = onSurface.copy(alpha = 0.12f),
                disabledInactiveTrackColor = onSurface.copy(alpha = 0.08f)
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
            .saltCardRow(enabled = enabled) { onClick() }
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

/** 可展开的分组行：右值是当前选中项，最右是折叠箭头；整行可点，再点一次收起。 */
@Composable
private fun ContextExpandRow(
    label: String,
    valueText: String,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltCardRow(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface.copy(alpha = if (enabled) 1f else 0.35f)
        )
        if (valueText.isNotEmpty()) {
            Text(
                text = valueText,
                fontSize = 12.sp,
                color = onSurface.copy(alpha = if (enabled) 0.45f else 0.25f)
            )
        }
        Text(
            text = if (expanded) "▴" else "▾",
            modifier = Modifier.padding(start = 8.dp),
            fontSize = 13.sp,
            color = Palette.Neutral.copy(alpha = if (enabled) 1f else 0.35f)
        )
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
            .saltCardRow(enabled = enabled) { onClick() }
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
 * 数字全部按当前策略和当前窗口计算（DeepSeek 为 1M 窗口，默认 0.40 → 约 419k），不固定。
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
    // 倒序：新记录在上；同时兼容调用方传入乱序数据
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

/**
 * 单条事件：左描述 / 右下时间 / 最右状态色点，整行不可点。
 *
 * 时间和描述分两行：横排时描述只剩约 200dp，「折叠 58 条（分界第 58 条）」会被挤到第二行、
 * 括号里只剩「条）」，读起来像排版事故。描述独占一行后不再折行，时间退到第二行小字。
 */
@Composable
private fun ContextEventRow(event: ContextEvent) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 清除是用户主动动作，使用中性灰而非绿/红表示成败；其余按结果取色
    val statusColor = when {
        event.kind == ContextEventKind.CLEAR -> onSurface.copy(alpha = 0.35f)
        event.ok -> onSurface.copy(alpha = 0.75f)
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = eventDescription(event),
                fontSize = 15.sp,
                lineHeight = 19.sp,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = eventTimeText(event.at),
                modifier = Modifier.padding(top = 1.dp),
                fontSize = 12.sp,
                lineHeight = 15.sp,
                color = onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                softWrap = false
            )
        }
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
 * 失焦与回车都提交，用户点击其它位置不会丢失已输入的数值。
 */
@Composable
private fun ContextTokenRow(
    label: String,
    // null = 未在编辑，直接显示落盘值；非 null = 正在输入的草稿，由页面持有以便联动其它行
    draft: String?,
    tokens: Int,
    budgetText: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onCommit: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val value = draft ?: if (tokens > 0) tokens.toString() else ""
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
            color = onSurface.copy(alpha = if (enabled) 1f else 0.35f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        ContextTokenField(
            value = value,
            onValueChange = { onDraftChange(it.filter { ch -> ch.isDigit() }) },
            enabled = enabled,
            onCommit = {
                // 提交一律交给页面：是否修改、是否清除草稿均由页面按当前草稿判断
                onCommit()
            }
        )
        Text(
            text = budgetText,
            modifier = Modifier.padding(start = 8.dp),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = if (enabled) 0.45f else 0.25f),
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * 行内数字输入：仅接受数字，回车或失焦提交。
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
            // 固定宽度：TextField 的装饰层会撑满可用宽度，放进 Row 会把左边的标签
            // 和右边的预算文字挤成一字一行（2026-11-09 实测）
            .width(96.dp)
            .heightIn(min = 32.dp)
            .onFocusChanged { state ->
                // 失焦提交：数字已输入完毕却直接点击其它位置的情况比回车更常见
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
        cursorBrush = SolidColor(UiColors.accentFg),
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
