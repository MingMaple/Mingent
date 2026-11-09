package com.zmf.agent.feature.schedule

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.zmf.agent.capability.config.ApiConfig
import com.zmf.agent.capability.loop.AgentLoop
import com.zmf.agent.capability.loop.AgentUi
import com.zmf.agent.capability.loop.ContextSummaryState
import com.zmf.agent.capability.loop.TurnOutcome
import com.zmf.agent.capability.permission.PermissionStore
import com.zmf.agent.capability.prompt.PromptInput
import com.zmf.agent.infra.llm.DeepSeekProvider
import com.zmf.agent.infra.llm.UsageBuckets
import com.zmf.agent.capability.loop.OutputTiming
import com.zmf.agent.core.store.SessionComposition
import com.zmf.agent.core.store.SessionUsageDelta
import com.zmf.agent.core.store.foldUsage
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.SessionBusy
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.StorageStore
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ORIGIN_SCHEDULE
import com.zmf.agent.core.model.SessionPreference
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.core.model.permissionLevelOfWire
import com.zmf.agent.core.model.wireName
import com.zmf.agent.capability.context.ContextCompactor
import com.zmf.agent.capability.context.contextSummarySystemText
import com.zmf.agent.capability.context.validSummaryUpTo
import com.zmf.agent.capability.prompt.PromptBuilder
import com.zmf.agent.core.llm.CostBook
import com.zmf.agent.core.store.LedgerComposition
import com.zmf.agent.core.store.UsageLedger
import com.zmf.agent.infra.llm.ProviderStore
import kotlin.math.roundToInt
import com.zmf.agent.core.notify.Notifier
import com.zmf.agent.infra.system.AndroidPermissions
import com.zmf.agent.capability.runtime.AgentForegroundService
import com.zmf.agent.capability.runtime.PlatformAlarms
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolRegistry
import com.zmf.agent.capability.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.zmf.agent.capability.runtime.ChatBridge
import com.zmf.agent.capability.runtime.ScheduleBridge
import com.zmf.agent.capability.runtime.ScheduleInfo
import com.zmf.agent.capability.runtime.ScheduleSpec

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

    /**
     * 单次任务的工具轮数上限：后台无界面监管，避免无限循环。
     *
     * 取 48 而非早期的 8：检索类任务（每日新闻）需要十余轮搜索与抓取，
     * 原值在半途截断，整轮拿不到答复（2026-11-07 实测）。达到上限仍由
     * AgentLoop 的无人值守收尾保证有一段正文，不会整轮空手而归。
     */
    private const val MAX_ROUNDS = 48

    /** 失败重试次数（含首次）：一次网络波动不应导致任务等待整整一个周期。 */
    private const val MAX_ATTEMPTS = 2

    /** 通知正文仅保留开头此长度的文字，完整答复写入会话。 */
    private const val PREVIEW_CHARS = 400

    /** 会话标题取任务名的最大字数（保证侧边栏单行可显示）。 */
    private const val TITLE_CHARS = 20

    /** 已错过触发时刻的任务统一立即执行：若再等待一个周期，用户会认为任务丢失。 */
    private const val IMMEDIATE_DELAY_MS = 5_000L

    /**
     * 打开应用时补跑的时间窗。
     *
     * 闹钟可能被系统延后数分钟到数小时（待机批次、厂商省电策略），此时用户打开应用
     * 才会看到结果。只补最近这段时间内错过的一次：更早的错过说明用户长时间未使用，
     * 按周期继续重排即可，补跑只会推送已经过期的结果。
     */
    private const val CATCH_UP_MS = 6 * 60 * 60 * 1000L

    /**
     * 迟到多久算「本机在推迟这类闹钟」：超过即把该任务升级为闹钟式排期。
     * 真机上 `setExactAndAllowWhileIdle` 熄屏后会被推迟到数天之后，而正常窗口只有几十秒。
     */
    private const val LATE_ESCALATE_MS = 90_000L

    /**
     * 两次尝试之间的等待。
     *
     * 闹钟把设备从待机唤醒时，网络往往尚未就绪：立即重试会以同一原因再失败一次，
     * 因此留出一段重连时间。等待期间仍持有唤醒锁，不会因休眠而中断。
     */
    private const val RETRY_DELAY_MS = 15_000L

    /**
     * 执行期唤醒锁的持有上限。
     *
     * 正常情况下任务在数分钟内结束；超时用于异常路径兜底，避免协程阻塞时持续耗电。
     */
    private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

    /**
     * 正在执行的任务 id。
     *
     * 同一条任务可能被两条路径同时触发：闹钟到点与打开应用时的补跑。并发执行会各写一次会话、
     * 各发一条通知，因此同一 id 只允许一个执行体，后来的直接丢弃。
     */
    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
        // 平台排期由 capability 的窄口负责：此处只决定「用精确还是非精确」。
        // 默认用 setExactAndAllowWhileIdle（无副作用）。个别 ROM 会把这类闹钟推迟到数天之后，
        // 任务被推迟过一次（clockAlarm）即改用 setAlarmClock：系统不会推迟，代价是状态栏闹钟图标。
        // Android 12 及以上先检查「闹钟与提醒」权限：未授权时直接用非精确闹钟。
        val pi = pendingIntent(app, task.id)
        if (AndroidPermissions.exactAlarmAllowed(app)) {
            val exact = runCatching { PlatformAlarms.setExact(app, task.nextAt, pi, task.clockAlarm) }
            // 个别机型即使自检通过也会抛出 SecurityException：退回非精确闹钟，
            // 可能延迟数分钟，但必须保证任务最终执行
            exact.exceptionOrNull()?.let { FileLog.log(TAG, "闹钟排不上（" + it + "），改用非精确") }
            if (exact.getOrNull() != true) {
                runCatching { PlatformAlarms.setInexact(app, task.nextAt, pi) }
                    .onFailure { FileLog.log(TAG, "闹钟排不上：" + it) }
            }
        } else {
            FileLog.log(TAG, "未获精确闹钟授权，改用非精确闹钟")
            runCatching { PlatformAlarms.setInexact(app, task.nextAt, pi) }
                .onFailure { FileLog.log(TAG, "闹钟排不上：" + it) }
        }
        FileLog.log(TAG, "排期「" + task.title + "」at=" + timeText(task.nextAt))
    }

    /** 取消一条任务的闹钟（任务删除或停用时调用）。 */
    fun cancel(context: Context, taskId: String) {
        val app = context.applicationContext
        PlatformAlarms.cancel(app, pendingIntent(app, taskId))
    }

    /**
     * 重排全部已启用任务的闹钟。
     *
     * 闹钟在重启、应用覆盖更新与被系统强制停止后都会丢失：开机与更新由 [ScheduleBootReceiver] 触发，
     * 每次打开应用时由 MainActivity 再调用一次作为兜底，两个入口均走此方法，避免逻辑分叉。
     *
     * 尚未到达触发时刻的任务保持原计划（每次向后顺延会使「每 60 分钟」的周期逐渐偏移）；
     * 刚错过不久的任务（见 [CATCH_UP_MS]）当场补跑一次，其余已错过的任务重新计算：
     * 周期任务只安排下一次，一次性任务立即执行。
     */
    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        var armed = 0
        // 本该在最近几小时内触发、却没有触发的任务：这些是被系统延后的，先补跑再重排
        val overdue = mutableListOf<String>()
        for (task in ScheduleStore.load(app)) {
            if (!task.enabled) {
                cancel(app, task.id)
                continue
            }
            if (task.nextAt in 1..now && now - task.nextAt <= CATCH_UP_MS) {
                // 交给 runOnce 推进排期：它先写入「已触发」再计算下一次，不会重复补跑
                overdue += task.id
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
        if (overdue.isNotEmpty()) {
            FileLog.log(TAG, "补跑 " + overdue.size + " 条被系统延后的任务")
            for (id in overdue) fire(app, id)
        }
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
        // 由闹钟冷启动的进程没有界面，ChatViewModel 不会初始化日志：
        // 此处先绑定，否则整轮执行不留任何痕迹，事后无法判断「没触发」还是「触发后失败」
        FileLog.init(app)
        AgentForegroundService.start(app)
        val lock = PlatformAlarms.keepAwake(app, WAKE_LOCK_TIMEOUT_MS)
        PlatformAlarms.scope.launch {
            if (!running.add(taskId)) {
                FileLog.log(TAG, "任务正在执行，忽略重复触发：" + taskId)
                lock?.release()
                return@launch
            }
            try {
                runCatching { runOnce(app, taskId) }
                    .onFailure { FileLog.log(TAG, "任务 " + taskId + " 执行异常：" + it) }
            } finally {
                running.remove(taskId)
                lock?.release()
            }
        }
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
        // 残留闹钟：补跑已经把排期推进到未来，同一时刻的旧闹钟随后到达时不再执行第二次。
        // 留 2 秒余量：系统可能提前交付，这不是残留。
        if (task.nextAt > System.currentTimeMillis() + 2_000L) {
            FileLog.log(TAG, "「" + task.title + "」未到触发时刻，忽略（已排到 " + timeText(task.nextAt) + "）")
            schedule(app, task)
            return
        }

        // 目标会话已归档或已删除：直接关闭开关，等用户到设置里重新编辑该任务。
        // 不新建替代会话、也不静默跳过——跳过会让任务每轮空转到下一个周期，用户无从察觉。
        val bound = task.targetSessionId?.takeIf { it.isNotEmpty() }
        if (bound != null) {
            val target = SessionStore.load(app).firstOrNull { it.id == bound }
            if (target == null || target.archived) {
                val reason = if (target == null) "目标会话已删除" else "目标会话已归档"
                ScheduleStore.upsert(
                    app,
                    task.copy(enabled = false, nextAt = 0L, targetSessionId = null)
                )
                cancel(app, taskId)
                FileLog.log(TAG, "「" + task.title + "」" + reason + "，已停用并解绑")
                Notifier.notifySchedule(
                    app,
                    "定时任务已停用：" + task.title,
                    reason + "，请到设置中重新编辑该任务。",
                    bound
                )
                return
            }
        }

        val now = System.currentTimeMillis()
        // 迟到判定要在写回之前完成：本轮若被推迟过，下一次排期就要换用闹钟式排期
        val lateMillis = if (task.nextAt > 0L) now - task.nextAt else 0L
        val escalate = lateMillis >= LATE_ESCALATE_MS && !task.clockAlarm
        if (escalate) {
            FileLog.log(
                TAG,
                "「" + task.title + "」比计划晚 " + (lateMillis / 1000) + " 秒，改用闹钟式排期"
            )
        }
        val computed = ScheduleStore.nextAfter(task, now)
        // 先记录「已触发」再实际执行：进程中途被终止时，下次开机重排不会将本次视为未执行而重复运行。
        // 一次性任务执行后即停用（nextAt 归零）；周期任务无法计算下一次（时刻格式错误）时同样停用，
        // 保留一条永远无法排期的任务比停用更易造成困惑。
        val keepRunning = task.kind != ScheduleStore.KIND_ONCE && computed > now
        val fired = task.copy(
            lastRunAt = now,
            enabled = keepRunning,
            nextAt = if (keepRunning) computed else 0L,
            clockAlarm = task.clockAlarm || escalate
        )
        ScheduleStore.upsert(app, fired)
        if (keepRunning) schedule(app, fired) else cancel(app, taskId)
        val startedAt = System.currentTimeMillis()
        // 系统会将精确闹钟降级为窗口闹钟，延迟无法避免；记录一行以便区分「闹钟延迟」与「上一轮执行过久」
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
        // 会话级串行：与聊天页共用同一把锁。同一会话两侧同时跑，落盘时后写者会覆盖对方的整份消息
        if (!SessionBusy.tryAcquire(session.id)) {
            FileLog.log(TAG, "目标会话正在执行其他回合，本轮跳过：" + session.id)
            Notifier.notifySchedule(
                app,
                "定时任务跳过：" + task.title,
                "目标会话正在执行其他任务，本轮未执行；下次触发时会再次尝试。",
                session.id
            )
            return
        }
        var content = ""
        var reason = ""
        var usage = TurnUsage()
        try {
            // 与聊天页同序：提问先落进历史，成功后再追加本轮答复。
            // 中途进程被杀时至少留下「问过什么」，不会连提问一起丢。
            // programmatic = true 表示 App 代发：高危操作不认它为授权；
            // origin = schedule 让统计仍把它当作一轮任务计入（唤醒来源不同而已）。
            appendPrompt(app, session, task)
            // 与普通任务同口径：提问落盘即记一轮（失败也记，和聊天页一样）
            UsageLedger.recordRound(app)
            ChatBridge.notifySessionsChanged()
            for (attempt in 1..MAX_ATTEMPTS) {
                val outcome = runCatching { runRound(app, session, task) }.getOrElse {
                    RoundResult("", "执行异常：" + (it.message ?: it.javaClass.simpleName))
                }
                content = outcome.content
                reason = outcome.error
                // 只保留成功那次尝试的用量：上一次失败的重试不该写进消息
                usage = outcome.usage
                if (content.isNotEmpty()) break
                // 失败的这次也花了 token 与时间：并入会话，但不写消息、不动排序
                // （与聊天页一致——那边每次请求拿到 usage 就直接记进会话，与整轮成败无关）
                SessionStore.addUsage(app, session.id, usageDelta(outcome.usage, includeSteps = false))
                FileLog.log(TAG, "第 " + attempt + " 次执行没拿到答复：" + reason)
                if (attempt < MAX_ATTEMPTS) {
                    FileLog.log(TAG, "等待 " + RETRY_DELAY_MS / 1000 + " 秒后重试")
                    delay(RETRY_DELAY_MS)
                }
            }
            if (content.isNotEmpty()) {
                appendReply(app, session, task, content, usage)
                // 轮次已在提问落盘时记过；这里只补本轮的步数（与聊天页 commitSteps 同口径）
                UsageLedger.addSteps(app, usage.steps)
                ChatBridge.notifySessionsChanged()
                Notifier.notifySchedule(app, "定时任务完成：" + task.title, preview(content), session.id)
            } else {
                Notifier.notifySchedule(app, "定时任务失败：" + task.title, failureText(reason), session.id)
            }
        } finally {
            SessionBusy.release(session.id)
        }
        FileLog.log(
            TAG,
            "执行结束「" + task.title + "」，耗时 " + (System.currentTimeMillis() - startedAt) +
                " 毫秒，" + (if (content.isNotEmpty()) "成功 " + content.length + " 字" else "失败：" + reason)
        )
    }

    /**
     * 一轮最简 Agent 往返：新建 messages、以默认提示词输入、执行后取**本轮新增**的最后一条 assistant 正文。
     *
     * 有意不复用 ChatViewModel 的状态：定时任务在后台执行，界面中的同一会话可能正被用户编辑，
     * 共用消息列表会导致相互覆盖。
     */
    private suspend fun runRound(app: Context, session: ChatSession, task: ScheduleTask): RoundResult {
        // 权限取目标会话的快照，缺失时退回全局默认：任务在后台执行，用户无法当场切档
        val permission = permissionLevelOfWire(session.permissionPreset) ?: PermissionStore.load(app)
        // 运行根取任务归属工作区映射的存储单元：会话被移到别的工作区后，任务目录不应随之漂移
        val workspace = rootOf(task.workspaceId, app)
        val registry = ToolRegistry()
        // 提示词输入只构造一次：既是请求来源，也是用量构成的估算依据，两者必须同源
        val promptInput = PromptInput(workspacePath = workspace.absolutePath, permission = permission)
        // 携带目标会话已有的上下文：与聊天页同一口径，不做「本任务历次投递」的删减。
        // 否则周期任务看不到自己上一轮说了什么，「与上次相比」「累计」这类要求无法完成。
        val messages = historyOf(session)
        // 请求视图与聊天页同一套裁剪：会话有摘要时只发摘要与分界之后的原文。
        // 后台不生成摘要，只沿用聊天页生成的那份，因此这里唯一的输入是会话记录上的两个字段。
        val summary = scheduledSummaryState(messages, session.contextSummary, session.summaryUpTo)
        FileLog.log(
            TAG,
            "历史携带 " + messages.size + " 条，摘要 " +
                (if (summary.text.isEmpty()) "无" else "分界 " + summary.upTo)
        )
        val model = ApiConfig.loadModel(app)
        val loop = AgentLoop(
            provider = DeepSeekProvider(
                apiKey = ApiConfig.loadApiKey(app),
                baseUrl = ApiConfig.loadBaseUrl(app)
            ),
            registry = registry,
            model = model,
            maxRounds = MAX_ROUNDS,
            // 系统提示词其余项均使用 PromptInput 的默认值，仅补充实际权限级别与工作区路径
            promptProvider = { promptInput },
            // 摘要状态交给 AgentLoop 裁剪请求视图；压缩策略不传：后台没有「摘要失败」这一运行时标记，
            // 策略在请求侧的唯一用途是兜底裁轮，传进去也不会生效。
            contextState = { summary }
        )
        // 本轮边界与答复提取走 [TurnOutcome]：与聊天页同一口径，历史消息不参与，
        // 请求失败、本轮没有产出正文时答复为空串，不会把上一轮的答复当成本轮结果写回。
        val turnStart = messages.size
        // 后台任务同样计入用量账本：每次请求一到就记（与聊天页同一口径），
        // 否则定时任务的消耗在「上下文洞察」里完全看不到
        val turn = TurnUsage()
        val startedAt = System.currentTimeMillis()
        val ui = SilentUi(
            usageSink = { buckets ->
                val modelName = usageModel(app)
                // 构成估算与真实 prompt 同源：先算一次，账本与消息共用同一份
                val composition = estimateComposition(
                    input = promptInput,
                    registry = registry,
                    messages = messages,
                    summary = summary,
                    promptTokens = buckets.promptTokens,
                    completionTokens = buckets.completionTokens
                )
                recordUsage(app, buckets, modelName, composition)
                turn.add(buckets, modelName, composition)
            },
            timingSink = { modelMillis, toolMillis -> turn.addTiming(modelMillis, toolMillis) },
            outputSink = { timing -> turn.addOutput(timing) },
            roundSink = { round -> turn.noteRound(round) }
        )
        // 后台无界面展示思考过程：关闭思考以节省 token，同时避免「历史中必须回传 reasoning_content」的约束
        val rounds = loop.run(
            messages = messages,
            // messages 是本轮开始前的快照，不含刚落盘的提问：这里由循环追加，避免同句出现两次。
            // programmatic = true：无人值守的执行不把任务文本当作高危授权。
            userText = task.prompt,
            userTextProgrammatic = true,
            thinking = false,
            thinkingEffort = null,
            toolContext = ToolContext(
                context = app,
                permission = permission,
                workspaceRoot = workspace,
                sessionId = session.id
            ),
            ui = ui,
            // 后台无人值守：达到工具轮数上限时以空工具表收尾，保证本轮仍有正文
            unattended = true
        )
        // 时间账按「这次执行」记一次：与聊天页一样，失败的那一轮也要留下已消耗的等待与生成时间
        UsageLedger.recordTiming(
            context = app,
            modelMs = turn.modelMillis,
            toolMs = turn.toolMillis,
            activeMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            ttftMs = turn.ttftMillis,
            genMs = turn.genMillis,
            reasoningMs = turn.reasoningMillis,
            textMs = turn.textMillis,
            toolArgMs = turn.toolArgMillis,
            genTokens = turn.genTokens,
            toolCalls = turn.toolCalls
        )
        // 与聊天页同一口径：答复只来自本轮新增消息，历史里的上一轮答复不参与
        val outcome = TurnOutcome(rounds, messages.drop(turnStart))
        val content = outcome.reply
        FileLog.log(
            TAG,
            "本轮新增 " + outcome.added.size + " 条消息，取到答复 " + content.length +
                " 字（开跑前 " + turnStart + " 条）"
        )
        if (content.isEmpty()) {
            // 通知正文要能区分「没产出正文」与「请求失败 / 上下文溢出」：
            // ui.errors 里已带提供方原文（如「请求失败（HTTP 400）：…」），为空说明本轮确实没有正文
            val detail = ui.errors.toString().ifEmpty { "模型未返回任何内容" }
            return RoundResult("", "未产出答复：" + detail)
        }
        return RoundResult(content, "", turn)
    }

    /**
     * 取目标会话的全部消息，原样交出。
     *
     * 定时任务与普通对话的差别只在触发方式：一端由用户输入，一端由闹钟投递，上下文口径不另立规则。
     * 周期性任务常需要「与上次相比」「本周累计」这类延续信息，任何按时间或条数切出的窗口都会
     * 让任务看不到自己上一轮做了什么；而且逐轮移动的窗口会使请求前缀每轮都不同，前缀缓存无法命中。
     *
     * 请求视图不在这里裁剪：与聊天页一样由 AgentLoop 按会话摘要生成（摘要 + 分界之后的原文），
     * 没有被摘要覆盖的部分才全文发送。工具消息的配对（孤立 tool、结果不完整的轮次）由 sanitized() 清洗。
     */
    private fun historyOf(session: ChatSession): MutableList<ChatMessage> =
        scheduledHistoryOf(session.messages)

    /**
     * [historyOf] 的实现，抽成纯函数以便单测：保持原顺序原样返回。
     *
     * 摘要分界不在这里处理：它属于「请求视图」而不是「消息列表」，见 [scheduledSummaryState]。
     */
    internal fun scheduledHistoryOf(messages: List<ChatMessage>): MutableList<ChatMessage> =
        ArrayList(messages)

    /**
     * 本轮请求视图的摘要状态，抽成纯函数以便单测。
     *
     * 与聊天页同一规则（[validSummaryUpTo]）：分界落在 user 消息上才生效，失效按没有摘要处理，
     * 此时请求视图为全部原文。后台不产生摘要，也不存在「摘要失败」的兜底标记，故不使用 fallback。
     */
    internal fun scheduledSummaryState(
        messages: List<ChatMessage>,
        summary: String,
        savedUpTo: Int
    ): ContextSummaryState {
        if (summary.isEmpty()) return ContextSummaryState()
        val upTo = validSummaryUpTo(messages, savedUpTo)
        if (upTo <= 0) return ContextSummaryState()
        return ContextSummaryState(text = summary, upTo = upTo)
    }

    /**
     * 目标会话：存在 id 则续用该会话（已被删除或已归档由调用方提前关闭开关，不会走到这里）。
     *
     * 选择「新会话」时仅在首次触发时创建一条，并将 id 写回任务：
     * 若每次都新建，同一任务每轮的上下文都会从零开始，用户测试「第二次回复二」时始终只回复一，
     * 侧边栏也会不断累积一次性会话。新建的会话归任务自身的工作区（未指定即默认工作区）。
     */
    private fun resolveSession(app: Context, task: ScheduleTask): ChatSession {
        val targetId = task.targetSessionId?.takeIf { it.isNotEmpty() }
        if (targetId != null) {
            val existing = SessionStore.load(app).firstOrNull { it.id == targetId }
            if (existing != null) return promoteIfHidden(app, existing)
            FileLog.log(TAG, "目标会话已不存在，改为新建：" + targetId)
        }
        val workspaceId = task.workspaceId?.takeIf { it.isNotEmpty() } ?: WorkSpaceStore.DEFAULT_ID
        val space = WorkSpaceStore.load(app).firstOrNull { it.id == workspaceId }
            ?: WorkSpaceStore.defaultSpace(app)
        // 标题先使用任务名：便于在侧边栏识别该会话来自哪个定时任务（已有标题后聊天不会覆盖）
        val created = SessionStore.newSession(
            storageId = space.storageId,
            listed = true,
            permissionPreset = PermissionStore.load(app).wireName(),
            reasoningEffort = effortWire(app)
        ).copy(
            title = task.title.take(TITLE_CHARS),
            workspaceId = space.id
        )
        // 立即持久化并写回任务：下一轮才能承接本轮上下文
        SessionStore.save(app, listOf(created) + SessionStore.load(app))
        ScheduleStore.upsert(app, task.copy(targetSessionId = created.id))
        ChatBridge.notifySessionsChanged()
        FileLog.log(TAG, "为任务「" + task.title + "」建立结果会话：" + created.id)
        return created
    }

    /**
     * 目标会话若是空白会话（进入工作区时预建、默认不进侧边栏），写入前先按聊天页
     * 「发出第一条消息」的同一口径转正。
     *
     * 否则定时结果与通知都在，侧边栏却没有这条会话，用户根本找不到
     * （2026-11-09 06:57 新闻早报实例：目标会话 blank 且未列出，早报写进去了但无处可见）。
     */
    private fun promoteIfHidden(app: Context, session: ChatSession): ChatSession {
        if (!session.blank && session.listed) return session
        val promoted = session.copy(blank = false, listed = true)
        SessionStore.saveMerged(app, listOf(promoted))
        ChatBridge.notifySessionsChanged()
        FileLog.log(TAG, "目标会话原为空白或未列出，已转正：" + session.id)
        return promoted
    }

    /**
     * 任务归属工作区的运行根。
     *
     * 按任务自身记录的工作区解析，而不是按目标会话的 `workspaceId`：
     * 会话被移到别的工作区或转为未分类后，任务目录不应随之漂移。
     */
    private fun rootOf(workspaceId: String?, app: Context): File {
        val space = WorkSpaceStore.load(app).firstOrNull { it.id == workspaceId }
            ?: WorkSpaceStore.defaultSpace(app)
        val storage = StorageStore.find(app, space.storageId)
        val root = storage?.let { StorageStore.ensureRoot(app, it) }
        if (root != null) return root
        FileLog.log(TAG, "任务归属工作区的目录不可用，退回默认目录")
        return PathGate.workspaceRoot(app)
    }

    /** 全局思考强度的落盘名，供新建的结果会话做快照。 */
    private fun effortWire(app: Context): String =
        if (!ApiConfig.loadThinkingEnabled(app)) {
            SessionPreference.EFFORT_OFF
        } else {
            SessionPreference.effortOrNull(ApiConfig.loadThinkingEffort(app))
                ?: SessionPreference.EFFORT_DEFAULT
        }

    /**
     * 注册到 [ScheduleBridge]：归档会话时由聊天域与助手工具调用，
     * 把以该会话为目标的任务停用并解绑（单向，恢复会话不会自动重绑）。
     */
    fun installBridge(context: Context) {
        val app = context.applicationContext
        ScheduleBridge.install(object : ScheduleBridge.Port {
            override fun affectsSession(sessionId: String): Boolean =
                ScheduleStore.load(app).any { it.targetSessionId == sessionId }

            override fun detachSession(sessionId: String): Int {
                val tasks = ScheduleStore.load(app).filter { it.targetSessionId == sessionId }
                if (tasks.isEmpty()) return 0
                for (task in tasks) {
                    ScheduleStore.upsert(
                        app,
                        task.copy(enabled = false, nextAt = 0L, targetSessionId = null)
                    )
                    cancel(app, task.id)
                }
                FileLog.log(TAG, tasks.size.toString() + " 个任务因目标会话被归档而停用并解绑")
                return tasks.size
            }

            override fun create(spec: ScheduleSpec): ScheduleInfo {
                val task = ScheduleTask(
                    title = spec.title,
                    // 未知类型按一次性处理：宁可少触发一次，也不把用户的意图变成周期任务
                    kind = when (spec.kind) {
                        ScheduleStore.KIND_DAILY -> ScheduleStore.KIND_DAILY
                        ScheduleStore.KIND_EVERY -> ScheduleStore.KIND_EVERY
                        else -> ScheduleStore.KIND_ONCE
                    },
                    everyMinutes = spec.everyMinutes,
                    timeOfDay = spec.timeOfDay.ifEmpty { ScheduleStore.DEFAULT_TIME_OF_DAY },
                    atMillis = spec.atMillis,
                    prompt = spec.prompt,
                    targetSessionId = spec.targetSessionId,
                    workspaceId = spec.workspaceId,
                    enabled = true
                )
                val stored = arm(app, task)
                FileLog.log(TAG, "助手创建定时任务「" + stored.title + "」at=" + timeText(stored.nextAt))
                return infoOf(stored)
            }

            override fun list(): List<ScheduleInfo> =
                ScheduleStore.load(app).map { infoOf(it) }

            override fun delete(id: String): Boolean {
                val exists = ScheduleStore.load(app).any { it.id == id }
                if (!exists) return false
                ScheduleStore.remove(app, id)
                cancel(app, id)
                FileLog.log(TAG, "助手删除定时任务 id=" + id)
                return true
            }
        })
    }

    /** 把持久化记录转换为窄口快照；字段一一对应，不额外计算。 */
    private fun infoOf(task: ScheduleTask): ScheduleInfo = ScheduleInfo(
        id = task.id,
        title = task.title,
        prompt = task.prompt,
        kind = task.kind,
        everyMinutes = task.everyMinutes,
        timeOfDay = task.timeOfDay,
        atMillis = task.atMillis,
        targetSessionId = task.targetSessionId,
        workspaceId = task.workspaceId,
        enabled = task.enabled,
        nextAt = task.nextAt,
        lastRunAt = task.lastRunAt
    )

    /**
     * 先把本轮提问落进会话（与聊天页「先有用户消息，再有回复」同序）。
     *
     * programmatic = true：这句是定时系统代发的，高危操作的一次性授权只认非代发消息，
     * 因此定时任务在无人值守时不会获得高危授权。
     * origin = schedule：统计口径仍按「正常任务」计一轮，唤醒来源不影响轮次。
     */
    private fun appendPrompt(app: Context, session: ChatSession, task: ScheduleTask) {
        val prompt = ChatMessage(
            role = "user",
            content = task.prompt,
            programmatic = true,
            origin = ORIGIN_SCHEDULE
        )
        if (SessionStore.appendMessages(app, session.id, listOf(prompt))) return
        // 开跑前目标会话被删除：把提问写进新会话，后续答复才有地方落
        val all = SessionStore.load(app)
        val revived = session.copy(
            title = session.title.ifEmpty { task.title.take(TITLE_CHARS) },
            messages = session.messages + prompt,
            updatedAt = System.currentTimeMillis()
        )
        SessionStore.save(app, listOf(revived) + all.filterNot { it.id == revived.id })
        FileLog.log(TAG, "目标会话已不存在，提问写入新会话：" + revived.id)
    }

    /**
     * 把本轮最终答复追加进会话。
     *
     * 只写最终答复：工具中间态（包括可能被轮数上限截断的不完整轮次）不写入历史，
     * 下次打开该会话时呈现一问一答，而非一批缺少上下文的工具卡片。
     * 用量写在 assistant 上：会话级「Token 统计」与用量面板都按消息读取。
     */
    private fun appendReply(
        app: Context,
        session: ChatSession,
        task: ScheduleTask,
        content: String,
        usage: TurnUsage
    ) {
        val now = System.currentTimeMillis()
        val reply = ChatMessage(
            role = "assistant",
            content = content,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            cachedTokens = usage.cachedTokens,
            uncachedInputTokens = usage.uncachedTokens,
            usageCacheReported = usage.cacheReported,
            usageModel = usage.model
        )
        // 会话累计：用量、步数、时间账一并写入，洞察页的会话卡片才有数
        val delta = usageDelta(usage, includeSteps = true)
        // 仅修改目标会话：整表读改写会与界面争夺快照，覆盖刚追加的问答
        if (SessionStore.appendMessages(app, session.id, listOf(reply), delta)) {
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
        // 目标会话恰好被删除：答复写入新会话，避免内容丢失
        val all = SessionStore.load(app)
        SessionStore.save(
            app,
            listOf(
                session.copy(
                    title = session.title.ifEmpty { task.title.take(TITLE_CHARS) },
                    messages = session.messages + reply,
                    updatedAt = now
                ).foldUsage(delta)
            ) + all
        )
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

    /**
     * 通知里的失败原因：只给用户可执行的说明，原始错误（异常类名、服务端错误码）
     * 留在日志中，避免把技术细节直接推到通知栏。
     */
    private fun failureText(raw: String): String =
        if (raw.startsWith("未产出答复")) {
            "模型未返回内容，可能是请求被拒绝或上下文过长；请检查模型设置后重试。"
        } else {
            "本轮执行中断，请检查网络连接后重试。"
        }

    private fun timeText(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))

    /** 单次执行结果：content 非空表示获得答复；error 为原始失败原因，仅用于日志与排障。 */
    private data class RoundResult(
        val content: String,
        val error: String,
        val usage: TurnUsage = TurnUsage()
    )

    /**
     * 一轮的用量累计。
     *
     * 与聊天页的口径一致：缓存命中/未命中只累加服务端确实上报过的那几次；
     * 没有上报时不写 0，消息上的 `usageCacheReported` 为 false，面板显示「—」而不是 0%。
     */
    private class TurnUsage {
        var promptTokens = 0
        var completionTokens = 0
        var cachedTokens = 0
        var uncachedTokens = 0
        var cacheReported = false
        var model = ""
        /** 成功的模型请求次数（主会话与子会话各自累加）。 */
        var requests = 0
        /** 本次回复跑过的最大轮次，即会话累计的「步数」。 */
        var steps = 0
        var composition = SessionComposition()
        var modelMillis = 0L
        var toolMillis = 0L
        var ttftMillis = 0L
        var ttftCount = 0
        var genMillis = 0L
        var genTokens = 0
        var reasoningMillis = 0L
        var textMillis = 0L
        var toolArgMillis = 0L
        var toolCalls = 0

        fun add(buckets: UsageBuckets, modelName: String, parts: SessionComposition) {
            promptTokens += buckets.promptTokens
            completionTokens += buckets.completionTokens
            requests++
            if (buckets.cacheReadReported) {
                cachedTokens += buckets.cacheReadTokens
                uncachedTokens += buckets.uncachedInputTokens
                cacheReported = true
            }
            composition += parts
            if (modelName.isNotEmpty()) model = modelName
        }

        fun addTiming(modelMillis: Long, toolMillis: Long) {
            this.modelMillis += modelMillis
            this.toolMillis += toolMillis
        }

        fun addOutput(timing: OutputTiming) {
            ttftMillis += timing.ttftMillis
            ttftCount += timing.ttftCount
            genMillis += timing.genMillis
            genTokens += timing.genTokens
            reasoningMillis += timing.reasoningMillis
            textMillis += timing.textMillis
            toolArgMillis += timing.toolArgMillis
            toolCalls += timing.toolCalls
        }

        fun noteRound(round: Int) {
            if (round > steps) steps = round
        }
    }

    /** 后台执行没有界面：收集错误供通知使用，用量与耗时交给调用方记账。 */
    private class SilentUi(
        private val usageSink: (UsageBuckets) -> Unit,
        private val timingSink: (Long, Long) -> Unit,
        private val outputSink: (OutputTiming) -> Unit,
        private val roundSink: (Int) -> Unit
    ) : AgentUi {
        val errors: StringBuilder = StringBuilder()

        override fun onRound(round: Int) = roundSink(round)

        override fun onToolStarted(call: ToolCall) = Unit

        override fun onToolFinished(call: ToolCall, result: ToolResult) = Unit

        override fun onUsage(usage: UsageBuckets) = usageSink(usage)

        override fun onTiming(modelMillis: Long, toolMillis: Long) =
            timingSink(modelMillis, toolMillis)

        override fun onOutputTiming(timing: OutputTiming) = outputSink(timing)

        override fun onError(message: String) {
            errors.append(if (errors.isEmpty()) "" else "；").append(message)
        }
    }

    /**
     * 把本轮累计换算成会话可用的增量。
     *
     * [includeSteps] 为 false 时不计步数：失败的那次没有产出，只记花掉的钱与时间。
     */
    private fun usageDelta(usage: TurnUsage, includeSteps: Boolean): SessionUsageDelta =
        SessionUsageDelta(
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            requests = usage.requests,
            steps = if (includeSteps) usage.steps else 0,
            toolCalls = if (includeSteps) usage.toolCalls else 0,
            cacheHitTokens = if (usage.cacheReported) usage.cachedTokens else 0,
            cacheMissTokens = if (usage.cacheReported) usage.uncachedTokens else 0,
            composition = usage.composition,
            modelMillis = usage.modelMillis,
            toolMillis = usage.toolMillis,
            lastTurnModelMillis = usage.modelMillis,
            lastTurnCompletionTokens = usage.completionTokens,
            ttftMillis = usage.ttftMillis,
            ttftCount = usage.ttftCount,
            genMillis = usage.genMillis,
            genTokens = usage.genTokens,
            reasoningMillis = usage.reasoningMillis,
            textMillis = usage.textMillis,
            toolArgMillis = usage.toolArgMillis
        )

    /** 用量面板显示的服务商/模型，与聊天页 currentUsageModel 同一拼法。 */
    private fun usageModel(app: Context): String {
        val provider = runCatching { ProviderStore.current(app).id }.getOrNull().orEmpty()
        return (provider.ifEmpty { "内置" }) + "/" + ApiConfig.loadModel(app)
    }

    /**
     * 把一次请求的用量写进全局账本。
     *
     * 为什么要在这里写：账本原本只有聊天页在记，定时任务的请求同样计费，
     * 不写就会出现「消耗了 token，但上下文洞察里查不到」。
     */
    private fun recordUsage(
        app: Context,
        usage: UsageBuckets,
        model: String,
        composition: SessionComposition
    ) {
        val hit = if (usage.cacheReadReported) usage.cacheReadTokens else 0
        val miss = if (usage.cacheReadReported) usage.uncachedInputTokens else 0
        val cost = CostBook.costOf(
            usageModel = model,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            cachedTokens = hit,
            uncachedTokens = miss,
            cacheWriteTokens = usage.cacheWriteTokens ?: 0,
            atMillis = System.currentTimeMillis()
        )
        UsageLedger.record(
            context = app,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            cacheHitTokens = hit,
            cacheMissTokens = miss,
            composition = LedgerComposition(
                system = composition.system,
                skills = composition.skills,
                tools = composition.tools,
                user = composition.user,
                inject = composition.inject,
                assistant = composition.assistant,
                toolResults = composition.toolResults,
                output = composition.output
            ),
            costCny = cost ?: 0.0
        )
    }

    /**
     * 计费构成估算：与聊天页的 scaledComposition 同一口径，按真实 prompt tokens 等比折算。
     *
     * 消息取「真正发出去的那份请求视图」：有摘要时从分界起算，摘要本身计入「注入内容」。
     * 提示词与工具表都取自同一次请求的输入，估算才与真实请求一致。
     */
    private fun estimateComposition(
        input: PromptInput,
        registry: ToolRegistry,
        messages: List<ChatMessage>,
        summary: ContextSummaryState,
        promptTokens: Int,
        completionTokens: Int
    ): SessionComposition {
        // AgentLoop 只在分界合法时使用摘要，这里与之同源：分界为 0 即发送全部原文
        val start = if (summary.text.isEmpty()) 0 else summary.upTo.coerceIn(0, messages.size)
        val promptText = PromptBuilder.build(input)
        val skills = PromptBuilder.skillSectionTokens(input)
        val system = ((promptText?.let { ContextCompactor.estimateText(it) } ?: 0) - skills)
            .coerceAtLeast(0)
        val inject = if (start > 0) {
            ContextCompactor.estimateText(contextSummarySystemText(summary.text))
        } else {
            0
        }
        val specs = registry.specs()
        val tools = if (specs.isEmpty()) {
            0
        } else {
            ContextCompactor.estimateText(
                specs.joinToString("\n") { it.name + "\n" + it.description + "\n" + it.parametersJson }
            )
        }
        var user = 0
        var assistant = 0
        var toolResults = 0
        for (i in start until messages.size) {
            when (messages[i].role) {
                "user" -> user += ContextCompactor.estimate(messages[i])
                "assistant" -> assistant += ContextCompactor.estimate(messages[i])
                "tool" -> toolResults += ContextCompactor.estimate(messages[i])
                else -> Unit
            }
        }
        val total = system + skills + tools + inject + user + assistant + toolResults
        if (total <= 0) return SessionComposition(output = completionTokens)
        fun part(value: Int): Int =
            (value.toDouble() * promptTokens / total).roundToInt().coerceAtLeast(0)
        return SessionComposition(
            system = part(system),
            skills = part(skills),
            tools = part(tools),
            user = part(user),
            inject = part(inject),
            assistant = part(assistant),
            toolResults = part(toolResults),
            output = completionTokens
        )
    }
}
