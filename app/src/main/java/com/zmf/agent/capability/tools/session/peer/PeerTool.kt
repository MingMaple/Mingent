package com.zmf.agent.capability.tools.session.peer

import com.zmf.agent.capability.runtime.PeerCollab
import com.zmf.agent.capability.runtime.PeerCollectResult
import com.zmf.agent.capability.runtime.PeerGroupInfo
import com.zmf.agent.capability.runtime.PeerGroupResult
import com.zmf.agent.capability.runtime.PeerMemberStatus
import com.zmf.agent.capability.runtime.PeerNode
import com.zmf.agent.capability.runtime.PeerPeekResult
import com.zmf.agent.capability.runtime.PeerStatusResult
import com.zmf.agent.capability.runtime.PeerStep
import com.zmf.agent.capability.runtime.PeerTurn
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.ChatSession
import com.zmf.agent.core.store.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话协同工具：一个入口按 action 分流。
 *
 * 协同以节点为中心：send_message 投递成功返回节点，collect/peek 按节点读取目标会话的落盘消息，
 * 结合进程内的 SessionBusy 判定轮次状态。显式给出节点时不需要预先建组；
 * 组只用于补全省略的 targets 与 nodes，并限制组内明文互发。
 */
class PeerTool : Tool {

    override val name = "peer"

    override val description =
        "跨会话协同：按 action 建立或解散协同组、等待或查看成员轮次、查看组状态。" +
            "没有协同组时须在 nodes 中显式给出节点；一个会话只能属于一个组，成员间不得明文互发。" +
            "节点取自 send_message 受理回执，进程重启后仍有效；组仅存内存，30 分钟无活动自动解散；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["collab", "collect", "status", "peek"],
              "description": "必填动作：collab 管理协同组，collect 等待成员轮次结束，peek 查看当前状态且不等待，status 查看组与成员"
            },
            "op": {
              "type": "string",
              "enum": ["start", "add", "remove", "end"],
              "description": "仅 action=collab 时使用：start 建立协同组，add 增加成员，remove 移除成员，end 解散协同组"
            },
            "sessions": {
              "type": "array",
              "items": {"type": "string"},
              "description": "collab 的 start、add、remove 所需成员会话，元素为会话 id 或标题的精确匹配；end 不使用"
            },
            "targets": {
              "type": "array",
              "items": {"type": "string"},
              "description": "collect、peek 的目标会话 id 列表；省略时取组内成员"
            },
            "nodes": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "session_id": {"type": "string", "description": "目标会话 id，取自 send_message 回执中的 node.session_id"},
                  "at": {"type": "integer", "description": "节点时间戳，单位毫秒，取 send_message 回执中的 node.at，必须为非负整数"}
                },
                "required": ["session_id", "at"]
              },
              "description": "collect、peek 的投递节点数组；省略时取每个目标最近一次 send_message 成功投递的节点"
            },
            "timeout_seconds": {
              "type": "integer",
              "description": "collect 单次等待上限，单位秒，范围 120–3600，默认 600"
            }
          },
          "required": ["action"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    /** 控制类工具：不写文件、不执行命令，只读档与计划模式下照常可用。 */
    override val sandboxSensitive = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val action = args.optString("action", "").trim()
            if (action != ACTION_COLLAB && action != ACTION_COLLECT &&
                action != ACTION_STATUS && action != ACTION_PEEK
            ) {
                return@withContext ToolResult.Error(
                    "[E_INVALID] action 只能取 " + ACTION_COLLAB + "、" + ACTION_COLLECT +
                        "、" + ACTION_STATUS + "、" + ACTION_PEEK + "，请按参数说明重新填写。"
                )
            }
            val coordinator = ctx.sessionId.trim()
            if (coordinator.isEmpty()) {
                return@withContext ToolResult.Error("[E_INVALID] 当前会话没有 id，无法执行协同动作；请在已绑定会话的界面中调用。")
            }
            when (action) {
                ACTION_COLLAB -> collab(args, ctx, coordinator)
                ACTION_COLLECT -> collect(args, ctx, coordinator)
                ACTION_STATUS -> status(ctx, coordinator)
                else -> peek(args, ctx, coordinator)
            }
        }

    /** collab：按 op 建立、增补、移除成员或解散协同组。 */
    private fun collab(args: JSONObject, ctx: ToolContext, coordinator: String): ToolResult {
        val op = args.optString("op", "").trim()
        if (op != OP_START && op != OP_ADD && op != OP_REMOVE && op != OP_END) {
            return ToolResult.Error(
                "[E_INVALID] collab 的 op 只能取 " + OP_START + "、" + OP_ADD + "、" + OP_REMOVE + "、" + OP_END + "，请补全后重试。"
            )
        }
        val all = SessionStore.load(ctx.context)
        if (op == OP_END) {
            return when (val result = PeerCollab.end(ctx.context, coordinator)) {
                is PeerGroupResult.Ok -> {
                    val released = JSONArray()
                    for (member in result.group.members) released.put(member.sessionId)
                    ToolResult.Ok(
                        JSONObject()
                            .put("group_id", result.group.id)
                            .put("dissolved", true)
                            .put("released_members", released)
                            .put("coordinator", result.group.coordinator)
                            .put("members", groupMembers(result.group, all))
                            .put("note", "协同组已解散，成员恢复自由。")
                            .toString()
                    )
                }

                is PeerGroupResult.Failed -> ToolResult.Error(result.text)
            }
        }

        val raw = parseStrings(args, "sessions")
        if (raw.isEmpty()) {
            return ToolResult.Error("[E_INVALID] collab " + op + " 需要 sessions，请至少列出一个成员会话。")
        }
        val members = ArrayList<String>(raw.size)
        for (value in raw) {
            val session = resolve(all, value)
                ?: return ToolResult.Error(
                    "[E_TARGET_NOT_FOUND] 没有匹配的会话 id 或标题：" + value + "；请先用 session_list 核对。"
                )
            if (session.id == coordinator) {
                return ToolResult.Error("[E_SELF] 统筹者不能把自己列为成员：" + session.id + "；请移除该会话后重试。")
            }
            if (!members.contains(session.id)) members.add(session.id)
        }
        val result = when (op) {
            OP_START -> PeerCollab.start(ctx.context, coordinator, members)
            OP_ADD -> PeerCollab.add(ctx.context, coordinator, members)
            else -> PeerCollab.remove(ctx.context, coordinator, members)
        }
        return when (result) {
            is PeerGroupResult.Ok -> {
                val root = JSONObject()
                    .put("group_id", result.group.id)
                    .put("coordinator", result.group.coordinator)
                    .put("op", op)
                    .put("members", groupMembers(result.group, all))
                    .put("dissolved", result.group.members.isEmpty())
                    .put("note", PeerCollab.RESTART_NOTE)
                ToolResult.Ok(root.toString())
            }

            is PeerGroupResult.Failed -> ToolResult.Error(result.text)
        }
    }

    /** collect：按节点等待成员轮次结束，先完成的先返回，已终端的成员移出名单。 */
    private suspend fun collect(args: JSONObject, ctx: ToolContext, coordinator: String): ToolResult {
        val timeout = args.optInt("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)
        if (timeout !in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
            return ToolResult.Error(
                "[E_INVALID] timeout_seconds 必须介于 " + MIN_TIMEOUT_SECONDS + "–" +
                    MAX_TIMEOUT_SECONDS + " 秒，请调整后重试。"
            )
        }
        val targets = parseStrings(args, "targets")
        val nodes = parseNodes(args)
            ?: return ToolResult.Error(
                "[E_INVALID] nodes 的每个元素必须是 {\"session_id\":\"…\",\"at\":毫秒时间戳}，请按格式补齐。"
            )
        return when (val result = PeerCollab.collect(ctx.context, coordinator, targets, nodes, timeout)) {
            is PeerCollectResult.Ok -> {
                val replies = JSONArray()
                for (turn in result.turns) replies.put(turnJson(turn))
                val remaining = JSONArray()
                for (id in result.remaining) remaining.put(id)
                val root = JSONObject()
                    .put("timed_out", result.timedOut)
                    .put("replies", replies)
                    .put("remaining", remaining)
                    .put("dissolved", result.dissolved)
                result.groupId?.let { root.put("group_id", it) }
                if (result.dissolved) root.put("note", "成员名单已清空，协同组自动解散。")
                ToolResult.Ok(root.toString())
            }

            is PeerCollectResult.TimedOut -> ToolResult.Error(
                "[E_TIMEOUT] 已等待 " + timeout + " 秒，成员轮次尚未结束；" +
                    "可用相同节点再次 collect，或用 peek 查看当前状态。"
            )

            is PeerCollectResult.Failed -> ToolResult.Error(result.text)
        }
    }

    /** status：只读返回组、统筹者、成员的最近一次投递节点与当前状态。 */
    private fun status(ctx: ToolContext, coordinator: String): ToolResult =
        when (val result = PeerCollab.status(ctx.context, coordinator)) {
            is PeerStatusResult.Ok -> {
                val all = SessionStore.load(ctx.context)
                val members = JSONArray()
                for (member in result.members) members.put(memberJson(member, all))
                ToolResult.Ok(
                    JSONObject()
                        .put("group_id", result.groupId)
                        .put("coordinator", result.coordinator)
                        .put("members", members)
                        .put("note", PeerCollab.RESTART_NOTE)
                        .toString()
                )
            }

            is PeerStatusResult.Failed -> ToolResult.Error(result.text)
        }

    /** peek：按节点查看当前状态，不等待、不移出成员、不改变任何状态。 */
    private fun peek(args: JSONObject, ctx: ToolContext, coordinator: String): ToolResult {
        val targets = parseStrings(args, "targets")
        val nodes = parseNodes(args)
            ?: return ToolResult.Error(
                "[E_INVALID] nodes 的每个元素必须是 {\"session_id\":\"…\",\"at\":毫秒时间戳}，请按格式补齐。"
            )
        return when (val result = PeerCollab.peek(ctx.context, coordinator, targets, nodes)) {
            is PeerPeekResult.Ok -> {
                val all = SessionStore.load(ctx.context)
                val replies = JSONArray()
                for (member in result.members) replies.put(memberJson(member, all))
                val root = JSONObject()
                    .put("replies", replies)
                    .put("note", "peek 不等待、不移出成员、不改变任何状态。")
                result.groupId?.let { root.put("group_id", it) }
                ToolResult.Ok(root.toString())
            }

            is PeerPeekResult.Failed -> ToolResult.Error(result.text)
        }
    }

    /** 组内成员一览：带标题与最近一次投递节点，便于统筹者核对。 */
    private fun groupMembers(group: PeerGroupInfo, all: List<ChatSession>): JSONArray {
        val array = JSONArray()
        for (member in group.members) array.put(memberJson(member, all))
        return array
    }

    /**
     * 一个成员的当前状态。
     *
     * 有节点时按轮次状态输出；没有节点时明确写 null 与 unknown，不虚构进度。
     */
    private fun memberJson(member: PeerMemberStatus, all: List<ChatSession>): JSONObject {
        val item = JSONObject()
            .put("session_id", member.sessionId)
            .put("title", titleOf(all, member.sessionId))
        if (member.node != null) item.put("node", nodeJson(member.node)) else item.put("node", JSONObject.NULL)
        if (member.turn != null) {
            val turn = turnJson(member.turn)
            val keys = turn.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key == "session_id" || key == "node") continue
                item.put(key, turn.get(key))
            }
        } else {
            if (member.node == null) {
                item.put("status", "unknown")
                item.put("note", "尚无投递节点：先向该会话发送消息取得节点。")
            }
        }
        return item
    }

    /** 一条终态或进行中的回执；步骤不设上限、不截断。 */
    private fun turnJson(turn: PeerTurn): JSONObject {
        val item = JSONObject()
            .put("session_id", turn.sessionId)
            .put("status", turn.status)
            .put("node", nodeJson(turn.node))
            .put("steps", stepsJson(turn.steps))
        when (turn.status) {
            "done" -> item.put("reply", turn.reply)
            "terminated" -> if (turn.note.isNotEmpty()) item.put("note", turn.note)
            "user_interjected" -> item
                .put("user_text", turn.userText)
                .put("final_text", turn.finalText)
        }
        return item
    }

    private fun stepsJson(steps: List<PeerStep>): JSONArray {
        val array = JSONArray()
        for (step in steps) {
            array.put(
                JSONObject()
                    .put("title", step.title)
                    .put("tool", step.tool)
                    .put("ok", step.ok ?: JSONObject.NULL)
            )
        }
        return array
    }

    private fun nodeJson(node: PeerNode): JSONObject = JSONObject()
        .put("session_id", node.sessionId)
        .put("at", node.at)

    private fun titleOf(all: List<ChatSession>, id: String): String =
        all.firstOrNull { it.id == id && it.title.isNotBlank() }?.title ?: ""

    /** 先按 id 精确匹配，再按标题精确匹配；同名标题取最近更新的一条。 */
    private fun resolve(all: List<ChatSession>, raw: String): ChatSession? =
        all.firstOrNull { it.id == raw }
            ?: all.filter { it.title.isNotBlank() && it.title == raw }.maxByOrNull { it.updatedAt }

    /** 读取字符串数组：缺字段按空数组处理，逐项去空白并丢弃空项。 */
    private fun parseStrings(args: JSONObject, key: String): List<String> {
        if (!args.has(key) || args.isNull(key)) return emptyList()
        val array = args.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i, "").trim()
            if (value.isNotEmpty()) out.add(value)
        }
        return out
    }

    /** 读取节点数组；任一元素缺 session_id 或 at 时返回 null，由调用方折算为 E_INVALID。 */
    private fun parseNodes(args: JSONObject): List<PeerNode>? {
        if (!args.has("nodes") || args.isNull("nodes")) return emptyList()
        val array = args.optJSONArray("nodes") ?: return emptyList()
        val out = ArrayList<PeerNode>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: return null
            val sessionId = item.optString("session_id", "").trim()
            if (sessionId.isEmpty() || !item.has("at")) return null
            val at = item.optLong("at", -1L)
            if (at < 0L) return null
            out.add(PeerNode(sessionId, at))
        }
        return out
    }

    private companion object {
        const val ACTION_COLLAB = "collab"
        const val ACTION_COLLECT = "collect"
        const val ACTION_STATUS = "status"
        const val ACTION_PEEK = "peek"
        const val OP_START = "start"
        const val OP_ADD = "add"
        const val OP_REMOVE = "remove"
        const val OP_END = "end"
        const val DEFAULT_TIMEOUT_SECONDS = 600
        const val MIN_TIMEOUT_SECONDS = 120
        const val MAX_TIMEOUT_SECONDS = 3600
    }
}
