package com.zmf.agent.ui.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zmf.agent.agent.session.ChatSession
import com.zmf.agent.agent.session.SessionStore
import com.zmf.agent.agent.workspace.WorkSpaceStore
import com.zmf.agent.schedule.ScheduleRunner
import com.zmf.agent.schedule.ScheduleStore
import com.zmf.agent.schedule.ScheduleTask
import com.zmf.agent.ui.common.saltCardRow
import com.zmf.agent.ui.common.blockTapsOnly
import com.zmf.agent.ui.common.saltRowClickable
import com.zmf.agent.ui.shell.SaltSwitch
import com.zmf.agent.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.zmf.agent.ui.common.saltFieldColors

/**
 * 「定时任务」页（设置下的二级页）：列表 / 新建 / 删除。
 *
 * 版式照 StatsPage：Surface 铺满 + Column + statusBarsPadding + blockTapsOnly，
 * 左上角「←」返回，标题一行小字。这里是唯一会写 schedules.json 的界面入口，
 * 数据读 [ScheduleStore]，排闹钟一律走 [ScheduleRunner]（页面不自己碰 AlarmManager）。
 */
@Composable
fun SchedulePage(onBack: () -> Unit) {
    val context = LocalContext.current
    var tasks by remember { mutableStateOf(ScheduleStore.load(context)) }
    var creating by remember { mutableStateOf(false) }
    // 哪一条正在展开编辑（null = 全部折叠）。同一时刻只展开一条：两条表单叠在一起只会改错。
    var editingId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ScheduleTask?>(null) }
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
                    text = "定时任务",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            if (tasks.isEmpty()) {
                Text(
                    text = "还没有定时任务。新建一条，到点由系统闹钟叫醒 App 跑一轮 Agent。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.45f)
                )
            }

            for (task in tasks) {
                ScheduleRow(
                    task = task,
                    expanded = editingId == task.id,
                    onToggle = { on ->
                        // 开关改的是「要不要排期」：arm 里打开会算下一次并排闹钟，关闭会把 nextAt 归零并撤闹钟
                        ScheduleRunner.arm(context, task.copy(enabled = on))
                        tasks = ScheduleStore.load(context)
                    },
                    // 点「编辑」展开成表单，再点一下收回（同一时刻只展开一条，两条表单叠着只会改错）
                    onToggleExpand = {
                        editingId = if (editingId == task.id) null else task.id
                        creating = false
                    },
                    onDelete = { pendingDelete = task }
                )
                if (editingId == task.id) {
                    TaskForm(
                        initial = task,
                        onCancel = { editingId = null },
                        onSave = { draft ->
                            // arm 会按新规则重算 nextAt 并重排闹钟；id 相同即覆盖原任务
                            ScheduleRunner.arm(context, draft)
                            tasks = ScheduleStore.load(context)
                            editingId = null
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (creating) {
                TaskForm(
                    initial = null,
                    onCancel = { creating = false },
                    onSave = { draft ->
                        // arm 里统一算 nextAt / 落盘 / 排闹钟，页面只负责把原始字段给全
                        ScheduleRunner.arm(context, draft)
                        tasks = ScheduleStore.load(context)
                        creating = false
                    }
                )
            } else {
                Text(
                    text = "＋ 新建任务",
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(10.dp)) {
                            creating = true
                            editingId = null
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    fontSize = 14.sp,
                    color = Palette.Neutral
                )
            }

            Text(
                text = "定时任务由系统闹钟触发，不监听开机广播；应用重启后首次打开时会自动重新排期。" +
                    "每个任务仅保留一条通知，点击通知可返回结果所在会话。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                fontSize = 11.sp,
                color = onSurface.copy(alpha = 0.35f)
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // 删除确认：删数据和删闹钟要一起做，只删一边会留下「幽灵闹钟」或「幽灵任务」
    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除定时任务") },
            text = { Text("删除「" + task.title.ifEmpty { "未命名任务" } + "」？已排的闹钟会一起取消。") },
            confirmButton = {
                TextButton(onClick = {
                    ScheduleRunner.cancel(context, task.id)
                    tasks = ScheduleStore.remove(context, task.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 列表一项：标题 / 类型 + 下次触发 / 开关；第二行放「编辑」与「删除」。 */
@Composable
private fun ScheduleRow(
    task: ScheduleTask,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    onToggleExpand: () -> Unit,
    onDelete: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            // 卡片自己裁到 16dp：行里的矩形波纹越界也不会露出方角
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title.ifEmpty { "未命名任务" },
                        fontSize = 15.sp,
                        color = onSurface
                    )
                    Text(
                        text = kindText(task) + " · 下次 " + nextText(task),
                        modifier = Modifier.padding(top = 2.dp),
                        fontSize = 12.sp,
                        color = onSurface.copy(alpha = 0.45f)
                    )
                }
                SaltSwitch(checked = task.enabled, onCheckedChange = onToggle)
            }
            // 编辑与删除单独占一行右对齐：跟开关挤在一起太容易误触
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = if (expanded) "收起" else "编辑",
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(8.dp), onClick = onToggleExpand)
                        .padding(top = 6.dp, start = 8.dp, end = 8.dp),
                    fontSize = 13.sp,
                    color = Palette.Neutral
                )
                Text(
                    text = "删除",
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(8.dp), onClick = onDelete)
                        .padding(top = 6.dp, start = 8.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/**
 * 新建表单：标题 + 类型胶囊 + 对应输入 + 指令 + 目标会话。
 *
 * 表单状态留在本地，只有点「保存」才把整条任务回调出去：
 * 输入到一半退出去，不会在 files 里留下半条任务。
 */
@Composable
private fun TaskForm(
    initial: ScheduleTask?,
    onCancel: () -> Unit,
    onSave: (ScheduleTask) -> Unit
) {
    val context = LocalContext.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val bodyStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)

    // initial 非空＝编辑既有任务：字段按它的现值铺开，保存时沿用 id（让 arm 覆盖原任务）
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var kind by remember(initial) { mutableStateOf(initial?.kind ?: ScheduleStore.KIND_EVERY) }
    var everyText by remember(initial) {
        mutableStateOf((initial?.everyMinutes ?: ScheduleStore.DEFAULT_EVERY_MINUTES).toString())
    }
    var timeText by remember(initial) {
        mutableStateOf(initial?.timeOfDay ?: ScheduleStore.DEFAULT_TIME_OF_DAY)
    }
    var prompt by remember(initial) { mutableStateOf(initial?.prompt.orEmpty()) }
    var targetNew by remember(initial) { mutableStateOf(initial?.targetSessionId == null) }
    var pickedSessionId by remember(initial) { mutableStateOf(initial?.targetSessionId) }
    var showPicker by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    // 弹窗和「当前选择」共用这一份：只在进表单时读一次，避免每次重组都读文件
    val sessions = remember { SessionStore.load(context).sortedByDescending { it.updatedAt } }

    fun submit() {
        val now = System.currentTimeMillis()
        val taskTitle = title.trim()
        val taskPrompt = prompt.trim()
        val minutes = everyText.trim().toIntOrNull()
        // 时刻统一换算成「下一个到达时刻」：返回 0 就是 "HH:mm" 写坏了（daily 和 once 共用这套校验）
        val at = ScheduleStore.nextTimeOfDay(timeText.trim(), now)
        val problem = when {
            taskTitle.isEmpty() -> "标题不能为空"
            taskPrompt.isEmpty() -> "指令不能为空"
            kind == ScheduleStore.KIND_EVERY && (minutes == null || minutes <= 0) -> "间隔要填正整数分钟"
            kind != ScheduleStore.KIND_EVERY && at <= 0L -> "时刻要写成 HH:mm"
            !targetNew && pickedSessionId == null -> "请先选择目标会话"
            else -> ""
        }
        if (problem.isNotEmpty()) {
            hint = problem
            return
        }
        hint = ""
        onSave(
            ScheduleTask(
                // 空 id＝新建（arm 里现发号）；有 id＝编辑，覆盖原任务
                id = initial?.id.orEmpty(),
                title = taskTitle,
                kind = kind,
                everyMinutes = minutes ?: ScheduleStore.DEFAULT_EVERY_MINUTES,
                timeOfDay = timeText.trim().ifEmpty { ScheduleStore.DEFAULT_TIME_OF_DAY },
                // once 存绝对毫秒：跨天 / 重启后仍然知道该在哪个时刻跑
                atMillis = if (kind == ScheduleStore.KIND_ONCE) at else 0L,
                prompt = taskPrompt,
                targetSessionId = if (targetNew) null else pickedSessionId,
                // 开关与「上次跑了没」不属于表单：编辑时原样带过去，别因为改个标题把任务停了
                enabled = initial?.enabled ?: true,
                lastRunAt = initial?.lastRunAt ?: 0L
            )
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                colors = saltFieldColors(),
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = bodyStyle,
                placeholder = { Text("标题（侧边栏以此识别任务）", fontSize = 12.sp) }
            )

            Text("类型", fontSize = 12.sp, color = onSurface.copy(alpha = 0.55f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScheduleChip("每 N 分钟", kind == ScheduleStore.KIND_EVERY) {
                    kind = ScheduleStore.KIND_EVERY
                }
                ScheduleChip("每天", kind == ScheduleStore.KIND_DAILY) {
                    kind = ScheduleStore.KIND_DAILY
                }
                ScheduleChip("只一次", kind == ScheduleStore.KIND_ONCE) {
                    kind = ScheduleStore.KIND_ONCE
                }
            }

            if (kind == ScheduleStore.KIND_EVERY) {
                OutlinedTextField(
                colors = saltFieldColors(),
                    value = everyText,
                    onValueChange = { everyText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = bodyStyle,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = { Text("间隔分钟数，例如 60", fontSize = 12.sp) }
                )
            } else {
                OutlinedTextField(
                colors = saltFieldColors(),
                    value = timeText,
                    onValueChange = { timeText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = bodyStyle,
                    placeholder = { Text("触发时刻（HH:mm），例如 09:00", fontSize = 12.sp) }
                )
            }

            OutlinedTextField(
                colors = saltFieldColors(),
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                textStyle = bodyStyle,
                placeholder = { Text("指令（到达触发时刻发送给模型的内容）", fontSize = 12.sp) }
            )

            Text("结果存到", fontSize = 12.sp, color = onSurface.copy(alpha = 0.55f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScheduleChip("新会话", targetNew) { targetNew = true }
                ScheduleChip("选择会话", !targetNew) {
                    targetNew = false
                    showPicker = true
                }
            }
            Text(
                text = sessions.firstOrNull { it.id == pickedSessionId }
                    ?.title?.ifBlank { "新会话" } ?: "未选择会话",
                fontSize = 11.sp,
                color = onSurface.copy(alpha = 0.35f)
            )

            if (hint.isNotEmpty()) {
                Text(text = hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "保存",
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(8.dp)) { submit() }
                        .padding(vertical = 8.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.Neutral
                )
                Spacer(Modifier.width(20.dp))
                Text(
                    text = "取消",
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(8.dp), onClick = onCancel)
                        .padding(vertical = 8.dp),
                    fontSize = 14.sp,
                    color = onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }

    // 点外部 / 返回键关闭交给 onDismissRequest
    if (showPicker) {
        SessionPickerDialog(
            sessions = sessions,
            onPick = { id ->
                pickedSessionId = id
                targetNew = false
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

/** 「选择会话」弹窗：按 updatedAt 降序列出全部会话，点一行即定下目标会话。 */
@Composable
private fun SessionPickerDialog(
    sessions: List<ChatSession>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 工作区名查表：workspaceId 为 null 或查不到都归「未分类」
    val names = remember { WorkSpaceStore.load(context).associate { it.id to it.name } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择会话") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (sessions.isEmpty()) {
                    Text("还没有会话", fontSize = 12.sp, color = onSurface.copy(alpha = 0.45f))
                }
                for (session in sessions) {
                    Text(
                        text = session.title.ifBlank { "新会话" } +
                            " · " + SessionStore.relativeTime(session.updatedAt) +
                            " · " + (session.workspaceId?.let { names[it] } ?: "未分类"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .saltRowClickable(RoundedCornerShape(10.dp)) { onPick(session.id) }
                            .padding(vertical = 10.dp),
                        fontSize = 13.sp,
                        maxLines = 1,
                        color = onSurface
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 胶囊选择：选中吃 Salt 绿半透明底，未选中是 6% 淡影（同 PackagePage / LogsPage 的 chip）。 */
@Composable
private fun ScheduleChip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val alpha = if (enabled) 1f else 0.3f
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            Palette.AccentGreen.copy(alpha = 0.16f * alpha)
        } else {
            onSurface.copy(alpha = 0.06f * alpha)
        },
        modifier = Modifier.saltCardRow(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                Palette.AccentGreen.copy(alpha = alpha)
            } else {
                onSurface.copy(alpha = 0.6f * alpha)
            }
        )
    }
}

/** 类型的中文说明：每 N 分钟 / 每天 HH:mm / 一次性。 */
private fun kindText(task: ScheduleTask): String = when (task.kind) {
    ScheduleStore.KIND_EVERY -> "每 " + task.everyMinutes + " 分钟"
    ScheduleStore.KIND_DAILY -> "每天 " + task.timeOfDay
    else -> "一次性"
}

/** 下次触发：停用 / 没排期直接说人话，别显示 1970 年。 */
private fun nextText(task: ScheduleTask): String = when {
    !task.enabled -> "已停用"
    task.nextAt <= 0L -> "未排期"
    else -> SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(task.nextAt))
}
