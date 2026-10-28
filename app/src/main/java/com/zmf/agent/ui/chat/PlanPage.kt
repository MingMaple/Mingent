package com.zmf.agent.ui.chat

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.agent.session.PlanDoc
import com.zmf.agent.agent.session.PlanStore
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.theme.Palette

/**
 * 「计划」页：把模型交出来的**每一份**计划都摊在一条标签栏上，切着看、单独删，再决定继续改还是执行。
 *
 * 为什么是无状态 Composable：plans 与 selectedId 都由父会话持有（读了 PlanStore 之后传进来）。
 * 这一页不碰磁盘、不持有协程；删除后选中项换成谁，也由父会话决定：
 * 页面自己改 selectedId 会出现「界面选 A、父会话以为选 B」的两套真值。
 *
 * 为什么编号不写进正文：编号是排版，按下标生成；正文里的编号一旦和真实顺序错位，用户照着做就会漏一步。
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
    // 选中项兜底取最后一份（最新）：selectedId 理论上由父会话维护好，
    // 但列表刚被别处刷新、或选中的那份刚被删掉时，宁可显示最新一份，也不要白屏。
    val current = plans.firstOrNull { it.id == selectedId } ?: plans.lastOrNull()

    Surface(
        // 铺满且不透明；最外层吞掉点击，避免穿透点到下面的聊天界面
        modifier = Modifier
            .fillMaxSize()
            .blockTapsOnly(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "←",
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "计划",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (plans.isEmpty()) {
                // 没有计划就没有动作可做：底部那两个按钮整块不出现，只留返回（父会话此时会自己回主页）
                Text(
                    text = "还没有计划",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    fontSize = 14.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
            } else {
                // 计划条：横滑 + 选中项自动入视野 + 滑到头的余量就地吃掉：
                // 这一套与浏览页的网页条共用（TabStrip），两个页面的条目样式必须一模一样。
                TabStrip(
                    items = plans.map { plan ->
                        TabStripItem(
                            key = plan.id,
                            label = plan.title.ifBlank { "未命名计划" },
                            // 已消费的计划条目也画淡：和详情页里变灰的按钮是同一件事
                            muted = !plan.actionable
                        )
                    },
                    selectedKey = current?.id,
                    onSelect = onSelect,
                    onClose = onDelete
                )
                Spacer(Modifier.height(10.dp))

                if (current != null) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                    ) {
                        Text(
                            text = current.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = onSurface
                        )
                        Spacer(Modifier.height(10.dp))
                        current.steps.forEachIndexed { index, step ->
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
                                    color = Palette.AccentGreen
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
                        if (current.note.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = current.note,
                                modifier = Modifier.fillMaxWidth(),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = onSurface.copy(alpha = 0.45f)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // 按钮变灰时说明原因：只把按钮画灰，用户会以为是 App 坏了
                    val hint = statusHint(current.status)
                    if (hint.isNotEmpty()) {
                        Text(
                            text = hint,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 2.dp),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = onSurface.copy(alpha = 0.5f)
                        )
                    }

                    // 底部固定：步骤再长也不把按钮顶出屏幕；导航栏 padding 留在这里，滚动区不吃它
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 只有「刚交上来还没处理」的那份能点：执行过 / 点过继续修改 / 被继续对话废弃
                        // 的计划一律灰掉（废弃的计划还能点，模型就直接开跑了）
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
 * 底部按钮：主按钮吃 Salt 绿实心，次按钮吃 8% 中性底（Salt 观感里不做白描边）。
 *
 * 绿底上用深色字：白字压在 #8BCF00 上对比度太低（同 ModelSettingsPage 的 Pill）。
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
    // 灰态：两种按钮都退成同一种淡底淡字，表示「这一页现在没有可执行的动作」
    val background = when {
        !enabled -> onSurface.copy(alpha = 0.05f)
        primary -> Palette.AccentGreen
        else -> onSurface.copy(alpha = 0.08f)
    }
    val textColor = when {
        !enabled -> onSurface.copy(alpha = 0.3f)
        primary -> Palette.DarkBackground
        else -> onSurface.copy(alpha = 0.8f)
    }
    Surface(
        modifier = modifier
            .height(44.dp)
            // clickable(enabled = false) 连涟漪都不给：点了毫无反应的按钮才像「坏了」，
            // 这里要的是「就是不能点」
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
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

/** 按钮为什么是灰的：按状态给一句话，别让用户以为界面卡住了。 */
private fun statusHint(status: String): String = when (status) {
    PlanStore.STATUS_APPROVED -> "该计划已执行完毕，不可重复执行。如需再次执行，请让模型重新提交一份计划。"
    PlanStore.STATUS_EDITED -> "该计划已转入修订流程。请在对话中说明修改要求，模型将重新提交计划。"
    PlanStore.STATUS_ABANDONED -> "对话已继续其他事项，该计划已自动作废。如需执行，请让模型重新提交一份计划。"
    else -> ""
}
