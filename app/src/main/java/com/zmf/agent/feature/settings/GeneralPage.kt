package com.zmf.agent.feature.settings
import com.zmf.agent.core.model.BusySendMode

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
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.common.stableStatusBarPadding
import com.zmf.agent.uikit.theme.Motion
import com.zmf.agent.core.prefs.GeneralPrefs

/**
 * 设置行的固定容器高 52dp：行高 24sp，上下余量由居中分配，字号变化时不改变容器高度。
 * 选项行的内距较小，因此取 48dp。
 */
private val SettingsRowHeight = 52.dp
private val SettingsOptionRowHeight = 48.dp

/**
 * 「通用设置」页：权限、字号、繁忙时发送行为、定时任务。
 *
 * 字号采用「数值 + 上下微调箭头」，原地生效、不展开选项；繁忙行为仍采用「展开一行再选」的方式，
 * 该展开区使用弹簧动画（与抽屉一致），展开与收起不产生闪烁。
 */
@Composable
fun GeneralSettingsPage(
    onBack: () -> Unit,
    onPermission: () -> Unit,
    baseFontSp: Int,
    onBaseFontSpChange: (Int) -> Unit,
    busyMode: BusySendMode,
    onBusyModeChange: (BusySendMode) -> Unit,
    onSchedule: () -> Unit
) {
    var busyOpen by remember { mutableStateOf(false) }

    GeneralScaffold(title = "通用设置", onBack = onBack) {
        GeneralCard {
            GeneralNavRow(
                title = "权限",
                // 此处进入的是系统授权状态页，而非三档权限，因此不显示当前档位：
                // 该行随三档变化，易被误认为可在此处修改档位，而档位只在主页浮层中修改
                value = "管理",
                onClick = onPermission
            )
            GeneralDivider()
            FontSizeStepperRow(
                baseFontSp = baseFontSp,
                onBaseFontSpChange = onBaseFontSpChange
            )
        }
        GeneralCard {
            GeneralExpanderRow(
                title = "繁忙时发送行为",
                value = GeneralPrefs.busyModeLabel(busyMode),
                expanded = busyOpen,
                onClick = { busyOpen = !busyOpen }
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
        // 铺满且不透明；最外层拦截点击，避免事件穿透到下方的设置首页
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

/** 分组卡片：圆角 16dp，卡片底走 colorScheme.surface，不描边。 */
@Composable
private fun GeneralCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier) {
            content()
        }
    }
}

/** 卡片内通栏分隔线。 */
@Composable
private fun GeneralDivider() {
    HorizontalDivider(
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
            .saltCardRow() { onClick() }
            .height(SettingsRowHeight)
            .padding(horizontal = 16.dp),
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
 * 展开行：右侧是当前值 + 可翻转的「▾」。使用「▾」而非「›」，
 * 是因为该行不打开新页面，只在原地展开选项。
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
            .saltCardRow() { onClick() }
            .height(SettingsRowHeight)
            .padding(horizontal = 16.dp),
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

/** 展开区里的一个选项：选中时文字加粗并在右侧显示勾选。 */
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
            .saltCardRow() { onClick() }
            .height(SettingsOptionRowHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            // 列表项选中：文字加粗 + 右侧勾选，不改变文字颜色
            color = if (selected) onSurface else onSurface.copy(alpha = 0.75f)
        )
        if (selected) {
            Text(text = "✓", fontSize = 14.sp, color = onSurface)
        }
    }
}
