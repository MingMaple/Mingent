package com.zmf.agent.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.shell.SaltSwitch
import com.zmf.agent.ui.theme.Motion
import com.zmf.agent.ui.theme.Palette
import com.zmf.agent.ui.theme.UiScaleStore
import kotlin.math.abs

/**
 * 「通用设置」页：权限、字号、工作代码视图、繁忙时发送行为、定时任务。
 *
 * 字号和繁忙行为用「展开一行再选」而不是再跳一层：两三个选项的页面跳来跳去更累，
 * 展开区用弹簧动画（和抽屉同一套），展开/收起不会让人觉得界面在闪。
 */
@Composable
fun GeneralSettingsPage(
    onBack: () -> Unit,
    permissionLabel: String,
    onPermission: () -> Unit,
    uiScale: Float,
    uiScaleLabel: String,
    onUiScale: (Float) -> Unit,
    busyMode: BusySendMode,
    onBusyModeChange: (BusySendMode) -> Unit,
    onSchedule: () -> Unit
) {
    // 两个展开区互斥：同一屏只摊开一个，否则页面被撑得太长，滚动位置也难找回
    var scaleOpen by remember { mutableStateOf(false) }
    var busyOpen by remember { mutableStateOf(false) }

    GeneralScaffold(title = "通用设置", onBack = onBack) {
        GeneralCard {
            GeneralNavRow(
                title = "权限",
                value = permissionLabel,
                onClick = onPermission
            )
            GeneralDivider()
            GeneralExpanderRow(
                title = "字号大小",
                value = uiScaleLabel,
                expanded = scaleOpen,
                onClick = {
                    scaleOpen = !scaleOpen
                    if (scaleOpen) busyOpen = false
                }
            )
            GeneralExpander(visible = scaleOpen) {
                // 档位由 UiScaleStore 定：界面只画它认的那几档，选出来的值一定能存回去
                UiScaleStore.STEPS.forEach { step ->
                    GeneralOptionRow(
                        text = UiScaleStore.label(step),
                        selected = abs(step - uiScale) < 0.001f,
                        onClick = {
                            onUiScale(step)
                            scaleOpen = false
                        }
                    )
                }
            }
        }
        GeneralCard {
            GeneralExpanderRow(
                title = "繁忙时发送行为",
                value = GeneralPrefs.busyModeLabel(busyMode),
                expanded = busyOpen,
                onClick = {
                    busyOpen = !busyOpen
                    if (busyOpen) scaleOpen = false
                }
            )
            GeneralExpander(visible = busyOpen) {
                BusySendMode.entries.forEach { mode ->
                    GeneralOptionRow(
                        text = GeneralPrefs.busyModeLabel(mode),
                        selected = mode == busyMode,
                        onClick = {
                            onBusyModeChange(mode)
                            busyOpen = false
                        }
                    )
                }
            }
        }
        GeneralCard {
            GeneralNavRow(title = "定时任务", onClick = onSchedule)
        }
    }
}

/** 页面外壳：不透明铺满、可纵向滚动，顶栏「← + 标题」，和二级页一致的观感。 */
@Composable
private fun GeneralScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        // 铺满且不透明；最外层吞掉点击，避免穿透到下面的设置首页
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
                    text = title,
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
                content()
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 分组卡片：圆角 22dp，卡片底走 colorScheme.surface，不描边。 */
@Composable
private fun GeneralCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

/** 卡片内的分隔线：从文字左边起，不横穿整张卡片。 */
@Composable
private fun GeneralDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    )
}

/** 跳页的行：标题 + 可选右侧值 + 「›」。 */
@Composable
private fun GeneralNavRow(
    title: String,
    value: String? = null,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltRowClickable(RoundedCornerShape(0.dp)) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface
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
            text = "›",
            modifier = Modifier.padding(start = 6.dp),
            fontSize = 16.sp,
            color = onSurface.copy(alpha = 0.35f)
        )
    }
}

/**
 * 展开行：右侧是当前值 + 会翻转的「▾」。给「▾」而不是「›」，
 * 是因为它不开新页，只在原地摊开选项。
 */
@Composable
private fun GeneralExpanderRow(
    title: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val glyphAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.spring(),
        label = "generalExpanderGlyph"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltRowClickable(RoundedCornerShape(0.dp)) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = onSurface
        )
        Text(
            text = value,
            modifier = Modifier.widthIn(max = 180.dp),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = onSurface.copy(alpha = 0.45f)
        )
        Text(
            text = "▾",
            modifier = Modifier
                .padding(start = 6.dp)
                .rotate(glyphAngle),
            fontSize = 16.sp,
            color = onSurface.copy(alpha = 0.35f)
        )
    }
}

/** 开关行：标题占满剩余宽度，开关贴右边，整行只有开关可点（和包管理页一致）。 */
@Composable
private fun GeneralSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        SaltSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 展开区外壳：竖向弹簧展开 + 淡入，收起时反过来。 */
@Composable
private fun GeneralExpander(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(animationSpec = Motion.spring()) + fadeIn(Motion.fadeIn()),
        exit = shrinkVertically(animationSpec = Motion.spring()) + fadeOut(Motion.fadeOut())
    ) {
        Column {
            GeneralDivider()
            content()
        }
    }
}

/** 展开区里的一个选项：选中吃 Salt 绿并在右侧打勾。 */
@Composable
private fun GeneralOptionRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltRowClickable(RoundedCornerShape(0.dp)) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Palette.AccentGreen else onSurface.copy(alpha = 0.75f)
        )
        if (selected) {
            Text(text = "✓", fontSize = 14.sp, color = Palette.AccentGreen)
        }
    }
}
