package com.zmf.agent.feature.chat

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.zmf.agent.capability.loop.AgentLoop
import com.zmf.agent.capability.loop.TurnOutcome
import com.zmf.agent.capability.runtime.AskBridge
import com.zmf.agent.capability.subagent.SubAgentRunner
import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.displayName
import com.zmf.agent.capability.tools.file.present.PresentArtifacts
import com.zmf.agent.core.AppForeground
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.BusySendMode
import com.zmf.agent.core.model.ABORTED_BEFORE_DISPATCH
import com.zmf.agent.core.model.ABORTED_IN_FLIGHT
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.model.ProducedArtifact
import com.zmf.agent.core.notify.Notifier
import com.zmf.agent.core.store.Goal
import com.zmf.agent.core.store.GoalPhase
import com.zmf.agent.core.store.GoalStore
import com.zmf.agent.core.store.QueueStore
import com.zmf.agent.core.store.SessionBusy
import com.zmf.agent.core.store.UploadStore
import com.zmf.agent.core.store.UsageLedger
import com.zmf.agent.core.prefs.GeneralPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 目标自动续跑的轮次上限。
 *
 * 上限是为了防跑飞而不是省钱：目标跑歪了、模型自己判不出做完时，
 * 八轮之后必须停下等待用户确认，不能依赖模型自行结束。
 */
internal const val MAX_GOAL_ROUNDS = 8

/**
 * 连续「空闲唤醒」的上限。
 *
 * 后台任务结算会唤醒会话去读结果；被唤醒的回合若又起任务、又结算，就形成自激链，
 * 用户不在场时无谓消耗 token。三次足以覆盖正常的连续任务，超过后应等待用户回到前台。
 */
internal const val MAX_CONSECUTIVE_WAKES = 3

/** 中止等待的记账间隔：组件不响应取消时，按这个节奏把已等待时长写进日志。 */
private const val ABORT_WATCH_STEP_MS = 5_000L

/** 中止等待日志的 logcat 标签（真机 logcat 无输出时以文件日志为准）。 */
private const val ABORT_LOG_TAG = "MingentAbort"

/**
 * 回合状态：运行 → 中止中 → 空闲。
 *
 * 界面（输入条右侧按钮、发送键）只看这个状态，不再按「回合还在不在 [SendController] 的 turns 里」推断：
 * 点停止后回合仍留在 turns 中，直到取消、落盘、释放会话占用全部做完才转空闲，
 * 因此整段收尾期间界面稳定停在「中止中」，不会出现「界面已空闲、忙状态还没释放」的假空闲。
 */
enum class TurnPhase { IDLE, RUNNING, ABORTING }

/**
 * 发送与流式回合：发消息、排队/插话、续跑、重新生成、回滚、删改与停止。
 *
 * 从 [ChatViewModel] 拆出，行为不变：状态字段名与原成员逐字一致，方法体原样保留；
 * 跨模块依赖一律改为构造注入（当前上下文、会话、输入区、Agent 循环、界面回调入口），
 * 避免该类反向持有整个 ViewModel。
 */
class SendController(
    private val appContext: Context,
    private val messagesOf: (String) -> SnapshotStateList<ChatMessage>,
    /** 一轮开始/收尾：回合账目按会话记（用户 2026-11-15：每条会话各跑一个回合）。 */
    private val beginTurn: (String) -> Unit,
    private val endTurn: (String) -> Unit,
    private val context: ContextState,
    private val session: SessionController,
    private val composer: ComposerState,
    private val scope: CoroutineScope,
    /** 每个回合新建一个循环实例：共用一个会互相覆盖计时与缓存指纹等账目（见 [LlmConfigController.newLoop]）。 */
    private val newLoop: () -> AgentLoop,
    /** 每个回合一份界面回调，绑死它自己那条会话。 */
    private val agentUiFor: (String) -> TurnAgentUi,
    private val slashCommand: (String) -> Boolean,
    private val restartBlocked: () -> Boolean,
    private val readError: () -> String?,
    private val reportError: (String?) -> Unit,
    private val setActivity: (String, String?) -> Unit,
    private val thinkingOn: () -> Boolean,
    private val thinkingEffortProvider: () -> String,
    private val permissionProvider: () -> PermissionLevel,
    private val planModeOn: () -> Boolean,
    private val goalNow: () -> Goal?,
    private val setGoal: (Goal) -> Unit,
    private val reloadTodos: () -> Unit,
    private val reloadPlans: () -> Unit,
    /** 收尾动作一律带会话：清的是**发起回合的那条会话**的清单与待执行计划。 */
    private val dropFinishedTodos: (String) -> Unit,
    private val abandonPendingPlans: (String) -> Unit
) {

    /** 一个正在跑的回合：任务本体 + 它自己那份界面回调（失败说明按回合记）。 */
    private class ActiveTurn(val ui: TurnAgentUi, val job: Job)

    /**
     * 正在跑的回合，按会话分开（用户 2026-11-15 要求：进 A 跑任务、切到 B 再让 B 跑，两条各自进行）。
     *
     * 用 `mutableStateMapOf`：界面读「当前会话是否在跑」要能被 Compose 观察到。
     * 同一会话仍只允许一个回合，重复发送走排队／插话。
     */
    private val turns = mutableStateMapOf<String, ActiveTurn>()

    /**
     * 每条会话的回合状态；[TurnPhase.IDLE] 不保留键。
     *
     * 这是界面的**唯一**运行态来源；[turns] 只管任务本体与界面回调，不再对外表达「在不在跑」。
     */
    private val phases = mutableStateMapOf<String, TurnPhase>()

    /** 中止等待的看门狗：每 5 秒记一条已等待时长，用于定位不响应取消的组件。 */
    private val abortWatchdogs = mutableMapOf<String, Job>()

    /** 这条会话的回合状态（界面据此决定右侧按钮形态与发送键可用性）。 */
    fun phaseOf(sessionId: String): TurnPhase = phases[sessionId] ?: TurnPhase.IDLE

    /** 这条会话是否仍占用回合：运行中与中止中都算（取消未收尾前不得再发）。 */
    fun isStreamingFor(sessionId: String): Boolean = phaseOf(sessionId) != TurnPhase.IDLE

    /** 是否有任意会话在跑（界面提示、状态点用）。 */
    val anyStreaming: Boolean get() = phases.isNotEmpty()

    /** 正在跑的会话集合。 */
    val turnSessions: Set<String> get() = phases.keys.toSet()

    /**
     * 本控制器写的那份正文：**当前显示的会话**那一份。
     *
     * 回合内部的写入一律用开局时钉住的那份（见 [startTurn] 里的 turnMessages），
     * 与显示会话无关——并行时两条会话各有各的正文列表。
     */
    private val messages: SnapshotStateList<ChatMessage>
        get() = messagesOf(session.currentSessionId)

    /** 繁忙（正在生成回复）时又发消息的行为：排队等本轮结束，或插进正在跑的这一轮。 */
    var busyMode by mutableStateOf(GeneralPrefs.loadBusyMode(appContext))
        private set

    /** 排队待发的消息，按会话分开：B 的队列不该被 A 的回合结束触发。 */
    private val queues = mutableMapOf<String, SnapshotStateList<QueuedMessage>>()

    /** 某条会话的队列（只读视图给界面；改动都走本控制器的方法）。 */
    fun queueOf(sessionId: String): SnapshotStateList<QueuedMessage> =
        queues.getOrPut(sessionId) { mutableStateListOf() }

    /** 队列 id 自增：同一毫秒连发两条也不会撞 id。 */
    private var nextQueueId = 1L

    // ---- P33：队列持久化 ----

    /**
     * 每次队列变更后整份落盘（条目少，同步写）。已领取但未完成的条目按「保留待发」处理：
     * continueAfterTurn 取走条目后才删键，进程在其间被杀则该条恢复回队列，与 P32 的中断标记一致。
     */
    private fun persistQueues() {
        val root = JSONObject()
        root.put("v", 1)
        root.put("nextId", nextQueueId)
        val sessions = JSONArray()
        for ((sessionId, queue) in queues) {
            if (queue.isEmpty()) continue
            val obj = JSONObject()
            obj.put("sessionId", sessionId)
            val items = JSONArray()
            for (item in queue) items.put(queuedMessageToJson(item))
            obj.put("items", items)
            sessions.put(obj)
        }
        root.put("sessions", sessions)
        QueueStore.save(appContext, root)
    }

    /** 启动时按会话恢复队列与原 id 计数（[ChatViewModel] 在 loadInitial 之后调用一次）。 */
    fun restoreQueues() {
        val root = QueueStore.load(appContext) ?: return
        val sessions = root.optJSONArray("sessions") ?: return
        var items = 0
        for (i in 0 until sessions.length()) {
            val obj = sessions.optJSONObject(i) ?: continue
            val sessionId = obj.optString("sessionId", "")
            if (sessionId.isEmpty()) continue
            val array = obj.optJSONArray("items") ?: continue
            val queue = queueOf(sessionId)
            if (queue.isNotEmpty()) continue // 已有队列（理论上不会）时不覆盖
            for (j in 0 until array.length()) {
                queuedMessageFromJson(array.optJSONObject(j) ?: continue)?.let {
                    queue.add(it)
                    items++
                }
            }
        }
        val savedNextId = root.optLong("nextId", 0L)
        if (savedNextId > nextQueueId) nextQueueId = savedNextId
        if (items > 0) FileLog.log("queue", "恢复队列：" + items + " 条待发消息（nextId=" + nextQueueId + "）")
    }

    private fun queuedMessageToJson(item: QueuedMessage): JSONObject {
        val obj = JSONObject()
        obj.put("id", item.id)
        if (item.text.isNotEmpty()) obj.put("text", item.text)
        fun files(list: List<PendingFile>): JSONArray {
            val array = JSONArray()
            for (file in list) {
                array.put(JSONObject().put("name", file.name).put("relPath", file.relPath))
            }
            return array
        }
        if (item.files.isNotEmpty()) obj.put("files", files(item.files))
        if (item.images.isNotEmpty()) obj.put("images", files(item.images))
        return obj
    }

    private fun queuedMessageFromJson(obj: JSONObject): QueuedMessage? {
        val id = obj.optLong("id", 0L)
        if (id <= 0L) return null
        fun files(key: String): List<PendingFile> {
            val array = obj.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<PendingFile>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val relPath = item.optString("relPath", "")
                if (relPath.isEmpty()) continue
                out.add(PendingFile(item.optString("name", ""), relPath))
            }
            return out
        }
        return QueuedMessage(id, obj.optString("text", ""), files("files"), files("images"))
    }

    /**
     * 有没被回答的插话，按会话记。
     *
     * 插话是「向历史中插入一条 user 消息」，模型可能在本轮后续请求中即予回答，
     * 也可能这一轮已经结束（见 streamJob 的 finally）：后者要再跑一个不追加用户消息的回合接上。
     */
    private val steerPendingSessions = mutableSetOf<String>()

    /**
     * 连续「空闲唤醒」的计数，按会话记。
     *
     * 唤醒指「后台任务结算后，代用户追问一轮」。被唤醒的回合若又创建任务并再次结算，
     * 就会持续自我唤醒，用户不在场时消耗 token。所以连续唤醒封顶 [MAX_CONSECUTIVE_WAKES]，
     * 用户下一次发消息时清零。达到上限后停止唤醒，已结算通知保留在队列中，待下一轮用户输入再注入。
     */
    private val consecutiveWakes = mutableMapOf<String, Int>()

    /**
     * 本轮结束后「已经排上了下一回合」，按会话记。
     *
     * 一轮收尾不是原子的：`finally` 末尾才把回合转空闲并摘掉，紧跟着 [continueAfterTurn] 可能另起一回合，
     * 从转空闲到续跑真正开跑之间仍有几毫秒不是流式状态。
     * 界面若只看「在不在跑」，过程区会在这段空档里折起来、下一段内容一到又展开。
     * 这个标记把「接着还要跑」如实报给界面：`isStreamingFor(id) || chainPendingFor(id)` 才是「这一轮还没完」。
     */
    private val chainSessions = mutableSetOf<String>()

    /** 这条会话是否已排上下一个回合（收尾到续跑之间的空档）。 */
    fun chainPendingFor(sessionId: String): Boolean = chainSessions.contains(sessionId)

    /**
     * 发送按钮回调（旧入口，留给还没把「发送」与「停止」拆成两个键的界面）。
     *
     * 语义已改为「发送一条消息」：空闲时直接发送，正在生成回复时按 [busyMode] 排队或插话。
     * 停止有独立的 [stop]：把发送键当停止键用，会让「排队/插话」这两个设置形同虚设。
     */
    fun onSendOrStop(text: String, scope: CoroutineScope) {
        sendWhileBusy(text, scope)
    }

    /**
     * 发消息的唯一入口：空闲直接发，繁忙时按 [busyMode] 排队或插话。
     *
     * [send] 本身在流式中仍然直接 return：「繁忙时该干什么」只在这里判一次，
     * 散成两处的话排队与插话迟早会走岔。
     *
     * 通道只看设置，与内容类型无关：插话把正文与附件一起补进本轮历史
     * （附件以工作区相对路径随消息交给模型），排队则把附件存进队列条目、本轮结束后随条目发出。
     */
    fun sendWhileBusy(text: String, scope: CoroutineScope): Boolean = sendUserMessage(
        text = text,
        // 取**当前会话**的待发槽：切到别的会话时那边的附件不属于这条提问
        files = composer.filesOf(session.currentSessionId),
        images = composer.imagesOf(session.currentSessionId),
        scope = scope,
        clearComposer = true,
        allowSlashCommands = true
    )

    /**
     * 发送一条用户消息的唯一实现：空闲直接发；繁忙时按 [busyMode] 排队或插话。
     *
     * 普通输入框与分享入口都走这里，差别只在参数：
     * - [clearComposer]：亲手输入为 true（附件与草稿已进这一条消息）；分享为 false，
     *   分享不读也不清空输入区队列，用户正在编辑的草稿不因这次分享消失；
     * - [redactBody]：分享正文常含私人内容，日志只记字符数；
     * - [allowSlashCommands]：分享正文来自外部，以 `/` 开头不代表用户要执行本地命令。
     *
     * 繁忙时的通道只由 [busyMode] 决定，与有没有附件无关：插话与排队都把附件带走。
     */
    internal fun sendUserMessage(
        text: String,
        files: List<PendingFile>,
        images: List<PendingFile>,
        scope: CoroutineScope,
        programmatic: Boolean = false,
        clearComposer: Boolean = false,
        redactBody: Boolean = false,
        titleSource: String? = null,
        allowSlashCommands: Boolean = true
    ): Boolean {
        // 斜杠命令先拦：它们不是消息，忙的时候也该立刻生效（比如中途 /plan off）
        if (allowSlashCommands && slashCommand(text)) return true
        if (restartBlocked()) {
            reportError("备份刚刚导入，请重启应用后再继续使用，否则新导入的数据将被覆盖")
            return false
        }
        val trimmed = text.trim()
        // 与 send 同一口径：只带附件 / 只带图片也算一次提问，三者都空才真的没得发
        if (trimmed.isEmpty() && files.isEmpty() && images.isEmpty()) return false
        // 繁忙只看**目标会话**：B 空闲就立刻在 B 开一个新回合，不等 A 那条跑完（用户 2026-11-15 要求）
        val target = session.currentSessionId
        if (!isStreamingFor(target)) {
            return dispatch(
                text = text,
                files = files,
                images = images,
                scope = scope,
                programmatic = programmatic,
                clearComposer = clearComposer,
                redactBody = redactBody,
                titleSource = titleSource
            )
        }
        if (busyMode == BusySendMode.STEER) {
            steerNow(target, trimmed, files = files, images = images)
            // 附件已进本轮历史：只清**这条会话**的待发槽，别的会话还排着的附件不动
            if (clearComposer) composer.clearSlot(target)
            return true
        }
        enqueue(target, text = trimmed, files = files, images = images)
        // 附件已进队列：同样只清这条会话的槽，避免同几份文件既排队又留在框里
        if (clearComposer) composer.clearSlot(target)
        return true
    }

    /**
     * 切换繁忙时的发送行为并落盘（只影响下一次「繁忙时发送」，不打断当前回合）。
     *
     * 为什么不叫 setBusyMode：属性 busyMode 的私有 setter 在 JVM 上就是这个签名，两者会撞车。
     */
    fun applyBusyMode(mode: BusySendMode) {
        if (mode == busyMode) return
        busyMode = mode
        GeneralPrefs.saveBusyMode(appContext, mode)
        FileLog.log("chat", "繁忙时发送行为：" + GeneralPrefs.busyModeLabel(mode))
    }

    /** 把某条排队消息移出当前会话的队列；id 已不在队列里时忽略。 */
    fun deleteQueued(id: Long) {
        val queue = queueOf(session.currentSessionId)
        val index = queue.indexOfFirst { it.id == id }
        if (index < 0) return
        queue.removeAt(index)
        persistQueues()
        FileLog.log("queue", "移出队列 " + id + "，剩 " + queue.size + " 条")
    }

    /**
     * 会话被真删时丢弃其待发队列（X10）：内存条目移除并同步落盘，
     * 否则残留条目会随下一次队列变更被 persistQueues 写回 queues.json。
     */
    fun dropQueuesOf(sessionId: String) {
        val queue = queues.remove(sessionId)
        if (queue == null || queue.isEmpty()) return
        persistQueues()
        FileLog.log("queue", "会话删除，丢弃待发队列 " + queue.size + " 条（" + sessionId + "）")
    }

    /**
     * 立即插话发送队列里的某一条（行尾那个 ↑）。
     *
     * 语义照 DSH 的「插话发送」：不随「繁忙时发送行为」走，点了就是现在插进这一轮；
     * 本轮已经结束则直接按普通消息发出去；运行中立刻追加进历史，界面上马上可见。
     * 带附件的条目同样插得进去：附件随这一条消息一起交给模型，与普通发送的说明段逐字一致。
     */
    fun sendQueuedNow(id: Long, scope: CoroutineScope) {
        val sessionId = session.currentSessionId
        val queue = queueOf(sessionId)
        val index = queue.indexOfFirst { it.id == id }
        if (index < 0) return
        val item = queue[index]
        queue.removeAt(index)
        persistQueues()
        FileLog.log("queue", "插话发送队列消息 " + item.id + "：" + item.text.take(80))
        if (!isStreamingFor(sessionId)) {
            dispatchQueued(sessionId, item, scope)
            return
        }
        steerNow(sessionId, item.text, files = item.files, images = item.images)
    }

    /**
     * 长按发送键：把队列里的消息全部插话发送（对应 DSH 的 Ctrl/Cmd+Enter）。
     *
     * 空闲时「插话」没有意义，按顺序正常发送：第一条开跑，其余回到队列等待本轮结束。
     * 运行中每条都直接插进这一轮，附件随各自的消息一起走。
     */
    fun steerAllQueued(scope: CoroutineScope) {
        val sessionId = session.currentSessionId
        val queue = queueOf(sessionId)
        if (queue.isEmpty()) return
        val items = queue.toList()
        queue.clear()
        persistQueues()
        FileLog.log("queue", "插话发送全部排队消息 " + items.size + " 条（会话 " + sessionId + "）")
        if (!isStreamingFor(sessionId)) {
            queue.addAll(items.drop(1))
            persistQueues()
            dispatchQueued(sessionId, items.first(), scope)
            return
        }
        for (item in items) {
            steerNow(sessionId, item.text, files = item.files, images = item.images)
        }
    }

    /**
     * 插话：立刻把一条 user 消息追加到**这条会话**的历史里，界面上马上可见，本轮后续请求也能读到它。
     *
     * 与 DSH 的语义一致（`steer` = 投递到「下一步」，`followup` = 留到下一轮）：
     * 不打断当前思考或工具执行，等它做完、下一次请求时模型自然读到这条插话。
     * 工具结果由 [AgentLoop] 插在所属 assistant 之后，因此中间夹一条 user 也不会破坏请求结构。
     *
     * 附件与普通发送同一套口径：文件与图片以工作区相对路径写在正文的说明段里，
     * 模型在下一步即可用 `read_file`（图片用 `read_image`）读到；图片同时记进
     * [ChatMessage.images]，气泡与回滚据此还原缩略图。通道与内容类型无关。
     *
     * 调用方（[sendUserMessage] / [sendQueuedNow] / [steerAllQueued]）只在**这条会话确实有回合在跑**时
     * 才会走到这里；会话空闲时一句消息就该按普通发送开出新回合，而不是插进别人的回合。
     */
    private fun steerNow(
        sessionId: String,
        text: String,
        files: List<PendingFile> = emptyList(),
        images: List<PendingFile> = emptyList()
    ) {
        val body = foldBlankRuns(text).trim()
        val content = promptWithAttachments(body, files, images)
        if (content.isEmpty()) return
        messagesOf(sessionId).add(
            ChatMessage(
                role = "user",
                content = content,
                images = images.map { it.relPath }
            )
        )
        steerPendingSessions.add(sessionId)
        // 单独一个分类：插话出了问题（被当成重复提问、模型答了两遍）只能靠这行日志回溯；
        // 只记长度不记正文——分享进来的内容可能是私人文本，日志里不得留原文
        FileLog.log(
            "steer",
            "插话（流式中追加用户消息）：" + body.length + " 字，" + files.size +
                " 个文件，" + images.size + " 张图片，会话 " + sessionId
        )
    }

    /**
     * 拼出真正交给模型的用户正文：正文 + 附件说明段 + 图片说明段（唯一实现）。
     *
     * 普通发送（[dispatch]）与插话（[steerNow]）共用它，两处的标记、行格式与尾巴提示因此逐字一致；
     * 反过来，界面按同一批标记把说明段裁掉（`userBubbleText`）并还原文件卡。
     */
    private fun promptWithAttachments(
        text: String,
        files: List<PendingFile>,
        images: List<PendingFile>
    ): String = buildString {
        // 连按的换行先收成一个空行，正文里的单、双换行原样留着（用户排版与发给模型的字节一致）
        append(foldBlankRuns(text).trim())
        if (files.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append(ATTACHMENT_PATHS_MARKER)
            files.forEach { append("\n- ").append(it.relPath) }
            append("\n（相对路径，用 read_file 读）")
        }
        if (images.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append(IMAGE_PATHS_MARKER)
            images.forEach { append("\n- ").append(it.relPath) }
        }
    }.trim()

    /** 排队：本轮结束后由 [continueAfterTurn] 自动发出去；附件随条目一起存。 */
    private fun enqueue(
        sessionId: String,
        text: String,
        files: List<PendingFile> = emptyList(),
        images: List<PendingFile> = emptyList()
    ) {
        val queue = queueOf(sessionId)
        val item = QueuedMessage(nextQueueId++, text, files, images)
        queue.add(item)
        persistQueues()
        FileLog.log(
            "queue",
            "繁忙，已排队（第 " + queue.size + " 条，会话 " + sessionId + "）：" + text.length + " 字，" +
                files.size + " 个文件，" + images.size + " 张图片"
        )
    }

    /**
     * 把队列里的一条按它自己的内容发出去。
     *
     * 带附件时直接走 [dispatch]：附件已在条目里，不能再读输入区；
     * 纯文本仍走 [send]，与手动发一条完全同路径（保留斜杠命令与重启拦截）。
     * [sessionId] 是这条队列消息所属的会话：并行时必须发回原会话，不能跟着当时显示的会话走。
     */
    private fun dispatchQueued(sessionId: String, item: QueuedMessage, scope: CoroutineScope) {
        if (item.files.isEmpty() && item.images.isEmpty()) {
            send(item.text, scope, sessionId = sessionId)
            return
        }
        val accepted = dispatch(
            text = item.text,
            files = item.files,
            images = item.images,
            scope = scope,
            programmatic = false,
            clearComposer = false,
            redactBody = false,
            titleSource = null,
            sessionId = sessionId
        )
        if (!accepted) FileLog.log("queue", "队列消息 " + item.id + " 未受理（原因已提示用户）")
    }

    /**
     * 连续空行收敛：三个及以上换行压成一个空行（最多两个换行符）。
     *
     * 单个、两个换行原样保留：那是用户自己的段落排版，发给模型和界面都按原样；
     * 连按几十个换行则只会撑出整屏空白，对模型也没有额外语义，一律收到一个空行。
     */
    private fun foldBlankRuns(text: String): String =
        text.replace(Regex("\n(?:[ \t]*\n){2,}"), "\n\n")

    /**
     * 发一条消息：只做校验与拼 prompt，真正开跑交给 [startTurn]。
     *
     * 流式中直接 return：繁忙时的行为看 [sendWhileBusy]。
     *
     * @param programmatic true = 本句由 App 代发（点按钮触发的「按计划开始执行」、目标续跑等），
     *   不是用户亲手打的一句话。它照常进历史、照常显示，但**不获得高危操作授权资格**：
     *   授权码只认最近一条 programmatic=false 的 user 消息（见 [com.zmf.agent.capability.tools.HighRiskGate]）。
     */
    fun send(
        text: String,
        scope: CoroutineScope,
        programmatic: Boolean = false,
        /** 目标会话；省略＝当前显示的会话。队列补发与目标续跑会显式指定原会话。 */
        sessionId: String = session.currentSessionId
    ) {
        // 命令闸门置于最前：任何入口（输入框、队列补发、脚本式调用）均先处理 /xxx
        if (slashCommand(text)) return
        // 只看目标会话繁不繁忙：别的会话在跑不影响这条（用户 2026-11-15 要求会话各跑各的）
        if (isStreamingFor(sessionId)) return
        if (restartBlocked()) {
            reportError("备份刚刚导入，请重启应用后再继续使用，否则新导入的数据将被覆盖")
            return
        }
        dispatch(
            text = text,
            // 按**目标会话**的槽取：队列补发可能发往别的会话，不能把显示会话的附件带过去
            files = composer.filesOf(sessionId),
            images = composer.imagesOf(sessionId),
            scope = scope,
            programmatic = programmatic,
            clearComposer = true,
            redactBody = false,
            titleSource = null,
            sessionId = sessionId
        )
    }

    /**
     * 按调用方给定的正文与附件发送（分享入口专用）。
     *
     * 与普通发送走同一个 [sendUserMessage]，差别只有三项：附件与图片取自参数、不清空输入区队列、
     * 不解析斜杠命令、日志隐去正文。繁忙语义（排队 / 插话）因此与普通输入框逐条一致。
     *
     * @return true 表示已受理；false 时原因已写入 [reportError]，界面保留面板并显示该文案
     */
    fun sendPayload(
        text: String,
        files: List<PendingFile>,
        images: List<PendingFile>,
        titleSource: String?,
        scope: CoroutineScope
    ): Boolean = sendUserMessage(
        text = text,
        files = files,
        images = images,
        scope = scope,
        clearComposer = false,
        redactBody = true,
        titleSource = titleSource,
        allowSlashCommands = false
    )

    /**
     * [send] 与 [sendPayload] 的共同实现：校验、拼 prompt、记轮次、定标题，然后起跑。
     *
     * @param clearComposer 发送后是否清空输入区的附件与图片队列、作废当前草稿。
     *   亲手输入走 true（这些内容已经进了这一条消息）；分享入口走 false（队列属于用户的草稿）。
     * @param redactBody 日志是否隐去正文，只留字符数
     * @param titleSource 会话标题的候选；null 时沿用「正文或附件名」的既有规则
     */
    private fun dispatch(
        text: String,
        files: List<PendingFile>,
        images: List<PendingFile>,
        scope: CoroutineScope,
        programmatic: Boolean,
        clearComposer: Boolean,
        redactBody: Boolean,
        titleSource: String?,
        /** 目标会话：省略＝当前显示的会话。队列补发会把原会话显式带回来。 */
        sessionId: String = session.currentSessionId
    ): Boolean {
        // 只发附件 / 只发图片、不写字也算一次提问；三者都空才真的没得发
        if (text.isBlank() && files.isEmpty() && images.isEmpty()) return false
        // 会话被后台定时回合占着：在产生任何副作用（记轮次、收清单）之前就拒绝，避免白记一次
        if (SessionBusy.isBusy(sessionId)) {
            reportError("该会话正在后台执行定时任务，请稍后重试")
            return false
        }
        // 用户开口了：连续唤醒的预算复位（封顶只约束「用户不在场时的自激链」）
        if (!programmatic) consecutiveWakes.remove(sessionId)
        // 新一轮提问：上一轮已经全部打勾的清单在这里收掉（必须在开跑前同步做完，见函数注释）。
        // 一律带本回合的会话：在 B 发一句话不得动 A 的清单与计划
        dropFinishedTodos(sessionId)
        // 用户没点按钮就直接接着聊：那份计划就此作废（按钮变灰），要执行得重新交一份新的
        abandonPendingPlans(sessionId)
        // 兜底再夹一次上限：入队时已经卡过，这里防的是历史队列里带回来的超量图片
        val used = images.take(MAX_IMAGES)
        val tooManyImages = images.size > MAX_IMAGES
        val prompt = promptWithAttachments(text, files, used)

        // 用户亲手提问记一轮：轮次进总账，删会话不会让全局轮次下降（App 代发不算）
        if (!programmatic) UsageLedger.recordRound(appContext)
        // 首句同时用作会话标题，侧边栏就不用显示一串「新会话」；只有附件/图片时用文件名。
        // 标题写回**本回合的会话**：队列补发与分享发送都显式带 id，不能写到当时显示的那条上
        session.autoTitleIfNeeded(
            titleSource
                ?: if (text.isBlank()) (files.firstOrNull() ?: used.firstOrNull())?.name ?: prompt else text.trim(),
            sessionId = sessionId
        )

        startTurn(
            userText = prompt,
            prompt = prompt,
            files = files,
            images = used,
            scope = scope,
            tooManyImages = tooManyImages,
            programmatic = programmatic,
            clearComposer = clearComposer,
            redactBody = redactBody,
            sessionId = sessionId
        )
        return true
    }

    /**
     * 起一个回复回合（校验通过之后的一切都在这里）。
     *
     * @param userText 要追加进历史的用户正文；**null = 不追加任何用户消息**，
     *   直接在当前历史之后继续跑。插话之后本轮可能已经结束，需要再跑一回合把它接上，
     *   这时追加会让模型看到同一句话被问了两遍，所以单独给一个 null 入口（AgentLoop 支持）。
     * @param prompt 拼好附件/图片说明之后的正文，只用于日志；与 [userText] 同一个值
     * @param programmatic 本句是否 App 代发（见 [send]）：透传给 AgentLoop 写进消息标记，
     *   高危闸门据此判断「这条消息有没有资格批准删除」
     * @param clearComposer 发送后是否清空输入区的附件、图片与当前草稿（分享入口不清，原因见 [dispatch]）
     * @param redactBody 日志是否隐去正文，只记字符数（分享内容常含私人对话与代码）
     */
    private fun startTurn(
        userText: String?,
        prompt: String,
        files: List<PendingFile>,
        images: List<PendingFile>,
        scope: CoroutineScope,
        tooManyImages: Boolean = false,
        programmatic: Boolean = false,
        clearComposer: Boolean = true,
        redactBody: Boolean = false,
        /** 这一轮在*哪条会话*里跑：必填。并行时代会各跑各的，不能跟着「当前显示的会话」走。 */
        sessionId: String
    ) {
        reportError(null)
        // 会话级串行：定时任务正在同一会话执行时不再起新回合，否则两侧整份覆盖写会丢消息
        val turnSessionId = sessionId
        if (!SessionBusy.tryAcquire(turnSessionId)) {
            reportError("该会话正在后台执行定时任务，请稍后重试")
            FileLog.log("chat", "会话占用中，本回合未开始：" + turnSessionId)
            return
        }
        // 新回合已经真正开跑：交接空档到此结束，界面回到「正在流式」这一种状态
        chainSessions.remove(turnSessionId)
        // P32：回合开合标记置位——进程在此后被杀，重启时恢复路径据此补中断标记
        session.setTurnOpen(turnSessionId, true)
        // 这一回合写的那份正文：开局就钉住，之后用户切到哪儿都不影响
        val turnMessages = messagesOf(turnSessionId)
        // 超过上限只在界面提示一句，不阻断发送（用户已经选了图，全丢掉比只发前几张更糟）。
        // 这句不算「本轮失败」：发通知那一步要排除它，否则成功的回合也会弹「回复失败」
        val imageNotice = if (tooManyImages) {
            "每条消息最多 " + MAX_IMAGES + " 张图片，已只发送前 " + MAX_IMAGES + " 张"
        } else {
            null
        }
        reportError(imageNotice)
        setActivity(turnSessionId, null)
        // 新的一轮：上一轮的用量已经写进气泡了，这里清零重新累计；
        // 发送这一刻先对一次水位：状态行与圆环读的是 contextSnapshot，本轮跑完还会再刷一次。
        // 先把这一轮钉在自己的会话上，再让上下文层取回合边界：此后用户切走也不影响本回合写哪儿
        beginTurn(turnSessionId)
        context.beginTurn(turnSessionId)

        // 用户消息在点发送的这一刻就进正文：摘要（可能数秒）、图片编码都在下方协程里跑，
        // 若等协程里再追加，界面会先出现一段「消息还没发出去」的空档——用户气泡不存在，
        // 而上一轮的 user 下标未更新，最后一轮被当成仍在流式（汇总行与动作按钮一并被藏掉）。
        // 循环侧因此不再追加（见下方 run 的 userText），其余语义不变。
        val userMessageIndex = if (userText != null) {
            turnMessages.add(
                ChatMessage(
                    role = "user",
                    content = userText,
                    images = images.map { it.relPath },
                    programmatic = programmatic
                )
            )
            turnMessages.lastIndex
        } else {
            null
        }

        val thinking = thinkingOn()
        val effort = thinkingEffortProvider()
        val permission = permissionProvider()
        val ui = agentUiFor(turnSessionId)
        if (userText != null) {
            // 附件/图片已经拼进 prompt 与 images 了：这里立刻清空，避免下一条消息重复带上。
            // 插话续跑（userText == null）时不清空：输入框中排队的内容属于用户的下一条消息。
            // 分享入口（clearComposer = false）同样不清：那条正文不是输入框里的内容，
            // 用户的草稿与草稿附件与本次分享无关
            if (clearComposer) {
                // 只清本回合会话的槽：并行时另一条会话的待发附件仍在原位
                composer.clearSlot(turnSessionId)
                // 文本已进入历史，草稿随之作废，否则下次打开会回填已发送的内容
                session.clearCurrentDraft(turnSessionId)
            }
            // 首轮消息发出即转正：清除空白标记并进入会话列表
            session.markSessionStarted()
        }

        FileLog.log(
            "chat",
            if (userText != null) {
                "用户输入：" + (if (redactBody) "（分享内容 " + prompt.length + " 字，正文不记入日志）" else prompt.take(200)) +
                    "｜思考=" + thinking + "（" + effort + "）｜权限=" +
                    permission.displayName() + "｜附件=" + files.size + "｜图片=" + images.size
            } else {
                "插话续跑（不追加用户消息）｜思考=" + thinking + "（" + effort + "）｜权限=" +
                    permission.displayName()
            },
            sessionId = turnSessionId
        )

        // 回合必须挂在控制器自有的稳定作用域上：界面传入的 scope 来自输入区组合，
        // 分享面板顶替输入区时该组合会被销毁，作用域随之取消，正在生成的回合会被误当成「用户停止」。
        val job = this.scope.launch {
            // 用户点「停止」也会走到 finally：那种情况既不算完成也不算失败，不发通知
            var stopped = false
            // 本轮最终答复：只取本轮新增消息，通知不会再把上一轮的答复当成本轮结果
            var turnReply = ""
            // 非请求失败的意外中断：与请求失败一样写进消息流，不在输入框上方内嵌
            var turnErrorText: String? = null
            var turnErrorCode: String? = null
            // 本轮新增消息的起始下标：收尾时据此定位本轮最后一条 assistant 消息。
            // 用户消息在起跑前就已追加（见上方 userMessageIndex），因此起点就是它。
            var turnStart = userMessageIndex ?: turnMessages.size
            try {
                // 发送前置检查：命中闸门时先摘要（阻塞，失败时静默回退兜底），摘要完成后再发送主请求
                if (context.shouldSummarize(turnSessionId)) {
                    setActivity(turnSessionId, "正在压缩上下文…")
                    FileLog.log("context", "命中摘要闸门：" + context.contextPolicy.summary)
                    context.runSummary(turnSessionId)
                    setActivity(turnSessionId, null)
                }
                // 工具的根目录取会话自身的存储单元：分组变化不会让运行根漂移
                val root = session.currentWorkspaceRoot()
                // 图片按路径现读现编成 data URL：只对这一次请求有效，不写进会话
                val imagePaths = images.map { it.relPath }
                val imageDataUrls = if (imagePaths.isEmpty()) {
                    emptyList<String>()
                } else {
                    encodeImages(root, imagePaths)
                }
                // 图片的 data URL 只在这一份内存正文上补齐：落盘时会排除（见 SessionStore）
                if (userMessageIndex != null && imageDataUrls.isNotEmpty()) {
                    turnMessages.getOrNull(userMessageIndex)?.let {
                        turnMessages[userMessageIndex] = it.copy(imageDataUrls = imageDataUrls)
                    }
                }
                // 每个回合一个新循环实例：共用会把计时与缓存指纹等账目互相覆盖
                val rounds = newLoop().run(
                    messages = turnMessages,
                    // null = 历史中已包含这条用户消息（本轮已在起跑前追加 / 插话续跑），循环不得再追加
                    userText = if (userMessageIndex == null) userText else null,
                    // 代发标记随消息一并写入历史：授权码判定需区分「用户亲自输入」与「App 代为发送」
                    userTextProgrammatic = programmatic,
                    thinking = thinking,
                    thinkingEffort = effort,
                    toolContext = ToolContext(
                        context = appContext,
                        permission = permission,
                        workspaceRoot = root,
                        sessionId = turnSessionId,
                        // 计划模式不裁剪工具表，改由执行期逐调用拒绝；此处传递该状态
                        planMode = planModeOn(),
                        // 惰性读最近一条真实 user 消息：ToolContext 在 loop.run 追加新消息**之前**构造，
                        // 现在快照会漏掉本轮这句话；闸门要的是「此刻会话里最后一条用户亲口说的话」
                        lastUserMessage = {
                            turnMessages.lastOrNull { it.role == "user" && it.content.isNotBlank() }
                        },
                        // 检索等工具自行发起的请求同样计费：只并入会话与总账，不进本轮上下文
                        onAuxUsage = { aux -> context.recordAuxUsage(turnSessionId, aux) }
                    ),
                    ui = ui,
                    // 计划模式不作为参数传递：AgentLoop 每轮从 promptInput().planMode 实时读取
                    images = imagePaths,
                    imageDataUrls = imageDataUrls,
                    // 上下文溢出时压一次再重发（true = 压成了）。只从聊天页接：子智能体/定时任务
                    // 没有摘要能力，行为不该被这条路径改变
                    onOverflow = { context.onOverflowCompaction(turnSessionId) },
                    // 两道落盘屏障：发请求前、执行有副作用的工具前。
                    // 此前仅在回合结束时落盘，进程被终止会丢失整轮（实测 19 条 → 14 条）。
                    onCheckpoint = { reason -> session.persistCheckpoint(reason, turnSessionId) }
                )
                val outcome = TurnOutcome(rounds, turnMessages.drop(turnStart))
                turnReply = outcome.reply
                FileLog.log(
                    "loop",
                    "结束，共 " + outcome.rounds + " 轮，本轮新增 " + outcome.added.size +
                        " 条消息，答复 " + turnReply.length + " 字",
                    sessionId = turnSessionId
                )
            } catch (e: CancellationException) {
                stopped = true
                FileLog.log("loop", "被用户停止", sessionId = turnSessionId)
                throw e
            } catch (e: Exception) {
                // 界面上只说结果与下一步，具体原因留在日志里；这句与请求失败同样落在消息流内
                turnErrorText = "本轮回复中断，请重试一次。"
                turnErrorCode = "UNKNOWN"
                FileLog.log(
                    "chat",
                    "运行异常：" + (e.message ?: e.javaClass.simpleName),
                    sessionId = turnSessionId
                )
                FileLog.log("error", "运行异常：" + e, sessionId = turnSessionId)
            } finally {
                setActivity(turnSessionId, null)
                // 用户中止：半截内容照常落盘，但要打 interrupted 标记，并为未返回的调用补合成结果。
                // 必须在下方落盘之前完成，否则重进会话看到的仍是「执行中…」且没有「已停止」。
                if (stopped) {
                    closeInterruptedTurn(turnMessages, turnStart, ui.startedCalls)
                }
                // 本轮用量写入气泡：必须位于下方落盘之前，否则该数字需等待
                // 下一次保存才进盘；被用户停止时也已经收到的 usage 一样写进去，不丢。
                context.writeTurnUsage(turnSessionId)
                // 本次回复的步数只在这里加一次：本会话累计 + 全局总账（放 onRound 里会重复计）
                context.commitSteps(turnSessionId)
                // 时间账同理：一次回复只加一次，切会话回来还能接上。
                // X06：本轮追加过用户消息才计一轮；墙钟与轮数一并折进会话字段（重启后累计仍在）
                context.commitTiming(turnSessionId, turnCounts = userText != null)
                // P32：回合已收尾，开合标记复位（放在落盘之前，meta 与消息事件同批进盘）
                session.setTurnOpen(turnSessionId, false)
                // 本轮产物：只认本回合内执行成功的 present 调用，挂到本轮最后一条 assistant 消息上
                attachProduced(turnMessages, PresentArtifacts.collect(turnMessages.drop(turnStart)), turnStart)
                // 终态失败写进消息流：与 DSH 的 turn-error 同口径，重进会话仍可见，
                // 输入框上方不再内嵌「请求失败」。用户主动停止不算失败，不写。
                if (!stopped) {
                    val failText = ui.failure?.takeIf { it != imageNotice } ?: turnErrorText
                    val failCode = ui.failureCode ?: turnErrorCode
                    if (!failText.isNullOrBlank()) attachTurnError(turnMessages, failText, failCode)
                }
                // 回合已收尾：重试倒计时节点一并收起（成功 / 放弃 / 停止都要收）
                ui.onModelRetry(null)
                // 本轮浏览器租赁随之解除；失败不影响回合收尾
                runCatching { BrowserAutomation.release() }
                // 一轮（或被停止）结束后落盘：本会话必落一次，
                // 再把其余在跑的会话与当前显示的会话一并折回
                session.persistSessionNow(turnSessionId)
                session.persistCurrent()
                session.persistAll()
                // 新消息改变了请求视图：水位、轮数、步数一起重算（refreshStats 内部就带 refreshContext，
                // 下一次 send 的闸门判断同样使用该值）。仅刷新 refreshContext 不足：状态行与上下文圆环此前
                // 读的是 stats 里那几项，而 stats 只在进统计页时才刷，用户得切会话再切回来才看到数字变。
                context.refreshStats(turnSessionId)
                // 这轮跑过 todo_write 的话清单已经变了：重读一次，界面上的进度条跟上
                reloadTodos()
                // 计划同理：本轮可能刚用 write_plan 追加了新计划，计划预览与入口要立刻是对的
                reloadPlans()
                // 人不在前台才提醒（Notifier 内部不判前后台，这里判）；整段 runCatching：
                // 通知失败最多是少一条提醒，绝不能把回合结果带崩
                if (!AppForeground.isForeground && !stopped) {
                    runCatching {
                        // 只认本回合自己的失败：并行时旁边会话的失败不该把这条成功回合报成失败
                        val failure = ui.failure?.takeIf { it != imageNotice }
                        if (failure.isNullOrBlank()) {
                            Notifier.notifyReply(
                                appContext,
                                "回复完成",
                                turnReply.ifBlank { "（本轮已结束，没有正文输出）" },
                                turnSessionId
                            )
                        } else {
                            Notifier.notifyReply(appContext, "回复失败", failure, turnSessionId)
                        }
                    }.onFailure {
                        FileLog.log("notify", "通知失败：" + (it.message ?: it.javaClass.simpleName))
                    }
                }
                // 落盘完成后再释放会话占用：释放过早会让定时回合在界面写盘期间插进来，被旧快照覆盖
                SessionBusy.release(turnSessionId)
                // 忙状态释放之后才转空闲：界面切到「可发送」的那一刻，定时任务与新一轮发送都不会再撞上忙状态
                abortWatchdogs.remove(turnSessionId)?.cancel()
                phases.remove(turnSessionId)
                turns.remove(turnSessionId)
                // 收尾链放在最后：状态、落盘、通知都做完，才轮到「接着再跑一个回合」。
                // 续跑必须显式带上本回合的会话 id：用户可能已经切走了，不能跟着当前会话走
                if (!stopped) continueAfterTurn(this@SendController.scope, turnSessionId)
                // 回合彻底收尾：解除这条会话的钉住
                endTurn(turnSessionId)
            }
        }
        // 状态与任务同点登记：此后 stop() 能拿到 job，界面也立刻看到「运行中」
        turns[turnSessionId] = ActiveTurn(ui, job)
        phases[turnSessionId] = TurnPhase.RUNNING
    }

    /**
     * 本轮结束后的收尾链（顺序有意义）：
     * ① 有没被回答的插话 → 再跑一个不追加用户消息的回合把它接上；
     * ② 否则队列里有消息 → 取第一条按正常发送流程发出去。
     *
     * 两者互斥：同时起两个回合会让两条流抢着写同一份 messages。
     * 用户点了「停止」时整段不执行（停止后不得自行恢复）。
     */
    private fun continueAfterTurn(scope: CoroutineScope, turnId: String) {
        // 初始按「不续跑」记录，下方分支确需接续回合时再置为 true；
        // 置位与清零都在同一个主线程帧里完成，界面不会看到中间态。
        chainSessions.remove(turnId)
        if (steerPendingSessions.remove(turnId)) {
            // 插话消息已在后续请求中被回答（末尾已不是 user）→ 清除标记，避免下一轮误续跑。
            // 读的是**本回合**那一份正文：生成中用户可能已经切到别的会话了
            val unanswered = messagesOf(turnId).lastOrNull()?.role == "user"
            if (unanswered) {
                FileLog.log("steer", "本轮已结束但插话还没被回答，续跑一回合：" + turnId)
                scope.launch {
                    // launch 到真正开跑之间，用户可能又发了一条（那一轮会自己接管收尾）：
                    // 此时直接让路，避免并发两条流写入同一份正文
                    if (isStreamingFor(turnId)) return@launch
                    startTurn(
                        userText = null,
                        prompt = "",
                        files = emptyList(),
                        images = emptyList(),
                        scope = scope,
                        sessionId = turnId
                    )
                }
                return
            }
        }
        val queue = queueOf(turnId)
        val next = queue.firstOrNull()
        if (next != null) {
            scope.launch {
                // 同理让路；队列不动，下一次回合结束时会再取到它
                if (isStreamingFor(turnId)) return@launch
                // 仅在仍位于队列中时发送：其他路径已发送或删除时，不会重复发送或发送空内容
                if (queue.none { it.id == next.id }) return@launch
                queue.removeAll { it.id == next.id }
                // P33：条目已领取，同步落盘——此处之后再崩溃不应把这条消息恢复回队列
                persistQueues()
                FileLog.log(
                    "queue",
                    "本轮已结束，自动发送队列第一条（会话 " + turnId + "）：" + next.text.length + " 字，" +
                        next.files.size + " 个文件，" + next.images.size + " 张图片"
                )
                dispatchQueued(turnId, next, scope)
            }
            return
        }
        // ③ 后台任务结算的「空闲唤醒」：没有用户待发内容、也不续目标时，若有后台任务刚结算，
        // 把完成通知作为代发消息注入，再起一个不追加用户消息的回合把它接上（模型去 job_output 读结果）。
        // 取走即清空，每个结算只唤醒一次；连续唤醒由 AgentLoop 的 drain 去重兜底。
        // 连续唤醒封顶：自激链（唤醒的回合又起任务）不能无限跑；超预算时**不 drain**，
        // 通知留在队列里，下一轮（用户发消息时）自然注入。
        val wakes = consecutiveWakes[turnId] ?: 0
        if (wakes < MAX_CONSECUTIVE_WAKES) {
            val settled = JobRegistry.drainNotifications(turnId)
            if (settled.isNotEmpty()) {
                messagesOf(turnId).add(
                    ChatMessage(
                        role = "user",
                        content = JobRegistry.notificationText(settled),
                        programmatic = true
                    )
                )
                consecutiveWakes[turnId] = wakes + 1
                FileLog.log(
                    "jobs",
                    "空闲唤醒：会话 " + turnId + " 有 " + settled.size +
                        " 个后台任务已结算（连续第 " + (wakes + 1) + " 次）"
                )
                scope.launch {
                    if (isStreamingFor(turnId)) return@launch
                    startTurn(
                        userText = null,
                        prompt = "",
                        files = emptyList(),
                        images = emptyList(),
                        scope = scope,
                        sessionId = turnId
                    )
                }
                return
            }
        } else if (JobRegistry.forSession(turnId).any { it.status != JobRegistry.Status.RUNNING }) {
            FileLog.log("jobs", "连续唤醒已达上限 " + MAX_CONSECUTIVE_WAKES + " 次，暂停唤醒，通知留到下次用户输入")
        }
        // ④ 最后才轮到目标自动续跑：前几条都是「有更该先处理的事」，不应被目标挤掉
        continueGoal(scope, turnId)
    }

    /**
     * 目标自动续跑：目标仍处于进行中、本轮确实调用过工具且未达轮次上限时，继续运行一轮。
     *
     * 要求「调用过工具」的原因：仅回复一条消息说明模型并未推进（或在索取信息），再自动追问一轮仍无进展，
     * 白白消耗 token，用户还会停留在自动回复中。续跑不带新 user 消息的原因：目标本身已在上下文内，
     * 再补一句等同于代用户发言。
     */
    private fun continueGoal(scope: CoroutineScope, turnId: String) {
        val current = goalNow() ?: return
        if (current.phase != GoalPhase.ACTIVE) return
        if (current.rounds >= MAX_GOAL_ROUNDS) {
            FileLog.log("goal", "已到 " + MAX_GOAL_ROUNDS + " 轮上限，停止自动续跑")
            return
        }
        if (!context.turnUsedTools(turnId)) return
        val next = current.copy(rounds = current.rounds + 1, updatedAt = System.currentTimeMillis())
        // 落盘统一推进 revision：界面上的目标必须换成落盘结果，否则下一轮快照里的 revision 会过期
        setGoal(GoalStore.save(appContext, turnId, next) ?: next)
        FileLog.log("goal", "目标第 " + next.rounds + " 轮自动续跑：" + next.objective.take(60))
        // 同一轮里接着跑：界面据此不把这轮的过程折起来（见 chainSessions 的说明）
        chainSessions.add(turnId)
        scope.launch {
            // 和插话/队列同样让路：真正开跑前忙起来了就让那一轮接管
            if (isStreamingFor(turnId)) return@launch
            startTurn(
                userText = null,
                prompt = "",
                files = emptyList(),
                images = emptyList(),
                scope = scope,
                sessionId = turnId
            )
        }
    }

    /**
     * 重新生成：把这条用户消息之后的内容全删掉，用同一句话再跑一次。
     * 第一版就按「旧的不保留」实现，之后要做多版本切换再给消息加 version 字段。
     */
    fun regenerateFrom(userIndex: Int, scope: CoroutineScope) {
        if (isStreamingFor(session.currentSessionId)) return
        if (userIndex !in messages.indices) return
        val userText = messages[userIndex].content
        if (messages[userIndex].role != "user") return
        // 代发标记要跟着原文一起回来：重新生成「按计划开始执行」仍然是 App 代发，
        // 不能因为重跑一次就把它升格成用户亲口授权
        val programmatic = messages[userIndex].programmatic
        cutFrom(userIndex)
        FileLog.log("chat", "重新生成：" + userText.take(80))
        send(userText, scope, programmatic = programmatic)
        // send 内部流结束时也会落盘；这里再写一次，覆盖「send 提前返回」的情况
        session.persistCurrent()
        session.persistAll()
    }

    /** 回滚到此处：删掉这条用户消息及之后的所有内容，回到还没提问的状态。 */
    fun rollbackTo(userIndex: Int) {
        if (isStreamingFor(session.currentSessionId)) return
        if (userIndex !in messages.indices) return
        if (messages[userIndex].role != "user") return
        val message = messages[userIndex]
        // 图片和附件退回「已选未发」：文件还在工作区里就重新排队，路径失效的直接跳过，不放坏引用
        val root = session.currentWorkspaceRoot()
        restorePending(root, message.images, composer.pendingImages, MAX_IMAGES)
        restorePending(root, attachmentPathsIn(message.content), composer.attachments, Int.MAX_VALUE)
        // 输入框只放正文：图片路径段是写给模型看的，界面另有图片网格，裁完没字就留空
        val restore = userBubbleBody(message)
        composer.inputText = TextFieldValue(restore, selection = TextRange(restore.length))
        cutFrom(userIndex)
        context.clearUsageText(session.currentSessionId)
        setActivity(session.currentSessionId, null)
        reportError(null)
        // 回滚只退消息：本会话累计保留（已发生的请求不能凭空消失），按剩下的消息重算轮数与水位
        context.resetStats(session.currentSessionId)
        // X06：截断了历史，轮数字段按剩余消息反向修正
        context.recountTurns(session.currentSessionId)
        FileLog.log("chat", "回滚到第 " + userIndex + " 条（共剩 " + messages.size + " 条）")
        session.persistCurrent()
        session.persistAll()
        logOrphansAfterRollback()
    }

    /**
     * 回滚要从气泡里还原成文字的正文。
     *
     * 判定与 ChatScreen.userBubbleText 一致：images 非空时把 [IMAGE_PATHS_MARKER] 之后那段
     * 图片说明裁掉，输入框里不该出现一串图片路径。
     */
    private fun userBubbleBody(message: ChatMessage): String {
        // 图片段与附件段都以「正文 + 说明块」的形式追加在末尾：取两个标记里更早的那个切
        val imageCut = message.content.indexOf(IMAGE_PATHS_MARKER)
        val fileCut = message.content.indexOf(ATTACHMENT_PATHS_MARKER)
        val planCut = message.content.indexOf(PLAN_DETAIL_MARKER)
        val cut = listOf(imageCut, fileCut, planCut).filter { it >= 0 }.minOrNull()
            ?: return message.content
        return message.content.substring(0, cut).trimEnd()
    }
    private fun attachmentPathsIn(content: String): List<String> {
        val start = content.indexOf(ATTACHMENT_PATHS_MARKER)
        if (start < 0) return emptyList()
        return content.substring(start + ATTACHMENT_PATHS_MARKER.length)
            .lines()
            .dropWhile { it.isBlank() }
            .takeWhile { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim() }
            .filter { it.isNotEmpty() }
    }

    /** 将消息中的文件放回待发队列：工作区中找不到的跳过，队列已满时不会强行加入。 */
    private fun restorePending(
        root: File,
        relPaths: List<String>,
        queue: MutableList<PendingFile>,
        limit: Int
    ) {
        for (rel in relPaths) {
            if (queue.size >= limit) return
            if (rel.isBlank() || queue.any { it.relPath == rel }) continue
            if (!File(root, rel).isFile) continue
            queue.add(PendingFile(rel.substringAfterLast('/'), rel))
        }
    }

    /**
     * 回滚不删工作区里的文件，只在日志里记一行还剩多少未引用附件。
     *
     * 数字取自设置页「清理未引用附件」同一个统计口径，用户看到的和能清掉的是同一批。
     */
    private fun logOrphansAfterRollback() {
        val sessions = session.sessionsOfCurrentWorkspace()
        val root = session.currentWorkspaceRoot()
        scope.launch {
            val orphans = withContext(Dispatchers.IO) {
                runCatching { UploadStore.orphanReport(appContext, sessions, root).first }.getOrDefault(0)
            }
            FileLog.log("chat", "回滚后仍有 " + orphans + " 个未引用附件，可在设置里清理")
        }
    }

    private fun cutFrom(index: Int) {
        while (messages.size > index) {
            messages.removeAt(messages.lastIndex)
        }
    }

    /**
     * 删除单条消息：连带清掉它依赖的上下文，然后重算统计并立即落盘。
     *
     * 为什么删 user 要连带其后的工具消息：工具结果是对上一条 assistant 的 tool_calls 的回答，
     * 提问被删掉之后请求体里就只剩一串没有来路的 tool 消息，接口会直接报错；
     * 所以从这条 user 一直清到下一个 user 之前。
     * 反方向同理：删掉一条带 tool_calls 的 assistant 时，只清它自己发起的那些工具结果。
     */
    fun deleteMessage(index: Int) {
        if (isStreamingFor(session.currentSessionId)) {
            reportError("正在生成回复，无法删除消息")
            return
        }
        if (index !in messages.indices) return
        val target = messages[index]
        var end = index + 1
        if (target.role == "user") {
            var hasTool = false
            while (end < messages.size && messages[end].role != "user") {
                if (messages[end].role == "tool") hasTool = true
                end++
            }
            // 其后没有工具结果就只删这一条提问，当轮的助手回复留给用户自行决定
            if (!hasTool) end = index + 1
        } else if (target.toolCalls.isNotEmpty()) {
            val callIds = target.toolCalls.map { it.id }.toSet()
            while (end < messages.size) {
                val next = messages[end]
                val callId = next.toolCallId
                // 仅消费该消息自身发起的工具结果：其他调用链可能紧邻
                if (next.role != "tool" || callId == null || callId !in callIds) break
                end++
            }
        }
        // 从后往前删：每删一条后续下标都会变化，倒序删除可避免反复计算偏移
        for (i in end - 1 downTo index) messages.removeAt(i)
        reportError(null)
        context.clearUsageText(session.currentSessionId)
        setActivity(session.currentSessionId, null)
        // 轮数与上下文水位均需按剩余消息重新计算，否则统计页仍显示删除前的条数
        context.refreshStats()
        // X06：删的可能正是 user 消息，轮数字段同步修正
        context.recountTurns(session.currentSessionId)
        session.persistCurrent()
        session.persistAll()
        FileLog.log("chat", "删除第 " + index + " 条消息（连带到第 " + (end - 1) + " 条）")
    }

    /**
     * 编辑并重发：截断到这条 user 消息之前，用改过的文字重新走一次 [send]。
     *
     * 为什么截断而不是就地改这一条：这一轮的助手回复和工具调用都是针对旧问题的，
     * 留着会和改过的提问对不上，接着往下发只会得到前后矛盾的上下文。
     */
    fun editAndResend(index: Int, text: String, scope: CoroutineScope) {
        if (isStreamingFor(session.currentSessionId)) {
            reportError("正在生成回复，无法编辑重发")
            return
        }
        if (index !in messages.indices) return
        if (messages[index].role != "user") return
        val edited = text.trim()
        if (edited.isEmpty()) return
        cutFrom(index)
        // 文本先填回输入框：send 提前返回（导入后未重启等）时，用户改完还能自己再发一次
        composer.inputText = TextFieldValue(edited, selection = TextRange(edited.length))
        // X06：截断到这条 user 消息之前，轮数字段随之减少
        context.recountTurns(session.currentSessionId)
        FileLog.log("chat", "编辑重发第 " + index + " 条：" + edited.take(80))
        send(edited, scope)
        // send 实际启动后清空输入框：该文本已成为一条消息，保留副本会造成重复
        if (isStreamingFor(session.currentSessionId)) composer.inputText = TextFieldValue("")
        // send 内部流结束时也会落盘；这里再写一次，覆盖「send 提前返回」的情况
        session.persistCurrent()
        session.persistAll()
    }

    /**
     * 用户点「停止」：只取消**当前显示的会话**那条回合，其他会话照旧跑（用户 2026-11-15 定）。
     *
     * 只做两件事：状态进入 [TurnPhase.ABORTING]、下发取消信号。回合仍留在 [turns] 中，
     * 活动行也不清空——摘除、释放会话占用、落盘统一由该回合 `finally` 末尾完成，
     * 界面因此在「中止中」一直停到真正收尾，不会出现可发送却仍被判为忙的假空闲。
     *
     * 不在流式中或已在取消中时是空操作。
     */
    fun stop() {
        val sessionId = session.currentSessionId
        val phase = phaseOf(sessionId)
        if (phase != TurnPhase.RUNNING) return
        val turn = turns[sessionId] ?: return
        phases[sessionId] = TurnPhase.ABORTING
        FileLog.log("chat", "用户点了停止：" + sessionId)
        // 挂起中的提问必须以取消结束：否则提问卡会一直等待，同一会话后续提问也会被判为冲突
        AskBridge.cancel(sessionId, "用户停止生成")
        // 停止后不续跑：保留该标记时，下一次回合收尾可能额外执行一个空回合
        steerPendingSessions.remove(sessionId)
        // 停止即终止整条链：交接标记一并清掉，界面立刻按「这一轮结束」折叠
        chainSessions.remove(sessionId)
        // 对齐 DSH「父停止即连带中止子代理」：先收尾本会话正在跑的子智能体（标记已停止、取消协程、释放终端通道），
        // 再取消回合 job；按会话过滤，其它会话的子智能体与终端不受影响
        SubAgentRunner.cancelSession(appContext, sessionId)
        // 压缩任务与本回合脱钩：中止后既不再写盘，也不得在收尾时清掉下一回合的「正在压缩」标记
        context.abortCompaction(sessionId)
        turn.job.cancel()
        startAbortWatchdog(sessionId)
    }

    /**
     * 中止等待的看门狗：组件不响应取消时界面会一直停在「中止中」，这里每 5 秒记一条已等待时长。
     *
     * 只记日志，不做超时强制释放：强行把状态改成空闲会让下一轮与尚未结束的旧回合抢同一份正文与占用。
     */
    private fun startAbortWatchdog(sessionId: String) {
        abortWatchdogs.remove(sessionId)?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        abortWatchdogs[sessionId] = scope.launch {
            while (true) {
                delay(ABORT_WATCH_STEP_MS)
                if (phaseOf(sessionId) != TurnPhase.ABORTING) return@launch
                val waited = (SystemClock.elapsedRealtime() - startedAt) / 1000
                val text = "中止等待已 " + waited + " 秒仍未结束：" + sessionId
                Log.w(ABORT_LOG_TAG, text)
                FileLog.log("chat", text, sessionId = sessionId)
            }
        }
    }

    /**
     * 用户中止回合后的收尾：给半截 assistant 打中断标记，并补齐未返回的工具结果。
     *
     * 两件事都只改本轮新增的消息，且在落盘之前完成：
     * ① 本轮最后一条 assistant 就是被中止的那一条，正文原样保留，只把 [ChatMessage.interrupted] 置真，
     *    界面据此在消息尾部显示「已停止」，重进会话后仍按落盘值显示；
     * ② 每个「有 tool_calls、却没有对应 tool 消息」的调用补一条合成结果（[ChatMessage.toolAborted]），
     *    工具卡因此显示「已中止」而不是永久停在「执行中…」；配对齐后请求视图也不会整轮丢弃。
     *
     * 已派发与否由本回合界面回调记下的 [TurnAgentUi.startedCalls] 判定：已派发的提示副作用可能已经发生，
     * 未派发的明确未执行，模型下一轮据此决定是否重试。
     */
    private fun closeInterruptedTurn(
        turnMessages: SnapshotStateList<ChatMessage>,
        turnStart: Int,
        startedCalls: Set<String>
    ) {
        if (turnMessages.isEmpty()) return
        val first = turnStart.coerceIn(0, turnMessages.lastIndex)
        val lastAssistant = (turnMessages.lastIndex downTo first)
            .firstOrNull { turnMessages[it].role == "assistant" }
        if (lastAssistant != null && !turnMessages[lastAssistant].interrupted) {
            turnMessages[lastAssistant] = turnMessages[lastAssistant].copy(interrupted = true)
        }

        // 插入位置按当前下标计算；最后从后往前写入，前序下标不会因插入而位移
        val inserts = ArrayList<Pair<Int, ChatMessage>>()
        var i = first
        while (i <= turnMessages.lastIndex) {
            val message = turnMessages[i]
            if (message.role != "assistant" || message.toolCalls.isEmpty()) {
                i++
                continue
            }
            var end = i + 1
            val answered = HashSet<String>()
            while (end < turnMessages.size && turnMessages[end].role == "tool") {
                turnMessages[end].toolCallId?.let { answered.add(it) }
                end++
            }
            for (call in message.toolCalls) {
                if (call.id in answered) continue
                val dispatched = call.id in startedCalls
                inserts.add(
                    end to ChatMessage(
                        role = "tool",
                        content = if (dispatched) {
                            "〔已中止〕" + ABORTED_IN_FLIGHT + "：用户停止了本次运行，该工具调用尚未返回结果。" +
                                "副作用可能已经发生，重试前请先核实。"
                        } else {
                            "〔已中止〕" + ABORTED_BEFORE_DISPATCH + "：用户停止了本次运行，该工具调用未执行。"
                        },
                        toolCallId = call.id,
                        toolName = call.name,
                        toolOk = false,
                        toolAborted = true
                    )
                )
            }
            i = end
        }
        for ((index, items) in inserts.groupBy { it.first }.entries.sortedByDescending { it.key }) {
            var at = index.coerceAtMost(turnMessages.size)
            for ((_, synthetic) in items) {
                turnMessages.add(at.coerceAtMost(turnMessages.size), synthetic)
                at++
            }
        }
    }

    /**
     * 把本回合的终态失败写成消息流里的错误节点（与 DSH 的 turn-error 同口径）。
     *
     * 用一条内容为空的 assistant 消息承载：它不会被发回模型（请求视图会丢弃空 assistant），
     * 但随会话落盘，重进会话仍能看到「本轮运行失败」与错误码。
     */
    private fun attachTurnError(
        turnMessages: SnapshotStateList<ChatMessage>,
        text: String,
        code: String?
    ) {
        val last = turnMessages.lastOrNull()
        if (last?.role == "assistant" && last.content.isEmpty() && last.errorText != null) return
        turnMessages.add(
            ChatMessage(
                role = "assistant",
                content = "",
                errorText = text,
                errorCode = code?.takeIf { it.isNotBlank() }
            )
        )
    }

    /**
     * 把本回合登记的产物挂到最后一条 assistant 消息上。
     *
     * 只在本轮新增的消息范围内查找；本轮没有 assistant 消息时不挂载。
     * 与既有产物合并并按路径去重，先出现的条目优先。
     */
    private fun attachProduced(
        turnMessages: SnapshotStateList<ChatMessage>,
        produced: List<ProducedArtifact>,
        turnStart: Int
    ) {
        if (produced.isEmpty()) return
        val index = (turnMessages.lastIndex downTo turnStart)
            .firstOrNull { turnMessages[it].role == "assistant" } ?: return
        val target = turnMessages[index]
        val merged = LinkedHashMap<String, ProducedArtifact>()
        for (item in target.produced) merged[item.path] = item
        val before = merged.size
        for (item in produced) merged.putIfAbsent(item.path, item)
        if (merged.size == before) return
        turnMessages[index] = target.copy(produced = merged.values.toList())
    }

    /**
     * 图片的相对路径 → data URL（"data:image/jpeg;base64,..."）。
     *
     * MIME 按扩展名推导：图片归一化后可能是 JPEG、WebP（透明图）或原样透传的 PNG/WebP，
     * 一律写死 image/jpeg 会让 data URL 的类型与真实字节不符。
     * 读盘 + base64 一律在 IO：单张最大 4MB，主线程做会明显卡一帧。
     * 单张失败只丢这一张（记日志），不让整条提问发不出去。
     */
    private suspend fun encodeImages(root: File, relPaths: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            relPaths.mapNotNull { rel ->
                runCatching {
                    val bytes = File(root, rel).readBytes()
                    "data:" + imageMimeOf(rel) + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                }.onFailure {
                    FileLog.log(
                        "chat",
                        "图片编码失败：" + rel + " / " + (it.message ?: it.javaClass.simpleName)
                    )
                }.getOrNull()
            }
        }
}

/**
 * 排队等发送的一条消息。
 *
 * 自带正文与附件：轮到时按这条自己的内容发出去（[SendController.dispatchQueued]），
 * 不再去读输入区——否则排队期间用户改了输入框，这条消息会带走别人的附件，
 * 或把分享进来的文件丢掉。没有附件的消息仍走普通发送流程（[SendController.send]）。
 *
 * @param id 队列内的稳定标识，编辑 / 删除 / 立即发送都按它找
 */
data class QueuedMessage(
    val id: Long,
    val text: String,
    val files: List<PendingFile> = emptyList(),
    val images: List<PendingFile> = emptyList()
) {
    /** 队列行显示的一行字：只有附件时给出数量，避免出现空白行。 */
    val displayText: String
        get() = if (text.isNotEmpty()) text else "附件 " + (files.size + images.size) + " 个"
}
