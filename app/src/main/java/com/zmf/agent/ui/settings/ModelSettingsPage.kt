package com.zmf.agent.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zmf.agent.agent.ApiConfig
import com.zmf.agent.agent.provider.ProviderConfig
import com.zmf.agent.agent.provider.ProviderStore
import com.zmf.agent.core.api.BalanceResult
import com.zmf.agent.ui.common.saltCardRow
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.shell.SaltSwitch
import com.zmf.agent.ui.theme.Motion
import com.zmf.agent.ui.theme.Palette

/**
 * 「模型」页（二级）：多提供商目录 + 单个提供商的编辑表单 + 思考强度 + 余额。
 *
 * 表单做成页内展开卡而不是新页面：改一个提供商就是名字/密钥/地址三件事，
 * 再压一层页面只会让「返回」到底回哪一层变含糊；展开卡用 animateContentSize 撑开，
 * 高度变化走 Motion 的弹簧，和抽屉、工具卡同一套手感。
 */
@Composable
fun ModelSettingsPage(
    onBack: () -> Unit,
    providers: List<ProviderConfig>,
    currentProviderId: String,
    onSelectProvider: (String) -> Unit,
    onSaveProvider: (ProviderConfig) -> Unit,
    onDeleteProvider: (String) -> Unit,
    models: List<Pair<String, String>>,
    thinkingEnabled: Boolean,
    thinkingEffort: String,
    onThinkingToggle: (Boolean) -> Unit,
    onPickEffort: (String) -> Unit,
    balance: BalanceResult?,
    balanceBusy: Boolean,
    onBalance: () -> Unit
) {
    // null = 表单收起；草稿直接就是 ProviderConfig，保存时原样回传，页面不必知道字段清单
    var draft by remember { mutableStateOf<ProviderConfig?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    // 思考强度与余额都改行内展开：状态提到这里，收起再展开不会把刚查到的结果丢掉
    var thinkingOpen by remember { mutableStateOf(false) }
    var balanceOpen by remember { mutableStateOf(false) }

    Surface(
        // 铺满且不透明；最外层吞掉点击，避免穿透点到下面的页面
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
                        .saltRowClickable(RoundedCornerShape(50)) { onBack() }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "模型",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "填入各提供商的 API 密钥即可使用其模型。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                // 提供商列表：点整行 = 选中，右侧胶囊 = 编辑
                providers.forEach { cfg ->
                    ProviderRow(
                        cfg = cfg,
                        selected = cfg.id == currentProviderId,
                        onSelect = { onSelectProvider(cfg.id) },
                        onEdit = { draft = cfg }
                    )
                }

                // 编辑表单：收起/展开时内容尺寸变化交给弹簧，不做淡入淡出（列表会跟着跳）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = Motion.spring())
                ) {
                    draft?.let { editing ->
                        ProviderFormCard(
                            initial = editing,
                            canDelete = providers.size > 1 && providers.any { it.id == editing.id },
                            candidates = models,
                            onDelete = {
                                draft = null
                                onDeleteProvider(editing.id)
                            },
                            onCancel = { draft = null },
                            onSave = { saved ->
                                draft = null
                                onSaveProvider(saved)
                            }
                        )
                    }
                }

                // 虚线圈按钮：一眼看出这里是「加一条」而不是跳页
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .dashedOutline(Palette.AccentGreen.copy(alpha = 0.5f), 18.dp)
                        .saltRowClickable(RoundedCornerShape(18.dp)) { pickerOpen = true }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "＋ 添加模型提供商",
                        fontSize = 15.sp,
                        color = Palette.Neutral
                    )
                }

                // 思考强度：开关 + 三档就三行，值得就地展开，不必再压一层页面
                SettingsCard {
                    Column {
                        ExpandRow(
                            title = "思考强度",
                            value = if (thinkingEnabled) {
                                ApiConfig.effortLabel(thinkingEffort)
                            } else {
                                "已关闭"
                            },
                            expanded = thinkingOpen,
                            onClick = { thinkingOpen = !thinkingOpen }
                        )
                        AnimatedVisibility(
                            visible = thinkingOpen,
                            enter = expandVertically(animationSpec = Motion.spring()) + fadeIn(Motion.fadeIn()),
                            exit = shrinkVertically(animationSpec = Motion.spring()) + fadeOut(Motion.fadeOut())
                        ) {
                            Column {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (thinkingEnabled) "已开启" else "已关闭",
                                        modifier = Modifier.weight(1f),
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    SaltSwitch(
                                        checked = thinkingEnabled,
                                        onCheckedChange = onThinkingToggle
                                    )
                                }
                                // 三档只显示接口取值本身：中文标签和 reasoning_effort 对不上，排查时容易看错
                                ApiConfig.EFFORTS.forEach { value ->
                                    EffortRow(
                                        text = ApiConfig.effortLabel(value),
                                        selected = thinkingEnabled &&
                                            ApiConfig.effortLabel(value) == ApiConfig.effortLabel(thinkingEffort),
                                        enabled = thinkingEnabled,
                                        onClick = { onPickEffort(value) }
                                    )
                                }
                            }
                        }
                    }
                }

                SettingsCard {
                    Column {
                        ExpandRow(
                            title = "余额",
                            // 查询在飞的时候行尾显示进度，且整行不可点：避免并发查同一份
                            value = if (balanceBusy) "查询中…" else null,
                            expanded = balanceOpen,
                            enabled = !balanceBusy,
                            onClick = {
                                // 收起不清结果：刚查完就收起，再点开还能看到上一次的数字
                                balanceOpen = !balanceOpen
                                if (balanceOpen) onBalance()
                            }
                        )
                        AnimatedVisibility(
                            visible = balanceOpen,
                            enter = expandVertically(animationSpec = Motion.spring()) + fadeIn(Motion.fadeIn()),
                            exit = shrinkVertically(animationSpec = Motion.spring()) + fadeOut(Motion.fadeOut())
                        ) {
                            Column {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                )
                                Column(modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)) {
                                    if (balanceBusy || balance == null) {
                                        BalanceText(
                                            text = "查询中…",
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                        )
                                    } else {
                                        ProviderBalanceLines(balance)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (pickerOpen) {
        CatalogPickerDialog(
            onDismiss = { pickerOpen = false },
            onPick = { template ->
                pickerOpen = false
                draft = draftFrom(template, providers)
            }
        )
    }
}

/**
 * 目录模板映射为编辑草稿。
 *
 * 同一厂商允许多份配置并存（例如两把不同的密钥，或一个直连地址与一个中转地址），
 * 因此目标 id 已被占用时**新建一条**，而不是回落到既有那条：否则「添加」会变成
 * 编辑现有配置，保存后覆盖原有内容。
 */
private fun draftFrom(template: ProviderConfig, providers: List<ProviderConfig>): ProviderConfig {
    if (providers.none { it.id == template.id }) return template
    // 克隆内置模板时同时解除 builtIn 标记：新的一份配置需要能与原配置区分命名
    // （例如「GLM-直连」与「GLM-中转」），否则名称字段会被锁定为模板名。
    return template.copy(
        id = ProviderStore.newCustomId(),
        name = uniqueProviderName(template.name, providers),
        builtIn = false
    )
}

/** 在既有配置中取一个不重复的显示名：GLM-4.6、GLM-4.6 2、GLM-4.6 3…… */
private fun uniqueProviderName(base: String, providers: List<ProviderConfig>): String {
    val used = providers.map { it.name }.toSet()
    if (base !in used) return base
    var index = 2
    while ("$base $index" in used) index++
    return "$base $index"
}

/** 单个提供商的一行：名字 + 状态点 + 编辑胶囊；选中时套一圈 Salt 绿描边。 */
@Composable
private fun ProviderRow(
    cfg: ProviderConfig,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)),
        border = if (selected) BorderStroke(1.5.dp, Palette.AccentGreen) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .saltCardRow() { onSelect() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = cfg.name,
                modifier = Modifier.widthIn(max = 200.dp),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(6.dp))
            // 有 Key 才亮绿点：这一行是「能不能直接拿去用」的唯一提示
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (cfg.apiKey.isNotBlank()) {
                            Palette.AccentGreen
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        },
                        shape = CircleShape
                    )
            )
            Spacer(Modifier.weight(1f))
            Pill(text = "编辑", onClick = onEdit)
        }
    }
}

/**
 * 编辑表单：一个 provider 一份草稿。
 *
 * [initial] 的 id 不变就不重置输入（remember 的 key 是 id），否则每敲一个字整块表单都会重排。
 */
@Composable
private fun ProviderFormCard(
    initial: ProviderConfig,
    canDelete: Boolean,
    candidates: List<Pair<String, String>>,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onSave: (ProviderConfig) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 内置模板的名字是预填死的，所以「第三方」模式只锁名字，地址仍可改
    var customMode by remember(initial.id) { mutableStateOf(!initial.builtIn) }
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var apiKey by remember(initial.id) { mutableStateOf(initial.apiKey) }
    var baseUrl by remember(initial.id) { mutableStateOf(initial.baseUrl) }
    var modelList by remember(initial.id) { mutableStateOf(initial.models) }
    var modelInput by remember(initial.id) { mutableStateOf("") }
    var keyVisible by remember(initial.id) { mutableStateOf(false) }
    // 地址为空时直接摊开自定义设置：必填项藏在折叠区里，人会以为「保存按不动」是坏了
    var advancedOpen by remember(initial.id) { mutableStateOf(initial.baseUrl.isBlank()) }
    // 「＋ 添加模型」点开才出现输入行：默认只有列表 + 一个按钮，
    // 不再让「候选 chips / 输入框 / 已添加列表」三套东西同时铺在屏幕上。
    var addingModel by remember(initial.id) { mutableStateOf(false) }

    // 输入框配色统一走这里：Material 默认把焦点边框和光标刷成 colorScheme.primary（蓝色），
    // 在这个灰阶主题里非常跳：每个可点的输入框一点下去就冒出一圈蓝。
    // 改成 onSurface 的深/浅两档，光标跟随正文色。
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = onSurface.copy(alpha = 0.45f),
        unfocusedBorderColor = onSurface.copy(alpha = 0.15f),
        focusedTextColor = onSurface,
        unfocusedTextColor = onSurface.copy(alpha = 0.9f),
        cursorColor = onSurface,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedPlaceholderColor = onSurface.copy(alpha = 0.35f),
        unfocusedPlaceholderColor = onSurface.copy(alpha = 0.35f)
    )

    SettingsCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 两个分段：第三方目录模板 / 自定义 API，区别只在于名字能不能改
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(onSurface.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                SegmentChip(
                    text = "第三方模型提供商",
                    selected = !customMode,
                    modifier = Modifier.weight(1f)
                ) { customMode = false }
                SegmentChip(
                    text = "自定义模型 API",
                    selected = customMode,
                    modifier = Modifier.weight(1f)
                ) { customMode = true }
            }

            FormLabel(text = "提供商名")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors,
                enabled = customMode,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                placeholder = { Text("例如：我的中转 API", fontSize = 13.sp) }
            )

            FormLabel(text = "API 密钥")
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                placeholder = { Text("未填写", fontSize = 13.sp) },
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    Text(
                        text = if (keyVisible) "隐藏" else "显示",
                        modifier = Modifier
                            .saltRowClickable(RoundedCornerShape(8.dp)) { keyVisible = !keyVisible }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 12.sp,
                        color = Palette.Neutral
                    )
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .saltCardRow() { advancedOpen = !advancedOpen }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (advancedOpen) "▾ 自定义设置" else "▸ 自定义设置",
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.7f)
                )
            }

            AnimatedVisibility(
                visible = advancedOpen,
                enter = expandVertically(animationSpec = Motion.spring()) + fadeIn(Motion.fadeIn()),
                exit = shrinkVertically(animationSpec = Motion.spring()) + fadeOut(Motion.fadeOut())
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormLabel(text = "API 地址")
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = fieldColors,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        placeholder = { Text("https://...", fontSize = 13.sp) }
                    )

                    FormLabel(text = "模型目录")

                    // 已添加的模型：一行一个（参考 DeepSeek 的信息结构：左边 id、右边删除），
                    // 空态给一句人话，而不是留一片空白让人猜。
                    if (modelList.isEmpty()) {
                        Text(
                            text = "还没有模型。点下面的「＋ 添加模型」加一个。",
                            fontSize = 12.sp,
                            color = onSurface.copy(alpha = 0.45f)
                        )
                    } else {
                        modelList.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(onSurface.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = model,
                                    modifier = Modifier.weight(1f),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = onSurface.copy(alpha = 0.85f)
                                )
                                Text(
                                    text = "×",
                                    modifier = Modifier
                                        .saltRowClickable(RoundedCornerShape(50)) {
                                            modelList = modelList - model
                                        }
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    fontSize = 15.sp,
                                    color = onSurface.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }

                    // 添加：默认只是一个按钮；点开才出现输入行（可一次贴多个，逗号分隔）
                    if (!addingModel) {
                        Text(
                            text = "＋ 添加模型",
                            modifier = Modifier
                                .saltRowClickable(RoundedCornerShape(8.dp)) { addingModel = true }
                                .padding(vertical = 6.dp),
                            fontSize = 13.sp,
                            color = Palette.Neutral
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = modelInput,
                                onValueChange = { modelInput = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                colors = fieldColors,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                placeholder = { Text("模型 id，多个用逗号分隔", fontSize = 13.sp) }
                            )
                            Text(
                                text = "添加",
                                modifier = Modifier
                                    .saltRowClickable(RoundedCornerShape(8.dp)) {
                                        val parts = modelInput.split(',', '\n')
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                        if (parts.isNotEmpty()) {
                                            modelList = (modelList + parts).distinct()
                                            modelInput = ""
                                            addingModel = false
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                fontSize = 13.sp,
                                color = Palette.Neutral
                            )
                        }
                        // 候选模型：只有正在添加时才露出来，作为快捷选择（平时不占版面）。
                        // 已经加进目录的不再出现在候选里，否则会出现「上面是 glm-4.6、下面又建议
                        // ＋ glm-4.6」这种重复。
                        val remaining = candidates.filter { it.first !in modelList }
                        if (remaining.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                remaining.forEach { (id, label) ->
                                    Chip(text = "＋ " + label) {
                                        modelList = (modelList + id).distinct()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canDelete) {
                    Text(
                        text = "删除",
                        modifier = Modifier
                            .saltRowClickable(RoundedCornerShape(8.dp), onClick = onDelete)
                            .padding(vertical = 8.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.weight(1f))
                Pill(text = "取消", onClick = onCancel)
                Spacer(Modifier.width(8.dp))
                Pill(
                    text = "保存",
                    primary = true,
                    // 地址必填：空着就保存等于存一条永远连不上的配置
                    enabled = baseUrl.isNotBlank(),
                    onClick = {
                        onSave(
                            initial.copy(
                                name = name.trim().ifBlank { "自定义 API" },
                                baseUrl = baseUrl.trim(),
                                apiKey = apiKey.trim(),
                                models = modelList,
                                builtIn = !customMode
                            )
                        )
                    }
                )
            }
        }
    }
}

/** 目录选择弹窗：一行一个模板，点哪行就用哪行的预填内容起一份草稿。 */
@Composable
private fun CatalogPickerDialog(
    onDismiss: () -> Unit,
    onPick: (ProviderConfig) -> Unit
) {
    // 目录是固定模板，取一次就够，没必要每次重组再问一遍
    val catalog = remember { ProviderStore.catalog() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    text = "添加模型提供商",
                    modifier = Modifier.padding(vertical = 8.dp),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                catalog.forEach { template ->
                    Text(
                        text = template.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .saltCardRow() { onPick(template) }
                            .padding(vertical = 13.dp),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** 分组卡片：Salt 的圆角 16dp，卡片底走 colorScheme.surface，不描边。 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        // 卡片自己裁一刀：行里的方波即使铺到边界，也只会出现在 16dp 的弧里
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        content()
    }
}

/**
 * 展开行：右侧只放状态值 + 会翻转的「▾」。
 *
 * 放「▾」而不是「›」：点了是在本行下面摊开，不是跳页，用箭头会让人以为要走一层。
 * 翻转角度走弹簧，和展开区的高度动画同一条时间线。
 */
@Composable
private fun ExpandRow(
    title: String,
    value: String? = null,
    expanded: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val glyphAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.spring(),
        label = "modelExpandGlyph"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltCardRow(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface.copy(alpha = if (enabled) 1f else 0.4f)
        )
        if (value != null) {
            Text(
                text = value,
                modifier = Modifier.widthIn(max = 180.dp),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.45f)
            )
        }
        Text(
            text = "▾",
            modifier = Modifier
                .padding(start = 6.dp)
                .rotate(glyphAngle),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.35f)
        )
    }
}

/** 一行强度：选中绿字 + 右侧绿点；思考关着时整行压暗且点不动，不给假按钮。 */
@Composable
private fun EffortRow(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .saltCardRow(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                !enabled -> onSurface.copy(alpha = 0.3f)
                selected -> Palette.AccentGreen
                else -> onSurface
            }
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Palette.AccentGreen, CircleShape)
            )
        }
    }
}

/** 胶囊按钮：主按钮吃 Salt 绿，次要按钮吃 6% 淡影（和 SchedulePage 的 chip 同一套观感）。 */
@Composable
private fun Pill(
    text: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val background = when {
        !enabled -> onSurface.copy(alpha = 0.06f)
        primary -> Palette.AccentGreen
        else -> onSurface.copy(alpha = 0.08f)
    }
    // 绿底上用深色字：白字压在 #8BCF00 上对比度太低
    val content = when {
        !enabled -> onSurface.copy(alpha = 0.3f)
        primary -> Palette.DarkBackground
        else -> onSurface.copy(alpha = 0.8f)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = background,
        modifier = Modifier.saltRowClickable(RoundedCornerShape(16.dp), enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            fontSize = 14.sp,
            color = content
        )
    }
}

/** 分段 chip：选中吃 Salt 绿半透明底 + 绿字，未选中透明。 */
@Composable
private fun SegmentChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = if (selected) Palette.AccentGreen.copy(alpha = 0.16f) else Color.Transparent,
        modifier = modifier.saltRowClickable(RoundedCornerShape(13.dp), onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Palette.AccentGreen else onSurface.copy(alpha = 0.6f),
                maxLines = 1
            )
        }
    }
}

/** 小胶囊：候选模型这种「点一下加一条」的动作用它，不抢视觉重心。 */
@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = onSurface.copy(alpha = 0.06f),
        modifier = Modifier.saltCardRow(onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 12.sp,
            maxLines = 1,
            color = onSurface.copy(alpha = 0.7f)
        )
    }
}

/** 表单里的小标题；不给解释性小字，只标字段名。 */
@Composable
private fun FormLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    )
}

/** 余额明细：失败一行红字，成功先报可用性再逐币种列总额/赠送/充值。 */
@Composable
private fun ProviderBalanceLines(result: BalanceResult) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    if (!result.ok) {
        BalanceText(
            text = result.error.ifBlank { "余额查询失败" },
            color = MaterialTheme.colorScheme.error
        )
    } else {
        BalanceText(
            text = if (result.isAvailable) "账户可用" else "账户不可用",
            color = if (result.isAvailable) Palette.AccentGreen else MaterialTheme.colorScheme.error
        )
        if (result.infos.isEmpty()) {
            BalanceText(text = "无余额明细", color = onSurface.copy(alpha = 0.45f))
        }
        result.infos.forEach { info ->
            BalanceText(
                text = info.currency.ifBlank { "币种未知" } +
                    " 总额 " + info.totalBalance.ifBlank { "-" } +
                    "（赠送 " + info.grantedBalance.ifBlank { "-" } +
                    " / 充值 " + info.toppedUpBalance.ifBlank { "-" } + "）",
                color = onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

/** 余额明细的一行；金额按字符串存，这里只排版不做数字格式化。 */
@Composable
private fun BalanceText(text: String, color: Color) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        fontSize = 12.sp,
        color = color
    )
}

/**
 * 虚线圆角描边：Compose 没有现成的虚线边框，这里自己画一层，
 * 免得为了「添加」这一个按钮去引额外依赖。
 */
private fun Modifier.dashedOutline(color: Color, radius: Dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
        )
    )
}
