package com.zmf.agent.capability.tools.session.session_delete

import android.content.Context
import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.capability.runtime.ChatBridge
import com.zmf.agent.capability.context.ContextEventStore
import com.zmf.agent.capability.storage.StorageReclaimer
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.prefs.UiStateStore
import com.zmf.agent.core.store.DraftStore
import com.zmf.agent.core.store.GoalStore
import com.zmf.agent.core.store.PlanStore
import com.zmf.agent.core.store.QueueStore
import com.zmf.agent.core.store.SessionStore
import com.zmf.agent.core.store.WorkSpaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 永久删除会话：仅限已归档。 */
class SessionDeleteTool : Tool {

    override val name = "session_delete"

    override val description =
        "永久删除一条已归档会话，消息、计划、目标与上下文记录一并清除且不可恢复。" +
            "未归档的会话不得删除，先调用 session_archive。" +
            "调用前须在对话中复述后果；内置目录按引用归零删除或移入回收站；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {"id": {"type": "string", "description": "会话 id，取自 session_list，必须是已归档会话"}},
          "required": ["id"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = true

    /** 控制类工具：不写文件、不执行命令，只读档与计划模式下照常可用。 */
    override val sandboxSensitive = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val id = args.optString("id", "").trim()
        val spaces = WorkSpaceStore.load(ctx.context)
        val all = SessionStore.load(ctx.context)
        val target = all.firstOrNull { it.id == id }
            ?: return@withContext ToolResult.Error("[E_SESSION_NOT_FOUND] 没有这个会话：" + id + "；请先用 session_list 核对。")
        if (!target.archived) {
            return@withContext ToolResult.Error(
                "[E_INVALID] 只能删除已归档会话：" + id + "；请先调用 session_archive 归档后重试。"
            )
        }
        val remaining = all.filterNot { it.id == id }
        // 会话不再存在：它名下的后台任务一并终止，与界面删除路径同口径
        JobRegistry.killSession(ctx.context, id, "会话已删除")
        // D1：save 不再具备「缺失即删除」语义（逐会话 upsert），真删必须走 removeSession
        SessionStore.removeSession(ctx.context, id)
        // 界面格局键同点删除（D3）：与 SessionController.deleteSession 保持同一清理口径
        UiStateStore.forget(ctx.context, id)
        // X10：其余级联清理与界面删除路径同口径——计划/目标/上下文事件/草稿/队列/子智能体登记，
        // 缺任何一处都会留下随备份迁移的孤儿（JSONL 会话文件与 meta 随 removeSession 一并消失）
        PlanStore.clearPlans(ctx.context, id)
        GoalStore.clear(ctx.context, id)
        ContextEventStore.clear(ctx.context, id)
        DraftStore.remove(ctx.context, listOf(id))
        dropQueuedEntries(ctx.context, id)
        SubAgentRegistry.clearSession(id)
        val outcome = StorageReclaimer.reclaim(ctx.context, target.storageId, null, spaces, remaining)
        ChatBridge.notifySessionsChanged()
        val suffix = when (outcome.result) {
            StorageReclaimer.Result.TRASHED -> "；关联的内置目录已移入回收站，保留 30 天"
            StorageReclaimer.Result.DELETED_EMPTY -> "；关联的内置目录为空，已直接移除"
            else -> ""
        }
        ToolResult.Ok("已永久删除会话「" + target.title.ifBlank { "新会话" } + "」" + suffix)
    }

    /**
     * 从 `queues.json` 里摘掉该会话的待发条目（X10/P33）。
     *
     * 界面侧的内存队列由 ChatViewModel 接线在删除时一并丢弃；工具路径没有界面在手，
     * 直接对落盘文件做同键删除——core 层的 QueueStore 只认 JSON（core 不依赖 feature 的
     * QueuedMessage 类型），因此在这里按 JSON 结构处理。
     */
    private fun dropQueuedEntries(context: Context, sessionId: String) {
        runCatching {
            val root = QueueStore.load(context) ?: return
            val sessions = root.optJSONArray("sessions") ?: return
            var kept = 0
            val next = JSONArray()
            for (i in 0 until sessions.length()) {
                val obj = sessions.optJSONObject(i) ?: continue
                if (obj.optString("sessionId", "") == sessionId) continue
                next.put(obj)
                kept++
            }
            if (kept == sessions.length()) return // 没有该会话的条目，不必重写
            root.put("sessions", next)
            QueueStore.save(context, root)
            FileLog.log("queue", "会话删除，queues.json 摘除待发条目（" + sessionId + "）")
        }
    }
}
