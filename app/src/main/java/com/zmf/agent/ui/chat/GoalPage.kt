package com.zmf.agent.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import com.zmf.agent.agent.goal.Goal
import com.zmf.agent.agent.goal.GoalPhase
import com.zmf.agent.agent.goal.label
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.theme.Palette

/**
 * 「目标」页：看当前会话的长期目标，并按阶段给动作。
 *
 * 版式与 SchedulePage 等一级页面一致：Surface 铺满 + blockTapsOnly，左上角「←」返回。
 * 页面本身不读写盘，目标从参数进来、动作全部回调出去：
 * 谁持有 ChatViewModel 谁去改它，页面只负责显示，避免两个地方各存一份目标。
 */
@Composable
fun GoalPage(
    goal: Goal?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
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
                    text = "目标",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // 先收进局部 val：后面几层 lambda 都用同一个非空引用，不必每处再判一次空
            val current = goal
            if (current == null) {
                Text(
                    text = "还没有目标",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    fontSize = 13.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
            } else {
                // 正文独占剩余高度并自己滚动：目标可能很长，状态行和按钮必须一直露在外面
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = current.objective,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = onSurface
                    )
                    if (current.note.isNotEmpty()) {
                        Text(
                            text = current.note,
                            modifier = Modifier.padding(top = 8.dp),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = current.phase.label(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        // 只有「进行中」是活的，用 Salt 绿；暂停/完成都是灰的，一眼看出没在跑
                        color = if (current.phase == GoalPhase.ACTIVE) {
                            Palette.AccentGreen
                        } else {
                            onSurface.copy(alpha = 0.45f)
                        }
                    )
                    Text(
                        text = " · 已跑 " + current.rounds + " 轮",
                        fontSize = 13.sp,
                        color = onSurface.copy(alpha = 0.45f)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 按阶段给动作：已完成只剩删除，避免「继续」把结论又拉回进行中
                    when (current.phase) {
                        GoalPhase.ACTIVE -> {
                            GoalAction("暂停", primary = false, onClick = onPause)
                            GoalAction("标记完成", primary = true, onClick = onComplete)
                        }

                        GoalPhase.PAUSED -> {
                            GoalAction("继续", primary = true, onClick = onResume)
                            GoalAction("标记完成", primary = false, onClick = onComplete)
                        }

                        GoalPhase.DONE -> GoalAction("删除目标", primary = false, danger = true, onClick = onDelete)
                    }
                }
            }
        }
    }
}

/** 底部动作：主操作用 Salt 绿，次要操作淡化，删除用错误色；和 SchedulePage 的行内按钮同一套观感。 */
@Composable
private fun GoalAction(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    val color = when {
        danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
        primary -> Palette.AccentGreen
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }
    Text(
        text = text,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        fontSize = 14.sp,
        fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
        color = color
    )
}
