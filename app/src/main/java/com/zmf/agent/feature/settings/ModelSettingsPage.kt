package com.zmf.agent.feature.settings
import com.zmf.agent.core.llm.ApiDefaults
import com.zmf.agent.core.model.PermissionLevel

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.zmf.agent.capability.config.ApiConfig
import com.zmf.agent.infra.llm.ProviderConfig
import com.zmf.agent.infra.llm.ProviderStore
import com.zmf.agent.infra.api.BalanceResult
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.common.stableStatusBarPadding
import com.zmf.agent.uikit.components.salt.SaltSwitch
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.common.saltFieldColors
import com.zmf.agent.uikit.theme.isDarkSurface
import com.zmf.agent.uikit.theme.UiColors

/**
 * 「模型」页（二级）：多提供商目录 + 单个提供商的编辑表单 + 思考强度 + 执行权限 + 余额。
 *
 * 表单做成页内展开卡而非新页面：修改一个提供商仅涉及名称/密钥/地址三项，
 * 增加一层页面会使「返回」的层级语义变得含糊；展开卡使用 animateContentSize 展开，
 * 高度变化使用 Motion 弹簧，与抽屉、工具卡一致。
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
    permission: PermissionLevel,
    onPickPermission: (PermissionLevel) -> Unit,
    balance: BalanceResult?,
    balanceBusy: Boolean,
    onBalance: () -> Unit
) {
    // null = 表单收起；草稿直接就是 ProviderConfig，保存时原样回传，页面不必知道字段清单
    var draft by remember { mutableStateOf<ProviderConfig?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    // 思考强度、执行权限与余额均改为行内展开：状态提升至此，收起后再次展开不会丢失已查询到的结果
    var thinkingOpen by remember { mutableStateOf(false) }
    var permissionOpen by remember { mutableStateOf(false) }
    var balanceOpen by remember { mutableStateOf(false) }

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

                // 提供商列表：点击整行即为选中，右侧胶囊为编辑。
                // 表单紧贴被编辑的行展开：若挂到列表末尾，编辑第一条时表单会在最下方展开，
                // 点击位置与内容出现位置不一致，易被误认为无响应。
                providers.forEach { cfg ->
                    // 每一行自带一个可动画的容器：表单在这行下面展开/收起时由它撑高度
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(animationSpec = Motion.spring()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ProviderRow(
                            cfg = cfg,
                            selected = cfg.id == currentProviderId,
                            onSelect = { onSelectProvider(cfg.id) },
                            onEdit = { draft = cfg }
                        )
                        draft?.takeIf { it.id == cfg.id }?.let { editing ->
                            ProviderFormCard(
                                initial = editing,
                                canDelete = providers.size > 1,
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
                }

                // 新增（目录里选出的模板 id 还不在列表里）：表单挂在列表末尾、添加按钮上方
                draft?.takeIf { d -> providers.none { it.id == d.id } }?.let { editing ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(animationSpec = Motion.spring())
                    ) {
                        ProviderFormCard(
                            initial = editing,
                            canDelete = false,
                            candidates = models,
                            onDelete = {},
                            onCancel = { draft = null },
                            onSave = { saved ->
                                draft = null
                                onSaveProvider(saved)
                            }
                        )
                    }
                }

                // 虚线圈按钮：明确表示此处为「新增一条」而非跳转页面
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .dashedOutline(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f), 18.dp)
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

                // 思考强度：开关 + 三档共三行，宜就地展开，无需再增加一层页面
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
                                // 三档仅显示接口取值本身：中文标签与 reasoning_effort 不一致，排查时易误读
                                ApiDefaults.EFFORTS.forEach { value ->
                                    EffortRow(
                                        text = ApiConfig.effortLabel(value),
                                        selected = thinkingEnabled &&
                                            ApiConfig.effortLabel(value) == ApiConfig.effortLabel(thinkingEffort),
                                        enabled = thinkingEnabled,
                                        onClick = { onPickEffort(value) }
                                    )
                                }
                                SettingsNote(
                                    text = "此处仅为新建会话的默认开关与强度；在会话中修改只影响该会话，" +
                                        "不会影响这里的值。"
                                )
                            }
                        }
                    }
                }

                // 执行权限：与输入区浮层的权限胶囊是同一份设置，在一处改动，另一处立即跟着变
                SettingsCard {
                    Column {
                        ExpandRow(
                            title = "执行权限",
                            value = permissionTitle(permission),
                            expanded = permissionOpen,
                            onClick = { permissionOpen = !permissionOpen }
                        )
                        AnimatedVisibility(
                            visible = permissionOpen,
                            enter = expandVertically(animationSpec = Motion.spring()) + fadeIn(Motion.fadeIn()),
                            exit = shrinkVertically(animationSpec = Motion.spring()) + fadeOut(Motion.fadeOut())
                        ) {
                            Column {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                )
                                PermissionLevel.entries.forEach { level ->
                                    PermissionTierRow(
                                        title = permissionTitle(level),
                                        selected = level == permission,
                                        onClick = { onPickPermission(level) }
                                    )
                                }
                                SettingsNote(
                                    text = "此处仅为新建会话的默认档位；在会话中切换只影响该会话，" +
                                        "不会回写这里。"
                                )
                            }
                        }
                    }
                }

                SettingsCard {
                    Column {
                        ExpandRow(
                            title = "余额",
                            // 查询进行中时行尾显示进度，且整行不可点击：避免并发查询同一份数据
                            value = if (balanceBusy) "查询中…" else null,
                            expanded = balanceOpen,
                            enabled = !balanceBusy,
                            onClick = {
                                // 收起不清除结果：查询后立即收起，再次展开仍可查看上一次的结果
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
 * 因此目标 id 已被占用时**新建一条**，而非回落到既有条目：否则「添加」会变成
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
        color = if (selected) UiColors.rowSelected else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)),
        border = if (selected) {
            BorderStroke(1.5.dp, if (isDarkSurface()) Palette.DarkStroke else Palette.LightStroke)
        } else null
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
            // 有 Key 时才显示绿点：该行是「能否直接使用」的唯一提示
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (cfg.apiKey.isNotBlank()) {
                            UiColors.success
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
    // 地址为空时直接展开自定义设置：必填项若藏在折叠区，用户易误认为「保存无法点击」是故障
    var advancedOpen by remember(initial.id) { mutableStateOf(initial.baseUrl.isBlank()) }
    // 「＋ 添加模型」点开才出现输入行：默认只有列表 + 一个按钮，
    // 避免「候选 chips / 输入框 / 已添加列表」三者同时铺满屏幕。
    var addingModel by remember(initial.id) { mutableStateOf(false) }

    // 输入框配色统一由全局管理：见 uikit/common/SaltField.kt，本页不单独维护一份。
    val fieldColors = saltFieldColors()

    SettingsCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 两个分段：第三方目录模板 / 自定义 API，区别仅在于名称是否可修改
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
                    // 空态给出明确说明，而非留白让用户猜测。
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
                        // 已经加入目录的模型不重复出现在候选里，否则同一模型会在列表与候选区重复显示。
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
                    // 地址为必填项：留空保存会得到一条始终无法连接的配置
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
    // 目录为固定模板，读取一次即可，无需在每次重组时重新读取
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

/**
 * 设置项下方的说明文字。
 *
 * 与行内说明的区别：行内说明描述「这一项是什么」，此处专门交代「改动的作用范围」，
 * 二者混排会让用户误判默认值与会话内取值的优先级。
 */
@Composable
private fun SettingsNote(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    )
}

/** 分组卡片：Salt 的圆角 16dp，卡片底走 colorScheme.surface，不描边。 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        // 卡片自身裁剪：行内的矩形波纹即使铺到边界，也只会出现在 16dp 圆弧内
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
 * 使用「▾」而非「›」：点击后是在本行下方展开，而非跳转页面，使用箭头易被理解为需要进入下一层。
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
                selected -> UiColors.accentFg
                else -> onSurface
            }
        )
    }
}

/** 一行执行权限：只有档位名，选中靠加粗与右侧圆点表达。 */
@Composable
private fun PermissionTierRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .saltCardRow { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = onSurface
        )
        // 选中点：卡片底色已经说明选中，这里只补一个 8dp 的实心点，颜色跟正文走
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (selected) onSurface else Color.Transparent,
                    shape = CircleShape
                )
        )
    }
}

/** 三档的界面名：与输入区权限浮层同一套说法，两处看到的档位名必须一致。 */
private fun permissionTitle(level: PermissionLevel): String = when (level) {
    PermissionLevel.READ_ONLY -> "仅可查看"
    PermissionLevel.WORKSPACE -> "工作区内修改"
    PermissionLevel.FULL -> "完全权限"
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
        primary -> UiColors.accentFg
        else -> onSurface.copy(alpha = 0.08f)
    }
    // 绿底上使用深色文字：白字置于 #8BCF00 上对比度过低
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
    // 单选的分段控件与 DSH 的 chipActive 一致：墨底反白，选中状态可即时辨识。
    // 多选胶囊不采用此形态（一屏可能选中多个，黑色块会形成堆叠），仍用中性淡底 + 蓝字。
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = if (selected) onSurface else Color.Transparent,
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
                color = if (selected) MaterialTheme.colorScheme.surface else onSurface.copy(alpha = 0.6f),
                maxLines = 1
            )
        }
    }
}

/** 小胶囊：候选模型这类「点击一次添加一条」的动作用它，不抢视觉重心。 */
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
            color = if (result.isAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
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
 * 避免为「添加」这一个按钮引入额外依赖。
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
