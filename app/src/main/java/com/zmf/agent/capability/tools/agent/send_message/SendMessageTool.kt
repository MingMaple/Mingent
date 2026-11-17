package com.zmf.agent.capability.tools.agent.send_message

import com.zmf.agent.capability.port.DeliveryResult
import com.zmf.agent.capability.runtime.PeerCollab
import com.zmf.agent.capability.runtime.SessionDispatchBridge
import com.zmf.agent.capability.subagent.SubAgentDeps
import com.zmf.agent.capability.subagent.SubAgentRunner
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.ORIGIN_PEER
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * send_message 工具：两种目标，二选一。
 *
 * agent_id 指向子智能体：沿用原有上下文继续执行，同步返回新结论。
 * session_id 指向会话：经 [SessionDispatchBridge] 投递一轮，只返回受理结果与节点，不等待对方正文。
 *
 * 会话目标沿用既有校验口径；协同组内的成员不得向统筹者或其他成员发送正文，
 * 组内结果由统筹者通过 peer 按节点取回。
 */
class SendMessageTool : Tool {

    override val name = "send_message"

    override val description =
        "向已有子智能体追加一条消息并取回新结论，或向指定会话投递一条消息。" +
            "新建子智能体用 subagent 或 subagent_fork；组内取回结果用 peer，不要向组内成员发送正文。" +
            "agent_id 与 session_id 二选一；会话投递只返回受理结果与节点，同一目标 5 秒内限投 1 次、最多 2 跳；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "agent_id": {
              "type": "string",
              "description": "目标子智能体的 id，取自 subagent 或 subagent_fork 结果中 agent_id= 那一行；与 session_id 二选一"
            },
            "session_id": {
              "type": "string",
              "description": "目标会话，取会话 id 或标题的精确匹配；与 agent_id 二选一"
            },
            "message": {
              "type": "string",
              "description": "要发送的消息：追加给子智能体的要求，或投递给会话的任务正文；必填且必须自包含"
            }
          },
          "required": ["message"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val agentId = text(args, "agent_id")
        val targetSession = text(args, "session_id")
        if (agentId.isNotEmpty() == targetSession.isNotEmpty()) {
            return ToolResult.Error("[E_INVALID] agent_id 与 session_id 必须二选一：修正后重试")
        }
        val message = text(args, "message")
        if (message.isEmpty()) {
            return ToolResult.Error("[E_INVALID] message 不得为空：写明要发送的内容后重试")
        }
        if (agentId.isNotEmpty()) return resumeAgent(agentId, message, ctx)
        return withContext(Dispatchers.IO) { deliverToSession(targetSession, message, ctx) }
    }

    /** 子智能体分支：保持同步续聊，机制不变。 */
    private suspend fun resumeAgent(id: String, message: String, ctx: ToolContext): ToolResult {
        val provider = SubAgentDeps.provider?.invoke()
        val registry = SubAgentDeps.registry?.invoke()
        if (provider == null || registry == null) {
            return ToolResult.Error(
                "[E_SERVICE_UNAVAILABLE] 子智能体服务未初始化：该错误不能通过调整参数解决，请重启应用后重试"
            )
        }

        val outcome = SubAgentRunner.resume(
            context = ctx.context,
            id = id,
            text = message,
            provider = provider,
            tools = registry,
            permission = ctx.permission,
            workspaceRoot = ctx.workspaceRoot,
            sessionId = ctx.sessionId
        )
        return if (outcome.ok) ToolResult.Ok(outcome.answer) else ToolResult.Error(outcome.answer)
    }

    /** 会话分支：校验后投递，只回报受理结果与节点，不等待也不携带对方正文。 */
    private fun deliverToSession(targetRaw: String, message: String, ctx: ToolContext): ToolResult {
        PeerCollab.cleanupLegacy(ctx.context)
        val all = SessionStore.load(ctx.context)
        val target = resolve(all, targetRaw)
            ?: return ToolResult.Error("[E_TARGET_NOT_FOUND] 没有匹配的会话 id 或标题：" + targetRaw + "；请先用 session_list 核对")
        val source = ctx.sessionId.trim()
        if (source.isNotEmpty() && target.id == source) {
            return ToolResult.Error("[E_SELF] 不得向当前会话自身投递：" + target.id + "；改用其他目标会话")
        }
        if (target.archived) {
            return ToolResult.Error(
                "[E_TARGET_ARCHIVED] 目标会话已归档：" + target.id + "；先用 session_archive 传 archived=false 恢复后重试"
            )
        }
        // 协同组内不得明文互发：成员向统筹者或其他成员发送正文一律拒绝，向组外照常放行
        PeerCollab.groupPrivateReason(source, target.id)?.let { return ToolResult.Error(it) }

        val hop = nextHop(all, source)
        if (hop > MAX_HOP) {
            return ToolResult.Error("[E_HOP_LIMIT] 会话投递最多 " + MAX_HOP + " 跳，本次为第 " + hop + " 跳：不再转发")
        }
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value >= RATE_LIMIT_MS }
        val last = recent[target.id]
        if (last != null && now - last < RATE_LIMIT_MS) {
            return ToolResult.Error("[E_RATE_LIMITED] 同一目标 5 秒内不得重复投递：" + target.id + "；等待 5 秒后重试")
        }

        return when (val result = SessionDispatchBridge.deliver(
            sessionId = target.id,
            text = message,
            from = source.ifEmpty { UNKNOWN_SOURCE },
            hop = hop
        )) {
            is DeliveryResult.Accepted -> {
                recent[target.id] = System.currentTimeMillis()
                // 节点取自实际落盘的 user 消息时间，供 peer 的缺省节点使用
                if (source.isNotEmpty()) PeerCollab.noteDelivered(source, target.id, result.at)
                ToolResult.Ok(
                    JSONObject()
                        .put("session_id", target.id)
                        .put("accepted", true)
                        .put(
                            "node",
                            JSONObject()
                                .put("session_id", target.id)
                                .put("at", result.at)
                        )
                        .toString()
                )
            }

            is DeliveryResult.Busy ->
                ToolResult.Error("[E_BUSY] 目标会话正在执行其他回合：" + target.id + "；等其当前回合结束后重试")

            is DeliveryResult.Failed -> ToolResult.Error(failureText(result.reason))
        }
    }

    /** 先按 id 精确匹配，再按标题精确匹配；同名标题取最近更新的一条。 */
    private fun resolve(all: List<ChatSession>, raw: String): ChatSession? =
        all.firstOrNull { it.id == raw }
            ?: all.filter { it.title.isNotBlank() && it.title == raw }.maxByOrNull { it.updatedAt }

    /**
     * 本次投递的跳数。
     *
     * 仅当当前会话最近一条 user 消息是 peer 投递时才递增：用户手动发起的后续回合重新从首跳计。
     */
    private fun nextHop(all: List<ChatSession>, sessionId: String): Int {
        if (sessionId.isEmpty()) return 1
        val session = all.firstOrNull { it.id == sessionId } ?: return 1
        val lastUser = session.messages.lastOrNull { it.role == "user" } ?: return 1
        if (lastUser.origin != ORIGIN_PEER) return 1
        val inbound = HOP_PATTERN.find(lastUser.content)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return (inbound ?: 0) + 1
    }

    /** 失败文案：仅补全端口未装配时的说明，其余错误码由投递方给出。 */
    private fun failureText(reason: String): String =
        if (reason == "E_NO_DISPATCH") {
            "[E_NO_DISPATCH] 会话投递端口尚未装配：该错误不能通过调整参数解决，请重启应用后重试"
        } else {
            reason
        }

    /**
     * 读取字符串参数：缺省、显式 null 与字符串 "null" 一律当空串。
     *
     * org.json 的 optString 遇到 JSON null 会返回字符串 "null"，直接参与判断会误判成有值。
     */
    private fun text(args: JSONObject, key: String): String {
        if (!args.has(key) || args.isNull(key)) return ""
        val value = args.optString(key, "").trim()
        return if (value == "null") "" else value
    }

    private companion object {
        const val MAX_HOP = 2
        const val RATE_LIMIT_MS = 5_000L
        const val UNKNOWN_SOURCE = "unknown"

        val HOP_PATTERN = Regex("hop=(\\d+)")

        /** 各目标最近一次成功受理的时刻：用于拒绝 5 秒内的重复投递。 */
        val recent = ConcurrentHashMap<String, Long>()
    }
}
