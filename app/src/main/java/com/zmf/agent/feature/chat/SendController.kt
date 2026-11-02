package com.zmf.agent.feature.chat

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.zmf.agent.capability.loop.AgentLoop
import com.zmf.agent.capability.loop.AgentUi
import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.displayName
import com.zmf.agent.core.AppForeground
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.BusySendMode
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.notify.Notifier
import com.zmf.agent.core.store.Goal
import com.zmf.agent.core.store.GoalPhase
import com.zmf.agent.core.store.GoalStore
import com.zmf.agent.core.store.UploadStore
import com.zmf.agent.core.store.UsageLedger
import com.zmf.agent.core.store.WorkSpaceStore
import com.zmf.agent.core.prefs.GeneralPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 目标自动续跑的轮次上限。
 *
 * 上限是为了防跑飞而不是省钱：目标跑歪了、模型自己判不出做完时，
 * 八轮之后必须停下来让人看一眼，不能靠它自己醒。
 */
internal const val MAX_GOAL_ROUNDS = 8

/**
 * 连续「空闲唤醒」的上限。
 *
 * 后台任务结算会唤醒会话去读结果；被唤醒的回合若又起任务、又结算，就形成自激链，
 * 用户不在场时白烧 token。三次足够接住正常的连续任务，再多就该等人回来。
 */
internal const val MAX_CONSECUTIVE_WAKES = 3

/**
 * 发送与流式回合：发消息、排队/插话、续跑、重新生成、回滚、删改与停止。
 *
 * 从 [ChatViewModel] 拆出，行为不变：状态字段名与原成员逐字一致，方法体原样保留；
 * 跨模块依赖一律改为构造注入（当前上下文、会话、输入区、Agent 循环、界面回调入口），
 * 避免该类反向持有整个 ViewModel。
 */
class SendController(
    private val appContext: Context,
    private val messages: SnapshotStateList<ChatMessage>,
    private val context: ContextState,
    private val session: SessionController,
    private val composer: ComposerState,
    private val scope: CoroutineScope,
    private val loop: () -> AgentLoop,
    private val agentUi: () -> AgentUi,
    private val slashCommand: (String) -> Boolean,
    private val restartBlocked: () -> Boolean,
    private val readError: () -> String?,
    private val reportError: (String?) -> Unit,
    private val setActivity: (String?) -> Unit,
    private val thinkingOn: () -> Boolean,
    private val thinkingEffortProvider: () -> String,
    private val permissionProvider: () -> PermissionLevel,
    private val planModeOn: () -> Boolean,
    private val goalNow: () -> Goal?,
    private val setGoal: (Goal) -> Unit,
    private val reloadTodos: () -> Unit,
    private val reloadPlans: () -> Unit,
    private val dropFinishedTodos: () -> Unit,
    private val abandonPendingPlans: () -> Unit
) {

    var isStreaming by mutableStateOf(false)
        private set

    private var streamJob: Job? = null

    /** 繁忙（正在生成回复）时又发消息的行为：排队等本轮结束，或插进正在跑的这一轮。 */
    var busyMode by mutableStateOf(GeneralPrefs.loadBusyMode(appContext))
        private set

    /** 排队待发的消息（只有排队模式会进这里）；本轮结束后自动取第一条发出去。 */
    val pendingQueue = mutableStateListOf<QueuedMessage>()

    /** 队列 id 自增：同一毫秒连发两条也不会撞 id。 */
    private var nextQueueId = 1L

    /**
     * 有没被回答的插话。
     *
     * 插话是「向历史中插入一条 user 消息」，模型可能在本轮后续请求中即予回答，
     * 也可能这一轮已经结束（见 streamJob 的 finally）：后者要再跑一个不追加用户消息的回合接上。
     */
    private var steerPending = false

    /**
     * 连续「空闲唤醒」的计数。
     *
     * 唤醒是「有后台任务结算了，替用户追问一轮」。被唤醒的那一轮如果又起了新任务、又结算，
     * 就会一直自己唤醒自己——用户不在场时烧 token。所以连续唤醒封顶 [MAX_CONSECUTIVE_WAKES]，
     * 用户下一次发消息时清零。达到上限后停止唤醒，已结算通知保留在队列中，待下一轮用户输入再注入。
     */
    private var consecutiveWakes = 0

    /**
     * 本轮结束后「已经排上了下一回合」。
     *
     * 一轮收尾不是原子的：`finally` 里先把 [isStreaming] 置 false，再落盘、刷新状态、发通知，
     * 最后才由 [continueAfterTurn] 起下一回合——这中间有几百毫秒真的不是流式状态。
     * 界面若只看 [isStreaming]，过程区会在这段空档里折起来、下一段内容一到又展开。
     * 这个标记把「接着还要跑」如实报给界面：`isStreaming || chainPending` 才是「这一轮还没完」。
     *
     * 置位与清零都在主线程同步完成（[continueAfterTurn] 与 [startTurn] 都不挂起），
     * 因此界面不会看到中间那一帧。
     */
    var chainPending by mutableStateOf(false)
        private set

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
     */
    fun sendWhileBusy(text: String, scope: CoroutineScope) {
        // 斜杠命令先拦：它们不是消息，忙的时候也该立刻生效（比如中途 /plan off）
        if (slashCommand(text)) return
        if (restartBlocked()) {
            reportError("备份刚刚导入，请重启应用后再继续使用，否则新导入的数据将被覆盖")
            return
        }
        val trimmed = text.trim()
        // 与 send 同一口径：只带附件 / 只带图片也算一次提问
        if (trimmed.isEmpty() && composer.attachments.isEmpty() && composer.pendingImages.isEmpty()) return
        if (!isStreaming) {
            send(text, scope)
            return
        }
        if (trimmed.isEmpty()) {
            // 只有附件/图片、没有正文：队列只存文字、插话也只能插文字，
            // 与其排一条空消息到轮到时被静默丢掉，不如直接告诉用户等这一轮结束
            reportError("正在生成回复，附件等本轮结束后再发送")
            return
        }
        // 插话会把正在回填的工具调用截成半轮（见 steerAppendable），那个窗口里退成排队：
        // 消息一条都不丢，只是晚一点发
        if (busyMode == BusySendMode.STEER && steerAppendable()) {
            steerNow(trimmed)
        } else {
            enqueue(trimmed)
        }
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

    /** 修改排队消息的正文；改为空串即将其移出队列（保留时仅在轮到时被 send 静默丢弃）。 */
    fun editQueued(id: Long, text: String) {
        val index = pendingQueue.indexOfFirst { it.id == id }
        if (index < 0) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            pendingQueue.removeAt(index)
            FileLog.log("queue", "队列消息 " + id + " 被清空，已移出队列")
            return
        }
        pendingQueue[index] = pendingQueue[index].copy(text = trimmed)
    }

    /** 把某条排队消息移出队列；id 已不在队列里时忽略。 */
    fun deleteQueued(id: Long) {
        val index = pendingQueue.indexOfFirst { it.id == id }
        if (index < 0) return
        pendingQueue.removeAt(index)
        FileLog.log("queue", "移出队列 " + id + "，剩 " + pendingQueue.size + " 条")
    }

    /**
     * 立即发送队列里的某一条：先从队列取出来，再按 [busyMode] 走一遍繁忙逻辑。
     *
     * 本轮已经结束 → 直接发出去；还在跑 → 排队模式重新排到队尾、插话模式立刻插进历史。
     */
    fun sendQueuedNow(id: Long, scope: CoroutineScope) {
        val index = pendingQueue.indexOfFirst { it.id == id }
        if (index < 0) return
        val item = pendingQueue.removeAt(index)
        FileLog.log("queue", "立即发送队列消息 " + item.id + "：" + item.text.take(80))
        sendWhileBusy(item.text, scope)
    }

    /**
     * 插话能不能安全地追加到历史末尾。
     *
     * 末尾是一条带 tool_calls 的 assistant 时，它还在等工具结果回填：这时插一条 user 进去，
     * 工具结果就会挂在 user 后面，发请求前的那道清洗只能把整轮工具调用丢掉（不丢就是 400）。
     * 这个窗口里退成排队更划算：晚几秒发，但不丢任何一条工具结果。
     */
    private fun steerAppendable(): Boolean {
        val last = messages.lastOrNull() ?: return true
        return last.role != "assistant" || last.toolCalls.isEmpty()
    }

    /** 插话：往历史末尾追加一条 user 消息。界面上立刻可见，本轮后续请求也能看到它。 */
    private fun steerNow(text: String) {
        messages.add(ChatMessage(role = "user", content = foldBlankRuns(text)))
        steerPending = true
        // 单独一个分类：插话出了问题（被当成重复提问、模型答了两遍）只能靠这行日志回溯
        FileLog.log("steer", "插话（流式中追加用户消息）：" + text.take(80))
    }

    /** 排队：本轮结束后由 [continueAfterTurn] 自动发出去。 */
    private fun enqueue(text: String) {
        val item = QueuedMessage(nextQueueId++, text)
        pendingQueue.add(item)
        FileLog.log("queue", "繁忙，已排队（第 " + pendingQueue.size + " 条）：" + text.take(80))
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
    fun send(text: String, scope: CoroutineScope, programmatic: Boolean = false) {
        // 命令闸门置于最前：任何入口（输入框、队列补发、脚本式调用）均先处理 /xxx
        if (slashCommand(text)) return
        if (isStreaming) return
        if (restartBlocked()) {
            reportError("备份刚刚导入，请重启应用后再继续使用，否则新导入的数据将被覆盖")
            return
        }
        val files = composer.attachments.toList()
        val images = composer.pendingImages.toList()
        // 只发附件 / 只发图片、不写字也算一次提问；三者都空才真的没得发
        if (text.isBlank() && files.isEmpty() && images.isEmpty()) return
        // 用户开口了：连续唤醒的预算复位（封顶只约束「用户不在场时的自激链」）
        if (!programmatic) consecutiveWakes = 0
        // 新一轮提问：上一轮已经全部打勾的清单在这里收掉（必须在开跑前同步做完，见函数注释）
        dropFinishedTodos()
        // 用户没点按钮就直接接着聊：那份计划就此作废（按钮变灰），要执行得重新交一份新的
        abandonPendingPlans()
        // 兜底再夹一次上限：入队时已经卡过，这里防的是历史队列里带回来的超量图片
        val used = images.take(MAX_IMAGES)
        val tooManyImages = images.size > MAX_IMAGES
        val prompt = buildString {
            // 连按的换行先收成一个空行，正文里的单、双换行原样留着（用户排版与发给模型的字节一致）
            append(foldBlankRuns(text).trim())
            if (files.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(ATTACHMENT_PATHS_MARKER)
                files.forEach { append("\n- ").append(it.relPath) }
                append("\n（相对路径，用 read_file 读）")
            }
            if (used.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(IMAGE_PATHS_MARKER)
                used.forEach { append("\n- ").append(it.relPath) }
            }
        }.trim()

        // 用户亲手提问记一轮：轮次进总账，删会话不会让全局轮次下降（App 代发不算）
        if (!programmatic) UsageLedger.recordRound(appContext)
        // 第一句话顺便当会话标题，侧边栏就不用显示一串「新会话」；只有附件/图片时用文件名
        session.autoTitleIfNeeded(
            if (text.isBlank()) (files.firstOrNull() ?: used.firstOrNull())?.name ?: prompt else text.trim()
        )

        startTurn(
            userText = prompt,
            prompt = prompt,
            files = files,
            images = used,
            scope = scope,
            tooManyImages = tooManyImages,
            programmatic = programmatic
        )
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
     */
    private fun startTurn(
        userText: String?,
        prompt: String,
        files: List<PendingFile>,
        images: List<PendingFile>,
        scope: CoroutineScope,
        tooManyImages: Boolean = false,
        programmatic: Boolean = false
    ) {
        reportError(null)
        // 新回合已经真正开跑：交接空档到此结束，界面回到「正在流式」这一种状态
        chainPending = false
        // 超过上限只在界面提示一句，不阻断发送（用户已经选了图，全丢掉比只发前几张更糟）。
        // 这句不算「本轮失败」：发通知那一步要排除它，否则成功的回合也会弹「回复失败」
        val imageNotice = if (tooManyImages) {
            "每条消息最多 " + MAX_IMAGES + " 张图片，已只发送前 " + MAX_IMAGES + " 张"
        } else {
            null
        }
        reportError(imageNotice)
        setActivity(null)
        // 新的一轮：上一轮的用量已经写进气泡了，这里清零重新累计；
        // 发送这一刻先对一次水位：状态行与圆环读的是 contextSnapshot，本轮跑完还会再刷一次
        context.beginTurn()

        val thinking = thinkingOn()
        val effort = thinkingEffortProvider()
        val permission = permissionProvider()
        isStreaming = true
        if (userText != null) {
            // 附件/图片已经拼进 prompt 与 images 了：这里立刻清空，避免下一条消息重复带上。
            // 插话续跑（userText == null）不清：输入框里排着的东西属于用户的下一条消息
            composer.attachments.clear()
            composer.pendingImages.clear()
        }

        FileLog.log(
            "chat",
            if (userText != null) {
                "用户输入：" + prompt.take(200) + "｜思考=" + thinking + "（" + effort + "）｜权限=" +
                    permission.displayName() + "｜附件=" + files.size + "｜图片=" + images.size
            } else {
                "插话续跑（不追加用户消息）｜思考=" + thinking + "（" + effort + "）｜权限=" +
                    permission.displayName()
            }
        )

        streamJob = scope.launch {
            // 用户点「停止」也会走到 finally：那种情况既不算完成也不算失败，不发通知
            var stopped = false
            try {
                // 发送前置检查：命中闸门时先摘要（阻塞，失败时静默回退兜底），摘要完成后再发送主请求
                if (context.shouldSummarize()) {
                    setActivity("正在压缩上下文…")
                    FileLog.log("context", "命中摘要闸门：" + context.contextPolicy.summary)
                    context.runSummary()
                    setActivity(null)
                }
                // 工具的根目录跟着当前工作区：未分类（currentWorkspaceId == null）或找不到时用默认工作区
                val space = session.workspaces.firstOrNull { it.id == session.currentWorkspaceId }
                    ?: WorkSpaceStore.defaultSpace(appContext)
                val root = session.rootOf(space)
                // 图片按路径现读现编成 data URL：只对这一次请求有效，不写进会话
                val imagePaths = images.map { it.relPath }
                val imageDataUrls = if (imagePaths.isEmpty()) {
                    emptyList<String>()
                } else {
                    encodeImages(root, imagePaths)
                }
                val rounds = loop().run(
                    messages = messages,
                    // null = 插话续跑：历史中已包含该用户消息，不可再次追加
                    userText = userText,
                    // 代发标记随消息一并写入历史：授权码判定需区分「用户亲自输入」与「App 代为发送」
                    userTextProgrammatic = programmatic,
                    thinking = thinking,
                    thinkingEffort = effort,
                    toolContext = ToolContext(
                        context = appContext,
                        permission = permission,
                        workspaceRoot = root,
                        sessionId = session.currentSessionId,
                        // 计划模式不裁剪工具表，改由执行期逐调用拒绝；此处传递该状态
                        planMode = planModeOn(),
                        // 惰性读最近一条真实 user 消息：ToolContext 在 loop.run 追加新消息**之前**构造，
                        // 现在快照会漏掉本轮这句话；闸门要的是「此刻会话里最后一条用户亲口说的话」
                        lastUserMessage = {
                            messages.lastOrNull { it.role == "user" && it.content.isNotBlank() }
                        }
                    ),
                    ui = agentUi(),
                    // 计划模式不作为参数传递：AgentLoop 每轮从 promptInput().planMode 实时读取
                    images = imagePaths,
                    imageDataUrls = imageDataUrls,
                    // 上下文溢出时压一次再重发（true = 压成了）。只从聊天页接：子智能体/定时任务
                    // 没有摘要能力，行为不该被这条路径改变
                    onOverflow = { context.onOverflowCompaction() },
                    // 两道落盘屏障：发请求前、执行有副作用的工具前。
                    // 此前仅在回合结束时落盘，进程被终止会丢失整轮（实测 19 条 → 14 条）。
                    onCheckpoint = { reason -> session.persistCheckpoint(reason) }
                )
                FileLog.log("loop", "结束，共 " + rounds + " 轮")
            } catch (e: CancellationException) {
                stopped = true
                FileLog.log("loop", "被用户停止")
                throw e
            } catch (e: Exception) {
                reportError("运行异常：" + (e.message ?: e.javaClass.simpleName))
                FileLog.log("error", "运行异常：" + e)
            } finally {
                isStreaming = false
                streamJob = null
                setActivity(null)
                // 本轮用量写入气泡：必须位于下方 persistCurrent 之前，否则该数字需等待
                // 下一次保存才进盘；被用户停止时也已经收到的 usage 一样写进去，不丢。
                context.writeTurnUsage()
                // 本次回复的步数只在这里加一次：本会话累计 + 全局总账（放 onRound 里会重复计）
                context.commitSteps()
                // 时间账同理：一次回复只加一次，切会话回来还能接上
                context.commitTiming()
                // 一轮（或被停止）结束后整条会话落盘
                session.persistCurrent()
                session.persistAll()
                // 新消息改变了请求视图：水位、轮数、步数一起重算（refreshStats 内部就带 refreshContext，
                // 下一次 send 的闸门判断同样使用该值）。仅刷新 refreshContext 不足：状态行与上下文圆环此前
                // 读的是 stats 里那几项，而 stats 只在进统计页时才刷，用户得切会话再切回来才看到数字变。
                context.refreshStats()
                // 这轮跑过 todo_write 的话清单已经变了：重读一次，界面上的进度条跟上
                reloadTodos()
                // 计划同理：本轮可能刚用 write_plan 追加了新计划，计划预览与入口要立刻是对的
                reloadPlans()
                // 人不在前台才提醒（Notifier 内部不判前后台，这里判）；整段 runCatching：
                // 通知失败最多是少一条提醒，绝不能把回合结果带崩
                if (!AppForeground.isForeground && !stopped) {
                    runCatching {
                        val failure = readError()?.takeIf { it != imageNotice }
                        if (failure.isNullOrBlank()) {
                            val reply = messages
                                .lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
                                ?.content
                                .orEmpty()
                            Notifier.notifyReply(
                                appContext,
                                "回复完成",
                                reply.ifBlank { "（本轮已结束，没有正文输出）" },
                                session.currentSessionId
                            )
                        } else {
                            Notifier.notifyReply(appContext, "回复失败", failure, session.currentSessionId)
                        }
                    }.onFailure {
                        FileLog.log("notify", "通知失败：" + (it.message ?: it.javaClass.simpleName))
                    }
                }
                // 收尾链放在最后：状态、落盘、通知都做完，才轮到「接着再跑一个回合」
                if (!stopped) continueAfterTurn(scope)
            }
        }
    }

    /**
     * 本轮结束后的收尾链（顺序有意义）：
     * ① 有没被回答的插话 → 再跑一个不追加用户消息的回合把它接上；
     * ② 否则队列里有消息 → 取第一条按正常发送流程发出去。
     *
     * 两者互斥：同时起两个回合会让两条流抢着写同一份 messages。
     * 用户点了「停止」时整段不执行（停止后不得自行恢复）。
     */
    private fun continueAfterTurn(scope: CoroutineScope) {
        // 初始按「不续跑」记录，下方分支确需接续回合时再置为 true；
        // 置位与清零都在同一个主线程帧里完成，界面不会看到中间态。
        chainPending = false
        if (steerPending) {
            // 插话消息已在后续请求中被回答（末尾已不是 user）→ 清除标记，避免下一轮误续跑
            val unanswered = messages.lastOrNull()?.role == "user"
            steerPending = false
            if (unanswered) {
                FileLog.log("steer", "本轮已结束但插话还没被回答，续跑一回合")
                scope.launch {
                    // launch 到真正开跑之间，用户可能又发了一条（那一轮会自己接管收尾）：
                    // 此时直接让路，避免并发两条流写入同一份 messages
                    if (isStreaming) return@launch
                    startTurn(
                        userText = null,
                        prompt = "",
                        files = emptyList(),
                        images = emptyList(),
                        scope = scope
                    )
                }
                return
            }
        }
        val next = pendingQueue.firstOrNull()
        if (next != null) {
            scope.launch {
                // 同理让路；队列不动，下一次回合结束时会再取到它
                if (isStreaming) return@launch
                // 仅在仍位于队列中时发送：其他路径已发送或删除时，不会重复发送或发送空内容
                if (pendingQueue.none { it.id == next.id }) return@launch
                pendingQueue.removeAll { it.id == next.id }
                FileLog.log("queue", "本轮已结束，自动发送队列第一条：" + next.text.take(80))
                send(next.text, scope)
            }
            return
        }
        // ③ 后台任务结算的「空闲唤醒」：没有用户待发内容、也不续目标时，若有后台任务刚结算，
        // 把完成通知作为代发消息注入，再起一个不追加用户消息的回合把它接上（模型去 job_output 读结果）。
        // 取走即清空，每个结算只唤醒一次；连续唤醒由 AgentLoop 的 drain 去重兜底。
        // 连续唤醒封顶：自激链（唤醒的回合又起任务）不能无限跑；超预算时**不 drain**，
        // 通知留在队列里，下一轮（用户发消息时）自然注入。
        if (consecutiveWakes < MAX_CONSECUTIVE_WAKES) {
            val settled = JobRegistry.drainNotifications(session.currentSessionId)
            if (settled.isNotEmpty()) {
                messages.add(
                    ChatMessage(
                        role = "user",
                        content = JobRegistry.notificationText(settled),
                        programmatic = true
                    )
                )
                consecutiveWakes++
                FileLog.log(
                    "jobs",
                    "空闲唤醒：会话 " + session.currentSessionId + " 有 " + settled.size +
                        " 个后台任务已结算（连续第 " + consecutiveWakes + " 次）"
                )
                scope.launch {
                    if (isStreaming) return@launch
                    startTurn(
                        userText = null,
                        prompt = "",
                        files = emptyList(),
                        images = emptyList(),
                        scope = scope
                    )
                }
                return
            }
        } else if (JobRegistry.forSession(session.currentSessionId).any { it.status != JobRegistry.Status.RUNNING }) {
            FileLog.log("jobs", "连续唤醒已达上限 " + MAX_CONSECUTIVE_WAKES + " 次，暂停唤醒，通知留到下次用户输入")
        }
        // ④ 最后才轮到目标自动续跑：前面几条都是「有更该先处理的事」，不该被目标挤掉
        continueGoal(scope)
    }

    /**
     * 目标自动续跑：目标还在进行中、这一轮真的动过工具、且没到轮次上限时，接着跑一轮。
     *
     * 为什么非要「动过工具」：只回一句话说明模型没在推进（或者在要信息），再自动追问一轮也是空转，
     * 白烧 token 还把用户晾在自动回复里。为什么续跑不带新 user 消息：目标本身就在上下文里，
     * 再补一句话等于替用户说话。
     */
    private fun continueGoal(scope: CoroutineScope) {
        val current = goalNow() ?: return
        if (current.phase != GoalPhase.ACTIVE) return
        if (current.rounds >= MAX_GOAL_ROUNDS) {
            FileLog.log("goal", "已到 " + MAX_GOAL_ROUNDS + " 轮上限，停止自动续跑")
            return
        }
        if (!context.turnUsedTools()) return
        val next = current.copy(rounds = current.rounds + 1, updatedAt = System.currentTimeMillis())
        setGoal(next)
        GoalStore.save(appContext, session.currentSessionId, next)
        FileLog.log("goal", "目标第 " + next.rounds + " 轮自动续跑：" + next.objective.take(60))
        // 同一轮里接着跑：界面据此不把这轮的过程折起来（见 chainPending 的说明）
        chainPending = true
        scope.launch {
            // 和插话/队列同样让路：真正开跑前忙起来了就让那一轮接管
            if (isStreaming) return@launch
            startTurn(
                userText = null,
                prompt = "",
                files = emptyList(),
                images = emptyList(),
                scope = scope
            )
        }
    }

    /**
     * 重新生成：把这条用户消息之后的内容全删掉，用同一句话再跑一次。
     * 第一版就按「旧的不保留」实现，之后要做多版本切换再给消息加 version 字段。
     */
    fun regenerateFrom(userIndex: Int, scope: CoroutineScope) {
        if (isStreaming) return
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
        if (isStreaming) return
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
        context.clearUsageText()
        setActivity(null)
        reportError(null)
        // 回滚只退消息：本会话累计保留（已发生的请求不能凭空消失），按剩下的消息重算轮数与水位
        context.resetStats()
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
     * 为什么删 user 要连带后面的工具消息：工具结果是对上一条 assistant 的 tool_calls 的回答，
     * 提问被删掉之后请求体里就只剩一串没有来路的 tool 消息，接口会直接报错；
     * 所以从这条 user 一直清到下一个 user 之前。
     * 反方向同理：删掉一条带 tool_calls 的 assistant 时，只清它自己发起的那些工具结果。
     */
    fun deleteMessage(index: Int) {
        if (isStreaming) {
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
            // 后面没有工具结果就只删这一条提问，当轮的助手回复留给用户自己决定
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
        // 从后往前删：每删一条后面的下标都会变，倒着删就不用反复算偏移
        for (i in end - 1 downTo index) messages.removeAt(i)
        reportError(null)
        context.clearUsageText()
        setActivity(null)
        // 轮数与上下文水位均需按剩余消息重新计算，否则统计页仍显示删除前的条数
        context.refreshStats()
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
        if (isStreaming) {
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
        FileLog.log("chat", "编辑重发第 " + index + " 条：" + edited.take(80))
        send(edited, scope)
        // send 实际启动后清空输入框：该文本已成为一条消息，保留副本会造成重复
        if (isStreaming) composer.inputText = TextFieldValue("")
        // send 内部流结束时也会落盘；这里再写一次，覆盖「send 提前返回」的情况
        session.persistCurrent()
        session.persistAll()
    }

    /** 用户点「停止」：取消当前流式任务并清掉进行中提示；不在流式中时是空操作。 */
    fun stop() {
        if (!isStreaming) return
        // 停止后不续跑：保留该标记时，下一次回合收尾可能额外执行一个空回合
        steerPending = false
        // 停止即终止整条链：交接标记一并清掉，界面立刻按「这一轮结束」折叠
        chainPending = false
        streamJob?.cancel()
        streamJob = null
        isStreaming = false
        setActivity(null)
        FileLog.log("chat", "用户点了停止")
    }

    /**
     * 图片的相对路径 → data URL（"data:image/jpeg;base64,..."）。
     *
     * 读盘 + base64 一律在 IO：单张压完 2MB 内，主线程做会明显卡一帧。
     * 单张失败只丢这一张（记日志），不让整条提问发不出去。
     */
    private suspend fun encodeImages(root: File, relPaths: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            relPaths.mapNotNull { rel ->
                runCatching {
                    val bytes = File(root, rel).readBytes()
                    "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
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
 * 只存纯文本，不存附件/图片：等它轮到时走的就是普通发送流程（[SendController.send]），
 * 附件按那一刻输入框里的状态一起拼进 prompt：和手动发一条完全同路径，
 * 排过队的消息不会多带一份、也不会丢一份文件。
 *
 * @param id 队列内的稳定标识，编辑 / 删除 / 立即发送都按它找
 */
data class QueuedMessage(val id: Long, val text: String)
