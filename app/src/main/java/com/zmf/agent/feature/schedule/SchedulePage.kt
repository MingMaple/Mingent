package com.zmf.agent.feature.schedule

import android.os.Build
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zmf.agent.infra.system.AndroidPermissions
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpace
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.feature.schedule.ScheduleRunner
import com.zmf.agent.feature.schedule.ScheduleStore
import com.zmf.agent.feature.schedule.ScheduleTask
import com.zmf.agent.uikit.common.saltCardRow
import com.zmf.agent.uikit.common.blockTapsOnly
import com.zmf.agent.uikit.common.saltRowClickable
import com.zmf.agent.uikit.components.salt.SaltSwitch
import com.zmf.agent.uikit.theme.Palette
import com.zmf.agent.uikit.theme.UiColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.zmf.agent.uikit.common.saltFieldColors

/**
 * 「定时任务」页（设置下的二级页）：列表 / 新建 / 删除。
 *
 * 布局沿用 StatsPage：Surface 铺满、Column、statusBarsPadding 与 blockTapsOnly，
 * 左上角「←」返回，标题为单行小字号文字。这里是唯一会写入 schedules.json 的界面入口，
 * 数据读取 [ScheduleStore]，设置闹钟统一通过 [ScheduleRunner]（页面不直接操作 AlarmManager）。
 */
@Composable
fun SchedulePage(onBack: () -> Unit) {
    val context = LocalContext.current
    var tasks by remember { mutableStateOf<List<ScheduleTask>>(emptyList()) }
    var tasksLoaded by remember { mutableStateOf(false) }
    // 任务表与工作区均为小文件，但仍放到后台读取，避免进入页面时卡住首帧。
    LaunchedEffect(Unit) {
        tasks = withContext(Dispatchers.IO) { ScheduleStore.load(context) }
        tasksLoaded = true
    }
    // 归属工作区只在表单里选择，进入页面时读一次即可
    val workspaces = remember { WorkSpaceStore.load(context) }
    var creating by remember { mutableStateOf(false) }
    // 当前展开编辑的任务（null 表示全部折叠）。同一时刻仅展开一条：两条表单同时显示容易导致误改。
    var editingId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ScheduleTask?>(null) }
    // 系统保障项仅在存在启用任务时才需要提示；用户从系统设置返回时重新查询一次
    var permissions by remember { mutableStateOf(AndroidPermissions.status(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissions = AndroidPermissions.status(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val onSurface = MaterialTheme.colorScheme.onSurface

    /**
     * 通知被关闭时的引导。
     *
     * 应用 targetSdk 为 28，系统不会为它弹出通知运行时授权框；未授权时定时任务的结果通知
     * 会被系统静默丢弃，用户到点后什么都看不到。因此在启用任务这一步直接跳到通知设置页。
     */
    fun ensureNotificationEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissions.notification) {
            AndroidPermissions.openNotificationSettings(context)
        }
    }

    Surface(
        // 页面铺满且不透明；最外层拦截点击，避免穿透到下层聊天界面
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

            if (tasksLoaded && tasks.isEmpty()) {
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
                        // 开关控制是否排期：arm 启用时计算下一次并设置闹钟，停用时将 nextAt 归零并取消闹钟
                        if (on) ensureNotificationEnabled()
                        ScheduleRunner.arm(context, task.copy(enabled = on))
                        tasks = ScheduleStore.load(context)
                    },
                    // 点击「编辑」展开表单，再次点击收起（同一时刻仅展开一条，两条表单叠加容易导致误改）
                    onToggleExpand = {
                        editingId = if (editingId == task.id) null else task.id
                        creating = false
                    },
                    onDelete = { pendingDelete = task }
                )
                if (editingId == task.id) {
                    TaskForm(
                        initial = task,
                        workspaces = workspaces,
                        onCancel = { editingId = null },
                        onSave = { draft ->
                            // arm 会按新规则重新计算 nextAt 并重排闹钟；id 相同时覆盖原任务
                            if (draft.enabled) ensureNotificationEnabled()
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
                    workspaces = workspaces,
                    onCancel = { creating = false },
                    onSave = { draft ->
                        // arm 中统一计算 nextAt、持久化并设置闹钟，页面仅负责提供完整原始字段
                        if (draft.enabled) ensureNotificationEnabled()
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

            // 仅在存在启用任务且系统保障项缺失时显示：无任务或均已授权时不提示
            val enabledCount = tasks.count { it.enabled }
            if (enabledCount > 0 && (!permissions.exactAlarm || !permissions.batteryIgnore)) {
                ReliabilityHintCard(
                    exactAlarmMissing = !permissions.exactAlarm,
                    batteryMissing = !permissions.batteryIgnore,
                    onOpenExactAlarm = { AndroidPermissions.openExactAlarmSettings(context) },
                    onOpenBattery = { AndroidPermissions.openBatterySettings(context) }
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // 删除确认：数据与闹钟必须同时删除，仅删除一侧会留下无效闹钟或无效任务
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
            // 卡片自身裁剪为 16dp 圆角：行内矩形波纹越界时不会露出直角
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
            // 编辑与删除单独占一行并右对齐：与开关同排容易误触
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
 * 新建表单：标题、类型胶囊、对应输入项、指令与目标会话。
 *
 * 表单状态保存在本地，仅点击「保存」时才回调整条任务：
 * 输入中途退出不会在文件中留下不完整任务。
 */
@Composable
private fun TaskForm(
    initial: ScheduleTask?,
    workspaces: List<WorkSpace>,
    onCancel: () -> Unit,
    onSave: (ScheduleTask) -> Unit
) {
    val context = LocalContext.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val bodyStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)

    // initial 非空表示编辑既有任务：字段以其当前值初始化，保存时沿用 id（由 arm 覆盖原任务）
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
    var showWorkspacePicker by remember { mutableStateOf(false) }
    // 归属工作区：null（默认工作区）在界面上显示为「默认工作区」，保存时再折回 null
    var workspaceId by remember(initial) {
        mutableStateOf(initial?.workspaceId?.takeIf { it.isNotEmpty() } ?: WorkSpaceStore.DEFAULT_ID)
    }
    var hint by remember { mutableStateOf("") }
    // 弹窗与当前选择共用该数据：仅在进入表单时读取一次，避免每次重组都读取文件
    var sessions by remember { mutableStateOf<List<ChatSession>>(emptyList()) }
    // 只需标题列表；完整会话正文的解析放到后台，避免打开表单时阻塞主线程。
    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) {
            SessionStore.load(context).sortedByDescending { it.updatedAt }
        }
    }

    fun submit() {
        val now = System.currentTimeMillis()
        val taskTitle = title.trim()
        val taskPrompt = prompt.trim()
        val minutes = everyText.trim().toIntOrNull()
        // 时刻统一换算为下一次到达时刻：返回 0 表示 "HH:mm" 格式错误（daily 与 once 共用此校验）
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
                // 空 id 表示新建（由 arm 生成）；有 id 表示编辑，覆盖原任务
                id = initial?.id.orEmpty(),
                title = taskTitle,
                kind = kind,
                everyMinutes = minutes ?: ScheduleStore.DEFAULT_EVERY_MINUTES,
                timeOfDay = timeText.trim().ifEmpty { ScheduleStore.DEFAULT_TIME_OF_DAY },
                // once 保存绝对毫秒：跨天或重启后仍能确定触发时刻
                atMillis = if (kind == ScheduleStore.KIND_ONCE) at else 0L,
                prompt = taskPrompt,
                targetSessionId = if (targetNew) null else pickedSessionId,
                // 默认工作区以 null 落盘，与旧任务迁移后的形态一致
                workspaceId = workspaceId.takeIf { it != WorkSpaceStore.DEFAULT_ID },
                // 开关与上次执行时间不属于表单：编辑时原样保留，避免仅修改标题导致任务停用
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
            val boundId = initial?.targetSessionId
            if (boundId != null) {
                // 任务已绑定会话：只显示「指定会话」与该会话名称，不再提供「新会话」，
                // 否则用户会误以为修改即可换回新建，而解绑不会自动发生
                Text(
                    text = "指定会话 · " + (sessions.firstOrNull { it.id == boundId }
                        ?.title?.ifBlank { "新会话" } ?: "已解绑"),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.75f)
                )
            } else {
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
            }

            // 归属工作区：决定后台执行时的运行根，新建的结果会话也归入该工作区
            Text("归属工作区", fontSize = 12.sp, color = onSurface.copy(alpha = 0.55f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = workspaces.firstOrNull { it.id == workspaceId }?.name ?: "默认工作区",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = onSurface.copy(alpha = 0.75f)
                )
                Text(
                    text = "选择",
                    modifier = Modifier
                        .saltRowClickable(RoundedCornerShape(8.dp)) { showWorkspacePicker = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    color = Palette.Neutral
                )
            }

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

    // 点击外部或返回键关闭由 onDismissRequest 处理
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

    if (showWorkspacePicker) {
        WorkspacePickerDialog(
            workspaces = workspaces,
            onPick = { id ->
                workspaceId = id
                showWorkspacePicker = false
            },
            onDismiss = { showWorkspacePicker = false }
        )
    }
}

/** 「归属工作区」弹窗：默认工作区排在最前，点击任意一行即确定。 */
@Composable
private fun WorkspacePickerDialog(
    workspaces: List<WorkSpace>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("归属工作区") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (workspaces.isEmpty()) {
                    Text("还没有工作区", fontSize = 12.sp, color = onSurface.copy(alpha = 0.45f))
                }
                for (space in workspaces) {
                    Text(
                        text = space.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .saltRowClickable(RoundedCornerShape(10.dp)) { onPick(space.id) }
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

/** 「选择会话」弹窗：按 updatedAt 降序列出全部会话，点击任意一行即确定目标会话。 */
@Composable
private fun SessionPickerDialog(
    sessions: List<ChatSession>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    // 工作区名称查表：workspaceId 为 null 或未匹配时归入「未分类」
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

/** 胶囊选择：选中为墨色底反白，未选中为 6% 淡色底（与 PackagePage、LogsPage 的 chip 一致）。 */
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
            // 选中为墨色底反白：与模型页、洞察页的分段规则一致
            onSurface.copy(alpha = alpha)
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
                MaterialTheme.colorScheme.surface.copy(alpha = alpha)
            } else {
                onSurface.copy(alpha = 0.6f * alpha)
            }
        )
    }
}

/**
 * 后台可靠性提示卡：仅在存在启用任务且系统保障项缺失时显示。
 *
 * 每行只说明缺失项并跳转对应系统页，不在应用内代替用户做授权决定。
 */
@Composable
private fun ReliabilityHintCard(
    exactAlarmMissing: Boolean,
    batteryMissing: Boolean,
    onOpenExactAlarm: () -> Unit,
    onOpenBattery: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            if (exactAlarmMissing) {
                ReliabilityHintRow(
                    text = "精确闹钟未授权，触发可能延迟数分钟",
                    onClick = onOpenExactAlarm
                )
            }
            if (batteryMissing) {
                ReliabilityHintRow(
                    text = "电池优化未关闭，后台执行可能被系统中止",
                    onClick = onOpenBattery
                )
            }
        }
    }
}

/** 提示卡中的一行：左侧为说明，右侧为蓝色「去设置」链接文本。 */
@Composable
private fun ReliabilityHintRow(text: String, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .saltRowClickable(RoundedCornerShape(10.dp), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            color = onSurface.copy(alpha = 0.6f)
        )
        Text(text = "去设置 ›", fontSize = 12.sp, color = UiColors.accentFg)
    }
}

/** 类型的中文说明：每 N 分钟 / 每天 HH:mm / 一次性。 */
private fun kindText(task: ScheduleTask): String = when (task.kind) {
    ScheduleStore.KIND_EVERY -> "每 " + task.everyMinutes + " 分钟"
    ScheduleStore.KIND_DAILY -> "每天 " + task.timeOfDay
    else -> "一次性"
}

/** 下次触发：停用或未排期时直接显示状态文字，不显示 1970 年的时间。 */
private fun nextText(task: ScheduleTask): String = when {
    !task.enabled -> "已停用"
    task.nextAt <= 0L -> "未排期"
    else -> SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(task.nextAt))
}
