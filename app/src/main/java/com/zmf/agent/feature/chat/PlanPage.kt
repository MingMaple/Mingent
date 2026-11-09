package com.zmf.agent.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.core.store.PlanDoc
import com.zmf.agent.core.store.PlanStore
import com.zmf.agent.uikit.common.TopIconButton
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.common.stableStatusBarPadding
import com.zmf.agent.uikit.theme.UiColors

/**
 * 「计划」页：把模型交出的**每一份**计划排列在标签栏上，可切换查看、单独删除，再决定继续修改或执行。
 *
 * 为什么是无状态 Composable：plans 与 selectedId 均由父会话持有（读取 PlanStore 后传入）。
 * 本页不访问磁盘、不持有协程；删除后选中项由父会话决定：
 * 页面自行修改 selectedId 会出现「界面选 A、父会话认为选 B」的两套状态。
 *
 * 为什么编号不写入正文：编号属于排版，按下标生成；正文编号一旦与实际顺序错位，用户按编号操作会遗漏步骤。
 */
@Composable
fun PlanPage(
    plans: List<PlanDoc>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onContinueEdit: (String) -> Unit,
    onApprove: (String) -> Unit,
    onBack: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 每份计划的滚动位置各自保存：交叉淡化结束后旧正文被回收，切回来仍能接在原处
    val holder = rememberSaveableStateHolder()
    // 选中项兜底取最后一份（最新）：selectedId 理论上由父会话维护，
    // 但列表刚被其他位置刷新或选中项刚被删除时，显示最新一份优于空白页。
    val current = plans.firstOrNull { it.id == selectedId } ?: plans.lastOrNull()

    Surface(
        // 铺满且不透明；最外层消费点击，避免穿透到下方聊天界面
        modifier = Modifier
            .fillMaxSize()
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .stableStatusBarPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TopIconButton(glyph = "←", onClick = onBack)
                Text(
                    text = "计划",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (plans.isEmpty()) {
                // 无计划时无动作可执行：底部两个按钮整块不显示，仅保留返回（父会话此时自行返回主页）
                Text(
                    text = "还没有计划",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    fontSize = 14.sp,
                    color = UiColors.subText
                )
            } else {
                // 计划条：横滑、选中项自动进入视野、滑至尽头的余量就地消耗：
                // 与浏览页网页条共用（TabStrip），两页条目样式必须一致。
                TabStrip(
                    items = plans.map { plan ->
                        TabStripItem(
                            key = plan.id,
                            label = plan.title.ifBlank { "未命名计划" },
                            // 已消费的计划条目同样淡化：与详情页中变灰的按钮含义相同
                            muted = !plan.actionable
                        )
                    },
                    selectedKey = current?.id,
                    onSelect = onSelect,
                    onClose = onDelete
                )
                Spacer(Modifier.height(10.dp))

                if (current != null) {
                    // 正文交叉淡化：与预览页同一套过渡，切条目时下方不再硬切
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = { tabContentTransform() },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        label = "plan-body"
                    ) { plan ->
                        // 每份计划的滚动位置各自保存：切回来仍在原处
                        holder.SaveableStateProvider(plan.id) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 20.dp)
                                ) {
                                    Text(
                                        text = plan.title,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = onSurface
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    plan.steps.forEachIndexed { index, step ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = (index + 1).toString(),
                                                modifier = Modifier.width(24.dp),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = UiColors.subText
                                            )
                                            Text(
                                                text = step,
                                                modifier = Modifier.weight(1f),
                                                fontSize = 14.sp,
                                                lineHeight = 20.sp,
                                                color = onSurface
                                            )
                                        }
                                    }
                                    if (plan.note.isNotBlank()) {
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            text = plan.note,
                                            modifier = Modifier.fillMaxWidth(),
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp,
                                            color = UiColors.subText
                                        )
                                    }
                                    Spacer(Modifier.height(16.dp))
                                }

                                // 按钮变灰时说明原因：仅置灰而不说明，易被误认为故障
                                val hint = statusHint(plan.status)
                                if (hint.isNotEmpty()) {
                                    Text(
                                        text = hint,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 2.dp),
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        color = UiColors.subText
                                    )
                                }
                            }
                        }
                    }

                    // 底部固定：步骤再长也不会将按钮推出屏幕；导航栏 padding 保留在此，滚动区不占用
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 仅「刚提交且未处理」的计划可点击：已执行、已点继续修改或被后续对话废弃
                        // 的计划一律置灰（已废弃计划仍可点则模型会直接开始执行）
                        PlanButton(
                            text = "继续修改",
                            primary = false,
                            enabled = current.actionable,
                            modifier = Modifier.weight(1f),
                            onClick = { onContinueEdit(current.id) }
                        )
                        PlanButton(
                            text = "同意执行",
                            primary = true,
                            enabled = current.actionable,
                            modifier = Modifier.weight(1f),
                            onClick = { onApprove(current.id) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 底部按钮：主按钮使用强调填充面与反色文字，次按钮使用 6% 中性底色。
 *
 * 涟漪按 16dp 圆角裁剪，不会在四角露出方块。
 */
@Composable
private fun PlanButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(16.dp)
    // 灰态：两种按钮均退为淡底淡字，表示「当前页面无可执行动作」
    val background = when {
        !enabled -> onSurface.copy(alpha = 0.05f)
        primary -> UiColors.accentFill
        else -> onSurface.copy(alpha = 0.06f)
    }
    val textColor = when {
        !enabled -> onSurface.copy(alpha = 0.3f)
        primary -> UiColors.onAccent
        else -> UiColors.subText
    }
    Surface(
        modifier = modifier
            .height(44.dp)
            // 不可点击时不提供涟漪：点击无反应的按钮更像故障，
            // 此处语义为「确实不可点击」
            .saltRowClickable(shape, enabled = enabled, onClick = onClick),
        shape = shape,
        color = background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = if (primary && enabled) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor
            )
        }
    }
}

/** 按钮为何置灰：按状态给出说明，避免误认为界面卡死。 */
private fun statusHint(status: String): String = when (status) {
    PlanStore.STATUS_APPROVED -> "该计划已执行完毕，不可重复执行。如需再次执行，请让模型重新提交一份计划。"
    PlanStore.STATUS_EDITED -> "该计划已转入修订流程。请在对话中说明修改要求，模型将重新提交计划。"
    PlanStore.STATUS_ABANDONED -> "对话已继续其他事项，该计划已自动作废。如需执行，请让模型重新提交一份计划。"
    else -> ""
}
