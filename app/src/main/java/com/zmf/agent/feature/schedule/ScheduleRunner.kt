package com.zmf.agent.feature.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.zmf.agent.capability.config.ApiConfig
import com.zmf.agent.capability.loop.AgentLoop
import com.zmf.agent.capability.loop.AgentUi
import com.zmf.agent.capability.permission.PermissionStore
import com.zmf.agent.capability.prompt.PromptInput
import com.zmf.agent.infra.llm.DeepSeekProvider
import com.zmf.agent.infra.llm.UsageBuckets
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.core.notify.Notifier
import com.zmf.agent.infra.system.AndroidPermissions
import com.zmf.agent.capability.runtime.AgentForegroundService
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolRegistry
import com.zmf.agent.capability.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.zmf.agent.capability.runtime.ChatBridge

/**
 * 定时任务的执行端：设置闹钟、到点执行一轮 Agent、将结果写回会话并发送通知。
 *
 * 职责划分：[ScheduleReceiver] 仅转发广播，实际工作在此完成；[ScheduleStore] 只负责数据。
 *
 * 执行一轮需启动常驻前台服务：广播唤醒的进程回到后台后可能被回收，
 * 而一轮 Agent（含工具调用）可能持续数分钟，仅靠 BroadcastReceiver 的 onReceive 执行预算无法维持。
 */
object ScheduleRunner {

    private const val TAG = "schedule"

    /** 单次任务的工具轮数上限：后台无界面监管，避免无限循环。 */
    private const val MAX_ROUNDS = 8

    /** 失败重试次数（含首次）：一次网络波动不应导致任务等待整整一个周期。 */
    private const val MAX_ATTEMPTS = 2

    /** 通知正文仅保留开头此长度的文字，完整答复写入会话。 */
    private const val PREVIEW_CHARS = 400

    /** 会话标题取任务名的最大字数（保证侧边栏单行可显示）。 */
    private const val TITLE_CHARS = 20

    /** 后台单轮携带的历史问答上限：既能承接「第二次回复二」这类上下文，又不会占满上下文窗口。 */
    private const val HISTORY_MESSAGES = 20

    /** 已错过触发时刻的任务统一立即执行：若再等待一个周期，用户会认为任务丢失。 */
    private const val IMMEDIATE_DELAY_MS = 5_000L

    /**
     * 执行期唤醒锁的持有上限。
     *
     * 正常情况下任务在数分钟内结束；超时用于异常路径兜底，避免协程阻塞时持续耗电。
     */
    private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

    /** 唤醒锁标签：便于在系统电池统计中识别为定时任务持有。 */
    private const val WAKE_LOCK_TAG = "Mingent:schedule"

    /** 应用级作用域：广播返回后协程仍需继续执行，因此不能绑定到 Receiver 的生命周期。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- 排期 ----

    /**
     * 按任务当前配置计算下一次触发时刻，持久化并设置闹钟，返回持久化后的副本（界面可直接用于刷新列表）。
     *
     * 停用状态下仅取消闹钟；无法计算出未来时刻（once 的时刻已过）时安排为立即执行，
     * 静默不排期比延迟更糟：用户启用开关即期望任务执行。
     */
    fun arm(context: Context, task: ScheduleTask): ScheduleTask {
        val app = context.applicationContext
        if (!task.enabled) {
            cancel(app, task.id)
            return ScheduleStore.upsert(app, task.copy(nextAt = 0L))
        }
        val now = System.currentTimeMillis()
        // 新建、编辑或重新启用：以当前时刻为起点计算一次，不沿用既有的计划时刻（以旧 nextAt 为锚会使首次触发延续旧节奏）
        val computed = ScheduleStore.nextAfter(task.copy(nextAt = 0L), now)
        val nextAt = if (computed > now) computed else now + IMMEDIATE_DELAY_MS
        val stored = ScheduleStore.upsert(app, task.copy(nextAt = nextAt))
        schedule(app, stored)
        return stored
    }

    /** 设置下一次闹钟；停用或未排期时取消，确保数据与闹钟状态一致。 */
    fun schedule(context: Context, task: ScheduleTask) {
        val app = context.applicationContext
        if (!task.enabled || task.nextAt <= 0L) {
            cancel(app, task.id)
            return
        }
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (manager == null) {
            FileLog.log(TAG, "拿不到 AlarmManager，「" + task.title + "」排不上")
            return
        }
        val pi = pendingIntent(app, task.id)
        // 使用 setExactAndAllowWhileIdle 而非 set：在 Doze 模式下也能准点唤醒，分钟级间隔不会被系统延迟为数小时。
        // Android 12 及以上先检查「闹钟与提醒」权限：未授权时直接使用非精确闹钟，避免一次必然失败的调用。
        if (AndroidPermissions.exactAlarmAllowed(app)) {
            val exact = runCatching {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.nextAt, pi)
            }
            if (exact.isFailure) {
                // 个别机型即使自检通过也会抛出 SecurityException：退回非精确闹钟，
                // 可能延迟数分钟，但必须保证任务最终执行
                FileLog.log(TAG, "精确闹钟排不上（" + exact.exceptionOrNull() + "），改用非精确")
                runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.nextAt, pi) }
                    .onFailure { FileLog.log(TAG, "闹钟排不上：" + it) }
            }
        } else {
            FileLog.log(TAG, "未获精确闹钟授权，改用非精确闹钟")
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.nextAt, pi) }
                .onFailure { FileLog.log(TAG, "闹钟排不上：" + it) }
        }
        FileLog.log(TAG, "排期「" + task.title + "」at=" + timeText(task.nextAt))
    }

    /** 取消一条任务的闹钟（任务删除或停用时调用）。 */
    fun cancel(context: Context, taskId: String) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(pendingIntent(app, taskId)) }
    }

    /**
     * 重排全部已启用任务的闹钟。
     *
     * 闹钟在重启、应用覆盖更新与被系统强制停止后都会丢失：开机与更新由 [ScheduleBootReceiver] 触发，
     * 每次打开应用时由 MainActivity 再调用一次作为兜底，两个入口均走此方法，避免逻辑分叉。
     *
     * 尚未到达触发时刻的任务保持原计划（每次向后顺延会使「每 60 分钟」的周期逐渐偏移）；
     * 已错过时刻的任务才重新计算：周期任务只安排下一次（不补执行，避免连续执行多次），一次性任务立即执行。
     */
    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        var armed = 0
        for (task in ScheduleStore.load(app)) {
            if (!task.enabled) {
                cancel(app, task.id)
                continue
            }
            var next = task.nextAt
            if (next <= now) {
                val computed = ScheduleStore.nextAfter(task, now)
                next = if (computed > now) computed else now + IMMEDIATE_DELAY_MS
            }
            val stored = if (next == task.nextAt) task else ScheduleStore.upsert(app, task.copy(nextAt = next))
            schedule(app, stored)
            armed++
        }
        FileLog.log(TAG, "重排 " + armed + " 条已启用任务")
    }

    // ---- 触发 ----

    /**
     * 闹钟到点：先启动前台服务维持进程存活，再交由应用级协程执行，广播本身立即返回。
     *
     * 前台服务仅保证进程优先级，不保证 CPU 保持唤醒；执行期间必须持有部分唤醒锁，
     * 否则设备进入 Doze 后网络与协程都会停止。唤醒锁设有超时，异常路径也不会永久持有。
     */
    fun fire(context: Context, taskId: String) {
        val app = context.applicationContext
        AgentForegroundService.start(app)
        val lock = acquireWakeLock(app)
        scope.launch {
            try {
                runCatching { runOnce(app, taskId) }
                    .onFailure { FileLog.log(TAG, "任务 " + taskId + " 执行异常：" + it) }
            } finally {
                releaseWakeLock(lock)
            }
        }
    }

    /** 获取部分唤醒锁；系统拒绝（权限缺失或厂商限制）时返回 null，任务仍照常尝试执行。 */
    private fun acquireWakeLock(app: Context): PowerManager.WakeLock? {
        return runCatching {
            val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return null
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                // 不启用引用计数：每次执行只获取一次、释放一次，超时由系统兜底
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.onFailure { FileLog.log(TAG, "唤醒锁获取失败：" + it) }.getOrNull()
    }

    /** 释放唤醒锁；超时自动释放后 isHeld 为 false，不重复释放。 */
    private fun releaseWakeLock(lock: PowerManager.WakeLock?) {
        if (lock == null) return
        runCatching { if (lock.isHeld) lock.release() }
            .onFailure { FileLog.log(TAG, "唤醒锁释放失败：" + it) }
    }

    /** 执行一次：先记录「已触发」并安排下一次，再请求模型，最后写入会话并发送通知。 */
    private suspend fun runOnce(app: Context, taskId: String) {
        val task = ScheduleStore.load(app).firstOrNull { it.id == taskId }
        if (task == null) {
            FileLog.log(TAG, "任务已不存在，忽略：" + taskId)
            return
        }
        if (!task.enabled) {
            FileLog.log(TAG, "任务已停用，忽略：" + task.title)
            return
        }

        val now = System.currentTimeMillis()
        val computed = ScheduleStore.nextAfter(task, now)
        // 先记录「已触发」再实际执行：进程中途被终止时，下次开机重排不会将本次视为未执行而重复运行。
        // 一次性任务执行后即停用（nextAt 归零）；周期任务无法计算下一次（时刻格式错误）时同样停用，
        // 保留一条永远无法排期的任务比停用更易造成困惑。
        val keepRunning = task.kind != ScheduleStore.KIND_ONCE && computed > now
        val fired = task.copy(
            lastRunAt = now,
            enabled = keepRunning,
            nextAt = if (keepRunning) computed else 0L
        )
        ScheduleStore.upsert(app, fired)
        if (keepRunning) schedule(app, fired) else cancel(app, taskId)
        val startedAt = System.currentTimeMillis()
        // 系统会将精确闹钟降级为 45 秒窗口（见 ScheduleStore.nextEvery），延迟无法避免；
        // 记录一行以便排查时区分「闹钟延迟」与「上一轮执行过久」
        val lateMillis = if (task.nextAt > 0L) startedAt - task.nextAt else 0L
        if (lateMillis >= 5_000L) {
            FileLog.log(TAG, "「" + task.title + "」比计划晚 " + (lateMillis / 1000) + " 秒触发")
        }
        // 每次触发记录一行：排期只说明闹钟设定的时刻，实际开始时间与耗时需依赖这一对起止日志
        FileLog.log(
            TAG,
            "开始执行「" + task.title + "」，开始于 " + timeText(startedAt) +
                "，下一次 " + (if (keepRunning) timeText(computed) else "不再执行")
        )

        val session = resolveSession(app, task)
        var content = ""
        var reason = ""
        for (attempt in 1..MAX_ATTEMPTS) {
            val outcome = runCatching { runRound(app, session, task.prompt) }
                .getOrElse { RoundResult("", "执行异常：" + (it.message ?: it.javaClass.simpleName)) }
            content = outcome.content
            reason = outcome.error
            if (content.isNotEmpty()) break
            FileLog.log(TAG, "第 " + attempt + " 次执行没拿到答复：" + reason)
        }

        if (content.isNotEmpty()) {
            appendResult(app, session, task, content)
            // 界面可能正在显示：立即通知其重新读取，无需等待用户重启进程
            ChatBridge.notifySessionsChanged()
            Notifier.notifySchedule(app, "定时任务完成：" + task.title, preview(content), session.id)
        } else {
            Notifier.notifySchedule(app, "定时任务失败：" + task.title, reason, session.id)
        }
        FileLog.log(
            TAG,
            "执行结束「" + task.title + "」，耗时 " + (System.currentTimeMillis() - startedAt) +
                " 毫秒，" + (if (content.isNotEmpty()) "成功 " + content.length + " 字" else "失败：" + reason)
        )
    }

    /**
     * 一轮最简 Agent 往返：新建 messages、以默认提示词输入、执行后取最后一条有内容的 assistant 消息。
     *
     * 有意不复用 ChatViewModel 的状态：定时任务在后台执行，界面中的同一会话可能正被用户编辑，
     * 共用消息列表会导致相互覆盖。
     */
    private suspend fun runRound(app: Context, session: ChatSession, prompt: String): RoundResult {
        val permission = PermissionStore.load(app)
        // 使用目标会话所属的工作区：否则工具相对路径的解析位置会与会话中显示的目录不一致
        val spaces = WorkSpaceStore.load(app)
        val space = spaces.firstOrNull { it.id == session.workspaceId } ?: WorkSpaceStore.defaultSpace(app)
        val workspace = WorkSpaceStore.dirOf(app, space)
        val loop = AgentLoop(
            provider = DeepSeekProvider(
                apiKey = ApiConfig.loadApiKey(app),
                baseUrl = ApiConfig.loadBaseUrl(app)
            ),
            registry = ToolRegistry(),
            model = ApiConfig.loadModel(app),
            maxRounds = MAX_ROUNDS,
            // 系统提示词其余项均使用 PromptInput 的默认值，仅补充实际权限级别与工作区路径
            promptProvider = { PromptInput(workspacePath = workspace.absolutePath, permission = permission) }
        )
        // 携带目标会话已有的问答：否则每轮都相当于首次见到该语句，
        // 用户设置的「第二次回复二」将始终只回复一。
        val messages = historyOf(session)
        val ui = SilentUi()
        // 后台无界面展示思考过程：关闭思考以节省 token，同时避免「历史中必须回传 reasoning_content」的约束
        loop.run(
            messages = messages,
            userText = prompt,
            thinking = false,
            thinkingEffort = null,
            toolContext = ToolContext(
                context = app,
                permission = permission,
                workspaceRoot = workspace,
                sessionId = session.id
            ),
            ui = ui
        )
        val content = messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
            ?.content.orEmpty().trim()
        if (content.isEmpty()) {
            return RoundResult("", ui.errors.toString().ifEmpty { "模型没有返回内容" })
        }
        return RoundResult(content, "")
    }

    /**
     * 将目标会话中「有正文的 user/assistant」消息逐条转换为单轮上下文。
     *
     * 有意丢弃 tool_calls 与 tool 结果：二者必须成对出现，后台单轮重放容易产生非法请求；
     * 定时任务需要的是上一轮问答的上下文，而非中间工具细节。
     */
    private fun historyOf(session: ChatSession): MutableList<ChatMessage> =
        session.messages
            .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
            .takeLast(HISTORY_MESSAGES)
            .mapTo(mutableListOf()) { ChatMessage(role = it.role, content = it.content) }

    /**
     * 目标会话：存在 id 则续用该会话（已被删除则改为新建）。
     *
     * 选择「新会话」时仅在首次触发时创建一条，并将 id 写回任务：
     * 若每次都新建，同一任务每轮的上下文都会从零开始，用户测试「第二次回复二」时始终只回复一，
     * 侧边栏也会不断累积一次性会话。
     */
    private fun resolveSession(app: Context, task: ScheduleTask): ChatSession {
        val targetId = task.targetSessionId?.takeIf { it.isNotEmpty() }
        if (targetId != null) {
            val existing = SessionStore.load(app).firstOrNull { it.id == targetId }
            if (existing != null) return existing
            FileLog.log(TAG, "目标会话已不存在，改为新建：" + targetId)
        }
        // 标题先使用任务名：便于在侧边栏识别该会话来自哪个定时任务（已有标题后聊天不会覆盖）
        val created = SessionStore.newSession().copy(title = task.title.take(TITLE_CHARS))
        // 立即持久化并写回任务：下一轮才能承接本轮上下文
        SessionStore.save(app, listOf(created) + SessionStore.load(app))
        ScheduleStore.upsert(app, task.copy(targetSessionId = created.id))
        ChatBridge.notifySessionsChanged()
        FileLog.log(TAG, "为任务「" + task.title + "」建立结果会话：" + created.id)
        return created
    }

    /**
     * 将结果追加进会话。
     *
     * 仅补充「提问 + 最终答复」：工具中间态（包括可能被轮数上限截断的不完整轮次）不写入历史，
     * 下次打开该会话时呈现一问一答，而非一批缺少上下文的工具卡片。
     * 现读现写同样是为了尽量减少 ChatViewModel 侧更新的丢失（两侧均为整份覆盖写）。
     */
    private fun appendResult(app: Context, session: ChatSession, task: ScheduleTask, content: String) {
        val now = System.currentTimeMillis()
        val added = listOf(
            ChatMessage(role = "user", content = task.prompt),
            ChatMessage(role = "assistant", content = content)
        )
        // 仅修改目标会话：整表读改写会与界面争夺快照，覆盖刚追加的问答
        if (SessionStore.appendMessages(app, session.id, added)) {
            // 补充后台生成的标题（用户手动设置的标题 appendMessages 不会修改）
            val stored = SessionStore.load(app).firstOrNull { it.id == session.id }
            if (stored != null && stored.title.isBlank()) {
                SessionStore.saveMerged(
                    app,
                    listOf(stored.copy(title = task.title.take(TITLE_CHARS)))
                )
            }
            return
        }
        val all = SessionStore.load(app)
        val index = all.indexOfFirst { it.id == session.id }
        val merged = if (index >= 0) {
            val old = all[index]
            all.toMutableList().also {
                it[index] = old.copy(
                    title = old.title.ifEmpty { task.title.take(TITLE_CHARS) },
                    messages = old.messages + added,
                    updatedAt = now
                )
            }
        } else {
            // 目标会话恰好被删除：结果写入新会话，避免内容丢失
            listOf(
                session.copy(
                    title = session.title.ifEmpty { task.title.take(TITLE_CHARS) },
                    messages = session.messages + added,
                    updatedAt = now
                )
            ) + all
        }
        SessionStore.save(app, merged)
    }

    // ---- 小工具 ----

    /**
     * 每条任务使用独立的 PendingIntent。
     *
     * requestCode 取 id 的哈希值：不同任务互不覆盖；FLAG_UPDATE_CURRENT 使修改时间后的请求更新同一个闹钟；
     * FLAG_IMMUTABLE 是 Android 12 及以上的强制要求（在 minSdk 26 上附加无副作用）。
     */
    private fun pendingIntent(app: Context, taskId: String): PendingIntent {
        val intent = Intent(app, ScheduleReceiver::class.java)
            .setAction(ScheduleReceiver.ACTION_FIRE)
            .putExtra(ScheduleReceiver.EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            app,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 通知正文仅包含开头一段：完整答复已写入会话，点击通知即可查看。 */
    private fun preview(content: String): String =
        if (content.length <= PREVIEW_CHARS) content else content.take(PREVIEW_CHARS) + "…"

    private fun timeText(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))

    /** 单次执行结果：content 非空表示获得答复；error 为失败原因（也用于拼接通知正文）。 */
    private data class RoundResult(val content: String, val error: String)

    /** 后台执行没有界面：仅收集错误供通知使用，其余回调均为空实现。 */
    private class SilentUi : AgentUi {
        val errors: StringBuilder = StringBuilder()

        override fun onRound(round: Int) = Unit

        override fun onToolStarted(call: ToolCall) = Unit

        override fun onToolFinished(call: ToolCall, result: ToolResult) = Unit

        override fun onUsage(usage: UsageBuckets) = Unit

        override fun onError(message: String) {
            errors.append(if (errors.isEmpty()) "" else "；").append(message)
        }
    }
}
