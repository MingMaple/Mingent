package com.zmf.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.agent.subagent.SubAgentRegistry
import com.zmf.agent.ui.theme.Palette

/**
 * 子智能体详情页：看它自己的对话，并能给它追加一条消息继续跑。
 *
 * 数据全部来自 [SubAgentRegistry] 的内存快照（父会话每 300ms 轮询一次传进来），
 * 这里不持有执行状态；发送只回调给上层，由上层调执行器。
 */
@Composable
fun SubAgentPage(
    entry: SubAgentRegistry.Entry?,
    onBack: () -> Unit,
    onSend: (String) -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val running = entry?.state == SubAgentRegistry.RUNNING
    val failed = entry?.state == SubAgentRegistry.FAILED
    var draft by remember(entry?.id) { mutableStateOf("") }
    // 跑完一轮后清空草稿，免得用户以为没发出去
    LaunchedEffect(entry?.state) { if (entry?.state == SubAgentRegistry.DONE) draft = "" }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "←",
                    modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = entry?.description ?: "子智能体",
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    fontSize = 16.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (entry != null) {
                    Text(
                        text = if (entry.multiTurn) "多轮" else "单轮",
                        fontSize = 11.sp,
                        color = onSurface.copy(alpha = 0.45f)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = stateText(entry?.state),
                    fontSize = 12.sp,
                    color = stateColor(entry?.state)
                )
                Spacer(Modifier.width(8.dp))
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (entry == null) {
                    item {
                        Text(
                            text = "这个子智能体的记录已经不在了（App 重启或已被清空）。",
                            fontSize = 13.sp,
                            color = onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    items(entry.transcript.size) { index ->
                        val turn = entry.transcript[index]
                        TurnRow(turn.role, turn.text, turn.tools)
                    }
                    if (entry.transcript.isEmpty() && entry.result.isNotBlank()) {
                        item(key = "result") { TurnRow("assistant", entry.result) }
                    }
                    if (failed && entry.error.isNotBlank()) {
                        item(key = "err") {
                            Text(
                                text = entry.error,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            val multiTurn = entry?.multiTurn == true
            val placeholder = when {
                entry == null -> "记录已不在，无法继续"
                !multiTurn -> "单轮子智能体：执行完即结束，不能再发消息"
                running -> "子智能体正在运行…"
                failed -> "已失败，无法继续"
                else -> "发送消息继续这个子智能体"
            }
            val enabled = entry != null && multiTurn && !running && !failed
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(onSurface.copy(alpha = 0.07f))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (draft.isEmpty()) {
                        Text(text = placeholder, fontSize = 14.sp, color = onSurface.copy(alpha = 0.4f))
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { if (enabled) draft = it },
                        enabled = enabled,
                        textStyle = TextStyle(fontSize = 14.sp, color = onSurface),
                        cursorBrush = SolidColor(Palette.AccentGreen),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(8.dp))
                val canSend = enabled && draft.isNotBlank()
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .width(40.dp)
                        .clip(CircleShape)
                        .background(Palette.AccentGreen.copy(alpha = if (canSend) 1f else 0.3f))
                        .clickable(enabled = canSend) {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                onSend(text)
                                draft = ""
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "↑", fontSize = 18.sp, color = Color.White)
                }
            }
        }
    }
}

/** 一条对话：user 靠右浅底，assistant 直接铺文字；工具名单独一行灰字。 */
@Composable
private fun TurnRow(role: String, text: String, tools: List<String> = emptyList()) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    if (role == "user") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = onSurface.copy(alpha = 0.07f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 6.dp),
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 14.sp,
                    color = onSurface
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (tools.isNotEmpty()) {
                Text(
                    text = "工具 " + tools.joinToString("、"),
                    fontSize = 11.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
                Spacer(Modifier.height(4.dp))
            }
            MarkdownText(text = text, fontSize = 14.sp, color = onSurface)
        }
    }
}

private fun stateText(state: String?): String = when (state) {
    SubAgentRegistry.RUNNING -> "运行中"
    SubAgentRegistry.DONE -> "完成"
    SubAgentRegistry.FAILED -> "失败"
    else -> ""
}

private val StateFailedColor = Color(0xFFE5484D)

private fun stateColor(state: String?): Color = when (state) {
    SubAgentRegistry.RUNNING -> Palette.AccentPurple
    SubAgentRegistry.DONE -> Palette.AccentGreen
    SubAgentRegistry.FAILED -> StateFailedColor
    else -> Palette.AccentGreen
}
