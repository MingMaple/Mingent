package com.zmf.agent.capability.runtime

import android.content.Context
import com.zmf.agent.capability.tools.toolHeadline
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.ToolCall
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.SessionBusy
import com.zmf.agent.core.store.SessionStore
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

/** 一条轮次的判定状态：进行中、正常结束、异常中断、用户插话。 */
const val PEER_RUNNING = "running"
const val PEER_DONE = "done"
const val PEER_TERMINATED = "terminated"
const val PEER_USER_INTERJECTED = "user_interjected"

/**
 * 节点：一次成功投递在目标会话中留下的位置。
 *
 * [at] 是实际追加到目标会话的那条 user 消息的 `createdAt`，已随会话落盘，
 * 因此进程重启后仍可按节点读取该轮次的后续消息。
 */
data class PeerNode(val sessionId: String, val at: Long)

/** 一个已执行的工具步骤：标题取工具调用的 description，成败取对应工具结果。 */
data class PeerStep(val title: String, val tool: String, val ok: Boolean?)

/** 一个节点之后轮次的判定结果，字段按状态取用。 */
data class PeerTurn(
    val sessionId: String,
    val status: String,
    val node: PeerNode,
    val steps: List<PeerStep> = emptyList(),
    /** done：该轮次最后一条非空 assistant 正文。 */
    val reply: String = "",
    /** user_interjected：用户插话原文。 */
    val userText: String = "",
    /** user_interjected：用户插话之后该轮产出的最终 assistant 正文。 */
    val finalText: String = "",
    /** terminated：中断说明。 */
    val note: String = ""
)

/** 一个目标的当前状态；[node] 为空表示还没有投递节点，[turn] 为空表示无法判定。 */
data class PeerMemberStatus(
    val sessionId: String,
    val node: PeerNode?,
    val turn: PeerTurn?
)

/** 协同组快照：组 id、统筹者与成员。 */
data class PeerGroupInfo(
    val id: String,
    val coordinator: String,
    val createdAt: Long,
    val members: List<PeerMemberStatus>
)

/** collab 动作的结果。 */
sealed interface PeerGroupResult {
    data class Ok(val group: PeerGroupInfo) : PeerGroupResult
    data class Failed(val text: String) : PeerGroupResult
}

/** collect 动作的结果；[Ok.timedOut] 表示等到超时之前已经拿到终态回执。 */
sealed interface PeerCollectResult {
    data class Ok(
        /** 有协同组时为组 id；用显式节点查询时为 null。 */
        val groupId: String?,
        val turns: List<PeerTurn>,
        val remaining: List<String>,
        val dissolved: Boolean,
        val timedOut: Boolean
    ) : PeerCollectResult

    data class TimedOut(val groupId: String?) : PeerCollectResult

    data class Failed(val text: String) : PeerCollectResult
}

/** peek 动作的结果：不等待、不移出成员、不改变任何状态。 */
sealed interface PeerPeekResult {
    data class Ok(val groupId: String?, val members: List<PeerMemberStatus>) : PeerPeekResult
    data class Failed(val text: String) : PeerPeekResult
}

/** status 动作的结果。 */
sealed interface PeerStatusResult {
    data class Ok(
        val groupId: String,
        val coordinator: String,
        val members: List<PeerMemberStatus>
    ) : PeerStatusResult

    data class Failed(val text: String) : PeerStatusResult
}

/** collect/peek 的解析结果：目标与节点，或可直接返回的失败说明。 */
sealed interface PeerPlan {
    data class Ok(val groupId: String?, val nodes: List<PeerNode>) : PeerPlan
    data class Failed(val text: String) : PeerPlan
}

/**
 * 跨会话协同的运行时状态。
 *
 * 模型只有两块，且都在内存中：
 * - 协同组：组 id、统筹者与成员名单；`end` 清除，或最近一次 peer 调用后 30 分钟无活动自动解散。
 * - 最近一次投递节点：按「统筹者|目标」记录 `send_message` 成功投递时带回的 `createdAt`，
 *   仅用于缺省节点；显式传入的节点不需要任何内存状态。
 *
 * 回执不在这里登记：轮次状态一律由 [judge] 读取目标会话的落盘消息与进程内 [SessionBusy] 判定。
 */
object PeerCollab {

    private const val TAG = "peer"

    /** 旧版按统筹者→目标持久化的游标文件；启动时直接删除，不做迁移。 */
    private const val LEGACY_FILE_NAME = "peer_collab.json"

    /** collect 的轮询间隔：足够及时，又不至于持续读盘。 */
    private const val POLL_MS = 200L

    /** 组在最近一次 peer 调用之后的无活动时限。 */
    private const val IDLE_DISSOLVE_MS = 30L * 60L * 1000L

    /** 没有协同组时统一说明。 */
    const val NO_GROUP =
        "[E_NO_GROUP] 当前会话没有协同组；如已有节点，可在 nodes 中显式给出，不建组也能查询"

    /** 组与重启的口径，供 collab/status 结果附带。 */
    const val RESTART_NOTE =
        "协同组只保存在内存中；App 重启后需用户手动唤醒会话，统筹者可用旧节点继续 collect 或 peek。"

    private const val ALREADY_GROUPED =
        "[E_ALREADY_GROUPED] 会话已在其他协同组中，一个会话同一时间只能属于一个组："

    private const val TERMINATED_NOTE =
        "轮次被异常中断，可发送明文询问进度并要求继续。"

    private val lock = Any()

    /** 组按 id 保存。 */
    private val groups = LinkedHashMap<String, Group>()

    /** 键为「统筹者|目标」，值为最近一次成功投递的节点时间戳；只在内存中。 */
    private val lastNodes = HashMap<String, Long>()

    @Volatile
    private var legacyCleaned = false

    /** 删除旧版游标文件；启动装配与首次 peer 调用各调用一次即可，重复调用无副作用。 */
    fun cleanupLegacy(context: Context) {
        if (legacyCleaned) return
        synchronized(lock) {
            if (legacyCleaned) return
            legacyCleaned = true
            try {
                val app = context.applicationContext
                val target = AppPaths.dataFile(app, LEGACY_FILE_NAME)
                if (target.exists() && target.isFile) target.delete()
                val old = File(app.filesDir, LEGACY_FILE_NAME)
                if (old.exists() && old.isFile) old.delete()
            } catch (e: Exception) {
                FileLog.log(TAG, "删除旧协同文件失败：" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** 记下一次成功投递的节点；[from] 为统筹者会话，[at] 取自受理回执。 */
    fun noteDelivered(from: String, target: String, at: Long) {
        if (from.isEmpty() || target.isEmpty() || at <= 0L) return
        synchronized(lock) { lastNodes[cursorKey(from, target)] = at }
    }

    /** 建立协同组；调用者即统筹者，成员去重后写入。 */
    fun start(context: Context, coordinator: String, members: List<String>): PeerGroupResult {
        cleanupLegacy(context)
        return synchronized(lock) {
            cleanupStale()
            val conflict = if (groupOf(coordinator) != null) {
                coordinator
            } else {
                members.firstOrNull { groupOf(it) != null }
            }
            if (conflict != null) {
                PeerGroupResult.Failed(ALREADY_GROUPED + conflict)
            } else {
                val now = System.currentTimeMillis()
                val group = Group(
                    id = UUID.randomUUID().toString(),
                    coordinator = coordinator,
                    createdAt = now,
                    members = LinkedHashSet(members)
                )
                groups[group.id] = group
                touch(group)
                FileLog.log(
                    TAG,
                    "建组：" + group.id.take(8) + "…，统筹者 " + coordinator + "，成员 " + members.joinToString("、")
                )
                PeerGroupResult.Ok(snapshot(group))
            }
        }
    }

    /** 向本组增加成员；已在其他组时拒绝。 */
    fun add(context: Context, coordinator: String, members: List<String>): PeerGroupResult {
        cleanupLegacy(context)
        return synchronized(lock) {
            cleanupStale()
            val group = groupOfCoordinator(coordinator)
            if (group == null) {
                PeerGroupResult.Failed(NO_GROUP)
            } else {
                val conflict = members.firstOrNull {
                    it != coordinator && groupOf(it)?.id != group.id
                }
                if (conflict != null) {
                    PeerGroupResult.Failed(ALREADY_GROUPED + conflict)
                } else {
                    for (id in members) if (id != coordinator) group.members.add(id)
                    touch(group)
                    PeerGroupResult.Ok(snapshot(group))
                }
            }
        }
    }

    /** 从本组移除成员；移除后名单为空即视为组已解散。 */
    fun remove(context: Context, coordinator: String, members: List<String>): PeerGroupResult {
        cleanupLegacy(context)
        return synchronized(lock) {
            cleanupStale()
            val group = groupOfCoordinator(coordinator)
            if (group == null) {
                PeerGroupResult.Failed(NO_GROUP)
            } else {
                for (id in members) group.members.remove(id)
                touch(group)
                val result = snapshot(group)
                if (group.members.isEmpty()) groups.remove(group.id)
                PeerGroupResult.Ok(result)
            }
        }
    }

    /** 解散本组，成员恢复自由；返回解散前的快照。 */
    fun end(context: Context, coordinator: String): PeerGroupResult {
        cleanupLegacy(context)
        return synchronized(lock) {
            cleanupStale()
            val group = groupOfCoordinator(coordinator)
            if (group == null) {
                PeerGroupResult.Failed(NO_GROUP)
            } else {
                val before = snapshot(group)
                groups.remove(group.id)
                FileLog.log(
                    TAG,
                    "解散组：" + group.id.take(8) + "…（统筹者 end），原成员 " +
                        before.members.joinToString("、") { it.sessionId }
                )
                PeerGroupResult.Ok(before)
            }
        }
    }

    /** 只读状态：组、统筹者、成员的最近一次投递节点与当前状态。 */
    fun status(context: Context, coordinator: String): PeerStatusResult {
        cleanupLegacy(context)
        val all = SessionStore.load(context).associateBy { it.id }
        return synchronized(lock) {
            cleanupStale()
            val group = groupOfCoordinator(coordinator)
            if (group == null) {
                PeerStatusResult.Failed(NO_GROUP)
            } else {
                touch(group)
                val members = group.members.map { id ->
                    val node = nodeOf(group.coordinator, id)
                    PeerMemberStatus(id, node, node?.let { judge(all[id], it) })
                }
                PeerStatusResult.Ok(group.id, group.coordinator, members)
            }
        }
    }

    /** 只读查看：按节点给出目标的当前状态，不等待、不移出成员、不改变任何状态。 */
    fun peek(
        context: Context,
        coordinator: String,
        targets: List<String>,
        nodes: List<PeerNode>
    ): PeerPeekResult {
        cleanupLegacy(context)
        val plan = when (val resolved = resolvePlan(context, coordinator, targets, nodes)) {
            is PeerPlan.Failed -> return PeerPeekResult.Failed(resolved.text)
            is PeerPlan.Ok -> resolved
        }
        val all = SessionStore.load(context).associateBy { it.id }
        val members = plan.nodes.map { node ->
            PeerMemberStatus(node.sessionId, node, judge(all[node.sessionId], node))
        }
        return PeerPeekResult.Ok(plan.groupId, members)
    }

    /**
     * 等待至少一个目标进入终态，再把此刻所有已终态的轮次一并返回。
     *
     * 已终态的目标立即返回；返回的成员从组名单移出，名单为空即解散组。
     * 超时且没有终态时返回 [PeerCollectResult.TimedOut]，由调用方折算为 `E_TIMEOUT`。
     */
    suspend fun collect(
        context: Context,
        coordinator: String,
        targets: List<String>,
        nodes: List<PeerNode>,
        timeoutSeconds: Int
    ): PeerCollectResult {
        cleanupLegacy(context)
        val plan = when (val resolved = resolvePlan(context, coordinator, targets, nodes)) {
            is PeerPlan.Failed -> return PeerCollectResult.Failed(resolved.text)
            is PeerPlan.Ok -> resolved
        }
        // 等待中的 collect 视为活动：其它 peer 调用不得在等待中途把组按「无活动」解散
        synchronized(lock) {
            groupOfCoordinator(coordinator)?.let {
                it.collecting++
                touch(it)
            }
        }
        try {
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
            while (true) {
                val terminal = terminalTurns(context, plan.nodes)
                if (terminal.isNotEmpty()) return finishCollect(plan.groupId, terminal, false)
                if (System.currentTimeMillis() >= deadline) {
                    // 到点前再判定一次：回执可能正好在上一轮检查之后形成
                    val last = terminalTurns(context, plan.nodes)
                    return if (last.isNotEmpty()) {
                        finishCollect(plan.groupId, last, true)
                    } else {
                        FileLog.log(
                            TAG,
                            "collect 超时：" + (plan.groupId?.take(8) ?: "-") + "…，统筹者 " + coordinator +
                                "，目标 " + plan.nodes.joinToString("、") { it.sessionId } + "，均未到终态"
                        )
                        PeerCollectResult.TimedOut(plan.groupId)
                    }
                }
                delay(POLL_MS)
            }
        } finally {
            synchronized(lock) {
                groupOfCoordinator(coordinator)?.let {
                    if (it.collecting > 0) it.collecting--
                    touch(it)
                }
            }
        }
    }

    /**
     * 组内明文投递的拒绝理由；允许时返回 null。
     *
     * 统筹者向成员下发任务不算明文互发；成员向统筹者或其他成员发送正文一律拒绝。
     */
    fun groupPrivateReason(sender: String, target: String): String? = synchronized(lock) {
        val group = groupOf(sender) ?: return null
        if (sender == group.coordinator) return null
        val inGroup = target == group.coordinator || group.members.contains(target)
        if (!inGroup) return null
        FileLog.log(TAG, "组内明文拒绝：成员 " + sender + " 试图向 " + target + " 发送明文（组 " + group.id.take(8) + "…）")
        "[E_GROUP_PRIVATE] 协同组内不得直接发送明文消息：组内结果由统筹者按节点 collect 获取，" +
            "不要直接发正文。目标会话：" + target
    }

    /** 解析 collect/peek 的目标与节点；缺省目标为组内成员，缺省节点为最近一次投递节点。 */
    private fun resolvePlan(
        context: Context,
        coordinator: String,
        targets: List<String>,
        nodes: List<PeerNode>
    ): PeerPlan {
        val all = SessionStore.load(context).associateBy { it.id }
        return synchronized(lock) {
            cleanupStale()
            val group = groupOfCoordinator(coordinator)
            group?.let { touch(it) }
            val requested = LinkedHashSet<String>()
            if (targets.isNotEmpty()) {
                for (id in targets) {
                    if (!all.containsKey(id)) {
                        return@synchronized PeerPlan.Failed("[E_TARGET_NOT_FOUND] 没有这个会话：" + id)
                    }
                    requested.add(id)
                }
            } else if (nodes.isNotEmpty()) {
                for (node in nodes) requested.add(node.sessionId)
            } else if (group != null) {
                requested.addAll(group.members)
            } else {
                return@synchronized PeerPlan.Failed(NO_GROUP)
            }
            val explicit = HashMap<String, Long>()
            for (node in nodes) explicit[node.sessionId] = node.at
            val resolved = ArrayList<PeerNode>(requested.size)
            val missing = ArrayList<String>()
            for (id in requested) {
                val at = explicit[id] ?: lastNodes[cursorKey(coordinator, id)]
                if (at == null) missing.add(id) else resolved.add(PeerNode(id, at))
            }
            when {
                missing.isNotEmpty() -> PeerPlan.Failed(
                    "[E_NO_NODE] 目标会话没有可用的投递节点，先向它发送消息取得节点，" +
                        "或在 nodes 中显式给出：" + missing.joinToString("、")
                )

                resolved.isEmpty() -> PeerPlan.Failed(NO_GROUP)
                else -> PeerPlan.Ok(group?.id, resolved)
            }
        }
    }

    /** 一次判定所有目标，只保留终态。 */
    private fun terminalTurns(context: Context, nodes: List<PeerNode>): List<PeerTurn> {
        val all = SessionStore.load(context).associateBy { it.id }
        return nodes.map { judge(all[it.sessionId], it) }.filter { it.status != PEER_RUNNING }
    }

    /** 收下终态：移出这些成员，名单为空即解散组。 */
    private fun finishCollect(
        groupId: String?,
        turns: List<PeerTurn>,
        timedOut: Boolean
    ): PeerCollectResult.Ok {
        var remaining: List<String> = emptyList()
        var dissolved = false
        synchronized(lock) {
            val group = groupId?.let { groups[it] }
            if (group == null) {
                // 组在等待期间被 end 解散：按已解散返回，但终态回执照常交回
                dissolved = groupId != null
                if (dissolved) {
                    FileLog.log(TAG, "collect 时组已被解散（组 " + groupId?.take(8) + "…），终态回执照常交回")
                }
            } else {
                for (turn in turns) group.members.remove(turn.sessionId)
                remaining = group.members.toList()
                if (group.members.isEmpty()) {
                    groups.remove(group.id)
                    dissolved = true
                    FileLog.log(
                        TAG,
                        "collect 收齐并解散：组 " + group.id.take(8) + "…，本轮收取 " +
                            turns.size + " 个（" + turns.joinToString("、") { it.sessionId + "=" + it.status } + "）"
                    )
                } else {
                    touch(group)
                    FileLog.log(
                        TAG,
                        "collect 部分收取：组 " + group.id.take(8) + "…，本轮收取 " + turns.size +
                            " 个，仍留在组内的 " + remaining.size + " 个"
                    )
                }
            }
        }
        return PeerCollectResult.Ok(groupId, turns, remaining, dissolved, timedOut)
    }

    /**
     * 判定一个节点之后的轮次。
     *
     * 只看 `createdAt > node.at` 的后缀；[SessionBusy] 是进程内锁，进程被杀后自然为空，
     * 因此不在任何地方持久化运行状态。
     */
    private fun judge(session: ChatSession?, node: PeerNode): PeerTurn {
        if (session == null) {
            return PeerTurn(
                sessionId = node.sessionId,
                status = PEER_TERMINATED,
                node = node,
                note = "目标会话已不存在，无法读取这一轮。"
            )
        }
        val busy = SessionBusy.isBusy(node.sessionId)
        val suffix = session.messages.filter { it.createdAt > node.at }
        if (suffix.isEmpty()) {
            return if (busy) {
                PeerTurn(node.sessionId, PEER_RUNNING, node)
            } else {
                PeerTurn(node.sessionId, PEER_TERMINATED, node, note = TERMINATED_NOTE)
            }
        }
        val first = suffix.first()
        if (first.role == "user" && !first.programmatic) {
            return PeerTurn(
                sessionId = node.sessionId,
                status = PEER_USER_INTERJECTED,
                node = node,
                steps = stepsOf(suffix),
                userText = first.content,
                finalText = lastAssistant(suffix).orEmpty()
            )
        }
        if (busy) {
            return PeerTurn(node.sessionId, PEER_RUNNING, node, steps = stepsOf(suffix))
        }
        val reply = lastAssistant(suffix)
        return if (reply != null) {
            PeerTurn(
                sessionId = node.sessionId,
                status = PEER_DONE,
                node = node,
                steps = stepsOf(suffix),
                reply = reply
            )
        } else {
            PeerTurn(
                sessionId = node.sessionId,
                status = PEER_TERMINATED,
                node = node,
                steps = stepsOf(suffix),
                note = TERMINATED_NOTE
            )
        }
    }

    /** 后缀里最后一条非空 assistant 正文。 */
    private fun lastAssistant(messages: List<ChatMessage>): String? =
        messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content

    /** 按发生顺序取全部工具调用；成败按 toolCallId 与该批工具结果配对，未知记为 null。 */
    private fun stepsOf(messages: List<ChatMessage>): List<PeerStep> {
        val results = HashMap<String, Boolean?>()
        for (message in messages) {
            if (message.role != "tool") continue
            message.toolCallId?.let { results[it] = message.toolOk }
        }
        val steps = ArrayList<PeerStep>()
        for (message in messages) {
            if (message.role != "assistant") continue
            for (call in message.toolCalls) {
                steps.add(PeerStep(titleOf(call), call.name, results[call.id]))
            }
        }
        return steps
    }

    /** 步骤标题与界面共用同一条推导规则；不截断：统筹者需要看到完整路径与命令。 */
    private fun titleOf(call: ToolCall): String =
        toolHeadline(call.name, call.arguments, limit = Int.MAX_VALUE)

    /** 解散超过时限且没有 collect 在等待的组。调用方必须持有 [lock]。 */
    private fun cleanupStale() {
        val now = System.currentTimeMillis()
        val iterator = groups.values.iterator()
        while (iterator.hasNext()) {
            val group = iterator.next()
            if (group.collecting == 0 && now - group.lastActivityAt >= IDLE_DISSOLVE_MS) {
                iterator.remove()
                FileLog.log(TAG, "组闲置自动解散：" + group.id.take(8) + "…（统筹者 " + group.coordinator + "）")
            }
        }
    }

    private fun touch(group: Group) {
        group.lastActivityAt = System.currentTimeMillis()
    }

    private fun snapshot(group: Group): PeerGroupInfo = PeerGroupInfo(
        id = group.id,
        coordinator = group.coordinator,
        createdAt = group.createdAt,
        members = group.members.map { id -> PeerMemberStatus(id, nodeOf(group.coordinator, id), null) }
    )

    private fun nodeOf(coordinator: String, target: String): PeerNode? =
        lastNodes[cursorKey(coordinator, target)]?.let { PeerNode(target, it) }

    private fun groupOf(sessionId: String): Group? =
        groups.values.firstOrNull { it.coordinator == sessionId || it.members.contains(sessionId) }

    private fun groupOfCoordinator(coordinator: String): Group? =
        groups.values.firstOrNull { it.coordinator == coordinator }

    private fun cursorKey(coordinator: String, target: String): String = coordinator + "|" + target

    /** 内存中的协同组；[collecting] 为正在等待的 collect 数量。 */
    private class Group(
        val id: String,
        val coordinator: String,
        val createdAt: Long,
        val members: LinkedHashSet<String>
    ) {
        var lastActivityAt: Long = createdAt
        var collecting: Int = 0
    }
}
