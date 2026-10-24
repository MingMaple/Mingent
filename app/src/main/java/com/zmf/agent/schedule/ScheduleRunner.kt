package com.zmf.agent.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.zmf.agent.agent.ApiConfig
import com.zmf.agent.agent.loop.AgentLoop
import com.zmf.agent.agent.loop.AgentUi
import com.zmf.agent.agent.permission.PermissionStore
import com.zmf.agent.agent.prompt.PromptInput
import com.zmf.agent.agent.provider.DeepSeekProvider
import com.zmf.agent.agent.session.ChatSession
import com.zmf.agent.agent.session.SessionStore
import com.zmf.agent.agent.workspace.WorkSpaceStore
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.core.notify.Notifier
import com.zmf.agent.service.AgentForegroundService
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolRegistry
import com.zmf.agent.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时任务的执行端：排闹钟、到点跑一轮 Agent、把结果写回会话、发通知。
 *
 * 分工：[ScheduleReceiver] 只做广播转发，真正的工作全在这里；[ScheduleStore] 只管数据。
 *
 * 为什么跑一轮要拉常驻前台服务：广播唤醒的进程一回到后台就可能被回收，
 * 而一轮 Agent（含工具调用）可能跑几分钟，光靠 BroadcastReceiver 的 onReceive 预算扛不住。
 */
object ScheduleRunner {

    private const val TAG = "schedule"

    /** 单次任务的工具轮数上限：后台没人看着，别让它自己一直转下去。 */
    private const val MAX_ROUNDS = 8

    /** 失败重试次数（含第一次）：一次网络抖动不该让任务白等一个周期。 */
    private const val MAX_ATTEMPTS = 2

    /** 通知正文只放开头这么多字，完整答复在会话里。 */
    private const val PREVIEW_CHARS = 400

    /** 会话标题最长取任务名前多少个字（侧边栏一行放得下）。 */
    private const val TITLE_CHARS = 20

    /** 已经错过的时间里的一律「马上」跑：等一个周期再跑，用户会以为任务丢了。 */
    private const val IMMEDIATE_DELAY_MS = 5_000L

    /** 应用级作用域：广播返回后协程还得继续跑，所以不能挂在 Receiver 的生命周期上。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- 排期 ----

    /**
     * 按任务当前配置算下一次，落盘 + 排闹钟，返回落盘后的副本（界面直接拿它刷新列表）。
     *
     * 停用就只取消闹钟；算不出未来时刻（once 的时刻已经过了）就排到「马上」，
     * 静默排不上比迟到更糟——用户开开关就是想让它跑。
     */
    fun arm(context: Context, task: ScheduleTask): ScheduleTask {
        val app = context.applicationContext
        if (!task.enabled) {
            cancel(app, task.id)
            return ScheduleStore.upsert(app, task.copy(nextAt = 0L))
        }
        val now = System.currentTimeMillis()
        val computed = ScheduleStore.nextAfter(task, now)
        val nextAt = if (computed > now) computed else now + IMMEDIATE_DELAY_MS
        val stored = ScheduleStore.upsert(app, task.copy(nextAt = nextAt))
        schedule(app, stored)
        return stored
    }

    /** 排下一次闹钟；停用 / 没排期就取消，保证「数据」和「闹钟」不会各说各话。 */
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
        val exact = runCatching {
            // 用 setExactAndAllowWhileIdle 而不是 set：Doze 里也能准点叫醒，间隔分钟才不会被系统拖成几小时
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.nextAt, pi)
        }
        if (exact.isFailure) {
            // 个别 Android 12+ 机型按「没有精确闹钟授权」抛 SecurityException：
            // 退回非精确闹钟（可能晚几分钟），宁可迟到也不能永远不跑
            FileLog.log(TAG, "精确闹钟排不上（" + exact.exceptionOrNull() + "），改用非精确")
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.nextAt, pi) }
                .onFailure { FileLog.log(TAG, "闹钟排不上：" + it) }
        }
        FileLog.log(TAG, "排期「" + task.title + "」at=" + timeText(task.nextAt))
    }

    /** 取消一条任务的闹钟（任务删除 / 停用时调）。 */
    fun cancel(context: Context, taskId: String) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(pendingIntent(app, taskId)) }
    }

    /**
     * 兜底重排：闹钟在重启 / 强停后全丢，而工程没有 RECEIVE_BOOT_COMPLETED（不加权限），
     * 所以每次打开 App 调一次，把所有已启用任务的闹钟重新挂上。
     *
     * 还没到点的保持原路（每次都往后推会把「每 60 分钟」越推越晚）；
     * 已经错过的才重算：周期任务只排下一次（不补跑，免得一口气连跑好几遍），一次性任务当场补跑。
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

    /** 闹钟到点：先把进程钉住（前台服务），再交给应用级协程跑，广播本身立刻返回。 */
    fun fire(context: Context, taskId: String) {
        val app = context.applicationContext
        AgentForegroundService.start(app)
        scope.launch {
            runCatching { runOnce(app, taskId) }
                .onFailure { FileLog.log(TAG, "任务 " + taskId + " 执行异常：" + it) }
        }
    }

    /** 跑一次：先落「已触发」并排好下一次，再请求模型，最后写会话 + 发通知。 */
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
        // 先落「已触发」再真跑：进程中途被杀，下次开机重排也不会把这次当成没跑过再来一遍。
        // 一次性任务跑完即停用（nextAt 归零）；周期性任务算不出下一次（时刻写坏）也一并停用，
        // 留一条永远排不上的任务比停用更让人困惑。
        val keepRunning = task.kind != ScheduleStore.KIND_ONCE && computed > now
        val fired = task.copy(
            lastRunAt = now,
            enabled = keepRunning,
            nextAt = if (keepRunning) computed else 0L
        )
        ScheduleStore.upsert(app, fired)
        if (keepRunning) schedule(app, fired) else cancel(app, taskId)

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
            Notifier.notifySchedule(app, "定时任务完成", preview(content), session.id)
        } else {
            Notifier.notifySchedule(app, "定时任务失败", task.title + "：" + reason, session.id)
        }
    }

    /**
     * 一轮最简 Agent 往返：新建 messages、用默认提示词输入、跑完取最后一条有内容的 assistant。
     *
     * 不复用 ChatViewModel 的状态是故意的：定时任务在后台跑，界面上那条会话可能正被用户编辑，
     * 共用一份消息列表只会互相踩。
     */
    private suspend fun runRound(app: Context, session: ChatSession, prompt: String): RoundResult {
        val permission = PermissionStore.load(app)
        // 跟着目标会话自己的工作区跑：不然工具的相对路径落点会和会话里看到的目录对不上
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
            // 系统提示词其余项都吃 PromptInput 的默认值，只补真实的权限档与工作区路径
            promptProvider = { PromptInput(workspacePath = workspace.absolutePath, permission = permission) }
        )
        val messages = mutableListOf<ChatMessage>()
        val ui = SilentUi()
        // 后台没人看思考过程：关掉思考省 token，也少一条「历史里必须回传 reasoning_content」的约束
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

    /** 目标会话：有 id 就续那条（被删了退成新建），null 就每次新开一条。 */
    private fun resolveSession(app: Context, task: ScheduleTask): ChatSession {
        val targetId = task.targetSessionId?.takeIf { it.isNotEmpty() }
        if (targetId != null) {
            val existing = SessionStore.load(app).firstOrNull { it.id == targetId }
            if (existing != null) return existing
            FileLog.log(TAG, "目标会话已不存在，改为新建：" + targetId)
        }
        // 标题先用任务名：侧边栏一眼能认出这条会话是哪个定时任务产出的（有标题后聊天不会覆盖它）
        return SessionStore.newSession().copy(title = task.title.take(TITLE_CHARS))
    }

    /**
     * 把结果追加进会话。
     *
     * 只补「提问 + 最终答复」：工具中间态（可能还有被轮数上限截断的半截轮次）不进历史，
     * 下次打开这条会话看到的是一问一答，而不是一堆没有上下文的工具卡。
     * 现读现写同样是为了尽量少丢 ChatViewModel 那边的更新（两边都是整份覆盖写）。
     */
    private fun appendResult(app: Context, session: ChatSession, task: ScheduleTask, content: String) {
        val now = System.currentTimeMillis()
        val added = listOf(
            ChatMessage(role = "user", content = task.prompt),
            ChatMessage(role = "assistant", content = content)
        )
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
            // 目标会话刚好被删：结果落进一条新会话，别让它凭空消失
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
     * 每条任务一个独立 PendingIntent。
     *
     * requestCode 用 id 的 hash：不同任务互不覆盖；FLAG_UPDATE_CURRENT 让「改了时间」能落到同一个闹钟上；
     * FLAG_IMMUTABLE 是 Android 12+ 的硬要求（minSdk 26 上多带无副作用）。
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

    /** 通知正文只放开头一段：完整答复已经写进会话，点通知就能看到。 */
    private fun preview(content: String): String =
        if (content.length <= PREVIEW_CHARS) content else content.take(PREVIEW_CHARS) + "…"

    private fun timeText(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))

    /** 单次执行的结果：content 非空＝拿到答复；error 是失败原因（也用来拼通知正文）。 */
    private data class RoundResult(val content: String, val error: String)

    /** 后台执行没有界面：只把错误攒起来给通知用，其余回调都是空动作。 */
    private class SilentUi : AgentUi {
        val errors: StringBuilder = StringBuilder()

        override fun onRound(round: Int) = Unit

        override fun onToolStarted(call: ToolCall) = Unit

        override fun onToolFinished(call: ToolCall, result: ToolResult) = Unit

        override fun onUsage(promptTokens: Int, completionTokens: Int) = Unit

        override fun onError(message: String) {
            errors.append(if (errors.isEmpty()) "" else "；").append(message)
        }
    }
}
